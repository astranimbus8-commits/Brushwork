package com.brushwork.paint.tools.text

import android.graphics.Paint
import android.text.TextPaint
import com.brushwork.paint.tools.text.LetterScaleFixtures.render
import com.brushwork.paint.tools.text.WrapFixtures.LOREM
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.5(c, d) "ScaledWrapTest": scaled letters are laid out by [WrapLayout] on their scaled
 * advances — a fixed box breaks lines on them, the lines keep the full-size pitch (the box
 * doesn't jump while the slider moves), text wraps around a picture the same way, and the
 * letters of linked frames continue one ramp over the whole story (the flow and the drawing use
 * the same measurement, which is only reused for the same place in the same story).
 */
@RunWith(RobolectricTestRunner::class)
class ScaledWrapTest {

    private val on = LetterScaleSpec(smallestPercent = 40f)

    private fun paintFor(spec: TextSpec) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = spec.antiAlias
        textSize = spec.sizePx
        typeface = TextRenderer.typeface(spec)
        letterSpacing = spec.letterSpacing
        // Scaled letters are measured as they are drawn: one by one, no ligatures.
        fontFeatureSettings = TextRenderer.SCALED_LETTER_FEATURES
    }

    /** Scaled advance width of `text[a, b)` measured independently of the renderer. */
    private fun scaledWidth(text: String, spec: TextSpec, a: Int, b: Int, source: String = text, offset: Int = 0): Double {
        val ramp = LetterRamp.compute(source, spec.letterScale)
        val adv = FloatArray(text.length)
        // WrapLayout measures paragraph by paragraph (no line breaks in these texts).
        paintFor(spec).getTextWidths(text, 0, text.length, adv)
        var w = 0.0
        for (i in a until b) w += adv[i] * ramp.factors[offset + i]
        return w
    }

    private fun trimEnd(text: String, s: Int, e: Int): Int {
        var x = e
        while (x > s && text[x - 1] == ' ') x--
        return x
    }

    @Test
    fun aFixedBoxBreaksOnTheScaledAdvancesAtTheFullSizePitch() {
        val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 300f), letterScale = on)
        val block = TextRenderer.layout(LOREM, spec)
        val lines = block.wrapLines!!
        assertNull("not StaticLayout", block.staticLayout)
        assertEquals(300f, block.contentWidth, 0f)
        val plain = TextRenderer.layout(LOREM, spec.copy(letterScale = LetterScaleSpec())).staticLayout!!
        assertTrue("smaller letters take fewer lines: ${lines.size} vs ${plain.lineCount}", lines.size < plain.lineCount)
        // The full-size pitch and first baseline (fixed leading).
        val pitch = (plain.getLineBaseline(1) - plain.getLineBaseline(0)).toFloat()
        assertEquals(plain.getLineBaseline(0).toFloat(), lines[0].baseline, 0.01f)
        for (i in 1 until lines.size) assertEquals(pitch, lines[i].baseline - lines[i - 1].baseline, 0.01f)
        // Every line fits the box on the scaled advances, and the next word would not (greedy).
        for ((i, l) in lines.withIndex()) {
            val w = scaledWidth(LOREM, spec, l.start, l.end)
            assertEquals(w.toFloat(), l.width, 0.05f)
            assertTrue("line $i is $w wide", w <= 300.001)
            if (i + 1 < lines.size) {
                val next = lines[i + 1]
                var wordEnd = LOREM.indexOf(' ', next.start).let { if (it < 0) LOREM.length else it }
                wordEnd = trimEnd(LOREM, next.start, wordEnd)
                assertTrue("the next word would overflow line $i", scaledWidth(LOREM, spec, l.start, wordEnd) > 300.0)
            }
        }
    }

    @Test
    fun theBoxKeepsItsHeightWhileTheSliderMoves() {
        // A fixed box: the same number of lines at two scales gives the same height.
        val base = TextSpec(sizePx = 30f, box = TextBoxSpec(width = 2000f), letterScale = on)
        val a = TextRenderer.layout("One line of text\nand a second", base)
        val b = TextRenderer.layout("One line of text\nand a second", base.copy(letterScale = on.copy(smallestPercent = 70f)))
        assertEquals(2, a.lineCount)
        assertEquals(a.height, b.height, 0f)
    }

    @Test
    fun unboxedTextIsAsWideAsItsWidestParagraph() {
        val spec = TextSpec(sizePx = 40f, align = TextAlign.CENTER, letterScale = on)
        val text = "ELTON JOHN\nshort"
        val block = TextRenderer.layout(text, spec)
        val lines = block.wrapLines!!
        assertEquals("one line per paragraph", 2, lines.size)
        val widest = scaledWidth(text, spec.copy(), 0, 10).toFloat()
        assertTrue("${block.contentWidth} vs $widest", block.contentWidth >= widest && block.contentWidth <= widest + 2f)
        // Centred: the short line sits in the middle of the block.
        val l = lines[1]
        assertEquals(block.contentWidth / 2f, l.x + l.width / 2f, 0.6f)
        // The unscaled text is wider.
        assertTrue(TextRenderer.layout(text, spec.copy(letterScale = LetterScaleSpec())).contentWidth > block.contentWidth)
    }

    @Test
    fun wrappingAroundAPictureWorksWithScaledLetters() {
        val square = WrapPolygon(listOf(150f, 260f, 260f, 150f), listOf(120f, 120f, 260f, 260f))
        val item = TextItem(
            LOREM, TextSpec(sizePx = 22f, box = TextBoxSpec(width = 380f), letterScale = on), 250f, 200f,
            wrap = TextWrapSpec(sourceLayerId = 9, polygons = listOf(square), gapPx = 5f, sides = WrapSides.BOTH),
        )
        val prep = TextRenderer.prepare(item)
        val lines = prep.block!!.wrapLines!!
        val unscaled = TextRenderer.prepare(item.copy(spec = item.spec.copy(letterScale = LetterScaleSpec()))).block!!.wrapLines!!
        assertTrue("fewer lines: ${lines.size} vs ${unscaled.size}", lines.size < unscaled.size)
        // The picture is kept clear: no ink inside the square (minus the gap).
        val bmp = render(item, 500, 400)
        var inside = 0
        for (y in 128 until 252) for (x in 158 until 252) if ((bmp.getPixel(x, y) ushr 24) > 40) inside++
        assertEquals("no letter inside the picture", 0, inside)
        // A drag re-uses the measured letters (same ramp), and lays out the same lines again.
        val moved = TextRenderer.prepare(item.copy(cy = 201f), prep)
        assertSame(prep.wrapText, moved.wrapText)
        val fresh = TextRenderer.prepare(item.copy(cy = 201f))
        assertEquals(fresh.block!!.wrapLines, moved.block!!.wrapLines)
    }

    private val story = "Linked frames flow a story from one box into the next, just like InDesign. " +
        "When the first frame is full, the words that do not fit continue in the second frame, and so on."

    private fun flow(spec: TextSpec, frames: Int, source: String = story): List<TextItem> {
        val out = ArrayList<TextItem>()
        var start = 0
        for (k in 0 until frames) {
            val probe = TextItem(spec = spec, cx = 200f, cy = 100f + 150f * k, thread = TextThreadSpec(storyId = 5, index = k, story = source, start = start, end = start))
            val end = TextRenderer.frameEnd(probe)
            out += probe.copy(thread = probe.thread.copy(end = end, overset = k == frames - 1 && end < source.length)).sanitized()
            start = end
        }
        return out
    }

    @Test
    fun linkedFramesContinueOneRampOverTheStory() {
        val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 240f, minHeight = 100f), letterScale = on)
        val frames = flow(spec, 3)
        val plain = flow(spec.copy(letterScale = LetterScaleSpec()), 3)
        assertTrue("smaller letters: more of the story fits two frames", frames[1].thread.end > plain[1].thread.end)
        assertTrue(frames[0].thread.end >= plain[0].thread.end)
        for (k in 1 until frames.size) assertEquals(frames[k - 1].thread.end, frames[k].thread.start)
        val ramp = LetterRamp.compute(story, on)
        for (f in frames) {
            assertEquals("I9", story.substring(f.thread.start, f.thread.end), f.text)
            assertEquals("the flow and the frame agree", f.thread.end, TextRenderer.frameEnd(f))
            val fl = TextRenderer.frameLayout(f)
            assertNotNull(fl.ramp)
            // The frame's letters are the story's: the second frame starts smaller than the first.
            if (f.thread.start < story.length) assertEquals(ramp.factors[f.thread.start], fl.ramp!!.factors[f.thread.start], 0f)
            // Its lines' widths are the story's scaled advances.
            for (l in fl.lines) {
                if (l.end <= l.start) continue
                assertEquals(scaledWidth(f.thread.story.substring(f.thread.start), spec, l.start, l.end, story, f.thread.start).toFloat(), l.width, 0.05f)
            }
        }
        assertTrue(ramp.factors[frames[1].thread.start] < ramp.factors[0])
        // Every frame draws exactly its own lines.
        for (f in frames) {
            val block = TextRenderer.prepare(f).block!!
            assertTrue(block.wrapLines!!.all { it.end <= f.text.length })
            assertNull(TextExport.lines(f))
        }
    }

    @Test
    fun aScaledMeasurementIsOnlyReusedWithTheSameFactors() {
        val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 240f, minHeight = 200f), letterScale = on)
        // Two stories ending in the same words: the tail is measured with different factors.
        val tail = "the same closing words"
        val a = TextItem(spec = spec, thread = TextThreadSpec(storyId = 1, story = "Short. $tail", start = 7, end = 7))
        val b = TextItem(spec = spec, thread = TextThreadSpec(storyId = 2, story = "A much longer opening first. $tail", start = 29, end = 29))
        val ma = TextRenderer.frameLayout(a).measured
        assertEquals(tail, ma.text)
        val reused = TextRenderer.frameLayout(b, ma)
        val fresh = TextRenderer.frameLayout(b)
        assertTrue("the stale measurement is not used", reused.measured !== ma)
        assertEquals(fresh.lines, reused.lines)
        // The same frame again: its own measurement is reused.
        assertSame(fresh.measured, TextRenderer.frameLayout(b, fresh.measured).measured)
        // Unscaled frames keep v1.5's rule (same text = same advances).
        val pa = TextRenderer.frameLayout(a.copy(spec = spec.copy(letterScale = LetterScaleSpec()))).measured
        assertSame(pa, TextRenderer.frameLayout(b.copy(spec = spec.copy(letterScale = LetterScaleSpec())), pa).measured)
    }

    @Test
    fun placeholderTextFillsAScaledBoxExactly() {
        val spec = TextSpec(sizePx = 20f, box = TextBoxSpec(width = 260f, minHeight = 120f), letterScale = on)
        val edit = PlaceholderFit.edit(TextItem("", spec), PlaceholderKind.LOREM, PlaceholderAmount.FILL, true, 600, 800, 4000f)!!
        val fits = TextRenderer.layout(edit.text, edit.spec.copy(box = edit.spec.box.copy(minHeight = 0f)))
        assertTrue("${fits.contentHeight} fits 120", fits.contentHeight <= 120.01f)
        val plain = PlaceholderFit.edit(TextItem("", spec.copy(letterScale = LetterScaleSpec())), PlaceholderKind.LOREM, PlaceholderAmount.FILL, true, 600, 800, 4000f)!!
        assertTrue("smaller letters take more words", edit.text.length > plain.text.length)
    }

    @Test
    fun unscaledLayoutsAreTheV15Ones() {
        val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 300f))
        assertNotNull(TextRenderer.layout(LOREM, spec).staticLayout)
        // Right-to-left text with scaling on keeps StaticLayout too.
        val rtl = TextRenderer.layout("שלום עולם", spec.copy(letterScale = on))
        assertNotNull(rtl.staticLayout)
        // The ramp of an unscaled text is all ones.
        assertArrayEquals(FloatArray(3) { 1f }, LetterRamp.compute("abc", LetterScaleSpec()).factors, 0f)
    }
}
