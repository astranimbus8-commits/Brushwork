package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Pure-Kotlin vector path model shared by the shape, curve and polyline tools. It has no
 * android.graphics dependency so all geometry is unit-tested on the JVM; VectorRender.kt
 * converts it to an android.graphics.Path for drawing.
 */

/** One drawing command of a [VectorPath]. Coordinates are document pixels. */
sealed interface PathOp {
    data class MoveTo(val p: Vec2) : PathOp
    data class LineTo(val p: Vec2) : PathOp
    data class CubicTo(val c1: Vec2, val c2: Vec2, val p: Vec2) : PathOp
    data object Close : PathOp
}

/** Axis-aligned bounds in document pixels. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun union(o: Bounds) = Bounds(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun outset(d: Float) = Bounds(left - d, top - d, right + d, bottom + d)
    operator fun contains(p: Vec2) = p.x in left..right && p.y in top..bottom

    companion object {
        fun of(points: Iterable<Vec2>): Bounds? {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            var any = false
            for (p in points) {
                any = true
                if (p.x < l) l = p.x
                if (p.x > r) r = p.x
                if (p.y < t) t = p.y
                if (p.y > b) b = p.y
            }
            return if (any) Bounds(l, t, r, b) else null
        }
    }
}

/** A flattened sub-path. */
data class Polyline(val points: List<Vec2>, val closed: Boolean)

/** Immutable path made of lines and cubic Beziers (possibly several sub-paths). */
class VectorPath(val ops: List<PathOp>) {

    val isEmpty: Boolean get() = ops.none { it is PathOp.LineTo || it is PathOp.CubicTo }

    /** Applies [f] to every point (exact for affine maps such as rotation + translation). */
    fun transformed(f: (Vec2) -> Vec2): VectorPath = VectorPath(ops.map { op ->
        when (op) {
            is PathOp.MoveTo -> PathOp.MoveTo(f(op.p))
            is PathOp.LineTo -> PathOp.LineTo(f(op.p))
            is PathOp.CubicTo -> PathOp.CubicTo(f(op.c1), f(op.c2), f(op.p))
            PathOp.Close -> PathOp.Close
        }
    })

    /** Bounds of all points including Bezier control points (a conservative superset). */
    fun controlBounds(): Bounds? = Bounds.of(ops.asSequence().flatMap { op ->
        when (op) {
            is PathOp.MoveTo -> sequenceOf(op.p)
            is PathOp.LineTo -> sequenceOf(op.p)
            is PathOp.CubicTo -> sequenceOf(op.c1, op.c2, op.p)
            PathOp.Close -> emptySequence()
        }
    }.asIterable())

    /** Tight bounds of the flattened geometry. */
    fun bounds(tolerance: Float = 0.1f): Bounds? = Bounds.of(flatten(tolerance).flatMap { it.points })

    /** Approximates every sub-path by a polyline whose deviation is at most [tolerance] px. */
    fun flatten(tolerance: Float = 0.25f): List<Polyline> {
        val out = ArrayList<Polyline>()
        var cur: ArrayList<Vec2>? = null
        var start = Vec2.ZERO
        var last = Vec2.ZERO
        fun finish(closed: Boolean) {
            val c = cur
            if (c != null && c.size >= 1) out += Polyline(c, closed)
            cur = null
        }
        for (op in ops) {
            when (op) {
                is PathOp.MoveTo -> {
                    finish(false)
                    cur = arrayListOf(op.p); start = op.p; last = op.p
                }
                is PathOp.LineTo -> {
                    val c = cur ?: arrayListOf(last).also { cur = it; start = last }
                    c += op.p; last = op.p
                }
                is PathOp.CubicTo -> {
                    val c = cur ?: arrayListOf(last).also { cur = it; start = last }
                    flattenCubic(last, op.c1, op.c2, op.p, tolerance, c)
                    last = op.p
                }
                PathOp.Close -> {
                    finish(true)
                    last = start
                }
            }
        }
        finish(false)
        return out
    }

    companion object {
        val EMPTY = VectorPath(emptyList())

        /** Closed polygon through [points]. */
        fun polygon(points: List<Vec2>): VectorPath {
            if (points.isEmpty()) return EMPTY
            val ops = ArrayList<PathOp>(points.size + 1)
            ops += PathOp.MoveTo(points[0])
            for (i in 1 until points.size) ops += PathOp.LineTo(points[i])
            ops += PathOp.Close
            return VectorPath(ops)
        }

        /** Open polyline through [points]. */
        fun polyline(points: List<Vec2>): VectorPath {
            if (points.isEmpty()) return EMPTY
            val ops = ArrayList<PathOp>(points.size)
            ops += PathOp.MoveTo(points[0])
            for (i in 1 until points.size) ops += PathOp.LineTo(points[i])
            return VectorPath(ops)
        }

        /** Point on the cubic Bezier at parameter [t]. */
        fun cubicPoint(p0: Vec2, c1: Vec2, c2: Vec2, p1: Vec2, t: Float): Vec2 {
            val u = 1f - t
            val a = u * u * u; val b = 3f * u * u * t; val c = 3f * u * t * t; val d = t * t * t
            return Vec2(a * p0.x + b * c1.x + c * c2.x + d * p1.x, a * p0.y + b * c1.y + c * c2.y + d * p1.y)
        }

        /**
         * Appends points of the cubic (excluding [p0]) to [out]. The number of uniform steps comes
         * from the second-difference bound: error <= 0.75 * maxSecondDiff / n^2.
         */
        fun flattenCubic(p0: Vec2, c1: Vec2, c2: Vec2, p1: Vec2, tolerance: Float, out: MutableList<Vec2>) {
            val dd = max((p0 - c1 * 2f + c2).length, (c1 - c2 * 2f + p1).length)
            val n = ceil(sqrt(0.75f * dd / tolerance.coerceAtLeast(1e-3f))).toInt().coerceIn(1, 2000)
            for (i in 1..n) out += cubicPoint(p0, c1, c2, p1, i.toFloat() / n)
        }

        /**
         * Resamples a polyline at even arc-length [spacing]. The first point is always included,
         * the last point too (so the final gap may be shorter than [spacing]).
         */
        fun resample(points: List<Vec2>, spacing: Float, closed: Boolean = false): List<Vec2> {
            if (points.isEmpty()) return emptyList()
            val pts = if (closed && points.size > 1 && points.first() != points.last()) points + points.first() else points
            val step = spacing.coerceAtLeast(1e-3f)
            val out = ArrayList<Vec2>()
            out += pts[0]
            var carry = 0f // distance travelled since the last emitted sample
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val seg = a.distanceTo(b)
                if (seg <= 0f) continue
                var d = step - carry
                while (d <= seg) {
                    out += a.lerp(b, d / seg)
                    d += step
                }
                carry = seg - (d - step)
            }
            val end = pts.last()
            if (out.size > 1 && out.last().distanceTo(end) <= 1e-3f) out[out.lastIndex] = end else if (out.last() != end) out += end
            return out
        }

        /** Total length of a polyline. */
        fun length(points: List<Vec2>): Float {
            var s = 0f
            for (i in 1 until points.size) s += points[i - 1].distanceTo(points[i])
            return s
        }
    }
}
