package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.spline.NurbsGeometry
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VSubpath
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry of a generated curve (v1.8 item 6, design §4.4): [subpath] is what the Curve and
 * Polyline tools draw (Bézier anchors); [spline] is set for the Path tool (its control points),
 * null for the others.
 */
class CurveBuild(val subpath: VSubpath, val spline: VSpline?)

/**
 * Builds generated curves ([CurveGenerator], `vector/CurveGenerator.kt`) and recognises their
 * geometry (I16). Frozen signatures (design §4.4); area E (CURVES) owns the bodies.
 *
 * The analytic curves ([Analytic], document px, y down). With centre c, size R, angle α (the
 * outer end, the wave's axis, the first petal) and dir = +1 when [CurveGenerator.clockwise]
 * (screen clockwise), −1 otherwise:
 * - Archimedean: φ in 0..Φ (Φ = 2π·turns), r = R·(inner + (1 − inner)·φ/Φ), angle α + dir·(φ − Φ),
 *   so the outer end is at c + R·(cos α, sin α).
 * - Logarithmic: r = R·growth^((φ − Φ)/2π); Golden: growth = φ⁴ (the golden ratio per quarter turn).
 * - Fermat: both arms through the centre as ONE curve, u in −1..1: r = R·u, angle α + dir·Φ·(u² − 1)
 *   (r² ∝ angle on each arm; smooth through the centre). It has no inner size.
 * - Sine wave: c is the wave's middle, R its length along α, t in 0..1:
 *   c + (t − ½)·R·(cos α, sin α) + side·height·sin(2π·waves·t)·(−sin α, cos α), side −1 when clockwise.
 * - Rose: r = R·cos(k·t), angle α + dir·t, k = n/d reduced; t over one period (π·d when n·d is odd,
 *   2π·d otherwise), so the rose always ends where it started: it is closed.
 *
 * Curve (Bézier, [CurveKind.CURVE]): an adaptive Hermite fit. Anchors at parameter steps (at first
 * [DEFAULT_CURVE_DENSITY] per turn, wave or petal, or the generator's [com.brushwork.paint.ui.common.CurveGenLabels18.POINTS_PER_TURN]), each with
 * BOTH handles from the analytic derivative, `h = P'(t)·Δt/3` on each side, so every anchor is
 * smooth. The span that misses most (the largest distance at [SAMPLES_PER_SPAN] samples between
 * the cubic and the curve at the same parameters) is halved until every span is within
 * [TOLERANCE_PX], at most [MAX_ANCHORS] anchors.
 *
 * Path (NURBS, [CurveKind.PATH]): [com.brushwork.paint.ui.common.CurveGenLabels18.POINTS_PER_TURN] samples (default [DEFAULT_PATH_DENSITY]) per
 * turn, wave or petal (at most [MAX_ANCHORS]), interpolated EXACTLY by a cubic B-spline on the
 * Path tool's own knots ([NurbsGeometry.basis]): clamped (order 4, endpoint on) with the end
 * tangents from the analytic derivative and a tridiagonal (Thomas) solve; cyclic for the rose (a
 * cyclic tridiagonal solve). Its [CurveBuild.subpath] is the spline's Bézier form ([SplineBezier]).
 *
 * Polyline: the Curve fit's anchors, sharp (Polyline offers no generators; this keeps [build]
 * total).
 *
 * [matches] (I16) is true when a path's geometry is its generator's within [MATCH_TOLERANCE] px:
 * a Bézier path must be a Hermite fit of the generator's curve (every anchor on the curve, every
 * handle the derivative times its step / 3, from the start to the end), which survives a
 * similarity (the Transform tool maps the generator with the path, `VectorOps.transformed`); a
 * Path-tool path must have the spline [build] gives (and its Bézier form). Per-point thickness
 * is not geometry and never counts.
 */
object CurveGenerators {
    /** Most anchors of a built curve (and most samples of a built spline). */
    const val MAX_ANCHORS = 400

    /** The Bézier fit's largest distance from the curve (document px). */
    const val TOLERANCE_PX = 0.25f

    /** I16: how far a stored geometry may be from its generator's (document px). */
    const val MATCH_TOLERANCE = 0.01f

    /** The Bézier fit's first anchors per turn, wave or petal when the generator says 0. */
    const val DEFAULT_CURVE_DENSITY = 4

    /** The spline's samples per turn, wave or petal when the generator says 0. */
    const val DEFAULT_PATH_DENSITY = 8

    /** The golden spiral's growth per turn: φ⁴, φ = (1 + √5) / 2. */
    val GOLDEN_GROWTH: Double = ((1.0 + sqrt(5.0)) / 2.0).pow(4)

    /** Samples per span of the fit's error check. */
    private const val SAMPLES_PER_SPAN = 8

    /** The geometry of [g] for a curve of [kind]: never empty, at least two points. */
    fun build(g: CurveGenerator, kind: CurveKind): CurveBuild {
        val a = Analytic(usable(g))
        return when (kind) {
            CurveKind.CURVE -> CurveBuild(hermite(a), null)
            CurveKind.POLYLINE -> {
                val h = hermite(a)
                CurveBuild(VSubpath(h.anchors.map { VAnchor(it.x, it.y, sharp = true) }, h.closed), null)
            }
            CurveKind.PATH -> {
                val s = spline(a)
                CurveBuild(SplineBezier.toSubpath(s), s)
            }
        }
    }

    /**
     * True when [path]'s geometry is its generator's within 0.01 px (I16; see the class docs).
     * The generator describes the source path alone (I17, §4.3: source only): its copies are maps
     * of the source and never part of the comparison.
     */
    fun matches(path: VPath): Boolean {
        val stored = path.generator ?: return false
        val g = stored.sanitized() ?: return false
        val src = ObjectCopies.source(path) as VPath
        if (src.subpaths.size != 1 || src.polyline) return false
        val spline = src.spline
        return if (spline != null) matchesSpline(g, src, spline) else matchesHermite(Analytic(g), src.subpaths[0], MATCH_TOLERANCE.toDouble())
    }

    // ------------------------------------------------------------------ the generator's handles

    /** The centre handle (moves the curve): the spirals' and the rose's centre, the wave's middle. */
    fun centre(g: CurveGenerator): Vec2 = Vec2(g.cx, g.cy)

    /**
     * The size handle: the spirals' outer end, the rose's first petal tip, the end of the wave's
     * axis (half its length from the middle). Dragged out it scales the curve, around it turns it
     * ([withSizeHandle]).
     */
    fun sizeHandle(g: CurveGenerator): Vec2 {
        val r = handleReach(g)
        val a = Math.toRadians(g.angleDeg.toDouble())
        return Vec2((g.cx + r * cos(a)).toFloat(), (g.cy + r * sin(a)).toFloat())
    }

    /**
     * [g] with its size handle at [p]: the size scales by the handle's new distance from the
     * centre (the wave's height with it), the angle turns to the handle's direction. [g] when [p]
     * is on the centre or not finite.
     */
    fun withSizeHandle(g: CurveGenerator, p: Vec2): CurveGenerator {
        val dx = (p.x - g.cx).toDouble()
        val dy = (p.y - g.cy).toDouble()
        val d = hypot(dx, dy)
        if (!d.isFinite() || d < 1e-3) return g
        val reach = handleReach(g)
        if (!(reach > 0.0)) return g
        val k = d / reach
        val angle = Math.toDegrees(atan2(dy, dx)).toFloat()
        val out = g.copy(size = (g.size * k).toFloat(), amplitude = (g.amplitude * k).toFloat(), angleDeg = angle)
        return out.sanitized() ?: g
    }

    /** The size handle's distance from the centre. */
    private fun handleReach(g: CurveGenerator): Double = if (g.kind == CurveGenKind.SINE) g.size / 2.0 else g.size.toDouble()

    /** [g] usable for building: sanitized, or the default generator when it is damaged beyond that. */
    private fun usable(g: CurveGenerator): CurveGenerator = g.sanitized() ?: CurveGenerator()

    // ------------------------------------------------------------------ the analytic curves

    /** The analytic curve of a sanitized generator over [start]..[end] (see the class docs). */
    internal class Analytic(val g: CurveGenerator) {
        val closed: Boolean = g.kind == CurveGenKind.ROSE
        private val alpha = Math.toRadians(g.angleDeg.toDouble())
        private val dir = if (g.clockwise) 1.0 else -1.0
        private val size = g.size.toDouble()
        private val cx = g.cx.toDouble()
        private val cy = g.cy.toDouble()
        private val big = 2.0 * PI * g.turns
        private val inner = g.inner.toDouble()
        private val lnGrowth = when (g.kind) {
            CurveGenKind.GOLDEN -> ln(GOLDEN_GROWTH)
            else -> ln(g.growth.toDouble())
        }
        private val roseN: Int
        private val roseD: Int
        private val k: Double

        init {
            val d = gcd(g.petalsN, g.petalsD).coerceAtLeast(1)
            roseN = g.petalsN / d
            roseD = g.petalsD / d
            k = roseN.toDouble() / roseD
        }

        /** True when the rose's n·d is odd: n petals over π·d; otherwise 2n petals over 2π·d. */
        private val roseOdd = (roseN * roseD) % 2 == 1

        val start: Double = when (g.kind) {
            CurveGenKind.FERMAT -> -1.0
            else -> 0.0
        }

        val end: Double = when (g.kind) {
            CurveGenKind.ARCHIMEDEAN, CurveGenKind.LOGARITHMIC, CurveGenKind.GOLDEN -> big
            CurveGenKind.FERMAT -> 1.0
            CurveGenKind.SINE -> 1.0
            CurveGenKind.ROSE -> if (roseOdd) PI * roseD else 2.0 * PI * roseD
        }

        /** Turns, both arms' turns, waves or petals: what [com.brushwork.paint.ui.common.CurveGenLabels18.POINTS_PER_TURN] counts. */
        val units: Double = when (g.kind) {
            CurveGenKind.ARCHIMEDEAN, CurveGenKind.LOGARITHMIC, CurveGenKind.GOLDEN -> g.turns.toDouble()
            CurveGenKind.FERMAT -> 2.0 * g.turns
            CurveGenKind.SINE -> g.waves.toDouble()
            CurveGenKind.ROSE -> (if (roseOdd) roseN else 2 * roseN).toDouble()
        }

        /** The point at [t] and its derivative d/dt into [out]: x, y, dx, dy. */
        fun eval(t: Double, out: DoubleArray) {
            when (g.kind) {
                CurveGenKind.ARCHIMEDEAN -> polar(
                    r = size * (inner + (1.0 - inner) * t / big),
                    dr = size * (1.0 - inner) / big,
                    theta = alpha + dir * (t - big), dTheta = dir, out = out,
                )
                CurveGenKind.LOGARITHMIC, CurveGenKind.GOLDEN -> {
                    val r = size * kotlin.math.exp(lnGrowth * (t - big) / (2.0 * PI))
                    polar(r, r * lnGrowth / (2.0 * PI), alpha + dir * (t - big), dir, out)
                }
                CurveGenKind.FERMAT -> polar(size * t, size, alpha + dir * big * (t * t - 1.0), 2.0 * dir * big * t, out)
                CurveGenKind.SINE -> {
                    val ax = cos(alpha); val ay = sin(alpha)
                    val side = if (g.clockwise) -1.0 else 1.0
                    val h = g.amplitude.toDouble() * side
                    val w = 2.0 * PI * g.waves
                    val s = sin(w * t); val c = cos(w * t)
                    val along = (t - 0.5) * size
                    out[0] = cx + along * ax - h * s * ay
                    out[1] = cy + along * ay + h * s * ax
                    out[2] = size * ax - h * w * c * ay
                    out[3] = size * ay + h * w * c * ax
                }
                CurveGenKind.ROSE -> polar(size * cos(k * t), -size * k * sin(k * t), alpha + dir * t, dir, out)
            }
        }

        private fun polar(r: Double, dr: Double, theta: Double, dTheta: Double, out: DoubleArray) {
            val c = cos(theta); val s = sin(theta)
            out[0] = cx + r * c
            out[1] = cy + r * s
            out[2] = dr * c - r * dTheta * s
            out[3] = dr * s + r * dTheta * c
        }

        private fun gcd(a: Int, b: Int): Int = if (b == 0) abs(a) else gcd(b, a % b)
    }

    // ------------------------------------------------------------------ Curve: the Hermite fit

    /** One span of the fit: its parameters and its error. */
    private class Span(val t0: Double, val t1: Double, val error: Double)

    /** The adaptive Hermite fit of [a] (see the class docs). */
    private fun hermite(a: Analytic): VSubpath {
        val segmentCap = if (a.closed) MAX_ANCHORS else MAX_ANCHORS - 1
        val per = if (a.g.density > 0) a.g.density else DEFAULT_CURVE_DENSITY
        val first = ceil(a.units * per).toInt().coerceIn(if (a.closed) 3 else 1, segmentCap)
        val v0 = DoubleArray(4)
        val v1 = DoubleArray(4)
        val vm = DoubleArray(4)
        fun spanOf(t0: Double, t1: Double): Span = Span(t0, t1, spanError(a, t0, t1, v0, v1, vm))
        val queue = PriorityQueue<Span>(first * 2) { x, y -> y.error.compareTo(x.error) }
        val step = (a.end - a.start) / first
        for (i in 0 until first) {
            val t0 = a.start + i * step
            val t1 = if (i == first - 1) a.end else a.start + (i + 1) * step
            queue += spanOf(t0, t1)
        }
        var segments = first
        while (segments < segmentCap) {
            val worst = queue.peek() ?: break
            if (!(worst.error > TOLERANCE_PX)) break
            queue.poll()
            val mid = (worst.t0 + worst.t1) / 2.0
            queue += spanOf(worst.t0, mid)
            queue += spanOf(mid, worst.t1)
            segments++
        }
        val spans = queue.toMutableList().apply { sortBy { it.t0 } }
        // The breakpoints: every span's start, then (open) the end.
        val ts = DoubleArray(if (a.closed) spans.size else spans.size + 1)
        for (i in spans.indices) ts[i] = spans[i].t0
        if (!a.closed) ts[spans.size] = a.end
        val n = ts.size
        val anchors = ArrayList<VAnchor>(n)
        val v = DoubleArray(4)
        for (i in 0 until n) {
            a.eval(ts[i], v)
            // The step before and after this anchor (an open end mirrors its one step).
            val after = when {
                i < n - 1 -> ts[i + 1] - ts[i]
                a.closed -> a.end - ts[i]
                else -> ts[i] - ts[i - 1]
            }
            val before = when {
                i > 0 -> ts[i] - ts[i - 1]
                a.closed -> a.end - ts[n - 1]
                else -> after
            }
            anchors += VAnchor(
                x = v[0].toFloat(), y = v[1].toFloat(), sharp = false,
                inX = (-v[2] * before / 3.0).toFloat(), inY = (-v[3] * before / 3.0).toFloat(),
                outX = (v[2] * after / 3.0).toFloat(), outY = (v[3] * after / 3.0).toFloat(),
            )
        }
        return VSubpath(anchors, closed = a.closed)
    }

    /** The largest distance between the Hermite cubic over [t0]..[t1] and the curve, at [SAMPLES_PER_SPAN] samples. */
    private fun spanError(a: Analytic, t0: Double, t1: Double, p: DoubleArray, q: DoubleArray, m: DoubleArray): Double {
        a.eval(t0, p)
        a.eval(t1, q)
        val dt = t1 - t0
        val c1x = p[0] + p[2] * dt / 3.0; val c1y = p[1] + p[3] * dt / 3.0
        val c2x = q[0] - q[2] * dt / 3.0; val c2y = q[1] - q[3] * dt / 3.0
        var worst = 0.0
        for (j in 1 until SAMPLES_PER_SPAN) {
            val u = j.toDouble() / SAMPLES_PER_SPAN
            val w = 1.0 - u
            val bx = w * w * w * p[0] + 3 * w * w * u * c1x + 3 * w * u * u * c2x + u * u * u * q[0]
            val by = w * w * w * p[1] + 3 * w * w * u * c1y + 3 * w * u * u * c2y + u * u * u * q[1]
            a.eval(t0 + dt * u, m)
            val d = hypot(bx - m[0], by - m[1])
            if (d > worst || !d.isFinite()) worst = if (d.isFinite()) d else Double.MAX_VALUE
        }
        return worst
    }

    // ------------------------------------------------------------------ Path: the interpolating spline

    /** The interpolating cubic B-spline of [a]'s samples (see the class docs). */
    private fun spline(a: Analytic): VSpline {
        val per = if (a.g.density > 0) a.g.density else DEFAULT_PATH_DENSITY
        return if (a.closed) {
            val m = ceil(a.units * per).toInt().coerceIn(3, MAX_ANCHORS)
            cyclicSpline(a, m)
        } else {
            val m = (ceil(a.units * per).toInt() + 1).coerceIn(2, MAX_ANCHORS)
            clampedSpline(a, m)
        }
    }

    /** [m] samples from start to end, interpolated by a clamped uniform cubic with the curve's end tangents. */
    private fun clampedSpline(a: Analytic, m: Int): VSpline {
        val n = m + 2
        val h = (a.end - a.start) / (m - 1)
        val qx = DoubleArray(m)
        val qy = DoubleArray(m)
        val v = DoubleArray(4)
        val px = DoubleArray(n)
        val py = DoubleArray(n)
        for (i in 0 until m) {
            a.eval(if (i == m - 1) a.end else a.start + i * h, v)
            qx[i] = v[0]; qy[i] = v[1]
            // The end tangents, d/d(spline parameter) = P'·h: the clamped ends give P1 − P0 = C'(0)/3.
            if (i == 0) { px[0] = v[0]; py[0] = v[1]; px[1] = v[0] + v[2] * h / 3.0; py[1] = v[1] + v[3] * h / 3.0 }
            if (i == m - 1) { px[n - 1] = v[0]; py[n - 1] = v[1]; px[n - 2] = v[0] - v[2] * h / 3.0; py[n - 2] = v[1] - v[3] * h / 3.0 }
        }
        val unknowns = m - 2
        if (unknowns > 0) {
            // The Path tool's own knots for n points of order 4, endpoint on.
            val knots = NurbsGeometry.basis(VSpline(List(n) { VSplinePoint(0f, 0f) }, order = 4, endpoint = true)).knots
            val sub = DoubleArray(unknowns)
            val diag = DoubleArray(unknowns)
            val sup = DoubleArray(unknowns)
            val rx = DoubleArray(unknowns)
            val ry = DoubleArray(unknowns)
            val nb = DoubleArray(4)
            for (i in 1..m - 2) {
                // Sample i at parameter i: span s = i + 3 (knots[s] = i); the nonzero basis are
                // N_i, N_(i+1), N_(i+2) (N_(i+3) is 0 at its own first knot).
                val span = i + 3
                basisFuns(span, i.toDouble(), 3, knots, nb)
                val r = i - 1
                val a0 = nb[0]; val b0 = nb[1]; val c0 = nb[2]
                var bx = qx[i]
                var by = qy[i]
                // Known neighbours move to the right-hand side.
                if (i == 1) { bx -= a0 * px[1]; by -= a0 * py[1]; sub[r] = 0.0 } else sub[r] = a0
                if (i == m - 2) { bx -= c0 * px[m]; by -= c0 * py[m]; sup[r] = 0.0 } else sup[r] = c0
                diag[r] = b0
                rx[r] = bx; ry[r] = by
            }
            val sx = thomas(sub, diag, sup, rx)
            val sy = thomas(sub, diag, sup, ry)
            for (r in 0 until unknowns) { px[r + 2] = sx[r]; py[r + 2] = sy[r] }
        }
        return VSpline(List(n) { VSplinePoint(px[it].toFloat(), py[it].toFloat()) }, order = 4, endpoint = true)
    }

    /** [m] samples over one period, interpolated by a cyclic uniform cubic (the rose). */
    private fun cyclicSpline(a: Analytic, m: Int): VSpline {
        val h = (a.end - a.start) / m
        val qx = DoubleArray(m)
        val qy = DoubleArray(m)
        val v = DoubleArray(4)
        for (i in 0 until m) {
            a.eval(a.start + i * h, v)
            qx[i] = v[0]; qy[i] = v[1]
        }
        // Periodic uniform knots: the curve at its i-th knot is (P_i + 4·P_(i+1) + P_(i+2)) / 6
        // (indices mod m). With R_i = P_(i+1): R_(i−1)/6 + 4·R_i/6 + R_(i+1)/6 = Q_i.
        val rx = cyclicThomas(1.0 / 6.0, 4.0 / 6.0, 1.0 / 6.0, qx)
        val ry = cyclicThomas(1.0 / 6.0, 4.0 / 6.0, 1.0 / 6.0, qy)
        return VSpline(List(m) { j -> val r = (j - 1 + m) % m; VSplinePoint(rx[r].toFloat(), ry[r].toFloat()) }, order = 4, endpoint = true, cyclic = true)
    }

    /** The p + 1 nonzero B-spline basis values at [t] in knot span [span] (The NURBS Book, A2.2) into [out]. */
    internal fun basisFuns(span: Int, t: Double, p: Int, knots: DoubleArray, out: DoubleArray) {
        val left = DoubleArray(p + 1)
        val right = DoubleArray(p + 1)
        out[0] = 1.0
        for (j in 1..p) {
            left[j] = t - knots[span + 1 - j]
            right[j] = knots[span + j] - t
            var saved = 0.0
            for (r in 0 until j) {
                val denom = right[r + 1] + left[j - r]
                val temp = if (denom != 0.0) out[r] / denom else 0.0
                out[r] = saved + right[r + 1] * temp
                saved = left[j - r] * temp
            }
            out[j] = saved
        }
    }

    /** Solves the tridiagonal system (sub, diag, sup) x = rhs (Thomas; the B-spline matrix needs no pivoting). */
    private fun thomas(sub: DoubleArray, diag: DoubleArray, sup: DoubleArray, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val c = DoubleArray(n)
        val d = DoubleArray(n)
        var beta = diag[0]
        c[0] = sup[0] / beta
        d[0] = rhs[0] / beta
        for (i in 1 until n) {
            beta = diag[i] - sub[i] * c[i - 1]
            c[i] = sup[i] / beta
            d[i] = (rhs[i] - sub[i] * d[i - 1]) / beta
        }
        val x = DoubleArray(n)
        x[n - 1] = d[n - 1]
        for (i in n - 2 downTo 0) x[i] = d[i] - c[i] * x[i + 1]
        return x
    }

    /** Solves the cyclic tridiagonal system with constant [a] (sub), [b] (diag), [c] (super) (Sherman–Morrison). */
    private fun cyclicThomas(a: Double, b: Double, c: Double, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val gamma = -b
        val sub = DoubleArray(n) { a }
        val sup = DoubleArray(n) { c }
        val diag = DoubleArray(n) { b }
        diag[0] = b - gamma
        diag[n - 1] = b - c * a / gamma
        val x = thomas(sub, diag, sup, rhs)
        val u = DoubleArray(n)
        u[0] = gamma
        u[n - 1] = c
        val z = thomas(sub, diag, sup, u)
        val fact = (x[0] + a * x[n - 1] / gamma) / (1.0 + z[0] + a * z[n - 1] / gamma)
        for (i in 0 until n) x[i] -= fact * z[i]
        return x
    }

    // ------------------------------------------------------------------ I16: does a path still match?

    /** A Path-tool path: the spline [build] gives (positions, order, flags; weights 1) and its Bézier form. */
    private fun matchesSpline(g: CurveGenerator, path: VPath, stored: VSpline): Boolean {
        val built = spline(Analytic(g))
        if (stored.points.size != built.points.size || stored.order != built.order || stored.cyclic != built.cyclic) return false
        if (!built.cyclic && stored.endpoint != built.endpoint) return false
        val tol = MATCH_TOLERANCE
        for (i in built.points.indices) {
            val s = stored.points[i]
            val b = built.points[i]
            if (s.sharp || abs(s.weight - 1f) > 1e-4f) return false
            if (abs(s.x - b.x) > tol || abs(s.y - b.y) > tol) return false
        }
        return SplineBezier.matches(path)
    }

    /**
     * A Bézier path: a Hermite fit of [a] from its start to its end (see the class docs): true
     * when [parameters] finds one parameter per anchor within [tol].
     */
    internal fun matchesHermite(a: Analytic, sub: VSubpath, tol: Double): Boolean = parameters(a, sub, tol) != null

    /**
     * The parameter of each anchor of [sub] when it is a Hermite fit of [a] within [tol] px (null
     * otherwise). Each anchor's parameter follows from the one before through its out handle's
     * length (|h| = |P'|·Δt/3), then is refined by projecting the anchor onto the curve, so a
     * similarity-mapped fit still matches. Every anchor must be smooth with both handles, the
     * first at the curve's start, the last (open) at its end, and every handle the derivative
     * times its step / 3; a closed fit's last span returns to the start.
     */
    internal fun parameters(a: Analytic, sub: VSubpath, tol: Double): DoubleArray? {
        val anchors = sub.anchors
        val n = anchors.size
        if (n < 2 || n > MAX_ANCHORS || sub.closed != a.closed || (a.closed && n < 3)) return null
        for (an in anchors) if (an.sharp || an.inX == null || an.inY == null || an.outX == null || an.outY == null) return null
        val ts = DoubleArray(n)
        val v = DoubleArray(4)
        val w = DoubleArray(4)
        var t = a.start
        ts[0] = t
        a.eval(t, v)
        if (hypot(anchors[0].x - v[0], anchors[0].y - v[1]) > tol) return null
        val segments = if (a.closed) n else n - 1
        for (i in 0 until segments) {
            val from = anchors[i]
            val wrap = i == n - 1
            a.eval(t, v)
            val d2 = v[2] * v[2] + v[3] * v[3]
            if (!(d2 > 0.0) || !d2.isFinite()) return null
            val ox = from.outX!!.toDouble(); val oy = from.outY!!.toDouble()
            val dt = 3.0 * (ox * v[2] + oy * v[3]) / d2
            if (!(dt > 0.0)) return null
            var next = t + dt
            val to = if (wrap) anchors[0] else anchors[i + 1]
            if (wrap || (!a.closed && i == n - 2)) {
                // The last span ends at the curve's end (the closed curve's end is its start again).
                next = a.end
            } else {
                // Refine: project the next anchor onto the curve near the predicted parameter, only
                // while it misses, and by at most half a span per step: where the curve barely
                // moves (a logarithmic spiral's innermost turns) the anchors' float rounding alone
                // would throw a full Newton step far along the curve.
                next = next.coerceIn(t, a.end)
                for (round in 0 until 3) {
                    a.eval(next, w)
                    if (hypot(to.x - w[0], to.y - w[1]) <= tol * 0.25) break
                    val e2 = w[2] * w[2] + w[3] * w[3]
                    if (!(e2 > 0.0)) break
                    val d = ((to.x - w[0]) * w[2] + (to.y - w[1]) * w[3]) / e2
                    next = (next + d.coerceIn(-0.5 * dt, 0.5 * dt)).coerceIn(t, a.end)
                }
            }
            a.eval(next, w)
            if (hypot(to.x - w[0], to.y - w[1]) > tol) return null
            val step = next - t
            if (!(step > 0.0)) return null
            if (hypot(ox - v[2] * step / 3.0, oy - v[3] * step / 3.0) > tol) return null
            val ix = to.inX!!.toDouble(); val iy = to.inY!!.toDouble()
            if (hypot(ix + w[2] * step / 3.0, iy + w[3] * step / 3.0) > tol) return null
            t = next
            if (!wrap) ts[i + 1] = t
        }
        return ts
    }
}
