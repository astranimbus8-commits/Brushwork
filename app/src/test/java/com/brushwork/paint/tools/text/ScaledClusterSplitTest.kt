package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import com.brushwork.paint.engine.BitmapUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scaled letters and a line that starts inside a letter (v1.6 review fix). `WrapLayout` breaks a
 * word too wide for the box between characters, and on some platforms those break points split
 * what the ramp keeps as ONE letter (an emoji with a skin tone, a ZWJ sequence, a flag); a frame
 * of a linked story may also start there. The part of that letter on the next line (or frame) is
 * drawn there, at the letter's size, and outlined the same: no character is ever lost.
 */
@RunWith(RobolectricTestRunner::class)
class ScaledClusterSplitTest {

    /** Records each per-letter `drawText`: its characters, x and text size. */
    private class Recorder(b: Bitmap) : Canvas(b) {
        val calls = ArrayList<Triple<IntRange, Float, Float>>()
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            calls += Triple(start until end, x, paint.textSize)
            super.drawText(text, start, end, x, y, paint)
        }
    }

    /** 10 px per character (only where the letters go matters here). */
    private fun measured(text: String) = WrapLayout.measure(text) { _, a, b, out -> for (i in 0 until b - a) out[i] = 10f }

    private val spec = LetterScaleSpec(smallestPercent = 50f)

    @Test
    fun aLineStartingInsideALetterDrawsItsPart() {
        // A thumbs up with a skin tone (4 chars) is one letter for the ramp, here broken between
        // its two code points: "AB👍" on line 1, "🏽CD" on line 2.
        val text = "AB👍🏽CD"
        val ramp = LetterRamp.of(text, spec)
        assertArrayEquals("one letter of 4 chars", intArrayOf(0, 1, 2, 6, 7, 8), ramp.bounds)
        val lines = listOf(WrapLine(0, 4, 5f, 50f, 40f), WrapLine(4, 8, 5f, 110f, 40f))
        val letters = ScaledLetters(text, ramp, 0, LetterScaleAlign.BASELINE, 30f)
        val rec = Recorder(BitmapUtils.createLayerBitmap(200, 200))
        val paint = TextPaint().apply { textSize = 40f }
        letters.draw(rec, lines, measured(text), paint)

        val drawn = IntArray(text.length)
        for ((r, _, _) in rec.calls) for (i in r) drawn[i]++
        assertArrayEquals("every character drawn once: ${rec.calls}", IntArray(text.length) { 1 }, drawn)
        // The part on line 2 starts that line, at the letter's own size.
        val tail = rec.calls.single { it.first.first == 4 }
        assertEquals(4..5, tail.first)
        assertEquals(5f, tail.second, 0f)
        assertEquals(40f * ramp.factors[2], tail.third, 1e-4f)
        assertEquals("the paint gets its size back", 40f, paint.textSize, 0f)

        // The outline places the same parts (export and the screen agree).
        val out = Path()
        letters.outline(out, lines, measured(text), paint)
        assertFalse(out.isEmpty)
    }

    @Test
    fun aFrameStartingInsideALetterDrawsItsPart() {
        // A story whose second frame starts between an "e" and its combining accent.
        val story = "Aézz"
        val ramp = LetterRamp.of(story, spec)
        assertArrayEquals(intArrayOf(0, 1, 3, 4, 5), ramp.bounds)
        val start = 2
        val text = story.substring(start)
        val lines = listOf(WrapLine(0, text.length, 0f, 50f, 30f))
        val letters = ScaledLetters(text, ramp, start, LetterScaleAlign.CENTER, 30f)
        val rec = Recorder(BitmapUtils.createLayerBitmap(200, 200))
        letters.draw(rec, lines, measured(text), TextPaint().apply { textSize = 40f })
        assertEquals("the accent, then both z's", listOf(0..0, 1..1, 2..2), rec.calls.map { it.first })
        // Each at its letter's factor in the story's ramp (the accent's letter is the "é").
        assertEquals(40f * ramp.factors[1], rec.calls[0].third, 1e-4f)
        assertEquals(40f * ramp.factors[3], rec.calls[1].third, 1e-4f)
        assertEquals(40f * ramp.factors[4], rec.calls[2].third, 1e-4f)
    }

    @Test
    fun unsplitLinesDrawExactlyTheirLetters() {
        // The usual case is unchanged: lines that start on a letter boundary draw only their own letters.
        val text = "ELTON JOHN"
        val ramp = LetterRamp.of(text, spec)
        val lines = listOf(WrapLine(0, 6, 0f, 50f, 50f), WrapLine(6, 10, 0f, 110f, 40f))
        val rec = Recorder(BitmapUtils.createLayerBitmap(200, 200))
        ScaledLetters(text, ramp, 0, LetterScaleAlign.CENTER, 30f).draw(rec, lines, measured(text), TextPaint().apply { textSize = 40f })
        assertEquals(text.filter { it != ' ' }.length, rec.calls.size)
        assertEquals((0..4).map { it..it } + (6..9).map { it..it }, rec.calls.map { it.first })
    }
}
