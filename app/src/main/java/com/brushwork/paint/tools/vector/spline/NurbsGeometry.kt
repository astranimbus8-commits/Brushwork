package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint

/*
 * NURBS / B-spline geometry of the Path tool (v1.6 §3.2c; pure Kotlin, doubles, JVM-tested).
 *
 * A [VSpline] of n control points and effective order k (degree p = k − 1) is evaluated on a
 * knot vector that is
 *  - open-uniform (clamped: k equal knots at each end) for an open curve with Endpoint on, so it
 *    touches its first and last points;
 *  - uniform for an open curve with Endpoint off (it starts and ends inside the polygon);
 *  - periodic for a cyclic curve (n ≥ 3): the control polygon is unrolled by wrapping k − 1
 *    points, so the curve closes with full continuity (C^(p−1)) at the seam.
 * Weights make it rational: points are lifted to homogeneous coordinates (x·w, y·w, w) and the
 * thickness factor rides along as width·w, so it is blended with the same rational basis.
 *
 * v1.7 (item 4): a sharp control point ([VSplinePoint.sharp]) is a corner. [NurbsGeometry.pieces]
 * splits the spline there into independently clamped pieces, each converted on its own (the
 * curve passes exactly through the corner, at its exact thickness); `SplineBezier` joins them
 * into one subpath. A spline without sharp points is one piece, the spline itself.
 */

/** The knot vector and the unrolled control polygon of a spline (see the file docs). */
class NurbsBasis internal constructor(
    /** Effective order k (1..6): the order limited by the point count. */
    val order: Int,
    /** Knot values (length = [ctrl].size + [order]). */
    val knots: DoubleArray,
    /** Index into the spline's points of each unrolled control point (a cyclic curve wraps). */
    val ctrl: IntArray,
    /** True when the curve is periodic (cyclic with at least 3 points). */
    val closed: Boolean,
) {
    val degree: Int get() = order - 1

    /** The spans of the domain in order: knot index i of each span `[knots[i], knots[i + 1])` (non-empty). */
    val spans: IntArray = run {
        val out = ArrayList<Int>()
        for (i in (order - 1) until ctrl.size) if (knots[i] < knots[i + 1]) out += i
        out.toIntArray()
    }

    /** First and last parameter of the curve. */
    val start: Double get() = knots[order - 1]
    val end: Double get() = knots[ctrl.size]
}

object NurbsGeometry {
    /** Number of doubles per homogeneous point: x·w, y·w, w, width·w. */
    const val DIM = 4

    /** True when the spline is drawn closed (cyclic with at least 3 points). */
    fun isClosed(s: VSpline): Boolean = s.cyclic && s.points.size >= 3

    /**
     * v1.7 (item 4): the indices of [s]'s corners, ascending: its [VSplinePoint.sharp] points that
     * are interior. On a closed spline ([isClosed]) every point is interior; on an open one the
     * two ends never are (their flag is ignored, whether or not [s] was sanitized).
     */
    internal fun cornerIndices(s: VSpline): IntArray {
        val pts = s.points
        val n = pts.size
        val closed = isClosed(s)
        val from = if (closed) 0 else 1
        val until = if (closed) n else n - 1
        var count = 0
        for (i in from until until) if (pts[i].sharp) count++
        if (count == 0) return EMPTY
        val out = IntArray(count)
        var k = 0
        for (i in from until until) if (pts[i].sharp) out[k++] = i
        return out
    }

    private val EMPTY = IntArray(0)

    /**
     * v1.7 (item 4): [s] split at its interior sharp points ([cornerIndices]) into independently
     * clamped pieces, in curve order. Each piece is an open spline (`cyclic = false`) with
     * `endpoint = true` (so it starts and ends exactly on its first and last points, the corners)
     * and order `min(order, count)` of its own point count: two adjacent sharp points give a
     * 2-point piece of order 2, a straight segment. The pieces share their corners (a corner ends
     * one piece and starts the next) and keep the spline's own point instances, flags included.
     * - A closed spline is rotated so its first sharp point leads, and that point is repeated at
     *   the end: its last piece ends where its first one starts.
     * - Without an interior sharp point: `listOf(s)`, the very instance, which converts exactly
     *   as in v1.6.
     * - An open spline's Endpoint setting then applies to no piece: every piece is clamped, so the
     *   curve also reaches the spline's two ends.
     */
    fun pieces(s: VSpline): List<VSpline> {
        val corners = cornerIndices(s)
        if (corners.isEmpty()) return listOf(s)
        val pts = s.points
        val n = pts.size
        val out = ArrayList<VSpline>(corners.size + 1)
        if (isClosed(s)) {
            for (k in corners.indices) {
                val a = corners[k]
                val b = if (k + 1 < corners.size) corners[k + 1] else corners[0] + n
                out += piece(s, List(b - a + 1) { pts[(a + it) % n] })
            }
        } else {
            var a = 0
            for (c in corners) {
                out += piece(s, pts.subList(a, c + 1).toList())
                a = c
            }
            out += piece(s, pts.subList(a, n).toList())
        }
        return out
    }

    /** One clamped piece of [s] through [points] (see [pieces]). */
    private fun piece(s: VSpline, points: List<VSplinePoint>): VSpline =
        VSpline(points, order = minOf(s.order, points.size), endpoint = true, cyclic = false)

    /**
     * The knots and unrolled control polygon of [s] (expected sanitized, at least 2 points).
     * See the file docs for the three kinds of knot vectors.
     */
    fun basis(s: VSpline): NurbsBasis {
        val n = s.points.size
        require(n >= 2) { "a basis needs at least 2 points" }
        val k = s.effectiveOrder.coerceIn(2, n)
        val closed = isClosed(s)
        return when {
            closed -> {
                val m = n + k - 1
                NurbsBasis(k, DoubleArray(m + k) { it.toDouble() }, IntArray(m) { it % n }, true)
            }
            s.endpoint -> {
                val last = (n - k + 1).toDouble()
                val t = DoubleArray(n + k) { j ->
                    when {
                        j < k -> 0.0
                        j >= n -> last
                        else -> (j - k + 1).toDouble()
                    }
                }
                NurbsBasis(k, t, IntArray(n) { it }, false)
            }
            else -> NurbsBasis(k, DoubleArray(n + k) { it.toDouble() }, IntArray(n) { it }, false)
        }
    }

    /** Homogeneous coordinates of control point [i] of [s] into [out] at [at] (x·w, y·w, w, width·w). */
    private fun lift(s: VSpline, i: Int, out: DoubleArray, at: Int) {
        val p = s.points[i]
        val w = p.weight.toDouble()
        out[at] = p.x * w
        out[at + 1] = p.y * w
        out[at + 2] = w
        out[at + 3] = p.width * w
    }

    /**
     * The homogeneous Bézier control points (degree p, [DIM] doubles each, p + 1 of them) of the
     * span starting at knot index [span]: the polynomial pieces of the lifted curve, by
     * blossoming (Boehm's knot insertion to full multiplicity, done per span). [out] must hold
     * (p + 1)·[DIM] doubles; [scratch] (p + 1)·[DIM]. v1.7: [from] and [to] (within the span)
     * give the Bézier of that part of the span instead (the same polynomial, cut exactly).
     */
    fun spanBezier(
        s: VSpline, b: NurbsBasis, span: Int, out: DoubleArray, scratch: DoubleArray,
        from: Double = b.knots[span], to: Double = b.knots[span + 1],
    ) {
        val p = b.degree
        val a = from
        val c = to
        for (m in 0..p) {
            // blossom(a × (p − m), c × m)
            for (j in 0..p) lift(s, b.ctrl[span - p + j], scratch, j * DIM)
            for (r in 1..p) {
                val u = if (r <= p - m) a else c
                for (j in p downTo r) {
                    val kj = span - p + j
                    val t0 = b.knots[kj]
                    val t1 = b.knots[kj + p - r + 1]
                    val alpha = if (t1 > t0) (u - t0) / (t1 - t0) else 0.0
                    val o = j * DIM
                    val q = (j - 1) * DIM
                    for (d in 0 until DIM) scratch[o + d] = (1.0 - alpha) * scratch[q + d] + alpha * scratch[o + d]
                }
            }
            System.arraycopy(scratch, p * DIM, out, m * DIM, DIM)
        }
    }

    /**
     * The lifted curve at parameter [t] (clamped to the domain) by de Boor's algorithm: [DIM]
     * homogeneous values. Independent of [spanBezier] (tests compare the two).
     */
    fun deBoor(s: VSpline, t: Double): DoubleArray {
        val b = basis(s)
        val p = b.degree
        val tt = t.coerceIn(b.start, b.end)
        var span = b.spans.first()
        for (i in b.spans) if (b.knots[i] <= tt) span = i
        val d = DoubleArray((p + 1) * DIM)
        for (j in 0..p) lift(s, b.ctrl[span - p + j], d, j * DIM)
        for (r in 1..p) {
            for (j in p downTo r) {
                val kj = span - p + j
                val t0 = b.knots[kj]
                val t1 = b.knots[kj + p - r + 1]
                val alpha = if (t1 > t0) (tt - t0) / (t1 - t0) else 0.0
                for (k in 0 until DIM) d[j * DIM + k] = (1.0 - alpha) * d[(j - 1) * DIM + k] + alpha * d[j * DIM + k]
            }
        }
        return d.copyOfRange(p * DIM, (p + 1) * DIM)
    }

    /** The curve point at [t] (de Boor, divided out): x, y, width. */
    fun pointAt(s: VSpline, t: Double): DoubleArray {
        val h = deBoor(s, t)
        val w = h[2]
        return doubleArrayOf(h[0] / w, h[1] / w, h[3] / w)
    }

    /** The parameter range of the curve of [s] (at least 2 points). */
    fun domain(s: VSpline): ClosedFloatingPointRange<Double> {
        val b = basis(s)
        return b.start..b.end
    }

    /**
     * Evaluates the homogeneous Bézier [h] of [degree] at local parameter [u] (de Casteljau):
     * the point into [value] and its derivative d/du into [deriv] ([DIM] each). [work] holds
     * (degree + 1)·[DIM] doubles.
     */
    fun bezierAt(h: DoubleArray, degree: Int, u: Double, value: DoubleArray, deriv: DoubleArray, work: DoubleArray) {
        val n = (degree + 1) * DIM
        System.arraycopy(h, 0, work, 0, n)
        // Reduce to two points: their difference times the degree is the derivative.
        for (r in 1 until degree) {
            for (j in 0..degree - r) {
                val o = j * DIM
                for (d in 0 until DIM) work[o + d] = (1.0 - u) * work[o + d] + u * work[o + DIM + d]
            }
        }
        if (degree == 0) {
            for (d in 0 until DIM) { value[d] = work[d]; deriv[d] = 0.0 }
            return
        }
        for (d in 0 until DIM) {
            val a = work[d]
            val b = work[DIM + d]
            value[d] = (1.0 - u) * a + u * b
            deriv[d] = degree * (b - a)
        }
    }
}
