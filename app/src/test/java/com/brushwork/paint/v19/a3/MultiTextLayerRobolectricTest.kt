package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.TextWrapSpec
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.ui.common.TextLabels19
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 7 (R14 a, e; I1, I24): several texts in one text layer, through the Text tool as a
 * finger uses it (taps are pointer down + up, the view is the identity). A tap on empty canvas
 * places the new text in the active text layer (one "Add text" step, the layer keeps its name);
 * a tap on a text of several edits that text alone; a second tap on the same spot opens the
 * text below; the 65th text starts a layer of its own with a message; frames, wrapped texts and
 * the “New layer each time” switch keep v1.8's layer per text. After every stored change the
 * layer's pixels equal a fresh rendering of all its texts (I1).
 */
@RunWith(RobolectricTestRunner::class)
class MultiTextLayerRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val black = 0xFF000000.toInt()

    // ------------------------------------------------------------------ helpers

    private fun newController(w: Int = 400, h: Int = 300): Pair<EditorController, TextTool> {
        val d = Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, d, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    /**
     * A tap on empty canvas at ([x], [y]) that types [text] and applies it with ✓; [over] places
     * it there although a text is there already (a tap would open that text).
     */
    private fun place(c: EditorController, tool: TextTool, text: String, x: Float, y: Float, size: Float = 32f, over: Boolean = false) {
        if (over) tool.startTextAt(x, y) else tap(c, x, y)
        assertTrue("the editor opened for a new text", tool.editorOpen && tool.editingNew)
        tool.setText(text)
        tool.updateSpec { it.copy(sizePx = size, color = black) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        assertNull(tool.item)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** I1: [layer]'s pixels equal a fresh rendering of every text it stores, bottom first. */
    private fun assertRendered(layer: Layer, what: String) {
        val items = TextCodec.decodeAll(layer.textData)
        assertTrue("$what: the layer holds text", items.isNotEmpty())
        val fresh = BitmapUtils.createLayerBitmap(layer.width, layer.height)
        val cv = Canvas(fresh)
        for (t in items) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null)
        assertArrayEquals("$what: pixels equal a fresh rendering of every stored text (I1)", pixels(fresh), pixels(layer.bitmap))
    }

    private fun texts(layer: Layer): List<String> = TextCodec.decodeAll(layer.textData).map { it.text }

    /** Replaces [layer]'s texts with [items] (pixels and data, one setup step). */
    private fun store(c: EditorController, layer: Layer, items: List<TextItem>) {
        assertTrue(c.updateTextLayer(layer, TextCodec.encodeAll(items), "Setup", null) { cv -> for (t in items) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null) })
    }

    // ------------------------------------------------------------------ join

    @Test
    fun aTapOnEmptyCanvasPlacesTheNewTextInTheActiveTextLayer() {
        val (c, tool) = newController()
        place(c, tool, "Alpha", 100f, 80f)
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)
        val name = layer.name
        assertEquals(2, c.doc.layers.size)
        val steps = c.undoManager.undoCount

        // The second text: the tap joins the active text layer, nothing stored until ✓.
        tap(c, 260f, 200f)
        assertSame(layer, tool.editingLayer)
        assertTrue(tool.joining && tool.sharesLayer && tool.editingNew)
        assertEquals(listOf("Alpha"), texts(layer))
        tool.setText("Beta")
        tool.updateSpec { it.copy(sizePx = 28f, color = black) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())

        assertEquals("no new layer", 2, c.doc.layers.size)
        assertSame(layer, c.activeLayer)
        assertEquals("the new text on top", listOf("Alpha", "Beta"), texts(layer))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("Add text", c.undoManager.undoLabel)
        assertEquals("the layer keeps its name", name, layer.name)
        assertRendered(layer, "after the join")
        assertNull(tool.editingLayer)

        c.undo()
        assertEquals(listOf("Alpha"), texts(layer))
        assertRendered(layer, "after undo")
        c.redo()
        assertEquals(listOf("Alpha", "Beta"), texts(layer))
        assertRendered(layer, "after redo")
    }

    @Test
    fun aCancelledOrEmptyNewTextLeavesTheLayerAsItWas() {
        val (c, tool) = newController()
        place(c, tool, "Alpha", 100f, 80f)
        val layer = c.activeLayer
        val data = layer.textData
        val before = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount

        tap(c, 260f, 200f)
        assertTrue(tool.joining)
        tool.setText("Gone")
        tool.cancelEditor()
        assertNull("Cancel ends the join", tool.editingLayer)
        assertNull(c.renderOverride)

        tap(c, 260f, 200f)
        assertTrue(tool.joining)
        tool.setText("   ")
        tool.confirmEditor()
        assertNull("an empty new text is dropped", tool.item)
        assertNull(tool.editingLayer)

        assertEquals(data, layer.textData)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals("nothing recorded", steps, c.undoManager.undoCount)
        assertEquals(2, c.doc.layers.size)
    }

    // ------------------------------------------------------------------ edit, cycle

    @Test
    fun aTapOnOneTextOfSeveralEditsThatTextAlone() {
        val (c, tool) = newController()
        place(c, tool, "Alpha", 100f, 80f)
        place(c, tool, "Beta", 260f, 200f)
        val layer = c.activeLayer
        assertEquals(listOf("Alpha", "Beta"), texts(layer))

        // Tap the bottom text: it opens, the other one stays in the layer's preview.
        c.selectLayer(c.doc.layers[0])
        tap(c, 100f, 80f)
        assertSame(layer, tool.editingLayer)
        assertSame("the tapped layer becomes active", layer, c.activeLayer)
        assertEquals("Alpha", tool.item!!.text)
        assertEquals(0, tool.openIndex)
        assertFalse(tool.joining)
        assertTrue(tool.sharesLayer)
        val steps = c.undoManager.undoCount
        tool.setText("Gamma")
        assertTrue(tool.commitItem())
        assertEquals("the edited text keeps its place", listOf("Gamma", "Beta"), texts(layer))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Edit text", c.undoManager.undoLabel)
        assertRendered(layer, "after editing the bottom text")

        // The top one, then undo.
        tap(c, 260f, 200f)
        assertEquals("Beta", tool.item!!.text)
        assertEquals(1, tool.openIndex)
        tool.updateSpec { it.copy(color = 0xFFCC2222.toInt()) }
        assertTrue(tool.commitItem())
        assertEquals(0xFFCC2222.toInt(), TextCodec.decodeAll(layer.textData)[1].spec.color)
        assertEquals(black, TextCodec.decodeAll(layer.textData)[0].spec.color)
        assertRendered(layer, "after editing the top text")
        c.undo()
        assertEquals(black, TextCodec.decodeAll(layer.textData)[1].spec.color)
        assertRendered(layer, "after undo")
        assertEquals(2, c.doc.layers.size)
    }

    @Test
    fun aSecondTapOnTheSameSpotOpensTheTextBelow() {
        val (c, tool) = newController()
        place(c, tool, "Bottom", 200f, 150f, size = 40f)
        place(c, tool, "Top", 200f, 150f, size = 40f, over = true)
        val layer = c.activeLayer
        assertEquals(listOf("Bottom", "Top"), texts(layer))
        val steps = c.undoManager.undoCount

        tap(c, 200f, 150f)
        assertEquals("the topmost first", "Top", tool.item!!.text)
        assertFalse("a tap only loads the text", tool.editorOpen)
        tap(c, 200f, 150f)
        assertEquals("then the one below", "Bottom", tool.item!!.text)
        assertEquals(0, tool.openIndex)
        tap(c, 200f, 150f)
        assertEquals("then round to the top again", "Top", tool.item!!.text)
        assertEquals("cycling stores nothing", steps, c.undoManager.undoCount)

        // An edit made before the next tap is applied, then the text below opens.
        tool.setText("Top!")
        tap(c, 200f, 150f)
        assertEquals("Bottom", tool.item!!.text)
        assertEquals(listOf("Bottom", "Top!"), texts(layer))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertRendered(layer, "after the cycle applied the edit")

        // A text alone at the spot: the second tap opens the editor, as in v1.8.
        tool.discard()
        place(c, tool, "Far", 60f, 260f, size = 24f)
        tap(c, 60f, 260f)
        assertEquals("Far", tool.item!!.text)
        tap(c, 60f, 260f)
        assertTrue(tool.editorOpen)
        tool.cancelEditor()
    }

    // ------------------------------------------------------------------ limits

    @Test
    fun theSixtyFifthTextStartsANewLayerWithAMessage() {
        val (c, tool) = newController()
        place(c, tool, "T0", 20f, 20f, size = 12f)
        val layer = c.activeLayer
        val first = TextCodec.decodeAll(layer.textData).single()
        val all = List(TextCodec.MAX_ITEMS) { i -> first.copy(text = "T$i", cx = 20f + (i % 8) * 45f, cy = 20f + (i / 8) * 34f) }
        store(c, layer, all)
        assertEquals(TextCodec.MAX_ITEMS, TextCodec.decodeAll(layer.textData).size)
        c.message = null

        tap(c, 380f, 290f)
        assertEquals(TextLabels19.LAYER_FULL, c.message)
        assertNull("not a join", tool.editingLayer)
        assertTrue(tool.editingNew)
        tool.setText("Next")
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        assertEquals("a layer of its own", 3, c.doc.layers.size)
        assertNotEquals(layer, c.activeLayer)
        assertEquals(listOf("Next"), texts(c.activeLayer))
        assertEquals("the full layer is unchanged", TextCodec.MAX_ITEMS, TextCodec.decodeAll(layer.textData).size)
    }

    @Test
    fun aFrameAWrappedTextOrALockedLayerTakesNoOtherText() {
        // A frame of a linked story.
        val s = FrameFixtures.setup(context)
        val frame = FrameFixtures.newFrame(s, 40f, 40f, 200f, 160f)
        val frameData = frame.textData
        s.c.selectTool(ToolId.TEXT)
        val tool = s.c.tools.getValue(ToolId.TEXT) as TextTool
        val layers = s.doc.layers.size
        tap(s.c, 320f, 250f)
        assertNull("no join into a frame", tool.editingLayer)
        tool.setText("Beside")
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        assertEquals(layers + 1, s.doc.layers.size)
        assertEquals(frameData, frame.textData)

        // A text that wraps around a picture.
        val (c, t2) = newController()
        place(c, t2, "Wraps", 100f, 80f)
        val wrapped = c.activeLayer
        val item = TextCodec.decodeAll(wrapped.textData).single()
        store(c, wrapped, listOf(item.copy(wrap = TextWrapSpec(sourceLayerId = c.doc.layers[0].id))))
        assertTrue(TextCodec.decodeAll(wrapped.textData).single().wrap.isOn)
        c.selectLayer(wrapped)
        tap(c, 300f, 250f)
        assertNull("no join into a wrapping text's layer", t2.editingLayer)
        t2.cancelEditor()

        // Locked or hidden active text layer.
        place(c, t2, "Locked", 100f, 200f)
        val locked = c.activeLayer
        assertNotEquals(wrapped, locked)
        locked.locked = true
        tap(c, 300f, 250f)
        assertNull(t2.editingLayer)
        assertTrue(t2.editingNew)
        t2.cancelEditor()
        assertEquals(listOf("Locked"), texts(locked))
    }

    @Test
    fun aTextAmongOthersCannotStartToWrap() {
        val (c, tool) = newController()
        place(c, tool, "Alpha", 100f, 80f)
        place(c, tool, "Beta", 260f, 200f)
        tap(c, 100f, 80f)
        assertTrue(tool.sharesLayer)
        assertFalse(tool.canWrap)
        c.message = null
        tool.openWrapSheet()
        assertEquals(TextTool.WRAP_ALONE_ONLY, c.message)
        assertFalse(tool.wrapSheetOpen)
    }

    // ------------------------------------------------------------------ the switch

    @Test
    fun newLayerEachTimeGivesEveryNewTextALayerOfItsOwn() {
        val (c, tool) = newController()
        assertFalse("off by default", tool.newLayerEachTime)
        tool.newLayerEachTime = true
        assertTrue("stored in the settings", AppSettings(context).textNewLayerEachTime)
        place(c, tool, "One", 100f, 80f)
        val first = c.activeLayer
        place(c, tool, "Two", 260f, 200f)
        assertEquals(3, c.doc.layers.size)
        assertNotEquals(first, c.activeLayer)
        assertEquals(listOf("One"), texts(first))
        assertEquals(listOf("Two"), texts(c.activeLayer))

        // Read at the tap: off again, the next text joins.
        tool.newLayerEachTime = false
        place(c, tool, "Three", 200f, 260f)
        assertEquals(3, c.doc.layers.size)
        assertEquals(listOf("Two", "Three"), texts(c.activeLayer))
    }

    @Test
    fun theTextAtAPointIsTheTopmostOfTheActiveLayerFirst() {
        val (c, tool) = newController()
        place(c, tool, "Under", 200f, 150f, size = 40f)
        place(c, tool, "Over", 200f, 150f, size = 40f, over = true)
        val layer = c.activeLayer
        val hit = tool.textAt(Vec2(200f, 150f))
        assertNotNull(hit)
        assertSame(layer, hit!!.layer)
        assertEquals(1, hit.index)
        assertNull(tool.textAt(Vec2(10f, 290f)))
    }
}
