package com.brushwork.paint.v19.a3

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBlock
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.VerticalGlyph
import com.brushwork.paint.tools.text.VerticalLayoutResult
import com.brushwork.paint.tools.text.VerticalTextLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 12 (R4 on vertical text: "Justify columns", "Justify the last column too"):
 * [VerticalTextLayout] spreads columns to the block height as `Justify` spreads lines to the box
 * width.
 * - Breaks never change: the same glyphs in the same columns, the same width and height, for
 *   every text, wrap height and alignment.
 * - [TextJustify.ON]: every column but the last of its line ends flush with the block height, the
 *   free space in its word gaps (letters of a word keep their spacing); the last column of each
 *   line keeps its alignment; [TextJustify.ALL] spreads that one too.
 * - A column without a word gap spreads between its cells when it holds Chinese, Japanese or
 *   Korean letters; a one-word Latin column spreads only under [TextJustify.ALL].
 * - Text without a fixed height (each line one column, the last of its line) does not change
 *   under ON; under ALL its columns reach the tallest one. Opening spaces stay an indent, spaces
 *   after the last letter hang past the end.
 * - Scaled letters (letter scaling) spread with their own advances; scales and advances stay.
 * - [TextRenderer] draws (and outlines, for export) the justified columns: the last letter of a
 *   spread column moves down by exactly the free space; the last column draws the same pixels.
 */
@RunWith(RobolectricTestRunner::class)
class VerticalJustifyTest {

    /** Latin cells advance 6 px a letter (rotated, mixed style); CJK cells one em. */
    private val latin = { s: String -> 6f * s.length }
    private val em = 10f

    private fun layout(text: String, justify: TextJustify, wrap: Float = 0f, align: TextAlign = TextAlign.START, spacing: Float = 0f): VerticalLayoutResult =
        VerticalTextLayout.layout(text, em, spacing, 1.5f, align, latin, wrapLength = wrap, justify = justify)

    private fun column(r: VerticalLayoutResult, col: Int): List<VerticalGlyph> = r.glyphs.filter { it.column == col }

    private fun top(g: VerticalGlyph) = g.cy - g.advance / 2f
    private fun bottom(g: VerticalGlyph) = g.cy + g.advance / 2f

    /** The letters (not spaces) of a column. */
    private fun letters(gs: List<VerticalGlyph>) = gs.filter { it.text.isNotBlank() }

    private val samples = listOf(
        "ab cd ef gh ij kl" to 40f,
        "ab cd ef gh\nij kl mn op qr" to 50f,
        "abcdefghij klm" to 40f,
        "あいうえおかきく" to 35f,
        "日本語 の テキスト を 揃える" to 45f,
        "  ab cd ef gh" to 40f,
        "ab cd\nabcdefghijk" to 0f,
        "ab\n\ncd ef" to 0f,
        "" to 0f,
    )

    @Test
    fun breaksNeverChange() {
        for ((text, wrap) in samples) for (align in TextAlign.entries) {
            val off = layout(text, TextJustify.OFF, wrap, align)
            for (j in listOf(TextJustify.ON, TextJustify.ALL)) {
                val r = layout(text, j, wrap, align)
                val where = "\"$text\" at $wrap, $align, $j"
                assertEquals(where, off.columns, r.columns)
                assertEquals(where, off.width, r.width, 1e-4f)
                assertEquals(where, off.height, r.height, 1e-4f)
                assertEquals(where, off.glyphs.map { it.text to it.column }, r.glyphs.map { it.text to it.column })
                assertEquals(where, off.glyphs.map { it.cx }, r.glyphs.map { it.cx })
                assertEquals(where, off.glyphs.map { it.advance }, r.glyphs.map { it.advance })
                // Never out of the block (spaces after the last letter may hang).
                for (g in letters(r.glyphs)) assertTrue("$where: ${g.text} at ${g.cy}", top(g) >= -1e-3f && bottom(g) <= r.height + 1e-3f)
                // Top to bottom in each column.
                for (c in 0 until r.columns) column(r, c).zipWithNext { a, b -> assertTrue("$where: ${a.text} before ${b.text}", b.cy > a.cy) }
            }
        }
    }

    @Test
    fun onSpreadsEveryColumnButTheLastOfItsLine() {
        // Line 1 wraps into "ab cd" / "ef gh" / "ij kl" (30 px each), at 40 px.
        val wrap = 40f
        for (align in TextAlign.entries) {
            val off = layout("ab cd ef gh ij kl", TextJustify.OFF, wrap, align)
            val on = layout("ab cd ef gh ij kl", TextJustify.ON, wrap, align)
            assertEquals(3, on.columns)
            assertEquals(wrap, on.height, 1e-4f)
            for (c in 0..1) {
                val gs = column(on, c)
                assertEquals("$align: column $c starts at the top", 0f, top(gs.first()), 1e-3f)
                assertEquals("$align: column $c ends at the bottom", wrap, bottom(gs.last()), 1e-3f)
                // The word gap takes the 10 px; letters of a word keep their 6 px.
                assertEquals(6f, gs[1].cy - gs[0].cy, 1e-3f)
                assertEquals(6f, gs[4].cy - gs[3].cy, 1e-3f)
                assertEquals(16f, gs[3].cy - gs[2].cy, 1e-3f)
            }
            assertEquals("$align: the last column keeps its alignment", column(off, 2).map { it.cy }, column(on, 2).map { it.cy })
            val all = layout("ab cd ef gh ij kl", TextJustify.ALL, wrap, align)
            assertEquals("$align: ALL spreads the last column too", wrap, bottom(column(all, 2).last()), 1e-3f)
            assertEquals(0f, top(column(all, 2).first()), 1e-3f)
        }
        // Every line ends with its own last column: "ij kl mn op qr" is line 2.
        val two = layout("ab cd ef gh\nij kl mn op qr", TextJustify.ON, 50f)
        val off = layout("ab cd ef gh\nij kl mn op qr", TextJustify.OFF, 50f)
        assertEquals(4, two.columns)
        assertEquals("ab cd ef", column(two, 0).joinToString("") { it.text })
        assertEquals(50f, bottom(column(two, 0).last()), 1e-3f)
        assertEquals("line 1's last column", column(off, 1).map { it.cy }, column(two, 1).map { it.cy })
        assertEquals(50f, bottom(column(two, 2).last()), 1e-3f)
        assertEquals("line 2's last column", column(off, 3).map { it.cy }, column(two, 3).map { it.cy })
    }

    /** Scaled letters (v1.6 letter scaling, the renderer's scaled block) spread the same way: their own advances, the block height. */
    @Test
    fun scaledLettersSpreadToo() {
        val text = "ab cd ef gh ij kl mn op"
        val factors = FloatArray(text.length) { if (it < 6) 1f else 0.5f }
        fun scaled(j: TextJustify) = VerticalTextLayout.layout(text, em, 0f, 1.5f, TextAlign.CENTER, latin, wrapLength = 30f, factors = factors, justify = j)
        val off = scaled(TextJustify.OFF)
        val on = scaled(TextJustify.ON)
        assertTrue("several columns: ${off.columns}", off.columns >= 2)
        assertEquals(off.columns, on.columns)
        assertEquals(off.height, on.height, 1e-4f)
        assertEquals("the scales stay", off.glyphs.map { it.scale }, on.glyphs.map { it.scale })
        assertEquals("the advances stay", off.glyphs.map { it.advance }, on.glyphs.map { it.advance })
        var spread = 0
        for (c in 0 until on.columns - 1) {
            val ls = letters(column(on, c))
            val inner = column(on, c).dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
            if (inner.none { it.text.isBlank() }) continue
            assertEquals("column $c starts at the top", 0f, top(ls.first()), 1e-3f)
            assertEquals("column $c ends at the bottom", on.height, bottom(ls.last()), 1e-3f)
            spread++
        }
        assertTrue("a column spread", spread >= 1)
        assertEquals("the last column keeps its alignment", column(off, on.columns - 1).map { it.cy }, column(on, on.columns - 1).map { it.cy })
    }

    @Test
    fun letterSpacingStaysAndTheGapsShareTheSpace() {
        // "ab cd ef" / "gh": two gaps in the first column share the free space.
        val wrap = 60f
        val on = layout("ab cd ef gh", TextJustify.ON, wrap, spacing = 0.1f)
        val gs = column(on, 0)
        assertEquals("ab cd ef", gs.joinToString("") { it.text })
        assertEquals(wrap, bottom(gs.last()), 1e-3f)
        assertEquals("a letter spacing of 1 px stays", 7f, gs[1].cy - gs[0].cy, 1e-3f)
        assertEquals(7f, gs[7].cy - gs[6].cy, 1e-3f)
        assertEquals("both gaps grow alike", gs[3].cy - gs[2].cy, gs[6].cy - gs[5].cy, 1e-3f)
    }

    @Test
    fun cjkSpreadsBetweenCellsAndLatinWordsWaitForAll() {
        // 8 cells of one em at 35 px: columns of 3, 3, 2 cells; 5 px over 2 gaps in the first two.
        val on = layout("あいうえおかきく", TextJustify.ON, 35f)
        assertEquals(3, on.columns)
        for (c in 0..1) {
            val gs = column(on, c)
            assertEquals(listOf(5f, 17.5f, 30f), gs.map { it.cy })
        }
        assertEquals("the last column keeps its alignment", listOf(5f, 15f), column(on, 2).map { it.cy })
        // A one-word Latin column (no gap, no CJK) keeps its alignment under ON.
        val word = "abcdefghij klm"
        val off = layout(word, TextJustify.OFF, 40f, TextAlign.CENTER)
        val wordOn = layout(word, TextJustify.ON, 40f, TextAlign.CENTER)
        assertEquals("abcdef", column(wordOn, 0).joinToString("") { it.text })
        assertEquals("a one-word column under ON", column(off, 0).map { it.cy }, column(wordOn, 0).map { it.cy })
        // Under ALL it spreads between its letters: 4 px over 5 gaps.
        val wordAll = layout(word, TextJustify.ALL, 40f, TextAlign.CENTER)
        val gs = column(wordAll, 0)
        assertEquals(0f, top(gs.first()), 1e-3f)
        assertEquals(40f, bottom(gs.last()), 1e-3f)
        for (k in 1 until gs.size) assertEquals(6.8f, gs[k].cy - gs[k - 1].cy, 1e-3f)
    }

    @Test
    fun pointTextAndSpaces() {
        // No fixed height: each line is one column, the last of its line.
        val text = "ab cd\nabcdefghijk"
        assertEquals(layout(text, TextJustify.OFF).glyphs.map { it.cy }, layout(text, TextJustify.ON).glyphs.map { it.cy })
        val all = layout(text, TextJustify.ALL)
        assertEquals("the tallest column", 66f, all.height, 1e-4f)
        assertEquals("ALL: the short column reaches the tallest", 66f, bottom(column(all, 0).last()), 1e-3f)
        assertEquals(layout(text, TextJustify.OFF).glyphs.filter { it.column == 1 }.map { it.cy }, column(all, 1).map { it.cy })
        // Opening spaces stay an indent; spaces after the last letter hang past the end.
        val indented = layout("  ab cd  \nabcdefghijk", TextJustify.ALL)
        val gs = column(indented, 0)
        assertEquals("the indent", 15f, gs[2].cy, 1e-3f)
        val d = gs.last { it.text == "d" }
        assertEquals("the last letter ends at the bottom", 66f, bottom(d), 1e-3f)
        assertTrue("the spaces after it hang", bottom(gs.last()) > 66f)
    }

    // --- The renderer (upright cells of one em: 20 px) ---

    private val size = 20f

    /** "ab cd" / "efghijklmn" / "op" in a 200 px column box, upright. */
    private fun block(justify: TextJustify): TextBlock {
        val spec = TextSpec(sizePx = size, vertical = true, box = TextBoxSpec(height = 200f), justify = justify)
        return TextRenderer.prepare(TextItem("ab cd efghijklmnop", spec, 100f, 100f)).block!!
    }

    private fun pixels(b: TextBlock): Triple<IntArray, Int, Int> {
        val w = kotlin.math.ceil(b.width).toInt()
        val h = kotlin.math.ceil(b.height).toInt() + 2
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        b.draw(Canvas(bmp))
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Triple(px, w, h)
    }

    /** The lowest row with ink in x [x0, x1), or -1. */
    private fun lowestInk(p: Triple<IntArray, Int, Int>, x0: Int, x1: Int): Int {
        val (px, w, h) = p
        for (y in h - 1 downTo 0) for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) if ((px[y * w + x] ushr 24) > 40) return y
        return -1
    }

    @Test
    fun theRendererDrawsAndOutlinesTheSpreadColumns() {
        val off = block(TextJustify.OFF)
        val on = block(TextJustify.ON)
        assertEquals("three columns either way", 3, on.lineCount)
        assertEquals(off.lineCount, on.lineCount)
        assertEquals(off.width, on.width, 1e-3f)
        assertEquals(off.height, on.height, 1e-3f)
        val po = pixels(off)
        val pn = pixels(on)
        val w = po.second
        // The first column (the rightmost): "ab cd" spread to the 200 px column, "d" 100 px lower.
        val right = w - size.toInt()
        val lowOff = lowestInk(po, right, w)
        val lowOn = lowestInk(pn, right, w)
        assertTrue("ink in the first column: $lowOff", lowOff in 80 until 100)
        assertEquals("the last letter moves down by the free space", 100, lowOn - lowOff)
        // The last column ("op", the last of the line) draws the same pixels.
        val pitch = (size * 1.2f).toInt()
        val left = w - size.toInt() - 2 * pitch
        for (y in 0 until po.third) for (x in left.coerceAtLeast(0) until (left + size.toInt() - 2)) {
            assertEquals("the last column at $x, $y", po.first[y * w + x], pn.first[y * w + x])
        }
        assertFalse("the drawings differ", po.first.contentEquals(pn.first))
        // The export's outlines follow the drawing.
        val oo = outlinePixels(off, w, po.third)
        val on2 = outlinePixels(on, w, po.third)
        assertEquals("the outline of the last letter moves down alike", 100, lowestInk(on2, right, w) - lowestInk(oo, right, w))
        assertTrue("the outline lies on the drawing", kotlin.math.abs(lowestInk(on2, right, w) - lowOn) <= 1)
    }

    /** [b]'s glyph outlines (as the export takes them) filled into a [w] x [h] bitmap. */
    private fun outlinePixels(b: TextBlock, w: Int, h: Int): Triple<IntArray, Int, Int> {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        Canvas(bmp).drawPath(b.glyphOutline()!!, Paint().apply { isAntiAlias = true; color = Color.BLACK })
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Triple(px, w, h)
    }
}
