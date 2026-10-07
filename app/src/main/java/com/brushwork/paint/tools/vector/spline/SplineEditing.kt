package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint

/**
 * Pure edits of a Path-tool spline (v1.6 §3.2a): what the tool's gestures and strip buttons do
 * to the control points. Every function returns a new [VSpline] (or the same instance when
 * nothing changes) and keeps the result within the [VSpline] limits (at most
 * [VSpline.MAX_POINTS] points, weights and widths in range), so a pending path is always
 * `sanitized()`-stable and passes the I9 check once committed.
 */
object SplineEditing {

    /**
     * Where a point is closest to the control polygon: segment [segment] (point i to i + 1; the
     * closing one of a cyclic polygon last), at [t] along it. [interior] is false when the
     * nearest place is one of the segment's end points (the point lies beyond the segment's
     * ends, e.g. outside a corner): a point inserted there would sit on an existing one.
     */
    data class PolygonHit(val segment: Int, val t: Float, val point: Vec2, val distance: Float, val interior: Boolean = true)

    /** The position of control point [i]. */
    fun pos(p: VSplinePoint): Vec2 = Vec2(p.x, p.y)

    /**
     * Index of the control point nearest to [p] within [tol] (document px), or -1. Later points
     * win ties, so the newest point is grabbed where points overlap.
     */
    fun nearestPoint(points: List<VSplinePoint>, p: Vec2, tol: Float): Int {
        var best = -1
        var bestD = tol
        for (i in points.indices.reversed()) {
            val d = pos(points[i]).distanceTo(p)
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }

    /** Number of segments of the control polygon (the closing one included while [cyclic] with ≥ 3 points). */
    fun polygonSegments(n: Int, cyclic: Boolean): Int = when {
        n < 2 -> 0
        cyclic && n >= 3 -> n
        else -> n - 1
    }

    /** The point of [s]'s control polygon nearest to [p], or null with fewer than two points. */
    fun polygonHit(s: VSpline, p: Vec2): PolygonHit? {
        val pts = s.points
        val segs = polygonSegments(pts.size, s.cyclic)
        var best: PolygonHit? = null
        for (i in 0 until segs) {
            val a = pos(pts[i])
            val b = pos(pts[(i + 1) % pts.size])
            val q = Geometry.projectOnSegment(p, a, b)
            val d = q.distanceTo(p)
            if (best == null || d < best.distance) {
                val len = a.distanceTo(b)
                // The unclamped parameter of the projection: strictly inside the segment or not.
                val ab = b - a
                val len2 = ab.lengthSq
                val raw = if (len2 >= 1e-9f) (p - a).dot(ab) / len2 else 0f
                best = PolygonHit(
                    i, if (len > 1e-6f) (a.distanceTo(q) / len).coerceIn(0f, 1f) else 0f, q, d,
                    interior = len2 >= 1e-9f && raw > 0f && raw < 1f,
                )
            }
        }
        return best
    }

    /** [s] with [point] inserted at [index] (clamped to 0..size); [s] itself when it is full. */
    fun inserted(s: VSpline, index: Int, point: VSplinePoint): VSpline {
        if (s.points.size >= VSpline.MAX_POINTS) return s
        val list = s.points.toMutableList()
        list.add(index.coerceIn(0, list.size), clean(point))
        return s.copy(points = list)
    }

    /**
     * The control point inserted on the polygon at [hit]: its position, the weight and width
     * blended linearly from the segment's two ends (the curve keeps its look there).
     */
    fun pointOnPolygon(s: VSpline, hit: PolygonHit, at: Vec2): VSplinePoint {
        val a = s.points[hit.segment]
        val b = s.points[(hit.segment + 1) % s.points.size]
        val t = hit.t
        return VSplinePoint(at.x, at.y, weight = a.weight + (b.weight - a.weight) * t, width = a.width + (b.width - a.width) * t)
    }

    /** [s] with point [i] moved to [p]. */
    fun moved(s: VSpline, i: Int, p: Vec2): VSpline {
        val old = s.points.getOrNull(i) ?: return s
        if (!p.x.isFinite() || !p.y.isFinite()) return s
        val x = p.x.coerceIn(-VSpline.MAX_COORD, VSpline.MAX_COORD)
        val y = p.y.coerceIn(-VSpline.MAX_COORD, VSpline.MAX_COORD)
        if (old.x == x && old.y == y) return s
        return replaced(s, i, old.copy(x = x, y = y))
    }

    /** [s] with every point moved by [d]. */
    fun translated(s: VSpline, d: Vec2): VSpline {
        if (!d.x.isFinite() || !d.y.isFinite() || (d.x == 0f && d.y == 0f)) return s
        val lim = VSpline.MAX_COORD
        return s.copy(points = s.points.map { it.copy(x = (it.x + d.x).coerceIn(-lim, lim), y = (it.y + d.y).coerceIn(-lim, lim)) })
    }

    /** [s] without point [i]. */
    fun removed(s: VSpline, i: Int): VSpline {
        if (i !in s.points.indices) return s
        return s.copy(points = s.points.toMutableList().also { it.removeAt(i) })
    }

    /** [s] with point [i]'s weight set to [w] (held to the [VSpline] range). */
    fun withWeight(s: VSpline, i: Int, w: Float): VSpline {
        val old = s.points.getOrNull(i) ?: return s
        if (!w.isFinite()) return s
        val v = w.coerceIn(VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT)
        if (old.weight == v) return s
        return replaced(s, i, old.copy(weight = v))
    }

    /** [s] with point [i]'s thickness factor set to [w] (0..[VSpline.MAX_WIDTH]). */
    fun withWidth(s: VSpline, i: Int, w: Float): VSpline {
        val old = s.points.getOrNull(i) ?: return s
        if (!w.isFinite()) return s
        val v = w.coerceIn(0f, VSpline.MAX_WIDTH)
        if (old.width == v) return s
        return replaced(s, i, old.copy(width = v))
    }

    /** [s] with every point back at 100 % thickness. */
    fun uniformWidth(s: VSpline): VSpline =
        if (s.points.all { it.width == 1f }) s else s.copy(points = s.points.map { if (it.width == 1f) it else it.copy(width = 1f) })

    /** True when every control point is at 100 % thickness. */
    fun isUniformWidth(s: VSpline?): Boolean = s == null || s.points.all { it.width == 1f }

    private fun replaced(s: VSpline, i: Int, p: VSplinePoint): VSpline =
        s.copy(points = s.points.toMutableList().also { it[i] = p })

    /** A point within the [VSpline] limits (finite position, weight and width in range; v1.7: [VSplinePoint.sharp] kept). */
    fun clean(p: VSplinePoint): VSplinePoint {
        val lim = VSpline.MAX_COORD
        val x = if (p.x.isFinite()) p.x.coerceIn(-lim, lim) else 0f
        val y = if (p.y.isFinite()) p.y.coerceIn(-lim, lim) else 0f
        val wt = if (p.weight.isFinite()) p.weight.coerceIn(VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT) else 1f
        val wd = if (p.width.isFinite()) p.width.coerceIn(0f, VSpline.MAX_WIDTH) else 1f
        return if (x == p.x && y == p.y && wt == p.weight && wd == p.width) p else p.copy(x = x, y = y, weight = wt, width = wd)
    }

    // ------------------------------------------------------------------ the Weight slider (log scale)

    /** Slider position 0..1 of a weight: logarithmic over [VSpline.MIN_WEIGHT]..[VSpline.MAX_WEIGHT] (1 sits in the middle). */
    fun weightToFraction(w: Float): Float {
        val lo = kotlin.math.ln(VSpline.MIN_WEIGHT.toDouble())
        val hi = kotlin.math.ln(VSpline.MAX_WEIGHT.toDouble())
        val v = if (w.isFinite()) w.coerceIn(VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT) else 1f
        return ((kotlin.math.ln(v.toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    }

    /** The weight at slider position [f] (see [weightToFraction]), rounded to 2 decimals so values read cleanly. */
    fun fractionToWeight(f: Float): Float {
        val lo = kotlin.math.ln(VSpline.MIN_WEIGHT.toDouble())
        val hi = kotlin.math.ln(VSpline.MAX_WEIGHT.toDouble())
        val x = if (f.isFinite()) f.coerceIn(0f, 1f) else 0.5f
        val w = kotlin.math.exp(lo + (hi - lo) * x)
        return (Math.round(w * 100.0) / 100.0).toFloat().coerceIn(VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT)
    }
}
