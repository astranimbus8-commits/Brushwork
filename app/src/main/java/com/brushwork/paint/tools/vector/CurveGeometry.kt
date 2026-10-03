package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.VAnchor
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
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

    // ------------------------------------------------------------------ per-point thickness (v1.5, §4.5)

    /**
     * Thickness factor at fraction [t] (of the ARC LENGTH, 0..1) of segment [seg] (from anchor
     * [seg] to the next one, the first again after the last of a closed path): the two anchors'
     * [CurveAnchor.width]s blended with smoothstep, so the thickness eases in and out of every
     * anchor. 1 for no anchors.
     */
    fun widthAt(anchors: List<CurveAnchor>, seg: Int, t: Float): Float {
        val n = anchors.size
        if (n == 0) return 1f
        val i = seg.coerceIn(0, n - 1)
        val a = CurveWidths.factor(anchors[i].width)
        val b = CurveWidths.factor(anchors[(i + 1) % n].width)
        if (a == b) return a
        return a + (b - a) * smoothstep(t)
    }

    /** 3t² − 2t³ of [t] clamped to 0..1. */
    fun smoothstep(t: Float): Float {
        val x = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** True when every anchor is at 100 % thickness (the path is drawn exactly as before v1.5). */
    fun isUniformWidth(anchors: List<CurveAnchor>): Boolean = anchors.all { it.width == 1f }

    // ------------------------------------------------------------------ handle scaling (v1.6, §3.3)

    /** Smallest and largest handle scale factor of one change. */
    const val MIN_HANDLE_SCALE = 0.01f
    const val MAX_HANDLE_SCALE = 100f

    /** [k] as a usable handle scale factor: clamped to [MIN_HANDLE_SCALE]..[MAX_HANDLE_SCALE], non-finite = 1. */
    fun clampHandleScale(k: Float): Float = if (k.isFinite()) k.coerceIn(MIN_HANDLE_SCALE, MAX_HANDLE_SCALE) else 1f

    /**
     * [anchors] with the handles of the anchors at [indices] scaled by [k] (see
     * [clampHandleScale]) along their own directions. Each anchor's handles as drawn
     * ([handles]: automatic Catmull-Rom tangents and chord handles included) are first made
     * explicit, so at k = 1 the geometry is unchanged; then [HandleSide.BOTH] scales both by the
     * same factor (a smooth anchor stays smooth and collinear, a sharp one keeps its angle) and
     * [HandleSide.IN] / [HandleSide.OUT] only one side (the length ratio changes, never a
     * direction). Other anchors are returned as they are (their automatic tangents depend on
     * positions only, which don't change). Pure, O(points).
     */
    fun scaledHandles(
        anchors: List<CurveAnchor>,
        indices: IntArray,
        k: Float,
        side: HandleSide,
        closed: Boolean,
        tension: Float,
    ): List<CurveAnchor> {
        if (anchors.isEmpty() || indices.isEmpty()) return anchors
        val f = clampHandleScale(k)
        val out = anchors.toMutableList()
        for (i in indices) {
            if (i !in anchors.indices) continue
            val (hIn, hOut) = handles(anchors, i, closed, tension)
            val newIn = if (side != HandleSide.OUT) hIn * f else hIn
            val newOut = if (side != HandleSide.IN) hOut * f else hOut
            if (!newIn.x.isFinite() || !newIn.y.isFinite() || !newOut.x.isFinite() || !newOut.y.isFinite()) continue
            out[i] = anchors[i].copy(handleIn = newIn, handleOut = newOut)
        }
        return out
    }
}

/** Which handles of an anchor a handle scale changes (v1.6, §3.3): the chips read as the Shape tool's ("In and out" / "In" / "Out"). */
enum class HandleSide(val label: String) { BOTH("In and out"), IN("In"), OUT("Out") }

/**
 * A flattened line with its full width at every point (document px): [n] points in [xs] / [ys],
 * widths in [ws]. What [VariableWidthOutline.build] outlines.
 */
class WidthLine(val xs: FloatArray, val ys: FloatArray, val ws: FloatArray, val n: Int)

/**
 * Per-point thickness along curves and polylines (v1.5 §4.5): the thickness factors of the
 * anchors ([CurveAnchor.width], 0..3) blend with smoothstep along the arc length between
 * neighbouring anchors ([CurveGeometry.widthAt]). A plain line becomes a [WidthLine] that
 * [VariableWidthOutline] outlines; a brush gets the factors as pressure ([atSamples] feeds a
 * [WidthProfile]). Pure Kotlin and thread-safe (vector layer renderers call it off the main
 * thread). With every factor at 1 nothing changes (the callers keep their v1.4 drawing).
 */
object CurveWidths {
    /** Largest thickness factor (300 %). */
    const val MAX_FACTOR = 3f

    /** Flattening tolerance of [line] (document px). */
    const val LINE_TOLERANCE = 0.25f

    /** Largest distance between two points of a [line] whose width changes (document px). */
    const val LINE_STEP = 2f

    /** Most points one flattened piece of a [line] is cut into. */
    private const val MAX_PIECES = 1024

    /** [w] as a factor: non-finite → 1, negative → 0 (no upper clamp: data from other sources is drawn as stored). */
    fun factor(w: Float): Float = if (w.isFinite()) w.coerceAtLeast(0f) else 1f

    /** [w] clamped to the slider's range 0..[MAX_FACTOR] (non-finite → 1). */
    fun clamp(w: Float): Float = if (w.isFinite()) w.coerceIn(0f, MAX_FACTOR) else 1f

    /** Largest factor of [anchors] (1 for none). */
    fun maxFactor(anchors: List<CurveAnchor>): Float {
        if (anchors.isEmpty()) return 1f
        var m = 0f
        for (a in anchors) m = max(m, factor(a.width))
        return m
    }

    /**
     * The path through [anchors] flattened ([tolerance] px) with the full line width ([width] ×
     * the anchor factors, smoothstep-blended along the arc length of each segment) at every
     * point. Pieces between flattened points are cut to [LINE_STEP] px where the width changes,
     * so the blend is followed on straight segments too. Null for no anchors. Closed paths
     * (more than two anchors) end on their first point again.
     */
    fun line(anchors: List<CurveAnchor>, closed: Boolean, tension: Float, polyline: Boolean, width: Float, tolerance: Float = LINE_TOLERANCE): WidthLine? {
        val n = anchors.size
        if (n == 0) return null
        fun w(i: Int) = factor(anchors[i % n].width) * width
        if (n == 1) return WidthLine(floatArrayOf(anchors[0].x), floatArrayOf(anchors[0].y), floatArrayOf(w(0)), 1)
        val isClosed = closed && n > 2
        val segs = CurveGeometry.segmentCount(n, isClosed)
        var xs = FloatArray(64); var ys = FloatArray(64); var ws = FloatArray(64)
        var count = 0
        fun add(x: Float, y: Float, wv: Float) {
            if (count == xs.size) { xs = xs.copyOf(count * 2); ys = ys.copyOf(count * 2); ws = ws.copyOf(count * 2) }
            xs[count] = x; ys[count] = y; ws[count] = wv; count++
        }
        add(anchors[0].x, anchors[0].y, w(0))
        val pts = ArrayList<Vec2>()
        for (seg in 0 until segs) {
            val (p0, c1, c2, p1) = CurveGeometry.segment(anchors, seg, isClosed, tension, polyline)
            pts.clear()
            pts += p0
            VectorPath.flattenCubic(p0, c1, c2, p1, tolerance, pts)
            var total = 0f
            for (i in 1 until pts.size) total += pts[i - 1].distanceTo(pts[i])
            var acc = 0f
            val wa = w(seg)
            val wb = w(seg + 1)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val len = a.distanceTo(b)
                // A changing width needs points along straight pieces too (they flatten to their
                // ends), or the outline would blend linearly instead of with smoothstep.
                val pieces = if (wa == wb) 1 else ceil(len / LINE_STEP).toInt().coerceIn(1, MAX_PIECES)
                for (k in 1..pieces) {
                    val f = k.toFloat() / pieces
                    val t = if (total > 0f) ((acc + len * f) / total).coerceIn(0f, 1f) else 1f
                    val e = t * t * (3f - 2f * t)
                    val q = if (k == pieces) b else a.lerp(b, f)
                    add(q.x, q.y, wa + (wb - wa) * e)
                }
                acc += len
            }
        }
        return WidthLine(xs, ys, ws, count)
    }

    /**
     * The thickness factor at each of the first [count] samples of the brush input of the path
     * through [anchors] (`brushStrokeInput(CurveGeometry.toPath(anchors, ...))`: samples
     * [spacing] px apart along the path flattened at [tolerance], the last one at its end), for a
     * [WidthProfile]. Each sample's factor is [CurveGeometry.widthAt] at its exact arc-length
     * position within its segment.
     */
    fun atSamples(
        anchors: List<CurveAnchor>,
        closed: Boolean,
        tension: Float,
        polyline: Boolean,
        count: Int,
        spacing: Float = BRUSH_SAMPLE_SPACING,
        tolerance: Float = BRUSH_SAMPLE_TOLERANCE,
    ): FloatArray {
        val out = FloatArray(max(0, count)) { 1f }
        val n = anchors.size
        if (count <= 0 || n < 2) return out
        val isClosed = closed && n > 2
        val segs = CurveGeometry.segmentCount(n, isClosed)
        // Arc length at each anchor along the flattened path (as the brush samples walk it).
        val at = FloatArray(segs + 1)
        val pts = ArrayList<Vec2>()
        for (seg in 0 until segs) {
            val (p0, c1, c2, p1) = CurveGeometry.segment(anchors, seg, isClosed, tension, polyline)
            val straight = polyline || (Geometry.distanceToSegment(c1, p0, p1) < 1e-3f && Geometry.distanceToSegment(c2, p0, p1) < 1e-3f)
            var len = 0f
            if (straight) {
                len = hypot(p1.x - p0.x, p1.y - p0.y)
            } else {
                pts.clear()
                pts += p0
                VectorPath.flattenCubic(p0, c1, c2, p1, tolerance, pts)
                for (i in 1 until pts.size) len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            }
            at[seg + 1] = at[seg] + len
        }
        val total = at[segs]
        val step = spacing.coerceAtLeast(1e-3f)
        var seg = 0
        for (i in 0 until count) {
            val s = if (i == count - 1) total else min(i * step, total)
            while (seg < segs - 1 && s > at[seg + 1]) seg++
            val segLen = at[seg + 1] - at[seg]
            val t = if (segLen > 0f) ((s - at[seg]) / segLen).coerceIn(0f, 1f) else 1f
            out[i] = CurveGeometry.widthAt(anchors, seg, t)
        }
        return out
    }
}

/*
 * Conversions between the Curve tool's anchors and vector path anchors (v1.5 §4.9): handle
 * offsets are kept on sharp anchors too (broken tangents, V14), so an imported SVG cubic reopens
 * and commits back unchanged.
 */

/** This anchor as a vector path anchor. */
fun CurveAnchor.toVAnchor(): VAnchor = VAnchor(
    x, y, sharp,
    inX = handleIn?.x, inY = handleIn?.y,
    outX = handleOut?.x, outY = handleOut?.y,
    width = width,
)

/** This vector path anchor as a Curve tool anchor (a handle needs both coordinates; width clamped to 0..3). */
fun VAnchor.toCurveAnchor(): CurveAnchor = CurveAnchor(
    x, y, sharp,
    handleIn = if (inX != null && inY != null) Vec2(inX, inY) else null,
    handleOut = if (outX != null && outY != null) Vec2(outX, outY) else null,
    width = CurveWidths.clamp(width),
)
