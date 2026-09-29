package com.brushwork.paint.assist

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerSnap
import com.brushwork.paint.model.RulerType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A curve that a snapped stroke follows (document px). Pure Kotlin so it is unit-testable.
 * Points returned by [project] and [between] always lie exactly on the curve.
 */
sealed class StrokeConstraint {
    /** Closest point of the curve to (x, y). */
    abstract fun project(x: Float, y: Float): Vec2

    /** The point a fraction [t] (0..1) of the way from [a] to [b] (both on the curve), walking along the curve. */
    abstract fun between(a: Vec2, b: Vec2, t: Float): Vec2

    /** Approximate length of the curve between [a] and [b] (an upper bound for curved constraints). */
    abstract fun pathLength(a: Vec2, b: Vec2): Float

    fun project(p: Vec2): Vec2 = project(p.x, p.y)

    /** True when chords visibly differ from the curve, so output must be subdivided along it. */
    open val isCurved: Boolean get() = true

    /** Infinite straight line through ([ox], [oy]) with unit direction ([dx], [dy]). */
    class Line(val ox: Float, val oy: Float, dx: Float, dy: Float) : StrokeConstraint() {
        val dx: Float
        val dy: Float

        init {
            val l = hypot(dx, dy)
            require(l > 1e-9f) { "Line direction must not be zero" }
            this.dx = dx / l
            this.dy = dy / l
        }

        override fun project(x: Float, y: Float): Vec2 {
            val t = (x - ox) * dx + (y - oy) * dy
            return Vec2(ox + dx * t, oy + dy * t)
        }

        override val isCurved: Boolean get() = false

        override fun between(a: Vec2, b: Vec2, t: Float): Vec2 = if (t >= 1f) b else a.lerp(b, t)

        override fun pathLength(a: Vec2, b: Vec2): Float = a.distanceTo(b)

        /** Distance of (x, y) from the line. */
        fun distance(x: Float, y: Float): Float = abs((x - ox) * dy - (y - oy) * dx)
    }

    /** Circle around ([cx], [cy]). */
    class Circle(val cx: Float, val cy: Float, val radius: Float) : StrokeConstraint() {
        override fun project(x: Float, y: Float): Vec2 {
            val vx = x - cx; val vy = y - cy
            val l = hypot(vx, vy)
            return if (l < 1e-6f) Vec2(cx + radius, cy) else Vec2(cx + vx / l * radius, cy + vy / l * radius)
        }

        override fun between(a: Vec2, b: Vec2, t: Float): Vec2 {
            if (t >= 1f) return b
            if (t <= 0f) return a
            val a0 = atan2(a.y - cy, a.x - cx)
            val a1 = atan2(b.y - cy, b.x - cx)
            val ang = a0 + wrapAngle(a1 - a0) * t
            return Vec2(cx + cos(ang) * radius, cy + sin(ang) * radius)
        }

        override fun pathLength(a: Vec2, b: Vec2): Float {
            val a0 = atan2(a.y - cy, a.x - cx)
            val a1 = atan2(b.y - cy, b.x - cx)
            return abs(wrapAngle(a1 - a0)) * radius
        }
    }

    /** Ellipse around ([cx], [cy]) with semi-axes [rx], [ry], rotated by [rotation] radians. */
    class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val rotation: Float) : StrokeConstraint() {
        private val cr = cos(rotation)
        private val sr = sin(rotation)

        /**
         * Closest point of the ellipse. The single-start Newton projection of core/Geometry can
         * converge to a wrong (even far-side) point for positions well inside an eccentric
         * ellipse, which would sweep the stroke around half the curve, so it is compared with
         * guarded Newton runs from the curve points straight across from p along each axis and
         * the closest candidate wins.
         */
        override fun project(x: Float, y: Float): Vec2 {
            val p = Vec2(x, y)
            var best = Geometry.projectOnEllipse(p, Vec2(cx, cy), rx, ry, rotation)
            var bestD = best.distanceTo(p)
            val vx = x - cx; val vy = y - cy
            val lx = vx * cr + vy * sr
            val ly = -vx * sr + vy * cr
            val ux = (lx / rx).coerceIn(-1f, 1f)
            val uy = (ly / ry).coerceIn(-1f, 1f)
            for (i in 0 until 3) {
                val start = when (i) {
                    0 -> atan2(ly / ry, lx / rx)
                    1 -> atan2(signOf(ly) * sqrt(1f - ux * ux), ux)
                    else -> atan2(uy, signOf(lx) * sqrt(1f - uy * uy))
                }
                val q = pointAt(refine(start, lx, ly))
                val d = q.distanceTo(p)
                if (d < bestD) { best = q; bestD = d }
            }
            return best
        }

        /** Newton on the squared distance to local point (lx, ly); stops where it isn't convex. */
        private fun refine(theta: Float, lx: Float, ly: Float): Float {
            var t = theta
            repeat(8) {
                val c = cos(t); val s = sin(t)
                val ex = rx * c - lx; val ey = ry * s - ly
                val f = -ex * rx * s + ey * ry * c
                val df = rx * rx * s * s - ex * rx * c + ry * ry * c * c - ey * ry * s
                if (df <= 1e-6f) return t
                val step = f / df
                t -= step
                if (abs(step) < 1e-6f) return t
            }
            return t
        }

        private fun signOf(v: Float): Float = if (v < 0f) -1f else 1f

        /** Parametric angle of a point (eccentric anomaly) in the ellipse's local frame. */
        private fun param(p: Vec2): Float {
            val vx = p.x - cx; val vy = p.y - cy
            val lx = vx * cr + vy * sr
            val ly = -vx * sr + vy * cr
            return atan2(ly / ry, lx / rx)
        }

        private fun pointAt(theta: Float): Vec2 {
            val lx = rx * cos(theta); val ly = ry * sin(theta)
            return Vec2(cx + lx * cr - ly * sr, cy + lx * sr + ly * cr)
        }

        override fun between(a: Vec2, b: Vec2, t: Float): Vec2 {
            if (t >= 1f) return b
            if (t <= 0f) return a
            val t0 = param(a)
            return pointAt(t0 + wrapAngle(param(b) - t0) * t)
        }

        override fun pathLength(a: Vec2, b: Vec2): Float =
            abs(wrapAngle(param(b) - param(a))) * maxOf(rx, ry)
    }

    companion object {
        private const val TWO_PI = (2 * PI).toFloat()
        private const val PI_F = PI.toFloat()

        /** Wraps an angle difference into (-PI, PI]. */
        fun wrapAngle(a: Float): Float {
            var r = a % TWO_PI
            if (r <= -PI_F) r += TWO_PI
            if (r > PI_F) r -= TWO_PI
            return r
        }
    }
}

/** Builds the stroke constraint that a ruler imposes on a stroke. Pure Kotlin. */
object RulerSnapping {
    /**
     * Constraint for a stroke starting at ([sx], [sy]) (document px). [snapDistance] is how close
     * (document px) the start must be to the ruler itself to snap onto it in PARALLEL mode.
     * Returns null when the stroke can't be constrained: a circle/ellipse through its own center,
     * or a RADIAL stroke starting within [minRadialDistance] of the center (its direction is then
     * resolved later from the first point far enough away, see [radialThrough]).
     */
    fun constraintFor(
        r: RulerSettings,
        sx: Float,
        sy: Float,
        snapDistance: Float,
        minRadialDistance: Float = MIN_RADIUS,
    ): StrokeConstraint? {
        val cx = r.centerX; val cy = r.centerY
        val rot = r.angleDeg * Geometry.DEG
        return when (r.type) {
            RulerType.STRAIGHT -> {
                val ruler = StrokeConstraint.Line(cx, cy, cos(rot), sin(rot))
                if (r.snap == RulerSnap.ON_RULER || ruler.distance(sx, sy) <= snapDistance) ruler
                else StrokeConstraint.Line(sx, sy, ruler.dx, ruler.dy)
            }
            RulerType.CIRCLE -> {
                val d = hypot(sx - cx, sy - cy)
                val onRuler = r.snap == RulerSnap.ON_RULER || abs(d - r.radius) <= snapDistance
                val radius = if (onRuler) r.radius else d
                if (radius < MIN_RADIUS) null else StrokeConstraint.Circle(cx, cy, radius)
            }
            RulerType.ELLIPSE -> {
                val rx = r.radiusX; val ry = r.radiusY
                if (rx < MIN_RADIUS || ry < MIN_RADIUS) return null
                val ruler = StrokeConstraint.Ellipse(cx, cy, rx, ry, rot)
                val onRuler = r.snap == RulerSnap.ON_RULER || ruler.project(sx, sy).distanceTo(Vec2(sx, sy)) <= snapDistance
                val k = if (onRuler) 1f else ellipseScale(r, sx, sy)
                when {
                    onRuler -> ruler
                    k * minOf(rx, ry) < MIN_RADIUS -> null
                    else -> StrokeConstraint.Ellipse(cx, cy, rx * k, ry * k, rot)
                }
            }
            RulerType.RADIAL -> radialThrough(cx, cy, sx, sy, maxOf(minRadialDistance, MIN_RADIUS))
        }
    }

    /** Line through the center ([cx], [cy]) and ([px], [py]), or null if they are closer than [minDistance]. */
    fun radialThrough(cx: Float, cy: Float, px: Float, py: Float, minDistance: Float): StrokeConstraint.Line? {
        val vx = px - cx; val vy = py - cy
        if (hypot(vx, vy) < minDistance.coerceAtLeast(1e-4f)) return null
        return StrokeConstraint.Line(cx, cy, vx, vy)
    }

    /**
     * Scale factor k of the concentric ellipse (k*rx, k*ry) through ([x], [y]):
     * k = sqrt((lx/rx)^2 + (ly/ry)^2) in the ruler's rotated local frame.
     */
    fun ellipseScale(r: RulerSettings, x: Float, y: Float): Float {
        val rot = r.angleDeg * Geometry.DEG
        val vx = x - r.centerX; val vy = y - r.centerY
        val c = cos(rot); val s = sin(rot)
        val lx = vx * c + vy * s
        val ly = -vx * s + vy * c
        val ax = lx / r.radiusX; val ay = ly / r.radiusY
        return sqrt(ax * ax + ay * ay)
    }

    /** Smallest radius (document px) a circular constraint may have. */
    const val MIN_RADIUS = 0.5f
}
