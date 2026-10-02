package com.brushwork.paint.tools.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * v1.6 foundation (§3.5, §4.9, V1 / V2): [LetterScaleSpec.factor], the size ramp of progressive
 * letter scaling (JVM). The user's ELTON JOHN example measures 42, 40, 38, 36, 34, 32, 30, 28, 26
 * px over its 9 letters: even steps from 100 % down to 26 / 42.
 */
class LetterScaleFactorTest {

    @Test
    fun eltonJohnRampsInEvenTwoPixelSteps() {
        val spec = LetterScaleSpec(smallestPercent = 26f / 42f * 100f)
        assertTrue(spec.isOn)
        assertEquals("defaults measured from the example", LetterScaleAlign.CENTER, spec.align)
        assertEquals(LetterScaleCurve.EVEN, spec.curve)
        assertEquals(LetterScaleDirection.START_TO_END, spec.direction)
        val sizes = (0 until 9).map { 42f * spec.factor(it, 9) }
        val expected = listOf(42f, 40f, 38f, 36f, 34f, 32f, 30f, 28f, 26f)
        for (i in sizes.indices) assertEquals("letter $i", expected[i], sizes[i], 1e-3f)
    }

    @Test
    fun endToBeginningIsTheMirrorImage() {
        val a = LetterScaleSpec(smallestPercent = 50f)
        val b = a.copy(direction = LetterScaleDirection.END_TO_START)
        for (k in 0 until 7) assertEquals(a.factor(k, 7), b.factor(6 - k, 7), 1e-6f)
        assertEquals(0.5f, b.factor(0, 7), 1e-6f)
        assertEquals(1f, b.factor(6, 7), 1e-6f)
    }

    @Test
    fun sameRatioIsGeometric() {
        val s = LetterScaleSpec(smallestPercent = 25f, curve = LetterScaleCurve.RATIO)
        for (k in 0 until 5) assertEquals(0.25.pow(k / 4.0).toFloat(), s.factor(k, 5), 1e-6f)
        // Each letter is the same fraction of the one before it.
        val q = s.factor(1, 5) / s.factor(0, 5)
        for (k in 1 until 5) assertEquals(q, s.factor(k, 5) / s.factor(k - 1, 5), 1e-5f)
    }

    @Test
    fun offSingleLettersAndOutOfRangeIndices() {
        val off = LetterScaleSpec()
        assertFalse(off.isOn)
        assertEquals(1f, off.factor(3, 9), 0f)
        assertFalse(LetterScaleSpec(smallestPercent = 99.97f).isOn)
        val on = LetterScaleSpec(smallestPercent = 60f)
        assertEquals(LetterScaleSpec.DEFAULT_ON_PERCENT, on.smallestPercent, 0f)
        // One letter (or none) is the largest letter: full size whatever the direction.
        assertEquals(1f, on.factor(0, 1), 0f)
        assertEquals(1f, on.copy(direction = LetterScaleDirection.END_TO_START).factor(0, 1), 0f)
        assertEquals(1f, on.factor(0, 0), 0f)
        // Indices outside the letters are clamped.
        assertEquals(on.factor(0, 4), on.factor(-5, 4), 0f)
        assertEquals(on.factor(3, 4), on.factor(40, 4), 0f)
        // Garbage percentages: off after sanitizing; the minimum is 5 %.
        assertFalse(LetterScaleSpec(smallestPercent = Float.NaN).sanitized().isOn)
        assertEquals(0.05f, LetterScaleSpec(smallestPercent = 1f).factor(1, 2), 1e-6f)
    }
}
