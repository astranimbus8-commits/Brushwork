package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Pure geometry of text on a path (no android imports, unit-tested on the JVM): the guide every
 * TextPathType follows, where the text starts on it, which side the letters stand on, and the
 * on-canvas handles that shape it. Everything is in DOCUMENT pixels with y pointing down.
 *
 * Letters and the path
 * --------------------
 * A letter's "up" is always the LEFT normal of the reading direction on screen (for a line drawn
 * left to right it points up; for a clockwise circle it points away from the center). That is the
 * only way to bend letters without mirroring them, so on closed shapes:
 *  - [TextPathSpec.clockwise] is the reading direction: clockwise text reads upright at the top of
 *    the shape (letter tops point outwards), counter-clockwise text upright at the bottom (tops
 *    point to the center);
 *  - [TextPathSpec.side] is where the letter bodies are. When the bodies are on the "up" side
 *    (outside + clockwise, inside + counter-clockwise) the letters STAND on the path (the baseline
 *    is the path); otherwise they HANG from it (the cap line is the path).
 * [TextPathSpec.baselineShift] moves the letters further from the path on their body's side
 * (negative values pull them across it). Open paths (line, curve) always have standing letters.
 */

/** Circle and rectangle: the text can stand on either side and run in either direction. */
val TextPathType.isClosed: Boolean get() = this == TextPathType.CIRCLE || this == TextPathType.RECT

/**
 * The line a text path follows: a dense polyline with a unit tangent at every vertex and the
 * cumulative arc length at every vertex. Between vertices positions are interpolated linearly and
 * tangents by normalized linear interpolation, so the normal turns smoothly and bent letters show
 * no steps. Sharp corners are arcs of negligible radius: their tangent turns over a length far
 * below a pixel, so a letter bent across a corner fans out instead of tearing apart.
 *
 * Arc lengths beyond the ends of an open path continue along the end tangents; on a closed path
 * they wrap around (text longer than the shape overlaps itself).
 */
class TextPathGuide internal constructor(
    private val px: DoubleArray,
    private val py: DoubleArray,
    private val tx: DoubleArray,
    private val ty: DoubleArray,
    private val dist: DoubleArray,
    /** Closed paths (circle, rectangle) wrap around; open ones continue along their end tangents. */
    val closed: Boolean,
    /** Closed paths: arc length of the anchor the text is aligned to. 0 for open paths. */
    val anchor: Double,
) {
    init {
        require(px.isNotEmpty() && px.size == py.size && px.size == tx.size && px.size == ty.size && px.size == dist.size)
        require(!closed || (px.size >= 3 && dist[dist.size - 1] > 0.0)) { "a closed guide needs a length" }
    }

    /** Total arc length (px). */
    val length: Double = dist[dist.size - 1]

    val vertexCount: Int get() = px.size

    /** Angle (radians) the tangent turns between vertex i and i + 1. */
    private val turn = DoubleArray(max(0, px.size - 1)) { i -> angleBetween(tx[i], ty[i], tx[i + 1], ty[i + 1]) }

    /** Whether the tangent turns right (clockwise on screen) between vertex i and i + 1. */
    private val turnsRight = BooleanArray(max(0, px.size - 1)) { i -> tx[i] * ty[i + 1] - ty[i] * tx[i + 1] > 0.0 }

    /**
     * Largest curvature (1 / turn radius) of the path between arc lengths [a] and [b] (a <= b;
     * unwrapped on a closed path), turning right into out[0] and left into out[1]. A sharp corner
     * has a huge curvature; past the ends of an open path it is 0.
     */
    fun curvatureIn(a: Double, b: Double, out: DoubleArray) {
        out[0] = 0.0
        out[1] = 0.0
        if (px.size < 2 || !(b >= a) || !(length > 0.0)) return
        var s = if (closed) a else max(a, 0.0)
        val end = if (closed) min(b, a + length) else min(b, length)
        // Wholly past an end of an open path: straight.
        if (!closed && (s >= length || end <= 0.0)) return
        var steps = 0
        do {
            val u = if (closed) wrap(s) else s
            val i = segmentAt(u).coerceAtMost(px.size - 2)
            val segLen = dist[i + 1] - dist[i]
            val k = if (segLen > 1e-12) turn[i] / segLen else if (turn[i] > 1e-12) Double.POSITIVE_INFINITY else 0.0
            if (turnsRight[i]) out[0] = max(out[0], k) else out[1] = max(out[1], k)
            val next = s - u + dist[i + 1]
            if (!(next > s)) break
            s = next
        } while (s < end && steps++ <= px.size + 2)
    }

    fun vertex(i: Int): Vec2 = Vec2(px[i].toFloat(), py[i].toFloat())

    fun vertexDistance(i: Int): Double = dist[i]

    /** Point at arc length [d]. */
    fun posAt(d: Float): Vec2 {
        val e = DoubleArray(4)
        eval(d.toDouble(), e)
        return Vec2(e[0].toFloat(), e[1].toFloat())
    }

    /** Unit tangent (direction of travel) at arc length [d]. */
    fun tangentAt(d: Float): Vec2 {
        val e = DoubleArray(4)
        eval(d.toDouble(), e)
        return Vec2(e[2].toFloat(), e[3].toFloat())
    }

    /**
     * Unit normal on the LEFT of the direction of travel on screen: the way letter tops point
     * (away from the center of a clockwise circle, toward it on a counter-clockwise one).
     */
    fun normalAt(d: Float): Vec2 {
        val e = DoubleArray(4)
        eval(d.toDouble(), e)
        return Vec2(e[3].toFloat(), (-e[2]).toFloat())
    }

    /** [s] folded into 0 until [length] on a closed path; unchanged on an open one. */
    fun wrap(s: Double): Double {
        if (!closed) return s
        var u = s - floor(s / length) * length
        if (u >= length) u -= length
        if (u < 0.0) u = 0.0
        return u
    }

    /** Position (out[0], out[1]) and unit tangent (out[2], out[3]) at arc length [s]. */
    fun eval(s: Double, out: DoubleArray) {
        val n = px.size
        val last = n - 1
        if (n == 1 || !(length > 0.0)) {
            out[0] = px[0] + tx[0] * s; out[1] = py[0] + ty[0] * s; out[2] = tx[0]; out[3] = ty[0]
            return
        }
        val u = if (closed) wrap(s) else s
        if (!closed && u <= 0.0) {
            out[0] = px[0] + tx[0] * u; out[1] = py[0] + ty[0] * u; out[2] = tx[0]; out[3] = ty[0]
            return
        }
        if (!closed && u >= length) {
            val e = u - length
            out[0] = px[last] + tx[last] * e; out[1] = py[last] + ty[last] * e; out[2] = tx[last]; out[3] = ty[last]
            return
        }
        val i = segmentAt(u)
        val d0 = dist[i]
        val d1 = dist[i + 1]
        val f = if (d1 > d0) ((u - d0) / (d1 - d0)).coerceIn(0.0, 1.0) else 0.0
        out[0] = px[i] + (px[i + 1] - px[i]) * f
        out[1] = py[i] + (py[i + 1] - py[i]) * f
        var x = tx[i] + (tx[i + 1] - tx[i]) * f
        var y = ty[i] + (ty[i + 1] - ty[i]) * f
        var l = hypot(x, y)
        if (l < 1e-9) {
            // Opposite tangents (a cusp): use the chord.
            x = px[i + 1] - px[i]; y = py[i + 1] - py[i]; l = hypot(x, y)
            if (l < 1e-12) { x = tx[i]; y = ty[i]; l = 1.0 }
        }
        out[2] = x / l
        out[3] = y / l
    }

    /** Index i of the segment with dist[i] <= u < dist[i + 1] (u within 0..length). */
    private fun segmentAt(u: Double): Int {
        var lo = 0
        var hi = px.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (dist[mid] <= u) lo = mid else hi = mid
        }
        return lo
    }

    /** First index with dist[i] > u (u < length). */
    private fun firstAbove(u: Double): Int {
        var lo = 0
        var hi = px.size - 1
        if (dist[0] > u) return 0
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (dist[mid] > u) hi = mid else lo = mid
        }
        return hi
    }

    /** Last index with dist[i] < u (u > 0). */
    private fun lastBelow(u: Double): Int {
        var lo = 0
        var hi = px.size - 1
        if (dist[hi] < u) return hi
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (dist[mid] < u) lo = mid else hi = mid
        }
        return lo
    }

    /**
     * The arc length of the next vertex strictly beyond [s] in the direction [forward] (closed
     * paths: on the unwrapped line, lap after lap); ±infinity past the ends of an open path.
     */
    fun nextVertex(s: Double, forward: Boolean): Double {
        val n = px.size
        if (!closed) {
            return if (forward) {
                if (s >= length) Double.POSITIVE_INFINITY else dist[firstAbove(s)]
            } else {
                if (s <= 0.0) Double.NEGATIVE_INFINITY else dist[lastBelow(s)]
            }
        }
        var lap = floor(s / length)
        var u = s - lap * length
        if (u >= length) { lap += 1.0; u -= length }
        if (u < 0.0) u = 0.0
        if (forward) {
            var i = firstAbove(u)
            var r = lap * length + dist[i]
            while (r <= s) {
                i++
                if (i >= n) { i = 1; lap += 1.0 }
                r = lap * length + dist[i]
            }
            return r
        } else {
            var i: Int
            if (u > 0.0) {
                i = lastBelow(u)
            } else {
                i = n - 2; lap -= 1.0
            }
            var r = lap * length + dist[i]
            while (r >= s) {
                i--
                if (i < 0) { i = n - 2; lap -= 1.0 }
                r = lap * length + dist[i]
            }
            return r
        }
    }

    /**
     * Angle (radians) the tangent turns between arc lengths [a] and [b], which must lie within one
     * segment (between two consecutive vertices); 0 past the ends of an open path.
     */
    fun turnBetween(a: Double, b: Double): Double {
        if (px.size < 2) return 0.0
        val mid = (a + b) * 0.5
        val u = if (closed) wrap(mid) else mid
        if (!closed && (u <= 0.0 || u >= length)) return 0.0
        val i = segmentAt(u)
        val segLen = dist[i + 1] - dist[i]
        if (!(segLen > 0.0)) return turn[i]
        return turn[i] * min(1.0, abs(b - a) / segLen)
    }

    private companion object {
        fun angleBetween(ax: Double, ay: Double, bx: Double, by: Double): Double =
            acos((ax * bx + ay * by).coerceIn(-1.0, 1.0))
    }
}

/** Grows the arrays of a [TextPathGuide] vertex by vertex, accumulating arc length. */
internal class GuideBuilder(capacity: Int = 64) {
    private var px = DoubleArray(capacity)
    private var py = DoubleArray(capacity)
    private var tx = DoubleArray(capacity)
    private var ty = DoubleArray(capacity)
    private var dist = DoubleArray(capacity)
    var size = 0
        private set

    val lastX: Double get() = px[size - 1]
    val lastY: Double get() = py[size - 1]

    /** Adds a vertex [step] px of arc after the previous one (vertices that don't advance are dropped). */
    fun add(x: Double, y: Double, dx: Double, dy: Double, step: Double) {
        if (size > 0 && !(step > 1e-12)) return
        if (size == px.size) {
            val c = size * 2
            px = px.copyOf(c); py = py.copyOf(c); tx = tx.copyOf(c); ty = ty.copyOf(c); dist = dist.copyOf(c)
        }
        val l = hypot(dx, dy)
        px[size] = x
        py[size] = y
        tx[size] = if (l > 1e-12) dx / l else 1.0
        ty[size] = if (l > 1e-12) dy / l else 0.0
        dist[size] = if (size == 0) 0.0 else dist[size - 1] + step
        size++
    }

    /** Adds a vertex at the chord distance from the previous one. */
    fun lineTo(x: Double, y: Double, dx: Double, dy: Double) =
        add(x, y, dx, dy, if (size == 0) 0.0 else hypot(x - lastX, y - lastY))

    /**
     * Arc length at which the ray from the origin at [angle] (radians) crosses the polyline (a
     * closed polyline around the origin); 0 when it doesn't.
     */
    fun rayHit(angle: Double): Double {
        val dx = cos(angle)
        val dy = sin(angle)
        for (i in 0 until size - 1) {
            val ax = px[i]; val ay = py[i]
            val ex = px[i + 1] - ax; val ey = py[i + 1] - ay
            val denom = dx * ey - dy * ex
            if (abs(denom) < 1e-12) continue
            val t = (ax * ey - ay * ex) / denom
            val u = (ax * dy - ay * dx) / denom
            if (t > 0.0 && u >= -1e-9 && u <= 1.0 + 1e-9) return dist[i] + u.coerceIn(0.0, 1.0) * (dist[i + 1] - dist[i])
        }
        return 0.0
    }

    /** Rotates every vertex by [radians] around the origin, then moves it by ([ox], [oy]). */
    fun transform(radians: Double, ox: Double, oy: Double) {
        val c = cos(radians)
        val s = sin(radians)
        for (i in 0 until size) {
            val x = px[i]; val y = py[i]
            px[i] = ox + x * c - y * s
            py[i] = oy + x * s + y * c
            val a = tx[i]; val b = ty[i]
            tx[i] = a * c - b * s
            ty[i] = a * s + b * c
        }
    }

    fun build(closed: Boolean, anchor: Double = 0.0): TextPathGuide =
        TextPathGuide(px.copyOf(size), py.copyOf(size), tx.copyOf(size), ty.copyOf(size), dist.copyOf(size), closed, anchor)

    /** The same polyline travelled the other way ([anchor] is measured on the original). */
    fun buildReversed(closed: Boolean, anchor: Double = 0.0): TextPathGuide {
        val n = size
        val total = dist[n - 1]
        val rpx = DoubleArray(n) { px[n - 1 - it] }
        val rpy = DoubleArray(n) { py[n - 1 - it] }
        val rtx = DoubleArray(n) { -tx[n - 1 - it] }
        val rty = DoubleArray(n) { -ty[n - 1 - it] }
        val rd = DoubleArray(n) { total - dist[n - 1 - it] }
        rd[0] = 0.0
        var a = total - anchor
        if (a >= total) a -= total
        if (a < 0.0) a = 0.0
        return TextPathGuide(rpx, rpy, rtx, rty, rd, closed, a)
    }
}

/** Shapes, anchors and handles of [TextPathSpec]s. */
object TextPathGeometry {
    /** Largest distance (px) between a guide polyline and the exact shape. */
    const val GUIDE_TOLERANCE = 0.05

    /** Smallest circle radius and rectangle side (px). */
    const val MIN_EXTENT = 1f

    /** Radius of the arc that stands in for a sharp rectangle corner (px): far below a pixel. */
    internal const val SHARP_CORNER_RADIUS = 1e-3

    /** Largest angle between two guide vertices. */
    private const val MAX_STEP_RAD = 3.0 * PI / 180.0

    /** The guide of [spec], or null for straight text ([TextPathType.NONE]). */
    fun guide(spec: TextPathSpec): TextPathGuide? = when (spec.type) {
        TextPathType.NONE -> null
        TextPathType.LINE -> lineGuide(spec)
        TextPathType.CIRCLE -> circleGuide(spec)
        TextPathType.RECT -> rectGuide(spec)
        TextPathType.CURVE -> curveGuide(spec)
    }

    /**
     * Whether a letter spanning arc lengths [from]..[to] whose ink reaches from [low] to [high] px
     * along the up normal (see [heightOffset]; below the path is negative) is better placed rigidly
     * than bent: where the path turns tighter than the ink can follow. On the inner side of a turn
     * a radius smaller than the ink's reach folds the letter over itself; on the outer side a much
     * smaller one smears it into a wedge (a sharp corner of a square, a tight bend of a curve).
     */
    fun tooCurvedToBend(guide: TextPathGuide, from: Double, to: Double, low: Double, high: Double): Boolean {
        val k = DoubleArray(2)
        guide.curvatureIn(from, to, k)
        val above = max(0.0, high)
        val below = max(0.0, -low)
        // Turning right (clockwise on screen) the inner side is below the letters' baseline.
        val needRight = max(BEND_INNER_MARGIN * below, BEND_OUTER_SHARE * above)
        val needLeft = max(BEND_INNER_MARGIN * above, BEND_OUTER_SHARE * below)
        return k[0] * needRight > 1.0 || k[1] * needLeft > 1.0
    }

    /** Smallest turn radius, in inner reaches of the ink, that bent letters follow. */
    private const val BEND_INNER_MARGIN = 1.15

    /** Smallest turn radius, in outer reaches of the ink, that bent letters follow (they stretch up to (1 + 1/0.6)×). */
    private const val BEND_OUTER_SHARE = 0.6

    private fun arcStep(r: Double): Double =
        if (r <= GUIDE_TOLERANCE * 2.0) PI / 2.0 else min(MAX_STEP_RAD, 2.0 * acos(1.0 - GUIDE_TOLERANCE / r))

    private fun lineGuide(s: TextPathSpec): TextPathGuide {
        val b = GuideBuilder(2)
        val dx = (s.x2 - s.x1).toDouble()
        val dy = (s.y2 - s.y1).toDouble()
        val (ux, uy) = if (hypot(dx, dy) > 1e-9) dx to dy else 1.0 to 0.0
        b.lineTo(s.x1.toDouble(), s.y1.toDouble(), ux, uy)
        b.lineTo(s.x2.toDouble(), s.y2.toDouble(), ux, uy)
        return b.build(closed = false)
    }

    private fun circleGuide(s: TextPathSpec): TextPathGuide {
        val r = max(s.radius, MIN_EXTENT).toDouble()
        val n = ceil(2.0 * PI / arcStep(r)).toInt().coerceIn(24, 4096)
        val sign = if (s.clockwise) 1.0 else -1.0
        val step = 2.0 * PI / n
        val a0 = Math.toRadians(s.startAngleDeg.toDouble())
        val b = GuideBuilder(n + 1)
        val cx = s.cx.toDouble()
        val cy = s.cy.toDouble()
        for (k in 0..n) {
            // The last vertex closes the circle exactly on the first.
            val a = if (k == n) a0 else a0 + sign * step * k
            b.add(cx + r * cos(a), cy + r * sin(a), -sin(a) * sign, cos(a) * sign, if (k == 0) 0.0 else r * step)
        }
        return b.build(closed = true, anchor = 0.0)
    }

    private fun rectGuide(s: TextPathSpec): TextPathGuide {
        val hw = max(s.width, MIN_EXTENT) / 2.0
        val hh = max(s.height, MIN_EXTENT) / 2.0
        val r = s.cornerRadius.toDouble().coerceIn(0.0, min(hw, hh))
        val re = max(r, SHARP_CORNER_RADIUS)
        val b = GuideBuilder(64)
        val arcSteps = max(4, ceil((PI / 2.0) / arcStep(re)).toInt())
        val da = (PI / 2.0) / arcSteps
        fun arc(cx: Double, cy: Double, fromDeg: Double) {
            val a0 = Math.toRadians(fromDeg)
            for (k in 1..arcSteps) {
                val a = a0 + da * k
                b.add(cx + re * cos(a), cy + re * sin(a), -sin(a), cos(a), re * da)
            }
        }
        // Clockwise on screen from the middle of the top edge (local coordinates).
        b.lineTo(0.0, -hh, 1.0, 0.0)
        b.lineTo(hw - re, -hh, 1.0, 0.0)
        arc(hw - re, -hh + re, -90.0)
        b.lineTo(hw, hh - re, 0.0, 1.0)
        arc(hw - re, hh - re, 0.0)
        b.lineTo(-hw + re, hh, -1.0, 0.0)
        arc(-hw + re, hh - re, 90.0)
        b.lineTo(-hw, -hh + re, 0.0, -1.0)
        arc(-hw + re, -hh + re, 180.0)
        b.lineTo(0.0, -hh, 1.0, 0.0)
        val anchor = b.rayHit(Math.toRadians(s.startAngleDeg.toDouble()))
        b.transform(Math.toRadians(s.rotationDeg.toDouble()), s.cx.toDouble(), s.cy.toDouble())
        return if (s.clockwise) b.build(closed = true, anchor = anchor) else b.buildReversed(closed = true, anchor = anchor)
    }

    /** Control points of a cubic Bezier: p0, c1, c2, p3 as x, y pairs. */
    private class Cubic(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x3: Double, val y3: Double) {
        fun x(t: Double): Double { val m = 1 - t; return m * m * m * x0 + 3 * m * m * t * x1 + 3 * m * t * t * x2 + t * t * t * x3 }
        fun y(t: Double): Double { val m = 1 - t; return m * m * m * y0 + 3 * m * m * t * y1 + 3 * m * t * t * y2 + t * t * t * y3 }
        fun dx(t: Double): Double { val m = 1 - t; return 3 * (m * m * (x1 - x0) + 2 * m * t * (x2 - x1) + t * t * (x3 - x2)) }
        fun dy(t: Double): Double { val m = 1 - t; return 3 * (m * m * (y1 - y0) + 2 * m * t * (y2 - y1) + t * t * (y3 - y2)) }

        /** Unit direction of travel at [t], also where the derivative vanishes (a control point on its end point). */
        fun tangent(t: Double, out: DoubleArray) {
            var ax = dx(t); var ay = dy(t)
            val scale = max(1.0, max(abs(x3 - x0) + abs(y3 - y0), abs(x1 - x0) + abs(x2 - x0) + abs(y1 - y0) + abs(y2 - y0)))
            if (hypot(ax, ay) < 1e-9 * scale) {
                val t2 = if (t < 0.5) t + 1e-3 else t - 1e-3
                ax = dx(t2); ay = dy(t2)
            }
            if (hypot(ax, ay) < 1e-12 * scale) { ax = x3 - x0; ay = y3 - y0 }
            if (hypot(ax, ay) < 1e-12) { ax = 1.0; ay = 0.0 }
            val l = hypot(ax, ay)
            out[0] = ax / l; out[1] = ay / l
        }
    }

    private fun curveGuide(s: TextPathSpec): TextPathGuide {
        val c = Cubic(
            s.x1.toDouble(), s.y1.toDouble(), s.cx1.toDouble(), s.cy1.toDouble(),
            s.cx2.toDouble(), s.cy2.toDouble(), s.x2.toDouble(), s.y2.toDouble(),
        )
        val b = GuideBuilder(128)
        val tan = DoubleArray(2)
        c.tangent(0.0, tan)
        b.lineTo(c.x0, c.y0, tan[0], tan[1])
        flattenCubic(c, 0.0, 1.0, c.x0, c.y0, c.x1, c.y1, c.x2, c.y2, c.x3, c.y3, 0, b, tan)
        return b.build(closed = false)
    }

    /**
     * Adaptive flattening (de Casteljau): a piece is emitted when its control points are within
     * [GUIDE_TOLERANCE] of its evenly parameterized chord and its tangent turns less than
     * [MAX_STEP_RAD]; otherwise it is split in half.
     */
    private fun flattenCubic(
        c: Cubic, t0: Double, t1: Double,
        x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double,
        depth: Int, b: GuideBuilder, tan: DoubleArray,
    ) {
        val d1 = hypot(x1 - (2 * x0 + x3) / 3, y1 - (2 * y0 + y3) / 3)
        val d2 = hypot(x2 - (x0 + 2 * x3) / 3, y2 - (y0 + 2 * y3) / 3)
        var flat = depth >= 3 && max(d1, d2) <= GUIDE_TOLERANCE
        if (flat) {
            c.tangent(t0, tan)
            val ax = tan[0]; val ay = tan[1]
            c.tangent(t1, tan)
            flat = ax * tan[0] + ay * tan[1] >= cos(MAX_STEP_RAD)
        }
        if (flat || depth >= 18) {
            c.tangent(t1, tan)
            b.lineTo(x3, y3, tan[0], tan[1])
            return
        }
        // Split at t = 0.5 of this piece.
        val ax = (x0 + x1) / 2; val ay = (y0 + y1) / 2
        val bx = (x1 + x2) / 2; val by = (y1 + y2) / 2
        val cx = (x2 + x3) / 2; val cy = (y2 + y3) / 2
        val abx = (ax + bx) / 2; val aby = (ay + by) / 2
        val bcx = (bx + cx) / 2; val bcy = (by + cy) / 2
        val mx = (abx + bcx) / 2; val my = (aby + bcy) / 2
        val tm = (t0 + t1) / 2
        flattenCubic(c, t0, tm, x0, y0, ax, ay, abx, aby, mx, my, depth + 1, b, tan)
        flattenCubic(c, tm, t1, mx, my, bcx, bcy, cx, cy, x3, y3, depth + 1, b, tan)
    }

    // ------------------------------------------------------------------ text placement

    /** Whether the letters stand on the path (baseline on it) rather than hang from it (cap line on it). */
    fun isStanding(spec: TextPathSpec): Boolean =
        !spec.type.isClosed || (spec.side == TextPathSide.OUTSIDE) == spec.clockwise

    /**
     * Offset (px) along the letters' "up" normal of a glyph's baseline from the path, for glyphs
     * whose capitals are [capHeight] px tall: the baseline shift for standing letters; for hanging
     * ones the cap line sits on the path and the shift pushes them further to their body's side.
     */
    fun heightOffset(spec: TextPathSpec, capHeight: Float): Double =
        if (isStanding(spec)) spec.baselineShift.toDouble() else -capHeight.toDouble() - spec.baselineShift

    /**
     * Arc length at which a text [textWidth] px wide starts on [guide]: open paths align it to
     * the start, middle or end of the path; closed ones to the anchor. Plus [TextPathSpec.offset].
     */
    fun startDistance(spec: TextPathSpec, guide: TextPathGuide, textWidth: Float): Double {
        val w = textWidth.toDouble()
        val base = if (guide.closed) {
            guide.anchor - when (spec.align) {
                TextPathAlign.START -> 0.0
                TextPathAlign.CENTER -> w / 2.0
                TextPathAlign.END -> w
            }
        } else {
            when (spec.align) {
                TextPathAlign.START -> 0.0
                TextPathAlign.CENTER -> (guide.length - w) / 2.0
                TextPathAlign.END -> guide.length - w
            }
        }
        return base + spec.offset
    }

    /**
     * Where the glyph-space point ([x], [y]) lands (x along the text from its start, y DOWN from
     * the baseline, like android text paths) for a text starting at arc length [start] whose
     * baseline sits [shift] px off the path (see [heightOffset]).
     */
    fun placePoint(guide: TextPathGuide, start: Double, shift: Double, x: Float, y: Float): Vec2 {
        val e = DoubleArray(4)
        guide.eval(start + x, e)
        val h = -y + shift
        return Vec2((e[0] + e[3] * h).toFloat(), (e[1] - e[2] * h).toFloat())
    }

    // ------------------------------------------------------------------ handles

    /**
     * Handles that shape [spec]'s path, in document px. Stable order per type:
     * - LINE: 0 start, 1 end, 2 middle (moves the line);
     * - CIRCLE: 0 center (moves it), 1 radius (on the circle opposite the text), 2 text position
     *   (on the circle at [TextPathSpec.startAngleDeg]);
     * - RECT: 0 center (moves it), 1 size (bottom-right corner; the center stays), 2 rotation
     *   (beyond the right edge), 3 corner radius (inside the top-left corner), 4 text position
     *   (on the outline);
     * - CURVE: 0 start, 1 first control point, 2 second control point, 3 end, 4 middle of the
     *   curve (moves it).
     * NONE has none.
     */
    fun handles(spec: TextPathSpec): List<Vec2> = when (spec.type) {
        TextPathType.NONE -> emptyList()
        TextPathType.LINE -> listOf(
            Vec2(spec.x1, spec.y1),
            Vec2(spec.x2, spec.y2),
            Vec2((spec.x1 + spec.x2) / 2f, (spec.y1 + spec.y2) / 2f),
        )
        TextPathType.CIRCLE -> {
            val c = Vec2(spec.cx, spec.cy)
            val r = max(spec.radius, MIN_EXTENT)
            val a = Math.toRadians(spec.startAngleDeg.toDouble()).toFloat()
            listOf(c, c + Vec2.polar(r, a + PI.toFloat()), c + Vec2.polar(r, a))
        }
        TextPathType.RECT -> {
            val hw = max(spec.width, MIN_EXTENT) / 2f
            val hh = max(spec.height, MIN_EXTENT) / 2f
            listOf(
                Vec2(spec.cx, spec.cy),
                rectLocalToDoc(spec, hw, hh),
                rectLocalToDoc(spec, hw + rotationStem(spec), 0f),
                rectLocalToDoc(spec, -hw + cornerHandleInset(spec), -hh + cornerHandleInset(spec)),
                rectGuide(spec).let { g -> g.posAt(g.anchor.toFloat()) },
            )
        }
        TextPathType.CURVE -> listOf(
            Vec2(spec.x1, spec.y1),
            Vec2(spec.cx1, spec.cy1),
            Vec2(spec.cx2, spec.cy2),
            Vec2(spec.x2, spec.y2),
            curveMiddle(spec),
        )
    }

    /** Distance (px) of the rectangle's rotation handle beyond its right edge. */
    fun rotationStem(spec: TextPathSpec): Float = 0.2f * max(max(spec.width, spec.height), MIN_EXTENT)

    /** Largest corner radius of [spec]'s rectangle. */
    fun maxCornerRadius(spec: TextPathSpec): Float = min(max(spec.width, MIN_EXTENT), max(spec.height, MIN_EXTENT)) / 2f

    /** Share of the largest corner radius the corner handle keeps from the corner at radius 0. */
    private const val CORNER_GRIP = 0.12f

    /** Distance of the corner-radius handle from the corner along each side. */
    private fun cornerHandleInset(spec: TextPathSpec): Float {
        val rMax = maxCornerRadius(spec)
        val r = spec.cornerRadius.coerceIn(0f, rMax)
        return rMax * CORNER_GRIP + r * (1f - CORNER_GRIP)
    }

    private fun rectLocalToDoc(spec: TextPathSpec, lx: Float, ly: Float): Vec2 =
        Vec2(lx, ly).rotated(Math.toRadians(spec.rotationDeg.toDouble()).toFloat()) + Vec2(spec.cx, spec.cy)

    private fun rectDocToLocal(spec: TextPathSpec, p: Vec2): Vec2 =
        (p - Vec2(spec.cx, spec.cy)).rotated(-Math.toRadians(spec.rotationDeg.toDouble()).toFloat())

    private fun curveMiddle(spec: TextPathSpec): Vec2 = Vec2(
        (spec.x1 + 3f * spec.cx1 + 3f * spec.cx2 + spec.x2) / 8f,
        (spec.y1 + 3f * spec.cy1 + 3f * spec.cy2 + spec.y2) / 8f,
    )

    private fun degrees(v: Vec2): Float = normalizeDegrees(Math.toDegrees(atan2(v.y, v.x).toDouble()).toFloat())

    /** [deg] in (-180, 180]. */
    fun normalizeDegrees(deg: Float): Float {
        if (!deg.isFinite()) return 0f
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    /** [spec] with handle [index] (see [handles]) dragged to [pos]; unknown indices change nothing. */
    fun moveHandle(spec: TextPathSpec, index: Int, pos: Vec2): TextPathSpec {
        if (!pos.x.isFinite() || !pos.y.isFinite()) return spec
        return when (spec.type) {
            TextPathType.NONE -> spec
            TextPathType.LINE -> when (index) {
                0 -> spec.copy(x1 = pos.x, y1 = pos.y)
                1 -> spec.copy(x2 = pos.x, y2 = pos.y)
                2 -> {
                    val d = pos - Vec2((spec.x1 + spec.x2) / 2f, (spec.y1 + spec.y2) / 2f)
                    spec.copy(x1 = spec.x1 + d.x, y1 = spec.y1 + d.y, x2 = spec.x2 + d.x, y2 = spec.y2 + d.y)
                }
                else -> spec
            }
            TextPathType.CIRCLE -> {
                val c = Vec2(spec.cx, spec.cy)
                when (index) {
                    0 -> spec.copy(cx = pos.x, cy = pos.y)
                    1 -> spec.copy(radius = max(pos.distanceTo(c), MIN_EXTENT))
                    2 -> if (pos.distanceTo(c) < 1e-3f) spec else spec.copy(startAngleDeg = degrees(pos - c))
                    else -> spec
                }
            }
            TextPathType.RECT -> {
                val local = rectDocToLocal(spec, pos)
                when (index) {
                    0 -> spec.copy(cx = pos.x, cy = pos.y)
                    1 -> {
                        var hw = max(abs(local.x), MIN_EXTENT / 2f)
                        var hh = max(abs(local.y), MIN_EXTENT / 2f)
                        if (spec.keepSquare) { val s = (hw + hh) / 2f; hw = s; hh = s }
                        spec.copy(width = 2f * hw, height = 2f * hh, cornerRadius = spec.cornerRadius.coerceIn(0f, min(hw, hh)))
                    }
                    2 -> if (pos.distanceTo(Vec2(spec.cx, spec.cy)) < 1e-3f) spec
                    else spec.copy(rotationDeg = degrees(pos - Vec2(spec.cx, spec.cy)))
                    3 -> {
                        val hw = max(spec.width, MIN_EXTENT) / 2f
                        val hh = max(spec.height, MIN_EXTENT) / 2f
                        val rMax = maxCornerRadius(spec)
                        val inset = ((local.x + hw) + (local.y + hh)) / 2f
                        val r = if (rMax <= 0f) 0f else ((inset - rMax * CORNER_GRIP) / (1f - CORNER_GRIP)).coerceIn(0f, rMax)
                        spec.copy(cornerRadius = r)
                    }
                    4 -> if (local.length < 1e-3f) spec else spec.copy(startAngleDeg = degrees(local))
                    else -> spec
                }
            }
            TextPathType.CURVE -> when (index) {
                0 -> spec.copy(x1 = pos.x, y1 = pos.y)
                1 -> spec.copy(cx1 = pos.x, cy1 = pos.y)
                2 -> spec.copy(cx2 = pos.x, cy2 = pos.y)
                3 -> spec.copy(x2 = pos.x, y2 = pos.y)
                4 -> {
                    val d = pos - curveMiddle(spec)
                    spec.copy(
                        x1 = spec.x1 + d.x, y1 = spec.y1 + d.y, cx1 = spec.cx1 + d.x, cy1 = spec.cy1 + d.y,
                        cx2 = spec.cx2 + d.x, cy2 = spec.cy2 + d.y, x2 = spec.x2 + d.x, y2 = spec.y2 + d.y,
                    )
                }
                else -> spec
            }
        }
    }

    /**
     * [spec] moved by [translation], scaled by [scale] and rotated by [rotationDeg] around
     * [pivot] (p' = pivot + R(p - pivot) * scale + translation). Every shape's points move, sizes,
     * the offset and the baseline shift scale, angles turn — except the rectangle's text position,
     * which is relative to the rectangle and turns with its rotation.
     */
    fun transformed(spec: TextPathSpec, translation: Vec2, scale: Float, rotationDeg: Float, pivot: Vec2): TextPathSpec {
        val k = if (scale.isFinite() && scale > 0f) scale else 1f
        val deg = if (rotationDeg.isFinite()) rotationDeg else 0f
        val t = Vec2(if (translation.x.isFinite()) translation.x else 0f, if (translation.y.isFinite()) translation.y else 0f)
        val rad = Math.toRadians(deg.toDouble()).toFloat()
        fun map(x: Float, y: Float): Vec2 = pivot + (Vec2(x, y) - pivot).rotated(rad) * k + t
        val p1 = map(spec.x1, spec.y1)
        val p2 = map(spec.x2, spec.y2)
        val c1 = map(spec.cx1, spec.cy1)
        val c2 = map(spec.cx2, spec.cy2)
        val c = map(spec.cx, spec.cy)
        return spec.copy(
            x1 = p1.x, y1 = p1.y, x2 = p2.x, y2 = p2.y,
            cx1 = c1.x, cy1 = c1.y, cx2 = c2.x, cy2 = c2.y,
            cx = c.x, cy = c.y,
            radius = spec.radius * k,
            width = spec.width * k,
            height = spec.height * k,
            cornerRadius = spec.cornerRadius * k,
            rotationDeg = normalizeDegrees(spec.rotationDeg + deg),
            startAngleDeg = if (spec.type == TextPathType.RECT) spec.startAngleDeg else normalizeDegrees(spec.startAngleDeg + deg),
            offset = spec.offset * k,
            baselineShift = spec.baselineShift * k,
        )
    }

    /**
     * A path of [type] that fits a text [textWidth] px wide set in [fontSize] px whose straight
     * layout is centered at [center], so it stays where it was: a horizontal line under it, a
     * gentle arch as wide as it, a circle or a square with the text centered on top (clockwise)
     * or at the bottom (counter-clockwise). Fields of other shapes, the mode, side, direction,
     * alignment and shifts are kept from [current].
     */
    fun defaultFor(type: TextPathType, center: Vec2, textWidth: Float, fontSize: Float, current: TextPathSpec): TextPathSpec {
        val fs = if (fontSize.isFinite() && fontSize > 0f) fontSize else 48f
        val w = if (textWidth.isFinite() && textWidth > 0f) textWidth else fs
        // Straight text centered on `center`: its baseline sits about 0.35 em below the center and
        // its capitals reach about 0.7 em above the baseline.
        val baseY = center.y + 0.35f * fs
        val capY = baseY - 0.7f * fs
        return when (type) {
            TextPathType.NONE -> current.copy(type = TextPathType.NONE)
            TextPathType.LINE -> {
                val half = max(w, 2f * fs) / 2f
                current.copy(type = type, x1 = center.x - half, y1 = baseY, x2 = center.x + half, y2 = baseY)
            }
            TextPathType.CURVE -> {
                // A parabolic arch whose middle is on the old baseline.
                val chord = max(w * 1.1f, 3f * fs)
                val rise = 0.2f * chord
                val ye = baseY + rise
                val yc = ye - rise * 4f / 3f
                current.copy(
                    type = type,
                    x1 = center.x - chord / 2f, y1 = ye,
                    cx1 = center.x - chord / 6f, cy1 = yc,
                    cx2 = center.x + chord / 6f, cy2 = yc,
                    x2 = center.x + chord / 2f, y2 = ye,
                )
            }
            TextPathType.CIRCLE -> {
                val r = max(w / (1.2f * PI.toFloat()), 2f * fs)
                val next = current.copy(type = type, radius = r, startAngleDeg = if (current.clockwise) -90f else 90f)
                val pathY = if (isStanding(next)) baseY else capY
                next.copy(cx = center.x, cy = if (current.clockwise) pathY + r else pathY - r)
            }
            TextPathType.RECT -> {
                val side = max(w * 1.2f, 3f * fs)
                val next = current.copy(
                    type = type, width = side, height = side, rotationDeg = 0f,
                    cornerRadius = current.cornerRadius.coerceIn(0f, side / 2f),
                    startAngleDeg = if (current.clockwise) -90f else 90f,
                )
                val pathY = if (isStanding(next)) baseY else capY
                next.copy(cx = center.x, cy = if (current.clockwise) pathY + side / 2f else pathY - side / 2f)
            }
        }
    }

    /** Length (px) of [spec]'s path, 0 for straight text. */
    fun pathLength(spec: TextPathSpec): Float = guide(spec)?.length?.toFloat() ?: 0f
}
