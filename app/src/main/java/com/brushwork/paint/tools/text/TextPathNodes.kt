package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.spline.NurbsGeometry
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * v1.9 (R12): a text path's guide as editable nodes ([TextPathSpec.nodes], a [VSpline] like the
 * Path tool's): order 4 for TextLabels19.EDIT_AS_PATH (a B-spline; a sharp node is a corner,
 * see NurbsGeometry.pieces), order 2 for TextLabels19.EDIT_AS_POLYLINE. While a path that is not
 * straight has nodes, they ARE its guide; its flat v1.8 fields only hold the fallback v1.8 reads
 * ([TextPathSpec.withNodeFallback]).
 *
 * The guide runs through the nodes in their order. Letters read along it and their tops point to
 * the left of the direction of travel on screen, as on a line or a curve. A closed guide (cyclic,
 * at least 3 nodes) has two sides like a circle: the letters stand on it when their bodies are on
 * that left side ([TextPathSpec.side] OUTSIDE on a clockwise run, INSIDE on a counter-clockwise
 * one) and hang from it otherwise; its text position (the anchor the alignment refers to) is
 * where it comes closest to the first node. A v1.8 shape turned into nodes keeps its text where
 * it was: the nodes run in the shape's direction and the first one sits at its text position.
 */

/** Conversion of v1.8 guides to nodes, the guide of a node list, and the node bar's edits. Pure (no android imports). */
object TextPathNodes {
    /** A circle as path nodes ([asPath]): a uniform cubic B-spline over a regular octagon. */
    const val CIRCLE_PATH_NODES = 8

    /** A circle as a polyline ([asPolyline]): the vertices of the inscribed polygon. */
    const val CIRCLE_POLYLINE_VERTICES = 16

    /** A cubic curve (one span of path nodes) as a polyline ([asPolyline]): its segments. */
    const val CUBIC_POLYLINE_SEGMENTS = 8

    /** Vertices of a guide's curved spans in all (its arc-length table, design R12 c). */
    const val GUIDE_SAMPLES = 2048

    /** Vertices per curved span of a guide, at least and at most. */
    private const val MIN_SPAN_SAMPLES = 4
    private const val MAX_SPAN_SAMPLES = 128

    /** A guide turns at a joint (a corner, a polyline vertex) when its direction changes more than this. */
    private const val JOINT_TURN_RAD = 1e-3

    /** Largest turn of one step of a corner's fan (as the rectangle guide's 90° corners take 4). */
    private const val CORNER_STEP_RAD = PI / 8.0

    /** Points closer than this (px) are the same point. */
    private const val SAME_POINT = 1e-3

    /** Guides kept: the texts of a layer redraw without rebuilding theirs. */
    private const val CACHE_SIZE = 16

    /**
     * Radius of the curve of a uniform cubic B-spline over a regular octagon of radius 1: it runs
     * between its value at the knots, (4 + 2 cos 45°) / 6, and mid-span, (2 cos 67.5° + 46 cos
     * 22.5°) / 48; the mean is within 0.06 % of both (the Path tool's circle preset).
     */
    private val OCTAGON_CURVE_RADIUS: Double = run {
        val step = 2.0 * PI / CIRCLE_PATH_NODES
        val atKnot = (4.0 + 2.0 * cos(step)) / 6.0
        val midSpan = (2.0 * cos(1.5 * step) + 46.0 * cos(0.5 * step)) / 48.0
        (atKnot + midSpan) / 2.0
    }

    // ------------------------------------------------------------------ the nodes of a spec

    /**
     * [spec]'s nodes when they are its guide: the path is not straight and has 2 to
     * [TextPathSpec.MAX_NODES] nodes, all within range (else the flat fields are the guide, as
     * [TextPathSpec.withNodeFallback] keeps them); sanitized, of order 2 or 4. Null otherwise.
     */
    fun of(spec: TextPathSpec): VSpline? {
        val n = spec.nodes ?: return null
        if (spec.type == TextPathType.NONE || n.points.size < 2 || n.points.size > TextPathSpec.MAX_NODES) return null
        if (n.points.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > VSpline.MAX_COORD || abs(it.y) > VSpline.MAX_COORD }) return null
        val ordered = if (n.order == 2 || n.order == 4) n else n.copy(order = 4)
        return ordered.sanitized()
    }

    /** [spec] with [nodes] as its guide: the type and the flat fields become their v1.8 fallback. */
    fun withNodes(spec: TextPathSpec, nodes: VSpline): TextPathSpec = spec.copy(nodes = nodes).withNodeFallback()

    /** The v1.8 type [nodes] fall back to: a line for two nodes, a curve for more. */
    fun fallbackType(nodes: VSpline): TextPathType = if (nodes.points.size == 2) TextPathType.LINE else TextPathType.CURVE

    /** A polyline (order 2): its corners are its vertices, none is a path node's corner. */
    fun isPolyline(nodes: VSpline): Boolean = nodes.order == 2

    /** A closed guide: cyclic with at least 3 nodes. */
    fun isClosed(nodes: VSpline): Boolean = NurbsGeometry.isClosed(nodes)

    /** Whether the nodes run clockwise on screen (their polygon's signed area, y down). */
    fun isClockwise(nodes: VSpline): Boolean {
        val p = nodes.points
        var a = 0.0
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            a += p[i].x.toDouble() * q.y - q.x.toDouble() * p[i].y
        }
        return a > 0.0
    }

    // ------------------------------------------------------------------ conversion

    /**
     * [spec]'s guide as path nodes (order 4), for TextLabels19.EDIT_AS_PATH; null for straight
     * text. Nodes of a path are kept; a polyline keeps its shape (every vertex becomes a sharp
     * node). A line gives its 2 ends; a curve its 4 control points (exactly the same curve); a
     * circle [CIRCLE_PATH_NODES] nodes (within 0.06 % of its radius); a rectangle its corners as
     * sharp nodes, a rounded one each corner as an exact arc (a sharp node at each end, a weighted
     * one between), plus a node at the text position when it is not one of those.
     */
    fun asPath(spec: TextPathSpec): VSpline? {
        val s = TextPathGeometry.sanitized(spec)
        if (s.type == TextPathType.NONE) return null
        of(s)?.let { n -> return if (!isPolyline(n)) n else VSpline(n.points.map { it.copy(sharp = true) }, 4, true, n.cyclic).sanitized() }
        return when (s.type) {
            TextPathType.NONE -> null
            TextPathType.LINE -> VSpline(listOf(VSplinePoint(s.x1, s.y1), VSplinePoint(s.x2, s.y2)), order = 4)
            TextPathType.CURVE -> VSpline(
                listOf(VSplinePoint(s.x1, s.y1), VSplinePoint(s.cx1, s.cy1), VSplinePoint(s.cx2, s.cy2), VSplinePoint(s.x2, s.y2)),
                order = 4,
            )
            TextPathType.CIRCLE -> circle(s, CIRCLE_PATH_NODES, max(s.radius, TextPathGeometry.MIN_EXTENT) / OCTAGON_CURVE_RADIUS, 4)
            TextPathType.RECT -> rectangle(s, polyline = false)
        }?.sanitized()
    }

    /**
     * [spec]'s guide as a polyline (order 2), for TextLabels19.EDIT_AS_POLYLINE; null for straight
     * text. A polyline is kept; path nodes are sampled ([CUBIC_POLYLINE_SEGMENTS] segments per
     * curved span, fewer when that passes [TextPathSpec.MAX_NODES]). A line gives its 2 ends; a
     * curve [CUBIC_POLYLINE_SEGMENTS] segments; a circle [CIRCLE_POLYLINE_VERTICES] vertices on it;
     * a rectangle its corners, a rounded one each corner in 4 segments, plus a vertex at the text
     * position when it is not one of those.
     */
    fun asPolyline(spec: TextPathSpec): VSpline? {
        val s = TextPathGeometry.sanitized(spec)
        if (s.type == TextPathType.NONE) return null
        of(s)?.let { n -> return if (isPolyline(n)) n else sampled(n) }
        return when (s.type) {
            TextPathType.NONE -> null
            TextPathType.LINE -> VSpline(listOf(VSplinePoint(s.x1, s.y1), VSplinePoint(s.x2, s.y2)), order = 2)
            TextPathType.CURVE -> {
                val pts = ArrayList<VSplinePoint>(CUBIC_POLYLINE_SEGMENTS + 1)
                for (k in 0..CUBIC_POLYLINE_SEGMENTS) {
                    val t = k.toDouble() / CUBIC_POLYLINE_SEGMENTS
                    val m = 1.0 - t
                    val a = m * m * m; val b = 3.0 * m * m * t; val c = 3.0 * m * t * t; val d = t * t * t
                    pts += VSplinePoint(
                        (a * s.x1 + b * s.cx1 + c * s.cx2 + d * s.x2).toFloat(),
                        (a * s.y1 + b * s.cy1 + c * s.cy2 + d * s.y2).toFloat(),
                    )
                }
                VSpline(pts, order = 2)
            }
            TextPathType.CIRCLE -> circle(s, CIRCLE_POLYLINE_VERTICES, max(s.radius, TextPathGeometry.MIN_EXTENT).toDouble(), 2)
            TextPathType.RECT -> rectangle(s, polyline = true)
        }?.sanitized()
    }

    /** [count] nodes on a circle of radius [r] around the circle's center, the first at its text position, in its direction. */
    private fun circle(s: TextPathSpec, count: Int, r: Double, order: Int): VSpline {
        val a0 = Math.toRadians(s.startAngleDeg.toDouble())
        val sign = if (s.clockwise) 1.0 else -1.0
        val pts = List(count) { i ->
            val a = a0 + sign * i * 2.0 * PI / count
            VSplinePoint((s.cx + r * cos(a)).toFloat(), (s.cy + r * sin(a)).toFloat())
        }
        return VSpline(pts, order = order, endpoint = true, cyclic = true)
    }

    /** Collects the nodes of a closed outline, dropping a node on the previous one. */
    private class NodeList {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val weights = ArrayList<Double>()
        val sharp = ArrayList<Boolean>()

        fun add(x: Double, y: Double, isSharp: Boolean, weight: Double = 1.0) {
            val n = xs.size
            if (n > 0 && hypot(x - xs[n - 1], y - ys[n - 1]) < SAME_POINT) {
                if (isSharp) sharp[n - 1] = true
                return
            }
            xs += x; ys += y; weights += weight; sharp += isSharp
        }
    }

    /**
     * The rectangle's outline as nodes (local coordinates turned and moved onto the canvas): from
     * the text position, in the rectangle's direction. Path nodes: the corners sharp, a rounded
     * corner an exact arc (rational quadratic: a sharp node at each end, the tangents' meeting
     * point between, weighted cos(sweep / 2)). Polyline: each quarter arc in 4 segments.
     */
    private fun rectangle(s: TextPathSpec, polyline: Boolean): VSpline {
        val hw = max(s.width, TextPathGeometry.MIN_EXTENT) / 2.0
        val hh = max(s.height, TextPathGeometry.MIN_EXTENT) / 2.0
        val r = s.cornerRadius.toDouble().coerceIn(0.0, min(hw, hh))
        // Corner k (clockwise on screen from the top right): its arc's center and start angle
        // (each arc turns +90°, clockwise on screen); the straight edge from arc k to arc k + 1.
        val ccx = doubleArrayOf(hw - r, hw - r, -hw + r, -hw + r)
        val ccy = doubleArrayOf(-hh + r, hh - r, hh - r, -hh + r)
        val from = doubleArrayOf(-0.5 * PI, 0.0, 0.5 * PI, PI)
        val text = rectTextPosition(s, hw, hh, r, ccx, ccy, from)
        val out = NodeList()

        fun arc(k: Int, a: Double, b: Double) {
            val sweep = b - a
            if (sweep <= 1e-9) return
            // Its start ends the straight edge before it (nothing new after an edge of no length).
            out.add(ccx[k] + r * cos(a), ccy[k] + r * sin(a), isSharp = !polyline)
            if (r < SAME_POINT) return
            if (polyline) {
                val segs = max(1, ceil(sweep / (2.0 * PI / CIRCLE_POLYLINE_VERTICES) - 1e-9).toInt())
                for (j in 1..segs) {
                    val t = a + sweep * j / segs
                    out.add(ccx[k] + r * cos(t), ccy[k] + r * sin(t), isSharp = false)
                }
            } else {
                val half = sweep / 2.0
                val reach = r / cos(half)
                out.add(ccx[k] + reach * cos(a + half), ccy[k] + reach * sin(a + half), isSharp = false, weight = cos(half))
                out.add(ccx[k] + r * cos(b), ccy[k] + r * sin(b), isSharp = true)
            }
        }

        out.add(text.x, text.y, isSharp = !polyline && text.arc >= 0)
        if (text.arc >= 0) {
            val k = text.arc
            arc(k, text.angle, from[k] + 0.5 * PI)
            for (j in 1..3) arc((k + j) % 4, from[(k + j) % 4], from[(k + j) % 4] + 0.5 * PI)
            arc(k, from[k], text.angle)
        } else {
            // On the straight edge before arc `edge`: every arc from there on.
            for (j in 0..3) {
                val k = (text.edge + j) % 4
                arc(k, from[k], from[k] + 0.5 * PI)
            }
        }
        // The outline closes on the text position.
        val n0 = out.xs.size
        if (n0 > 1 && hypot(out.xs[n0 - 1] - out.xs[0], out.ys[n0 - 1] - out.ys[0]) < SAME_POINT) {
            if (out.sharp[n0 - 1]) out.sharp[0] = out.sharp[0] || !polyline
            out.xs.removeAt(n0 - 1); out.ys.removeAt(n0 - 1); out.weights.removeAt(n0 - 1); out.sharp.removeAt(n0 - 1)
        }
        val order = IntArray(out.xs.size) { it }
        if (!s.clockwise) for (i in 1 until order.size) order[i] = order.size - i
        val rot = Math.toRadians(s.rotationDeg.toDouble())
        val c = cos(rot)
        val sn = sin(rot)
        val pts = order.map { i ->
            val x = out.xs[i]; val y = out.ys[i]
            VSplinePoint(
                (s.cx + x * c - y * sn).toFloat(), (s.cy + x * sn + y * c).toFloat(),
                weight = out.weights[i].toFloat(), sharp = out.sharp[i],
            )
        }
        return VSpline(pts, order = if (polyline) 2 else 4, endpoint = true, cyclic = true)
    }

    /** Where the rectangle's text sits (local): on corner arc [arc] at [angle], or (arc -1) on the straight edge before arc [edge]. */
    private class RectPlace(val x: Double, val y: Double, val arc: Int, val angle: Double, val edge: Int)

    /**
     * The point of the outline the ray from the center at the text position's angle meets (as
     * the rectangle guide anchors its text). A point at an edge's end belongs to the arc there.
     */
    private fun rectTextPosition(
        s: TextPathSpec, hw: Double, hh: Double, r: Double, ccx: DoubleArray, ccy: DoubleArray, from: DoubleArray,
    ): RectPlace {
        val th = Math.toRadians(s.startAngleDeg.toDouble())
        val dx = cos(th)
        val dy = sin(th)
        val ex = hw - r
        val ey = hh - r
        // Straight edges: top (before arc 0), right (before arc 1), bottom (2), left (3).
        fun edge(t: Double, along: Double, limit: Double, k: Int): RectPlace? {
            if (!(t > 0.0) || abs(along) > limit - SAME_POINT) return null
            return RectPlace(dx * t, dy * t, -1, 0.0, k)
        }
        (if (dy < 0.0) edge(-hh / dy, dx * (-hh / dy), ex, 0) else null)?.let { return it }
        (if (dx > 0.0) edge(hw / dx, dy * (hw / dx), ey, 1) else null)?.let { return it }
        (if (dy > 0.0) edge(hh / dy, dx * (hh / dy), ex, 2) else null)?.let { return it }
        (if (dx < 0.0) edge(-hw / dx, dy * (-hw / dx), ey, 3) else null)?.let { return it }
        // A corner: the arc of its quadrant, where the ray leaves it.
        val k = when {
            dx >= 0.0 && dy < 0.0 -> 0
            dx > 0.0 && dy >= 0.0 -> 1
            dx <= 0.0 && dy > 0.0 -> 2
            else -> 3
        }
        val b = dx * ccx[k] + dy * ccy[k]
        val disc = b * b - (ccx[k] * ccx[k] + ccy[k] * ccy[k] - r * r)
        val t = b + sqrt(max(0.0, disc))
        val px = dx * t
        val py = dy * t
        var a = if (r < SAME_POINT) from[k] + 0.5 * PI else atan2(py - ccy[k], px - ccx[k])
        // Into the arc's own range (it starts at from[k] and turns +90°).
        while (a < from[k] - 1e-9) a += 2.0 * PI
        while (a > from[k] + 0.5 * PI + 1e-9) a -= 2.0 * PI
        a = a.coerceIn(from[k], from[k] + 0.5 * PI)
        return RectPlace(ccx[k] + r * cos(a), ccy[k] + r * sin(a), k, a, -1)
    }

    /** Path nodes [s] as a polyline: [CUBIC_POLYLINE_SEGMENTS] per curved span (fewer past [TextPathSpec.MAX_NODES]), one per straight one. */
    private fun sampled(s: VSpline): VSpline {
        val closed = isClosed(s)
        val pieces = NurbsGeometry.pieces(s)
        val bases = pieces.map { NurbsGeometry.basis(it) }
        var curved = 0
        var straight = 0
        for (b in bases) if (b.degree >= 2) curved += b.spans.size else straight += b.spans.size
        val budget = TextPathSpec.MAX_NODES - straight - (if (closed) 0 else 1)
        val per = if (curved == 0) 1 else (budget / curved).coerceIn(1, CUBIC_POLYLINE_SEGMENTS)
        val out = NodeList()
        val dim = NurbsGeometry.DIM
        val value = DoubleArray(dim)
        val deriv = DoubleArray(dim)
        for ((i, piece) in pieces.withIndex()) {
            val b = bases[i]
            val p = b.degree
            val h = DoubleArray((p + 1) * dim)
            val scratch = DoubleArray((p + 1) * dim)
            val work = DoubleArray((p + 1) * dim)
            val n = if (p >= 2) per else 1
            for (span in b.spans) {
                NurbsGeometry.spanBezier(piece, b, span, h, scratch)
                for (k in (if (out.xs.isEmpty()) 0 else 1)..n) {
                    NurbsGeometry.bezierAt(h, p, k.toDouble() / n, value, deriv, work)
                    out.add(value[0] / value[2], value[1] / value[2], isSharp = false)
                }
            }
        }
        val m = out.xs.size
        if (closed && m > 3 && hypot(out.xs[m - 1] - out.xs[0], out.ys[m - 1] - out.ys[0]) < SAME_POINT) {
            out.xs.removeAt(m - 1); out.ys.removeAt(m - 1)
        }
        val pts = out.xs.indices.map { VSplinePoint(out.xs[it].toFloat(), out.ys[it].toFloat()) }
        if (pts.size < 2) return VSpline(s.points.take(2).map { VSplinePoint(it.x, it.y) }, order = 2)
        return VSpline(pts.take(TextPathSpec.MAX_NODES), order = 2, endpoint = true, cyclic = closed && pts.size >= 3).sanitized()
    }

    // ------------------------------------------------------------------ the guide

    private val cache = object : LinkedHashMap<VSpline, TextPathGuide>(CACHE_SIZE * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VSpline, TextPathGuide>?): Boolean = size > CACHE_SIZE
    }

    /**
     * The guide of [nodes] (sanitized, at least 2, as [of] gives them): their curve sampled into
     * an arc-length table of about [GUIDE_SAMPLES] vertices (exact on straight pieces), every
     * corner a fan of negligible radius like the rectangle guide's. A closed guide is anchored
     * where it comes closest to the first node. Cached for the last [CACHE_SIZE] node lists.
     */
    fun guide(nodes: VSpline): TextPathGuide {
        synchronized(cache) { cache[nodes]?.let { return it } }
        val g = buildGuide(nodes)
        synchronized(cache) { cache[nodes] = g }
        return g
    }

    private fun buildGuide(s: VSpline): TextPathGuide {
        val pieces = NurbsGeometry.pieces(s)
        val bases = pieces.map { NurbsGeometry.basis(it) }
        var curved = 0
        for (b in bases) if (b.degree >= 2) curved += b.spans.size
        val per = if (curved == 0) 1 else ceil(GUIDE_SAMPLES.toDouble() / curved).toInt().coerceIn(MIN_SPAN_SAMPLES, MAX_SPAN_SAMPLES)
        val g = GuideBuilder(curved * per + s.points.size * 4 + 8)
        val dim = NurbsGeometry.DIM
        val value = DoubleArray(dim)
        val deriv = DoubleArray(dim)
        val tan = DoubleArray(2)
        // The direction of travel at the last vertex, and at the first.
        var lx = 1.0; var ly = 0.0
        var fx = 1.0; var fy = 0.0
        for ((i, piece) in pieces.withIndex()) {
            val b = bases[i]
            val p = b.degree
            val h = DoubleArray((p + 1) * dim)
            val scratch = DoubleArray((p + 1) * dim)
            val work = DoubleArray((p + 1) * dim)
            val n = if (p >= 2) per else 1
            for (span in b.spans) {
                NurbsGeometry.spanBezier(piece, b, span, h, scratch)
                for (k in 0..n) {
                    val u = k.toDouble() / n
                    NurbsGeometry.bezierAt(h, p, u, value, deriv, work)
                    val w = value[2]
                    val x = value[0] / w
                    val y = value[1] / w
                    direction(h, p, u, value, deriv, work, tan)
                    if (g.size == 0) {
                        g.lineTo(x, y, tan[0], tan[1])
                        lx = tan[0]; ly = tan[1]; fx = lx; fy = ly
                        continue
                    }
                    if (k == 0) {
                        // A span starts where the last one ended: a corner when the direction turns there.
                        corner(g, lx, ly, tan[0], tan[1])
                        lx = tan[0]; ly = tan[1]
                        continue
                    }
                    val before = g.size
                    g.lineTo(x, y, tan[0], tan[1])
                    if (g.size > before) { lx = tan[0]; ly = tan[1] }
                }
            }
        }
        if (!isClosed(s) || g.size < 3) return g.build(closed = false)
        // The end meets the start: turning there too, the guide wraps around without a seam.
        corner(g, lx, ly, fx, fy)
        val open = g.build(closed = false)
        if (!(open.length > 0.0)) return open
        return g.build(closed = true, anchor = nearestDistance(open, s.points[0]))
    }

    /** Unit direction of travel of the Bezier [h] of [degree] at [u] into [out], also where its derivative vanishes. */
    private fun direction(h: DoubleArray, degree: Int, u: Double, value: DoubleArray, deriv: DoubleArray, work: DoubleArray, out: DoubleArray) {
        val dim = NurbsGeometry.DIM
        fun at(t: Double): Boolean {
            NurbsGeometry.bezierAt(h, degree, t, value, deriv, work)
            val w = value[2]
            val dx = deriv[0] * w - value[0] * deriv[2]
            val dy = deriv[1] * w - value[1] * deriv[2]
            val l = hypot(dx, dy)
            if (!(l > 1e-12 * max(1.0, w * w))) return false
            out[0] = dx / l; out[1] = dy / l
            return true
        }
        if (at(u)) return
        // A control point on its end point: the direction just inside the span.
        if (at(if (u < 0.5) u + 1e-3 else u - 1e-3)) return
        // The chord of the span.
        val n = degree * dim
        val ax = h[0] / h[2]; val ay = h[1] / h[2]
        val bx = h[n] / h[n + 2]; val by = h[n + 1] / h[n + 2]
        val l = hypot(bx - ax, by - ay)
        if (l > 1e-12) { out[0] = (bx - ax) / l; out[1] = (by - ay) / l } else { out[0] = 1.0; out[1] = 0.0 }
    }

    /**
     * A corner of negligible radius at the last vertex of [g]: vertices on it turning from
     * direction (ax, ay) to (bx, by) in steps of at most [CORNER_STEP_RAD], so bent letters fan
     * out across it as across a sharp corner of the rectangle guide.
     */
    private fun corner(g: GuideBuilder, ax: Double, ay: Double, bx: Double, by: Double) {
        val turn = atan2(ax * by - ay * bx, ax * bx + ay * by)
        if (abs(turn) <= JOINT_TURN_RAD) return
        val steps = max(1, ceil(abs(turn) / CORNER_STEP_RAD).toInt())
        val x = g.lastX
        val y = g.lastY
        val a0 = atan2(ay, ax)
        val step = TextPathGeometry.SHARP_CORNER_RADIUS * abs(turn) / steps
        for (k in 1..steps) {
            val a = a0 + turn * k / steps
            g.add(x, y, cos(a), sin(a), step)
        }
    }

    /** Arc length of [guide]'s point closest to [p] (the first such point). */
    private fun nearestDistance(guide: TextPathGuide, p: VSplinePoint): Double {
        val px = p.x.toDouble()
        val py = p.y.toDouble()
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0 until guide.vertexCount - 1) {
            val a = guide.vertex(i)
            val b = guide.vertex(i + 1)
            val ex = (b.x - a.x).toDouble()
            val ey = (b.y - a.y).toDouble()
            val len2 = ex * ex + ey * ey
            val t = if (len2 > 1e-18) (((px - a.x) * ex + (py - a.y) * ey) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(a.x + ex * t - px, a.y + ey * t - py)
            if (d < bestD - 1e-9) {
                bestD = d
                best = guide.vertexDistance(i) + t * (guide.vertexDistance(i + 1) - guide.vertexDistance(i))
            }
        }
        return if (best >= guide.length) 0.0 else best
    }

    // ------------------------------------------------------------------ edits (the node bar)

    /** The middle of [a] and [b]: weight and thickness blended, no corner. */
    private fun middle(a: VSplinePoint, b: VSplinePoint): VSplinePoint =
        VSplinePoint((a.x + b.x) / 2f, (a.y + b.y) / 2f, weight = (a.weight + b.weight) / 2f, width = (a.width + b.width) / 2f)

    /**
     * TextLabels19.ADD_POINTS: a node at the middle of every segment between two nodes (the
     * closing one of a closed guide included), in order, until [TextPathSpec.MAX_NODES]. The
     * nodes there stay; on a path (order 4) the curve moves a little closer to them, as when the
     * Path tool adds a point. [nodes] itself when it is full.
     */
    fun withAddedPoints(nodes: VSpline): VSpline {
        val pts = nodes.points
        val n = pts.size
        val segs = SplineEditing.polygonSegments(n, nodes.cyclic)
        val room = TextPathSpec.MAX_NODES - n
        if (room <= 0 || segs == 0) return nodes
        val out = ArrayList<VSplinePoint>(min(TextPathSpec.MAX_NODES, n + segs))
        var added = 0
        for (i in 0 until n) {
            out += pts[i]
            if (i < segs && added < room) {
                out += middle(pts[i], pts[(i + 1) % n])
                added++
            }
        }
        return nodes.copy(points = out)
    }

    /**
     * The node bar's Add point: a node after node [selected] at the middle of its segment; with
     * none selected (or the last of an open guide) at the middle of the last segment, before the
     * end. The new nodes and the new node's index; null when [nodes] holds
     * [TextPathSpec.MAX_NODES] (TextLabels19.NODES_MAX).
     */
    fun withPointAfter(nodes: VSpline, selected: Int): Pair<VSpline, Int>? {
        val pts = nodes.points
        val n = pts.size
        if (n >= TextPathSpec.MAX_NODES || n < 2) return null
        val i = if (selected in 0 until n) selected else n - 1
        val closed = n >= 3 && nodes.cyclic
        val (a, b) = if (closed || i < n - 1) i to (i + 1) % n else (n - 2) to (n - 1)
        val at = if (closed || i < n - 1) i + 1 else n - 1
        val list = pts.toMutableList()
        list.add(at, middle(pts[a], pts[b]))
        return nodes.copy(points = list) to at
    }

    /** The node bar's Delete point: [nodes] without node [index]; null when only 2 are left (a guide needs them). */
    fun withoutPoint(nodes: VSpline, index: Int): VSpline? {
        if (nodes.points.size <= 2 || index !in nodes.points.indices) return null
        return SplineEditing.removed(nodes, index).sanitized()
    }

    /** Whether node [index] can be a corner: a path's node, not an end of an open guide. */
    fun canBeSharp(nodes: VSpline, index: Int): Boolean {
        if (isPolyline(nodes) || index !in nodes.points.indices) return false
        return isClosed(nodes) || (index > 0 && index < nodes.points.size - 1)
    }

    /** The node bar's Sharp corner: node [index] a corner or smooth again; null where it can't be ([canBeSharp]). */
    fun withSharpToggled(nodes: VSpline, index: Int): VSpline? {
        if (!canBeSharp(nodes, index)) return null
        val p = nodes.points[index]
        return nodes.copy(points = nodes.points.toMutableList().also { it[index] = p.copy(sharp = !p.sharp) })
    }

    /**
     * v1.9 (A3 step 13): where a tap at [at] (document px) on the guide adds a node, within
     * [tolerance] of what the editor draws: the lines between the nodes (a polyline's guide
     * itself; path nodes show them too), else the curve of path nodes. The node goes on the
     * segment between two nodes there, strictly between them (at a segment's end lies a node), as
     * Add point puts one on a segment: a polyline's exactly at the tap, path nodes' at the point of
     * the segment nearest the tapped point of the curve (so the curve moves a little closer to it,
     * as with Add points). The index the node takes and the node; null off the guide.
     */
    fun pointOnGuide(nodes: VSpline, at: Vec2, tolerance: Float): Pair<Int, VSplinePoint>? {
        if (nodes.points.size < 2 || !at.x.isFinite() || !at.y.isFinite()) return null
        val onLines = SplineEditing.polygonHit(nodes, at)
        val hit = if (onLines != null && onLines.distance <= tolerance) {
            onLines
        } else {
            if (isPolyline(nodes)) return null
            val (q, d) = nearestOnGuide(guide(nodes), at) ?: return null
            if (d > tolerance) return null
            SplineEditing.polygonHit(nodes, q) ?: return null
        }
        if (!hit.interior) return null
        return hit.segment + 1 to SplineEditing.pointOnPolygon(nodes, hit, hit.point)
    }

    /** The point of [guide]'s line nearest to [p] and its distance; null for a guide without a segment. */
    private fun nearestOnGuide(guide: TextPathGuide, p: Vec2): Pair<Vec2, Float>? {
        if (guide.vertexCount < 2) return null
        var best: Vec2? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until guide.vertexCount - 1) {
            val q = Geometry.projectOnSegment(p, guide.vertex(i), guide.vertex(i + 1))
            val d = q.distanceTo(p)
            if (d < bestD) { bestD = d; best = q }
        }
        return best?.let { it to bestD }
    }

    /** [nodes] with node [index] at [at] (kept within range). */
    fun withPointAt(nodes: VSpline, index: Int, at: Vec2): VSpline = SplineEditing.moved(nodes, index, at)

    /** The node nearest to [p] within [tolerance] document px (the newest where they overlap), or -1. */
    fun nearest(nodes: VSpline, p: Vec2, tolerance: Float): Int = SplineEditing.nearestPoint(nodes.points, p, tolerance)
}
