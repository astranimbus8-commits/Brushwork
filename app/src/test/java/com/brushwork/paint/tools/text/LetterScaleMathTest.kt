package com.brushwork.paint.tools.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * v1.6 §3.5(d): which size every character of a text gets ([LetterRamp], pure JVM). The letters
 * are the non-whitespace grapheme clusters; whitespace takes the factor of the letter before it
 * and uses up no step (V2: the space between ELTON and JOHN is no letter of its own).
 */
class LetterScaleMathTest {

    private val elton = LetterScaleSpec(smallestPercent = 26f / 42f * 100f)

    private fun sizes(text: String, spec: LetterScaleSpec, size: Float = 42f): List<Float> {
        val r = LetterRamp.compute(text, spec)
        return text.indices.map { r.factors[it] * size }
    }

    @Test
    fun eltonJohnStepsTwoPixelsPerLetterAndTheSpaceTakesNoStep() {
        val s = sizes("ELTON JOHN", elton)
        val expected = listOf(42f, 40f, 38f, 36f, 34f, 34f, 32f, 30f, 28f, 26f)
        for (i in s.indices) assertEquals("\"${"ELTON JOHN"[i]}\" at $i", expected[i], s[i], 1e-3f)
        val r = LetterRamp.compute("ELTON JOHN", elton)
        assertEquals("9 letters", 9, r.letterCount)
        assertEquals(10, r.bounds.size - 1)
        assertTrue("the space is blank", r.blank[5])
    }

    @Test
    fun sameRatioAndEndToBeginning() {
        val ratio = LetterScaleSpec(smallestPercent = 25f, curve = LetterScaleCurve.RATIO)
        val f = LetterRamp.compute("ABCDE", ratio).factors
        for (k in 0 until 5) assertEquals(0.25.pow(k / 4.0).toFloat(), f[k], 1e-6f)
        val grow = LetterRamp.compute("ABCDE", ratio.copy(direction = LetterScaleDirection.END_TO_START)).factors
        for (k in 0 until 5) assertEquals("mirrored", f[4 - k], grow[k], 1e-6f)
        assertEquals("the last letter is the largest", 1f, grow[4], 0f)
        val even = LetterRamp.compute("ABCDE", LetterScaleSpec(smallestPercent = 60f, direction = LetterScaleDirection.END_TO_START)).factors
        assertEquals(0.6f, even[0], 1e-6f)
        assertEquals(0.8f, even[2], 1e-6f)
        assertEquals(1f, even[4], 1e-6f)
    }

    @Test
    fun aSingleLetterAndBlankTextsStayFullSize() {
        val on = LetterScaleSpec(smallestPercent = 30f)
        assertArrayEquals(floatArrayOf(1f), LetterRamp.compute("A", on).factors, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f, 1f), LetterRamp.compute("  A  ", on).factors, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), LetterRamp.compute(" \n ", on).factors, 0f)
        assertEquals(0, LetterRamp.compute("", on).factors.size)
        // Leading whitespace takes the first letter's size; trailing, the last one's.
        val f = LetterRamp.compute("  AB ", LetterScaleSpec(smallestPercent = 50f)).factors
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 0.5f, 0.5f), f, 1e-6f)
    }

    @Test
    fun emojiAndAccentedLettersAreOneLetterEach() {
        val on = LetterScaleSpec(smallestPercent = 50f)
        // Skin-tone modifier, ZWJ family, two flags, a keycap, e + combining acute, a variation selector.
        val cases = listOf(
            "A👍🏽B" to 3,
            "A👨‍👩‍👧B" to 3,
            "🇯🇵🇺🇸" to 2,
            "1️⃣2" to 2,
            "éa" to 2,
            "❤️x" to 2,
            "é" to 1,
        )
        for ((text, letters) in cases) {
            val r = LetterRamp.compute(text, on)
            assertEquals("letters of \"$text\": ${r.bounds.toList()}", letters, r.letterCount)
            // Every character of a cluster has its cluster's factor.
            for (c in 0 until r.bounds.size - 1) {
                for (i in r.bounds[c] until r.bounds[c + 1]) assertEquals(r.factors[r.bounds[c]], r.factors[i], 0f)
            }
        }
        val thumbs = LetterRamp.compute("A👍🏽B", on).factors
        assertEquals(1f, thumbs[0], 0f)
        assertEquals("the emoji is the middle letter", 0.75f, thumbs[1], 1e-6f)
        assertEquals(0.5f, thumbs[thumbs.size - 1], 1e-6f)
    }

    @Test
    fun scopeWholeTextOrEachParagraph() {
        val whole = LetterScaleSpec(smallestPercent = 40f)
        val w = LetterRamp.compute("AB\nCD", whole).factors
        assertEquals(1f, w[0], 1e-6f)
        assertEquals(0.8f, w[1], 1e-6f)
        assertEquals("the line break takes B's size", 0.8f, w[2], 1e-6f)
        assertEquals(0.6f, w[3], 1e-6f)
        assertEquals(0.4f, w[4], 1e-6f)
        val each = LetterRamp.compute("AB\nCD\n\nEFG", whole.copy(scope = LetterScaleScope.EACH_PARAGRAPH)).factors
        assertEquals(1f, each[0], 1e-6f)
        assertEquals(0.4f, each[1], 1e-6f)
        assertEquals("C starts its paragraph's ramp", 1f, each[3], 1e-6f)
        assertEquals(0.4f, each[4], 1e-6f)
        assertEquals(1f, each[7], 1e-6f)
        assertEquals(0.7f, each[8], 1e-6f)
        assertEquals(0.4f, each[9], 1e-6f)
    }

    @Test
    fun offAndGarbageSpecsAreFullSize() {
        assertTrue(LetterRamp.compute("ABC", LetterScaleSpec()).factors.all { it == 1f })
        assertTrue(LetterRamp.compute("ABC", LetterScaleSpec(smallestPercent = Float.NaN).sanitized()).factors.all { it == 1f })
        // Below the minimum: held to 5 %.
        assertEquals(0.05f, LetterRamp.compute("AB", LetterScaleSpec(smallestPercent = 1f).sanitized()).factors[1], 1e-6f)
    }

    @Test
    fun theRampIsCachedPerTextAndSpec() {
        val text = "Cached " + "x".repeat(50)
        val a = LetterRamp.of(text, elton)
        assertSame(a, LetterRamp.of(String(text.toCharArray()), elton))
        assertFalse(a === LetterRamp.of(text, elton.copy(curve = LetterScaleCurve.RATIO)))
    }

    @Test
    fun scriptsThatCanBeDrawnPerLetter() {
        for (ok in listOf("", "ELTON JOHN", "Café déjà vu", "Привет", "Γειά", "日本語のテキスト", "한국어", "Hi 😀 there", "1 + 2 = 3")) {
            assertTrue("\"$ok\" scales", LetterRamp.supports(ok))
        }
        for (no in listOf("مرحبا", "שלום", "नमस्ते", "สวัสดี", "ສະບາຍດີ", "བོད", "မြန်မာ", "ខ្មែរ", "Hello مرحبا", "abc ܫܠܡܐ")) {
            assertFalse("\"$no\" is drawn unscaled", LetterRamp.supports(no))
        }
    }

    @Test
    fun aLongStoryIsLinear() {
        val story = buildString { while (length < 50_000) append("Linked frames flow a story. ") }
        val t0 = System.nanoTime()
        val r = LetterRamp.compute(story, elton)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(story.length, r.factors.size)
        assertEquals(1f, r.factors[0], 0f)
        assertEquals(26f / 42f, r.factors[story.trimEnd().length - 1], 1e-4f)
        assertTrue("50k characters in $ms ms", ms < com.brushwork.paint.testing.PerfBudget.ms(400.0))
    }
}
