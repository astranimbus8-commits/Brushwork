package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Text boxes (wrapping, background, border, rounding) and upright vertical text on real Skia. */
@RunWith(RobolectricTestRunner::class)
class TextBoxVerticalRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    private fun newController(w: Int = 400, h: Int = 400): Pair<EditorController, TextTool> {
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun inkBounds(b: Bitmap): Rect {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val r = Rect()
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if ((px[y * b.width + x] ushr 24) != 0) {
                if (r.isEmpty) r.set(x, y, x + 1, y + 1) else r.union(x, y)
            }
        }
        return r
    }

    /** Renders [text] with [spec] into a fresh bitmap (block at the origin + margin). */
    private fun render(text: String, spec: TextSpec, margin: Int = 20): Pair<TextBlock, Bitmap> {
        val block = TextRenderer.layout(text, spec)
        val bmp = BitmapUtils.createLayerBitmap(block.width.toInt() + 2 * margin, block.height.toInt() + 2 * margin)
        val item = TextItem(text, spec, margin + block.width / 2f, margin + block.height / 2f)
        TextRenderer.drawItem(Canvas(bmp), item, block, null)
        return block to bmp
    }

    // ------------------------------------------------------------------ boxes

    @Test
    fun aNarrowBoxWrapsLines() {
        val spec = TextSpec(sizePx = 20f)
        val free = TextRenderer.layout("one two three four five", spec)
        assertEquals(1, free.lineCount)
        val narrow = TextRenderer.layout("one two three four five", spec.copy(box = TextBoxSpec(width = 70f)))
        assertTrue("wraps into several lines: ${narrow.lineCount}", narrow.lineCount >= 3)
        assertEquals(70f, narrow.contentWidth, 0.5f)
        assertEquals(70f, narrow.width, 0.5f)
        assertTrue(narrow.height > free.height * 2.5f)
        // Rendered ink stays inside the box width.
        val (block, bmp) = render("one two three four five", spec.copy(box = TextBoxSpec(width = 70f)))
        val ink = inkBounds(bmp)
        assertTrue("ink $ink inside the ${block.width} px box", ink.left >= 19 && ink.right <= 20 + 71)
        // Vertical text wraps into columns at the box height.
        val v = TextRenderer.layout("ABCDEFGH", spec.copy(vertical = true, box = TextBoxSpec(height = 60f)))
        assertEquals(3, v.lineCount)
        assertEquals(60f, v.contentHeight, 0.01f)
    }

    @Test
    fun boxBackgroundBorderAndPaddingArePainted() {
        val box = TextBoxSpec(padding = 10f, fill = true, fillColor = white, borderWidth = 4f, borderColor = black)
        val spec = TextSpec(sizePx = 30f, color = red, box = box)
        val (block, bmp) = render("Hi", spec)
        val plain = TextRenderer.layout("Hi", spec.copy(box = TextBoxSpec()))
        assertEquals(plain.width + 28f, block.width, 0.01f)   // 2 x (padding 10 + border 4)
        assertEquals(plain.height + 28f, block.height, 0.01f)
        val x0 = 20; val y0 = 20
        assertEquals("border at the edge", black, bmp.getPixel(x0 + 1, y0 + block.height.toInt() / 2))
        assertEquals("background inside", white, bmp.getPixel(x0 + 7, y0 + 7))
        assertEquals("nothing outside", 0, bmp.getPixel(x0 - 3, y0 - 3))
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        assertTrue("text drawn over the box", px.any { it == red })

        // Round corners: the corner pixel is empty; square corners fill it.
        val (_, round) = render("Hi", spec.copy(box = box.copy(roundness = 1f)))
        assertEquals(0, round.getPixel(x0, y0) ushr 24)
        assertEquals(black, bmp.getPixel(x0, y0))
        // A see-through background keeps its alpha.
        val (_, glass) = render("Hi", spec.copy(box = box.copy(borderWidth = 0f, fillColor = 0x80FFFFFF.toInt())))
        assertEquals(0x80, glass.getPixel(x0 + 2, y0 + 2) ushr 24)
    }

    @Test
    fun boxIsPartOfTheCommittedLayerAndOfHitTesting() {
        val (c, tool) = newController()
        tool.startTextAt(200f, 200f)
        tool.setText("Box")
        tool.updateSpec { it.copy(sizePx = 30f) }
        tool.applyBoxPreset(TextBoxPreset.CAPTION)
        tool.confirmEditor()
        val block = tool.blockFor(tool.item!!)
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        val ink = inkBounds(layer.bitmap)
        assertEquals(block.width, ink.width().toFloat(), 1.5f)
        assertEquals(white, layer.bitmap.getPixel(200, ink.top + 5))
        // Tapping the box padding (not a glyph) still edits the text.
        c.pointerDown(ToolPoint(ink.left + 4f, ink.top + 4f))
        c.pointerUp(ToolPoint(ink.left + 4f, ink.top + 4f))
        assertEquals(layer, tool.editingLayer)
        assertTrue(TextBoxPreset.CAPTION.matches(tool.item!!.spec.box, 30f))
    }

    @Test
    fun draggingTheSideHandleSetsTheBoxWidthAndKeepsTheLeftEdge() {
        val (c, tool) = newController()
        tool.startTextAt(200f, 200f)
        tool.setText("one two three four")
        tool.updateSpec { it.copy(sizePx = 24f) }
        tool.confirmEditor()
        val start = tool.item!!
        val b0 = tool.blockFor(start)
        val leftEdge = start.localToDoc(0f, 0f, b0.width, b0.height)
        // The handle sits in the middle of the right edge, 6 px (BOX_PAD at zoom 1) outside it.
        val handle = start.localToDoc(b0.width + 6f, b0.height / 2f, b0.width, b0.height)
        c.pointerDown(ToolPoint(handle.x, handle.y))
        c.pointerMove(ToolPoint(handle.x - 60f, handle.y))
        c.pointerMove(ToolPoint(handle.x - 120f, handle.y))
        c.pointerUp(ToolPoint(handle.x - 120f, handle.y))
        val item = tool.item!!
        assertEquals(b0.width - 120f, item.spec.box.width, 1f)
        val b1 = tool.blockFor(item)
        assertTrue("wrapped: ${b1.lineCount}", b1.lineCount >= 2)
        val newLeft = item.localToDoc(0f, 0f, b1.width, b1.height)
        assertEquals(leftEdge.x, newLeft.x, 0.6f)
        assertEquals(leftEdge.y, newLeft.y, 0.6f)
        // It can't get narrower than one em.
        val h2 = item.localToDoc(b1.width + 6f, b1.height / 2f, b1.width, b1.height)
        c.pointerDown(ToolPoint(h2.x, h2.y))
        c.pointerMove(ToolPoint(h2.x - 500f, h2.y))
        c.pointerUp(ToolPoint(h2.x - 500f, h2.y))
        assertEquals(24f, tool.item!!.spec.box.width, 0.01f)
        // Resizing the text (corner handle / pinch) scales the box with it.
        val scaled = tool.item!!.pinched(Vec2(200f, 200f), Vec2.ZERO, 2f, 0f, tool.maxSizePx)
        assertEquals(48f, scaled.spec.box.width, 0.01f)
    }

    @Test
    fun fixedBoxToggleStartsAtTheNaturalWidth() {
        val (_, tool) = newController()
        tool.startTextAt(200f, 200f)
        tool.setText("Hello there")
        tool.updateSpec { it.copy(sizePx = 20f) }
        val natural = tool.blockFor(tool.item!!).contentWidth
        tool.setFixedBox(true)
        assertEquals(natural, tool.item!!.spec.box.width, 0.01f)
        assertEquals(1, tool.blockFor(tool.item!!).lineCount)
        tool.setBoxLength(50f)
        assertTrue(tool.blockFor(tool.item!!).lineCount >= 2)
        tool.setBoxLength(Float.NaN)
        assertEquals(50f, tool.item!!.spec.box.width, 0f)
        tool.setFixedBox(false)
        assertEquals(0f, tool.item!!.spec.box.width, 0f)
        // Vertical text: the box height.
        tool.updateSpec { it.copy(vertical = true) }
        tool.setFixedBox(true)
        assertTrue(tool.item!!.spec.box.height > 0f)
        assertEquals(0f, tool.item!!.spec.box.width, 0f)
    }

    @Test
    fun aTextPathKeepsTheBoxAndVerticalSettingsForLater() {
        val (c, tool) = newController()
        tool.startTextAt(200f, 200f)
        tool.setText("Round")
        tool.updateSpec { it.copy(vertical = true, box = TextBoxSpec(width = 80f, fill = true)) }
        tool.confirmEditor()
        tool.setPath(TextPathSpec(type = TextPathType.CIRCLE))
        val item = tool.item!!
        assertEquals(TextPathType.CIRCLE, item.path.type)
        assertTrue("kept", item.spec.vertical && item.spec.box.fill && item.spec.box.width == 80f)
        // Drawing the guide / handles and moving work whatever the path engine measures.
        val screen = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        tool.drawOverlay(Canvas(screen), c.viewTransform)
        tool.nudge(10f, 0f)
        assertEquals(210f, tool.item!!.cx, 0f)
        tool.setRotation(30f)
        assertEquals(30f, tool.item!!.rotationDeg, 0f)
        tool.drawOverlay(Canvas(screen), c.viewTransform)
        // Back to straight text: the shape's settings stay for later.
        tool.setPath(tool.item!!.path.copy(type = TextPathType.NONE))
        assertFalse(tool.item!!.path.isActive)
        assertTrue(tool.blockFor(tool.item!!).lineCount >= 1)
        tool.discard()
        assertEquals(1, c.doc.layers.size)
    }

    // ------------------------------------------------------------------ vertical text

    @Test
    fun uprightVerticalLettersStandUpright() {
        val spec = TextSpec(sizePx = 40f, vertical = true, color = black)
        val (ub, upright) = render("III", spec.copy(verticalStyle = VerticalStyle.UPRIGHT))
        val ink = inkBounds(upright)
        // Three upright "I"s stacked: a tall, thin column; each glyph is taller than wide.
        assertTrue("upright column $ink", ink.height() > 4 * ink.width())
        assertTrue(ink.height() > 80)
        assertEquals(40f, ub.width, 0.01f)
        assertEquals(120f, ub.height, 0.01f)
        // Every letter is centered in its column.
        assertEquals(20f + ub.width / 2f, (ink.left + ink.right) / 2f, 3f)

        // Mixed (manga) style turns Latin letters: the "I"s lie on their side, one after another.
        val (_, sideways) = render("I", spec.copy(verticalStyle = VerticalStyle.MIXED))
        val s = inkBounds(sideways)
        assertTrue("sideways I $s", s.width() > 2 * s.height())
    }

    @Test
    fun verticalTextDefaultsToUprightAndCommitsTopToBottom() {
        val (c, tool) = newController(300, 400)
        tool.toggleVertical()
        tool.startTextAt(150f, 200f)
        assertEquals(VerticalStyle.UPRIGHT, tool.item!!.spec.verticalStyle)
        tool.setText("HELLO")
        tool.updateSpec { it.copy(sizePx = 40f) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val ink = inkBounds(c.activeLayer.bitmap)
        assertTrue("tall and narrow: $ink", ink.height() > 3 * ink.width())
        // Columns: right to left by default, left to right as an option. The first line is a
        // short column ("I"), the second a tall one ("WWW").
        val base = TextSpec(sizePx = 20f, vertical = true, lineSpacing = 2f, color = black)
        val (rtl, rb) = render("I\nWWW", base)
        val (ltr, lb) = render("I\nWWW", base.copy(columnsLeftToRight = true))
        assertEquals(60f, rtl.width, 0.01f)
        assertEquals(rtl.width, ltr.width, 0f)
        fun leftHalfInk(b: Bitmap) = inkBounds(Bitmap.createBitmap(b, 0, 0, b.width / 2, b.height))
        assertTrue("right to left: the second (tall) column is on the left", leftHalfInk(rb).height() > 40)
        assertTrue("left to right: the first (short) column is on the left", leftHalfInk(lb).height() < 25)
    }

    @Test
    fun verticalAlignmentOutlineAndBoxWork() {
        val spec = TextSpec(sizePx = 30f, vertical = true, color = black, strokeWidthPx = 3f, strokeColor = red, align = TextAlign.END, box = TextBoxSpec(height = 200f, padding = 5f, fill = true, fillColor = white))
        val (block, bmp) = render("AB", spec)
        assertEquals(210f, block.height, 0.01f)
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        assertTrue(px.any { it == red })
        // Bottom-aligned: the upper half of the box is only background.
        var inkTop = Int.MAX_VALUE
        for (y in 20 until 20 + block.height.toInt()) for (x in 20 until 20 + block.width.toInt()) {
            val p = bmp.getPixel(x, y)
            if (p != white && (p ushr 24) != 0) { inkTop = minOf(inkTop, y); break }
        }
        assertTrue("letters at the bottom of the box: $inkTop", inkTop > 20 + 100)
    }
}
