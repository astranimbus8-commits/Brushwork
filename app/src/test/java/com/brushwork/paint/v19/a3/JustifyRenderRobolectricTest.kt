package com.brushwork.paint.v19.a3

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBlock
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.WrapLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * v1.9 A3 step 2 (R4): justified text through the renderer. Text that isn't justified keeps
 * StaticLayout (and its pixels); justified text in a box takes the WrapLayout route with the same
 * line breaks, its lines' ink reaching the box's right edge while the last line stays ragged;
 * frames justify their last line when the paragraph goes on in the next frame; kerns and scaled
 * letters add up with justify.
 */
@RunWith(RobolectricTestRunner::class)
class JustifyRenderRobolectricTest {

    private val para = "Brushwork sets justified lines flush with both edges of the box, word by word, while the last line keeps its alignment."

    private fun boxed(j: TextJustify, align: TextAlign = TextAlign.START, text: String = para) =
        TextItem(text, TextSpec(sizePx = 24f, align = align, justify = j, box = TextBoxSpec(width = 300f)), 200f, 200f)

    private fun block(item: TextItem): TextBlock = TextRenderer.prepare(item).block!!

    private fun crc(item: TextItem): Long {
        val bmp = BitmapUtils.createLayerBitmap(500, 400)
        TextRenderer.drawItem(Canvas(bmp), item, TextRenderer.prepare(item), null)
        val px = IntArray(500 * 400)
        bmp.getPixels(px, 0, 500, 0, 0, 500, 400)
        val buf = ByteBuffer.allocate(px.size * 4)
        buf.asIntBuffer().put(px)
        return CRC32().apply { update(buf.array()) }.value
    }

    /** Where line [l] of [b]'s last letter ends (text area coordinates), justified offsets included. */
    private fun rightEdge(b: TextBlock, l: WrapLine): Float {
        val j = b.justified!!
        return l.x + j.measured.width(l.start, l.end) + j.offsets[l.end - 1]
    }

    @Test
    fun routesAndLineBreaks() {
        val off = block(boxed(TextJustify.OFF))
        assertNotNull("not justified: StaticLayout, as v1.8", off.staticLayout)
        assertNull(off.justified)
        val on = block(boxed(TextJustify.ON))
        assertNull(on.staticLayout)
        assertNotNull(on.justified)
        val lines = on.wrapLines!!
        val sl = off.staticLayout!!
        assertEquals("the same lines", sl.lineCount, lines.size)
        for (i in lines.indices) {
            assertEquals("line $i start", sl.getLineStart(i), lines[i].start)
            assertEquals("line $i end", sl.getLineVisibleEnd(i), lines[i].end)
            assertEquals("line $i baseline", sl.getLineBaseline(i).toFloat(), lines[i].baseline, 0.01f)
        }
        assertEquals(off.width, on.width, 0f)
        assertEquals(off.height, on.height, 0f)
        // Point text with "Justify lines": every line is its paragraph's last, so nothing moves and
        // StaticLayout keeps the pixels.
        val point = TextItem("Two lines\nof text", TextSpec(sizePx = 24f, justify = TextJustify.ON), 200f, 200f)
        assertNotNull(block(point).staticLayout)
        assertEquals(crc(point.copy(spec = point.spec.copy(justify = TextJustify.OFF))), crc(point))
        // "Justify the last line too" spreads point text to its widest line.
        val all = block(point.copy(spec = point.spec.copy(justify = TextJustify.ALL)))
        assertNull(all.staticLayout)
        val widest = all.wrapLines!!.maxOf { it.width }
        for (l in all.wrapLines!!) assertEquals(widest, rightEdge(all, l), 0.01f)
        // Vertical text spreads its columns in VerticalTextLayout (step 12, VerticalJustifyTest), never these lines.
        assertNull(block(boxed(TextJustify.ALL).let { it.copy(spec = it.spec.copy(vertical = true)) }).justified)
    }

    @Test
    fun justifiedInkReachesTheRightEdge() {
        for (align in TextAlign.entries) {
            val item = boxed(TextJustify.ON, align)
            val b = block(item)
            val lines = b.wrapLines!!
            val bmp = Bitmap.createBitmap(b.width.toInt() + 40, b.height.toInt() + 40, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(20f, 20f)
            b.draw(c)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            fun inkRight(l: WrapLine): Int {
                var r = -1
                val top = (20f + b.inset + l.baseline - 17f).toInt()
                val bottom = (20f + b.inset + l.baseline).toInt()
                for (y in top until bottom) for (x in 0 until bmp.width) if ((px[y * bmp.width + x] ushr 24) > 128) r = maxOf(r, x)
                return r - 20
            }
            val edge = b.inset + b.contentWidth
            for (l in lines.dropLast(1)) {
                assertEquals("$align line '${para.substring(l.start, l.end)}' ends at the box edge", edge, rightEdge(b, l) + b.inset, 1.5f)
                val ink = inkRight(l)
                assertTrue("$align ink at $ink, edge $edge", ink >= edge - 4f && ink <= edge + 2f)
            }
            // The last line keeps its alignment: ragged.
            val last = lines.last()
            val offB = block(boxed(TextJustify.OFF, align))
            val offLast = offB.staticLayout!!
            val lastX = TextRenderer.staticLineX(offLast, offLast.lineCount - 1)
            assertEquals("$align last line keeps its place", lastX, last.x, 1f)
        }
    }

    @Test
    fun framesJustifyALastLineWhoseParagraphGoesOn() {
        val spec = TextSpec(sizePx = 24f, justify = TextJustify.ON, box = TextBoxSpec(width = 300f, minHeight = 90f))
        val whole = TextItem(para, spec, 200f, 200f, thread = TextThreadSpec(storyId = 7, index = 0, story = para, start = 0, end = para.length, overset = true))
        val end = TextRenderer.frameEnd(whole)
        assertTrue("the frame holds part of the paragraph", end in 1 until para.length)
        val frame = whole.copy(text = para.substring(0, end), thread = whole.thread.copy(end = end))
        val b = block(frame)
        assertNotNull(b.justified)
        val last = b.wrapLines!!.last { it.end > it.start }
        assertEquals("the frame's last line is justified", b.contentWidth, rightEdge(b, last), 1f)
        assertEquals(0f, last.x, 0f)
        // The next frame (tall enough for the rest) starts there and its own last line (the
        // paragraph's) keeps align.
        val next = whole.copy(
            text = para.substring(end),
            spec = spec.copy(box = spec.box.copy(minHeight = 400f)),
            thread = whole.thread.copy(index = 1, start = end, end = para.length, overset = false),
        )
        assertEquals("the next frame holds the rest", para.length, TextRenderer.frameEnd(next))
        val nb = block(next)
        val nl = nb.wrapLines!!.last { it.end > it.start }
        assertTrue(nl.lastOfParagraph)
        assertEquals(0f, nl.x, 0f)
        nb.justified?.let { j -> for (k in nl.start until nl.end) assertEquals(0f, j.offsets[k], 0f) }
    }

    @Test
    fun kernsAndScaledLettersAddUpWithJustify() {
        // A kern inside the first line: the justified line still ends at the box edge.
        val kerned = boxed(TextJustify.ON).copy(kerns = listOf(TextKern(2, 300)))
        val kb = block(kerned)
        val first = kb.wrapLines!!.first()
        assertEquals(kb.contentWidth, rightEdge(kb, first), 1f)
        // Scaled letters: their lines spread too.
        val scaled = boxed(TextJustify.ON).let { it.copy(spec = it.spec.copy(letterScale = LetterScaleSpec(smallestPercent = 60f))) }
        val sb = block(scaled)
        assertNotNull(sb.justified)
        val sl = sb.wrapLines!!
        for (l in sl.dropLast(1)) assertEquals(sb.contentWidth, rightEdge(sb, l), 1f)
    }
}
