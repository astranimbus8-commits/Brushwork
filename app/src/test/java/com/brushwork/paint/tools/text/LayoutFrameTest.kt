package com.brushwork.paint.tools.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6 foundation (§4.4, §4.9): [WrapLayout.layoutFrame], the band loop of [WrapLayout.layout]
 * with a height stop (JVM, a fake monospace font: every character 10 px, cell 20, extra 4).
 */
class LayoutFrameTest {
    private val mono = WrapMeasurer { _, start, end, out -> for (i in 0 until end - start) out[i] = 10f }
    private val metrics = WrapMetrics(ascent = 16f, descent = 4f, extra = 4f)
    private val nothing: (Float, Float) -> List<ClosedFloatingPointRange<Float>> = { _, _ -> emptyList() }

    private val story = "The quick brown fox jumps over the lazy dog.\n\nA second paragraph follows here, with more words to wrap " +
        "across several lines.\nThird.\n"

    private fun sameLines(a: List<WrapLine>, b: List<WrapLine>) {
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals("line $i", a[i], b[i])
    }

    @Test
    fun infiniteHeightFromZeroEqualsLayout() {
        for (width in listOf(60f, 100f, 155f, 400f, 2000f)) {
            for (align in TextAlign.entries) {
                val t = WrapLayout.measure(story, mono)
                val full = WrapLayout.layout(t, width, metrics, nothing, WrapSides.LARGEST, align, 15f)
                val frame = WrapLayout.layoutFrame(t, width, Float.POSITIVE_INFINITY, metrics, nothing, WrapSides.LARGEST, align, 15f)
                sameLines(full.lines, frame.lines)
                assertEquals(full.height, frame.height, 0f)
                assertEquals(story.length, frame.end)
            }
        }
        // With an obstacle too (a band blocked in the middle).
        val blocked: (Float, Float) -> List<ClosedFloatingPointRange<Float>> = { top, _ -> if (top in 20f..80f) listOf(40f..90f) else emptyList() }
        val t = WrapLayout.measure(story, mono)
        val full = WrapLayout.layout(t, 200f, metrics, blocked, WrapSides.BOTH, TextAlign.START, 15f)
        val frame = WrapLayout.layoutFrame(t, 200f, Float.POSITIVE_INFINITY, metrics, blocked, WrapSides.BOTH, TextAlign.START, 15f)
        sameLines(full.lines, frame.lines)
    }

    @Test
    fun aFrameStopsAtTheFirstBandThatDoesNotFit() {
        val t = WrapLayout.measure(story, mono)
        // Pitch 24, cell 20: 3 lines need 24 + 24 + 20 = 68.
        val r = WrapLayout.layoutFrame(t, 100f, 68f, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 15f)
        assertEquals(3, r.lines.size)
        assertEquals(68f, r.height, 0f)
        val all = WrapLayout.layout(t, 100f, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 15f).lines
        assertEquals("the end is the start of the first line that doesn't fit", all[3].start, r.end)
        // One pixel less: two lines.
        assertEquals(2, WrapLayout.layoutFrame(t, 100f, 67f, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 15f).lines.size)
        // Not even one line: nothing, end 0.
        val none = WrapLayout.layoutFrame(t, 100f, 19f, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 15f)
        assertTrue(none.lines.isEmpty())
        assertEquals(0, none.end)
        assertEquals(0f, none.height, 0f)
        // NaN height fits nothing.
        assertEquals(0, WrapLayout.layoutFrame(t, 100f, Float.NaN, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 15f).end)
    }

    @Test
    fun chainingFramesCoversTheStory() {
        for (h in listOf(20f, 44f, 70f, 140f)) {
            var start = 0
            val slices = ArrayList<String>()
            var guard = 0
            while (start < story.length && guard++ < 200) {
                val t = WrapLayout.measure(story.substring(start), mono)
                val r = WrapLayout.layoutFrame(t, 120f, h, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 15f)
                assertTrue("a frame of height $h takes something at $start", r.end > 0)
                slices += story.substring(start, start + r.end)
                // Lines index into the frame's own slice.
                for (l in r.lines) assertTrue(l.end <= r.end)
                start += r.end
            }
            assertEquals("frames of height $h cover the story contiguously", story, slices.joinToString(""))
        }
    }

    @Test
    fun anEmptyLineAtTheBoundaryStartsTheNextFrame() {
        val text = "one\n\ntwo"
        val t = WrapLayout.measure(text, mono)
        // Room for one line: "one" fits, the empty line doesn't.
        val r = WrapLayout.layoutFrame(t, 100f, 20f, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 15f)
        assertEquals(1, r.lines.size)
        assertEquals("the line break belongs to the first frame", 4, r.end)
        val rest = WrapLayout.measure(text.substring(4), mono)
        val r2 = WrapLayout.layoutFrame(rest, 100f, 44f, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 15f)
        assertEquals(2, r2.lines.size)
        assertEquals(0, r2.lines[0].end - r2.lines[0].start)
        assertEquals(4, r2.end)
    }
}
