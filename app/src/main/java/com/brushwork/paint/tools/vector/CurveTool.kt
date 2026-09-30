package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Path
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** How a committed curve is stroked. */
@Serializable
enum class CurveStroke(val label: String) {
    /** Drives the last painting tool (brush / smudge / blur) along the path. */
    BRUSH("Current brush"),
    /** A plain anti-aliased line of a fixed width. */
    PLAIN("Plain line"),
    NONE("No stroke"),
}

/** Persisted options of the curve / polyline tools. Lengths are document pixels. */
@Serializable
data class CurveSettings(
    val closed: Boolean = false,
    /** 0 = Catmull-Rom, 1 = straight segments. */
    val tension: Float = 0f,
    val stroke: CurveStroke = CurveStroke.BRUSH,
    val plainWidth: Float = 6f,
    val fill: Boolean = false,
    /** Fill color, or null to follow the main drawing color. */
    val fillColor: Int? = null,
    /** Brush strokes fade in/out through a pressure ramp. */
    val taper: Boolean = false,
    /** Length of each taper as a percentage of the path length. */
    val taperPercent: Float = 20f,
    val unit: LengthUnit = LengthUnit.PX,
    val nudgeStepPx: Float = 1f,
) {
    /** Clamps every value to its supported range; non-finite values are taken from [fallback]. */
    fun sanitized(fallback: CurveSettings = DEFAULT) = copy(
        tension = tension.finiteOr(fallback.tension).coerceIn(0f, 1f),
        plainWidth = plainWidth.finiteOr(fallback.plainWidth).coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE),
        taperPercent = taperPercent.finiteOr(fallback.taperPercent).coerceIn(1f, 50f),
        nudgeStepPx = nudgeStepPx.finiteOr(fallback.nudgeStepPx).coerceIn(0.01f, ShapeSettings.MAX_LENGTH),
    )

    companion object {
        private val DEFAULT = CurveSettings()
    }
}

/**
 * Bezier curve tool, or polyline tool when [polyline] (all corners sharp). Tap to add anchors
 * (tapping near the path inserts one), drag any anchor or tangent handle to edit it, tap /
 * long-press an anchor to select it for sharp / smooth / delete / numeric editing. Undo (the
 * app's undo button and two-finger tap included) takes back one anchor edit at a time, redo
 * brings it back. With "Current brush" the painting tool's real stroke is shown live while the
 * path is edited; ✓ paints it (plus the optional fill) as one undo step.
 */
class CurveTool(controller: EditorController, val polyline: Boolean) : Tool(controller) {
    override val id = if (polyline) ToolId.POLYLINE else ToolId.CURVE

    /** Current options (Compose state); change them with [update]. */
    var settings by mutableStateOf(loadSettings())
        private set

    /** Anchors of the pending path (Compose state). */
    var anchors by mutableStateOf<List<CurveAnchor>>(emptyList())
        private set

    /** Index of the selected anchor or -1. */
    var selected by mutableIntStateOf(-1)
        private set

    /** True when [undoStep] can go back. */
    override var canUndoStep by mutableStateOf(false)
        private set

    /** Number of edits [redoStep] can bring back (Compose state; the in-tool redo button). */
    var redoCount by mutableIntStateOf(0)
        private set

    override val hasPendingWork: Boolean get() = anchors.isNotEmpty()

    // The app's redo only reaches the tool while a path is pending (see EditorController.redo).
    override val canRedoStep: Boolean get() = redoCount > 0 && anchors.isNotEmpty()

    private val history = ArrayDeque<List<CurveAnchor>>()
    private val redo = ArrayDeque<List<CurveAnchor>>()
    private var historyKey: Any? = null
    private var historyKeyTime = 0L
    private data class NumericKey(val kind: String, val index: Int)

    /** Time source for coalescing numeric edits (replaceable in tests). */
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }
    private var targetLayer: Layer? = null
    private val preview = PreviewHost(controller)
    private val brushPreview = BrushStrokePreview(controller)
    private val specOverlay = SpecOverlay()
    /** Plain items drawn in the overlay while the brush preview owns the render override (the fill). */
    private var overlaySpecs: List<VectorPaintSpec> = emptyList()
    private var observeJob: Job? = null

    private enum class Drag { NONE, ANCHOR, NEW_ANCHOR, HANDLE_IN, HANDLE_OUT, IGNORE }

    private var drag = Drag.NONE
    private var dragIndex = -1
    private var downPoint = Vec2.ZERO
    private var moved = false
    /** The current touch long-pressed an anchor and selected it (lifting keeps it selected). */
    private var longPressed = false
    private var gestureStart: List<CurveAnchor> = emptyList()
    private var gestureSelected = -1
    private var gestureHistorySize = 0
    private var gestureRedo: List<List<CurveAnchor>> = emptyList()

    private val painter = OverlayPainter()
    private val docPath = Path()
    private val pts = FloatArray(2)

    private val tension: Float get() = if (polyline) 1f else settings.tension

    /** The current path (straight segments for the polyline tool). */
    fun path(): VectorPath = CurveGeometry.toPath(anchors, settings.closed, tension, polyline)

    // ------------------------------------------------------------------ settings

    fun update(transform: (CurveSettings) -> CurveSettings) {
        val new = transform(settings).sanitized(fallback = settings)
        if (new == settings) return
        settings = new
        runCatching { controller.settings.putObject(prefsKey, CurveSettings.serializer(), new) }
        changed()
    }

    private val prefsKey: String get() = if (polyline) "vec.polyline" else "vec.curve"

    private fun loadSettings(): CurveSettings =
        runCatching { controller.settings.getObject(prefsKey, CurveSettings.serializer()) }.getOrNull()?.sanitized() ?: CurveSettings()

    // ------------------------------------------------------------------ editing actions

    /**
     * Saves the anchors for [undoStep] (and drops the redo steps: this is a new edit).
     * Consecutive edits with the same non-null [key] that follow each other quickly (typing a
     * coordinate, holding a nudge arrow) share one step.
     */
    private fun pushHistory(key: Any? = null) {
        val now = if (key != null) clock() else 0L
        val coalesce = key != null && key == historyKey && history.isNotEmpty() && now - historyKeyTime <= COALESCE_MS
        historyKey = key
        historyKeyTime = now
        clearRedo()
        if (coalesce) return
        history.addLast(anchors)
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
    }

    private fun clearRedo() {
        if (redo.isEmpty()) return
        redo.clear()
        redoCount = 0
    }

    /**
     * Steps back one anchor edit (add, move, delete, corner change). Also used by the app's undo
     * (button / two-finger tap) so it takes back the last point instead of the whole curve.
     */
    override fun undoStep(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        redo.addLast(anchors)
        redoCount = redo.size
        historyKey = null
        restore(prev)
        canUndoStep = history.isNotEmpty()
        return true
    }

    /** Brings back the edit last taken back by [undoStep] (the app's redo, or the in-tool button). */
    override fun redoStep(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        redoCount = redo.size
        history.addLast(anchors)
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
        historyKey = null
        restore(next)
        return true
    }

    private fun restore(list: List<CurveAnchor>) {
        anchors = list
        if (selected !in list.indices) selected = -1
        if (list.isEmpty()) targetLayer = null
        else if (targetLayer == null) targetLayer = controller.doc.activeLayer
        changed()
    }

    /**
     * Appends an anchor at [p] and selects it (numeric entry). Returns false when the active
     * layer can't be edited.
     */
    fun addAnchor(p: Vec2): Boolean {
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        if (anchors.isEmpty() && !controller.checkEditable()) return false
        val lim = ShapeSettings.MAX_LENGTH
        pushHistory()
        if (targetLayer == null) targetLayer = controller.doc.activeLayer
        anchors = anchors + CurveAnchor(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim), sharp = polyline)
        selected = anchors.lastIndex
        changed()
        return true
    }

    fun select(index: Int) {
        selected = if (index in anchors.indices) index else -1
        controller.invalidateOverlay()
    }

    fun deselect() = select(-1)

    /** Makes anchor [index] a corner (sharp) or smooth. */
    fun setSharp(index: Int, sharp: Boolean) {
        val a = anchors.getOrNull(index) ?: return
        if (a.sharp == sharp && !a.hasCustomTangent) return
        pushHistory()
        replace(index, a.copy(sharp = sharp, handleIn = null, handleOut = null))
    }

    /** Drops the dragged tangent of anchor [index] and goes back to the automatic one. */
    fun resetTangent(index: Int) {
        val a = anchors.getOrNull(index) ?: return
        if (!a.hasCustomTangent) return
        pushHistory()
        replace(index, a.withAutoTangent())
    }

    fun deleteAnchor(index: Int) {
        if (index !in anchors.indices) return
        pushHistory()
        anchors = anchors.toMutableList().also { it.removeAt(index) }
        selected = -1
        if (anchors.isEmpty()) targetLayer = null
        changed()
    }

    /** Moves anchor [index] to [p] (numeric entry; edits of the same anchor share one undo step). */
    fun moveAnchor(index: Int, p: Vec2) {
        val a = anchors.getOrNull(index) ?: return
        if (!p.x.isFinite() || !p.y.isFinite()) return
        val lim = ShapeSettings.MAX_LENGTH
        val q = Vec2(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim))
        if (q == a.pos) return
        pushHistory(NumericKey("move", index))
        replace(index, a.moved(q))
    }

    /**
     * Nudges the selected anchor (or the whole path when none is selected) by the nudge step.
     * A run of nudges of the same target is one undo step.
     */
    fun nudge(dx: Int, dy: Int) {
        if (anchors.isEmpty()) return
        val step = settings.nudgeStepPx
        val d = Vec2(dx * step, dy * step)
        pushHistory(NumericKey("nudge", selected))
        anchors = if (selected in anchors.indices) {
            anchors.mapIndexed { i, a -> if (i == selected) a.moved(a.pos + d) else a }
        } else {
            anchors.map { it.moved(it.pos + d) }
        }
        changed()
    }

    private fun replace(index: Int, a: CurveAnchor) {
        anchors = anchors.toMutableList().also { it[index] = a }
        changed()
    }

    /** Bezier handles (in, out) of anchor [i] as offsets, as currently drawn. */
    fun handlesOf(i: Int): Pair<Vec2, Vec2> = CurveGeometry.handles(anchors, i, settings.closed, tension)

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        downPoint = pt
        moved = false
        longPressed = false
        gestureStart = anchors
        gestureSelected = selected
        gestureHistorySize = history.size
        gestureRedo = redo.toList()
        if (anchors.isEmpty() && !controller.checkEditable()) { drag = Drag.IGNORE; return }
        val tol = controller.docLength(HANDLE_TOUCH_DP)
        val sel = selected
        if (!polyline && sel in anchors.indices && !anchors[sel].sharp) {
            val (hIn, hOut) = handlesOf(sel)
            val a = anchors[sel].pos
            val dOut = if (hOut.length > 1e-3f) pt.distanceTo(a + hOut) else Float.MAX_VALUE
            val dIn = if (hIn.length > 1e-3f) pt.distanceTo(a + hIn) else Float.MAX_VALUE
            // The anchor itself wins when the finger is closer to it than to its handles.
            if (minOf(dOut, dIn) <= tol && minOf(dOut, dIn) < pt.distanceTo(a)) {
                drag = if (dOut <= dIn) Drag.HANDLE_OUT else Drag.HANDLE_IN
                dragIndex = sel
                return
            }
        }
        val idx = nearestAnchor(pt, tol)
        if (idx >= 0) {
            // Every existing point can be grabbed and moved at any time.
            drag = Drag.ANCHOR
            dragIndex = idx
            return
        }
        // New anchor: inserted when tapping on the path, appended otherwise.
        pushHistory()
        val hit = if (anchors.size >= 2) CurveGeometry.nearest(anchors, pt, settings.closed, tension, polyline) else null
        val list = anchors.toMutableList()
        if (hit != null && hit.distance <= tol * 0.6f) {
            dragIndex = hit.segment + 1
            val q = controller.snapToGrid(hit.point)
            list.add(dragIndex, CurveAnchor(q.x, q.y, sharp = polyline))
        } else {
            val q = controller.snapToGrid(pt)
            list.add(CurveAnchor(q.x, q.y, sharp = polyline))
            dragIndex = list.lastIndex
        }
        if (targetLayer == null) targetLayer = controller.doc.activeLayer
        anchors = list
        selected = -1
        drag = Drag.NEW_ANCHOR
        changed()
    }

    override fun onMove(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        when (drag) {
            Drag.NONE, Drag.IGNORE -> return
            Drag.ANCHOR, Drag.NEW_ANCHOR -> {
                if (!moved && pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                if (!moved && drag == Drag.ANCHOR) pushHistory()
                moved = true
                val a = anchors.getOrNull(dragIndex) ?: return
                replace(dragIndex, a.moved(controller.snapToGrid(pt)))
            }
            Drag.HANDLE_IN, Drag.HANDLE_OUT -> {
                val a = anchors.getOrNull(dragIndex) ?: return
                if (!moved) {
                    if (pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                    pushHistory(); moved = true
                }
                val (hIn, hOut) = handlesOf(dragIndex)
                val v = pt - a.pos
                if (v.length < 1e-3f) return
                // Smooth anchor: the other handle stays collinear and keeps its length.
                replace(dragIndex, if (drag == Drag.HANDLE_OUT) {
                    a.copy(handleOut = v, handleIn = -v.normalized() * hIn.length)
                } else {
                    a.copy(handleIn = v, handleOut = -v.normalized() * hOut.length)
                })
            }
        }
    }

    override fun onUp(p: ToolPoint) {
        when (drag) {
            Drag.ANCHOR -> when {
                moved -> onMove(p)
                !longPressed -> select(if (selected == dragIndex) -1 else dragIndex)
            }
            Drag.NEW_ANCHOR -> onMove(p)
            Drag.HANDLE_IN, Drag.HANDLE_OUT -> if (moved) onMove(p)
            Drag.NONE, Drag.IGNORE -> {}
        }
        drag = Drag.NONE
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        if (drag != Drag.NONE && drag != Drag.IGNORE) {
            anchors = gestureStart
            selected = gestureSelected
            while (history.size > gestureHistorySize) history.removeLast()
            redo.clear(); redo.addAll(gestureRedo)
            redoCount = redo.size
            historyKey = null
            canUndoStep = history.isNotEmpty()
            if (anchors.isEmpty()) targetLayer = null
        }
        drag = Drag.NONE
        changed()
    }

    /**
     * A long press on an anchor selects it (its sharp / smooth / delete actions appear) and the
     * finger can still drag it; on a tangent handle it keeps the drag going. Elsewhere it is left
     * to the controller (color picking).
     */
    override fun onLongPress(p: ToolPoint): Boolean = when (drag) {
        Drag.ANCHOR -> {
            if (!moved) {
                select(dragIndex)
                longPressed = true
            }
            true
        }
        Drag.HANDLE_IN, Drag.HANDLE_OUT -> true
        else -> false
    }

    private fun nearestAnchor(p: Vec2, tol: Float): Int {
        var best = -1
        var bestD = tol
        // Later anchors win ties so the newest point is grabbed when points overlap.
        for (i in anchors.indices.reversed()) {
            val d = anchors[i].pos.distanceTo(p)
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }

    // ------------------------------------------------------------------ preview

    /** Plain items of the path: the fill (when on) and the plain line (when that is the stroke). */
    private fun buildSpecs(path: VectorPath): List<VectorPaintSpec> {
        if (anchors.size < 2) return emptyList()
        val s = settings
        val fill = if (s.fill && anchors.size >= 3) path else null
        val stroke = if (s.stroke == CurveStroke.PLAIN) path else null
        return listOfNotNull(
            VectorPaintSpec.build(fill, s.fillColor ?: controller.color, stroke, controller.color, s.plainWidth, LineCapStyle.ROUND, JoinStyle.ROUND),
        )
    }

    /** The brush stroke of [path] with the current taper settings. */
    private data class BrushGeometry(val ops: List<PathOp>, val taperFraction: Float)

    private fun brushGeometry(path: VectorPath, s: CurveSettings) =
        BrushGeometry(path.ops, if (s.taper) s.taperPercent / 100f else 0f)

    private fun brushPoints(path: VectorPath, g: BrushGeometry): List<ToolPoint> = brushStrokePoints(path, g.taperFraction)

    /**
     * Rebuilds the guide path and the preview, then redraws. A plain line / fill goes through the
     * compositor; with "Current brush" the painting tool's own unfinished stroke is the preview
     * (replayed, coalesced) and the fill is drawn in the overlay.
     */
    private fun changed() {
        if (anchors.isNotEmpty()) ensureObserving()
        val path = if (anchors.size >= 2) path() else null
        if (path != null) path.toAndroidPath(docPath) else docPath.rewind()
        val layer = targetLayer ?: controller.doc.activeLayer
        val specs = if (path != null) buildSpecs(path) else emptyList()
        if (path != null && settings.stroke == CurveStroke.BRUSH && layer === controller.doc.activeLayer) {
            preview.release()
            overlaySpecs = specs
            if (specs.isNotEmpty()) specOverlay.setBand(docPath, controller.presetFor(controller.lastPaintTool)?.size ?: 0f)
            val g = brushGeometry(path, settings)
            brushPreview.request(g) { brushPoints(path, g) }
        } else {
            brushPreview.cancel()
            overlaySpecs = emptyList()
            preview.show(layer, specs)
        }
        controller.invalidateOverlay()
    }

    /** Runs a waiting live-brush replay now (the main looper does it otherwise). */
    internal fun flushPreview() = brushPreview.flush()

    // ------------------------------------------------------------------ commit / discard

    override fun commit() {
        if (anchors.size < 2) { discard(); return }
        val layer = targetLayer ?: controller.doc.activeLayer
        if (controller.doc.indexOf(layer) < 0) { discard(); return }
        if (!controller.checkEditable(layer)) return
        val s = settings
        val path = path()
        val specs = buildSpecs(path)
        val brush = s.stroke == CurveStroke.BRUSH && layer === controller.doc.activeLayer
        val g = brushGeometry(path, s)
        resetPath()
        // Fill and brush stroke are ONE undo step, named after the tool (a plain line / fill
        // alone keeps its own name).
        val step: (String, () -> Unit) -> Unit = if (brush) controller::undoStepNamed else controller::groupUndo
        step(if (polyline) "Polyline" else "Curve") {
            if (specs.isNotEmpty()) {
                // The fill goes under the stroke, so the stroke is painted after it (smudge /
                // blur previews edit the pixels: they are restored first).
                brushPreview.cancel()
                val label = when {
                    s.stroke != CurveStroke.PLAIN -> "Fill path"
                    polyline -> "Polyline"
                    else -> "Curve"
                }
                VectorCommit.commit(controller, layer, specs, label)
            }
            if (brush) brushPreview.commit(g) { brushPoints(path, g) }
        }
        brushPreview.end()
        controller.invalidateOverlay()
    }

    override fun discard() {
        clear()
        controller.invalidateOverlay()
    }

    /** Forgets the path and its in-tool history (the brush preview is left to the caller). */
    private fun resetPath() {
        anchors = emptyList()
        selected = -1
        history.clear()
        redo.clear()
        redoCount = 0
        historyKey = null
        canUndoStep = false
        drag = Drag.NONE
        targetLayer = null
        docPath.rewind()
        overlaySpecs = emptyList()
        preview.release()
    }

    private fun clear() {
        resetPath()
        brushPreview.end()
    }

    override fun onActivate() = ensureObserving()

    /**
     * The preview depends on the main color, selection, layer props and the painting tool's
     * preset (the size / opacity sliders). Started on activation and again whenever anchors
     * exist, because some controller operations call onDeactivate without a following onActivate.
     */
    private fun ensureObserving() {
        if (observeJob?.isActive == true) return
        observeJob = controller.scope.launch {
            snapshotFlow {
                listOf(controller.color, controller.selection, controller.layersVersion, controller.lastPaintTool, controller.presetFor(controller.lastPaintTool))
            }
                .drop(1)
                .collect { if (anchors.size >= 2) changed() }
        }
    }

    override fun onSelectionChanged() {
        if (anchors.size >= 2) changed()
    }

    override fun onDeactivate() {
        observeJob?.cancel()
        observeJob = null
        drag = Drag.NONE
        if (hasPendingWork) commit()
        // Drops a path whose layer refused the commit (locked / hidden) and any in-tool history
        // left over from a path whose points were all deleted.
        clear()
    }

    override fun onDispose() = brushPreview.end()

    // ------------------------------------------------------------------ overlay

    private fun map(t: ViewTransform, p: Vec2): FloatArray {
        pts[0] = p.x; pts[1] = p.y
        t.matrix.mapPoints(pts)
        return pts
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val list = anchors
        if (list.isEmpty()) return
        if (overlaySpecs.isNotEmpty()) {
            specOverlay.draw(canvas, t, controller, targetLayer ?: controller.doc.activeLayer, overlaySpecs, keepBandFree = brushPreview.isLive)
        }
        if (list.size >= 2) painter.path(canvas, t, docPath)
        val sel = selected
        if (!polyline && sel in list.indices && !list[sel].sharp) {
            val (hIn, hOut) = handlesOf(sel)
            val a = map(t, list[sel].pos).let { it[0] to it[1] }
            for (h in listOf(hIn, hOut)) {
                if (h.length < 1e-3f) continue
                val q = map(t, list[sel].pos + h).let { it[0] to it[1] }
                painter.line(canvas, t, a.first, a.second, q.first, q.second)
                painter.handle(canvas, t, q.first, q.second, small = true, active = true)
            }
        }
        for (i in list.indices) {
            val q = map(t, list[i].pos)
            painter.handle(canvas, t, q[0], q[1], square = list[i].sharp || polyline, active = i == sel)
        }
    }

    companion object {
        private const val MAX_HISTORY = 200
        /** Keyed numeric edits closer together than this share one in-tool undo step. */
        private const val COALESCE_MS = 1500L
        private const val TOUCH_SLOP_DP = 6f
        /** Grab radius of anchors and tangent handles (screen dp): generous for fingers. */
        private const val HANDLE_TOUCH_DP = 24f
    }
}
