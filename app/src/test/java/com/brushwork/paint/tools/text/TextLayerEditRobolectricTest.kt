package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Editable text layers on real Skia (Robolectric NATIVE graphics): the text object is stored in
 * the layer, the text tool loads it again on a tap, ✓ re-renders the same layer as one undo step.
 */
@RunWith(RobolectricTestRunner::class)
class TextLayerEditRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newController(w: Int = 300, h: Int = 200, doc: Document? = null): Pair<EditorController, TextTool> {
        val d = doc ?: Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        val c = EditorController(context, d, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        // Drags land exactly under the finger here; snapping is covered by TextSnapRobolectricTest.
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The document as the compositor draws it now (tool previews included). */
    private fun composite(c: EditorController): Bitmap =
        BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height).also { c.compositor.drawDocument(Canvas(it), null) }

    /** What the screen shows: the display tiles after redrawing whatever was invalidated. */
    private fun screen(c: EditorController): Bitmap {
        c.tiles.update(c.compositor)
        return BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height).also { c.tiles.draw(Canvas(it), null) }
    }

    private fun inkBounds(b: Bitmap): Rect {
        val px = pixels(b)
        val r = Rect()
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if ((px[y * b.width + x] ushr 24) != 0) {
                if (r.isEmpty) r.set(x, y, x + 1, y + 1) else r.union(x, y)
            }
        }
        return r
    }

    /** Places [text] at ([x], [y]) with [size] px and commits it into a new text layer. */
    private fun addText(c: EditorController, tool: TextTool, text: String, x: Float, y: Float, size: Float = 40f, spec: (TextSpec) -> TextSpec = { it }): Layer {
        tool.startTextAt(x, y)
        tool.setText(text)
        tool.updateSpec { spec(it.copy(sizePx = size, color = 0xFF000000.toInt())) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        return c.activeLayer
    }

    // ------------------------------------------------------------------ create, edit, undo

    @Test
    fun committedTextLayerCanBeEditedAgainAsOneUndoStep() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "Hello", 150f, 100f)
        assertEquals(2, c.doc.layers.size)
        assertTrue("the new layer is a text layer", layer.isTextLayer)
        val stored = TextCodec.decode(layer.textData)!!
        assertEquals("Hello", stored.text)
        assertEquals(150f, stored.cx, 0f)
        val oldPixels = pixels(layer.bitmap)
        val undoBefore = c.undoManager.undoCount

        // Tap the text with the text tool: it is loaded as pending work, the layer's pixels hidden.
        c.selectLayer(c.doc.layers[0])
        tap(c, 150f, 100f)
        assertSame(layer, tool.editingLayer)
        assertSame(layer, c.activeLayer)
        assertEquals("Hello", tool.item!!.text)
        assertFalse("loading the text opens no dialog", tool.editorOpen)
        assertSame(layer, c.renderOverride?.layer)
        // The layer shows the pending text in place of its pixels (identical while unchanged).
        assertArrayEquals(oldPixels, pixels(composite(c)))
        assertEquals("nothing recorded yet", undoBefore, c.undoManager.undoCount)

        // A tap on the pending text opens the editor; edit and apply.
        tap(c, 150f, 100f)
        assertTrue(tool.editorOpen)
        assertEquals("Edit text", if (tool.editingNew) "Add text" else "Edit text")
        tool.setText("Bye")
        tool.updateSpec { it.copy(color = 0xFFFF0000.toInt()) }
        tool.confirmEditor()
        // Live, before applying: the new text in the layer, the old one hidden.
        val live = pixels(composite(c)).filter { it ushr 24 == 255 }
        assertTrue("only the edited (red) text shows", live.isNotEmpty() && live.all { it == 0xFFFF0000.toInt() })
        assertArrayEquals("the layer itself is untouched until ✓", oldPixels, pixels(layer.bitmap))
        assertTrue(tool.commitItem())
        assertNull(tool.item)
        assertNull(tool.editingLayer)
        assertNull("the old pixels show again", c.renderOverride)
        assertEquals("same layer, no new one", 2, c.doc.layers.size)
        assertSame(layer, c.doc.layers[1])
        assertEquals("Bye", TextCodec.decode(layer.textData)!!.text)
        assertEquals("Text: Bye", layer.name)
        assertEquals("one undo step", undoBefore + 1, c.undoManager.undoCount)
        assertEquals("Edit text", c.undoManager.undoLabel)
        val red = pixels(layer.bitmap).filter { it ushr 24 == 255 }
        assertTrue(red.isNotEmpty() && red.all { it == 0xFFFF0000.toInt() })

        // Undo restores the old pixels, the old name AND the old editable text.
        c.undo()
        assertArrayEquals(oldPixels, pixels(layer.bitmap))
        assertEquals("Hello", TextCodec.decode(layer.textData)!!.text)
        assertEquals("Text: Hello", layer.name)
        c.redo()
        assertEquals("Bye", TextCodec.decode(layer.textData)!!.text)
        c.undo()
        // Still editable: tapping loads the old text.
        tap(c, 150f, 100f)
        assertSame(layer, tool.editingLayer)
        assertEquals("Hello", tool.item!!.text)
    }

    @Test
    fun theEditedTextKeepsItsLayersLookWhileEditing() {
        val (c, tool) = newController()
        val text = addText(c, tool, "Clip", 150f, 100f, size = 50f)
        // A layer clipped to the text (a common "gradient in the letters" setup) and a
        // half-transparent text layer.
        val tint = c.addLayer("Tint")!!
        tint.bitmap.eraseColor(0xFF0000FF.toInt())
        tint.clipping = true
        text.opacity = 0.5f
        // Blue letters (black only mixes in at soft edges), at most half opaque.
        fun looksRight(px: IntArray): Boolean {
            val ink = px.filter { it ushr 24 != 0 }
            return ink.isNotEmpty() &&
                ink.all { (it shr 16 and 0xFF) == 0 && (it shr 8 and 0xFF) == 0 && (it ushr 24) <= 140 } &&
                ink.any { (it and 0xFF) == 0xFF && (it ushr 24) in 120..135 }
        }
        val before = pixels(composite(c))
        assertTrue("the clipped layer fills the half-transparent letters", looksRight(before))

        assertTrue(tool.editLayer(text))
        assertSame(text, c.activeLayer)
        assertArrayEquals("editing doesn't change the look (clipping, opacity, order)", before, pixels(composite(c)))

        // Moved: the letters (still filled by the clipped layer, still half transparent) move.
        tool.nudge(60f, 0f)
        val live = composite(c)
        val ink = inkBounds(live)
        assertTrue("moved right: $ink", ink.left > inkBounds(text.bitmap).left + 50)
        assertTrue("still clipped and half transparent", looksRight(pixels(live)))
        // What was shown live is exactly what ✓ produces.
        val shown = pixels(live)
        assertTrue(tool.commitItem())
        assertArrayEquals(shown, pixels(composite(c)))
    }

    @Test
    fun draggingTheEditedTextLeavesNoTrailOnScreen() {
        val (c, tool) = newController(400, 300)
        val layer = addText(c, tool, "Drag me", 150f, 150f, size = 36f)
        val original = pixels(composite(c))
        screen(c)
        tap(c, 150f, 150f)
        assertSame(layer, tool.editingLayer)
        assertArrayEquals(original, pixels(screen(c)))
        // Drag the text in steps, redrawing the screen after each like the canvas view does.
        c.pointerDown(ToolPoint(150f, 150f))
        for (i in 1..8) {
            c.pointerMove(ToolPoint(150f + i * 15f, 150f + i * 10f))
            screen(c)
        }
        c.pointerUp(ToolPoint(270f, 230f))
        assertEquals(270f, tool.item!!.cx, 0.01f)
        assertArrayEquals("screen == fresh composite (no ghosts)", pixels(composite(c)), pixels(screen(c)))
        // A new look in the same place is redrawn too.
        tool.updateSpec { it.copy(color = 0xFF00FF00.toInt()) }
        assertArrayEquals(pixels(composite(c)), pixels(screen(c)))
        // ✕: the old text is back where it was, on screen too.
        tool.discard()
        assertNull(c.renderOverride)
        assertArrayEquals(original, pixels(screen(c)))
    }

    @Test
    fun draggingAcrossDisplayTileSeamsLeavesNoTrail() {
        // Wider than one 512 px display tile: the text crosses the seams at x = 512 and y = 512.
        val (c, tool) = newController(1100, 700)
        addText(c, tool, "Seams", 470f, 470f, size = 48f)
        screen(c)
        tap(c, 470f, 470f)
        assertNotNull(tool.editingLayer)
        c.pointerDown(ToolPoint(470f, 470f))
        for (i in 1..6) {
            c.pointerMove(ToolPoint(470f + i * 17f, 470f + i * 13f))
            assertArrayEquals("step $i", pixels(composite(c)), pixels(screen(c)))
        }
        c.pointerUp(ToolPoint(572f, 548f))
        assertTrue(tool.commitItem())
        assertArrayEquals(pixels(composite(c)), pixels(screen(c)))
    }

    @Test
    fun theNextNewTextKeepsTheLookButNotTheWrapWidth() {
        val (c, tool) = newController()
        addText(c, tool, "Boxed words", 150f, 100f) {
            it.copy(bold = true, box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(width = 90f), 40f))
        }
        tool.startTextAt(60f, 60f)
        val spec = tool.item!!.spec
        assertTrue("the look carries over", spec.bold && spec.box.fill && spec.box.borderWidth > 0f)
        assertEquals("a new text fits its own words", 0f, spec.box.width, 0f)
        tool.cancelEditor()
        // Bad numbers from a field never reach the layout.
        tool.startTextAt(60f, 60f)
        tool.setText("n")
        tool.setSizePx(Float.NaN)
        tool.setRotation(Float.POSITIVE_INFINITY)
        assertEquals(40f, tool.item!!.spec.sizePx, 0f)
        assertEquals(0f, tool.item!!.rotationDeg, 0f)
        tool.discard()
    }

    @Test
    fun cancellingAnEditLeavesTheLayerUntouched() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "Keep", 150f, 100f)
        val before = pixels(layer.bitmap)
        val data = layer.textData
        val undo = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer, openEditor = true))
        assertTrue(tool.editorOpen)
        tool.setText("Changed a lot")
        tool.confirmEditor()
        tool.nudge(30f, 10f)
        tool.discard() // ✕
        assertNull(c.renderOverride)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(data, layer.textData)
        assertEquals(undo, c.undoManager.undoCount)

        // Applying an unchanged text records nothing either.
        assertTrue(tool.editLayer(layer))
        assertTrue(tool.commitItem())
        assertEquals(undo, c.undoManager.undoCount)
        assertNull(c.renderOverride)
    }

    @Test
    fun anUntouchedLoadedTextDoesNotSwallowUndo() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "Undo me", 150f, 100f)
        assertTrue(tool.editLayer(layer))
        c.undo() // undoes "Add text", not just the loading
        assertNull(tool.item)
        assertEquals(1, c.doc.layers.size)
        assertNull(c.renderOverride)

        // With a change, undo first throws the change away.
        c.redo()
        assertTrue(tool.editLayer(layer))
        tool.nudge(5f, 0f)
        c.undo()
        assertNull(tool.item)
        assertEquals(2, c.doc.layers.size)
    }

    @Test
    fun oldPixelsOutsideTheNewTextAreCleared() {
        val (c, tool) = newController(400, 200)
        val layer = addText(c, tool, "WWWWWWWW", 200f, 100f, size = 36f)
        val wide = inkBounds(layer.bitmap)
        assertTrue(wide.width() > 150)
        assertTrue(tool.editLayer(layer))
        tool.setText("i")
        assertTrue(tool.commitItem())
        val ink = inkBounds(layer.bitmap)
        assertFalse(ink.isEmpty)
        assertTrue("only the new, narrow text is left: $ink", ink.width() < 40)
        c.undo()
        assertEquals(wide, inkBounds(layer.bitmap))
    }

    @Test
    fun oldPixelsAreClearedEvenWhereTheyDifferFromTheComputedText() {
        val (c, tool) = newController(400, 300)
        val layer = addText(c, tool, "Font", 200f, 150f)
        val black = 0xFF000000.toInt()
        val ink = inkBounds(layer.bitmap)
        // As if another device's font had drawn the text differently: ink a bit beyond the
        // computed box, and a long run reaching far out.
        layer.bitmap.setPixel(ink.right + 20, ink.centerY(), black)
        for (x in ink.right until 398) layer.bitmap.setPixel(x, ink.top, black)
        assertTrue(tool.editLayer(layer))
        tool.setText("x")
        assertTrue(tool.commitItem())
        assertEquals(0, layer.bitmap.getPixel(ink.right + 20, ink.centerY()))
        assertEquals(0, layer.bitmap.getPixel(397, ink.top))
        c.undo()
        assertEquals(black, layer.bitmap.getPixel(397, ink.top))
        assertEquals(black, layer.bitmap.getPixel(ink.right + 20, ink.centerY()))
    }

    @Test
    fun reEditingIgnoresALeftoverSelection() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "MMMM", 150f, 100f, size = 40f)
        val full = inkBounds(layer.bitmap)
        // Select only the left third, then edit the text: the whole text is still drawn.
        val w = c.doc.width; val h = c.doc.height
        c.setSelection(Selection.fromBytes(ByteArray(w * h) { if (it % w < 100) -1 else 0 }, w, h))
        assertTrue(tool.editLayer(layer))
        tool.updateSpec { it.copy(bold = true) }
        assertTrue(tool.commitItem())
        val ink = inkBounds(layer.bitmap)
        assertTrue("whole text re-rendered: $ink vs $full", ink.right > 150 && ink.left < full.left + 10)
    }

    @Test
    fun emptiedTextDeletesTheLayerWithoutAskingAndUndoRestoresIt() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "Gone?", 150f, 100f)
        val before = pixels(layer.bitmap)
        val undoBefore = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer, openEditor = true))
        tool.setText("  ")
        c.message = null
        tool.confirmEditor()
        // Deleted at once, no question.
        assertEquals(1, c.doc.layers.size)
        assertTrue(c.doc.layers.none { it === layer })
        assertEquals("Text layer deleted — undo to restore", c.message)
        assertNull(tool.item)
        assertNull(tool.editingLayer)
        assertFalse(tool.editorOpen)
        assertNull(c.renderOverride)
        assertEquals("one undo step", undoBefore + 1, c.undoManager.undoCount)
        // Undo brings the layer back with its pixels and editable text.
        c.undo()
        assertEquals(2, c.doc.layers.size)
        assertSame(layer, c.doc.layers[1])
        assertSame(layer, c.activeLayer)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals("Gone?", TextCodec.decode(layer.textData)!!.text)
        c.redo()
        assertEquals(1, c.doc.layers.size)
        c.undo()

        // Applying an emptied text any other way (switching tools) deletes it the same way.
        assertTrue(tool.editLayer(layer))
        tool.setText("")
        c.selectTool(ToolId.BRUSH)
        assertEquals(1, c.doc.layers.size)
        assertNull(c.renderOverride)
        c.undo()
        assertEquals(2, c.doc.layers.size)
        assertTrue(layer.isTextLayer)

        // The only layer of a drawing can't go: it keeps its old text.
        c.selectTool(ToolId.TEXT)
        c.deleteLayer(c.doc.layers[0])
        assertEquals(listOf(layer), c.doc.layers.toList())
        assertTrue(tool.editLayer(layer, openEditor = true))
        tool.setText("")
        tool.confirmEditor()
        assertEquals(1, c.doc.layers.size)
        assertEquals("Gone?", TextCodec.decode(layer.textData)!!.text)
        assertNull(tool.item)
        assertNull(c.renderOverride)
    }

    @Test
    fun anEmptiedTextWhoseLayerGotLockedKeepsTheEditorOpen() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "Locked", 150f, 100f)
        val before = pixels(layer.bitmap)
        assertTrue(tool.editLayer(layer, openEditor = true))
        tool.setText("")
        // Locked from the layers window while the editor was open.
        layer.locked = true
        c.message = null
        tool.confirmEditor()
        assertEquals("nothing deleted", 2, c.doc.layers.size)
        assertTrue(c.message!!.contains("locked"))
        assertTrue("the editor stays open to fix or cancel it", tool.editorOpen)
        assertEquals("", tool.item!!.text)
        // Cancel: the old text is back, the layer untouched.
        tool.cancelEditor()
        assertEquals("Locked", tool.item!!.text)
        assertFalse(tool.editorOpen)
        tool.discard()
        assertNull(c.renderOverride)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals("Locked", TextCodec.decode(layer.textData)!!.text)
        // Unlocked again: emptying it now deletes it.
        layer.locked = false
        assertTrue(tool.editLayer(layer, openEditor = true))
        tool.setText(" ")
        tool.confirmEditor()
        assertFalse(tool.editorOpen)
        assertEquals(1, c.doc.layers.size)
    }

    // ------------------------------------------------------------------ hit testing

    @Test
    fun tappingEmptyCanvasStillAddsTextAndTheActiveLayerWins() {
        val (c, tool) = newController()
        val a = addText(c, tool, "AAAA", 150f, 100f)
        val b = addText(c, tool, "BBBB", 150f, 100f) // on top, same place
        // Empty canvas: a new text.
        tap(c, 20f, 20f)
        assertNull(tool.editingLayer)
        assertTrue(tool.editingNew && tool.editorOpen)
        tool.cancelEditor()
        // Over both: the topmost...
        tap(c, 150f, 100f)
        assertSame(b, tool.editingLayer)
        tool.discard()
        // ...unless the lower one is the active layer.
        c.selectLayer(a)
        tap(c, 150f, 100f)
        assertSame(a, tool.editingLayer)
        tool.discard()
        // Hidden and locked text layers are skipped.
        a.visible = false
        b.locked = true
        tap(c, 150f, 100f)
        assertNull(tool.editingLayer)
        assertTrue(tool.editingNew)
        tool.cancelEditor()
    }

    @Test
    fun tappingAwayFromAnEditedTextAppliesItAndLoadsTheTappedOne() {
        val (c, tool) = newController(400, 200)
        val left = addText(c, tool, "Left", 90f, 100f)
        val right = addText(c, tool, "Right", 300f, 100f)
        assertTrue(tool.editLayer(left))
        tool.setText("Links")
        tap(c, 300f, 100f)
        assertEquals("Links", TextCodec.decode(left.textData)!!.text)
        assertSame(right, tool.editingLayer)
        assertEquals(3, c.doc.layers.size)
    }

    // ------------------------------------------------------------------ other edits

    @Test
    fun brushStrokeRasterizesTheTextLayerAndUndoBringsTheTextBack() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "Paint", 150f, 100f)
        val data = layer.textData
        c.selectTool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 8f, pressureSize = false)
        c.pointerDown(ToolPoint(20f, 20f))
        for (i in 1..10) c.pointerMove(ToolPoint(20f + i * 10f, 20f))
        c.pointerUp(ToolPoint(120f, 20f))
        assertSame(layer, c.activeLayer)
        assertNull("painted over: a raster layer now", layer.textData)
        assertFalse(layer.isTextLayer)
        c.undo()
        assertEquals(data, layer.textData)
        c.selectTool(ToolId.TEXT)
        tap(c, 150f, 100f)
        assertSame(layer, tool.editingLayer)
    }

    @Test
    fun duplicateKeepsTheTextEditableAndIndependent() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "Twin", 150f, 100f)
        val copy = c.duplicateLayer(layer)!!
        assertEquals(layer.textData, copy.textData)
        assertTrue(tool.editLayer(copy))
        tool.setText("Other")
        assertTrue(tool.commitItem())
        assertEquals("Other", TextCodec.decode(copy.textData)!!.text)
        assertEquals("Twin", TextCodec.decode(layer.textData)!!.text)
    }

    @Test
    fun textLayerSurvivesSaveAndLoadAndStaysEditable() = runBlocking<Unit> {
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        val (c, tool) = newController()
        val layer = addText(c, tool, "Saved", 150f, 100f) { it.copy(vertical = true, box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(), 40f)) }
        val pixels = pixels(layer.bitmap)
        repo.save(c.doc, c.compositor.renderThumbnail(64))

        val loaded = repo.load(c.doc.id)
        val l2 = loaded.layers.first { it.id == layer.id }
        assertEquals(layer.textData, l2.textData)
        assertArrayEquals(pixels, pixels(l2.bitmap))
        val item = TextCodec.decode(l2.textData)!!
        assertTrue(item.spec.vertical && item.spec.box.fill)

        val (c2, tool2) = newController(doc = loaded)
        tap(c2, 150f, 100f)
        assertSame(l2, tool2.editingLayer)
        tool2.setText("Loaded")
        assertTrue(tool2.commitItem())
        assertEquals("Loaded", TextCodec.decode(l2.textData)!!.text)
        assertEquals(loaded.layers.size, c2.doc.layers.size)
    }

    @Test
    fun editingALockedOrHiddenLayerIsRefused() {
        val (c, tool) = newController()
        val layer = addText(c, tool, "Lock", 150f, 100f)
        layer.locked = true
        c.message = null
        assertFalse(tool.editLayer(layer))
        assertNotNull(c.message)
        assertNull(tool.item)
        layer.locked = false
        // Locked while being edited: ✓ keeps the edit pending instead of losing it.
        assertTrue(tool.editLayer(layer))
        tool.setText("Later")
        layer.locked = true
        assertFalse(tool.commitItem())
        assertNotNull(tool.item)
        assertSame(layer, tool.editingLayer)
        layer.locked = false
        assertTrue(tool.commitItem())
        assertEquals("Later", TextCodec.decode(layer.textData)!!.text)
    }
}
