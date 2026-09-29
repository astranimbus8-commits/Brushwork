package com.brushwork.paint.assist

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.tools.ToolPoint

// STUB — replaced by the assist module.
/**
 * Applies ruler snapping (controller.ruler) and the stabilizer (controller.stabilizer) to the
 * raw input of painting tools.
 */
class StrokeAssist(private val controller: EditorController) {
    /** Start of a stroke; returns the (possibly snapped) first point. */
    fun down(p: ToolPoint): ToolPoint = p

    /** Returns zero or more processed points to feed to the tool. */
    fun move(p: ToolPoint): List<ToolPoint> = listOf(p)

    /** End of stroke; returns remaining points, the LAST one is used as the up point (never empty). */
    fun up(p: ToolPoint): List<ToolPoint> = listOf(p)

    fun cancel() {}

    /** Screen-space overlay while a stroke is in progress (e.g. the rope line). */
    fun drawOverlay(canvas: Canvas, t: ViewTransform) {}
}
