package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import kotlin.math.min

/**
 * One anchor of the curve / polyline tools (document pixels). A smooth anchor gets an automatic
 * Catmull-Rom tangent unless [handleIn]/[handleOut] (offsets from the anchor) override it; a
 * [sharp] anchor breaks the curve into a corner, and each of its handles that is set is used as
 * is (broken tangents, e.g. imported SVG cubics) while an unset one follows its chord.
 * [width] is the line thickness factor at this anchor (0..3, v1.5).
 */
data class CurveAnchor(
    val x: Float,
    val y: Float,
    val sharp: Boolean = false,
    val handleIn: Vec2? = null,
    val handleOut: Vec2? = null,
    val width: Float = 1f,
) {
    val pos: Vec2 get() = Vec2(x, y)
    val hasCustomTangent: Boolean get() = handleIn != null && handleOut != null
    fun moved(p: Vec2) = copy(x = p.x, y = p.y)
    fun withAutoTangent() = copy(handleIn = null, handleOut = null)
}

/** Where a point lies on a curve: segment index (from anchor [segment]) and its parameter. */
data class CurveHit(val segment: Int, val t: Float, val point: Vec2, val distance: Float)

object CurveGeometry {

    /**
     * Bezier handles (in, out) of anchor [i] as offsets from the anchor. Smooth anchors use the
     * cardinal-spline tangent `(1 - tension) * (next - prev) / 2` (one-sided at open ends) unless
     * both custom handles are set; sharp anchors point each handle along its own chord, so the
     * curve meets there at an angle, except a custom handle that is set (broken tangents: a null
     * handle on a sharp anchor keeps the chord handle, so v1.4 paths are unchanged).
     */
    fun handles(anchors: List<CurveAnchor>, i: Int, closed: Boolean, tension: Float): Pair<Vec2, Vec2> {
        val a = anchors[i]
        val hi = a.handleIn; val ho = a.handleOut
        if (!a.sharp && hi != null && ho != null) return hi to ho
        val n = anchors.size
        val prev = if (i > 0) anchors[i - 1].pos else if (closed && n > 2) anchors[n - 1].pos else null
        val next = if (i < n - 1) anchors[i + 1].pos else if (closed && n > 2) anchors[0].pos else null
        val k = (1f - tension).coerceIn(0f, 1f)
        val p = a.pos
        if (a.sharp) {
            val hIn = hi ?: prev?.let { (it - p) * (k / 6f) } ?: Vec2.ZERO
            val hOut = ho ?: next?.let { (it - p) * (k / 6f) } ?: Vec2.ZERO
            return hIn to hOut
        }
        val m = when {
            prev != null && next != null -> (next - prev) * (k / 2f)
            next != null -> (next - p) * k
            prev != null -> (p - prev) * k
            else -> Vec2.ZERO
        }
        return (m / -3f) to (m / 3f)
    }

    /** Number of segments of the curve. */
    fun segmentCount(n: Int, closed: Boolean): Int = when {
        n < 2 -> 0
        closed && n > 2 -> n
        else -> n - 1
    }

    /** Cubic control points (p0, c1, c2, p1) of segment [s] (from anchor s to s + 1). */
    fun segment(anchors: List<CurveAnchor>, s: Int, closed: Boolean, tension: Float, polyline: Boolean): Array<Vec2> {
        val n = anchors.size
        val a = anchors[s].pos
        val b = anchors[(s + 1) % n].pos
        if (polyline) return arrayOf(a, a.lerp(b, 1f / 3f), a.lerp(b, 2f / 3f), b)
        val out = handles(anchors, s, closed, tension).second
        val inn = handles(anchors, (s + 1) % n, closed, tension).first
        return arrayOf(a, a + out, b + inn, b)
    }

    /** The path through all anchors (straight segments when [polyline]). */
    fun toPath(anchors: List<CurveAnchor>, closed: Boolean, tension: Float, polyline: Boolean): VectorPath {
        val n = anchors.size
        if (n == 0) return VectorPath.EMPTY
        val ops = ArrayList<PathOp>(n + 2)
        ops += PathOp.MoveTo(anchors[0].pos)
        val segs = segmentCount(n, closed)
        for (s in 0 until segs) {
            val (p0, c1, c2, p1) = segment(anchors, s, closed, tension, polyline)
            val straight = polyline || (isOnSegment(c1, p0, p1) && isOnSegment(c2, p0, p1))
            ops += if (straight) PathOp.LineTo(p1) else PathOp.CubicTo(c1, c2, p1)
        }
        if (closed && n > 2) ops += PathOp.Close
        return VectorPath(ops)
    }

    private fun isOnSegment(c: Vec2, a: Vec2, b: Vec2): Boolean = Geometry.distanceToSegment(c, a, b) < 1e-3f

    /** Closest point of the curve to [p] (null when there are no segments). */
    fun nearest(anchors: List<CurveAnchor>, p: Vec2, closed: Boolean, tension: Float, polyline: Boolean): CurveHit? {
        var best: CurveHit? = null
        val segs = segmentCount(anchors.size, closed)
        for (s in 0 until segs) {
            val (p0, c1, c2, p1) = segment(anchors, s, closed, tension, polyline)
            val steps = 48
            var prev = p0
            for (k in 1..steps) {
                val t1 = k.toFloat() / steps
                val q = VectorPath.cubicPoint(p0, c1, c2, p1, t1)
                val proj = Geometry.projectOnSegment(p, prev, q)
                val d = proj.distanceTo(p)
                if (best == null || d < best.distance) {
                    val segLen = prev.distanceTo(q)
                    val local = if (segLen < 1e-6f) 0f else prev.distanceTo(proj) / segLen
                    best = CurveHit(s, (k - 1 + local) / steps, proj, d)
                }
                prev = q
            }
        }
        return best
    }

    /**
     * Even samples along the path, [spacing] px apart (first and last points included; a closed
     * path ends back at its start).
     */
    fun sample(path: VectorPath, spacing: Float, tolerance: Float = 0.1f): List<Vec2> {
        val poly = path.flatten(tolerance).firstOrNull() ?: return emptyList()
        return VectorPath.resample(poly.points, spacing, poly.closed)
    }

    /**
     * Pressure for a sample [dist] px along a stroke of [total] px when tapering both ends over
     * [taperLength] px: eases from [minPressure] at the tips to 1 in the middle.
     */
    fun taperPressure(dist: Float, total: Float, taperLength: Float, minPressure: Float = 0.08f): Float {
        if (taperLength <= 0f || total <= 0f) return 1f
        val len = min(taperLength, total / 2f)
        val e = (min(dist, total - dist) / len).coerceIn(0f, 1f)
        val eased = 1f - (1f - e) * (1f - e)
        return minPressure + (1f - minPressure) * eased
    }
}
