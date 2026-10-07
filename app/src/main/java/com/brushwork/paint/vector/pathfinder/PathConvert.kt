package com.brushwork.paint.vector.pathfinder

import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import androidx.annotation.RequiresApi
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.tools.vector.toAndroidPath
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorOps
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * Pathfinder's conversions (v1.7 item 20, §3.20; area G), in document pixels:
 * - an operand to the `android.graphics.Path` region `Path.op` (Skia PathOps) works on: a
 *   [VPath]'s outline under its fill rule (an open sub-path closes implicitly: its filled
 *   region), a [VShape]'s or a shape layer's [ShapeObject] outline (a line's is empty);
 * - a result back to lines and cubics ([contours], [toVectorPath]) read with `PathIterator`:
 *   the platform's on API 34+, androidx's `graphics-path` below (its JNI library loads in a
 *   static initializer, so it lives in [AndroidxReader] and a JVM test never touches it). Conics
 *   become quads within [CONIC_TOLERANCE] px and quads become cubics (exact);
 * - the area of a region ([area]) and its connected pieces ([components]), for Divide and the
 *   sliver rule.
 */
object PathConvert {
    /** Conics are split into quads whose distance to the conic is at most this (px). */
    const val CONIC_TOLERANCE = 0.25f

    /** Flattening tolerance of [area] and [components] (px). */
    private const val FLATTEN = 0.05f

    // ------------------------------------------------------------------ operands

    /** The region of [o]: null for a brush stroke (never an operand). */
    fun region(o: VObject): Path? = when (o) {
        is VPath -> region(o)
        is VShape -> region(o.shape)
        is VStroke -> null
    }

    /** The interior of [p]'s outline under its fill rule (whether or not it is filled). */
    fun region(p: VPath): Path = VectorOps.toVectorPath(p).toAndroidPath(Path()).also { it.fillType = fillTypeOf(p.fillRule) }

    /** The interior of a shape's outline (a shape layer's, or a [VShape]'s). */
    fun region(shape: ShapeObject): Path = ShapeOutlines.outline(shape).toAndroidPath(Path()).also { it.fillType = Path.FillType.WINDING }

    fun fillTypeOf(rule: VFillRule): Path.FillType = if (rule == VFillRule.EVENODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING

    /** The fill rule of a result: Skia's (even-odd for its op results). */
    fun ruleOf(path: Path): VFillRule = when (path.fillType) {
        Path.FillType.EVEN_ODD, Path.FillType.INVERSE_EVEN_ODD -> VFillRule.EVENODD
        else -> VFillRule.NONZERO
    }

    // ------------------------------------------------------------------ reading back

    /** One segment of a [Contour]: a line ([c1] and [c2] null) or a cubic. */
    class Seg(val p0: Vec2, val c1: Vec2?, val c2: Vec2?, val p1: Vec2) {
        val isCubic: Boolean get() = c1 != null

        /** The point at [t] (0..1). */
        fun at(t: Float): Vec2 {
            if (c1 == null || c2 == null) return p0.lerp(p1, t)
            val u = 1f - t
            val a = u * u * u; val b = 3f * u * u * t; val c = 3f * u * t * t; val d = t * t * t
            return Vec2(a * p0.x + b * c1.x + c * c2.x + d * p1.x, a * p0.y + b * c1.y + c * c2.y + d * p1.y)
        }

        /** The same segment run backwards. */
        fun reversed(): Seg = Seg(p1, c2, c1, p0)
    }

    /** A contour of a path: its segments in order; a closed one ends where it starts (the closing line included). */
    class Contour(val segs: List<Seg>, val closed: Boolean)

    /** [path]'s contours as lines and cubics (zero-length lines dropped). */
    fun contours(path: Path): List<Contour> {
        val b = ContourBuilder()
        if (Build.VERSION.SDK_INT >= 34) PlatformReader.read(path, b) else AndroidxReader.read(path, b)
        b.finish(false)
        return b.out
    }

    /** [contours] as a [VectorPath] (Move, Line, Cubic, Close). */
    fun toVectorPath(contours: List<Contour>): VectorPath {
        val ops = ArrayList<PathOp>()
        for (c in contours) {
            if (c.segs.isEmpty()) continue
            ops += PathOp.MoveTo(c.segs[0].p0)
            for (s in c.segs) ops += if (s.c1 != null && s.c2 != null) PathOp.CubicTo(s.c1, s.c2, s.p1) else PathOp.LineTo(s.p1)
            if (c.closed) ops += PathOp.Close
        }
        return if (ops.isEmpty()) VectorPath.EMPTY else VectorPath(ops)
    }

    fun toVectorPath(path: Path): VectorPath = toVectorPath(contours(path))

    /** [path] as the sub-paths of a [VPath]: sharp anchors with explicit handles (`VectorOps.subpathsOf`). */
    fun subpaths(path: Path): List<VSubpath> = VectorOps.subpathsOf(toVectorPath(path))

    /** [contours] as an android path with [fillType]. */
    fun toPath(contours: List<Contour>, fillType: Path.FillType): Path =
        toVectorPath(contours).toAndroidPath(Path()).also { it.fillType = fillType }

    // ------------------------------------------------------------------ area and pieces

    /**
     * Area (px²) of the region [path] fills, whatever its fill rule. [simple]: [path] is already
     * non-overlapping contours (a `Path.op` result), so it isn't simplified first.
     */
    fun area(path: Path, simple: Boolean = false): Float {
        if (path.isEmpty) return 0f
        val r = RectF()
        @Suppress("DEPRECATION") path.computeBounds(r, true)
        if (r.width() * r.height() <= 0f) return 0f
        val s = if (simple) path else simplified(path) ?: return 0f
        val polys = polygons(contours(s))
        var sum = 0.0
        for (i in polys.indices) {
            val a = abs(polys[i].area)
            sum += if (depth(polys, i) % 2 == 0) a else -a
        }
        return abs(sum).toFloat()
    }

    /**
     * The connected pieces of [path]'s region: each outer contour with the holes directly inside
     * it, as its own even-odd path (one piece: the simplified path itself). [simple] as for [area].
     */
    fun components(path: Path, simple: Boolean = false): List<Path> {
        if (path.isEmpty) return emptyList()
        val s = if (simple) path else simplified(path) ?: return emptyList()
        val cs = contours(s)
        if (cs.size <= 1) return if (cs.isEmpty()) emptyList() else listOf(s)
        val polys = polygons(cs)
        val depths = IntArray(polys.size) { depth(polys, it) }
        val outers = polys.indices.filter { depths[it] % 2 == 0 }
        if (outers.size <= 1) return listOf(s)
        val members = HashMap<Int, MutableList<Int>>()
        for (o in outers) members[o] = mutableListOf(o)
        for (h in polys.indices) {
            if (depths[h] % 2 == 0) continue
            // The hole's outer: the contour one level up that contains it.
            val p = polys[h].sample
            val parent = outers.firstOrNull { depths[it] == depths[h] - 1 && polys[it].contains(p) } ?: continue
            members[parent]!! += h
        }
        return outers.map { o -> toPath(members[o]!!.map { cs[polys[it].index] }, Path.FillType.EVEN_ODD) }
    }

    /** [path] as non-overlapping contours (Skia's union with nothing); null when Skia gives up. */
    private fun simplified(path: Path): Path? = op(path, Path(), Path.Op.UNION)

    /** [a] [op] [b] (Skia PathOps: non-overlapping contours); null when Skia gives up. */
    fun op(a: Path, b: Path, op: Path.Op): Path? {
        val out = Path()
        return if (out.op(a, b, op)) out else null
    }

    /** A flattened closed contour: its points, signed area and a point on its boundary (an edge midpoint). */
    private class Poly(val index: Int, val xs: FloatArray, val ys: FloatArray, val area: Double, val sample: Vec2) {
        val n: Int get() = xs.size

        /** Even-odd point-in-polygon. */
        fun contains(p: Vec2): Boolean {
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val yi = ys[i]; val yj = ys[j]
                if ((yi > p.y) != (yj > p.y)) {
                    val x = xs[i] + (p.y - yi) / (yj - yi) * (xs[j] - xs[i])
                    if (p.x < x) inside = !inside
                }
                j = i
            }
            return inside
        }
    }

    private fun polygons(cs: List<Contour>): List<Poly> {
        val out = ArrayList<Poly>(cs.size)
        for ((i, c) in cs.withIndex()) {
            val pts = ArrayList<Vec2>()
            for (s in c.segs) {
                if (pts.isEmpty()) pts += s.p0
                if (s.isCubic) {
                    val n = cubicSteps(s)
                    for (k in 1..n) pts += s.at(k.toFloat() / n)
                } else {
                    pts += s.p1
                }
            }
            if (pts.size < 3) continue
            val xs = FloatArray(pts.size) { pts[it].x }
            val ys = FloatArray(pts.size) { pts[it].y }
            var a = 0.0
            var j = pts.size - 1
            for (k in pts.indices) {
                a += xs[j].toDouble() * ys[k] - xs[k].toDouble() * ys[j]
                j = k
            }
            val m = c.segs[0].at(0.5f)
            out += Poly(i, xs, ys, a / 2.0, m)
        }
        return out
    }

    /** How many other contours contain contour [i] (its nesting depth). */
    private fun depth(polys: List<Poly>, i: Int): Int {
        val p = polys[i].sample
        var d = 0
        for (k in polys.indices) if (k != i && polys[k].contains(p)) d++
        return d
    }

    /** Steps flattening [s] within [FLATTEN] px. */
    internal fun cubicSteps(s: Seg): Int {
        val c1 = s.c1 ?: return 1
        val c2 = s.c2 ?: return 1
        val dd = maxOf(
            hypot(s.p0.x - 2f * c1.x + c2.x, s.p0.y - 2f * c1.y + c2.y),
            hypot(c1.x - 2f * c2.x + s.p1.x, c1.y - 2f * c2.y + s.p1.y),
        )
        return ceil(sqrt(0.75f * dd / FLATTEN)).toInt().coerceIn(1, 256)
    }

    // ------------------------------------------------------------------ readers

    /** Collects segments into [Contour]s. */
    private class ContourBuilder {
        val out = ArrayList<Contour>()
        private var segs = ArrayList<Seg>()
        private var start = Vec2.ZERO
        private var last = Vec2.ZERO

        fun move(x: Float, y: Float) {
            finish(false)
            start = Vec2(x, y); last = start
        }

        fun line(x: Float, y: Float) {
            val p = Vec2(x, y)
            if (p != last) segs += Seg(last, null, null, p)
            last = p
        }

        fun quad(x1: Float, y1: Float, x2: Float, y2: Float) {
            // Exact: the cubic's handles are 2/3 of the way to the quad's control point.
            val p0 = last
            val q = Vec2(x1, y1)
            val p = Vec2(x2, y2)
            cubic(p0.x + 2f / 3f * (q.x - p0.x), p0.y + 2f / 3f * (q.y - p0.y), p.x + 2f / 3f * (q.x - p.x), p.y + 2f / 3f * (q.y - p.y), p.x, p.y)
        }

        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val p = Vec2(x3, y3)
            segs += Seg(last, Vec2(x1, y1), Vec2(x2, y2), p)
            last = p
        }

        /** A conic as 2^k quads (Skia's chop at t = 1/2, k from its error estimate). */
        fun conic(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) {
            if (!w.isFinite() || w <= 0f) { line(x2, y2); return }
            val a = w - 1f
            val k = a / (4f * (2f + a))
            val ex = k * (last.x - 2f * x1 + x2)
            val ey = k * (last.y - 2f * y1 + y2)
            val err = sqrt(ex * ex + ey * ey)
            val pow2 = if (err <= CONIC_TOLERANCE) 0 else ceil(log2(err / CONIC_TOLERANCE) / 2f).toInt().coerceIn(0, 5)
            chop(last.x, last.y, x1, y1, x2, y2, w, pow2)
        }

        private fun chop(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, depth: Int) {
            if (depth == 0) { quad(x1, y1, x2, y2); return }
            val scale = 1f / (1f + w)
            val nw = sqrt(0.5f + w * 0.5f)
            val wx = w * x1; val wy = w * y1
            val mx = (x0 + 2f * wx + x2) * scale * 0.5f
            val my = (y0 + 2f * wy + y2) * scale * 0.5f
            chop(x0, y0, (x0 + wx) * scale, (y0 + wy) * scale, mx, my, nw, depth - 1)
            chop(mx, my, (wx + x2) * scale, (wy + y2) * scale, x2, y2, nw, depth - 1)
        }

        fun close() {
            if (segs.isNotEmpty() && last != start) segs += Seg(last, null, null, start)
            finish(true)
            last = start
        }

        fun finish(closed: Boolean) {
            if (segs.isNotEmpty()) out += Contour(segs, closed)
            segs = ArrayList()
        }
    }

    /**
     * API 34+: the platform's iterator, read with `next(points, 0)` only. A conic's weight is the
     * 7th float (`points[6]`, where `next()` takes its `conicWeight` from). Never `peek()`: after
     * `hasNext()` (which reads the next segment ahead) it answers the segment after that one, so
     * a conic-and-peek reader takes the move before a conic for the conic.
     */
    @RequiresApi(34)
    private object PlatformReader {
        fun read(path: Path, b: ContourBuilder) {
            val it = path.pathIterator
            val pts = FloatArray(8)
            while (it.hasNext()) {
                when (it.next(pts, 0)) {
                    android.graphics.PathIterator.VERB_MOVE -> b.move(pts[0], pts[1])
                    android.graphics.PathIterator.VERB_LINE -> b.line(pts[2], pts[3])
                    android.graphics.PathIterator.VERB_QUAD -> b.quad(pts[2], pts[3], pts[4], pts[5])
                    android.graphics.PathIterator.VERB_CONIC -> b.conic(pts[2], pts[3], pts[4], pts[5], pts[6])
                    android.graphics.PathIterator.VERB_CUBIC -> b.cubic(pts[2], pts[3], pts[4], pts[5], pts[6], pts[7])
                    android.graphics.PathIterator.VERB_CLOSE -> b.close()
                    else -> return
                }
            }
        }
    }

    /** API 26..33: androidx's iterator, conics already as quads. */
    private object AndroidxReader {
        fun read(path: Path, b: ContourBuilder) {
            val it = androidx.graphics.path.PathIterator(path, androidx.graphics.path.PathIterator.ConicEvaluation.AsQuadratics, CONIC_TOLERANCE)
            val pts = FloatArray(8)
            while (it.hasNext()) {
                when (it.next(pts, 0)) {
                    androidx.graphics.path.PathSegment.Type.Move -> b.move(pts[0], pts[1])
                    androidx.graphics.path.PathSegment.Type.Line -> b.line(pts[2], pts[3])
                    androidx.graphics.path.PathSegment.Type.Quadratic -> b.quad(pts[2], pts[3], pts[4], pts[5])
                    androidx.graphics.path.PathSegment.Type.Conic -> b.quad(pts[2], pts[3], pts[4], pts[5])
                    androidx.graphics.path.PathSegment.Type.Cubic -> b.cubic(pts[2], pts[3], pts[4], pts[5], pts[6], pts[7])
                    androidx.graphics.path.PathSegment.Type.Close -> b.close()
                    androidx.graphics.path.PathSegment.Type.Done -> return
                }
            }
        }
    }
}
