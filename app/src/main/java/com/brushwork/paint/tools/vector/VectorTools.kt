package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.GridType

/*
 * Helpers shared by the vector tools ([ShapeTool], [CurveTool]).
 */

/** Snaps [p] to the square grid when grid snapping is on. */
internal fun EditorController.snapToGrid(p: Vec2): Vec2 {
    val g = grid
    return if (g.enabled && g.snap && g.type == GridType.SQUARE && g.spacingPx > 0f) {
        ShapeGeometry.snapToGrid(p, g.spacingPx, g.offsetXPx, g.offsetYPx)
    } else p
}

/** A screen distance in dp expressed in document pixels (for hit testing). */
internal fun EditorController.docLength(dp: Float): Float = viewTransform.screenToDocLength(viewTransform.dp(dp))

/** This value, or [fallback] when it is NaN or infinite (numeric fields can deliver "NaN"). */
internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

/** Screen-space drawing of guides and handles, readable over any artwork. */
internal class OverlayPainter {
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt(); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val accentLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val screenPath = Path()
    private var dashDensity = -1f

    /** Draws a document-space [path] as a thin two-tone line; dashed accent when [dashed]. */
    fun path(canvas: Canvas, t: ViewTransform, path: Path, dashed: Boolean = false) {
        path.transform(t.matrix, screenPath)
        prepare(t, dashed)
        canvas.drawPath(screenPath, dark)
        canvas.drawPath(screenPath, if (dashed) accentLine else light)
    }

    /** Line between two screen points. */
    fun line(canvas: Canvas, t: ViewTransform, ax: Float, ay: Float, bx: Float, by: Float) {
        prepare(t, false)
        canvas.drawLine(ax, ay, bx, by, dark)
        canvas.drawLine(ax, ay, bx, by, light)
    }

    /** Handle at a screen position: square for corner points, accent-filled when [active]. */
    fun handle(canvas: Canvas, t: ViewTransform, x: Float, y: Float, square: Boolean = false, active: Boolean = false, small: Boolean = false) {
        val r = t.dp(if (small) 5f else 7f)
        handleFill.color = if (active) ACCENT else 0xFFFFFFFF.toInt()
        handleEdge.color = if (active) 0xFFFFFFFF.toInt() else 0xFF1E1F22.toInt()
        handleEdge.strokeWidth = t.dp(1.5f)
        if (square) {
            canvas.drawRect(x - r, y - r, x + r, y + r, handleFill)
            canvas.drawRect(x - r, y - r, x + r, y + r, handleEdge)
        } else {
            canvas.drawCircle(x, y, r, handleFill)
            canvas.drawCircle(x, y, r, handleEdge)
        }
    }

    private fun prepare(t: ViewTransform, dashed: Boolean) {
        dark.strokeWidth = t.dp(3.5f)
        light.strokeWidth = t.dp(1.5f)
        accentLine.strokeWidth = t.dp(1.5f)
        if (dashed && dashDensity != t.density) {
            dashDensity = t.density
            accentLine.pathEffect = DashPathEffect(floatArrayOf(t.dp(6f), t.dp(4f)), 0f)
        }
    }

    companion object {
        const val ACCENT = 0xFF4DA3FF.toInt()
    }
}
