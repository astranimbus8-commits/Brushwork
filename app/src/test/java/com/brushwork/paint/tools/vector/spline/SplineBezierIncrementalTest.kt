package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.6 §3.2c / I9: [SplineBezier.Incremental] re-converts only the spans whose control points an
 * edit changed and gives exactly what a fresh [SplineBezier.toSubpath] gives — every float of every
 * anchor bit for bit — over random edits of every kind (moves, drags, weights, widths, inserts,
 * deletes, order, Cyclic, Endpoint, values the sanitizer clamps or drops, whole new splines).
 */
class SplineBezierIncrementalTest {

    private fun bits(v: Float?): Long = if (v == null) Long.MIN_VALUE else java.lang.Float.floatToRawIntBits(v).toLong()

    /** The same anchors, float for float (raw bits: −0 and 0 differ, as do NaN payloads). */
    private fun assertBitEqual(what: String, fresh: VSubpath, inc: VSubpath) {
        assertEquals("$what: closed", fresh.closed, inc.closed)
        assertEquals("$what: anchors", fresh.anchors.size, inc.anchors.size)
        for (i in fresh.anchors.indices) {
            val a: VAnchor = fresh.anchors[i]
            val b: VAnchor = inc.anchors[i]
            assertEquals("$what: anchor $i sharp", a.sharp, b.sharp)
            val fa = listOf(a.x, a.y, a.inX, a.inY, a.outX, a.outY, a.width)
            val fb = listOf(b.x, b.y, b.inX, b.inY, b.outX, b.outY, b.width)
            for (k in fa.indices) assertEquals("$what: anchor $i field $k (${fa[k]} vs ${fb[k]})", bits(fa[k]), bits(fb[k]))
        }
        assertEquals("$what: data", fresh, inc)
    }

    private fun randomSpline(rnd: Random, n: Int = rnd.nextInt(0, 30)): VSpline = VSpline(
        SplineTestSupport.randomPoints(rnd, n, weights = rnd.nextBoolean(), widths = rnd.nextBoolean()),
        order = rnd.nextInt(VSpline.MIN_ORDER, VSpline.MAX_ORDER + 1),
        endpoint = rnd.nextBoolean(),
        cyclic = rnd.nextBoolean(),
    )

    private fun randomPoint(rnd: Random) = VSplinePoint(
        rnd.nextDouble(20.0, 980.0).toFloat(), rnd.nextDouble(20.0, 980.0).toFloat(),
        weight = if (rnd.nextInt(3) == 0) rnd.nextDouble(0.1, 10.0).toFloat() else 1f,
        width = rnd.nextDouble(0.0, 3.0).toFloat(),
    )

    /** One random edit of [s], of the kinds the Path tool makes (and a few damaged values). */
    private fun edit(rnd: Random, s: VSpline): VSpline {
        val pts = s.points
        val n = pts.size
        return when (rnd.nextInt(14)) {
            0, 1, 2 -> if (n == 0) s.copy(points = listOf(randomPoint(rnd))) else {
                // A drag step: one point moves a little.
                val i = rnd.nextInt(n)
                val p = pts[i]
                s.copy(points = pts.toMutableList().also { it[i] = p.copy(x = p.x + rnd.nextDouble(-8.0, 8.0).toFloat(), y = p.y + rnd.nextDouble(-8.0, 8.0).toFloat()) })
            }
            3 -> if (n == 0) s else {
                val i = rnd.nextInt(n)
                s.copy(points = pts.toMutableList().also { it[i] = it[i].copy(weight = rnd.nextDouble(0.1, 10.0).toFloat()) })
            }
            4 -> if (n == 0) s else {
                val i = rnd.nextInt(n)
                s.copy(points = pts.toMutableList().also { it[i] = it[i].copy(width = rnd.nextDouble(0.0, 3.0).toFloat()) })
            }
            5 -> s.copy(points = pts.toMutableList().also { it.add(rnd.nextInt(n + 1), randomPoint(rnd)) })
            6 -> if (n == 0) s else s.copy(points = pts.toMutableList().also { it.removeAt(rnd.nextInt(n)) })
            7 -> s.copy(order = rnd.nextInt(VSpline.MIN_ORDER, VSpline.MAX_ORDER + 1))
            8 -> s.copy(cyclic = !s.cyclic)
            9 -> s.copy(endpoint = !s.endpoint)
            10 -> if (n < 2) s else {
                // Several points at once (a translate of a few, or two far apart).
                val m = pts.toMutableList()
                repeat(rnd.nextInt(2, 4)) { val i = rnd.nextInt(n); m[i] = m[i].copy(x = m[i].x + 3f, y = m[i].y - 2f) }
                s.copy(points = m)
            }
            11 -> if (n == 0) s else {
                // Out of range: the sanitizer clamps the weight / width (the same point after).
                val i = rnd.nextInt(n)
                s.copy(points = pts.toMutableList().also { it[i] = it[i].copy(weight = 50f, width = Float.NaN) })
            }
            12 -> if (n == 0) s else {
                // A damaged point: the sanitizer drops it (a change of structure).
                val i = rnd.nextInt(n)
                s.copy(points = pts.toMutableList().also { it[i] = it[i].copy(x = Float.NaN) })
            }
            else -> if (rnd.nextInt(8) == 0) randomSpline(rnd) else s
        }
    }

    @Test
    fun incrementalIsFreshBitForBitOverRandomEdits() {
        for (seed in 1..60) {
            val rnd = Random(seed * 7919L)
            val inc = SplineBezier.Incremental()
            var s = randomSpline(rnd)
            for (step in 0 until 120) {
                val what = "seed $seed step $step (n ${s.points.size}, order ${s.order}, cyclic ${s.cyclic}, endpoint ${s.endpoint})"
                assertBitEqual(what, SplineBezier.toSubpath(s), inc.toSubpath(s))
                s = edit(rnd, s).let { if (it.points.size > 60) it.copy(points = it.points.take(40)) else it }
            }
        }
    }

    @Test
    fun aMovedPointReconvertsOnlyTheSpansItInfluences() {
        val rnd = Random(42)
        for (order in 3..6) for (cyclic in listOf(false, true)) for (endpoint in listOf(false, true)) {
            var s = VSpline(SplineTestSupport.randomPoints(rnd, 40, weights = true, widths = true), order = order, endpoint = endpoint, cyclic = cyclic)
            val inc = SplineBezier.Incremental()
            inc.toSubpath(s)
            val spans = NurbsGeometry.basis(s).spans.size
            assertEquals("order $order cyclic $cyclic endpoint $endpoint: the first conversion converts it all", spans, inc.lastConverted)
            // Unchanged (a redraw, a tap): nothing to convert.
            assertBitEqual("unchanged", SplineBezier.toSubpath(s), inc.toSubpath(s))
            assertEquals(0, inc.lastConverted)
            // A drag of each point in turn (the ends of a cyclic one included): at most p + 1 spans each.
            for (i in listOf(0, 1, 2, 19, 37, 38, 39)) for (move in 1..5) {
                val p = s.points[i]
                s = s.copy(points = s.points.toMutableList().also { it[i] = p.copy(x = p.x + 1.5f * move, y = p.y - 0.5f) })
                assertBitEqual("order $order cyclic $cyclic endpoint $endpoint: point $i move $move", SplineBezier.toSubpath(s), inc.toSubpath(s))
                assertTrue("order $order: point $i converted ${inc.lastConverted} of $spans spans", inc.lastConverted in 1..order)
            }
            // A weight or a width: the same spans.
            s = s.copy(points = s.points.toMutableList().also { it[20] = it[20].copy(weight = 3f) })
            assertBitEqual("weight", SplineBezier.toSubpath(s), inc.toSubpath(s))
            assertTrue(inc.lastConverted in 1..order)
            s = s.copy(points = s.points.toMutableList().also { it[21] = it[21].copy(width = 0.25f) })
            assertBitEqual("width", SplineBezier.toSubpath(s), inc.toSubpath(s))
            assertTrue(inc.lastConverted in 1..order)
            // A change of structure converts it all.
            s = s.copy(points = s.points + VSplinePoint(500f, 500f))
            assertBitEqual("a point added", SplineBezier.toSubpath(s), inc.toSubpath(s))
            assertEquals(NurbsGeometry.basis(s).spans.size, inc.lastConverted)
            s = s.copy(cyclic = !s.cyclic)
            assertBitEqual("Cyclic", SplineBezier.toSubpath(s), inc.toSubpath(s))
            assertEquals(NurbsGeometry.basis(s).spans.size, inc.lastConverted)
        }
    }

    @Test
    fun trivialSplinesAndTheirWayBack() {
        val inc = SplineBezier.Incremental()
        val two = VSpline(listOf(VSplinePoint(10f, 10f), VSplinePoint(90f, 40f)), order = 4)
        val cases = listOf(
            VSpline(emptyList()),
            VSpline(listOf(VSplinePoint(5f, 5f, width = 2f))),
            two,
            two.copy(order = 2, points = two.points + VSplinePoint(50f, 80f), cyclic = true),
            two.copy(points = two.points + VSplinePoint(50f, 80f)),
            VSpline(emptyList()),
            two.copy(points = two.points + VSplinePoint(50f, 80f) + VSplinePoint(10f, 90f), cyclic = true),
        )
        for ((i, s) in cases.withIndex()) assertBitEqual("case $i", SplineBezier.toSubpath(s), inc.toSubpath(s))
        // After a trivial one the cache is gone: the next converts every span.
        inc.toSubpath(VSpline(emptyList()))
        val s = cases.last()
        inc.toSubpath(s)
        assertEquals(NurbsGeometry.basis(s).spans.size, inc.lastConverted)
    }
}
