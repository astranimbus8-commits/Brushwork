package com.brushwork.paint.tools.points

import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeAnchor
import com.brushwork.paint.tools.vector.ShapePoints
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** v1.9 (R7, design 2.4.10): a point of the line [PointBevel] works on; [width] is the caller's per-point width. */
data class BevelPoint(val x: Float, val y: Float, val width: Float = 1f)

/**
 * v1.9 (R7, design 2.4.10; frozen): [com.brushwork.paint.ui.common.PointLabels19.BEVEL] for Polyline (A1) and Shape (A9). Pure math:
 * the callers own their data, the step and the popup's values ([com.brushwork.paint.ui.points.BevelPopup]).
 */
object PointBevel {
    const val MAX_SEGMENTS = 8
    const val MAX_POINTS_POLYLINE = 2000
    const val MAX_POINTS_SHAPE = 1000

    /** The range of the amount: a fraction of the shorter edge next to the corner. */
    const val MIN_AMOUNT = 0.01f
    const val MAX_AMOUNT = 0.5f
    const val MIN_SEGMENTS = 1

    /** Edges shorter than this (px) have no direction: their corners are not bevelled. */
    private const val EDGE_EPS = 1e-4f

    sealed interface Result {
        /**
         * points: the new list; selected: the indices of every point the bevel made; origin: for each
         * output index, the source index it keeps, or -1 for a point the bevel made. Output index 0 is
         * source point 0, or the first point of its bevel when point 0 was bevelled (also on a closed line).
         */
        data class Ok(
            val points: List<BevelPoint>,
            val selected: Set<Int>,
            val bevelled: Int,
            val origin: IntArray,
        ) : Result {
            override fun equals(other: Any?): Boolean =
                other is Ok && points == other.points && selected == other.selected &&
                    bevelled == other.bevelled && origin.contentEquals(other.origin)

            override fun hashCode(): Int =
                ((points.hashCode() * 31 + selected.hashCode()) * 31 + bevelled) * 31 + origin.contentHashCode()
        }

        data object NoSharpCorner : Result
        data class TooMany(val max: Int) : Result
    }

    /**
     * Bevels each selected sharp corner. [sharp] is the caller's predicate: Polyline, every inner
     * point and every point of a closed line; Shape (A9), `!a.smooth && !a.hasExplicitHandles &&
     * effectiveRadius(a) == 0f` and both adjacent segments straight, where a segment (p, q) is straight
     * when `p.handleOut == null && q.handleIn == null && !p.smooth && !q.smooth`. Ends of an open line
     * are skipped. [amount] 0.01..0.5 is the cut distance as a fraction of the shorter adjacent edge.
     * [segments] 1..8: 1 is a chamfer (one point becomes 2); n > 1 puts n + 1 points on the circular arc
     * tangent to both edges. Widths are interpolated along the new points. The caller copies its own
     * per-point data through `origin`; on Shape a new anchor is not smooth, has no handles and an
     * explicit radius of 0f, so the shape's corner radius does not round it.
     *
     * Also: a corner needs a line of at least 3 points and two adjacent edges of nonzero length.
     * [amount] and [segments] are clamped to their ranges (a non-finite amount counts as 0.01).
     * [TooMany] when the result would have more than [maxPoints] points; nothing changes then.
     */
    fun bevel(
        points: List<BevelPoint>,
        closed: Boolean,
        sharp: (Int) -> Boolean,
        selected: Set<Int>,
        amount: Float,
        segments: Int,
        maxPoints: Int,
    ): Result {
        val n = points.size
        val corners = (0 until n).filter { i -> i in selected && isCorner(points, closed, i) && sharp(i) }
        if (corners.isEmpty()) return Result.NoSharpCorner
        val a = clampAmount(amount)
        val s = clampSegments(segments)
        if (n.toLong() + corners.size.toLong() * s > maxPoints) return Result.TooMany(maxPoints)
        val bevelled = corners.toHashSet()
        val out = ArrayList<BevelPoint>(n + corners.size * s)
        val origin = ArrayList<Int>(n + corners.size * s)
        val made = LinkedHashSet<Int>()
        for (i in 0 until n) {
            if (i !in bevelled) {
                out += points[i]
                origin += i
                continue
            }
            for (p in cornerPoints(points[(i - 1 + n) % n], points[i], points[(i + 1) % n], a, s)) {
                made += out.size
                out += p
                origin += -1
            }
        }
        return Result.Ok(out, made, corners.size, origin.toIntArray())
    }

    /** The amount in 0.01..0.5 (a non-finite one counts as 0.01). */
    fun clampAmount(amount: Float): Float = if (amount.isFinite()) amount.coerceIn(MIN_AMOUNT, MAX_AMOUNT) else MIN_AMOUNT

    /** The segment count in 1..8. */
    fun clampSegments(segments: Int): Int = segments.coerceIn(MIN_SEGMENTS, MAX_SEGMENTS)

    /** Polyline's predicate (A1): every inner point, and every point of a closed line. */
    fun polylineSharp(count: Int, closed: Boolean): (Int) -> Boolean = { i -> i in 0 until count && (closed || (i > 0 && i < count - 1)) }

    /**
     * Shape's predicate (A9), as in [bevel]: the point is not smooth, has no handles of its own and
     * no roundness ([ShapePoints.cornerStyleOf] SHARP, or [ShapePoints.cornerRadiusOf] 0; an open
     * outline is never rounded), and both adjacent segments are straight.
     */
    fun shapeSharp(anchors: List<ShapeAnchor>, closed: Boolean, corner: CornerStyle, radius: Float): (Int) -> Boolean = { i ->
        val n = anchors.size
        if (i !in 0 until n || n < ShapePoints.MIN_CLOSED || (!closed && (i == 0 || i == n - 1))) {
            false
        } else {
            val p = anchors[i]
            val prev = anchors[(i - 1 + n) % n]
            val next = anchors[(i + 1) % n]
            !p.smooth && !p.hasExplicitHandles && effectiveRadius(p, closed, corner, radius) == 0f &&
                straight(prev, p) && straight(p, next)
        }
    }

    private fun effectiveRadius(p: ShapeAnchor, closed: Boolean, corner: CornerStyle, radius: Float): Float = when {
        !closed -> 0f
        ShapePoints.cornerStyleOf(p, corner) == CornerStyle.SHARP -> 0f
        else -> ShapePoints.cornerRadiusOf(p, radius).coerceAtLeast(0f)
    }

    private fun straight(p: ShapeAnchor, q: ShapeAnchor): Boolean = p.handleOut == null && q.handleIn == null && !p.smooth && !q.smooth

    /** Point [i] has two edges of nonzero length (an inner point, or any point of a closed line of 3 or more). */
    private fun isCorner(points: List<BevelPoint>, closed: Boolean, i: Int): Boolean {
        val n = points.size
        if (n < 3 || i !in 0 until n) return false
        if (!closed && (i == 0 || i == n - 1)) return false
        val p = points[i]
        val prev = points[(i - 1 + n) % n]
        val next = points[(i + 1) % n]
        val lp = hypot(prev.x - p.x, prev.y - p.y)
        val ln = hypot(next.x - p.x, next.y - p.y)
        return lp.isFinite() && ln.isFinite() && lp > EDGE_EPS && ln > EDGE_EPS
    }

    /**
     * The points replacing corner [p] (between [prev] and [next]): [segments] + 1 of them, from the
     * cut on the incoming edge to the cut on the outgoing one. One segment is the straight chamfer;
     * more lie on the circle tangent to both edges at the cuts.
     */
    private fun cornerPoints(prev: BevelPoint, p: BevelPoint, next: BevelPoint, amount: Float, segments: Int): List<BevelPoint> {
        val lp = hypot(prev.x - p.x, prev.y - p.y)
        val ln = hypot(next.x - p.x, next.y - p.y)
        val d = amount * minOf(lp, ln)
        val upx = (prev.x - p.x) / lp
        val upy = (prev.y - p.y) / lp
        val unx = (next.x - p.x) / ln
        val uny = (next.y - p.y) / ln
        val ax = p.x + upx * d
        val ay = p.y + upy * d
        val bx = p.x + unx * d
        val by = p.y + uny * d
        val wa = p.width + (prev.width - p.width) * (d / lp)
        val wb = p.width + (next.width - p.width) * (d / ln)
        val first = BevelPoint(ax, ay, wa)
        val last = BevelPoint(bx, by, wb)
        if (segments <= 1) return listOf(first, last)
        val out = ArrayList<BevelPoint>(segments + 1)
        out += first
        // The bisector of the corner; near zero the corner is (almost) straight and the arc is the chord.
        val sx = upx + unx
        val sy = upy + uny
        val sl = hypot(sx, sy)
        val cosHalf = sl / 2f // |u + v| = 2 cos(theta / 2) for unit u, v at angle theta
        if (sl < 1e-4f || cosHalf < 1e-4f) {
            for (k in 1 until segments) {
                val t = k.toFloat() / segments
                out += BevelPoint(ax + (bx - ax) * t, ay + (by - ay) * t, wa + (wb - wa) * t)
            }
        } else {
            // Centre on the bisector at d / cos(theta / 2) from the corner; the arc runs from the cut
            // on the incoming edge to the one on the outgoing edge, the short way (it faces the corner).
            val dist = d / cosHalf
            val cx = p.x + sx / sl * dist
            val cy = p.y + sy / sl * dist
            val a0 = atan2(ay - cy, ax - cx)
            var sweep = atan2(by - cy, bx - cx) - a0
            while (sweep > Math.PI) sweep -= (2 * Math.PI).toFloat()
            while (sweep < -Math.PI) sweep += (2 * Math.PI).toFloat()
            // Radius d tan(theta / 2): the distance from the centre to either cut.
            val r = hypot(ax - cx, ay - cy)
            for (k in 1 until segments) {
                val t = k.toFloat() / segments
                val ang = a0 + sweep * t
                out += BevelPoint(cx + r * cos(ang), cy + r * sin(ang), wa + (wb - wa) * t)
            }
        }
        out += last
        return out
    }
}
