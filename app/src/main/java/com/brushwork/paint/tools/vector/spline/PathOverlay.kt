package com.brushwork.paint.tools.vector.spline

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.vector.VSpline

/**
 * Screen-space drawing of the curve tools' points (v1.6, §3.2a / §3.3):
 * - the Curve / Polyline anchors and tangent handles at the user's "Handle size" ([scale]; at 1
 *   exactly the v1.5 look of `OverlayPainter.handle`);
 * - the Path tool's dashed control polygon (1 dp grey) and its control points: hollow circles of
 *   [IbisDims.PathPoint], the selected one filled with [IbisColors.SplineSelected] (Blender's orange).
 * Main thread (it reuses its paints and path).
 */
internal class PathOverlay {
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val polygonDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000; strokeCap = Paint.Cap.ROUND }
    private val polygonLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = POLYGON_GREY; strokeCap = Paint.Cap.ROUND }
    private val pointEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pointFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val screen = Path()
    private val pts = FloatArray(2)
    private var dashDensity = -1f

    /**
     * A Curve-tool handle at screen ([x], [y]): square for corners, accent-filled when [active],
     * smaller for tangent handle ends; every size times [scale] (the "Handle size" setting).
     */
    fun handle(canvas: Canvas, t: ViewTransform, x: Float, y: Float, scale: Float, square: Boolean = false, active: Boolean = false, small: Boolean = false) {
        val r = t.dp(if (small) 5f else 7f) * scale
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

    /** The dashed control polygon of [s] (the closing segment too while it is cyclic). */
    fun controlPolygon(canvas: Canvas, t: ViewTransform, s: VSpline) {
        val p = s.points
        val segs = SplineEditing.polygonSegments(p.size, s.cyclic)
        if (segs == 0) return
        screen.rewind()
        map(t, p[0].x, p[0].y)
        screen.moveTo(pts[0], pts[1])
        for (i in 1 until p.size) {
            map(t, p[i].x, p[i].y)
            screen.lineTo(pts[0], pts[1])
        }
        if (segs == p.size) screen.close()
        prepare(t)
        canvas.drawPath(screen, polygonDark)
        canvas.drawPath(screen, polygonLine)
    }

    /**
     * A control point at screen ([x], [y]): a hollow circle, filled orange when [selected]; sized
     * by [scale]. v1.7 (item 4): a sharp point ([square]) is a [SHARP_SIDE_DP] square instead.
     */
    fun controlPoint(canvas: Canvas, t: ViewTransform, x: Float, y: Float, selected: Boolean, scale: Float, square: Boolean = false) {
        val r = t.dp(if (square) SHARP_SIDE_DP / 2f else IbisDims.PathPoint.value / 2f) * scale
        val w = t.dp(2f)
        // A dark rim under the white ring keeps it readable over light artwork.
        pointEdge.color = 0x99000000.toInt()
        pointEdge.strokeWidth = w + t.dp(1.5f)
        shape(canvas, x, y, r, square, pointEdge)
        if (selected) {
            pointFill.color = SELECTED
            shape(canvas, x, y, r, square, pointFill)
        }
        pointEdge.color = if (selected) SELECTED_EDGE else 0xFFFFFFFF.toInt()
        pointEdge.strokeWidth = w
        shape(canvas, x, y, r, square, pointEdge)
    }

    private fun shape(canvas: Canvas, x: Float, y: Float, r: Float, square: Boolean, paint: Paint) {
        if (square) canvas.drawRect(x - r, y - r, x + r, y + r, paint) else canvas.drawCircle(x, y, r, paint)
    }

    private fun map(t: ViewTransform, x: Float, y: Float) {
        pts[0] = x; pts[1] = y
        t.matrix.mapPoints(pts)
    }

    private fun prepare(t: ViewTransform) {
        val line = t.dp(IbisDims.PathPolygonLine.value)
        polygonLine.strokeWidth = line
        polygonDark.strokeWidth = line + t.dp(1f)
        if (dashDensity != t.density) {
            dashDensity = t.density
            val dash = DashPathEffect(floatArrayOf(t.dp(5f), t.dp(4f)), 0f)
            polygonLine.pathEffect = dash
            polygonDark.pathEffect = dash
        }
    }

    companion object {
        /** The accent of selected Curve anchors (as `OverlayPainter`). */
        const val ACCENT = 0xFF4DA3FF.toInt()
        /** The control polygon's grey. */
        const val POLYGON_GREY = 0xFFB0B0B0.toInt()
        val SELECTED: Int = IbisColors.SplineSelected.toArgb()
        val SELECTED_EDGE: Int = 0xFFFFE0A0.toInt()
        /** v1.7 (item 4): the side of a sharp control point's square (dp, before the handle-size scale). */
        const val SHARP_SIDE_DP = 10f
    }
}
