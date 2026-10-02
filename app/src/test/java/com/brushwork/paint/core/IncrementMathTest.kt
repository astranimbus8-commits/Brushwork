package com.brushwork.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v1.6 foundation (§4.9): the pure snapping math of the increments (JVM). */
class IncrementMathTest {

    private val badSteps = floatArrayOf(0f, -1f, -0.001f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

    @Test
    fun noStepMeansNoChange() {
        for (s in badSteps) {
            assertEquals(13.37f, IncrementMath.snap(13.37f, s), 0f)
            assertEquals(-4.2f, IncrementMath.snapDelta(-4.2f, s), 0f)
            assertEquals(1.234f, IncrementMath.snapFactor(1.234f, s), 0f)
            assertEquals(113f, IncrementMath.snapPercentOfOriginal(113f, s), 0f)
            assertEquals(37f, IncrementMath.snapAngle(37f, s), 0f)
            // A slider value is still kept in its range.
            assertEquals(37.0, IncrementMath.snapInRange(37.0, s.toDouble(), 0.0, 100.0), 0.0)
            assertEquals(100.0, IncrementMath.snapInRange(140.0, s.toDouble(), 0.0, 100.0), 0.0)
        }
    }

    @Test
    fun nonFiniteInputsPassThrough() {
        assertTrue(IncrementMath.snap(Float.NaN, 10f).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, IncrementMath.snapDelta(Float.POSITIVE_INFINITY, 10f), 0f)
        assertTrue(IncrementMath.snapFactor(Float.NaN, 10f).isNaN())
        assertTrue(IncrementMath.snapAngle(Float.NaN, 15f).isNaN())
        assertTrue(IncrementMath.snap(5f, 10f, Float.NaN) == 5f)
    }

    @Test
    fun snapsToNearestMultiple() {
        assertEquals(30f, IncrementMath.snap(27f, 10f), 0f)
        assertEquals(20f, IncrementMath.snap(24.9f, 10f), 0f)
        assertEquals(-30f, IncrementMath.snap(-27f, 10f), 0f)
        assertEquals(0f, IncrementMath.snap(0f, 10f), 0f)
        // Halves go up, the same on both sides of 0.
        assertEquals(30f, IncrementMath.snap(25f, 10f), 0f)
        assertEquals(-20f, IncrementMath.snap(-25f, 10f), 0f)
        // From an origin.
        assertEquals(13f, IncrementMath.snap(14f, 10f, origin = 3f), 0f)
        assertEquals(0.25f, IncrementMath.snap(0.3f, 0.25f), 1e-6f)
        // Deltas: a move of +27 px with a 10 px step is +30 px; 0 stays 0.
        assertEquals(30f, IncrementMath.snapDelta(27f, 10f), 0f)
        assertEquals(0f, IncrementMath.snapDelta(4f, 10f), 0f)
    }

    @Test
    fun scaleFactorsAreRelativeAndNeverBelowOneStep() {
        assertEquals(1.1f, IncrementMath.snapFactor(1.13f, 10f), 1e-5f)
        assertEquals(1.2f, IncrementMath.snapFactor(1.19f, 10f), 1e-5f)
        assertEquals(0.9f, IncrementMath.snapFactor(0.93f, 10f), 1e-5f)
        assertEquals(1f, IncrementMath.snapFactor(1.04f, 10f), 1e-5f)
        // A pinch to nothing stops at one step.
        assertEquals(0.1f, IncrementMath.snapFactor(0.01f, 10f), 1e-5f)
        assertEquals(0.1f, IncrementMath.snapFactor(-3f, 10f), 1e-5f)
        // Percent of the original: 100, 110, 120 …, 90, 80 …; never below one step.
        assertEquals(110f, IncrementMath.snapPercentOfOriginal(113f, 10f), 1e-4f)
        assertEquals(80f, IncrementMath.snapPercentOfOriginal(78f, 10f), 1e-4f)
        assertEquals(10f, IncrementMath.snapPercentOfOriginal(2f, 10f), 1e-4f)
    }

    @Test
    fun anglesWrapIntoTheHalfOpenRange() {
        assertEquals(45f, IncrementMath.snapAngle(40f, 15f), 0f)
        assertEquals(-45f, IncrementMath.snapAngle(-50f, 15f), 0f)
        assertEquals(180f, IncrementMath.snapAngle(179f, 15f), 0f)
        assertEquals(180f, IncrementMath.snapAngle(-179f, 15f), 0f)
        assertEquals(15f, IncrementMath.snapAngle(375f, 15f), 0f)
        assertEquals(-90f, IncrementMath.snapAngle(268f, 15f), 0f)
        val rnd = Random(7)
        repeat(2000) {
            val deg = rnd.nextFloat() * 4000f - 2000f
            val step = listOf(1f, 5f, 15f, 22.5f, 45f, 90f)[rnd.nextInt(6)]
            val r = IncrementMath.snapAngle(deg, step)
            assertTrue("$deg / $step -> $r", r > -180f && r <= 180f)
        }
    }

    @Test
    fun rangeEndsStayReachable() {
        // Range 0..100 with a step of 30: multiples 0, 30, 60, 90, and the end 100.
        assertEquals(60.0, IncrementMath.snapInRange(58.0, 30.0, 0.0, 100.0), 0.0)
        assertEquals(90.0, IncrementMath.snapInRange(94.0, 30.0, 0.0, 100.0), 0.0)
        assertEquals(100.0, IncrementMath.snapInRange(97.0, 30.0, 0.0, 100.0), 0.0)
        assertEquals(100.0, IncrementMath.snapInRange(250.0, 30.0, 0.0, 100.0), 0.0)
        assertEquals(0.0, IncrementMath.snapInRange(-3.0, 30.0, 0.0, 100.0), 0.0)
        // An odd range: 0.5..1000 with a step of 1 keeps the minimum reachable.
        assertEquals(0.5, IncrementMath.snapInRange(0.6, 1.0, 0.5, 1000.0), 0.0)
        assertEquals(3.0, IncrementMath.snapInRange(2.8, 1.0, 0.5, 1000.0), 0.0)
        // A step larger than the range: the nearer end.
        assertEquals(10.0, IncrementMath.snapInRange(8.0, 50.0, 1.0, 10.0), 0.0)
        assertEquals(1.0, IncrementMath.snapInRange(2.0, 50.0, 1.0, 10.0), 0.0)
        val rnd = Random(11)
        repeat(2000) {
            val min = rnd.nextDouble(-500.0, 500.0)
            val max = min + rnd.nextDouble(0.0, 1000.0)
            val step = rnd.nextDouble(0.01, 200.0)
            val v = rnd.nextDouble(-2000.0, 2000.0)
            val r = IncrementMath.snapInRange(v, step, min, max)
            assertTrue("$v in $min..$max / $step -> $r", r >= min && r <= max)
            val onMultiple = kotlin.math.abs(r / step - kotlin.math.round(r / step)) < 1e-6
            assertTrue("$v in $min..$max / $step -> $r is a multiple or an end", onMultiple || r == min || r == max)
        }
    }

    @Test
    fun normalizesDegrees() {
        assertEquals(180f, IncrementMath.normalizeDegrees(-180f), 0f)
        assertEquals(-170f, IncrementMath.normalizeDegrees(190f), 0f)
        assertEquals(0f, IncrementMath.normalizeDegrees(720f), 0f)
    }
}
