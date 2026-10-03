package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * v1.6 §3.2(d): order 3 and 4 splines with equal weights convert EXACTLY: one cubic per span
 * whose points equal de Boor's evaluation of the spline at the same parameter (1000 parameters,
 * within 1e-3 px), for clamped (Endpoint), uniform and periodic (Cyclic) knots.
 */
class NurbsBezierExactTest {

    private fun assertExact(s: VSpline, what: String) {
        val sub = SplineBezier.toSubpath(s)
        val b = NurbsGeometry.basis(s)
        assertEquals("$what: one cubic per span", b.spans.size, SplineTestSupport.segmentCount(sub))
        var worst = 0.0
        val samples = 1000
        for (k in 0 until samples) {
            val t = b.start + (b.end - b.start) * k / (samples - 1)
            // Which span (and segment) t falls in, and its local parameter.
            var si = 0
            for (j in b.spans.indices) if (b.knots[b.spans[j]] <= t) si = j
            val i = b.spans[si]
            val u = ((t - b.knots[i]) / (b.knots[i + 1] - b.knots[i])).coerceIn(0.0, 1.0)
            val q = SplineTestSupport.cubicAt(SplineTestSupport.segment(sub, si), u.toFloat())
            val p = NurbsGeometry.pointAt(s, t)
            worst = max(worst, hypot(q.x - p[0], q.y - p[1]))
        }
        assertTrue("$what: worst $worst px", worst <= 1e-3)
    }

    @Test
    fun order3And4AreExactForEveryKnotKind() {
        val rnd = Random(31)
        for (order in 3..4) {
            for (n in listOf(order, order + 1, 7, 23)) {
                val pts = SplineTestSupport.randomPoints(rnd, n)
                assertExact(VSpline(pts, order, endpoint = true), "order $order, $n points, endpoint")
                assertExact(VSpline(pts, order, endpoint = false), "order $order, $n points, uniform")
                assertExact(VSpline(pts, order, cyclic = true), "order $order, $n points, cyclic")
            }
        }
    }

    @Test
    fun equalWeightsOtherThanOneAreStillExact() {
        val rnd = Random(5)
        val pts = SplineTestSupport.randomPoints(rnd, 9).map { it.copy(weight = 3.5f) }
        assertExact(VSpline(pts, 4), "weights 3.5")
        assertExact(VSpline(pts, 3, cyclic = true), "weights 3.5 cyclic")
    }

    @Test
    fun clampedCurvesTouchTheirEndPointsAndUniformOnesDoNot() {
        val pts = listOf(VSplinePoint(0f, 0f), VSplinePoint(100f, 200f), VSplinePoint(300f, 200f), VSplinePoint(400f, 0f), VSplinePoint(500f, 100f))
        val clamped = SplineBezier.toSubpath(VSpline(pts, 4, endpoint = true)).anchors
        assertEquals(0f, clamped.first().x, 1e-4f); assertEquals(0f, clamped.first().y, 1e-4f)
        assertEquals(500f, clamped.last().x, 1e-3f); assertEquals(100f, clamped.last().y, 1e-3f)
        val uniform = SplineBezier.toSubpath(VSpline(pts, 4, endpoint = false)).anchors
        // A uniform cubic starts at (P0 + 4 P1 + P2) / 6.
        assertEquals((0f + 400f + 300f) / 6f, uniform.first().x, 1e-3f)
        assertEquals((0f + 800f + 200f) / 6f, uniform.first().y, 1e-3f)
    }

    @Test
    fun theBlossomAgreesWithDeBoorOnEveryControlPolygon() {
        // Spot check of the span extraction itself: the Bézier end points of each span are the
        // curve at the span's knots.
        val rnd = Random(77)
        val s = VSpline(SplineTestSupport.randomPoints(rnd, 11, weights = true, widths = true), 5)
        val b = NurbsGeometry.basis(s)
        val h = DoubleArray((b.degree + 1) * NurbsGeometry.DIM)
        val scratch = DoubleArray(h.size)
        for (i in b.spans) {
            NurbsGeometry.spanBezier(s, b, i, h, scratch)
            val a = NurbsGeometry.deBoor(s, b.knots[i])
            for (d in 0 until NurbsGeometry.DIM) assertEquals(a[d], h[d], 1e-6 * max(1.0, kotlin.math.abs(a[d])))
            val e = NurbsGeometry.deBoor(s, b.knots[i + 1] - 1e-12)
            val o = b.degree * NurbsGeometry.DIM
            for (d in 0 until NurbsGeometry.DIM) assertEquals(e[d], h[o + d], 1e-5 * max(1.0, kotlin.math.abs(e[d])))
        }
    }
}
