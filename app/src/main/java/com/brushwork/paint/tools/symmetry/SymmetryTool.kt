package com.brushwork.paint.tools.symmetry

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryHandle
import com.brushwork.paint.assist.SymmetryHandles
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint

/**
 * The Symmetry tool (v1.7 item 18, §3.18; area H): ibisPaint's five symmetry rulers (mirror,
 * kaleidoscope, rotation, array, perspective array) with their canvas handles. The settings are
 * `Document.symmetry`, changed through `EditorController.updateSymmetry`; they stay on after the
 * tool is put away, and the brush, eraser, smudge and blur replicate their strokes with them.
 *
 * On the canvas ([SymmetryHandles], drawn by `SymmetryGuides`): the centre handle moves the
 * centre, the angle knob turns the axes (snapping to 15°), the array's two spacing handles set
 * "Spacing X" (and the grid's angle) and "Spacing Y", the perspective cell's four corner handles
 * reshape it (it stays convex); dragging anywhere else moves the whole ruler. A tap never moves
 * anything (the finger must pass a small slop first). Like the ruler, changes are not undoable,
 * and a cancelled drag (a second finger) puts the ruler back. The tool never touches a layer, so
 * it works on every layer kind and never has pending work.
 */
class SymmetryTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.SYMMETRY

    /** The settings when the finger went down (null: no drag). */
    private var start: SymmetrySettings? = null

    /** The handle being dragged; null: the whole ruler is. */
    private var handle: SymmetryHandle? = null
    private var downX = 0f
    private var downY = 0f
    private var handleX = 0f
    private var handleY = 0f
    private var pastSlop = false
    private val pts = FloatArray(2)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCCFFFFFF.toInt() }

    /** The handle being dragged right now (null: none, or the whole ruler). */
    internal val dragged: SymmetryHandle? get() = if (start != null) handle else null

    /**
     * Picking the tool means using symmetry: with it off, the mirror ruler is turned on (as the
     * Ruler tool turns the ruler on). Not done in onActivate, which also fires on layer changes
     * and would turn symmetry back on right after the user chose "Off".
     */
    override fun onSelected() {
        val s = controller.symmetry
        if (s.type == SymmetryType.OFF) controller.updateSymmetry(s.copy(type = SymmetryType.MIRROR))
    }

    override fun onDeactivate() {
        start = null
        handle = null
    }

    override fun onDown(p: ToolPoint) {
        val s = controller.symmetry.sanitized()
        start = null
        handle = null
        if (s.type == SymmetryType.OFF) return
        val d = controller.doc
        val perDp = docPerDp(controller.viewTransform)
        start = s
        downX = p.x; downY = p.y
        pastSlop = false
        handle = SymmetryHandles.hit(s, d.width, d.height, p.x, p.y, perDp)
        handle?.let {
            val at = SymmetryHandles.position(s, d.width, d.height, it, perDp)
            handleX = at.x; handleY = at.y
        }
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        val s = start ?: return
        val d = controller.doc
        if (!pastSlop) {
            val t = controller.viewTransform
            if (t.docToScreen(Vec2(p.x, p.y)).distanceTo(t.docToScreen(Vec2(downX, downY))) < t.dp(SLOP_DP)) return
            pastSlop = true
        }
        val dx = p.x - downX
        val dy = p.y - downY
        val h = handle
        // A handle follows the finger by how far it moved (not to the finger itself, which is
        // rarely on the handle's centre).
        val next = if (h == null) SymmetryHandles.translated(s, d.width, d.height, dx, dy)
        else SymmetryHandles.moved(s, d.width, d.height, h, handleX + dx, handleY + dy)
        controller.updateSymmetry(next.sanitized())
    }

    override fun onUp(p: ToolPoint) {
        onMove(p)
        start = null
        handle = null
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        start?.let { controller.updateSymmetry(it) }
        start = null
        handle = null
        controller.invalidateOverlay()
    }

    /** Rings the handle being dragged. */
    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val h = dragged ?: return
        val d = controller.doc
        val at = SymmetryHandles.position(controller.symmetry.sanitized(), d.width, d.height, h, docPerDp(t))
        pts[0] = at.x; pts[1] = at.y
        t.matrix.mapPoints(pts)
        highlight.strokeWidth = t.dp(2f)
        canvas.drawCircle(pts[0], pts[1], t.dp(SymmetryHandles.HANDLE_DP / 2f), highlight)
    }

    private fun docPerDp(t: ViewTransform): Float = t.dp(1f) / t.zoom.coerceAtLeast(1e-4f)

    companion object {
        /** The finger must move this far (dp) before anything moves (a tap never does). */
        private const val SLOP_DP = 6f
    }
}
