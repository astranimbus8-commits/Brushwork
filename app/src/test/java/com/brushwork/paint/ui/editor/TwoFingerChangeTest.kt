package com.brushwork.paint.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two-finger measurement for tool gestures and the "may still be a tap" check (pure JVM). */
class TwoFingerChangeTest {

    private fun change(start: FloatArray, now: FloatArray, minSpread: Float = 1e-3f) = TwoFingerChange.between(start, now, minSpread)

    @Test
    fun spreadingTheFingersScalesAroundTheirMidpoint() {
        val c = change(floatArrayOf(90f, 100f, 110f, 100f), floatArrayOf(80f, 100f, 120f, 100f))
        assertEquals(2f, c.scale, 1e-5f)
        assertEquals(0f, c.rotationDeg, 1e-4f)
        assertEquals(0f, c.translation.x, 1e-5f)
        assertEquals(0f, c.translation.y, 1e-5f)
    }

    @Test
    fun movingBothFingersTranslatesTheMidpoint() {
        val c = change(floatArrayOf(0f, 0f, 10f, 0f), floatArrayOf(5f, 7f, 15f, 7f))
        assertEquals(1f, c.scale, 1e-5f)
        assertEquals(5f, c.translation.x, 1e-5f)
        assertEquals(7f, c.translation.y, 1e-5f)
    }

    @Test
    fun clockwiseOnAYDownScreenIsPositive() {
        // a->b points right, then down: a quarter turn clockwise as seen on screen (y down).
        val c = change(floatArrayOf(-10f, 0f, 10f, 0f), floatArrayOf(0f, -10f, 0f, 10f))
        assertEquals(90f, c.rotationDeg, 1e-3f)
        assertEquals(1f, c.scale, 1e-5f)
    }

    @Test
    fun mirroredViewTurnsTheOtherWayInDocumentSpace() {
        // The same clockwise screen motion on a view mirrored around x = 0: the canvas maps the
        // fingers to document coordinates first, which flips x.
        fun mirror(p: FloatArray) = floatArrayOf(-p[0], p[1], -p[2], p[3])
        val c = change(mirror(floatArrayOf(-10f, 0f, 10f, 0f)), mirror(floatArrayOf(0f, -10f, 0f, 10f)))
        assertEquals(-90f, c.rotationDeg, 1e-3f)
    }

    @Test
    fun rotationIsNormalizedAcrossTheSeam() {
        // Start pointing almost left (179 deg), end at -179 deg: a 2 degree turn, not 358.
        val a = Math.toRadians(179.0)
        val b = Math.toRadians(-179.0)
        val c = change(
            floatArrayOf(0f, 0f, (100 * Math.cos(a)).toFloat(), (100 * Math.sin(a)).toFloat()),
            floatArrayOf(0f, 0f, (100 * Math.cos(b)).toFloat(), (100 * Math.sin(b)).toFloat()),
        )
        assertEquals(2f, c.rotationDeg, 1e-3f)
    }

    @Test
    fun fingersTooCloseTogetherGiveNoScaleOrAngle() {
        val c = change(floatArrayOf(0f, 0f, 0.5f, 0f), floatArrayOf(10f, 0f, 40f, 30f), minSpread = 1f)
        assertEquals(1f, c.scale, 0f)
        assertEquals(0f, c.rotationDeg, 0f)
        // The midpoint still moves.
        assertEquals(24.75f, c.translation.x, 1e-4f)
    }

    @Test
    fun nonFinitePositionsAreNeutral() {
        val c = change(floatArrayOf(0f, 0f, 10f, 0f), floatArrayOf(Float.NaN, 0f, 10f, 0f))
        assertEquals(TwoFingerChange.NONE, c)
    }

    // ------------------------------------------------------------------ tap still possible

    private fun classifier() = TouchGestureClassifier(tapSlopPx = 24f, longPressSlopPx = 16f)

    @Test
    fun twoFingersStillQuickAndStillAreATapCandidate() {
        val c = classifier()
        c.down(0, 100f, 100f, 1000)
        c.down(1, 300f, 100f, 1030)
        c.move(1, 305f, 102f)
        assertTrue(c.tapStillPossible(1200))
        c.up(0, 1200)
        assertTrue("decided only at the last up", c.tapStillPossible(1250))
    }

    @Test
    fun movementTimeOrOneFingerRuleOutATap() {
        val moved = classifier()
        moved.down(0, 100f, 100f, 0)
        moved.down(1, 300f, 100f, 10)
        moved.move(1, 300f, 130f)
        assertFalse(moved.tapStillPossible(50))

        val slow = classifier()
        slow.down(0, 100f, 100f, 0)
        slow.down(1, 300f, 100f, 10)
        assertFalse(slow.tapStillPossible(301))

        val single = classifier()
        single.down(0, 100f, 100f, 0)
        assertFalse(single.tapStillPossible(10))

        val invalidated = classifier()
        invalidated.down(0, 100f, 100f, 0)
        invalidated.down(1, 300f, 100f, 10)
        invalidated.invalidate()
        assertFalse(invalidated.tapStillPossible(20))
    }
}
