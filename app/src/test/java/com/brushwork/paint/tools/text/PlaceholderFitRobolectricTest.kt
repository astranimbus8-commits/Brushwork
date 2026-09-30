package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Matrix
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Placeholder text fitted into text boxes with the real layout (StaticLayout on real Skia):
 * it fits, one more word would not, and the box's second side (height of horizontal text, width
 * of vertical text) can be set and dragged.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceholderFitRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newController(w: Int = 800, h: Int = 800): Pair<EditorController, TextTool> {
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    /** How many fill units [text] is made of (it must be exactly a run of them after [prefix]). */
    private fun unitsIn(text: String, prefix: String, kind: PlaceholderKind): Int {
        val tokens = PlaceholderText.tokens(kind, PlaceholderFit.MAX_CHARS)
        var acc = prefix
        var n = 0
        while (acc.length < text.length) {
            val t = tokens[n]
            acc += if (n == 0 && (prefix.isEmpty() || prefix.last().isWhitespace())) t.trimStart() else t
            n++
        }
        assertEquals("the fill is whole units", text, acc)
        return n
    }

    private fun nextUnit(kind: PlaceholderKind, n: Int) = PlaceholderText.tokens(kind, PlaceholderFit.MAX_CHARS)[n]

    @Test
    fun fillingAHorizontalBoxFitsAndOneMoreWordWouldOverflow() {
        val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 300f))
        val height = 200f
        for (kind in PlaceholderKind.entries) {
            val text = requireNotNull(PlaceholderFit.fill("", kind, spec, height)) { "$kind" }
            val block = TextRenderer.layout(text, spec)
            val paint = TextPaint().apply { textSize = 24f; typeface = TextRenderer.typeface(spec) }
            val fm = paint.fontMetrics
            assertTrue("$kind: ${block.lineCount} lines of ${fm.descent - fm.ascent} px in $height px", block.lineCount * (fm.descent - fm.ascent) <= height)
            assertTrue("$kind: ${block.contentHeight} <= $height", block.contentHeight <= height)
            assertTrue("$kind: several lines", block.lineCount >= 4)
            val n = unitsIn(text, "", kind)
            val more = TextRenderer.layout(text + nextUnit(kind, n), spec)
            assertTrue("$kind: one more unit overflows (${more.contentHeight})", more.contentHeight > height)
        }
        assertTrue(PlaceholderFit.fill("", PlaceholderKind.LOREM, spec, height)!!.startsWith("Lorem ipsum dolor sit amet"))
        // A taller box holds more.
        val tall = PlaceholderFit.fill("", PlaceholderKind.LOREM, spec, 400f)!!
        assertTrue(tall.length > PlaceholderFit.fill("", PlaceholderKind.LOREM, spec, 200f)!!.length * 1.6)
        // Without a fixed box there is nothing to fill.
        assertNull(PlaceholderFit.fill("", PlaceholderKind.LOREM, TextSpec(sizePx = 24f), height))
    }

    @Test
    fun fillingAVerticalBoxFillsItsColumns() {
        val spec = TextSpec(sizePx = 30f, vertical = true, lineSpacing = 1.2f, box = TextBoxSpec(height = 300f))
        val width = 150f
        val text = requireNotNull(PlaceholderFit.fill("", PlaceholderKind.JAPANESE, spec, width))
        val block = TextRenderer.layout(text, spec)
        assertTrue(block.contentWidth <= width)
        assertEquals("30 + (n-1) x 36 <= 150: 4 columns", 4, block.lineCount)
        assertEquals(300f, block.contentHeight, 0.01f)
        val n = unitsIn(text, "", PlaceholderKind.JAPANESE)
        assertTrue(TextRenderer.layout(text + nextUnit(PlaceholderKind.JAPANESE, n), spec).contentWidth > width)
        // The box minimum doesn't change the measurement, and shows the whole area.
        val boxed = spec.copy(box = spec.box.copy(minWidth = width))
        assertEquals(text, PlaceholderFit.fill("", PlaceholderKind.JAPANESE, boxed, width))
        assertEquals(width, TextRenderer.layout(text, boxed).contentWidth, 0.01f)
        assertEquals(width, TextRenderer.layout("あ", boxed).contentWidth, 0.01f)
    }

    @Test
    fun appendingFillsWhatIsLeft() {
        val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 300f))
        val text = requireNotNull(PlaceholderFit.fill("Title: ", PlaceholderKind.ENGLISH, spec, 150f))
        assertTrue(text.startsWith("Title: This is placeholder text."))
        val n = unitsIn(text, "Title: ", PlaceholderKind.ENGLISH)
        assertTrue(PlaceholderFit.extent(text, spec) <= 150f)
        assertTrue(PlaceholderFit.extent(text + nextUnit(PlaceholderKind.ENGLISH, n), spec) > 150f)
        // Text that already overflows the box leaves no room.
        val long = PlaceholderText.text(PlaceholderKind.LOREM, PlaceholderAmount.THREE_PARAGRAPHS) + " "
        assertNull(PlaceholderFit.fill(long, PlaceholderKind.ENGLISH, spec, 150f))
    }

    @Test
    fun theToolFillsItsTextBoxAndInsertsParagraphs() {
        val (c, tool) = newController()
        tool.startTextAt(400f, 400f)
        tool.updateSpec { it.copy(sizePx = 24f) }
        tool.setFixedBox(true)
        tool.setBoxLength(300f)
        tool.setFixedDepth(true)
        tool.setBoxDepth(200f)
        assertEquals(200f, tool.item!!.spec.box.minHeight, 0f)
        assertTrue(tool.insertPlaceholder(PlaceholderKind.LOREM, PlaceholderAmount.FILL, replace = true))
        val item = tool.item!!
        assertTrue(item.text.startsWith("Lorem ipsum dolor sit amet"))
        val block = tool.blockFor(item)
        assertEquals("the box keeps its area", 200f, block.contentHeight, 0.01f)
        assertEquals(300f, block.contentWidth, 0.01f)
        assertTrue(PlaceholderFit.extent(item.text, item.spec) <= 200f)

        // No fixed height yet: the fill makes the box 3/4 as tall as wide and fills that.
        tool.setFixedDepth(false)
        assertEquals(0f, tool.item!!.spec.box.minHeight, 0f)
        assertTrue(tool.insertPlaceholder(PlaceholderKind.ENGLISH, PlaceholderAmount.FILL, replace = true))
        assertEquals(225f, tool.item!!.spec.box.minHeight, 0.01f)
        assertTrue(tool.item!!.text.startsWith("This is placeholder text."))
        // Adding to a full box fills what is left, if anything.
        val full = tool.item!!
        tool.insertPlaceholder(PlaceholderKind.ENGLISH, PlaceholderAmount.FILL, replace = false)
        assertTrue(tool.item!!.text.startsWith(full.text))
        assertTrue(PlaceholderFit.extent(tool.item!!.text, tool.item!!.spec) <= 225f)
        // Text that already overflows the box: no room, nothing changes, a message says why.
        tool.setText(PlaceholderText.text(PlaceholderKind.LOREM, PlaceholderAmount.THREE_PARAGRAPHS))
        val over = tool.item!!
        c.message = null
        assertFalse(tool.insertPlaceholder(PlaceholderKind.ENGLISH, PlaceholderAmount.FILL, replace = false))
        assertEquals(over, tool.item)
        assertNotNull(c.message)
        tool.insertPlaceholder(PlaceholderKind.ENGLISH, PlaceholderAmount.FILL, replace = true)
        // Committed like any text; the stored box keeps its area.
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val stored = TextCodec.decode(c.activeLayer.textData)!!
        assertEquals(225f, stored.spec.box.minHeight, 0.01f)

        // A text without a box: a paragraph gets a comfortable wrap width, a short line doesn't.
        tool.startTextAt(400f, 300f)
        tool.updateSpec { it.copy(sizePx = 24f) }
        tool.setText("Intro")
        assertTrue(tool.insertPlaceholder(PlaceholderKind.LOREM, PlaceholderAmount.PARAGRAPH, replace = false))
        val para = tool.item!!
        assertTrue(para.text.startsWith("Intro\nLorem ipsum dolor sit amet, consectetur adipiscing elit, sed do"))
        assertEquals(minOf(800 * 0.8f, 24f * 22f), para.spec.box.width, 0.01f)
        assertTrue(tool.blockFor(para).lineCount >= 5)
        assertTrue(tool.insertPlaceholder(PlaceholderKind.JAPANESE, PlaceholderAmount.SHORT, replace = true))
        assertEquals("テキストが入ります", tool.item!!.text)
        tool.setFixedBox(false)
        assertTrue(tool.insertPlaceholder(PlaceholderKind.LOREM, PlaceholderAmount.SHORT, replace = true))
        assertEquals(0f, tool.item!!.spec.box.width, 0f)
        // "Fill" without a box inserts a paragraph.
        assertTrue(tool.insertPlaceholder(PlaceholderKind.LOREM, PlaceholderAmount.FILL, replace = true))
        assertEquals(PlaceholderText.text(PlaceholderKind.LOREM, PlaceholderAmount.PARAGRAPH), tool.item!!.text)
        tool.discard()

        // Vertical text: the box height is the wrap, its width the area.
        tool.startTextAt(400f, 400f)
        tool.updateSpec { it.copy(sizePx = 30f, vertical = true) }
        tool.setFixedBox(true)
        tool.setBoxLength(300f)
        assertTrue(tool.insertPlaceholder(PlaceholderKind.KANA, PlaceholderAmount.FILL, replace = true))
        val v = tool.item!!
        assertEquals(225f, v.spec.box.minWidth, 0.01f)
        assertTrue(PlaceholderFit.extent(v.text, v.spec) <= 225f)
        assertTrue(v.text.startsWith("あいうえお"))
        tool.discard()
    }

    @Test
    fun theBoxHeightHandleSetsTheAreaAndKeepsTheTopEdge() {
        val (_, tool) = newController(600, 600)
        val c = tool.controller
        tool.startTextAt(300f, 300f)
        tool.setText("one two three")
        tool.updateSpec { it.copy(sizePx = 24f) }
        tool.confirmEditor()
        // No fixed width: no second handle; the bottom edge is just the text.
        tool.setFixedBox(true)
        tool.setBoxLength(200f)
        val start = tool.item!!
        val b0 = tool.blockFor(start)
        val top = start.localToDoc(0f, 0f, b0.width, b0.height)
        // The handle sits in the middle of the bottom edge, 6 px (BOX_PAD at zoom 1) outside it.
        val handle = start.localToDoc(b0.width / 2f, b0.height + 6f, b0.width, b0.height)
        c.pointerDown(ToolPoint(handle.x, handle.y))
        c.pointerMove(ToolPoint(handle.x, handle.y + 60f))
        c.pointerMove(ToolPoint(handle.x, handle.y + 120f))
        c.pointerUp(ToolPoint(handle.x, handle.y + 120f))
        val item = tool.item!!
        assertEquals(b0.height + 120f, item.spec.box.minHeight, 1f)
        assertEquals("the width stays", 200f, item.spec.box.width, 0f)
        val b1 = tool.blockFor(item)
        assertEquals(b0.height + 120f, b1.height, 1f)
        val newTop = item.localToDoc(0f, 0f, b1.width, b1.height)
        assertEquals(top.x, newTop.x, 0.6f)
        assertEquals(top.y, newTop.y, 0.6f)
        // Turning the fixed width off drops the area too.
        tool.setFixedBox(false)
        assertEquals(0f, tool.item!!.spec.box.minHeight, 0f)
        // The next new text doesn't inherit the area.
        tool.setFixedBox(true)
        tool.setFixedDepth(true)
        assertTrue(tool.commitItem())
        tool.startTextAt(100f, 100f)
        assertEquals(TextBoxSpec(), tool.item!!.spec.box)
        tool.discard()
        // Vertical text, columns right to left: the handle is on the left edge.
        tool.startTextAt(300f, 300f)
        tool.setText("あいう")
        tool.updateSpec { it.copy(sizePx = 30f, vertical = true) }
        tool.confirmEditor()
        tool.setFixedBox(true)
        val vs = tool.item!!
        val vb = tool.blockFor(vs)
        val right = vs.localToDoc(vb.width, 0f, vb.width, vb.height)
        val left = vs.localToDoc(-6f, vb.height / 2f, vb.width, vb.height)
        c.pointerDown(ToolPoint(left.x, left.y))
        c.pointerMove(ToolPoint(left.x - 50f, left.y))
        c.pointerMove(ToolPoint(left.x - 90f, left.y))
        c.pointerUp(ToolPoint(left.x - 90f, left.y))
        val v = tool.item!!
        assertEquals(vb.width + 90f, v.spec.box.minWidth, 1f)
        val vb1 = tool.blockFor(v)
        val newRight = v.localToDoc(vb1.width, 0f, vb1.width, vb1.height)
        assertEquals("the right edge stays", right.x, newRight.x, 0.6f)
        assertEquals(right.y, newRight.y, 0.6f)
        assertTrue("the box grew to the left", v.cx < vs.cx)
        tool.discard()
    }
}
