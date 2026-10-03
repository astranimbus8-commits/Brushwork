package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * v1.6 §3.3 (G, JVM): scaling the tangent handles of a shape's own points. Both handles keep their
 * directions (a smooth point stays collinear within 1e-5 rad, a sharp one keeps its angle); In
 * or Out alone changes one length, never a direction; automatic tangents become explicit with
 * the same outline at 100 %; the factor is held to 1 %..10000 %; corners without handles and
 * indices out of range are left alone.
 */
class ShapeHandleScaleMathTest {

    private fun angle(v: Vec2): Double = atan2(v.y.toDouble(), v.x.toDouble())

    /** Angle between [a] and the opposite of [b] (0 when they are collinear and opposite). */
    private fun bend(a: Vec2, b: Vec2): Double {
        var d = abs(angle(a) - angle(-b))
        if (d > Math.PI) d = 2 * Math.PI - d
        return d
    }

    private fun assertVec(expected: Vec2, actual: Vec2, eps: Float = 1e-4f) {
        assertEquals("x of $actual", expected.x, actual.x, eps)
        assertEquals("y of $actual", expected.y, actual.y, eps)
    }

    /** A closed outline: a smooth point with explicit handles, an automatic smooth one, a sharp corner, a sharp point with explicit handles. */
    private val anchors = listOf(
        ShapeAnchor(Vec2(0f, 0f), smooth = true, handleIn = Vec2(-10f, 4f), handleOut = Vec2(15f, -6f)),
        ShapeAnchor(Vec2(100f, 0f), smooth = true),
        ShapeAnchor(Vec2(100f, 100f)),
        ShapeAnchor(Vec2(0f, 100f), handleIn = Vec2(0f, 20f), handleOut = Vec2(-12f, -9f)),
    )

    @Test
    fun bothHandlesKeepTheirDirections() {
        val all = IntArray(anchors.size) { it }
        val s = ShapePoints.scaledHandles(anchors, all, 1.5f, ShapeHandleSide.BOTH, closed = true)
        // Explicit smooth: × 1.5, still collinear.
        assertVec(Vec2(-15f, 6f), s[0].handleIn!!)
        assertVec(Vec2(22.5f, -9f), s[0].handleOut!!)
        assertTrue(bend(s[0].handleIn!!, s[0].handleOut!!) < 1e-5)
        // Automatic smooth: made explicit (its Catmull-Rom tangent), then × 1.5; collinear.
        val (autoIn, autoOut) = ShapePoints.handles(anchors, 1, true)
        assertVec(autoIn * 1.5f, s[1].handleIn!!)
        assertVec(autoOut * 1.5f, s[1].handleOut!!)
        assertTrue(bend(s[1].handleIn!!, s[1].handleOut!!) < 1e-5)
        // A sharp corner between straight edges has no handles: left alone.
        assertSame(anchors[2], s[2])
        // A sharp point with handles keeps its angle.
        assertEquals(angle(anchors[3].handleIn!!), angle(s[3].handleIn!!), 1e-6)
        assertEquals(angle(anchors[3].handleOut!!), angle(s[3].handleOut!!), 1e-6)
        assertEquals(30f, s[3].handleIn!!.length, 1e-4f)
        // The positions never move.
        for (i in anchors.indices) assertEquals(anchors[i].pos, s[i].pos)
    }

    @Test
    fun automaticTangentsBecomeExplicitWithTheSameOutline() {
        val s = ShapePoints.scaledHandles(anchors, intArrayOf(1), 1f, ShapeHandleSide.BOTH, closed = true)
        assertTrue(s[1].hasExplicitHandles)
        for (seg in 0 until ShapePoints.segmentCount(anchors.size, true)) {
            val a = ShapePoints.segment(anchors, seg, true)
            val b = ShapePoints.segment(s, seg, true)
            for (k in 0..3) assertVec(a[k], b[k], 1e-4f)
        }
    }

    @Test
    fun inOrOutAloneChangesOneLength() {
        val s = ShapePoints.scaledHandles(anchors, intArrayOf(0), 2f, ShapeHandleSide.OUT, closed = true)
        assertVec(Vec2(-10f, 4f), s[0].handleIn!!)
        assertVec(Vec2(30f, -12f), s[0].handleOut!!)
        assertEquals(angle(anchors[0].handleOut!!), angle(s[0].handleOut!!), 1e-6)
        val t = ShapePoints.scaledHandles(anchors, intArrayOf(0), 0.5f, ShapeHandleSide.IN, closed = true)
        assertVec(Vec2(-5f, 2f), t[0].handleIn!!)
        assertVec(Vec2(15f, -6f), t[0].handleOut!!)
        // Only the listed points change.
        for (i in 1 until anchors.size) assertSame(anchors[i], t[i])
    }

    @Test
    fun theFactorIsHeldToItsRange() {
        val tiny = ShapePoints.scaledHandles(anchors, intArrayOf(0), 0f, ShapeHandleSide.BOTH, closed = true)
        assertVec(Vec2(-0.1f, 0.04f), tiny[0].handleIn!!, 1e-6f)
        val huge = ShapePoints.scaledHandles(anchors, intArrayOf(0), 1e6f, ShapeHandleSide.BOTH, closed = true)
        assertVec(Vec2(-1000f, 400f), huge[0].handleIn!!, 1e-2f)
        val nan = ShapePoints.scaledHandles(anchors, intArrayOf(0), Float.NaN, ShapeHandleSide.BOTH, closed = true)
        assertVec(Vec2(-10f, 4f), nan[0].handleIn!!)
        // Indices out of range are ignored.
        val none = ShapePoints.scaledHandles(anchors, intArrayOf(-1, 7), 2f, ShapeHandleSide.BOTH, closed = true)
        assertEquals(anchors, none)
    }
}
