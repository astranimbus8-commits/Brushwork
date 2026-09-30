package com.brushwork.paint.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NumberSliderMathTest {

    // ------------------------------------------------------------------ scales

    @Test
    fun linearScaleMapsBothWaysAndClamps() {
        val s = SliderScale.linear(-180.0, 180.0)
        assertEquals(0f, s.fraction(-180.0), 0f)
        assertEquals(0.5f, s.fraction(0.0), 1e-6f)
        assertEquals(1f, s.fraction(180.0), 0f)
        assertEquals(0f, s.fraction(-500.0), 0f)
        assertEquals(1f, s.fraction(500.0), 0f)
        assertEquals(0f, s.fraction(Double.NaN), 0f)
        assertEquals(90.0, s.value(0.75f), 1e-4)
        assertEquals(-180.0, s.value(-2f), 0.0)
        assertEquals(180.0, s.value(3f), 0.0)
        assertEquals(-180.0, s.value(Float.NaN), 0.0)
    }

    @Test
    fun logScaleIsPureLogForPositiveMinimum() {
        val s = SliderScale.log(1.0, 10000.0)
        assertEquals(0f, s.fraction(1.0), 1e-6f)
        assertEquals(0.25f, s.fraction(10.0), 1e-5f)
        assertEquals(0.5f, s.fraction(100.0), 1e-5f)
        assertEquals(0.75f, s.fraction(1000.0), 1e-5f)
        assertEquals(1f, s.fraction(10000.0), 1e-6f)
        assertEquals(100.0, s.value(0.5f), 1e-3)
        for (v in listOf(1.0, 3.7, 42.0, 999.0, 9876.0)) {
            assertEquals("round trip $v", v, s.value(s.fraction(v)), v * 1e-5)
        }
    }

    @Test
    fun logScaleFromZeroKeepsZeroReachable() {
        val s = SliderScale.log(0.0, 5000.0)
        assertEquals(0f, s.fraction(0.0), 0f)
        assertEquals(0.0, s.value(0f), 0.0)
        assertEquals(5000.0, s.value(1f), 1e-9)
        // Small values get far more room than on a linear slider.
        assertTrue(s.fraction(50.0) > 0.3f)
        assertTrue(s.fraction(1.0) < s.fraction(2.0))
        // Slider positions are Floats: about 7 significant digits survive the round trip.
        assertEquals(123.0, s.value(s.fraction(123.0)), 1e-3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun logScaleRejectsNegativeRanges() {
        SliderScale.log(-1.0, 10.0)
    }

    @Test
    fun autoScalePicksKindAndRejectsUselessRanges() {
        assertNull(NumberSliderMath.autoScale(Double.NEGATIVE_INFINITY, 10.0))
        assertNull(NumberSliderMath.autoScale(0.0, Double.POSITIVE_INFINITY))
        assertNull(NumberSliderMath.autoScale(5.0, 5.0))
        assertNull(NumberSliderMath.autoScale(Double.NaN, 5.0))
        // Wide positive ranges: logarithmic (canvas sizes, brush sizes, dpi).
        assertTrue(NumberSliderMath.autoScale(1.0, 10000.0)!!.log)
        assertTrue(NumberSliderMath.autoScale(0.5, 1000.0)!!.log)
        // Narrow or signed ranges: linear (color channels, angles, percentages).
        assertFalse(NumberSliderMath.autoScale(0.0, 255.0)!!.log)
        assertFalse(NumberSliderMath.autoScale(-360.0, 360.0)!!.log)
        assertFalse(NumberSliderMath.autoScale(1.0, 250.0)!!.log)
        // Technical limits (±100000 px) make a useless linear slider: none unless asked for.
        assertNull(NumberSliderMath.autoScale(-100000.0, 100000.0))
        assertNotNull(NumberSliderMath.autoScale(-3000.0, 6000.0, explicit = true))
        // Forced kinds.
        assertFalse(NumberSliderMath.autoScale(1.0, 10000.0, log = false, explicit = true)!!.log)
        assertTrue(NumberSliderMath.autoScale(0.0, 100.0, log = true)!!.log)
        assertFalse("log needs a non-negative range", NumberSliderMath.autoScale(-10.0, 100.0, log = true)!!.log)
    }

    @Test
    fun scaleForHonorsAdjustAndClampsExplicitRanges() {
        assertNull(NumberSliderMath.scaleFor(NumberAdjust.SCRUB, 0.0, 100.0, null, null, null))
        assertNull(NumberSliderMath.scaleFor(NumberAdjust.NONE, 0.0, 100.0, 0.0, 50.0, null))
        assertEquals(SliderScale.linear(0.0, 100.0), NumberSliderMath.scaleFor(NumberAdjust.AUTO, 0.0, 100.0, null, null, null))
        // An explicit slider range is clamped to what the field accepts.
        assertEquals(SliderScale.linear(1.0, 50.0), NumberSliderMath.scaleFor(NumberAdjust.AUTO, 1.0, 50.0, -10.0, 90.0, null))
        // Unbounded field, explicit slider range: a slider over that range.
        assertEquals(
            SliderScale.linear(-400.0, 800.0),
            NumberSliderMath.scaleFor(NumberAdjust.AUTO, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -400.0, 800.0, null),
        )
        // Only one end given: treated as no explicit range.
        assertNull(NumberSliderMath.scaleFor(NumberAdjust.AUTO, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, null, null))
    }

    // ------------------------------------------------------------------ rounding

    @Test
    fun roundingHasNoBinaryNoise() {
        assertEquals(0.3, NumberSliderMath.roundToPowerOfTen(0.1 + 0.2, -1), 0.0)
        assertEquals("0.3", (NumberSliderMath.roundToPowerOfTen(0.30000000000000004, -2)).toString())
        assertEquals(1230.0, NumberSliderMath.roundToPowerOfTen(1234.5, 1), 0.0)
        assertEquals(-12.35, NumberSliderMath.roundToPowerOfTen(-12.3456, -2), 1e-12)
        assertTrue(NumberSliderMath.roundToPowerOfTen(Double.NaN, 0).isNaN())
        assertEquals(1e300, NumberSliderMath.roundToPowerOfTen(1e300, -3), 0.0)
    }

    @Test
    fun sliderValuesRoundToSensiblePrecision() {
        val log = SliderScale.log(0.5, 1000.0)
        // Three significant digits, never finer than the field's decimals.
        for (f in listOf(0.13f, 0.37f, 0.52f, 0.66f, 0.81f, 0.97f)) {
            val v = NumberSliderMath.sliderValue(f, log, decimals = 1, min = 0.5, max = 1000.0)
            val tenth = v * 10.0
            assertEquals("$v has at most one decimal", Math.rint(tenth), tenth, 1e-9)
            val digits = v.toBigDecimal().stripTrailingZeros().precision()
            assertTrue("$v has at most three significant digits", digits <= 3)
        }
        // Whole numbers when the field shows none.
        val lin = SliderScale.linear(0.0, 255.0)
        val v = NumberSliderMath.sliderValue(0.4321f, lin, decimals = 0, min = 0.0, max = 255.0)
        assertEquals(Math.rint(v), v, 0.0)
        // A wide linear range rounds to about a thousandth of its span.
        val wide = SliderScale.linear(-3000.0, 6000.0)
        val p = NumberSliderMath.sliderValue(0.3337f, wide, decimals = 1, min = Double.NEGATIVE_INFINITY, max = Double.POSITIVE_INFINITY)
        assertEquals(Math.rint(p), p, 0.0)
    }

    @Test
    fun sliderEndsGiveExactRangeEndsWithinFieldLimits() {
        val s = SliderScale.log(1.0, 10000.0)
        assertEquals(1.0, NumberSliderMath.sliderValue(0f, s, 1, 1.0, 100000.0), 0.0)
        assertEquals(10000.0, NumberSliderMath.sliderValue(1f, s, 1, 1.0, 100000.0), 0.0)
        // The field's own limits win over the slider range.
        assertEquals(5000.0, NumberSliderMath.sliderValue(1f, s, 1, 1.0, 5000.0), 0.0)
        val lin = SliderScale.linear(0.0, 100.0)
        for (i in 0..100) {
            val v = NumberSliderMath.sliderValue(i / 100f, lin, 0, 0.0, 100.0)
            assertTrue(v in 0.0..100.0)
        }
    }

    @Test
    fun sliderAndScaleRoundTripIsStable() {
        // Moving to the fraction of a rounded value and back gives the same value (no drift).
        val s = SliderScale.log(0.5, 1000.0)
        for (i in 1..99) {
            val v = NumberSliderMath.sliderValue(i / 100f, s, 1, 0.5, 1000.0)
            val again = NumberSliderMath.sliderValue(s.fraction(v), s, 1, 0.5, 1000.0)
            assertEquals("at ${i / 100f}", v, again, abs(v) * 0.011)
        }
    }

    // ------------------------------------------------------------------ scrub

    @Test
    fun scrubStartsAboutOneStepPerSixDpAndAccelerates() {
        assertEquals(0L, NumberSliderMath.scrubSteps(0f))
        assertEquals(0L, NumberSliderMath.scrubSteps(5f))
        assertEquals(1L, NumberSliderMath.scrubSteps(6.1f))
        assertEquals(-1L, NumberSliderMath.scrubSteps(-6.1f))
        assertEquals(5L, NumberSliderMath.scrubSteps(30f)) // 5 * (1 + 1/16) = 5.3
        // Twice as fast at ACCEL_DP, and faster beyond.
        assertEquals(40L, NumberSliderMath.scrubSteps(120f))
        assertTrue(NumberSliderMath.scrubSteps(240f) > 4 * NumberSliderMath.scrubSteps(120f))
        // Monotonic and symmetric.
        var last = -1L
        var dp = 0f
        while (dp < 400f) {
            val s = NumberSliderMath.scrubSteps(dp)
            assertTrue(s >= last)
            assertEquals(-s, NumberSliderMath.scrubSteps(-dp))
            last = s
            dp += 0.7f
        }
        assertEquals(0L, NumberSliderMath.scrubSteps(Float.NaN))
    }

    @Test
    fun scrubValueStepsFromTheStartValueAndClamps() {
        assertEquals(15.0, NumberSliderMath.scrubValue(10.0, 30.5f, 1.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), 0.0)
        assertEquals(5.0, NumberSliderMath.scrubValue(10.0, -30.5f, 1.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), 0.0)
        // Keeps the precision of the start value, without binary noise from the step.
        assertEquals(12.87, NumberSliderMath.scrubValue(12.37, 30.5f, 0.1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, NumberSliderMath.scrubValue(3.0, -300f, 1.0, 0.0, 100.0), 0.0)
        assertEquals(100.0, NumberSliderMath.scrubValue(3.0, 300f, 1.0, 0.0, 100.0), 0.0)
        // Nonsense steps leave the value alone.
        assertEquals(3.0, NumberSliderMath.scrubValue(3.0, 300f, 0.0, 0.0, 100.0), 0.0)
        assertEquals(3.0, NumberSliderMath.scrubValue(3.0, 300f, Double.NaN, 0.0, 100.0), 0.0)
    }

    @Test
    fun typedSliderValuesParseWithScaleAndSuffix() {
        assertEquals(0.57f, NumberSliderMath.parseTyped("57", 100f, 0f, 1f)!!, 1e-6f)
        assertEquals(0.57f, NumberSliderMath.parseTyped(" 57 % ", 100f, 0f, 1f)!!, 1e-6f)
        assertEquals(12.5f, NumberSliderMath.parseTyped("12,5 px", 1f, 0f, 600f)!!, 1e-6f)
        assertEquals(-30f, NumberSliderMath.parseTyped("-30°", 1f, -180f, 180f)!!, 0f)
        // Clamped to the slider's range.
        assertEquals(1f, NumberSliderMath.parseTyped("250", 100f, 0f, 1f)!!, 0f)
        assertEquals(0.01f, NumberSliderMath.parseTyped("0", 100f, 0.01f, 1f)!!, 0f)
        // Garbage and nonsense scales give nothing.
        assertNull(NumberSliderMath.parseTyped("abc", 1f, 0f, 1f))
        assertNull(NumberSliderMath.parseTyped("", 1f, 0f, 1f))
        assertNull(NumberSliderMath.parseTyped("--5", 1f, 0f, 10f))
        assertNull(NumberSliderMath.parseTyped("5", 0f, 0f, 10f))
    }

    @Test
    fun defaultDragStepFollowsShownDecimals() {
        assertEquals(1.0, NumberSliderMath.defaultDragStep(0), 0.0)
        assertEquals(1.0, NumberSliderMath.defaultDragStep(1), 0.0)
        assertEquals(0.1, NumberSliderMath.defaultDragStep(2), 1e-12)
        assertEquals(0.01, NumberSliderMath.defaultDragStep(3), 1e-12)
    }
}
