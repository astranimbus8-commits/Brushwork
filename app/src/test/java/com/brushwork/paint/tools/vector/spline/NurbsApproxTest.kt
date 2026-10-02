package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.vector.VSpline
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

/**
 * v1.6 §3.2(c, d): order 5 and 6 splines and weights other than 1 become Hermite cubics within
 * 0.05 px of the spline (both ways: every spline point near the converted curve and every
 * converted point near the spline), with at most 16 pieces per span; and the conversion budgets
 * (200 points at order 4: 0.3 ms on a desktop; 100 points at order 6 with weights: ≤ 5 ms on the
 * phone, guarded here at 1.5 ms).
 */
class NurbsApproxTest {

    /** Converted-curve flattening slack on top of the conversion tolerance. */
    private val slack = 0.012f

    private fun assertClose(s: VSpline, what: String, tolerance: Float = SplineBezier.DEFAULT_TOLERANCE) {
        val sub = SplineBezier.toSubpath(s)
        val b = NurbsGeometry.basis(s)
        val pieces = SplineTestSupport.segmentCount(sub)
        assertTrue("$what: $pieces pieces for ${b.spans.size} spans", pieces <= b.spans.size * SplineBezier.MAX_PIECES_PER_SPAN)
        val poly = SplineTestSupport.polyline(sub)
        val spline = SplineTestSupport.splineSamples(s, 1500)
        var worst = 0f
        for (q in spline) worst = max(worst, SplineTestSupport.distanceToPolyline(q, poly))
        assertTrue("$what: spline → converted $worst px", worst <= tolerance + slack)
        // And back: the converted curve never strays from the spline.
        val fine = SplineTestSupport.splineSamples(s, 6000)
        var back = 0f
        for (k in poly.indices step 7) back = max(back, SplineTestSupport.distanceToPolyline(poly[k], fine))
        assertTrue("$what: converted → spline $back px", back <= tolerance + 0.05f)
    }

    @Test
    fun order5And6StayWithinTheTolerance() {
        val rnd = Random(9)
        for (order in 5..6) {
            for (n in listOf(order, 9, 17)) {
                val pts = SplineTestSupport.randomPoints(rnd, n)
                assertClose(VSpline(pts, order, endpoint = true), "order $order, $n points")
                assertClose(VSpline(pts, order, endpoint = false), "order $order, $n points, uniform")
                assertClose(VSpline(pts, order, cyclic = true), "order $order, $n points, cyclic")
            }
        }
    }

    @Test
    fun weightsStayWithinTheTolerance() {
        val rnd = Random(21)
        for (order in 3..6) {
            // Every point weighted at random in 0.2..5 (a 25 × spread between neighbours).
            val pts = SplineTestSupport.randomPoints(rnd, 12, weights = true, minWeight = 0.2)
            assertClose(VSpline(pts, order), "order $order with weights")
            assertClose(VSpline(pts, order, cyclic = true), "order $order with weights, cyclic")
        }
        // One point at the heaviest weight among the lightest: the curve is pulled hard into it.
        val sharp = SplineTestSupport.randomPoints(Random(3), 6).mapIndexed { i, p -> if (i == 3) p.copy(weight = VSpline.MAX_WEIGHT) else p.copy(weight = VSpline.MIN_WEIGHT) }
        assertClose(VSpline(sharp, 4), "extreme weights")
    }

    @Test
    fun adversarialWeightsStayWithinTheTolerance() {
        // Stress: every point at a random weight over the whole 0.1..10 range (100 × between
        // neighbours), 960 px spans: the 16 pieces of a span go where it turns most.
        val rnd = Random(21)
        for (order in 3..6) {
            val pts = SplineTestSupport.randomPoints(rnd, 12, weights = true)
            assertClose(VSpline(pts, order), "order $order, adversarial weights")
            assertClose(VSpline(pts, order, cyclic = true), "order $order, adversarial weights, cyclic")
        }
    }

    @Test
    fun conversionBudgets() {
        val rnd = Random(1)
        val plain = VSpline(SplineTestSupport.randomPoints(rnd, 200), 4)
        val heavy = VSpline(SplineTestSupport.randomPoints(rnd, 100, weights = true), 6)
        repeat(200) { SplineBezier.toSubpath(plain); SplineBezier.toSubpath(heavy) }
        fun best(s: VSpline): Double {
            var b = Double.MAX_VALUE
            repeat(40) {
                val t0 = System.nanoTime()
                SplineBezier.toSubpath(s)
                b = minOf(b, (System.nanoTime() - t0) / 1e6)
            }
            return b
        }
        val a = best(plain)
        val h = best(heavy)
        assertTrue("200 points, order 4: $a ms", a <= PerfBudget.ms(0.3))
        assertTrue("100 points, order 6, weights: $h ms", h <= PerfBudget.ms(1.5))
    }
}
