package com.brushwork.paint.tools.select

import android.graphics.Canvas
import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.SnapGuide
import kotlinx.serialization.builtins.serializer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** How the lasso outline is drawn. */
enum class LassoKind(val label: String) {
    FREEHAND("Freehand"),
    POLYGON("Polygon"),
    CURVE("Curve"),
}

/**
 * Lasso selection. Freehand: drag around the area. Polygon: tap the corners; tapping near the
 * first corner (or ✓) closes the shape, ✕ discards it. While the polygon is open every corner
 * can be dragged to a new place, and undo / redo (the app's buttons and two-finger tap too)
 * take back / bring back one corner edit at a time. Curve: tap points and the outline runs
 * smoothly through them ([LassoCurve]). A plain tap in "New" mode deselects (freehand).
 *
 * "Snap to objects" (the app-wide setting), polygon and curve modes: a new corner / point and a
 * dragged one (past the touch slop) snap per axis to the canvas, other layers' content bounds,
 * the lines drawn in layers (Table filter lines...), shape vertices and the other corners, and on
 * axes that didn't snap to the square grid when grid snapping is on; the existing selection is a
 * target only in add / subtract / intersect modes. Freehand strokes don't snap. Off: exactly as
 * before.
 */
class LassoTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.LASSO

    var settings: LassoSettings by PersistedOption(controller.settings, "select.lasso", LassoSettings.serializer(), LassoSettings())
    var mode by mutableStateOf(SelectionMode.REPLACE)

    /** Curve mode is on (persisted on its own; it wins over [LassoSettings.polygon]). */
    private var curveMode: Boolean by PersistedOption(controller.settings, "select.lasso.curve", Boolean.serializer(), false)

    /** Freehand, polygon or curve (Compose state). */
    val kind: LassoKind
        get() = when {
            curveMode -> LassoKind.CURVE
            settings.polygon -> LassoKind.POLYGON
            else -> LassoKind.FREEHAND
        }

    /** The points of the curve mode (Compose state; the options strip edits them). */
    val curve = LassoCurve(controller, snapToSelection = { snapsToSelection }) { commit() }

    /**
     * The existing selection is a snap target only when the new outline is combined with it
     * (add / subtract / intersect); in "New" mode it is being replaced.
     */
    private val snapsToSelection: Boolean get() = mode != SelectionMode.REPLACE

    /** Snapping of polygon corners (the curve mode has its own). */
    private val snap = controller.newSnapSession()

    /** Guides shown right now (document px); empty when nothing is aligned. */
    internal val activeGuides: List<SnapGuide> get() = if (kind == LassoKind.CURVE) curve.activeGuides else snap.guides

    /** Committed polygon corners (Compose state so ✓/✕ appear). */
    var vertexCount by mutableIntStateOf(0)
        private set

    /** Corner edits [redoStep] can bring back (Compose state; the in-tool redo button). */
    var redoCount by mutableIntStateOf(0)
        private set

    /** True while the selection is being rasterized. */
    var busy by mutableStateOf(false)
        private set

    override val hasPendingWork: Boolean
        get() = if (kind == LassoKind.CURVE) curve.count > 0 else settings.polygon && vertexCount > 0

    // The app's redo only reaches the tool while the polygon / curve is pending (see EditorController.redo).
    override val canRedoStep: Boolean
        get() = (if (kind == LassoKind.CURVE) curve.redoCount else redoCount) > 0 && hasPendingWork

    private val stroke = PointList()
    private val vertices = PointList()
    /** Corner lists before each edit (for [undoStep]) and after each undone one (for [redoStep]). */
    private val history = ArrayDeque<FloatArray>()
    private val redo = ArrayDeque<FloatArray>()
    private var dragging = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorDown = false
    /** Corner being dragged by the current touch (-1: the touch places a new corner). */
    private var grabbed = -1
    private var grabMoved = false
    private var downX = 0f
    private var downY = 0f
    private var grabStartX = 0f
    private var grabStartY = 0f
    private var gestureRedo: List<FloatArray> = emptyList()
    /** Shape shown until its selection has been applied (avoids a blank frame). */
    private var committing: Path? = null
    private val screenPath = Path()
    private val mapped = FloatArray(2)

    /** Switches between freehand and polygon mode (drops an unfinished polygon or curve). */
    fun setPolygonMode(polygon: Boolean) = setKind(if (polygon) LassoKind.POLYGON else LassoKind.FREEHAND)

    /** Switches to freehand, polygon or curve mode (drops an unfinished polygon or curve). */
    fun setKind(kind: LassoKind) {
        if (kind == this.kind) return
        discard()
        curveMode = kind == LassoKind.CURVE
        settings = settings.copy(polygon = kind == LassoKind.POLYGON)
    }

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        if (busy) return
        if (kind == LassoKind.CURVE) { curve.onDown(p); return }
        if (settings.polygon) {
            cursorX = p.x; cursorY = p.y; cursorDown = true
            downX = p.x; downY = p.y
            grabMoved = false
            gestureRedo = redo.toList()
            grabbed = nearestVertex(p.x, p.y, docLength(GRAB_DP))
            if (grabbed >= 0) { grabStartX = vertices.x(grabbed); grabStartY = vertices.y(grabbed) }
            beginSnap(except = grabbed)
            // A new corner snaps as soon as the finger lands (the rubber band shows where).
            if (grabbed < 0) setCursor(snapped(p.x, p.y))
        } else {
            stroke.clear()
            stroke.add(p.x, p.y)
            dragging = true
        }
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        if (kind == LassoKind.CURVE) { curve.onMove(p); return }
        if (settings.polygon) {
            if (!cursorDown) return
            cursorX = p.x; cursorY = p.y
            if (grabbed >= 0) {
                // A touch that starts on a corner and moves drags that corner.
                if (!grabMoved) {
                    if (hypot(p.x - downX, p.y - downY) < docLength(SLOP_DP)) return
                    pushHistory()
                    grabMoved = true
                }
                val q = snapped(p.x, p.y)
                vertices.set(grabbed, q.x, q.y)
            } else {
                setCursor(snapped(p.x, p.y))
            }
        } else {
            if (!dragging) return
            val minStep = docLength(1.5f)
            if (hypot(p.x - stroke.lastX, p.y - stroke.lastY) < minStep) return
            stroke.add(p.x, p.y)
        }
        controller.invalidateOverlay()
    }

    override fun onUp(p: ToolPoint) {
        if (kind == LassoKind.CURVE) { curve.onUp(p); return }
        if (settings.polygon) {
            if (!cursorDown) return
            cursorDown = false
            val grab = grabbed
            grabbed = -1
            // A moved corner and a new one land where they snap (a tap on a corner never moves it).
            val q = snapped(p.x, p.y)
            snap.end()
            if (grab >= 0 && grabMoved) {
                vertices.set(grab, q.x, q.y)
                controller.invalidateOverlay()
                return
            }
            when {
                // (Where the finger is, not where it snapped: tapping the first corner closes.)
                vertices.size >= 3 && hypot(p.x - vertices.x(0), p.y - vertices.y(0)) <= docLength(CLOSE_DP) -> commit()
                // A corner snapped onto the last one isn't added twice.
                vertices.size == 0 || hypot(q.x - vertices.lastX, q.y - vertices.lastY) > docLength(3f) -> {
                    pushHistory()
                    vertices.add(q.x, q.y)
                    vertexCount = vertices.size
                }
            }
        } else {
            if (!dragging) return
            dragging = false
            stroke.add(p.x, p.y)
            val tap = stroke.extent() < docLength(8f)
            if (tap || stroke.size < 3) {
                stroke.clear()
                if (tap && mode == SelectionMode.REPLACE && controller.selection != null) controller.deselect()
            } else {
                val path = stroke.toPath()
                stroke.clear()
                apply(path)
            }
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        // Only the current gesture is dropped; committed polygon corners stay (a dragged corner
        // goes back to where it was).
        if (cursorDown && grabbed >= 0 && grabMoved && grabbed < vertices.size) {
            vertices.set(grabbed, grabStartX, grabStartY)
            history.removeLastOrNull()
            redo.clear(); redo.addAll(gestureRedo)
            redoCount = redo.size
        }
        grabbed = -1
        grabMoved = false
        dragging = false
        cursorDown = false
        snap.end()
        stroke.clear()
        curve.onCancel()
        controller.invalidateOverlay()
    }

    /** Curve mode: a long press on a point selects it (its sharp / smooth / delete actions). */
    override fun onLongPress(p: ToolPoint): Boolean = kind == LassoKind.CURVE && curve.onLongPress(p)

    private fun docLength(dp: Float): Float {
        val t = controller.viewTransform
        return t.screenToDocLength(t.dp(dp))
    }

    /**
     * Starts snapping for this polygon touch: the corners (all but [except]) are point targets as
     * they are now (a copy, so the dragged corner never becomes its own target).
     */
    private fun beginSnap(except: Int) {
        val others = ArrayList<Vec2>(vertices.size)
        for (i in 0 until vertices.size) if (i != except) others += Vec2(vertices.x(i), vertices.y(i))
        snap.begin(includeSelection = snapsToSelection) { pointLines(others, CORNER_LABEL) }
    }

    /**
     * A corner at ([x], [y]) snapped while "Snap to objects" is on (objects, the other corners,
     * then the square grid on axes that didn't snap when grid snapping is on); unchanged when off.
     */
    private fun snapped(x: Float, y: Float): Vec2 = snap.snapPointWhenOn(Vec2(x, y), controller.snapping)

    private fun setCursor(q: Vec2) {
        cursorX = q.x
        cursorY = q.y
    }

    private fun nearestVertex(x: Float, y: Float, tol: Float): Int {
        var best = -1
        var bestD = tol
        // Later corners win ties (the newest one is grabbed when corners overlap).
        for (i in vertices.size - 1 downTo 0) {
            val d = hypot(vertices.x(i) - x, vertices.y(i) - y)
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }

    // ------------------------------------------------------------------ corner history

    /** Saves the corners before an edit (and drops the redo steps: this is a new edit). */
    private fun pushHistory() {
        history.addLast(vertices.toArray())
        while (history.size > MAX_HISTORY) history.removeFirst()
        if (redo.isNotEmpty()) { redo.clear(); redoCount = 0 }
    }

    /** Takes back the last corner / point edit (placing or moving a corner). */
    override fun undoStep(): Boolean {
        if (kind == LassoKind.CURVE) return curve.undoStep()
        val prev = history.removeLastOrNull() ?: return false
        redo.addLast(vertices.toArray())
        redoCount = redo.size
        setVertices(prev)
        return true
    }

    /** Brings back the corner / point edit last taken back by [undoStep]. */
    override fun redoStep(): Boolean {
        if (kind == LassoKind.CURVE) return curve.redoStep()
        val next = redo.removeLastOrNull() ?: return false
        redoCount = redo.size
        history.addLast(vertices.toArray())
        setVertices(next)
        return true
    }

    /** Position of polygon corner [i] (document px). */
    fun corner(i: Int): Pair<Float, Float> = vertices.x(i) to vertices.y(i)

    /** True when there is a corner / point to take back (Compose state through [vertexCount] / the curve points). */
    override val canUndoStep: Boolean get() = if (kind == LassoKind.CURVE) curve.count > 0 else vertexCount > 0

    /**
     * The in-tool undo button: the last corner / point edit, or the whole polygon / curve if its
     * history ran out.
     */
    fun undoLastCorner() {
        if (!undoStep()) discard()
    }

    private fun setVertices(xy: FloatArray) {
        vertices.setAll(xy)
        vertexCount = vertices.size
        controller.invalidateOverlay()
    }

    private fun clearHistory() {
        history.clear()
        redo.clear()
        redoCount = 0
    }

    // ------------------------------------------------------------------ pending polygon

    override fun commit() {
        if (kind == LassoKind.CURVE) {
            val path = curve.closedPath()
            if (path == null) { discard(); return }
            curve.clear()
            apply(path)
            controller.invalidateOverlay()
            return
        }
        if (vertices.size >= 3) {
            val path = vertices.toPath()
            vertices.clear()
            vertexCount = 0
            clearHistory()
            apply(path)
        } else {
            discard()
        }
        controller.invalidateOverlay()
    }

    override fun discard() {
        vertices.clear()
        vertexCount = 0
        clearHistory()
        cursorDown = false
        grabbed = -1
        snap.end()
        curve.clear()
        controller.invalidateOverlay()
    }

    override fun onDeactivate() {
        curve.onDeactivate()
        snap.end()
        super.onDeactivate()
    }

    override fun onDispose() = curve.onDeactivate()

    private fun apply(path: Path) {
        val docW = controller.doc.width; val docH = controller.doc.height
        val aa = settings.antiAlias
        val work = Path(path) // the background job gets its own copy
        committing = path
        busy = true
        SelectionJobs.applyAsync(controller, "Lasso", mode, "Selecting…", onFinished = {
            busy = false
            committing = null
            controller.invalidateOverlay()
        }, toObjects = true) { cancelled ->
            if (cancelled()) null else rasterize(work, docW, docH, aa)
        }
    }

    // ------------------------------------------------------------------ overlay

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        committing?.let { SelectionOverlay.drawDocPath(canvas, t, it) }
        if (dragging && stroke.size > 1) {
            stroke.toScreenPath(t, screenPath, close = true)
            SelectionOverlay.drawScreenPath(canvas, t, screenPath)
        }
        if (settings.polygon && (vertices.size > 0 || cursorDown)) {
            vertices.toScreenPath(t, screenPath, close = false)
            // Rubber band to the finger while it is placing a new corner.
            if (cursorDown && !(grabbed >= 0 && grabMoved)) {
                map(t, cursorX, cursorY)
                if (vertices.size == 0) screenPath.moveTo(mapped[0], mapped[1]) else screenPath.lineTo(mapped[0], mapped[1])
            }
            SelectionOverlay.drawScreenPath(canvas, t, screenPath)
            val dragged = if (cursorDown && grabMoved) grabbed else -1
            for (i in 0 until vertices.size) {
                map(t, vertices.x(i), vertices.y(i))
                SelectionOverlay.drawVertex(canvas, t, mapped[0], mapped[1], highlighted = i == dragged || (i == 0 && vertices.size >= 3))
            }
            // Smart guides of the new / dragged corner (labels away from it).
            if (cursorDown) {
                val at = if (dragged in 0 until vertices.size) Vec2(vertices.x(dragged), vertices.y(dragged)) else Vec2(cursorX, cursorY)
                snap.draw(canvas, t, pointBox(at))
            }
        }
        if (kind == LassoKind.CURVE) curve.drawOverlay(canvas, t)
    }

    private fun map(t: ViewTransform, x: Float, y: Float) {
        mapped[0] = x; mapped[1] = y
        t.matrix.mapPoints(mapped)
    }

    companion object {
        /** Grab radius of polygon corners (screen dp): generous for fingers. */
        private const val GRAB_DP = 24f
        /** A corner touch must move this far (dp) before it drags the corner. */
        private const val SLOP_DP = 6f
        /** Tapping this close (dp) to the first corner closes the polygon. */
        private const val CLOSE_DP = 22f
        /** Guide label of the other corners. */
        private const val CORNER_LABEL = "Corner"
        private const val MAX_HISTORY = 200

        /** Rasterizes a closed lasso path into a document-sized selection (blocking; any thread). */
        internal fun rasterize(path: Path, docW: Int, docH: Int, antiAlias: Boolean): Selection =
            Selection.fromPath(path, docW, docH, antiAlias)

        /** Closed polygon path through [points] (x0, y0, x1, y1, ...) in document coordinates. */
        internal fun polygonPath(points: FloatArray): Path = PointList().apply {
            var k = 0
            while (k + 1 < points.size) { add(points[k], points[k + 1]); k += 2 }
        }.toPath()
    }
}

/** Growable list of float points (no boxing). */
internal class PointList {
    private var data = FloatArray(256)
    /** Reused for screen mapping so drawing a long stroke doesn't allocate every frame. */
    private var scratch = FloatArray(0)
    var size = 0
        private set

    fun add(x: Float, y: Float) {
        if (2 * size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[2 * size] = x
        data[2 * size + 1] = y
        size++
    }

    /** Moves point [i]. */
    fun set(i: Int, x: Float, y: Float) {
        data[2 * i] = x
        data[2 * i + 1] = y
    }

    /** The points as x0, y0, x1, y1, ... (a copy). */
    fun toArray(): FloatArray = data.copyOf(2 * size)

    /** Replaces all points with [xy] (x0, y0, x1, y1, ...). */
    fun setAll(xy: FloatArray) {
        if (xy.size > data.size) data = xy.copyOf(max(256, xy.size))
        else xy.copyInto(data)
        size = xy.size / 2
    }

    fun clear() { size = 0 }
    fun x(i: Int): Float = data[2 * i]
    fun y(i: Int): Float = data[2 * i + 1]
    val lastX: Float get() = data[2 * size - 2]
    val lastY: Float get() = data[2 * size - 1]

    /** Larger side of the bounding box. */
    fun extent(): Float {
        if (size == 0) return 0f
        var minX = data[0]; var maxX = data[0]; var minY = data[1]; var maxY = data[1]
        for (i in 1 until size) {
            minX = min(minX, x(i)); maxX = max(maxX, x(i)); minY = min(minY, y(i)); maxY = max(maxY, y(i))
        }
        return max(maxX - minX, maxY - minY)
    }

    /** Closed document-space path. */
    fun toPath(): Path {
        val p = Path()
        if (size == 0) return p
        p.moveTo(x(0), y(0))
        for (i in 1 until size) p.lineTo(x(i), y(i))
        p.close()
        return p
    }

    /** Maps the points to screen space into [out]. */
    fun toScreenPath(t: ViewTransform, out: Path, close: Boolean) {
        out.reset()
        if (size == 0) return
        if (scratch.size < 2 * size) scratch = FloatArray(data.size)
        val pts = scratch
        t.matrix.mapPoints(pts, 0, data, 0, size)
        out.moveTo(pts[0], pts[1])
        var k = 2
        while (k < 2 * size) { out.lineTo(pts[k], pts[k + 1]); k += 2 }
        if (close) out.close()
    }
}
