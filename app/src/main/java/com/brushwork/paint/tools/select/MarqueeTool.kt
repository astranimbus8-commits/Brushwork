package com.brushwork.paint.tools.select

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.getValue
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
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapGuide
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Rectangle / ellipse selection by dragging, optionally square (1:1) and from the center. Shows
 * the live outline and its size in pixels. A plain tap in "New" mode deselects.
 *
 * "Snap to objects" (the app-wide setting): the starting corner (the center with "From center")
 * snaps when the finger lands, and the dragged corner once the finger moved past the touch slop,
 * per axis, to the canvas edges and center, other layers' content bounds, the lines drawn in
 * layers (a Table filter's cells...) and shape vertices, and on axes that didn't snap to the
 * square grid when grid snapping is on, so a selection can be made exactly to a layer's content
 * or a table cell. With 1:1 the moving side closest to a line sets the size. The existing
 * selection is a target only in add / subtract / intersect modes. Off: exactly as before.
 */
class MarqueeTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.MARQUEE

    var settings: MarqueeSettings by PersistedOption(controller.settings, "select.marquee", MarqueeSettings.serializer(), MarqueeSettings())
    var mode by mutableStateOf(SelectionMode.REPLACE)

    var busy by mutableStateOf(false)
        private set

    /** The rectangle's corners as snapped (document px): the start (or center) and the dragged one. */
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    /** Where the finger went down / is now, before snapping (taps are told from drags with these). */
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    /** The finger moved past the touch slop: the dragged corner snaps from then on. */
    private var pastSlop = false
    private var dragging = false
    private var committing: Path? = null
    private val previewPath = Path()

    /** Snapping of the corners (one session, begun on every drag). */
    private val snap = controller.newSnapSession()

    /** Guides shown right now (document px); empty when nothing is aligned. */
    internal val activeGuides: List<SnapGuide> get() = snap.guides

    override fun onDown(p: ToolPoint) {
        if (busy) return
        downX = p.x; downY = p.y; fingerX = p.x; fingerY = p.y
        pastSlop = false
        // The old selection is being replaced in "New" mode: only a target when combining with it.
        snap.begin(includeSelection = mode != SelectionMode.REPLACE)
        // The starting corner is a new point: it may snap right away.
        val s = snap.snapPointWhenOn(Vec2(p.x, p.y), controller.snapping)
        startX = s.x; startY = s.y; curX = s.x; curY = s.y
        dragging = true
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        if (!dragging) return
        follow(p)
        controller.invalidateOverlay()
    }

    /** The dragged corner follows the finger at [p] (snapped once past the touch slop). */
    private fun follow(p: ToolPoint) {
        fingerX = p.x; fingerY = p.y
        if (!pastSlop) {
            val t = controller.viewTransform
            if (hypot(p.x - downX, p.y - downY) >= t.screenToDocLength(t.dp(SNAP_SLOP_DP))) pastSlop = true
        }
        if (!pastSlop) {
            curX = p.x; curY = p.y
            return
        }
        val c = snappedCorner(p.x, p.y)
        curX = c.x; curY = c.y
        // Every line of the rectangle that lies on a target shows its guide (the start too).
        if (controller.snapping.enabled) {
            val r = shapeRect(startX, startY, curX, curY, settings)
            snap.showGuidesFor(DocBox(r.left, r.top, r.right, r.bottom))
        }
    }

    /**
     * The dragged corner for the finger at ([x], [y]), snapped while "Snap to objects" is on: per
     * axis (the free corner); with 1:1 the one side length is set by whichever moving side is
     * closest to a line (else the grid, when grid snapping is on), so the square stays square.
     */
    private fun snappedCorner(x: Float, y: Float): Vec2 {
        val raw = Vec2(x, y)
        if (!controller.snapping.enabled) return raw
        if (!settings.square) return snap.snapPoint(raw)
        val dx = x - startX
        val dy = y - startY
        val side = max(abs(dx), abs(dy))
        val sx = if (dx < 0f) -1f else 1f
        val sy = if (dy < 0f) -1f else 1f
        val hx = snap.snapValue(startX + sx * side, SnapAxis.X)
        val hy = snap.snapValue(startY + sy * side, SnapAxis.Y)
        val k = when {
            hx != null && (hy == null || hx.distance <= hy.distance) -> abs(hx.pos - startX)
            hy != null -> abs(hy.pos - startY)
            else -> {
                val g = controller.snapping.gridPoint(Vec2(startX + sx * side, startY + sy * side))
                if (abs(dx) >= abs(dy)) abs(g.x - startX) else abs(g.y - startY)
            }
        }
        return Vec2(startX + sx * k, startY + sy * k)
    }

    override fun onUp(p: ToolPoint) {
        if (!dragging) return
        dragging = false
        follow(p)
        snap.end()
        val t = controller.viewTransform
        val r = shapeRect(startX, startY, curX, curY, settings)
        // A tap is judged by the finger, not by where the corners snapped: what the finger alone
        // drew decides, exactly as before snapping (with snapping off it is the same rectangle).
        val raw = shapeRect(downX, downY, fingerX, fingerY, settings)
        val tap = max(abs(fingerX - downX), abs(fingerY - downY)) < t.screenToDocLength(t.dp(8f)) || raw.width() < 1f || raw.height() < 1f
        when {
            tap -> if (mode == SelectionMode.REPLACE) deselectOnTap(controller)
            // A real drag whose corners snapped onto one line (e.g. along a layer's edge) encloses
            // nothing: nothing is selected and the current selection stays.
            r.width() < 1f || r.height() < 1f -> {}
            else -> apply(shapePath(r, settings.shape), settings.shape == MarqueeShape.ELLIPSE)
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        dragging = false
        snap.end()
        controller.invalidateOverlay()
    }

    override fun onDeactivate() {
        dragging = false
        snap.end()
        super.onDeactivate()
    }

    private fun apply(path: Path, antiAlias: Boolean) {
        val docW = controller.doc.width; val docH = controller.doc.height
        val work = Path(path)
        committing = path
        busy = true
        val label = if (settings.shape == MarqueeShape.ELLIPSE) "Ellipse selection" else "Rectangle selection"
        SelectionJobs.applyAsync(controller, label, mode, "Selecting…", onFinished = {
            busy = false
            committing = null
            controller.invalidateOverlay()
        }, toObjects = true) { cancelled ->
            if (cancelled()) null else Selection.fromPath(work, docW, docH, antiAlias)
        }
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        committing?.let { SelectionOverlay.drawDocPath(canvas, t, it) }
        if (!dragging) return
        val r = shapeRect(startX, startY, curX, curY, settings)
        previewPath.reset()
        addShape(previewPath, r, settings.shape)
        SelectionOverlay.drawDocPath(canvas, t, previewPath)
        // Smart guides (labels away from the dragged corner).
        snap.draw(canvas, t, pointBox(Vec2(curX, curY)))
        val pos = t.docToScreen(fingerX, fingerY)
        val label = "${r.width().roundToInt()} × ${r.height().roundToInt()} px"
        // Above the finger so it stays readable (below it near the top edge of the view).
        val above = pos.y - t.dp(56f)
        SelectionOverlay.drawLabel(canvas, t, label, pos.x, if (above > t.dp(20f)) above else pos.y + t.dp(56f))
    }

    companion object {
        /** The finger must move this far (dp) before the dragged corner snaps (a tap never jumps). */
        private const val SNAP_SLOP_DP = 6f

        /**
         * The selection rectangle for a drag from (x0, y0) to (x1, y1), with the square and
         * from-center constraints. Edges are snapped to whole pixels so rectangles are crisp and
         * the size readout matches the result.
         */
        internal fun shapeRect(x0: Float, y0: Float, x1: Float, y1: Float, s: MarqueeSettings): RectF {
            var dx = x1 - x0
            var dy = y1 - y0
            if (s.square) {
                val side = max(abs(dx), abs(dy))
                dx = side * (if (dx < 0f) -1f else 1f)
                dy = side * (if (dy < 0f) -1f else 1f)
            }
            val r = if (s.fromCenter) {
                val cx = x0.roundToInt().toFloat(); val cy = y0.roundToInt().toFloat()
                val hx = abs(dx).roundToInt().toFloat(); val hy = abs(dy).roundToInt().toFloat()
                RectF(cx - hx, cy - hy, cx + hx, cy + hy)
            } else {
                val ax = x0.roundToInt().toFloat(); val ay = y0.roundToInt().toFloat()
                val bx = ax + sign(dx) * abs(dx).roundToInt(); val by = ay + sign(dy) * abs(dy).roundToInt()
                RectF(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
            }
            return r
        }

        internal fun shapePath(r: RectF, shape: MarqueeShape): Path = Path().also { addShape(it, r, shape) }

        private fun addShape(path: Path, r: RectF, shape: MarqueeShape) {
            if (shape == MarqueeShape.ELLIPSE) path.addOval(r, Path.Direction.CW) else path.addRect(r, Path.Direction.CW)
        }
    }
}
