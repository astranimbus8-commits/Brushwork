package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The filled outline of a line whose width varies along it (per-point thickness, §4.5; v1.5,
 * owned by A4, API frozen). F2 writes a reference; A4 the production version (round joins, caps,
 * closed paths).
 *
 * F2 reference (correct but simple): the union of a disc at every point (round joins and caps)
 * and, between consecutive points, the hull of their two discs (the quad between the discs'
 * outer tangents). Every piece is wound the same way, so filling the result with the non-zero
 * rule (android's default) gives their union. Many small sub-paths: fine for drawing, not for
 * export (A4 builds a single contour).
 */
object VariableWidthOutline {
    /**
     * Outline of the polyline [xs]/[ys] (first [n] points, document px) with full widths
     * [widths] per point; [closed] joins the ends. Flattening [tolerance] in px.
     */
    fun build(xs: FloatArray, ys: FloatArray, widths: FloatArray, n: Int, closed: Boolean = false, tolerance: Float = 0.25f): VectorPath {
        val m = min4(n, xs.size, ys.size, widths.size)
        if (m <= 0) return VectorPath.EMPTY
        val tol = if (tolerance.isFinite() && tolerance > 0f) tolerance else 0.25f
        val ops = ArrayList<PathOp>(m * 8)
        fun r(i: Int): Float {
            val w = widths[i]
            return if (w.isFinite() && w > 0f) w / 2f else 0f
        }
        fun ok(i: Int) = xs[i].isFinite() && ys[i].isFinite()
        for (i in 0 until m) if (ok(i)) disc(xs[i], ys[i], r(i), tol, ops)
        val segs = if (closed && m > 2) m else m - 1
        for (s in 0 until segs) {
            val a = s
            val b = (s + 1) % m
            if (ok(a) && ok(b)) hull(xs[a], ys[a], r(a), xs[b], ys[b], r(b), ops)
        }
        return if (ops.isEmpty()) VectorPath.EMPTY else VectorPath(ops)
    }

    /**
     * A disc of radius [r] at ([cx], [cy]) as cubic arcs, wound like [hull]'s quads (negative
     * signed area in x-right / y-down coordinates, angles decreasing). Four arcs are within
     * 0.03 % of the radius; large discs get eight.
     */
    private fun disc(cx: Float, cy: Float, r: Float, tol: Float, ops: MutableList<PathOp>) {
        if (r < 1e-3f) return
        val arcs = if (r * 2.8e-4f <= tol) 4 else 8
        val step = (-2.0 * PI / arcs).toFloat()
        val k = (4.0 / 3.0 * tan(step / 4.0)).toFloat()
        var a0 = 0f
        var p0 = Vec2(cx + r, cy)
        ops += PathOp.MoveTo(p0)
        for (i in 0 until arcs) {
            val a1 = if (i == arcs - 1) (-2.0 * PI).toFloat() else a0 + step
            val p1 = if (i == arcs - 1) Vec2(cx + r, cy) else Vec2(cx + r * cos(a1), cy + r * sin(a1))
            val c1 = Vec2(p0.x - k * r * sin(a0), p0.y + k * r * cos(a0))
            val c2 = Vec2(p1.x + k * r * sin(a1), p1.y - k * r * cos(a1))
            ops += PathOp.CubicTo(c1, c2, p1)
            a0 = a1
            p0 = p1
        }
        ops += PathOp.Close
    }

    /**
     * The quad between the outer tangents of the discs ([ax], [ay], [ra]) and ([bx], [by], [rb]):
     * nothing when one disc holds the other (it is drawn already).
     */
    private fun hull(ax: Float, ay: Float, ra: Float, bx: Float, by: Float, rb: Float, ops: MutableList<PathOp>) {
        val d = hypot(bx - ax, by - ay)
        if (d < 1e-6f || d <= abs(ra - rb) || (ra <= 0f && rb <= 0f)) return
        val ux = (bx - ax) / d
        val uy = (by - ay) / d
        val vx = -uy
        val vy = ux
        val s = (ra - rb) / d
        val c = sqrt((1f - s * s).coerceAtLeast(0f))
        // Unit normals of the two outer tangents: n·(b - a) = ra - rb.
        val p1x = s * ux + c * vx; val p1y = s * uy + c * vy
        val m1x = s * ux - c * vx; val m1y = s * uy - c * vy
        ops += PathOp.MoveTo(Vec2(ax + ra * p1x, ay + ra * p1y))
        ops += PathOp.LineTo(Vec2(bx + rb * p1x, by + rb * p1y))
        ops += PathOp.LineTo(Vec2(bx + rb * m1x, by + rb * m1y))
        ops += PathOp.LineTo(Vec2(ax + ra * m1x, ay + ra * m1y))
        ops += PathOp.Close
    }

    private fun min4(a: Int, b: Int, c: Int, d: Int): Int = min(min(a, b), min(c, d))
}
