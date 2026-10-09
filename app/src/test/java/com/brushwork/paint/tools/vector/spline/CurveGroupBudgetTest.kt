package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveAnchor
import com.brushwork.paint.tools.vector.CurveGroupMath
import com.brushwork.paint.tools.vector.toCurveAnchor
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1.7 (item 1 and 12, design §3.1c and §6.3): the model side of the group-drag budgets, per
 * frame (one `Affine2` of the captured points). A Curve or Polyline of 2 000 anchors moved whole:
 * ≤ 2 ms. 200 neighbouring control points of a 2 000-point Path: only the spans they influence
 * are converted again (`SplineBezier.Incremental`), ≤ 16 ms, and the result is the fresh
 * conversion (I9); 500 of a hand-drawn spiral selected: half the phone's ≤ 6 ms at order
 * 4 and ≤ 16 ms at order 6 (random points with random weights and widths, the worst case, take
 * longer: a device row). Every point of a Path:
 * its cached Bézier form is mapped directly, ≤ 2 ms, no conversion until the finger lifts.
 * Medians after a warm-up (the JIT); the device rows of §6.3 measure the whole frame on the phone.
 */
class CurveGroupBudgetTest {

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    private inline fun frames(n: Int, warm: Int = 15, f: (Int) -> Unit): Double {
        repeat(warm) { f(it) }
        val t = DoubleArray(n)
        for (k in 0 until n) {
            val t0 = System.nanoTime()
            f(warm + k)
            t[k] = (System.nanoTime() - t0) / 1e6
        }
        return median(t)
    }

    private fun drag(k: Int): Affine2 = Affine2.translate(0.5f * k, -0.25f * k) * Affine2.rotateAbout(Vec2(500f, 500f), 0.1f * k)

    @Test
    fun aCurveOf2000AnchorsMovedWholeTakesAtMost2Ms() {
        val rnd = Random(17)
        val base = List(2000) { CurveAnchor(rnd.nextFloat() * 1000f, rnd.nextFloat() * 1000f, width = rnd.nextFloat() * 2f) }
        var out: List<CurveAnchor> = base
        val ms = frames(41) { k -> out = CurveGroupMath.mappedAnchors(base, null, drag(k + 1), 1e6f) }
        println("2000 anchors, group drag: %.3f ms per frame (median)".format(ms))
        assertEquals(base.map { it.width }, out.map { it.width })
        assertTrue("$ms ms", ms <= PerfBudget.ms(2.0))
    }

    @Test
    fun twoHundredOf2000PathPointsConvertOnlyTheirSpans() {
        val rnd = Random(2000)
        val s = VSpline(SplineTestSupport.randomPoints(rnd, 2000, weights = true, widths = true), order = 4)
        val sel = PointSelection.none(2000).plusAll((900 until 1100).toList())
        val inc = SplineBezier.Incremental()
        inc.toSubpath(s)
        var last = s
        val ms = frames(31) { k ->
            last = s.copy(points = CurveGroupMath.mappedPoints(s.points, sel, drag(k + 1)))
            inc.toSubpath(last).anchors.map { it.toCurveAnchor() }
        }
        println("200 of 2000 Path points (order 4), group drag: %.3f ms per frame (median)".format(ms))
        assertEquals("I9: the fresh conversion", SplineBezier.toSubpath(last), inc.toSubpath(last))
        assertTrue("$ms ms", ms <= PerfBudget.ms(16.0))
    }

    /**
     * A hand-drawn long path: 2 000 control points along a widening spiral (1 to 45 px apart),
     * the thickness swelling and thinning along it.
     */
    private fun spiral(n: Int): List<VSplinePoint> = List(n) { i ->
        val a = i * 0.05
        val r = 20.0 + 0.45 * i
        VSplinePoint((500 + r * cos(a)).toFloat(), (500 + r * sin(a)).toFloat(), width = (1.0 + 0.5 * sin(i * 0.1)).toFloat())
    }

    @Test
    fun fiveHundredSelectedPathPointsAtOrder4And6() {
        // Half the phone's budget here (the desktop is several times faster).
        for ((order, budget) in listOf(4 to 6.0, 6 to 16.0)) {
            val s = VSpline(spiral(2000), order = order)
            val sel = PointSelection.none(2000).plusAll((750 until 1250).toList())
            val inc = SplineBezier.Incremental()
            inc.toSubpath(s)
            var last = s
            val ms = frames(21, warm = 60) { k ->
                last = s.copy(points = CurveGroupMath.mappedPoints(s.points, sel, drag(k + 1)))
                inc.toSubpath(last).anchors.map { it.toCurveAnchor() }
            }
            println("500 of 2000 Path points (order $order), gizmo drag: %.3f ms per frame (median)".format(ms))
            assertEquals("I9: the fresh conversion", SplineBezier.toSubpath(last), inc.toSubpath(last))
            assertTrue("order $order: $ms ms", ms <= PerfBudget.ms(budget / 2))
        }
    }

    @Test
    fun everyPathPointMovedMapsTheCachedBezierForm() {
        val rnd = Random(4000)
        val s = VSpline(SplineTestSupport.randomPoints(rnd, 2000, weights = true, widths = true), order = 4)
        val anchors = SplineBezier.toSubpath(s).anchors.map { it.toCurveAnchor() }
        val all = PointSelection.all(2000)
        var mapped: List<CurveAnchor> = anchors
        val ms = frames(41) { k ->
            s.copy(points = CurveGroupMath.mappedPoints(s.points, all, drag(k + 1)))
            mapped = CurveGroupMath.mappedAnchors(anchors, null, drag(k + 1), VSpline.MAX_COORD)
        }
        println("2000 Path points moved whole (${anchors.size} anchors): %.3f ms per frame (median)".format(ms))
        assertEquals(anchors.size, mapped.size)
        assertTrue("$ms ms", ms <= PerfBudget.ms(2.0))
    }
}
