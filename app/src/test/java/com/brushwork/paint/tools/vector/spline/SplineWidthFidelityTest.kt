package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.vector.CurveWidths
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * v1.7 §3.5 (item 5): every Path point's thickness counts. 3 points at 0 / 100 / 0 % draw a lens
 * at once (v1.6 drew nothing until 5 points): the converted anchors carry the spline's widths,
 * and the width the renderer draws (anchor widths blended with smoothstep along the arc length,
 * [CurveWidths.line]) stays within [SplineBezier.WIDTH_TOL] of the spline's true width, measured
 * at 64 parameters per span. Constant widths convert as in v1.6; the incremental converter still
 * equals a fresh one; the budgets hold.
 */
class SplineWidthFidelityTest {

    private val three = listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))
    private val four = listOf(Vec2(40f, 200f), Vec2(140f, 70f), Vec2(260f, 70f), Vec2(360f, 200f))
    private val five = listOf(Vec2(40f, 200f), Vec2(110f, 80f), Vec2(200f, 60f), Vec2(290f, 80f), Vec2(360f, 200f))

    private fun spline(at: List<Vec2>, widths: List<Float>, order: Int, endpoint: Boolean = true, cyclic: Boolean = false) =
        VSpline(at.mapIndexed { i, p -> VSplinePoint(p.x, p.y, width = widths[i]) }, order, endpoint, cyclic)

    private fun maxAnchorWidth(sub: VSubpath): Float = sub.anchors.maxOf { it.width }

    /** The width line the renderer draws for [sub] (thickness factors, a 1 px line). */
    private fun drawn(sub: VSubpath) = CurveWidths.line(VectorOps.curveAnchors(sub), sub.closed, 0f, false, 1f, tolerance = 0.02f)!!

    /**
     * The worst difference, at [perSpan] parameters of every span of [s] (no corners), between
     * the spline's true width there and the width the renderer draws at that point of the
     * converted curve: the point of [CurveWidths.line] at the same fraction of the arc length
     * (robust where the curve crosses itself or turns back on itself, unlike a nearest point).
     */
    private fun drawnWidthError(s: VSpline, perSpan: Int = 64): Double {
        val line = drawn(SplineBezier.toSubpath(s))
        val lineArc = DoubleArray(line.n)
        for (i in 1 until line.n) lineArc[i] = lineArc[i - 1] + hypot((line.xs[i] - line.xs[i - 1]).toDouble(), (line.ys[i] - line.ys[i - 1]).toDouble())
        // The spline's arc length on a fine grid of parameters; the samples are grid points.
        val b = NurbsGeometry.basis(s)
        val fine = 8
        val steps = b.spans.size * perSpan * fine
        val ts = DoubleArray(steps + 1)
        var j = 0
        for (i in b.spans) for (k in 0 until perSpan * fine) ts[j++] = b.knots[i] + (b.knots[i + 1] - b.knots[i]) * k / (perSpan * fine)
        ts[steps] = b.end
        val arc = DoubleArray(steps + 1)
        val ws = DoubleArray(steps + 1)
        var prev = NurbsGeometry.pointAt(s, ts[0])
        ws[0] = prev[2]
        for (k in 1..steps) {
            val p = NurbsGeometry.pointAt(s, ts[k])
            arc[k] = arc[k - 1] + hypot(p[0] - prev[0], p[1] - prev[1])
            ws[k] = p[2]
            prev = p
        }
        val scale = lineArc[line.n - 1] / arc[steps]
        var worst = 0.0
        var seg = 0
        for (k in fine / 2 until steps step fine) {
            val target = arc[k] * scale
            while (seg < line.n - 2 && lineArc[seg + 1] < target) seg++
            val len = lineArc[seg + 1] - lineArc[seg]
            val f = if (len > 0.0) ((target - lineArc[seg]) / len).coerceIn(0.0, 1.0) else 0.0
            val w = line.ws[seg] + (line.ws[seg + 1] - line.ws[seg]) * f
            worst = max(worst, abs(ws[k] - w))
        }
        return worst
    }

    /** Flattening slack of [drawnWidthError] (the renderer's line, the projection). */
    private val slack = 0.002

    @Test
    fun everyPointsThicknessReachesTheLine() {
        val cases = listOf(three to listOf(0f, 1f, 0f), four to listOf(0f, 1f, 1f, 0f), five to listOf(0f, 1f, 1f, 1f, 0f))
        for ((at, widths) in cases) for (order in 3..6) for (endpoint in listOf(true, false)) {
            val s = spline(at, widths, order, endpoint)
            val what = "${at.size} points, order $order, endpoint $endpoint"
            val sub = SplineBezier.toSubpath(s)
            assertTrue("$what: max anchor width ${maxAnchorWidth(sub)}", maxAnchorWidth(sub) > 0.45f)
            val e = drawnWidthError(s)
            assertTrue("$what: drawn width off by $e", e <= SplineBezier.WIDTH_TOL + slack)
        }
    }

    @Test
    fun threePointsAtZeroFullZeroPeakAtHalf() {
        // Order 3 does not pass through its middle point: neither does its width (Blender's radius).
        val s = spline(three, listOf(0f, 1f, 0f), 3)
        val sub = SplineBezier.toSubpath(s)
        assertEquals(0.5f, maxAnchorWidth(sub), 0.01f)
        val line = drawn(sub)
        var peak = 0f
        for (i in 0 until line.n) peak = max(peak, line.ws[i])
        assertEquals("the rendered peak", 0.5f, peak, 0.01f)
        assertEquals(0f, sub.anchors.first().width, 1e-6f)
        assertEquals(0f, sub.anchors.last().width, 1e-6f)
        // Four points at 0 / 100 / 100 / 0 in order 4 peak at 75 %.
        val sub4 = SplineBezier.toSubpath(spline(four, listOf(0f, 1f, 1f, 0f), 4))
        assertEquals(0.75f, maxAnchorWidth(sub4), 0.01f)
    }

    @Test
    fun theWorstCaseStaysWithinTheCap() {
        // 0 / 300 / 0 % in order 3: 1.5 at the middle, the steepest change there is. Halving
        // needs more than 32 pieces for WIDTH_TOL here (even an optimal cut needs 30 to 33), so
        // the cap is reached: the anchors keep their exact widths, the residual between them is
        // accepted (§3.5 (c) 4; measured ≈ 0.015).
        for (at in listOf(three, listOf(Vec2(20f, 150f), Vec2(200f, 150f), Vec2(380f, 150f)), listOf(Vec2(20f, 280f), Vec2(80f, 20f), Vec2(380f, 260f)))) {
            val s = spline(at, listOf(0f, 3f, 0f), 3)
            val sub = SplineBezier.toSubpath(s)
            val pieces = SplineTestSupport.segmentCount(sub)
            assertTrue("$pieces pieces", pieces <= SplineBezier.WIDTH_MAX_PIECES_PER_SPAN)
            val line = drawn(sub)
            var peak = 0f
            for (i in 0 until line.n) peak = max(peak, line.ws[i])
            assertEquals("the rendered peak", 1.5f, peak, 0.03f)
            val e = drawnWidthError(s)
            assertTrue("residual at the cap $e", e <= 0.02)
        }
        // Half the change fits well within the cap and within WIDTH_TOL.
        val half = spline(three, listOf(0f, 1.5f, 0f), 3)
        assertTrue(SplineTestSupport.segmentCount(SplineBezier.toSubpath(half)) < SplineBezier.WIDTH_MAX_PIECES_PER_SPAN)
        assertTrue(drawnWidthError(half) <= SplineBezier.WIDTH_TOL + slack)
    }

    @Test
    fun randomWidthsAndWeightsAreDrawnAsTheSplineBlendsThem() {
        val rnd = Random(17)
        for (order in 3..6) for (cyclic in listOf(false, true)) {
            val pts = SplineTestSupport.randomPoints(rnd, 9, weights = order % 2 == 0).map { it.copy(x = it.x * 0.4f, y = it.y * 0.3f, width = rnd.nextDouble(0.0, 1.2).toFloat()) }
            val s = VSpline(pts, order, cyclic = cyclic)
            val e = drawnWidthError(s)
            assertTrue("order $order cyclic $cyclic: $e", e <= SplineBezier.WIDTH_TOL + slack)
            // The geometry is still the spline's (I9 conversion tolerance).
            val poly = SplineTestSupport.polyline(SplineBezier.toSubpath(s))
            var worst = 0f
            for (q in SplineTestSupport.splineSamples(s, 800)) worst = max(worst, SplineTestSupport.distanceToPolyline(q, poly))
            assertTrue("order $order cyclic $cyclic: $worst px", worst <= SplineBezier.DEFAULT_TOLERANCE + 0.015f)
        }
    }

    @Test
    fun constantWidthsConvertAsInV16() {
        val rnd = Random(3)
        for (order in 2..6) for (cyclic in listOf(false, true)) {
            val pts = SplineTestSupport.randomPoints(rnd, 11, weights = order == 5)
            val plain = SplineBezier.toSubpath(VSpline(pts, order, cyclic = cyclic))
            for (w in listOf(0f, 0.4f, 2.5f)) {
                val sub = SplineBezier.toSubpath(VSpline(pts.map { it.copy(width = w) }, order, cyclic = cyclic))
                // The same anchors as at 100 %, float for float, only the width differs.
                assertEquals("order $order cyclic $cyclic width $w", plain.copy(anchors = plain.anchors.map { it.copy(width = w) }), sub.copy(anchors = sub.anchors.map { it.copy(width = w) }))
                assertTrue(sub.anchors.all { abs(it.width - w) < 1e-5f })
            }
            // v1.6: exact orders are one cubic per span, approximations at most 16 per span.
            val spans = NurbsGeometry.basis(VSpline(pts, order, cyclic = cyclic)).spans.size
            val n = SplineTestSupport.segmentCount(plain)
            if (order in 3..4) assertEquals(spans, n) else assertTrue(n <= spans * SplineBezier.MAX_PIECES_PER_SPAN)
        }
    }

    /**
     * §3.5(b), I9: a varying-width path saved by v1.6 (its anchors are the constant-width
     * conversion, fewer than v1.7 now gives) still passes [SplineBezier.matches], so it reopens
     * in the Path tool: the anchor counts differ, the curves agree.
     */
    @Test
    fun aV16SavedVaryingWidthPathStillMatches() {
        val rnd = Random(11)
        for (order in 2..6) for (cyclic in listOf(false, true)) {
            val pts = SplineTestSupport.randomPoints(rnd, 9, weights = order == 5)
            val varying = VSpline(pts.mapIndexed { i, p -> p.copy(width = if (i % 2 == 0) 0f else 2f) }, order, cyclic = cyclic)
            val v16 = SplineBezier.toSubpath(VSpline(pts.map { it.copy(width = 1f) }, order, cyclic = cyclic))
            val now = SplineBezier.toSubpath(varying)
            assertTrue("order $order cyclic $cyclic: v1.7 adds pieces", now.anchors.size >= v16.anchors.size)
            val saved = VPath(id = 1000L + order * 2 + (if (cyclic) 1 else 0), subpaths = listOf(v16), spline = varying)
            assertTrue("order $order cyclic $cyclic", SplineBezier.matches(saved))
        }
    }

    @Test
    fun incrementalEqualsFreshOverRandomEditsWithWidths() {
        for (seed in 1..6) {
            val rnd = Random(seed * 104_729L)
            val inc = SplineBezier.Incremental()
            var s = VSpline(SplineTestSupport.randomPoints(rnd, rnd.nextInt(3, 14), widths = true), rnd.nextInt(2, 7), cyclic = rnd.nextBoolean())
            for (step in 0 until 200) {
                val what = "seed $seed step $step (n ${s.points.size}, order ${s.order}, cyclic ${s.cyclic}, endpoint ${s.endpoint})"
                assertEquals(what, SplineBezier.toSubpath(s), inc.toSubpath(s))
                s = edit(rnd, s)
            }
        }
    }

    /** One random edit of [s] (Path-tool kinds, width edits most often). */
    private fun edit(rnd: Random, s: VSpline): VSpline {
        val pts = s.points
        val n = pts.size
        fun at(i: Int, f: (VSplinePoint) -> VSplinePoint) = s.copy(points = pts.toMutableList().also { it[i] = f(it[i]) })
        return when (rnd.nextInt(10)) {
            0, 1, 2 -> if (n == 0) s else at(rnd.nextInt(n)) { it.copy(width = rnd.nextDouble(0.0, 3.0).toFloat()) }
            3, 4 -> if (n == 0) s else at(rnd.nextInt(n)) { it.copy(x = it.x + rnd.nextDouble(-6.0, 6.0).toFloat(), y = it.y + rnd.nextDouble(-6.0, 6.0).toFloat()) }
            5 -> if (n == 0) s else at(rnd.nextInt(n)) { it.copy(weight = rnd.nextDouble(0.1, 10.0).toFloat()) }
            6 -> if (n >= 30) s else s.copy(points = pts.toMutableList().also { it.add(rnd.nextInt(n + 1), VSplinePoint(rnd.nextDouble(20.0, 980.0).toFloat(), rnd.nextDouble(20.0, 980.0).toFloat(), width = rnd.nextDouble(0.0, 3.0).toFloat())) })
            7 -> if (n <= 2) s else s.copy(points = pts.toMutableList().also { it.removeAt(rnd.nextInt(n)) })
            8 -> if (n < 3) s else at(rnd.nextInt(1, n - 1)) { it.copy(sharp = !it.sharp) }
            else -> when (rnd.nextInt(3)) {
                0 -> s.copy(order = rnd.nextInt(VSpline.MIN_ORDER, VSpline.MAX_ORDER + 1))
                1 -> s.copy(cyclic = !s.cyclic)
                else -> s.copy(endpoint = !s.endpoint)
            }
        }
    }

    private fun best(runs: Int, block: () -> Unit): Double {
        var b = Double.MAX_VALUE
        repeat(runs) {
            val t0 = System.nanoTime()
            block()
            b = min(b, (System.nanoTime() - t0) / 1e6)
        }
        return b
    }

    @Test
    fun budgets() {
        val rnd = Random(5)
        // 50 points with varying widths: ≤ 4 ms on the phone (guarded at 1 ms here).
        val fifty = VSpline(SplineTestSupport.randomPoints(rnd, 50, widths = true), 4)
        // 2 000 points at order 6 with random widths: at most 2 × v1.6 (which ignored the widths:
        // the same points at one width convert as v1.6 converted these).
        val pts = SplineTestSupport.randomPoints(rnd, 2000, widths = true)
        val varying = VSpline(pts, 6)
        val constant = VSpline(pts.map { it.copy(width = 1f) }, 6)
        repeat(30) { SplineBezier.toSubpath(fifty) }
        repeat(4) { SplineBezier.toSubpath(varying); SplineBezier.toSubpath(constant) }
        val f = best(40) { SplineBezier.toSubpath(fifty) }
        assertTrue("50 points with widths: $f ms", f <= PerfBudget.ms(1.0))
        val v = best(8) { SplineBezier.toSubpath(varying) }
        val c = best(8) { SplineBezier.toSubpath(constant) }
        println("SplineWidthFidelityTest budgets: 50 points $f ms; 2 000 points order 6: $v ms with widths, $c ms without")
        assertTrue("2 000 points, order 6: $v ms with widths, $c ms without", v <= 2.0 * c)
    }
}
