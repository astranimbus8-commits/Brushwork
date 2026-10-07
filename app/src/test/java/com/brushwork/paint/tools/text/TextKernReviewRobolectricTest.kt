package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.HistoryLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.7 kerning end to end through the Text tool (item 17, area D review): what the tests of the
 * helpers and the renderer can't show on their own.
 * - A kerned text is ONE step when placed ("Add text") and when edited again ("Edit text"); undo
 *   and redo restore its data and pixels bit for bit, and the pixels always equal a fresh
 *   rendering of the stored item (I1).
 * - Its kerns and "Font kerning" survive saving and reopening, and the reopened text edits with
 *   the same kerns.
 * - The trash cell on a kerned text inside a folder is one "Delete text" step; undo puts the
 *   layer back in its folder, at its place.
 */
@RunWith(RobolectricTestRunner::class)
class TextKernReviewRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun controller(doc: Document): Pair<EditorController, TextTool> {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    /** A 400 × 300 canvas with two pixel layers, the Text tool selected. */
    private fun setUp(): Pair<EditorController, TextTool> {
        val doc = Document("k", "k", 400, 300)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(400, 300)) }
        doc.activeLayerIndex = 1
        return controller(doc)
    }

    /** "AVATAR WAVE" (48 px) at (200, 150), kerned at "A|V" and "T|A|R", committed: its new layer. */
    private fun kerned(c: EditorController, tool: TextTool): Layer {
        val before = c.doc.layers.toSet()
        tool.startTextAt(200f, 150f)
        tool.setText(TEXT)
        tool.setSizePx(48f)
        tool.setKerns(0..0, -200)
        tool.setKerns(2..3, 150)
        tool.confirmEditor()
        tool.commit()
        assertNull(tool.item)
        return c.doc.layers.single { it !in before && it.textData != null }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** I1: [layer]'s pixels equal a fresh rendering of the item it stores. */
    private fun assertRendered(layer: Layer, where: String) {
        val item = TextCodec.decode(layer.textData)!!
        val fresh = BitmapUtils.createLayerBitmap(layer.width, layer.height)
        TextRenderer.drawItem(Canvas(fresh), item, TextRenderer.prepare(item), null)
        assertArrayEquals("$where: the pixels are a fresh rendering of the stored text (I1)", pixels(fresh), pixels(layer.bitmap))
    }

    @Test
    fun kernedTextIsOneStepAndUndoRedoRestoreItBitForBit() {
        val (c, tool) = setUp()
        val steps = c.undoManager.undoCount
        val layer = kerned(c, tool)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("Add text", c.undoManager.undoLabel)
        val first = TextCodec.decode(layer.textData)!!
        assertEquals(listOf(TextKern(0, -200), TextKern(2, 150), TextKern(3, 150)), first.kerns)
        assertTrue(first.spec.fontKerning)
        assertRendered(layer, "placed")
        val data1 = layer.textData
        val ink1 = pixels(layer.bitmap)

        // Edited again: a kern changed, one removed, "Font kerning" off. One step.
        assertTrue(tool.editLayer(layer))
        assertEquals(first.kerns, tool.item!!.kerns)
        tool.setKerns(0..0, 0)
        tool.setKerns(7..7, 300)
        tool.updateSpec { it.copy(fontKerning = false) }
        assertTrue(tool.commitItem())
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals("Edit text", c.undoManager.undoLabel)
        val second = TextCodec.decode(layer.textData)!!
        assertEquals(listOf(TextKern(2, 150), TextKern(3, 150), TextKern(7, 300)), second.kerns)
        assertFalse(second.spec.fontKerning)
        assertRendered(layer, "edited")
        val data2 = layer.textData
        val ink2 = pixels(layer.bitmap)
        assertFalse("the edit shows", ink1.contentEquals(ink2))

        // Undo and redo: exactly the states before, bit for bit.
        c.undo()
        assertEquals(data1, layer.textData)
        assertArrayEquals("undo: the placed pixels", ink1, pixels(layer.bitmap))
        c.redo()
        assertEquals(data2, layer.textData)
        assertArrayEquals("redo: the edited pixels", ink2, pixels(layer.bitmap))
        c.undo()
        c.undo()
        assertEquals("undo of the add", -1, c.doc.indexOf(layer))
        c.redo()
        assertEquals(data1, layer.textData)
        assertArrayEquals(ink1, pixels(layer.bitmap))
    }

    @Test
    fun kernsAndFontKerningSurviveSavingAndReopening() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        val (c, tool) = setUp()
        val layer = kerned(c, tool)
        assertTrue(tool.editLayer(layer))
        tool.updateSpec { it.copy(fontKerning = false) }
        assertTrue(tool.commitItem())
        val ink = pixels(layer.bitmap)
        repo.save(c.doc, c.compositor.renderThumbnail(64))

        val loaded = repo.load(c.doc.id)
        val l2 = loaded.layers.first { it.id == layer.id }
        assertEquals(layer.textData, l2.textData)
        assertArrayEquals("the saved pixels", ink, pixels(l2.bitmap))
        val item = TextCodec.decode(l2.textData)!!
        assertEquals(listOf(TextKern(0, -200), TextKern(2, 150), TextKern(3, 150)), item.kerns)
        assertFalse(item.spec.fontKerning)
        assertRendered(l2, "reopened")

        // Edited after reopening: the same kerns, and the next one lands where it should.
        val (c2, tool2) = controller(loaded)
        assertTrue(tool2.editLayer(l2))
        assertEquals(item.kerns, tool2.item!!.kerns)
        tool2.setText("$TEXT!", TEXT.length + 1)
        assertTrue(tool2.commitItem())
        assertEquals("typing at the end keeps every kern", item.kerns, TextCodec.decode(l2.textData)!!.kerns)
        assertEquals(loaded.layers.size, c2.doc.layers.size)
        assertRendered(l2, "edited after reopening")
    }

    @Test
    fun deletingAKernedTextInAFolderIsOneStepAndUndoPutsItBack() {
        val (c, tool) = setUp()
        val layer = kerned(c, tool)
        val folder = c.putInNewFolder(layer)
        assertNotNull(folder)
        assertEquals(folder!!.id, layer.parentId)
        val order = c.doc.layers.toList()
        val data = layer.textData
        val ink = pixels(layer.bitmap)
        c.selectTool(ToolId.TEXT)
        val steps = c.undoManager.undoCount

        assertTrue(tool.editLayer(layer))
        tool.setKerns(0..0, 400)
        tool.objectDeletion!!.delete()
        assertEquals("gone", -1, c.doc.indexOf(layer))
        assertEquals("the folder stays", order.indexOf(folder) - 1, c.doc.indexOf(folder))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DELETE_TEXT, c.undoManager.undoLabel)
        assertNull(tool.item)

        c.undo()
        assertEquals("every layer in its place", order, c.doc.layers.toList())
        assertEquals("back in its folder", folder.id, layer.parentId)
        assertEquals("as committed (the pending kern is not kept)", data, layer.textData)
        assertArrayEquals(ink, pixels(layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
    }

    private companion object {
        const val TEXT = "AVATAR WAVE"
    }
}
