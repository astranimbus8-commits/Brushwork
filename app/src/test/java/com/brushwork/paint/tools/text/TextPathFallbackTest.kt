package com.brushwork.paint.tools.text

import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * v1.9 F1a (R12 b and e, design 2.3.1): [TextPathSpec.nodes] and its v1.8 fallback
 * ([TextPathSpec.withNodeFallback], run by `TextItem.sanitized` and so by every encode and
 * decode). Two nodes give a LINE from the first to the last point; more give a CURVE whose cubic
 * passes through the guide's points at 0, 1/3, 2/3 and 1 of its arc length; a closed guide falls
 * back to an open curve whose ends meet. Unusable nodes are dropped and the flat fields kept. JVM.
 */
class TextPathFallbackTest {
    private fun spline(vararg xy: Float, order: Int = 4, cyclic: Boolean = false) =
        VSpline((xy.indices step 2).map { VSplinePoint(xy[it], xy[it + 1]) }, order = order, cyclic = cyclic)

    /** The cubic of the flat CURVE fields at [t]. */
    private fun bezier(s: TextPathSpec, t: Double): Pair<Double, Double> {
        val u = 1 - t
        val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
        return (a * s.x1 + b * s.cx1 + c * s.cx2 + d * s.x2) to (a * s.y1 + b * s.cy1 + c * s.cy2 + d * s.y2)
    }

    private fun assertNear(what: String, ex: Double, ey: Double, p: Pair<Double, Double>, tol: Double) {
        assertTrue("$what: ($ex, $ey) expected, got $p", hypot(p.first - ex, p.second - ey) <= tol)
    }

    private val curve = TextPathSpec(type = TextPathType.CURVE, x1 = 1f, y1 = 2f, cx1 = 3f, cy1 = 4f, cx2 = 5f, cy2 = 6f, x2 = 7f, y2 = 8f)

    @Test
    fun withoutNodesNothingChanges() {
        assertSame(curve, curve.withNodeFallback())
        val none = TextPathSpec()
        assertSame(none, none.withNodeFallback())
    }

    @Test
    fun twoNodesGiveALineFromTheFirstToTheLast() {
        val out = curve.copy(nodes = spline(10f, 20f, 110f, 70f)).withNodeFallback()
        assertEquals(TextPathType.LINE, out.type)
        assertEquals(listOf(10f, 20f, 110f, 70f), listOf(out.x1, out.y1, out.x2, out.y2))
        assertNotNull("the nodes stay", out.nodes)
        // The other flat fields keep their values.
        assertEquals(listOf(3f, 4f, 5f, 6f), listOf(out.cx1, out.cy1, out.cx2, out.cy2))
    }

    @Test
    fun aPolylineGivesTheCubicThroughItsThirds() {
        // Arc length 180: a third is at (60, 0), two thirds at (90, 30).
        val out = TextPathSpec(type = TextPathType.LINE).copy(nodes = spline(0f, 0f, 90f, 0f, 90f, 90f, order = 2)).withNodeFallback()
        assertEquals(TextPathType.CURVE, out.type)
        assertNear("start", 0.0, 0.0, bezier(out, 0.0), 1e-4)
        assertNear("a third", 60.0, 0.0, bezier(out, 1.0 / 3), 1e-3)
        assertNear("two thirds", 90.0, 30.0, bezier(out, 2.0 / 3), 1e-3)
        assertNear("end", 90.0, 90.0, bezier(out, 1.0), 1e-4)
        assertEquals(2, out.nodes!!.order)
    }

    @Test
    fun aPathGivesTheCubicThroughItsThirdsOfArcLength() {
        // An open order-4 path of 4 points with clamped knots is the Bézier of those points.
        val pts = floatArrayOf(0f, 0f, 0f, 100f, 100f, 100f, 100f, 0f)
        val out = curve.copy(nodes = spline(*pts)).withNodeFallback()
        assertEquals(TextPathType.CURVE, out.type)
        val n = 20_000
        val xs = DoubleArray(n + 1)
        val ys = DoubleArray(n + 1)
        val len = DoubleArray(n + 1)
        for (i in 0..n) {
            val t = i.toDouble() / n
            val u = 1 - t
            xs[i] = u * u * u * pts[0] + 3 * u * u * t * pts[2] + 3 * u * t * t * pts[4] + t * t * t * pts[6]
            ys[i] = u * u * u * pts[1] + 3 * u * u * t * pts[3] + 3 * u * t * t * pts[5] + t * t * t * pts[7]
            if (i > 0) len[i] = len[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        fun atLength(d: Double): Pair<Double, Double> {
            val i = len.indexOfFirst { it >= d }
            return xs[i] to ys[i]
        }
        for ((t, f) in listOf(0.0 to 0.0, 1.0 / 3 to 1.0 / 3, 2.0 / 3 to 2.0 / 3, 1.0 to 1.0)) {
            val (ex, ey) = atLength(len[n] * f)
            assertNear("at $f of the arc length", ex, ey, bezier(out, t), 0.5)
        }
    }

    @Test
    fun aClosedGuideFallsBackToAnOpenCurveWhoseEndsMeet() {
        val out = curve.copy(nodes = spline(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f, order = 2, cyclic = true)).withNodeFallback()
        assertEquals(TextPathType.CURVE, out.type)
        assertEquals(out.x1, out.x2, 1e-3f)
        assertEquals(out.y1, out.y2, 1e-3f)
        assertTrue("the nodes stay closed", out.nodes!!.cyclic)
        // Once around the square: its points at a third and two thirds of the way are on its edges.
        fun onEdge(p: Pair<Double, Double>): Boolean {
            val (x, y) = p
            val inside = x in -1e-2..100.01 && y in -1e-2..100.01
            return inside && minOf(minOf(x, 100 - x), minOf(y, 100 - y)) < 1e-2
        }
        assertTrue("a third: ${bezier(out, 1.0 / 3)}", onEdge(bezier(out, 1.0 / 3)))
        assertTrue("two thirds: ${bezier(out, 2.0 / 3)}", onEdge(bezier(out, 2.0 / 3)))
        val (ax, ay) = bezier(out, 1.0 / 3)
        val (bx, by) = bezier(out, 2.0 / 3)
        assertTrue("a third and two thirds are apart", hypot(ax - bx, ay - by) > 50.0)
    }

    @Test
    fun unusableNodesAreDroppedAndTheFlatFieldsKept() {
        for ((what, nodes) in listOf(
            "one node" to spline(5f, 5f),
            "no node" to VSpline(emptyList()),
            "201 nodes" to VSpline((0..TextPathSpec.MAX_NODES).map { VSplinePoint(it.toFloat(), 0f) }, order = 2),
            "a NaN" to spline(0f, 0f, Float.NaN, 5f, 10f, 10f),
            "an infinity" to spline(0f, 0f, 5f, Float.POSITIVE_INFINITY),
            "a coordinate beyond MAX_COORD" to spline(0f, 0f, VSpline.MAX_COORD * 2, 5f, 10f, 10f),
        )) {
            val out = curve.copy(nodes = nodes).withNodeFallback()
            assertNull("$what: dropped", out.nodes)
            assertEquals("$what: the flat fields stay", curve, out)
        }
        val most = VSpline((0 until TextPathSpec.MAX_NODES).map { VSplinePoint(it.toFloat(), (it % 7).toFloat()) }, order = 2)
        assertEquals("200 nodes are kept", TextPathSpec.MAX_NODES, curve.copy(nodes = most).withNodeFallback().nodes!!.points.size)
    }

    @Test
    fun anOrderOtherThanTwoOrFourBecomesFourAndTheNodesAreSanitized() {
        for (order in listOf(3, 5, 6, 1, 9)) {
            assertEquals("order $order", 4, curve.copy(nodes = spline(0f, 0f, 30f, 40f, 60f, 0f, order = order)).withNodeFallback().nodes!!.order)
        }
        assertEquals(2, curve.copy(nodes = spline(0f, 0f, 30f, 40f, 60f, 0f, order = 2)).withNodeFallback().nodes!!.order)
        val raw = VSpline(listOf(VSplinePoint(0f, 0f, sharp = true), VSplinePoint(30f, 40f, weight = 50f), VSplinePoint(60f, 0f, width = Float.NaN, sharp = true)))
        val n = curve.copy(nodes = raw).withNodeFallback().nodes!!
        assertEquals("the open ends are not sharp", listOf(false, false), listOf(n.points.first().sharp, n.points.last().sharp))
        assertEquals("weights in range", VSpline.MAX_WEIGHT, n.points[1].weight)
        assertEquals("non-finite width is 1", 1f, n.points[2].width)
    }

    @Test
    fun aStraightTextKeepsNoneAndItsNodesWithTheFallbackInTheFlatFields() {
        // The guide switched off (F1a deviation b): NONE stays NONE, the nodes stay, the flat CURVE fields get the fallback.
        val off = TextPathSpec(nodes = spline(0f, 0f, 90f, 0f, 90f, 90f, order = 2))
        val out = off.withNodeFallback()
        assertEquals(TextPathType.NONE, out.type)
        assertNotNull(out.nodes)
        assertNear("a third", 60.0, 0.0, bezier(out, 1.0 / 3), 1e-3)
        val two = TextPathSpec(nodes = spline(1f, 2f, 3f, 4f)).withNodeFallback()
        assertEquals(TextPathType.NONE, two.type)
        assertEquals(listOf(1f, 2f, 3f, 4f), listOf(two.x1, two.y1, two.x2, two.y2))
    }

    @Test
    fun theFallbackIsIdempotent() {
        for (nodes in listOf(spline(10f, 20f, 110f, 70f), spline(0f, 0f, 90f, 0f, 90f, 90f, order = 2), spline(0f, 0f, 0f, 100f, 100f, 100f, 100f, 0f))) {
            val once = curve.copy(nodes = nodes).withNodeFallback()
            assertSame("a second run changes nothing", once, once.withNodeFallback())
        }
    }

    @Test
    fun theCodecWritesTheFallbackAndReadsTheNodesBack() {
        val nodes = spline(0f, 0f, 90f, 0f, 90f, 90f, order = 2)
        val item = TextItem("Along", TextSpec(), 0f, 0f, path = TextPathSpec(type = TextPathType.CURVE, nodes = nodes))
        val data = TextCodec.encode(item)
        val back = TextCodec.decode(data)!!
        assertEquals(nodes, back.path.nodes)
        assertEquals(TextPathType.CURVE, back.path.type)
        assertNear("the decoded fallback", 60.0, 0.0, bezier(back.path, 1.0 / 3), 1e-3)
        assertEquals("encode is stable", data, TextCodec.encode(back))
        // A text whose nodes are unusable loses them on the next sanitize, keeping its flat path.
        val bad = item.copy(path = curve.copy(nodes = spline(1f, 1f)))
        assertEquals(curve, TextCodec.decode(TextCodec.encode(bad))!!.path)
    }
}
