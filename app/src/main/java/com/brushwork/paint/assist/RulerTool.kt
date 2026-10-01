package com.brushwork.paint.assist

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.POINT_GUIDE_EPS
import com.brushwork.paint.tools.select.pointBox
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.SnapGuide
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lets the user drag/rotate/resize the ruler on the canvas: handles (see [RulerGeometry]) resize
 * or rotate it, dragging anywhere else moves it. Changes go through controller.updateRuler and
 * are not undoable; a cancelled gesture restores the ruler as it was.
 *
 * "Snap to objects" (the app-wide setting, the Snap chip): once the finger moved past the touch
 * slop (a tap never moves the ruler), a moved ruler's center snaps per axis to the canvas edges
 * and center, other layers' content bounds, the lines drawn in layers (Table filter lines...)
 * and shape vertices (and on axes that didn't snap to the square grid when grid snapping is on),
 * and a radius / semi-axis handle snaps so the circle or ellipse touches the closest line. The
 * rotation keeps its own 15° snapping.
 */
class RulerTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.RULER

    private var start: RulerSettings? = null
    private var handle: RulerHandle? = null
    private var downX = 0f
    private var downY = 0f
    /** The finger moved past the touch slop: the handle snaps from then on. */
    private var pastSlop = false
    private val pts = FloatArray(2)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCCFFFFFF.toInt() }

    /** Snapping of the dragged handle (one session, begun on every touch). */
    private val snap = controller.newSnapSession()

    /** The point that snapped (keeps the guide labels off it), or null. */
    private var snapMoving: DocBox? = null

    /** Guides shown right now (document px); empty when nothing is aligned. */
    internal val activeGuides: List<SnapGuide> get() = snap.guides

    /**
     * Editing the ruler implies using it: switch it on (and place it) when the user picks the
     * tool. Not done in onActivate, which also fires on layer changes and would turn a ruler
     * back on that the user had just switched off.
     */
    override fun onSelected() {
        val r = controller.ruler
        controller.updateRuler(RulerGeometry.resolved(r, controller.doc.width, controller.doc.height).copy(enabled = true))
    }

    override fun onDeactivate() {
        start = null
        handle = null
        endSnap()
    }

    override fun onDown(p: ToolPoint) {
        val r = RulerGeometry.resolved(controller.ruler, controller.doc.width, controller.doc.height)
        start = r
        downX = p.x; downY = p.y
        pastSlop = false
        handle = RulerGeometry.hitHandle(r, p.x, p.y, docPerDp(controller.viewTransform))
        endSnap()
        if (handle != RulerHandle.ROTATE) snap.begin()
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        val s = start ?: return
        val d = controller.doc
        if (!pastSlop) {
            val t = controller.viewTransform
            if (t.docToScreen(Vec2(p.x, p.y)).distanceTo(t.docToScreen(Vec2(downX, downY))) >= t.dp(SNAP_SLOP_DP)) pastSlop = true
        }
        // What the finger alone gives is snapped (never the last snapped ruler).
        var r = RulerGeometry.drag(s, handle, downX, downY, p.x, p.y)
        if (pastSlop) r = snapped(r)
        controller.updateRuler(RulerGeometry.sanitize(r, d.width, d.height))
    }

    /** [r] (the ruler as the finger alone drags it) with the dragged handle snapped to objects. */
    private fun snapped(r: RulerSettings): RulerSettings {
        if (!controller.snapping.enabled) return r
        return when (handle) {
            null, RulerHandle.CENTER -> {
                val c = snap.snapPoint(Vec2(r.centerX, r.centerY))
                snapMoving = pointBox(c)
                r.copy(centerX = c.x, centerY = c.y)
            }
            RulerHandle.RADIUS -> snapRadius(r, r.radius, listOf(Vec2(1f, 0f), Vec2(0f, 1f))) { r.copy(radius = it) }
            RulerHandle.RADIUS_X -> {
                val a = r.angleDeg * Geometry.DEG
                snapRadius(r, r.radiusX, listOf(Vec2(cos(a), sin(a)))) { r.copy(radiusX = it) }
            }
            RulerHandle.RADIUS_Y -> {
                val a = r.angleDeg * Geometry.DEG
                snapRadius(r, r.radiusY, listOf(Vec2(-sin(a), cos(a)))) { r.copy(radiusY = it) }
            }
            RulerHandle.ROTATE -> r
        }
    }

    /**
     * The circle's radius / an ellipse semi-axis [value] snapped so that the outline touches the
     * closest target line along [dirs] (both ways); [apply] makes the new ruler.
     */
    private fun snapRadius(r: RulerSettings, value: Float, dirs: List<Vec2>, apply: (Float) -> RulerSettings): RulerSettings {
        val hit = RulerHandleSnap.radius(Vec2(r.centerX, r.centerY), value, dirs, RulerGeometry.MIN_RADIUS) { v, axis -> snap.snapValue(v, axis) }
        if (hit == null) {
            snap.clearGuides()
            snapMoving = null
            return r
        }
        val (radius, at) = hit
        snap.showGuidesFor(pointBox(at), POINT_GUIDE_EPS)
        snapMoving = pointBox(at)
        return apply(radius)
    }

    override fun onUp(p: ToolPoint) {
        onMove(p)
        start = null
        handle = null
        endSnap()
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        start?.let { controller.updateRuler(it) }
        start = null
        handle = null
        endSnap()
        controller.invalidateOverlay()
    }

    private fun endSnap() {
        snapMoving = null
        snap.end()
    }

    /** Rings the handle being dragged; smart guides while it snaps. */
    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        snap.draw(canvas, t, snapMoving)
        val h = handle ?: return
        if (start == null) return
        val p = RulerGeometry.handlePosition(controller.ruler, h, docPerDp(t))
        pts[0] = p.x; pts[1] = p.y
        t.matrix.mapPoints(pts)
        highlight.strokeWidth = t.dp(2f)
        canvas.drawCircle(pts[0], pts[1], t.dp(RulerGeometry.HANDLE_DP), highlight)
    }

    private fun docPerDp(t: ViewTransform): Float = t.dp(1f) / t.zoom.coerceAtLeast(1e-4f)

    companion object {
        /** The finger must move this far (dp) before the ruler snaps (a tap never moves it). */
        private const val SNAP_SLOP_DP = 6f
    }
}
