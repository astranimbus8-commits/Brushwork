package com.brushwork.paint.engine

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Maps document pixels <-> screen (view) pixels. Owned and updated by the canvas view
 * (pan / pinch-zoom / rotate / flip); tools read it for hit testing and overlays.
 */
class ViewTransform {
    /** document -> screen */
    val matrix = Matrix()
    /** screen -> document */
    val inverse = Matrix()
    private val pts = FloatArray(2)

    /** Screen density (px per dp), set by the view. Use for handle sizes: `dp(24f)`. */
    var density: Float = 1f

    /** Screen pixels per document pixel. */
    val zoom: Float
        get() {
            val v = FloatArray(9); matrix.getValues(v)
            return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
        }

    /** View rotation in degrees. */
    val rotationDeg: Float
        get() {
            val v = FloatArray(9); matrix.getValues(v)
            return Math.toDegrees(atan2(v[Matrix.MSKEW_Y].toDouble(), v[Matrix.MSCALE_X].toDouble())).toFloat()
        }

    /** True when the view is mirrored (display flip). */
    val isMirrored: Boolean
        get() {
            val v = FloatArray(9); matrix.getValues(v)
            return v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y] - v[Matrix.MSKEW_X] * v[Matrix.MSKEW_Y] < 0
        }

    fun set(m: Matrix) {
        matrix.set(m)
        matrix.invert(inverse)
    }

    fun dp(v: Float): Float = v * density

    /** Screen distance [screenPx] expressed in document pixels. */
    fun screenToDocLength(screenPx: Float): Float = screenPx / zoom.coerceAtLeast(1e-6f)

    fun docToScreen(x: Float, y: Float): PointF { pts[0] = x; pts[1] = y; matrix.mapPoints(pts); return PointF(pts[0], pts[1]) }
    fun screenToDoc(x: Float, y: Float): PointF { pts[0] = x; pts[1] = y; inverse.mapPoints(pts); return PointF(pts[0], pts[1]) }
    fun docToScreen(v: Vec2): Vec2 { pts[0] = v.x; pts[1] = v.y; matrix.mapPoints(pts); return Vec2(pts[0], pts[1]) }
    fun screenToDoc(v: Vec2): Vec2 { pts[0] = v.x; pts[1] = v.y; inverse.mapPoints(pts); return Vec2(pts[0], pts[1]) }

    /** Document-space rectangle currently visible in a view of the given size. */
    fun visibleDocRect(viewWidth: Int, viewHeight: Int): RectF {
        val r = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        inverse.mapRect(r)
        return r
    }
}
