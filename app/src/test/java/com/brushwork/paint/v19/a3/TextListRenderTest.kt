package com.brushwork.paint.v19.a3

import android.graphics.Canvas
import android.text.TextPaint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.ListMarkerLayout
import com.brushwork.paint.tools.text.ListMarkers
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBlock
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextExport
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextNumbering
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.WrapLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * v1.9 A3 step 5 (R7 b): bullets and numbering through the renderer.
 *
 * - Every line keeps the markers' column: the widest marker plus the gap; a marker ends the gap
 *   before its paragraph's first line, on its baseline (wrapped lines hang under the text).
 * - "Left" and justified text start every line at the column; centred and right-aligned text
 *   keep the column and the marker sits just before the first line's text.
 * - Numbers count from "First number"; a paragraph with nothing to read is neither marked nor
 *   counted; the frames of a linked story go on counting, and a frame opening in the middle of a
 *   paragraph has no marker there (C22).
 * - Markers are in the text colour; a thickness adds ink; their own size and font.
 * - Vertical text draws no list; text without a list keeps v1.8's route and pixels.
 * - SVG runs: the markers come first, one run each with its own look.
 */
@RunWith(RobolectricTestRunner::class)
class TextListRenderTest {

    private val paras = listOf(
        "Brushwork numbers every paragraph of a list and hangs its wrapped lines under the text.",
        "Short one.",
        "A third paragraph, long enough to wrap onto a second line in the box.",
    )
    private val text = paras.joinToString("\n")

    private fun numbered(start: Int = 1, style: TextNumbering = TextNumbering.DECIMAL_DOT) =
        TextListSpec(type = TextListType.NUMBERS, numbering = style, start = start)

    private val bullets = TextListSpec(type = TextListType.BULLETS)

    private fun boxed(list: TextListSpec, align: TextAlign = TextAlign.START, t: String = text, justify: TextJustify = TextJustify.OFF) =
        TextItem(t, TextSpec(sizePx = 24f, align = align, justify = justify, box = TextBoxSpec(width = 320f), list = list), 220f, 220f)

    private fun block(item: TextItem): TextBlock = TextRenderer.prepare(item).block!!

    private fun markers(b: TextBlock): List<ListMarkerLayout.Marker> = b.listMarkers?.markers.orEmpty()

    /** Lines of [b] that open a paragraph of [source] (indices from [offset]). */
    private fun openers(b: TextBlock, source: String, offset: Int = 0): List<WrapLine> =
        b.wrapLines!!.filter { it.end > it.start && (offset + it.start == 0 || source[offset + it.start - 1] == '\n') }

    private fun width(m: ListMarkerLayout.Marker, spec: TextSpec): Float = TextPaint().apply {
        typeface = TextRenderer.typeface(ListMarkers.markerFontSpec(spec))
        textSize = m.sizePx
        isAntiAlias = true
        isSubpixelText = true
    }.measureText(m.text)

    private fun crc(item: TextItem, w: Int = 520, h: Int = 440): Long {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        TextRenderer.drawItem(Canvas(bmp), item, TextRenderer.prepare(item), null)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val buf = ByteBuffer.allocate(px.size * 4)
        buf.asIntBuffer().put(px)
        return CRC32().apply { update(buf.array()) }.value
    }

    @Test
    fun theColumnIsTheWidestMarkerPlusTheGap() {
        // Ten paragraphs: "10." is the widest marker.
        val ten = (1..10).joinToString("\n") { "Item $it" }
        val item = boxed(numbered(), t = ten)
        val b = block(item)
        val ms = markers(b)
        assertEquals((1..10).map { "$it." }, ms.map { it.text })
        val gap = 0.5f * 24f
        val widest = ms.maxOf { width(it, item.spec) }
        val indent = ListMarkers.plan(item.spec, ten, null)!!.indent
        assertEquals(widest + gap, indent, 0.01f)
        val open = openers(b, ten)
        assertEquals(10, open.size)
        for ((k, l) in open.withIndex()) {
            assertEquals("START: the line starts at the column", indent, l.x, 0.01f)
            val m = ms[k]
            assertEquals("${m.text} ends the gap before its line", l.x - gap, m.x + width(m, item.spec), 0.05f)
            assertEquals(l.baseline, m.baseline, 0f)
            assertEquals(24f, m.sizePx, 0f)
        }
        // The widest marker starts at the column's left; the others are right-aligned in it.
        assertEquals(0f, ms.last().x, 0.05f)
        assertTrue(ms.first().x > 0f)
        // The gap is in em of the text size.
        val wide = block(boxed(numbered().copy(gapEm = 2f), t = ten))
        assertEquals(widest + 48f, wide.wrapLines!![0].x, 0.01f)
    }

    @Test
    fun wrappedLinesHangUnderTheText() {
        for (justify in listOf(TextJustify.OFF, TextJustify.ON)) {
            val item = boxed(bullets, justify = justify)
            val b = block(item)
            val lines = b.wrapLines!!.filter { it.end > it.start }
            assertTrue("the paragraphs wrap", lines.size > paras.size)
            val indent = lines[0].x
            assertTrue(indent > 0f)
            for (l in lines) assertEquals("$justify: every line starts at the column", indent, l.x, 0.01f)
            assertEquals("one bullet per paragraph", paras.size, markers(b).size)
            assertTrue(markers(b).all { it.text == "•" })
            // The text area keeps its width: lines fit in it after the column.
            for (l in lines) assertTrue(l.x + l.width <= b.contentWidth + 0.5f)
        }
    }

    @Test
    fun centredAndRightAlignedTextHaveTheMarkerJustBeforeTheText() {
        for (align in listOf(TextAlign.CENTER, TextAlign.END)) {
            val item = boxed(numbered(), align)
            val b = block(item)
            val indent = ListMarkers.plan(item.spec, text, null)!!.indent
            val ms = markers(b)
            val open = openers(b, text)
            assertEquals(paras.size, ms.size)
            for ((k, l) in open.withIndex()) {
                assertTrue("$align: the line keeps the column", l.x >= indent - 0.01f)
                assertEquals("$align: the marker sits just before the text", l.x - 12f, ms[k].x + width(ms[k], item.spec), 0.05f)
            }
            // Right-aligned lines end at the box's right edge.
            if (align == TextAlign.END) for (l in b.wrapLines!!.filter { it.end > it.start }) assertEquals(b.contentWidth, l.x + l.width, 0.5f)
        }
    }

    @Test
    fun numbersCountFromTheFirstNumberSkippingBlankParagraphs() {
        val t = "Alpha\n\n   \nBeta\nGamma\n"
        val b = block(boxed(numbered(start = 5), t = t))
        assertEquals(listOf("5.", "6.", "7."), markers(b).map { it.text })
        val open = openers(b, t)
        val baselines = open.filter { t.substring(it.start, it.end).isNotBlank() }.map { it.baseline }
        assertEquals(baselines, markers(b).map { it.baseline })
        // Every style; bullets don't count.
        assertEquals(listOf("iii.", "iv.", "v."), markers(block(boxed(numbered(3, TextNumbering.LOWER_ROMAN), t = t))).map { it.text })
        assertEquals(listOf("③", "④", "⑤"), markers(block(boxed(numbered(3, TextNumbering.CIRCLED), t = t))).map { it.text })
        assertEquals(listOf("★", "★", "★"), markers(block(boxed(bullets.copy(bullet = "★"), t = t))).map { it.text })
        // Point text (no box) takes the column into its width.
        val point = TextItem("One\nTwo", TextSpec(sizePx = 24f, list = numbered()), 100f, 100f)
        val plain = TextItem("One\nTwo", TextSpec(sizePx = 24f), 100f, 100f)
        val indent = ListMarkers.plan(point.spec, point.text, null)!!.indent
        assertEquals(block(plain).contentWidth + indent, block(point).contentWidth, 1.01f)
    }

    @Test
    fun framesOfAStoryGoOnCounting() {
        val story = (1..6).joinToString("\n") { "Paragraph $it has a few words to wrap in a narrow frame." }
        val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 260f, minHeight = 150f), list = numbered())
        val first = TextItem(story, spec, 200f, 200f, thread = TextThreadSpec(storyId = 9, index = 0, story = story, start = 0, end = story.length, overset = true))
        val end = TextRenderer.frameEnd(first)
        assertTrue("the first frame holds part of the story", end in 1 until story.length)
        val f1 = first.copy(text = story.substring(0, end), thread = first.thread.copy(end = end))
        val m1 = markers(block(f1)).map { it.text }
        assertTrue(m1.isNotEmpty())
        assertEquals((1..m1.size).map { "$it." }, m1)
        val f2 = first.copy(
            text = story.substring(end),
            spec = spec.copy(box = spec.box.copy(minHeight = 2000f)),
            thread = first.thread.copy(index = 1, start = end, end = story.length, overset = false),
        )
        assertEquals(story.length, TextRenderer.frameEnd(f2))
        val b2 = block(f2)
        val m2 = markers(b2)
        val opensMidParagraph = story[end - 1] != '\n'
        val firstLine = b2.wrapLines!!.first { it.end > it.start }
        if (opensMidParagraph) {
            assertTrue("no marker on a line that goes on with a paragraph", m2.none { it.baseline == firstLine.baseline })
        }
        val counted = story.substring(0, end).count { it == '\n' } + 1 - (if (opensMidParagraph) 0 else 1)
        val expected = (counted + 1..6).map { "$it." }
        assertEquals("the second frame goes on counting", expected, m2.map { it.text })
        assertEquals(6, m1.size + m2.size)
        // The column is the story's widest marker: both frames' lines start at it.
        assertEquals(block(f1).wrapLines!![0].x, firstLine.x, 0.01f)
    }

    @Test
    fun markersAreInkInTheTextColourAndThicken() {
        val red = 0xFFD02020.toInt()
        val item = TextItem("Mark\nThese", TextSpec(sizePx = 40f, color = red, list = bullets.copy(bullet = "▪")), 0f, 0f)
        fun ink(it: TextItem): Pair<Int, Set<Int>> {
            val b = block(it)
            val bmp = BitmapUtils.createLayerBitmap(b.width.toInt() + 20, b.height.toInt() + 20)
            val c = Canvas(bmp)
            c.translate(10f, 10f)
            b.draw(c)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val col = (10f + b.inset + b.wrapLines!![0].x - 2f).toInt()
            var n = 0
            val opaque = HashSet<Int>()
            for (y in 0 until bmp.height) for (x in 0 until col) {
                val p = px[y * bmp.width + x]
                if ((p ushr 24) > 0) n++
                if ((p ushr 24) == 255) opaque += p
            }
            return n to opaque
        }
        val (thin, colours) = ink(item)
        assertTrue("the markers are drawn", thin > 0)
        assertEquals("in the text colour", setOf(red), colours)
        val (thick, _) = ink(item.copy(spec = item.spec.copy(list = item.spec.list.copy(markerWeight = 1f))))
        assertTrue("thickness adds ink: $thin -> $thick", thick > thin)
        // Size: half and double the text's.
        val b = block(item.copy(spec = item.spec.copy(list = item.spec.list.copy(markerScale = 2f))))
        assertEquals(80f, markers(b)[0].sizePx, 0f)
        // Own font.
        val serif = item.copy(spec = item.spec.copy(list = item.spec.list.copy(markerFont = TextFont.SERIF)))
        assertEquals(TextRenderer.typeface(item.spec.copy(font = TextFont.SERIF)), block(serif).listMarkers!!.typeface)
        // Scaled letters: a marker has its paragraph's first letter's size.
        val scaled = TextItem("Big\nsmall", TextSpec(sizePx = 40f, letterScale = LetterScaleSpec(smallestPercent = 50f), list = bullets), 0f, 0f)
        val sm = markers(block(scaled))
        assertEquals(2, sm.size)
        assertEquals("the first letter is full size", 40f, sm[0].sizePx, 0.5f)
        assertTrue("the first paragraph's marker is bigger: ${sm[0].sizePx} > ${sm[1].sizePx}", sm[0].sizePx > sm[1].sizePx)
    }

    @Test
    fun verticalTextAndTextWithoutAListKeepTheirPixels() {
        val vertical = TextItem("縦書き\nテキスト", TextSpec(sizePx = 32f, vertical = true), 200f, 200f)
        assertEquals("vertical text draws no list", crc(vertical), crc(vertical.copy(spec = vertical.spec.copy(list = numbered()))))
        assertNull(block(vertical.copy(spec = vertical.spec.copy(list = numbered()))).listMarkers)
        // No list: StaticLayout, v1.8's route.
        val plain = boxed(TextListSpec())
        assertNotNull(block(plain).staticLayout)
        assertNull(block(plain).listMarkers)
        val listed = boxed(bullets)
        assertNull(block(listed).staticLayout)
        assertNotEquals(crc(plain), crc(listed))
        // An empty text or one with nothing to read draws no marker.
        assertNull(block(boxed(bullets, t = "")).listMarkers)
        assertNull(block(boxed(bullets, t = " \n ")).listMarkers)
    }

    @Test
    fun svgRunsCarryTheMarkersFirst() {
        val item = boxed(numbered())
        val runs = TextExport.lines(item)!!
        val b = block(item)
        val ms = markers(b)
        assertEquals(ms.map { it.text }, runs.take(ms.size).map { it.text })
        for ((k, m) in ms.withIndex()) {
            val r = runs[k]
            assertEquals(m.x + b.inset, r.x, 0f)
            assertEquals(m.baseline + b.inset, r.baseline, 0f)
            assertEquals(m.sizePx, r.paintSpec.spec.sizePx, 0f)
            assertEquals(TextListSpec(), r.paintSpec.spec.list)
        }
        // The text's runs read as its lines (a line leaves out the spaces it ends with).
        fun letters(s: String) = s.filterNot { it.isWhitespace() }
        assertEquals(letters(text), letters(runs.drop(ms.size).joinToString("") { it.text }))
        // A thickened marker is an outline in the text colour.
        val thick = TextExport.lines(boxed(numbered().copy(markerWeight = 1f)))!!
        assertEquals(0.08f * 24f, thick[0].paintSpec.spec.strokeWidthPx, 0.001f)
        assertEquals(item.spec.color, thick[0].paintSpec.spec.strokeColor)
        // Runs can't carry a thickened marker on outlined text, nor an imported marker font.
        val outlined = boxed(numbered().copy(markerWeight = 1f)).let { it.copy(spec = it.spec.copy(strokeWidthPx = 2f)) }
        assertNull(TextExport.lines(outlined))
        assertNotNull(TextExport.outlineParts(outlined))
        assertNull(TextExport.lines(boxed(numbered().copy(markerFontId = "font-0123456789abcdef", markerFontName = "Mine"))))
    }
}
