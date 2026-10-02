package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random

/**
 * v1.6 §3.3: the pure math of Bézier handle scaling ([CurveGeometry.scaledHandles]). Both sides
 * keep smooth points collinear (1e-5 rad) and sharp points' angles; In / Out change one length,
 * never a direction; automatic tangents made explicit at 100 % draw exactly the same curve; the
 * factor is held to 0.01..100.
 */
class HandleScaleMathTest {

    private val rnd = Random(4)

    private fun randomAnchors(n: Int, sharpEvery: Int = 0): List<CurveAnchor> = List(n) { i ->
        CurveAnchor(rnd.nextFloat() * 800f, rnd.nextFloat() * 600f, sharp = sharpEvery > 0 && i % sharpEvery == 1)
    }

    private fun angle(v: Vec2) = atan2(v.y.toDouble(), v.x.toDouble())

    private fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % (2 * Math.PI)
        if (d > Math.PI) d -= 2 * Math.PI
        if (d < -Math.PI) d += 2 * Math.PI
        return abs(d)
    }

    @Test
    fun bothSidesKeepSmoothPointsCollinearAndSharpAngles() {
        for (closed in listOf(false, true)) {
            val a = randomAnchors(9, sharpEvery = 3)
            val all = a.indices.toList().toIntArray()
            val k = 1.7f
            val out = CurveGeometry.scaledHandles(a, all, k, HandleSide.BOTH, closed, 0.2f)
            for (i in a.indices) {
                val (bi, bo) = CurveGeometry.handles(a, i, closed, 0.2f)
                val (si, so) = CurveGeometry.handles(out, i, closed, 0.2f)
                assertEquals(bi.length * k, si.length, 1e-3f * (1f + bi.length))
                assertEquals(bo.length * k, so.length, 1e-3f * (1f + bo.length))
                if (bi.length > 1e-3f) assertTrue("in direction kept", angleDiff(angle(bi), angle(si)) < 1e-5)
                if (bo.length > 1e-3f) assertTrue("out direction kept", angleDiff(angle(bo), angle(so)) < 1e-5)
                if (!a[i].sharp && si.length > 1e-3f && so.length > 1e-3f) {
                    assertTrue("smooth point $i stays collinear", angleDiff(angle(si), angle(so) + Math.PI) < 1e-5)
                }
                if (a[i].sharp && bi.length > 1e-3f && bo.length > 1e-3f) {
                    assertEquals("sharp point $i keeps its angle", angleDiff(angle(bi), angle(bo)), angleDiff(angle(si), angle(so)), 1e-5)
                }
            }
        }
    }

    @Test
    fun inOrOutAloneChangeOneLengthNeverADirection() {
        val a = randomAnchors(6)
        for (side in listOf(HandleSide.IN, HandleSide.OUT)) {
            val out = CurveGeometry.scaledHandles(a, intArrayOf(2), 0.5f, side, false, 0f)
            val (bi, bo) = CurveGeometry.handles(a, 2, false, 0f)
            val (si, so) = CurveGeometry.handles(out, 2, false, 0f)
            assertEquals(if (side == HandleSide.IN) bi.length * 0.5f else bi.length, si.length, 1e-3f)
            assertEquals(if (side == HandleSide.OUT) bo.length * 0.5f else bo.length, so.length, 1e-3f)
            assertTrue(angleDiff(angle(bi), angle(si)) < 1e-5)
            assertTrue(angleDiff(angle(bo), angle(so)) < 1e-5)
            // The other points are untouched.
            for (i in a.indices) if (i != 2) assertSame(a[i], out[i])
        }
    }

    @Test
    fun automaticTangentsMadeExplicitDrawTheSameCurve() {
        for (closed in listOf(false, true)) for (tension in listOf(0f, 0.4f)) {
            val a = randomAnchors(7, sharpEvery = 4)
            val out = CurveGeometry.scaledHandles(a, a.indices.toList().toIntArray(), 1f, HandleSide.BOTH, closed, tension)
            assertTrue("now explicit", out.all { it.handleIn != null && it.handleOut != null })
            val before = CurveGeometry.toPath(a, closed, tension, false).flatten(0.05f).first().points
            val after = CurveGeometry.toPath(out, closed, tension, false).flatten(0.05f).first().points
            assertEquals(before.size, after.size)
            for (i in before.indices) {
                assertEquals(before[i].x, after[i].x, 1e-3f)
                assertEquals(before[i].y, after[i].y, 1e-3f)
            }
        }
    }

    @Test
    fun theFactorIsHeldToItsRange() {
        val a = randomAnchors(4)
        val (bi, bo) = CurveGeometry.handles(a, 1, false, 0f)
        val tiny = CurveGeometry.handles(CurveGeometry.scaledHandles(a, intArrayOf(1), 1e-6f, HandleSide.BOTH, false, 0f), 1, false, 0f)
        assertEquals(bo.length * CurveGeometry.MIN_HANDLE_SCALE, tiny.second.length, 1e-3f)
        val huge = CurveGeometry.handles(CurveGeometry.scaledHandles(a, intArrayOf(1), 1e6f, HandleSide.BOTH, false, 0f), 1, false, 0f)
        assertEquals(bi.length * CurveGeometry.MAX_HANDLE_SCALE, huge.first.length, 1e-2f * bi.length)
        assertEquals(1f, CurveGeometry.clampHandleScale(Float.NaN), 0f)
        // Out-of-range indices are ignored; nothing to scale returns the same list.
        assertSame(a, CurveGeometry.scaledHandles(a, IntArray(0), 2f, HandleSide.BOTH, false, 0f))
        assertEquals(a, CurveGeometry.scaledHandles(a, intArrayOf(-1, 9), 2f, HandleSide.BOTH, false, 0f))
    }
}
