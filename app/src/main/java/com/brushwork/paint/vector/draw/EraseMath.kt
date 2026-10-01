package com.brushwork.paint.vector.draw

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Geometry of the vector eraser (v1.5 §4.9, A3). Pure Kotlin, no android.graphics, so it is unit
 * tested on the JVM.
 *
 * Positions along a polyline of n points are written as one number u = i + t: segment i (from
 * point i to point i + 1) at parameter t in 0..1, so u runs from 0 to n - 1 and grows along the
 * line. The eraser is a chain of capsules: a segment between two eraser points grown by a radius.
 */

/** Sorted, disjoint closed intervals [a, b] of a polyline parameter (overlapping or touching ones are merged). */
internal class Intervals {
    private var lo = FloatArray(8)
    private var hi = FloatArray(8)

    var size = 0
        private set

    val isEmpty: Boolean get() = size == 0

    fun start(i: Int): Float = lo[i]
    fun end(i: Int): Float = hi[i]

    /** Adds [a, b] (in any order), merging with the intervals it overlaps or touches. */
    fun add(a: Float, b: Float) {
        if (a.isNaN() || b.isNaN()) return
        var s = min(a, b)
        var e = max(a, b)
        // First interval that ends at or after s.
        var i = 0
        while (i < size && hi[i] < s) i++
        var j = i
        while (j < size && lo[j] <= e) {
            s = min(s, lo[j])
            e = max(e, hi[j])
            j++
        }
        val removed = j - i
        if (removed == 0) {
            ensure(size + 1)
            System.arraycopy(lo, i, lo, i + 1, size - i)
            System.arraycopy(hi, i, hi, i + 1, size - i)
            size++
        } else if (removed > 1) {
            System.arraycopy(lo, j, lo, i + 1, size - j)
            System.arraycopy(hi, j, hi, i + 1, size - j)
            size -= removed - 1
        }
        lo[i] = s
        hi[i] = e
    }

    fun addAll(o: Intervals) {
        for (k in 0 until o.size) add(o.lo[k], o.hi[k])
    }

    /** True when some interval overlaps [a, b] (closed). */
    fun overlaps(a: Float, b: Float): Boolean {
        for (k in 0 until size) if (lo[k] <= b && hi[k] >= a) return true
        return false
    }

    /** True when some interval reaches strictly into [a, b] (touching an end only does not count). */
    fun reachesInto(a: Float, b: Float): Boolean {
        for (k in 0 until size) if (lo[k] < b && hi[k] > a) return true
        return false
    }

    /** The parts of [from, to] that are NOT covered (the kept pieces), in order. */
    fun complement(from: Float, to: Float): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var cur = from
        for (k in 0 until size) {
            if (hi[k] < from) continue
            if (lo[k] > to) break
            if (lo[k] > cur) out += floatArrayOf(cur, min(lo[k], to))
            cur = max(cur, hi[k])
        }
        if (cur < to) out += floatArrayOf(cur, to)
        return out
    }

    fun copy(): Intervals = Intervals().also { o ->
        o.ensure(size)
        System.arraycopy(lo, 0, o.lo, 0, size)
        System.arraycopy(hi, 0, o.hi, 0, size)
        o.size = size
    }

    private fun ensure(n: Int) {
        if (n <= lo.size) return
        val cap = max(n, lo.size * 2)
        lo = lo.copyOf(cap)
        hi = hi.copyOf(cap)
    }

    override fun toString(): String = (0 until size).joinToString(prefix = "[", postfix = "]") { "${lo[it]}..${hi[it]}" }
}

/** An open or closed polyline in document px ([n] points of [xs] / [ys]). */
internal class FlatLine(val xs: FloatArray, val ys: FloatArray, val n: Int = xs.size, val closed: Boolean = false) {
    init { require(n <= xs.size && n <= ys.size) }

    val left: Float
    val top: Float
    val right: Float
    val bottom: Float

    init {
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val x = xs[i]; val y = ys[i]
            if (!x.isFinite() || !y.isFinite()) continue
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        left = l; top = t; right = r; bottom = b
    }

    val isEmpty: Boolean get() = left > right

    /** Number of segments (a closed line has its closing segment last). */
    val segments: Int get() = if (n < 2) 0 else if (closed && n > 2) n else n - 1

    /** Last parameter of the line (open lines: n - 1). */
    val uMax: Float get() = max(0, n - 1).toFloat()

    fun x(i: Int): Float = xs[i % n]
    fun y(i: Int): Float = ys[i % n]

    /** The point at parameter [u] (clamped to the line). */
    fun pointAt(u: Float, out: FloatArray = FloatArray(2)): FloatArray {
        if (n == 0) { out[0] = 0f; out[1] = 0f; return out }
        if (n == 1) { out[0] = xs[0]; out[1] = ys[0]; return out }
        val c = u.coerceIn(0f, uMax)
        val i = min(floor(c).toInt(), n - 2)
        val t = c - i
        out[0] = xs[i] + (xs[i + 1] - xs[i]) * t
        out[1] = ys[i] + (ys[i + 1] - ys[i]) * t
        return out
    }

    /** Cumulative arc length at every point (open lines; [0] = 0). */
    fun cumulative(): FloatArray {
        val c = FloatArray(max(1, n))
        for (i in 1 until n) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return c
    }

    /** Arc length at parameter [u] given [cum] = [cumulative]. */
    fun arcAt(u: Float, cum: FloatArray): Float {
        if (n < 2) return 0f
        val c = u.coerceIn(0f, uMax)
        val i = min(floor(c).toInt(), n - 2)
        return cum[i] + (cum[i + 1] - cum[i]) * (c - i)
    }

    /** The parameter at arc length [s] given [cum] = [cumulative]. */
    fun uAtArc(s: Float, cum: FloatArray): Float {
        if (n < 2) return 0f
        if (s <= 0f) return 0f
        if (s >= cum[n - 1]) return uMax
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        val len = cum[hi] - cum[lo]
        return lo + if (len > 0f) (s - cum[lo]) / len else 0f
    }
}

/** Pure geometry of the vector eraser. */
internal object EraseMath {
    private const val EPS = 1e-6f

    /**
     * The parameters t (0..1) of segment A->B that lie within [rho] of segment C->D (a capsule),
     * as [out][0]..[out][1]; false when none does. The capsule is convex, so the part of a line
     * inside it is one interval: the union of the parts inside the two end discs and the band.
     */
    fun segmentInCapsule(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
        rho: Float, out: FloatArray,
    ): Boolean {
        if (!(rho >= 0f)) return false
        val vx = bx - ax; val vy = by - ay
        val vv = vx * vx + vy * vy
        if (vv <= EPS) {
            // A point.
            if (pointSegmentDistance(ax, ay, cx, cy, dx, dy) <= rho) { out[0] = 0f; out[1] = 1f; return true }
            return false
        }
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        // End discs.
        discInterval(ax, ay, vx, vy, vv, cx, cy, rho)?.let { lo = min(lo, it.first); hi = max(hi, it.second) }
        val ex = dx - cx; val ey = dy - cy
        val len = sqrt(ex * ex + ey * ey)
        if (len > EPS) {
            discInterval(ax, ay, vx, vy, vv, dx, dy, rho)?.let { lo = min(lo, it.first); hi = max(hi, it.second) }
            // Band: local u along C->D in [0, len], w across it in [-rho, rho].
            val ux = ex / len; val uy = ey / len
            val u0 = (ax - cx) * ux + (ay - cy) * uy
            val du = vx * ux + vy * uy
            val w0 = -(ax - cx) * uy + (ay - cy) * ux
            val dw = -vx * uy + vy * ux
            var t0 = Float.NEGATIVE_INFINITY
            var t1 = Float.POSITIVE_INFINITY
            fun clip(p0: Float, dp: Float, minV: Float, maxV: Float): Boolean {
                if (abs(dp) <= EPS) return p0 in minV..maxV
                var a = (minV - p0) / dp
                var b = (maxV - p0) / dp
                if (a > b) { val s = a; a = b; b = s }
                t0 = max(t0, a)
                t1 = min(t1, b)
                return t0 <= t1
            }
            if (clip(u0, du, 0f, len) && clip(w0, dw, -rho, rho)) {
                lo = min(lo, t0)
                hi = max(hi, t1)
            }
        }
        if (lo > hi) return false
        val a = max(0f, lo)
        val b = min(1f, hi)
        if (a > b) return false
        out[0] = a
        out[1] = b
        return true
    }

    /** t interval of A + t·v inside the disc (c, rho), or null. */
    private fun discInterval(ax: Float, ay: Float, vx: Float, vy: Float, vv: Float, cx: Float, cy: Float, rho: Float): Pair<Float, Float>? {
        val fx = ax - cx; val fy = ay - cy
        val b = fx * vx + fy * vy
        val c = fx * fx + fy * fy - rho * rho
        val disc = b * b - vv * c
        if (disc < 0f) return null
        val s = sqrt(disc)
        return ((-b - s) / vv) to ((-b + s) / vv)
    }

    /** Distance from (px, py) to segment A->B. */
    fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay
        val vv = vx * vx + vy * vy
        val t = if (vv <= EPS) 0f else (((px - ax) * vx + (py - ay) * vy) / vv).coerceIn(0f, 1f)
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }

    /**
     * Adds to [out] the parameters of [line] within [rho] of the capsule segment C->D (a single
     * point line: [0, 0] when it is inside). Segments whose boxes are farther than [rho] are skipped.
     */
    fun capsuleIntervals(line: FlatLine, cx: Float, cy: Float, dx: Float, dy: Float, rho: Float, out: Intervals) {
        if (line.n == 0 || line.isEmpty) return
        val l = min(cx, dx) - rho; val r = max(cx, dx) + rho
        val t = min(cy, dy) - rho; val b = max(cy, dy) + rho
        if (line.right < l || line.left > r || line.bottom < t || line.top > b) return
        if (line.n == 1) {
            if (pointSegmentDistance(line.xs[0], line.ys[0], cx, cy, dx, dy) <= rho) out.add(0f, 0f)
            return
        }
        val tmp = FloatArray(2)
        val xs = line.xs; val ys = line.ys
        for (i in 0 until line.segments) {
            val j = if (i + 1 == line.n) 0 else i + 1
            val ax = xs[i]; val ay = ys[i]; val bx = xs[j]; val by = ys[j]
            if (max(ax, bx) < l || min(ax, bx) > r || max(ay, by) < t || min(ay, by) > b) continue
            if (segmentInCapsule(ax, ay, bx, by, cx, cy, dx, dy, rho, tmp)) out.add(i + tmp[0], i + tmp[1])
        }
    }

    /** True when some segment (or the single point) of [line] lies within [rho] of segment C->D. */
    fun touches(line: FlatLine, cx: Float, cy: Float, dx: Float, dy: Float, rho: Float): Boolean {
        if (line.n == 0 || line.isEmpty) return false
        val l = min(cx, dx) - rho; val r = max(cx, dx) + rho
        val t = min(cy, dy) - rho; val b = max(cy, dy) + rho
        if (line.right < l || line.left > r || line.bottom < t || line.top > b) return false
        if (line.n == 1) return pointSegmentDistance(line.xs[0], line.ys[0], cx, cy, dx, dy) <= rho
        val tmp = FloatArray(2)
        val xs = line.xs; val ys = line.ys
        for (i in 0 until line.segments) {
            val j = if (i + 1 == line.n) 0 else i + 1
            val ax = xs[i]; val ay = ys[i]; val bx = xs[j]; val by = ys[j]
            if (max(ax, bx) < l || min(ax, bx) > r || max(ay, by) < t || min(ay, by) > b) continue
            if (segmentInCapsule(ax, ay, bx, by, cx, cy, dx, dy, rho, tmp)) return true
        }
        return false
    }

    /**
     * True when (px, py) is inside the area of [polys] (every one implicitly closed) under the
     * even-odd ([evenOdd]) or non-zero rule.
     */
    fun inside(polys: List<FlatLine>, px: Float, py: Float, evenOdd: Boolean): Boolean {
        var winding = 0
        var crossings = 0
        for (poly in polys) {
            val n = poly.n
            if (n < 3) continue
            if (px < poly.left || px > poly.right || py < poly.top || py > poly.bottom) continue
            val xs = poly.xs; val ys = poly.ys
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val ax = xs[i]; val ay = ys[i]; val bx = xs[j]; val by = ys[j]
                val cross = (bx - ax) * (py - ay) - (px - ax) * (by - ay)
                if (ay <= py) {
                    if (by > py && cross > 0f) { winding++; crossings++ }
                } else if (by <= py && cross < 0f) { winding--; crossings++ }
            }
        }
        return if (evenOdd) crossings % 2 != 0 else winding != 0
    }

    /**
     * Parameters of [a] (open) where it crosses [b] (its closing segment included when closed),
     * sorted. Touching end points count; overlapping collinear parts do not. A segment grid over
     * [a] keeps this near linear for long lines.
     */
    fun crossings(a: FlatLine, b: FlatLine, out: MutableList<Float>) {
        if (a.n < 2 || b.n < 2) return
        if (a.right < b.left || a.left > b.right || a.bottom < b.top || a.top > b.bottom) return
        val grid = SegmentGrid.of(a)
        val bx = b.xs; val by = b.ys
        val tmp = FloatArray(2)
        for (j in 0 until b.segments) {
            val k = if (j + 1 == b.n) 0 else j + 1
            val px = bx[j]; val py = by[j]; val qx = bx[k]; val qy = by[k]
            grid.forCandidates(min(px, qx), min(py, qy), max(px, qx), max(py, qy)) { i ->
                val ax = a.xs[i]; val ay = a.ys[i]; val cx = a.xs[i + 1]; val cy = a.ys[i + 1]
                if (segmentIntersection(ax, ay, cx, cy, px, py, qx, qy, tmp)) out += i + tmp[0]
            }
        }
    }

    /**
     * Intersection of segments A->B and P->Q: [out][0] = parameter on A->B, [out][1] on P->Q.
     * False for parallel segments and when they don't meet.
     */
    fun segmentIntersection(
        ax: Float, ay: Float, bx: Float, by: Float,
        px: Float, py: Float, qx: Float, qy: Float,
        out: FloatArray,
    ): Boolean {
        val rx = bx - ax; val ry = by - ay
        val sx = qx - px; val sy = qy - py
        val den = rx * sy - ry * sx
        if (abs(den) <= 1e-9f * (abs(rx) + abs(ry) + 1f) * (abs(sx) + abs(sy) + 1f)) return false
        val wx = px - ax; val wy = py - ay
        val t = (wx * sy - wy * sx) / den
        val u = (wx * ry - wy * rx) / den
        val e = 1e-5f
        if (t < -e || t > 1f + e || u < -e || u > 1f + e) return false
        out[0] = t.coerceIn(0f, 1f)
        out[1] = u.coerceIn(0f, 1f)
        return true
    }

    /** Length of [line] between parameters [a] and [b] ([a] <= [b]). */
    fun lengthBetween(line: FlatLine, a: Float, b: Float): Float {
        if (line.n < 2 || b <= a) return 0f
        val p = line.pointAt(a)
        var px = p[0]; var py = p[1]
        var len = 0f
        var i = floor(a).toInt() + 1
        while (i < b && i < line.n) {
            len += hypot(line.xs[i] - px, line.ys[i] - py)
            px = line.xs[i]; py = line.ys[i]
            i++
        }
        val q = line.pointAt(b)
        len += hypot(q[0] - px, q[1] - py)
        return len
    }

    /**
     * The parts of the open [line] that the "to intersection" eraser removes: [line] is cut at
     * the [crossings] (sorted parameters) into pieces; a piece goes when [touched] reaches into it
     * deeper than [spill] (arc length, plus [SPILL_MARGIN]) from each of its ends, so an eraser
     * that only spills over a crossing from the neighbouring piece (or sits right on the
     * crossing) leaves this piece alone. A piece shorter than four spills uses a quarter of its
     * length instead.
     */
    fun piecesToIntersection(line: FlatLine, crossings: List<Float>, touched: Intervals, spill: Float, out: Intervals) {
        if (touched.isEmpty || line.n < 2) return
        val cum = line.cumulative()
        val cuts = ArrayList<Float>(crossings.size + 2)
        cuts += 0f
        for (c in crossings) if (c > 0f && c < line.uMax) cuts += c
        cuts += line.uMax
        for (k in 0 until cuts.size - 1) {
            val a = cuts[k]; val b = cuts[k + 1]
            if (b <= a) continue
            val sa = line.arcAt(a, cum); val sb = line.arcAt(b, cum)
            val m = min(max(0f, spill) + SPILL_MARGIN, (sb - sa) / 4f)
            val ia = line.uAtArc(sa + m, cum)
            val ib = line.uAtArc(sb - m, cum)
            if (touched.reachesInto(min(ia, ib), max(ia, ib))) out.add(a, b)
        }
    }

    /**
     * How much deeper than the spill (document px) the "to intersection" eraser must reach into
     * a piece: an eraser centered on a crossing reaches exactly its spill into both neighbours.
     */
    const val SPILL_MARGIN = 0.5f

    /** Subdivision count of a cubic for [tolerance] (as VectorPath.flattenCubic). */
    fun cubicSteps(
        p0x: Float, p0y: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, p1x: Float, p1y: Float,
        tolerance: Float,
    ): Int {
        val dd = max(hypot(p0x - c1x * 2f + c2x, p0y - c1y * 2f + c2y), hypot(c1x - c2x * 2f + p1x, c1y - c2y * 2f + p1y))
        return ceil(sqrt(0.75f * dd / tolerance.coerceAtLeast(1e-3f))).toInt().coerceIn(1, 2000)
    }

    /**
     * The part [ta]..[tb] of the cubic (p0, c1, c2, p1) as 8 values (p0x, p0y, c1x, c1y, c2x,
     * c2y, p1x, p1y), by de Casteljau.
     */
    fun subCubic(c: FloatArray, ta: Float, tb: Float): FloatArray {
        val a = ta.coerceIn(0f, 1f)
        val b = tb.coerceIn(a, 1f)
        // Left part [0, b].
        val left = splitLeft(c, b)
        if (a <= 0f) return left
        // Of that, the right part from a / b.
        val s = if (b > 0f) a / b else 0f
        return splitRight(left, s)
    }

    private fun splitLeft(c: FloatArray, t: Float): FloatArray {
        val x01 = lerp(c[0], c[2], t); val y01 = lerp(c[1], c[3], t)
        val x12 = lerp(c[2], c[4], t); val y12 = lerp(c[3], c[5], t)
        val x23 = lerp(c[4], c[6], t); val y23 = lerp(c[5], c[7], t)
        val x012 = lerp(x01, x12, t); val y012 = lerp(y01, y12, t)
        val x123 = lerp(x12, x23, t); val y123 = lerp(y12, y23, t)
        val x = lerp(x012, x123, t); val y = lerp(y012, y123, t)
        return floatArrayOf(c[0], c[1], x01, y01, x012, y012, x, y)
    }

    private fun splitRight(c: FloatArray, t: Float): FloatArray {
        val x01 = lerp(c[0], c[2], t); val y01 = lerp(c[1], c[3], t)
        val x12 = lerp(c[2], c[4], t); val y12 = lerp(c[3], c[5], t)
        val x23 = lerp(c[4], c[6], t); val y23 = lerp(c[5], c[7], t)
        val x012 = lerp(x01, x12, t); val y012 = lerp(y01, y12, t)
        val x123 = lerp(x12, x23, t); val y123 = lerp(y12, y23, t)
        val x = lerp(x012, x123, t); val y = lerp(y012, y123, t)
        return floatArrayOf(x, y, x123, y123, x23, y23, c[6], c[7])
    }

    fun cubicPoint(c: FloatArray, t: Float, out: FloatArray = FloatArray(2)): FloatArray {
        val u = 1f - t
        val a = u * u * u; val b = 3f * u * u * t; val d = 3f * u * t * t; val e = t * t * t
        out[0] = a * c[0] + b * c[2] + d * c[4] + e * c[6]
        out[1] = a * c[1] + b * c[3] + d * c[5] + e * c[7]
        return out
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}

/**
 * Buckets the segments of an open line into square cells, so the segments near a box are found
 * without testing them all.
 */
internal class SegmentGrid private constructor(
    private val line: FlatLine,
    private val cell: Float,
    private val cells: HashMap<Long, IntArrayList>?,
) {
    private val seen = HashSet<Int>()

    /** Calls [f] with every segment index whose box meets the box l, t, r, b (each at most once per call). */
    fun forCandidates(l: Float, t: Float, r: Float, b: Float, f: (Int) -> Unit) {
        val map = cells
        if (map == null) {
            // Few segments: test them all (cheap box test first).
            for (i in 0 until line.n - 1) if (meets(i, l, t, r, b)) f(i)
            return
        }
        // Boxes far larger than the grid: walk the segments instead of the cells.
        val cols = floor(r / cell) - floor(l / cell) + 1f
        val rows = floor(b / cell) - floor(t / cell) + 1f
        if (cols * rows > line.n) {
            for (i in 0 until line.n - 1) if (meets(i, l, t, r, b)) f(i)
            return
        }
        val c0 = floor(l / cell).toInt(); val c1 = floor(r / cell).toInt()
        val r0 = floor(t / cell).toInt(); val r1 = floor(b / cell).toInt()
        seen.clear()
        for (row in r0..r1) for (col in c0..c1) {
            val list = map[key(col, row)] ?: continue
            for (k in 0 until list.size) {
                val i = list[k]
                if (seen.add(i) && meets(i, l, t, r, b)) f(i)
            }
        }
    }

    private fun meets(i: Int, l: Float, t: Float, r: Float, b: Float): Boolean {
        val ax = line.xs[i]; val ay = line.ys[i]; val bx = line.xs[i + 1]; val by = line.ys[i + 1]
        return !(max(ax, bx) < l || min(ax, bx) > r || max(ay, by) < t || min(ay, by) > b)
    }

    companion object {
        /** Lines with fewer segments are tested whole. */
        private const val GRID_MIN_SEGMENTS = 48

        private fun key(col: Int, row: Int): Long = (col.toLong() shl 32) xor (row.toLong() and 0xFFFFFFFFL)

        fun of(line: FlatLine): SegmentGrid {
            val segs = line.n - 1
            if (segs < GRID_MIN_SEGMENTS || line.isEmpty) return SegmentGrid(line, 1f, null)
            // About four segments per cell along the line.
            var total = 0f
            for (i in 0 until segs) total += hypot(line.xs[i + 1] - line.xs[i], line.ys[i + 1] - line.ys[i])
            val cell = max(4f, total / segs * 4f)
            val map = HashMap<Long, IntArrayList>()
            for (i in 0 until segs) {
                val ax = line.xs[i]; val ay = line.ys[i]; val bx = line.xs[i + 1]; val by = line.ys[i + 1]
                if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) continue
                val c0 = floor(min(ax, bx) / cell).toInt(); val c1 = floor(max(ax, bx) / cell).toInt()
                val r0 = floor(min(ay, by) / cell).toInt(); val r1 = floor(max(ay, by) / cell).toInt()
                // (A huge segment spanning many cells is listed in each; bounded by the line's size.)
                if ((c1 - c0 + 1).toLong() * (r1 - r0 + 1) > 4096) {
                    return SegmentGrid(line, 1f, null)
                }
                for (row in r0..r1) for (col in c0..c1) map.getOrPut(key(col, row)) { IntArrayList() }.add(i)
            }
            return SegmentGrid(line, cell, map)
        }
    }
}

/** A growable int list. */
internal class IntArrayList {
    private var data = IntArray(4)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
}
