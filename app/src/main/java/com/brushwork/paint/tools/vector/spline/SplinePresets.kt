package com.brushwork.paint.tools.vector.spline

import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.VSplinePoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Path tool's quick starts (v1.6 §3.2a "Shapes ▾"): control points of cyclic order-4
 * B-splines.
 *
 * - **Circle:** 8 points on a regular octagon, its radius chosen so the curve (which runs inside
 *   the polygon) has the requested radius; it stays within 0.1 % of a true circle.
 * - **Capsule:** 12 points, 3 : 1 like the user's Blender example. Each straight side is 4
 *   collinear control points (so one span is exactly straight), two points round each end; the
 *   curve stays within 0.3 % of the height from an exact stadium (fitted numerically: the
 *   largest distance from the stadium's outline is minimised over the four free coordinates).
 */
object SplinePresets {
    const val CIRCLE_POINTS = 8
    const val CAPSULE_POINTS = 12

    /** Width : height of the capsule. */
    const val CAPSULE_ASPECT = 3f

    /** The share of the visible area a quick start fills. */
    const val VIEW_FRACTION = 0.6f

    /**
     * Curve radius of a uniform cubic B-spline on a regular octagon of radius 1: it runs between
     * the value at the knots, (4 + 2 cos 45°) / 6, and mid-span, (2 cos 67.5° + 46 cos 22.5°) / 48.
     */
    private val OCTAGON_CURVE_RADIUS: Double = run {
        val step = 2.0 * PI / CIRCLE_POINTS
        val atKnot = (4.0 + 2.0 * cos(step)) / 6.0
        val midSpan = (2.0 * cos(1.5 * step) + 46.0 * cos(0.5 * step)) / 48.0
        (atKnot + midSpan) / 2.0
    }

    /** The 8 control points of a circle of [radius] around [center] (starting at the top, clockwise on screen). */
    fun circle(center: Vec2, radius: Float): List<VSplinePoint> {
        val r = radius / OCTAGON_CURVE_RADIUS
        return List(CIRCLE_POINTS) { i ->
            val a = -PI / 2.0 + i * 2.0 * PI / CIRCLE_POINTS
            VSplinePoint((center.x + r * cos(a)).toFloat(), (center.y + r * sin(a)).toFloat())
        }
    }

    /*
     * Capsule control points for a height of 1 centred on 0 (width 3): top row at y = −½ with x =
     * ±S1, ±S3; the ends at x = ±C, y = ±D. The stadium's caps are half circles of radius ½
     * around (±1, 0).
     */
    private const val S1 = 1.0309f
    private const val S3 = 1.1616f
    private const val C = 1.5131f
    private const val D = 0.2581f

    /** The 12 control points of a capsule of [height] (and 3 × that width) around [center], clockwise on screen from the top left. */
    fun capsule(center: Vec2, height: Float): List<VSplinePoint> {
        val h = height
        val unit = listOf(
            -S3 to -0.5f, -S1 to -0.5f, S1 to -0.5f, S3 to -0.5f,
            C to -D, C to D,
            S3 to 0.5f, S1 to 0.5f, -S1 to 0.5f, -S3 to 0.5f,
            -C to D, -C to -D,
        )
        return unit.map { (x, y) -> VSplinePoint(center.x + x * h, center.y + y * h) }
    }

    /**
     * Where a quick start goes: [VIEW_FRACTION] of [area] (the visible part of the canvas),
     * centred in it. Returns the centre and the circle's radius / the capsule's height.
     */
    fun circleIn(area: RectF): Pair<Vec2, Float> {
        val r = min(area.width(), area.height()) * VIEW_FRACTION / 2f
        return Vec2(area.centerX(), area.centerY()) to r
    }

    fun capsuleIn(area: RectF): Pair<Vec2, Float> {
        val w = min(area.width() * VIEW_FRACTION, area.height() * VIEW_FRACTION * CAPSULE_ASPECT)
        return Vec2(area.centerX(), area.centerY()) to w / CAPSULE_ASPECT
    }
}
