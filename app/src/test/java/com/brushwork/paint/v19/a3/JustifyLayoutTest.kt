package com.brushwork.paint.v19.a3

import com.brushwork.paint.tools.text.Justified
import com.brushwork.paint.tools.text.Justify
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.WrapLayout
import com.brushwork.paint.tools.text.WrapLine
import com.brushwork.paint.tools.text.WrapMeasurer
import com.brushwork.paint.tools.text.WrapMetrics
import com.brushwork.paint.tools.text.WrapSides
import com.brushwork.paint.tools.text.WrapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9 A3 step 2 (R4): justified lines ([Justify] on [WrapLayout]'s lines), on the JVM with a
 * fake monospace font (every character 10 px). Line breaks are those of `OFF`; the last line of a
 * paragraph keeps `align` under `ON` and spreads under `ALL`; word gaps take the slack, a line
 * without spaces in Japanese spreads between its grapheme clusters, a one-word Latin line keeps
 * `align` (spreads under `ALL`); right-to-left paragraphs keep `align`; a line beside a picture
 * spreads over its segment; a frame's last line whose paragraph goes on is justified; point text
 * spreads to its widest line.
 */
class JustifyLayoutTest {
    private val mono = WrapMeasurer { _, start, end, out -> for (i in 0 until end - start) out[i] = 10f }
    private val metrics = WrapMetrics(ascent = 16f, descent = 4f, extra = 4f)
    private val nothing: (Float, Float) -> List<ClosedFloatingPointRange<Float>> = { _, _ -> emptyList() }

    private val story = "The quick brown fox jumps over the lazy dog.\n\nA second paragraph follows here, with more words to wrap " +
        "across several lines.\nThird."

    private fun lay(text: String, width: Float, align: TextAlign = TextAlign.START): Pair<WrapText, List<WrapLine>> {
        val t = WrapLayout.measure(text, mono)
        return t to WrapLayout.layout(t, width, metrics, nothing, WrapSides.LARGEST, align, 0f).lines
    }

    /** Where the right edge of line [l]'s last letter ends (its x, its advances and the extra space before it). */
    private fun rightEdge(t: WrapText, l: WrapLine, j: Justified): Float =
        l.x + t.width(l.start, l.end) + Justify.at(j.offsets, l.end - 1)

    @Test
    fun breaksAreThoseOfOff() {
        for (width in listOf(60f, 100f, 155f, 400f)) {
            for (align in TextAlign.entries) {
                val (t, lines) = lay(story, width, align)
                for (mode in TextJustify.entries) {
                    val j = Justify.apply(t, lines, mode, toWidest = false)
                    assertEquals(lines.size, j.lines.size)
                    for (i in lines.indices) {
                        val a = lines[i]
                        val b = j.lines[i]
                        assertEquals("$width $align $mode line $i", listOf(a.start, a.end), listOf(b.start, b.end))
                        assertEquals(a.baseline, b.baseline, 0f)
                        assertEquals(a.width, b.width, 0f)
                    }
                }
                val off = Justify.apply(t, lines, TextJustify.OFF, toWidest = false)
                assertSame("OFF changes nothing", lines, off.lines)
                assertNull(off.offsets)
            }
        }
    }

    @Test
    fun lastLineRule() {
        for (align in TextAlign.entries) {
            val (t, lines) = lay(story, 155f, align)
            val on = Justify.apply(t, lines, TextJustify.ON, toWidest = false)
            val all = Justify.apply(t, lines, TextJustify.ALL, toWidest = false)
            assertNotNull(on.offsets)
            for (i in lines.indices) {
                val l = lines[i]
                if (l.end <= l.start) continue
                val words = t.text.substring(l.start, l.end).contains(' ')
                if (l.lastOfParagraph) {
                    // ON: the last line keeps align (its place and no extra space).
                    assertEquals("$align last line $i keeps its x", l.x, on.lines[i].x, 0f)
                    for (k in l.start until l.end) assertEquals(0f, Justify.at(on.offsets, k), 0f)
                } else if (words) {
                    assertEquals("$align line $i starts at its run", l.runX, on.lines[i].x, 0f)
                    assertEquals("$align line $i ends at the right edge", l.runX + l.runWidth, rightEdge(t, on.lines[i], on), 1e-3f)
                } else {
                    // A one-word line ("paragraph" at 155 px) keeps align under ON.
                    assertEquals("$align one-word line $i keeps its x", l.x, on.lines[i].x, 0f)
                    for (k in l.start until l.end) assertEquals(0f, Justify.at(on.offsets, k), 0f)
                }
                // ALL: every line spreads, the last ones and one-word lines (between letters) too.
                assertEquals("$align ALL line $i", l.runX + l.runWidth, rightEdge(t, all.lines[i], all), 1e-3f)
            }
        }
    }

    @Test
    fun wordGapsShareTheSlack() {
        // "aa bb cc" is 80 px in 100: 20 px over two gaps.
        val (t, lines) = lay("aa bb cc", 100f)
        val j = Justify.apply(t, lines, TextJustify.ALL, toWidest = false)
        val o = j.offsets!!
        assertEquals(listOf(0f, 0f, 0f, 10f, 10f, 10f, 20f, 20f), (0 until 8).map { o[it] })
        assertFalse(j.spreadsClusters)
        // Spaces that open a paragraph stay an indent: the gaps are after the first letter.
        val (t2, lines2) = lay("  aa bb", 100f)
        val o2 = Justify.apply(t2, lines2, TextJustify.ALL, toWidest = false).offsets!!
        // 70 px in 100: one gap (after "aa") takes all 30.
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 30f, 30f), (0 until 7).map { o2[it] })
        // Pieces split after every stretched gap, each keeping its space.
        val pieces = ArrayList<Pair<String, Float>>()
        Justify.forEachPiece(j.lines[0], t, o) { a, b, x -> pieces += t.text.substring(a, b) to x }
        assertEquals(listOf("aa " to 0f, "bb " to 40f, "cc" to 80f), pieces)
    }

    @Test
    fun japaneseSpreadsBetweenClusters() {
        // 13 characters, 85 px wide: the first line takes about 8 (80 px) and spreads the rest of its
        // width over the gaps between them; the last line keeps align under ON.
        val text = "テキストを入力します。もう"
        val (t, lines) = lay(text, 85f)
        assertTrue(lines.size >= 2)
        val j = Justify.apply(t, lines, TextJustify.ON, toWidest = false)
        assertTrue(j.spreadsClusters)
        val first = j.lines[0]
        assertFalse(first.lastOfParagraph)
        val n = first.end - first.start
        assertEquals(85f, rightEdge(t, first, j), 1e-3f)
        val step = (85f - t.width(first.start, first.end)) / (n - 1)
        for (k in 0 until n) assertEquals("char $k", k * step, j.offsets!![first.start + k], 1e-3f)
        val last = j.lines.last()
        assertEquals(lines.last().x, last.x, 0f)
        for (k in last.start until last.end) assertEquals(0f, j.offsets!![k], 0f)
    }

    @Test
    fun accentsStayOnTheirLetters() {
        // "é" is one cluster: under ALL the one-word line spreads between clusters only.
        val text = "ééé"
        val (t, lines) = lay(text, 100f)
        val j = Justify.apply(t, lines, TextJustify.ALL, toWidest = true)
        // Point text: its widest line is this one, so nothing to spread.
        assertNull(j.offsets)
        val boxed = Justify.apply(t, lines, TextJustify.ALL, toWidest = false)
        val o = boxed.offsets!!
        assertEquals("the accent moves with its letter", o[0], o[1], 0f)
        assertEquals(o[2], o[3], 0f)
        assertEquals(o[4], o[5], 0f)
        assertTrue(o[2] > o[1] && o[4] > o[3])
        assertEquals(100f, rightEdge(t, boxed.lines[0], boxed), 1e-3f)
    }

    @Test
    fun oneWordLinesKeepAlignUnlessAll() {
        // Line 1 "Abcdefghijkl" (120 px of 150, one word, not the last); line 2 "mnop".
        for (align in TextAlign.entries) {
            val (t, lines) = lay("Abcdefghijkl mnop", 150f, align)
            assertEquals(2, lines.size)
            assertFalse(lines[0].lastOfParagraph)
            val on = Justify.apply(t, lines, TextJustify.ON, toWidest = false)
            assertNull("$align: a one-word line keeps align", on.offsets)
            assertEquals(lines[0].x, on.lines[0].x, 0f)
            val all = Justify.apply(t, lines, TextJustify.ALL, toWidest = false)
            assertEquals(0f, all.lines[0].x, 0f)
            assertEquals(150f, rightEdge(t, all.lines[0], all), 1e-3f)
            assertEquals(150f, rightEdge(t, all.lines[1], all), 1e-3f)
            assertTrue(all.spreadsClusters)
        }
    }

    @Test
    fun rightToLeftParagraphsKeepAlign() {
        val hebrew = "שלום עולם שלום עולם שלום עולם"
        val (t, lines) = lay(hebrew + "\n" + "aa bb cc dd ee ff gg", 100f, TextAlign.END)
        val j = Justify.apply(t, lines, TextJustify.ALL, toWidest = false)
        val o = j.offsets!!
        for (i in lines.indices) {
            val l = lines[i]
            if (l.end <= hebrew.length) {
                assertEquals("Hebrew line $i keeps its x", l.x, j.lines[i].x, 0f)
                for (k in l.start until l.end) assertEquals(0f, o[k], 0f)
            } else if (l.end > l.start) {
                assertEquals("Latin line $i", 100f, rightEdge(t, j.lines[i], j), 1e-3f)
            }
        }
    }

    @Test
    fun aLineBesideAPictureSpreadsOverItsSegment() {
        val t = WrapLayout.measure("aa bb cc dd ee ff gg hh ii jj kk ll mm nn", mono)
        // The picture covers x 0..60 in every band: the runs are 60..200.
        val blocked: (Float, Float) -> List<ClosedFloatingPointRange<Float>> = { _, _ -> listOf(0f..60f) }
        val lines = WrapLayout.layout(t, 200f, metrics, blocked, WrapSides.LARGEST, TextAlign.CENTER, 0f).lines
        val j = Justify.apply(t, lines, TextJustify.ON, toWidest = false)
        for (i in lines.indices) {
            val l = lines[i]
            assertEquals(60f, l.runX, 0f)
            assertEquals(140f, l.runWidth, 0f)
            if (l.lastOfParagraph) continue
            assertEquals(60f, j.lines[i].x, 0f)
            assertEquals(200f, rightEdge(t, j.lines[i], j), 1e-3f)
        }
    }

    @Test
    fun aFramesLastLineWhoseParagraphGoesOnIsJustified() {
        val t = WrapLayout.measure(story, mono)
        // Room for two lines: the frame stops inside the first paragraph.
        val r = WrapLayout.layoutFrame(t, 100f, 44f, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 0f)
        assertEquals(2, r.lines.size)
        assertTrue(r.end < story.indexOf('\n'))
        assertFalse(r.lines[1].lastOfParagraph)
        val j = Justify.apply(t, r.lines, TextJustify.ON, toWidest = false)
        assertEquals(100f, rightEdge(t, j.lines[1], j), 1e-3f)
        // The same paragraph laid out whole: its real last line keeps align.
        val whole = WrapLayout.layoutFrame(t, 100f, Float.POSITIVE_INFINITY, metrics, nothing, WrapSides.LARGEST, TextAlign.START, 0f)
        assertTrue(whole.lines.first { it.end >= story.indexOf('\n') - 1 && it.end > it.start }.lastOfParagraph)
    }

    @Test
    fun pointTextSpreadsToItsWidestLine() {
        // Without a box every line is a paragraph's last: ON changes nothing, ALL spreads to the widest.
        val text = "aaaa bbbb\ncc dd\nee"
        val t = WrapLayout.measure(text, mono)
        val width = WrapLayout.widestParagraph(t) + 1f
        val lines = WrapLayout.layout(t, width, metrics, nothing, WrapSides.LARGEST, TextAlign.CENTER, 0f).lines
        assertNull(Justify.apply(t, lines, TextJustify.ON, toWidest = true).offsets)
        val all = Justify.apply(t, lines, TextJustify.ALL, toWidest = true)
        assertEquals(90f, rightEdge(t, all.lines[0], all), 1e-3f)
        assertEquals(90f, rightEdge(t, all.lines[1], all), 1e-3f)
        // "ee" (one word): its letters spread to the widest line too.
        assertEquals(90f, rightEdge(t, all.lines[2], all), 1e-3f)
        assertEquals(0f, all.lines[1].x, 0f)
        assertEquals("the widest line is flush with the others", 0f, all.lines[0].x, 0f)
    }

    @Test
    fun emptyLinesAndOverfullLinesAreLeftAlone() {
        val (t, lines) = lay("a\n\nbb cc", 20f)
        val j = Justify.apply(t, lines, TextJustify.ALL, toWidest = false)
        for (i in lines.indices) if (lines[i].end <= lines[i].start) assertEquals(lines[i], j.lines[i])
        // "bb" and "cc" each fill 20 px exactly: no slack, nothing moves.
        assertNull(j.offsets)
    }
}
