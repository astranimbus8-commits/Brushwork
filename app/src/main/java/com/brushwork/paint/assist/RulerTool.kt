package com.brushwork.paint.assist

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint

/**
 * Lets the user drag/rotate/resize the ruler on the canvas: handles (see [RulerGeometry]) resize
 * or rotate it, dragging anywhere else moves it. Changes go through controller.updateRuler and
 * are not undoable; a cancelled gesture restores the ruler as it was.
 */
class RulerTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.RULER

    private var start: RulerSettings? = null
    private var handle: RulerHandle? = null
    private var downX = 0f
    private var downY = 0f
    private val pts = FloatArray(2)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCCFFFFFF.toInt() }

    /** Editing the ruler implies using it: switch it on (and place it) when the tool is picked. */
    override fun onActivate() {
        val r = controller.ruler
        if (!r.enabled || r.centerX < 0f || r.centerY < 0f) {
            val placed = if (r.centerX < 0f || r.centerY < 0f) RulerGeometry.centered(r, controller.doc.width, controller.doc.height) else r
            controller.updateRuler(placed.copy(enabled = true))
        }
    }

    override fun onDeactivate() {
        start = null
        handle = null
    }

    override fun onDown(p: ToolPoint) {
        val r = controller.ruler
        start = r
        downX = p.x; downY = p.y
        handle = RulerGeometry.hitHandle(r, p.x, p.y, docPerDp(controller.viewTransform))
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        val s = start ?: return
        controller.updateRuler(RulerGeometry.drag(s, handle, downX, downY, p.x, p.y))
    }

    override fun onUp(p: ToolPoint) {
        onMove(p)
        start = null
        handle = null
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        start?.let { controller.updateRuler(it) }
        start = null
        handle = null
        controller.invalidateOverlay()
    }

    /** Rings the handle being dragged. */
    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val h = handle ?: return
        if (start == null) return
        val p = RulerGeometry.handlePosition(controller.ruler, h, docPerDp(t))
        pts[0] = p.x; pts[1] = p.y
        t.matrix.mapPoints(pts)
        highlight.strokeWidth = t.dp(2f)
        canvas.drawCircle(pts[0], pts[1], t.dp(RulerGeometry.HANDLE_DP), highlight)
    }

    private fun docPerDp(t: ViewTransform): Float = t.dp(1f) / t.zoom.coerceAtLeast(1e-4f)
}
