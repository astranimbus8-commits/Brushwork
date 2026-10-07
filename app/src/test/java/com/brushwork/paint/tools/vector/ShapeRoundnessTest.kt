package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7 item 2 (design §3.2): each Shape point's own roundness ([ShapeAnchor.radius]) in the outline, and the field's math. */
class ShapeRoundnessTest {

    /** A [s] px square centered on the origin, clockwise from the top-left, point 0 with roundness [r0]. */
    private fun square(s: Float, r0: Float? = null, r2: Float? = null): List<ShapeAnchor> = listOf(
        ShapeAnchor(Vec2(-s / 2f, -s / 2f), radius = r0),
        ShapeAnchor(Vec2(s / 2f, -s / 2f)),
        ShapeAnchor(Vec2(s / 2f, s / 2f), radius = r2),
        ShapeAnchor(Vec2(-s / 2f, s / 2f)),
    )

    private fun outlinePoints(a: List<ShapeAnchor>, corner: CornerStyle = CornerStyle.SHARP, radius: Float = 30f): List<Vec2> =
        ShapePoints.outline(a, true, corner, radius).flatten(0.01f).single().points

    @Test
    fun oneCornerOfASquareAt20IsAnArcOfRadius20AndTheOthersStaySharp() {
        val a = square(100f, r0 = 20f)
        val pts = outlinePoints(a)
        // The arc is tangent to both sides 20 px from the corner: its centre is (-30, -30).
        val centre = Vec2(-30f, -30f)
        val arc = pts.filter { it.x < -30f - 1e-3f && it.y < -30f - 1e-3f }
        assertTrue("the corner is rounded: ${arc.size} points", arc.size >= 5)
        for (p in arc) assertEquals("on the circle of radius 20: $p", 20f, p.distanceTo(centre), 0.5f)
        assertTrue("the cut corner is gone", pts.none { it.distanceTo(a[0].pos) < 5f })
        for (v in a.drop(1)) assertTrue("${v.pos} stays a sharp corner", pts.any { it.distanceTo(v.pos) < 1e-3f })
    }

    @Test
    fun aHugeRoundnessIsClampedToHalfTheShorterSide() {
        val pts = outlinePoints(square(100f, r0 = 1000f))
        // Cut back by 50 (half of a 100 px side): the arc of radius 50 around the centre.
        val arc = pts.filter { it.x < -1e-3f && it.y < -1e-3f }
        assertTrue(arc.size >= 5)
        for (p in arc) assertEquals("on the circle of radius 50: $p", 50f, p.distanceTo(Vec2.ZERO), 0.5f)
        assertTrue(pts.any { it.distanceTo(Vec2(-50f, 0f)) < 1e-3f })
        assertTrue(pts.any { it.distanceTo(Vec2(0f, -50f)) < 1e-3f })
    }

    @Test
    fun theShapesStyleAppliesAtThePointsOwnSize() {
        // A bevelled square (radius 10) whose point 0 has its own 30: a bevel 30 px from that corner.
        val pts = outlinePoints(square(100f, r0 = 30f), CornerStyle.BEVEL, 10f)
        assertTrue(pts.any { it.distanceTo(Vec2(-20f, -50f)) < 1e-3f })
        assertTrue(pts.any { it.distanceTo(Vec2(-50f, -20f)) < 1e-3f })
        assertTrue("the other corners keep the shape's 10", pts.any { it.distanceTo(Vec2(40f, -50f)) < 1e-3f })
        // An own roundness of 0 on a rounded shape leaves that corner sharp.
        val zero = outlinePoints(square(100f, r0 = 0f), CornerStyle.ROUND, 20f)
        assertTrue(zero.any { it.distanceTo(Vec2(-50f, -50f)) < 1e-3f })
        assertTrue(zero.none { it.distanceTo(Vec2(50f, -50f)) < 5f })
    }

    @Test
    fun onlyCornersBetweenStraightSidesAreRounded() {
        // Point 1 is smooth (curved sides): its roundness is ignored, point 0's is drawn.
        val a = square(100f).toMutableList()
        a[1] = a[1].copy(smooth = true, radius = 25f)
        a[3] = a[3].copy(radius = 15f)
        val roundable = ShapePoints.roundable(a, closed = true)
        assertArrayEquals(booleanArrayOf(false, false, false, true), roundable)
        assertFalse("open outlines have no corners to round", ShapePoints.roundable(a, closed = false).any { it })
        val withOwn = ShapePoints.outline(a, true, CornerStyle.SHARP, 0f).flatten(0.01f).single().points
        val without = ShapePoints.outline(a.map { if (it === a[3]) it.copy(radius = null) else it }, true, CornerStyle.SHARP, 0f)
            .flatten(0.01f).single().points
        assertTrue("point 3 is rounded", withOwn.none { it.distanceTo(Vec2(-50f, 50f)) < 3f })
        assertTrue("without it, point 3 is sharp", without.any { it.distanceTo(Vec2(-50f, 50f)) < 1e-3f })
        // A point in the middle of a straight side is no corner.
        val mid = listOf(a[0], ShapeAnchor(Vec2(0f, -50f)), square(100f)[1], square(100f)[2], square(100f)[3])
        assertFalse(ShapePoints.roundable(mid, true)[1])
    }

    @Test
    fun aTypedTimesTwoAppliesToEachSelectedCorner() {
        val a = square(100f, r0 = 10f, r2 = 20f)
        val targets = ShapeRoundness.targets(a, true, intArrayOf(2, 0))
        assertArrayEquals(intArrayOf(0, 2), targets)
        val out = ShapeRoundness.typed(a, targets, CornerStyle.SHARP, 30f, "*2")
        assertNotNull(out)
        assertEquals(20f, out!![0].radius!!, 1e-4f)
        assertEquals(40f, out[2].radius!!, 1e-4f)
        assertNull("the others keep the shape's", out[1].radius)
        // An absolute value sets all; invalid text changes nothing.
        val all = ShapeRoundness.typed(a, targets, CornerStyle.SHARP, 30f, "12")!!
        assertEquals(12f, all[0].radius!!, 0f)
        assertEquals(12f, all[2].radius!!, 0f)
        assertNull(ShapeRoundness.typed(a, targets, CornerStyle.SHARP, 30f, "abc"))
    }

    @Test
    fun valuesShiftsAndReset() {
        val a = square(100f, r0 = 10f)
        val t = intArrayOf(0, 1)
        // Point 1 follows the shape: 0 with sharp corners, the corner radius otherwise.
        assertArrayEquals(floatArrayOf(10f, 0f), ShapeRoundness.values(a, t, CornerStyle.SHARP, 30f), 0f)
        assertArrayEquals(floatArrayOf(10f, 30f), ShapeRoundness.values(a, t, CornerStyle.ROUND, 30f), 0f)
        val shifted = ShapeRoundness.shifted(a, t, CornerStyle.SHARP, 30f, 5f)
        assertEquals(15f, shifted[0].radius!!, 0f)
        assertEquals(5f, shifted[1].radius!!, 0f)
        val clamped = ShapeRoundness.shifted(a, t, CornerStyle.SHARP, 30f, -50f)
        assertEquals(0f, clamped[0].radius!!, 0f)
        assertEquals(ShapeRoundness.MAX, ShapeRoundness.shifted(a, t, CornerStyle.SHARP, 30f, 9_999f)[0].radius!!, 0f)
        val reset = ShapeRoundness.reset(shifted, t)
        assertNull(reset[0].radius)
        assertNull(reset[1].radius)
    }

    @Test
    fun aShapeObjectDrawsItsPointsRoundness() {
        val pts = ShapePoints.normalize(ShapeBox(100f, 100f, 100f, 100f), square(100f, r0 = 20f).map { it.copy(pos = it.pos + Vec2(100f, 100f)) })
        val o = ShapeObject(type = ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 100f, h = 100f, points = pts)
        val outline = ShapeOutlines.outline(o).flatten(0.01f).single().points
        val centre = Vec2(70f, 70f)
        val arc = outline.filter { it.x < 70f - 1e-3f && it.y < 70f - 1e-3f }
        assertTrue(arc.isNotEmpty())
        for (p in arc) assertEquals(20f, p.distanceTo(centre), 0.5f)
    }
}
