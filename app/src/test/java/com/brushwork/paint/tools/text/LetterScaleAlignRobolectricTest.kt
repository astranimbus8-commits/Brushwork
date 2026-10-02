package com.brushwork.paint.tools.text

import com.brushwork.paint.tools.text.LetterScaleFixtures.BLACK
import com.brushwork.paint.tools.text.LetterScaleFixtures.lettersLeftToRight
import com.brushwork.paint.tools.text.LetterScaleFixtures.lettersTopToBottom
import com.brushwork.paint.tools.text.LetterScaleFixtures.render
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.6 §3.5(d) "LetterScaleAlignRobolectricTest" (real Skia): the three alignments of scaled
 * letters, measured on the drawn ink of capital H's (flat tops and bottoms):
 * - Center: every letter's cap centre on one horizontal line (the user's ELTON JOHN example);
 * - Baseline: every letter's bottom on the base line;
 * - Top: every letter's top on one line;
 * each within 1 px. The letters really shrink from the beginning to the end (or grow), and
 * vertical text keeps every letter centred on its column axis.
 */
@RunWith(RobolectricTestRunner::class)
class LetterScaleAlignRobolectricTest {

    private val w = 900
    private val h = 260

    private fun item(align: LetterScaleAlign, direction: LetterScaleDirection = LetterScaleDirection.START_TO_END) = TextItem(
        "HHHHHH",
        TextSpec(
            sizePx = 90f, color = BLACK, letterSpacing = 0.25f,
            letterScale = LetterScaleSpec(smallestPercent = 40f, align = align, direction = direction),
        ),
        cx = w / 2f, cy = h / 2f,
    )

    private fun letters(align: LetterScaleAlign, direction: LetterScaleDirection = LetterScaleDirection.START_TO_END) =
        lettersLeftToRight(render(item(align, direction), w, h)).also { assertEquals("six separate letters: $it", 6, it.size) }

    private fun assertSame(what: String, values: List<Float>) {
        val spread = values.max() - values.min()
        assertTrue("$what within 1 px: $values", spread <= 1f)
    }

    @Test
    fun centerKeepsTheCapCentresOnOneLine() {
        val l = letters(LetterScaleAlign.CENTER)
        assertSame("cap centres", l.map { (it.top + it.bottom) / 2f })
        // The letters shrink from 100 % to 40 % (heights of the H's ink).
        val heights = l.map { it.height() }
        for (i in 1 until heights.size) assertTrue("shrinking: $heights", heights[i] < heights[i - 1])
        assertEquals("the last letter is 40 % of the first", 0.4f, heights.last().toFloat() / heights.first(), 0.05f)
    }

    @Test
    fun baselineKeepsTheBottomsOnTheBaseLine() {
        val l = letters(LetterScaleAlign.BASELINE)
        assertSame("bottoms", l.map { it.bottom.toFloat() })
        assertTrue("and the tops step down", l.last().top > l.first().top + 20)
    }

    @Test
    fun topKeepsTheTopsOnOneLine() {
        val l = letters(LetterScaleAlign.TOP)
        assertSame("tops", l.map { it.top.toFloat() })
        assertTrue("and the bottoms step up", l.last().bottom < l.first().bottom - 20)
    }

    @Test
    fun endToBeginningGrowsAndTheFirstLetterKeepsItsPlace() {
        val grow = letters(LetterScaleAlign.CENTER, LetterScaleDirection.END_TO_START)
        val heights = grow.map { it.height() }
        for (i in 1 until heights.size) assertTrue("growing: $heights", heights[i] > heights[i - 1])
        assertSame("cap centres", grow.map { (it.top + it.bottom) / 2f })
        // The cap centre line is the one of a full-size H on the same baseline.
        val plain = lettersLeftToRight(render(item(LetterScaleAlign.CENTER).let { it.copy(spec = it.spec.copy(letterScale = LetterScaleSpec())) }, w, h))
        val centre = (plain[0].top + plain[0].bottom) / 2f
        assertTrue("same line as unscaled text: $centre vs ${(grow.last().top + grow.last().bottom) / 2f}", abs(centre - (grow.last().top + grow.last().bottom) / 2f) <= 1f)
    }

    @Test
    fun verticalLettersStayCentredOnTheColumn() {
        val v = TextItem(
            "HHHHH",
            TextSpec(sizePx = 60f, color = BLACK, vertical = true, letterSpacing = 0.2f, letterScale = LetterScaleSpec(smallestPercent = 40f, align = LetterScaleAlign.TOP)),
            cx = 200f, cy = 250f,
        )
        val l = lettersTopToBottom(render(v, 400, 500))
        assertEquals("five letters: $l", 5, l.size)
        assertSame("column centres (Align is ignored for vertical text)", l.map { (it.left + it.right) / 2f })
        val widths = l.map { it.width() }
        for (i in 1 until widths.size) assertTrue("shrinking down the column: $widths", widths[i] < widths[i - 1])
        // The column is shorter than unscaled: the cells shrink along it.
        val plain = lettersTopToBottom(render(v.copy(spec = v.spec.copy(letterScale = LetterScaleSpec())), 400, 500))
        assertTrue((l.last().bottom - l.first().top) < (plain.last().bottom - plain.first().top))
    }

    @Test
    fun outlinesFollowTheDrawnLetters() {
        for (a in LetterScaleAlign.entries) {
            val it = item(a)
            val drawn = render(it, w, h)
            val parts = TextExport.outlineParts(it)!!
            val shape = com.brushwork.paint.engine.BitmapUtils.createLayerBitmap(w, h)
            android.graphics.Canvas(shape).drawPath(parts.last().path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = BLACK })
            // Every letter's outline lands on its drawn ink (edges within 1 px: anti-aliasing).
            val ink = lettersLeftToRight(drawn)
            val out = lettersLeftToRight(shape)
            assertEquals(ink.size, out.size)
            for (i in ink.indices) {
                val d = ink[i]
                val o = out[i]
                assertTrue("$a letter $i: $d vs $o", abs(d.left - o.left) <= 1 && abs(d.right - o.right) <= 1 && abs(d.top - o.top) <= 1 && abs(d.bottom - o.bottom) <= 1)
            }
            val iou = LetterScaleFixtures.iou(drawn, shape)
            assertTrue("$a: outlines cover the drawn letters (IoU $iou)", iou >= 0.88f)
        }
    }
}
