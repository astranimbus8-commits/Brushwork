package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.tan

/*
 * Shapes with their own points ("Points" in the shape tool): a regular shape converted to a list
 * of points that can be moved, inserted, deleted and made smooth. Pure Kotlin, unit-tested on the
 * JVM.
 *
 * Points are stored BOX-LOCAL and NORMALIZED ([ShapePoint]): x in box widths, y in box heights,
 * (0, 0) = the box center before rotation. Moving, resizing, rotating or pinching the shape's box
 * therefore transforms the custom outline exactly like a regular one. An axis of zero size (a
 * straight line converted to points) stores 0 on that axis. After a point edit the box is fitted
 * to the outline again ([ShapePoints.fit]), keeping its rotation.
 *
 * Segments: from each point to the next (and back to the first for closed shapes) a cubic Bezier
 * whose control points are the points' handles. A sharp point has no handles (straight edges)
 * unless it was given explicit ones (e.g. when a point is inserted into a curved segment next to
 * it); a smooth point without explicit handles gets an automatic tangent (Catmull-Rom, like the
 * curve tool), with explicit handles it keeps them (collinear when dragged).
 */

/** A handle offset of a [ShapePoint] (normalized like the point). */
@Serializable
data class ShapeHandle(val x: Float, val y: Float)

/** One point of a custom shape, box-local and normalized (see the file comment). */
@Serializable
data class ShapePoint(
    val x: Float,
    val y: Float,
    /** Smooth point (curve through it) or sharp corner. */
    val smooth: Boolean = false,
    /** Explicit handles (null = automatic for smooth points, none for sharp ones). */
    val handleIn: ShapeHandle? = null,
    val handleOut: ShapeHandle? = null,
) {
    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite() &&
            (handleIn == null || (handleIn.x.isFinite() && handleIn.y.isFinite())) &&
            (handleOut == null || (handleOut.x.isFinite() && handleOut.y.isFinite()))
}

/**
 * A point of a custom outline in pixels (document or box-local); handles are offsets from [pos]
 * (null: automatic for smooth points, none for sharp ones).
 */
data class ShapeAnchor(
    val pos: Vec2,
    val smooth: Boolean = false,
    val handleIn: Vec2? = null,
    val handleOut: Vec2? = null,
) {
    fun moved(p: Vec2) = copy(pos = p)
    val hasExplicitHandles: Boolean get() = handleIn != null || handleOut != null
}

/** Where a point lies on a custom outline: segment [segment] at parameter [t]. */
data class ShapeHit(val segment: Int, val t: Float, val point: Vec2, val distance: Float)

/**
 * Which tangent handles of a point the Handles group scales (v1.6 §3.3, Shape Points): both, or
 * only the one coming in / going out (its length changes, never its direction).
 */
enum class ShapeHandleSide { BOTH, IN, OUT }

object ShapePoints {
    /** Fewest points of a closed / open custom shape. */
    const val MIN_CLOSED = 3
    const val MIN_OPEN = 2

    /** Sizes below this (px) count as zero (a straight line has no height). */
    private const val SIZE_EPS = 1e-3f

    fun minPoints(closed: Boolean): Int = if (closed) MIN_CLOSED else MIN_OPEN

    // ------------------------------------------------------------------ conversion

    /** Normalized point -> box-local pixels of a [w] x [h] box. */
    fun toLocal(p: ShapePoint, w: Float, h: Float): ShapeAnchor = ShapeAnchor(
        Vec2(p.x * w, p.y * h),
        p.smooth,
        p.handleIn?.let { Vec2(it.x * w, it.y * h) },
        p.handleOut?.let { Vec2(it.x * w, it.y * h) },
    )

    fun localAnchors(points: List<ShapePoint>, w: Float, h: Float): List<ShapeAnchor> = points.map { toLocal(it, w, h) }

    /** The points of a shape placed in [box], in document pixels. */
    fun docAnchors(box: ShapeBox, points: List<ShapePoint>): List<ShapeAnchor> {
        val rad = box.rotationDeg * Geometry.DEG
        return points.map { p ->
            val l = toLocal(p, box.w, box.h)
            ShapeAnchor(box.toDoc(l.pos), l.smooth, l.handleIn?.rotated(rad), l.handleOut?.rotated(rad))
        }
    }

    /** Document anchors -> points normalized to [box] (an axis of zero size stores 0). */
    fun normalize(box: ShapeBox, anchors: List<ShapeAnchor>): List<ShapePoint> {
        val rad = -box.rotationDeg * Geometry.DEG
        fun nx(v: Float) = if (box.w > SIZE_EPS) v / box.w else 0f
        fun ny(v: Float) = if (box.h > SIZE_EPS) v / box.h else 0f
        fun handle(v: Vec2?) = v?.rotated(rad)?.let { ShapeHandle(nx(it.x), ny(it.y)) }
        return anchors.map { a ->
            val l = box.toLocal(a.pos)
            ShapePoint(nx(l.x), ny(l.y), a.smooth, handle(a.handleIn), handle(a.handleOut))
        }
    }

    /**
     * The box turned by [rotationDeg] that tightly fits the outline through the document
     * [anchors], and the anchors normalized to it. Sizes below a thousandth of a pixel are 0.
     */
    fun fit(rotationDeg: Float, anchors: List<ShapeAnchor>, closed: Boolean): Pair<ShapeBox, List<ShapePoint>> {
        val rad = rotationDeg * Geometry.DEG
        val turned = anchors.map { a -> ShapeAnchor(a.pos.rotated(-rad), a.smooth, a.handleIn?.rotated(-rad), a.handleOut?.rotated(-rad)) }
        val b = path(turned, closed).bounds(0.05f) ?: Bounds.of(turned.map { it.pos }) ?: Bounds(0f, 0f, 0f, 0f)
        val w = b.width.takeIf { it >= SIZE_EPS } ?: 0f
        val h = b.height.takeIf { it >= SIZE_EPS } ?: 0f
        val c = Vec2((b.left + b.right) / 2f, (b.top + b.bottom) / 2f).rotated(rad)
        val box = ShapeBox(c.x, c.y, w, h, rotationDeg)
        return box to normalize(box, anchors)
    }

    /**
     * The points of a regular shape of [type] (normalized), so that the outline does not change:
     * rectangles, polygons and stars get sharp points at their vertices, ellipses four smooth
     * points with the exact handles of their cubic arcs, lines and arrows their two ends.
     */
    fun fromRegular(type: ShapeType, params: OutlineParams): List<ShapePoint> = when (type) {
        ShapeType.LINE, ShapeType.ARROW -> listOf(ShapePoint(-0.5f, 0f), ShapePoint(0.5f, 0f))
        ShapeType.ELLIPSE -> {
            // The same handle length as ShapeGeometry.ellipsePath (four quarter arcs).
            val s = (2 * PI).toFloat() / 4
            val k = 4f / 3f * tan(s / 4f) / 2f
            listOf(
                ShapePoint(0f, -0.5f, true, ShapeHandle(-k, 0f), ShapeHandle(k, 0f)),
                ShapePoint(0.5f, 0f, true, ShapeHandle(0f, -k), ShapeHandle(0f, k)),
                ShapePoint(0f, 0.5f, true, ShapeHandle(k, 0f), ShapeHandle(-k, 0f)),
                ShapePoint(-0.5f, 0f, true, ShapeHandle(0f, k), ShapeHandle(0f, -k)),
            )
        }
        else -> ShapeGeometry.vertices(type, 1f, 1f, params).map { ShapePoint(it.x, it.y) }
    }

    // ------------------------------------------------------------------ outline

    /**
     * The handles (in, out) of anchor [i] as drawn: explicit ones, the automatic tangent of a
     * smooth point (Catmull-Rom: a third of half the vector from the previous to the next
     * point, one-sided at open ends), or none for a sharp point.
     */
    fun handles(a: List<ShapeAnchor>, i: Int, closed: Boolean): Pair<Vec2, Vec2> {
        val p = a[i]
        val hi = p.handleIn; val ho = p.handleOut
        if (hi != null && ho != null) return hi to ho
        val auto = if (p.smooth) autoTangent(a, i, closed) else null
        val hIn = hi ?: auto?.let { it / -3f } ?: Vec2.ZERO
        val hOut = ho ?: auto?.let { it / 3f } ?: Vec2.ZERO
        return hIn to hOut
    }

    private fun autoTangent(a: List<ShapeAnchor>, i: Int, closed: Boolean): Vec2 {
        val n = a.size
        val prev = if (i > 0) a[i - 1].pos else if (closed && n > 2) a[n - 1].pos else null
        val next = if (i < n - 1) a[i + 1].pos else if (closed && n > 2) a[0].pos else null
        val p = a[i].pos
        return when {
            prev != null && next != null -> (next - prev) / 2f
            next != null -> next - p
            prev != null -> p - prev
            else -> Vec2.ZERO
        }
    }

    /** Number of segments. */
    fun segmentCount(n: Int, closed: Boolean): Int = when {
        n < 2 -> 0
        closed && n > 2 -> n
        else -> n - 1
    }

    /** Control points (p0, c1, c2, p1) of segment [s] (from point s to the next one). */
    fun segment(a: List<ShapeAnchor>, s: Int, closed: Boolean): Array<Vec2> {
        val n = a.size
        val e = (s + 1) % n
        val p0 = a[s].pos
        val p1 = a[e].pos
        val out = handles(a, s, closed).second
        val inn = handles(a, e, closed).first
        return arrayOf(p0, p0 + out, p1 + inn, p1)
    }

    /** True when segment [seg] has no handles (a straight edge). */
    fun isStraight(seg: Array<Vec2>): Boolean = (seg[1] - seg[0]).lengthSq < 1e-12f && (seg[2] - seg[3]).lengthSq < 1e-12f

    /** The outline through [a] (no corner treatment). */
    fun path(a: List<ShapeAnchor>, closed: Boolean): VectorPath {
        val n = a.size
        if (n == 0) return VectorPath.EMPTY
        val ops = ArrayList<PathOp>(n + 2)
        ops += PathOp.MoveTo(a[0].pos)
        for (s in 0 until segmentCount(n, closed)) {
            val seg = segment(a, s, closed)
            ops += if (isStraight(seg)) PathOp.LineTo(seg[3]) else PathOp.CubicTo(seg[1], seg[2], seg[3])
        }
        if (closed && n > 2) ops += PathOp.Close
        return VectorPath(ops)
    }

    /**
     * The outline through [a] with [corner] treatment (closed shapes only): when every edge is
     * straight it is exactly [ShapeGeometry.cornerPath]; otherwise the corners between two
     * straight edges are treated and the others stay as they are.
     *
     * A point in the middle of a straight run (e.g. just inserted on an edge: the edge goes on in
     * the same direction, or a point on top of the previous one) is not a corner: it is left
     * out, so the corners at both ends of the run are cut back exactly as without it (the radius
     * is limited by half the run, not half of its pieces) and inserting a point never changes
     * the outline.
     */
    fun outline(a: List<ShapeAnchor>, closed: Boolean, corner: CornerStyle, radius: Float): VectorPath {
        val n = a.size
        if (!closed || n < MIN_CLOSED || corner == CornerStyle.SHARP || radius <= 0f) return path(a, closed)
        val segs = List(n) { segment(a, it, true) }
        val straight = BooleanArray(n) { isStraight(segs[it]) }
        val verts = a.map { it.pos }
        val through = BooleanArray(n) { i -> straight[i] && straight[(i - 1 + n) % n] && passesThrough(verts[(i - 1 + n) % n], verts[i], verts[(i + 1) % n]) }
        // The corners, starting at the first one (point 0 unless it lies on a straight run).
        val keep = (0 until n).filter { !through[it] }
        if (keep.size < MIN_CLOSED) return path(a, closed)
        if (straight.all { it }) return ShapeGeometry.cornerPath(keep.map { verts[it] }, corner, radius)
        val m = keep.size
        val corners = arrayOfNulls<ShapeGeometry.Corner>(n)
        for (k in 0 until m) {
            val i = keep[k]
            if (!straight[i] || !straight[(i - 1 + n) % n]) continue
            val prev = verts[keep[(k - 1 + m) % m]]
            val next = verts[keep[(k + 1) % m]]
            val v = verts[i]
            val cut = minOf(radius, minOf(v.distanceTo(prev), v.distanceTo(next)) / 2f)
            corners[i] = ShapeGeometry.corner(v, prev, next, cut)
        }
        val ops = ArrayList<PathOp>(n * 4 + 2)
        val first = keep[0]
        ops += PathOp.MoveTo(corners[first]?.b ?: verts[first])
        for (k in 0 until m) {
            val i = keep[k]
            val j = keep[(k + 1) % m]
            val c = corners[j]
            // From one corner to the next: a straight run (over the points on it) or one curve.
            val seg = segs[i]
            ops += if (straight[i]) PathOp.LineTo(c?.a ?: verts[j]) else PathOp.CubicTo(seg[1], seg[2], seg[3])
            if (c != null) ShapeGeometry.appendCorner(c, corner, ops)
        }
        ops += PathOp.Close
        return VectorPath(ops)
    }

    /**
     * True when the outline goes straight on at [p] (coming from [prev], going to [next]): the
     * same direction on both sides, or [p] on top of [prev]. Same angle limit as a corner that
     * [ShapeGeometry.corner] leaves sharp.
     */
    private fun passesThrough(prev: Vec2, p: Vec2, next: Vec2): Boolean {
        val toPrev = prev - p
        val toNext = next - p
        val lp = toPrev.length
        val ln = toNext.length
        if (lp < 1e-6f) return true
        if (ln < 1e-6f) return false
        val cos = (toPrev.dot(toNext) / (lp * ln)).coerceIn(-1f, 1f)
        return kotlin.math.acos(cos) > PI.toFloat() - 1e-3f
    }

    // ------------------------------------------------------------------ editing

    /** Closest point of the outline to [p] (null without segments). */
    fun nearest(a: List<ShapeAnchor>, closed: Boolean, p: Vec2): ShapeHit? {
        var best: ShapeHit? = null
        for (s in 0 until segmentCount(a.size, closed)) {
            val seg = segment(a, s, closed)
            val (p0, c1, c2, p1) = seg
            val steps = if (isStraight(seg)) 1 else 48
            var prev = p0
            for (k in 1..steps) {
                val q = if (steps == 1) p1 else VectorPath.cubicPoint(p0, c1, c2, p1, k.toFloat() / steps)
                val proj = Geometry.projectOnSegment(p, prev, q)
                val d = proj.distanceTo(p)
                if (best == null || d < best.distance) {
                    val segLen = prev.distanceTo(q)
                    val local = if (segLen < 1e-6f) 0f else prev.distanceTo(proj) / segLen
                    best = ShapeHit(s, (k - 1 + local) / steps, proj, d)
                }
                prev = q
            }
        }
        return best
    }

    /** Point at parameter [t] of segment [s]. */
    fun pointOn(a: List<ShapeAnchor>, closed: Boolean, s: Int, t: Float): Vec2 {
        val seg = segment(a, s, closed)
        val (p0, c1, c2, p1) = seg
        return if (isStraight(seg)) p0.lerp(p1, t) else VectorPath.cubicPoint(p0, c1, c2, p1, t)
    }

    /**
     * Inserts a point into segment [s] at parameter [t] WITHOUT changing the outline: on a
     * straight edge a sharp point, on a curve a smooth point with the handles of the split curve
     * (the two neighbors keep their current handles explicitly, since their automatic tangents
     * would follow the new neighbor). Returns the anchors and the new point's index.
     */
    fun insert(a: List<ShapeAnchor>, closed: Boolean, s: Int, t: Float): Pair<List<ShapeAnchor>, Int> {
        val n = a.size
        val e = (s + 1) % n
        val tt = t.coerceIn(0.001f, 0.999f)
        val seg = segment(a, s, closed)
        val list = a.toMutableList()
        val at = s + 1
        if (isStraight(seg)) {
            list.add(at, ShapeAnchor(seg[0].lerp(seg[3], tt)))
            return list to at
        }
        val (p0, c1, c2, p1) = seg
        val p01 = p0.lerp(c1, tt); val p12 = c1.lerp(c2, tt); val p23 = c2.lerp(p1, tt)
        val p012 = p01.lerp(p12, tt); val p123 = p12.lerp(p23, tt)
        val m = p012.lerp(p123, tt)
        val (inS, _) = handles(a, s, closed)
        val (_, outE) = handles(a, e, closed)
        list[s] = a[s].copy(handleIn = inS, handleOut = p01 - p0)
        list[e] = list[e].copy(handleIn = p23 - p1, handleOut = outE)
        list.add(at, ShapeAnchor(m, smooth = true, handleIn = p012 - m, handleOut = p123 - m))
        return list to at
    }

    /** Makes point [i] smooth (automatic tangent) or a sharp corner (straight edges). */
    fun setSmooth(a: List<ShapeAnchor>, i: Int, smooth: Boolean): List<ShapeAnchor> =
        a.mapIndexed { k, p -> if (k == i) ShapeAnchor(p.pos, smooth) else p }

    /** Drops the explicit handles of point [i] (a smooth point gets its automatic tangent back). */
    fun autoTangent(a: List<ShapeAnchor>, i: Int): List<ShapeAnchor> =
        a.mapIndexed { k, p -> if (k == i) p.copy(handleIn = null, handleOut = null) else p }

    /** Smallest and largest factor [scaledHandles] applies. */
    const val MIN_HANDLE_SCALE = 0.01f
    const val MAX_HANDLE_SCALE = 100f

    /**
     * The points [indices] of [a] with their tangent handles scaled by [k] (clamped to
     * [MIN_HANDLE_SCALE]..[MAX_HANDLE_SCALE]; NaN: 1), v1.6 §3.3 for the shape tool's points. Each
     * handle keeps its own direction, so a smooth point stays smooth (its handles collinear) and a
     * sharp one keeps its angle; [side] IN or OUT changes one handle's length only. Automatic
     * (Catmull-Rom) tangents are made explicit first ([handles]: the outline is the same at 1).
     * A point without handles (a sharp corner between straight edges) is left as it is, and so are
     * indices out of range. O(points).
     */
    fun scaledHandles(a: List<ShapeAnchor>, indices: IntArray, k: Float, side: ShapeHandleSide, closed: Boolean): List<ShapeAnchor> {
        val kk = if (k.isNaN()) 1f else k.coerceIn(MIN_HANDLE_SCALE, MAX_HANDLE_SCALE)
        val out = a.toMutableList()
        for (i in indices) {
            if (i !in a.indices) continue
            val (hIn, hOut) = handles(a, i, closed)
            if (hIn.lengthSq < 1e-12f && hOut.lengthSq < 1e-12f) continue
            out[i] = a[i].copy(
                handleIn = if (side == ShapeHandleSide.OUT) hIn else hIn * kk,
                handleOut = if (side == ShapeHandleSide.IN) hOut else hOut * kk,
            )
        }
        return out
    }

    /**
     * Point [i] with one handle dragged to [v] (an offset). A smooth point keeps its other handle
     * collinear (and its length); a sharp point's handles move independently.
     */
    fun dragHandle(a: List<ShapeAnchor>, i: Int, closed: Boolean, out: Boolean, v: Vec2): List<ShapeAnchor> {
        val p = a[i]
        val (hIn, hOut) = handles(a, i, closed)
        val updated = if (out) {
            p.copy(handleOut = v, handleIn = if (p.smooth) -v.normalized() * hIn.length else hIn)
        } else {
            p.copy(handleIn = v, handleOut = if (p.smooth) -v.normalized() * hOut.length else hOut)
        }
        return a.mapIndexed { k, q -> if (k == i) updated else q }
    }
}
