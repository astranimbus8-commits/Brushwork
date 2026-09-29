package com.brushwork.paint.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable 2D vector / point in document pixel coordinates. Pure Kotlin. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun cross(o: Vec2) = x * o.y - y * o.x
    val length: Float get() = hypot(x, y)
    val lengthSq: Float get() = x * x + y * y
    fun normalized(): Vec2 { val l = length; return if (l < 1e-6f) Vec2(0f, 0f) else Vec2(x / l, y / l) }
    fun distanceTo(o: Vec2) = hypot(x - o.x, y - o.y)
    fun perpendicular() = Vec2(-y, x)
    fun rotated(radians: Float): Vec2 { val c = cos(radians); val s = sin(radians); return Vec2(x * c - y * s, x * s + y * c) }
    fun rotatedAround(center: Vec2, radians: Float) = (this - center).rotated(radians) + center
    val angle: Float get() = atan2(y, x)
    fun lerp(o: Vec2, t: Float) = Vec2(x + (o.x - x) * t, y + (o.y - y) * t)

    companion object {
        val ZERO = Vec2(0f, 0f)
        fun polar(radius: Float, radians: Float) = Vec2(cos(radians) * radius, sin(radians) * radius)
    }
}

object Geometry {
    const val DEG = (Math.PI / 180.0).toFloat()

    /** Closest point to [p] on the infinite line through [a] with direction [dir]. */
    fun projectOnLine(p: Vec2, a: Vec2, dir: Vec2): Vec2 {
        val d = dir.normalized()
        val t = (p - a).dot(d)
        return a + d * t
    }

    /** Closest point to [p] on segment [a]-[b]. */
    fun projectOnSegment(p: Vec2, a: Vec2, b: Vec2): Vec2 {
        val ab = b - a
        val len2 = ab.lengthSq
        if (len2 < 1e-9f) return a
        val t = ((p - a).dot(ab) / len2).coerceIn(0f, 1f)
        return a + ab * t
    }

    fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Float = p.distanceTo(projectOnSegment(p, a, b))

    /** Closest point on circle (center, radius) to p. */
    fun projectOnCircle(p: Vec2, center: Vec2, radius: Float): Vec2 {
        val d = p - center
        val l = d.length
        return if (l < 1e-6f) center + Vec2(radius, 0f) else center + d * (radius / l)
    }

    /**
     * Approximate closest point on an ellipse with semi-axes [rx], [ry] rotated by [rotation]
     * radians around [center]. Uses a few Newton iterations on the parametric angle.
     */
    fun projectOnEllipse(p: Vec2, center: Vec2, rx: Float, ry: Float, rotation: Float): Vec2 {
        val local = (p - center).rotated(-rotation)
        if (rx <= 1e-6f || ry <= 1e-6f) return center
        var t = atan2(local.y * rx, local.x * ry)
        repeat(6) {
            val ct = cos(t); val st = sin(t)
            val ex = rx * ct; val ey = ry * st
            val dx = ex - local.x; val dy = ey - local.y
            val fx = -rx * st; val fy = ry * ct
            val f = dx * fx + dy * fy
            val df = fx * fx + fy * fy + dx * (-rx * ct) + dy * (-ry * st)
            if (kotlin.math.abs(df) > 1e-9f) t -= f / df
        }
        return Vec2(rx * cos(t), ry * sin(t)).rotated(rotation) + center
    }

    /** Segment-segment intersection point or null. */
    fun segmentIntersection(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Vec2? {
        val r = p2 - p1; val s = p4 - p3
        val denom = r.cross(s)
        if (kotlin.math.abs(denom) < 1e-9f) return null
        val t = (p3 - p1).cross(s) / denom
        val u = (p3 - p1).cross(r) / denom
        return if (t in 0f..1f && u in 0f..1f) p1 + r * t else null
    }

    /** Polygon signed area (positive = counter-clockwise in y-up; clockwise on screen). */
    fun signedArea(poly: List<Vec2>): Float {
        var a = 0f
        for (i in poly.indices) { val p = poly[i]; val q = poly[(i + 1) % poly.size]; a += p.cross(q) }
        return a / 2f
    }

    fun pointInPolygon(p: Vec2, poly: List<Vec2>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun distance(x1: Float, y1: Float, x2: Float, y2: Float) = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
}
