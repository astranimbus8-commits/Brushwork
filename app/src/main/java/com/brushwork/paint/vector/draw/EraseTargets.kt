package com.brushwork.paint.vector.draw

import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CurveAnchor
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VSubpath
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How the partial and "to intersection" erasers can cut an object. */
internal enum class CutKind {
    /** A freehand stroke: its input points are cut. */
    STROKE,
    /** A path of one open sub-path with an outline and no fill: its curve is split. */
    OPEN_PATH,
    /** Closed or filled paths, paths of several sub-paths, shapes and single-point strokes: erased whole. */
    WHOLE,
}

/**
 * The geometry the vector eraser and the bucket test an object against (document px; pure
 * Kotlin): its centerlines, the area its fill covers and how far its paint reaches from the
 * centerlines. Built once per object (objects are immutable) and cached by identity.
 */
internal class EraseTarget private constructor(
    val obj: VObject,
    val cut: CutKind,
    /** Centerlines: a stroke's input points, a path's flattened sub-paths, a shape's outline (and arrowheads). */
    val lines: List<FlatLine>,
    /** Areas inside the object (filled paths and shapes; all implicitly closed), or none. */
    val fills: List<FlatLine>,
    val evenOdd: Boolean,
    /** How far paint reaches from the centerlines (touch tests; a stroke's scatter included). */
    val reach: Float,
    /** Half the painted width of a cut line (a cut end keeps paint this far from its centerline end). */
    val cutReach: Float,
    /**
     * A stroke or a line of one open sub-path (also a dot, which has nothing to cut and goes
     * whole): what the partial and "to intersection" erasers work on. Closed or filled objects,
     * shapes and paths of several sub-paths are not lines.
     */
    val isLine: Boolean,
    /** [CutKind.OPEN_PATH]: the cubic (8 values) of every segment of the sub-path, and their anchors. */
    val cubics: List<FloatArray>? = null,
    val anchors: List<VAnchor>? = null,
    /** [CutKind.OPEN_PATH]: index in lines[0] of each segment's first point, and its uniform t steps. */
    val segStart: IntArray? = null,
    val segSteps: IntArray? = null,
) {
    val left: Float
    val top: Float
    val right: Float
    val bottom: Float

    init {
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (line in lines) {
            if (line.isEmpty) continue
            l = min(l, line.left); t = min(t, line.top); r = max(r, line.right); b = max(b, line.bottom)
        }
        for (line in fills) {
            if (line.isEmpty) continue
            l = min(l, line.left); t = min(t, line.top); r = max(r, line.right); b = max(b, line.bottom)
        }
        left = l - reach; top = t - reach; right = r + reach; bottom = b + reach
    }

    val isEmpty: Boolean get() = left > right

    fun intersects(l: Float, t: Float, r: Float, b: Float): Boolean = !isEmpty && left <= r && right >= l && top <= b && bottom >= t

    /** True when the eraser capsule C->D of radius [r] touches what this object paints. */
    fun touchedBy(cx: Float, cy: Float, dx: Float, dy: Float, r: Float): Boolean {
        if (!intersects(min(cx, dx) - r, min(cy, dy) - r, max(cx, dx) + r, max(cy, dy) + r)) return false
        val rho = r + reach
        for (line in lines) if (EraseMath.touches(line, cx, cy, dx, dy, rho)) return true
        if (fills.isNotEmpty() && (EraseMath.inside(fills, cx, cy, evenOdd) || EraseMath.inside(fills, dx, dy, evenOdd))) return true
        return false
    }

    companion object {
        /** Flattening tolerance of the centerlines (document px). */
        const val FLATTEN = 0.25f

        fun of(o: VObject): EraseTarget = when (o) {
            is VStroke -> ofStroke(o)
            is VPath -> ofPath(o)
            is VShape -> ofShape(o)
        }

        /** A stroke's visible radius: its largest dab, with the scatter (as VectorOps' hit tests). */
        fun strokeRadius(s: VStroke): Float {
            val k = if (s.sizeScale.isFinite() && s.sizeScale > 0f) s.sizeScale else 1f
            val d = max(1f, s.preset.size * k)
            return d / 2f + max(0f, s.preset.scatter) * d
        }

        /** A line shorter than this (document px) is a dot: nothing to cut, it goes whole. */
        const val MIN_LINE = 0.5f

        private fun ofStroke(s: VStroke): EraseTarget {
            val p = s.points
            val line = FlatLine(p.x, p.y, p.size)
            val k = if (s.sizeScale.isFinite() && s.sizeScale > 0f) s.sizeScale else 1f
            val half = max(0.5f, s.preset.size * k / 2f)
            // A tap (its down and up points at the same place) is a dot.
            val cut = if (p.size >= 2 && EraseMath.lengthBetween(line, 0f, line.uMax) >= MIN_LINE) CutKind.STROKE else CutKind.WHOLE
            return EraseTarget(s, cut, listOf(line), emptyList(), false, strokeRadius(s), half, isLine = true)
        }

        /** The Curve tool's anchors of a sub-path (handles kept when both coordinates are set). */
        fun curveAnchors(s: VSubpath): List<CurveAnchor> = s.anchors.map { a ->
            CurveAnchor(
                a.x, a.y, a.sharp,
                handleIn = if (a.inX != null && a.inY != null) Vec2(a.inX, a.inY) else null,
                handleOut = if (a.outX != null && a.outY != null) Vec2(a.outX, a.outY) else null,
                width = a.width,
            )
        }

        /** Largest anchor thickness factor (1 without anchors). */
        fun maxWidth(p: VPath): Float {
            var m = 0f
            var any = false
            for (s in p.subpaths) for (a in s.anchors) {
                any = true
                if (a.width.isFinite() && a.width > m) m = a.width
            }
            return if (any) m else 1f
        }

        /** Half the painted width of [p]'s outline (0 without one). */
        fun halfWidth(p: VPath): Float {
            val st = p.stroke ?: return 0f
            val w = when (st.kind) {
                VStrokeKind.PLAIN -> st.width
                VStrokeKind.BRUSH -> st.brush?.size ?: st.width
            }
            return if (w.isFinite()) max(0f, w) * maxWidth(p) / 2f else 0f
        }

        fun subpathGeometry(p: VPath, s: VSubpath): VectorPath =
            if (s.anchors.isEmpty()) VectorPath.EMPTY else CurveGeometry.toPath(curveAnchors(s), s.closed, p.tension, p.polyline)

        private fun ofPath(p: VPath): EraseTarget {
            val half = halfWidth(p)
            val reach = if (p.fill != null) max(half, 1f) else max(half, 0.5f)
            val single = p.subpaths.singleOrNull()
            if (single != null && !single.closed && p.fill == null && p.stroke != null && single.anchors.size >= 2) {
                return ofOpenPath(p, single, half, reach)
            }
            val lines = ArrayList<FlatLine>()
            val fills = ArrayList<FlatLine>()
            for (s in p.subpaths) {
                for (poly in subpathGeometry(p, s).flatten(FLATTEN)) {
                    val xs = FloatArray(poly.points.size) { poly.points[it].x }
                    val ys = FloatArray(poly.points.size) { poly.points[it].y }
                    lines += FlatLine(xs, ys, xs.size, poly.closed)
                    if (p.fill != null) fills += FlatLine(xs, ys, xs.size, true)
                }
            }
            return EraseTarget(p, CutKind.WHOLE, lines, fills, p.fillRule == VFillRule.EVENODD, reach, half, isLine = false)
        }

        private fun ofOpenPath(p: VPath, s: VSubpath, half: Float, reach: Float): EraseTarget {
            val anchors = curveAnchors(s)
            val segs = CurveGeometry.segmentCount(anchors.size, false)
            val cubics = ArrayList<FloatArray>(segs)
            val starts = IntArray(segs)
            val steps = IntArray(segs)
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            xs += anchors[0].x; ys += anchors[0].y
            val tmp = FloatArray(2)
            for (j in 0 until segs) {
                val (p0, c1, c2, p1) = CurveGeometry.segment(anchors, j, false, p.tension, p.polyline)
                val c = floatArrayOf(p0.x, p0.y, c1.x, c1.y, c2.x, c2.y, p1.x, p1.y)
                cubics += c
                val n = if (p.polyline) 1 else EraseMath.cubicSteps(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], FLATTEN)
                starts[j] = xs.size - 1
                steps[j] = n
                for (k in 1..n) {
                    if (k == n) { xs += c[6]; ys += c[7] } else {
                        EraseMath.cubicPoint(c, k.toFloat() / n, tmp)
                        xs += tmp[0]; ys += tmp[1]
                    }
                }
            }
            val line = FlatLine(xs.toFloatArray(), ys.toFloatArray())
            // Every anchor at one place: a dot, erased whole.
            if (EraseMath.lengthBetween(line, 0f, line.uMax) < MIN_LINE) {
                return EraseTarget(p, CutKind.WHOLE, listOf(line), emptyList(), false, reach, max(half, 0.5f), isLine = true)
            }
            return EraseTarget(p, CutKind.OPEN_PATH, listOf(line), emptyList(), false, reach, max(half, 0.5f), true, cubics, s.anchors, starts, steps)
        }

        private fun ofShape(v: VShape): EraseTarget {
            val o = v.shape
            val lines = ArrayList<FlatLine>()
            val fills = ArrayList<FlatLine>()
            fun add(path: VectorPath, fill: Boolean) {
                for (poly in path.flatten(FLATTEN)) {
                    val xs = FloatArray(poly.points.size) { poly.points[it].x }
                    val ys = FloatArray(poly.points.size) { poly.points[it].y }
                    lines += FlatLine(xs, ys, xs.size, poly.closed)
                    if (fill) fills += FlatLine(xs, ys, xs.size, true)
                }
            }
            if (o.type == ShapeType.ARROW) {
                val g = ShapeOutlines.arrow(o)
                add(if (o.paintsWithBrush) ShapeOutlines.brushOutline(o) else g.stroke, false)
                add(g.fill, true)
            } else {
                add(ShapeOutlines.outline(o), !o.type.isLineLike && o.style.fill)
            }
            return EraseTarget(v, CutKind.WHOLE, lines, fills, false, max(ShapeOutlines.reach(o), 1f), ShapeOutlines.reach(o), isLine = false)
        }
    }
}

/** Builds what is left of an object after parts of it are erased. Pure Kotlin. */
internal object ErasePieces {
    /** Removed parts shorter than this (document px of centerline) are ignored (a graze, not a cut). */
    const val MIN_CUT = 0.05f

    /** Pieces shorter than this (document px) are dropped (a cut that leaves a speck). */
    const val MIN_PIECE = 0.5f

    /**
     * The kept parts of [target]'s centerline once [removed] (parameters of lines[0]) is erased,
     * as parameter ranges; empty when nothing is left, null when nothing really is removed.
     */
    fun kept(target: EraseTarget, removed: Intervals): List<FloatArray>? {
        val line = target.lines[0]
        val real = Intervals()
        for (k in 0 until removed.size) {
            val a = removed.start(k); val b = removed.end(k)
            if (EraseMath.lengthBetween(line, a, b) >= MIN_CUT) real.add(a, b)
        }
        if (real.isEmpty) return null
        return real.complement(0f, line.uMax).filter { EraseMath.lengthBetween(line, it[0], it[1]) >= MIN_PIECE }
    }

    /**
     * What is left of the cut object of [target] once [removed] is erased: 0..n pieces; null
     * when nothing is removed (the object stays as it is).
     */
    fun pieces(target: EraseTarget, removed: Intervals): List<VObject>? {
        val kept = kept(target, removed) ?: return null
        return when (target.cut) {
            CutKind.STROKE -> kept.map { strokePiece(target.obj as VStroke, target.lines[0], it[0], it[1]) }
            CutKind.OPEN_PATH -> kept.mapNotNull { pathPiece(target, it[0], it[1]) }
            CutKind.WHOLE -> emptyList()
        }
    }

    /**
     * The part [ua]..[ub] of stroke [s] (parameters of its input points [line]): the original
     * points in between, with interpolated end points where it was cut. A cut end loses its
     * finger taper; an original end keeps it.
     */
    fun strokePiece(s: VStroke, line: FlatLine, ua: Float, ub: Float): VStroke {
        val p = s.points
        val n = p.size
        val last = (n - 1).toFloat()
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ps = ArrayList<Float>()
        fun addAt(u: Float) {
            val i = min(floor(u).toInt(), n - 2).coerceAtLeast(0)
            val t = (u - i).coerceIn(0f, 1f)
            when {
                t <= 0f -> { xs += p.x[i]; ys += p.y[i]; ps += p.p[i] }
                t >= 1f -> { xs += p.x[i + 1]; ys += p.y[i + 1]; ps += p.p[i + 1] }
                else -> {
                    xs += EraseMath.lerp(p.x[i], p.x[i + 1], t)
                    ys += EraseMath.lerp(p.y[i], p.y[i + 1], t)
                    ps += pressure(p.p[i], p.p[i + 1], t)
                }
            }
        }
        addAt(ua)
        var i = floor(ua).toInt() + 1
        while (i < ub && i < n) {
            if (i > ua) { xs += p.x[i]; ys += p.y[i]; ps += p.p[i] }
            i++
        }
        addAt(ub)
        return s.copy(
            points = PackedPoints(xs.toFloatArray(), ys.toFloatArray(), ps.toFloatArray()),
            taperIn = s.taperIn && ua <= 0f,
            taperOut = s.taperOut && ub >= last,
        )
    }

    /** Raw pressure between two points (NaN, a finger without pressure, takes the other). */
    private fun pressure(a: Float, b: Float, t: Float): Float = when {
        a.isNaN() -> b
        b.isNaN() -> a
        else -> EraseMath.lerp(a, b, t)
    }

    /** Segment index and its parameter at flattened parameter [u] of an open path target. */
    private fun segmentAt(target: EraseTarget, u: Float): Pair<Int, Float> {
        val starts = target.segStart!!
        val steps = target.segSteps!!
        var j = 0
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= u) { j = mid; lo = mid + 1 } else hi = mid - 1
        }
        val t = ((u - starts[j]) / steps[j]).coerceIn(0f, 1f)
        return j to t
    }

    /** Thickness factor at parameter [t] of segment [j] (smoothstep between its anchors, §4.5). */
    private fun widthAt(anchors: List<VAnchor>, j: Int, t: Float): Float {
        fun w(a: VAnchor) = if (a.width.isFinite()) a.width.coerceAtLeast(0f) else 1f
        val wa = w(anchors[j]); val wb = w(anchors[j + 1])
        return wa + (wb - wa) * EraseMath.smoothstep(t)
    }

    /**
     * The part [ua]..[ub] (flattened parameters) of the open path of [target] as a path of its
     * own: the curve is split exactly (de Casteljau) at both ends; every anchor of a curve piece
     * is a sharp anchor with explicit handles, so the piece keeps exactly the shape it had (the
     * automatic tangents of the old ends would otherwise bend it). Null for an empty piece.
     */
    fun pathPiece(target: EraseTarget, ua: Float, ub: Float): VPath? {
        val p = target.obj as VPath
        val cubics = target.cubics!!
        val anchors = target.anchors!!
        var (j0, t0) = segmentAt(target, ua)
        var (j1, t1) = segmentAt(target, ub)
        val eps = 1e-4f
        if (t1 <= eps && j1 > j0) { j1 -= 1; t1 = 1f }
        if (t0 >= 1f - eps && j0 < j1) { j0 += 1; t0 = 0f }
        if (j0 == j1 && t1 - t0 <= eps) return null
        val out = ArrayList<VAnchor>()
        if (p.polyline) {
            val a0 = cubics[j0]
            out += VAnchor(EraseMath.lerp(a0[0], a0[6], t0), EraseMath.lerp(a0[1], a0[7], t0), anchors[j0].sharp, width = widthAt(anchors, j0, t0))
            for (k in j0 + 1..j1) out += anchors[k].copy(inX = null, inY = null, outX = null, outY = null)
            val a1 = cubics[j1]
            out += VAnchor(EraseMath.lerp(a1[0], a1[6], t1), EraseMath.lerp(a1[1], a1[7], t1), anchors[j1 + 1].sharp, width = widthAt(anchors, j1, t1))
            return VPath(p.id, p.opacity, listOf(VSubpath(dedupe(out), false)), p.tension, true, p.fillRule, null, p.stroke)
        }
        // The cubics of the piece, in order.
        val parts = ArrayList<FloatArray>()
        for (j in j0..j1) {
            val a = if (j == j0) t0 else 0f
            val b = if (j == j1) t1 else 1f
            parts += if (a <= 0f && b >= 1f) cubics[j] else EraseMath.subCubic(cubics[j], a, b)
        }
        for (k in parts.indices) {
            val c = parts[k]
            val prev = if (k > 0) parts[k - 1] else null
            val width = if (k == 0) widthAt(anchors, j0, t0) else anchors[j0 + k].width
            out += VAnchor(
                c[0], c[1], sharp = true,
                inX = prev?.let { it[4] - c[0] }, inY = prev?.let { it[5] - c[1] },
                outX = c[2] - c[0], outY = c[3] - c[1],
                width = width,
            )
        }
        val lastC = parts.last()
        out += VAnchor(lastC[6], lastC[7], sharp = true, inX = lastC[4] - lastC[6], inY = lastC[5] - lastC[7], width = widthAt(anchors, j1, t1))
        return VPath(p.id, p.opacity, listOf(VSubpath(out, false)), 0f, false, p.fillRule, null, p.stroke)
    }

    /** Drops consecutive duplicate positions of a polyline piece (a cut exactly at an anchor). */
    private fun dedupe(a: List<VAnchor>): List<VAnchor> {
        val out = ArrayList<VAnchor>(a.size)
        for (x in a) {
            val l = out.lastOrNull()
            if (l != null && abs(l.x - x.x) < 1e-4f && abs(l.y - x.y) < 1e-4f) continue
            out += x
        }
        return if (out.size >= 2) out else a
    }

    /** The geometry of a path piece (for tests: compare with the original's part). */
    fun geometryOf(p: VPath): VectorPath {
        val ops = ArrayList<PathOp>()
        for (s in p.subpaths) ops += EraseTarget.subpathGeometry(p, s).ops
        return VectorPath(ops)
    }
}
