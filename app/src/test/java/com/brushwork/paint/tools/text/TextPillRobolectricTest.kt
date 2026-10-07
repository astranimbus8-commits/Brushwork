package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LayerStructure
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.tools.coordinateSourceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 §3.13 [D] (item 13) and the Text tool's other pill sources (items 1, 9, 10), through the
 * interfaces the pill reads ([PillPositionTool], [ScaledTool], [DeletingTool]) and the history
 * tap's mark, never through the pill's UI (area I's):
 * - the trash cell's label in every state: none open, a new text, an opened text layer;
 * - deleting a new text clears it with no step; deleting an opened text layer is ONE step
 *   ("Delete text") that ends the session, and one undo brings the layer back as it was;
 * - X / Y move the open text's centre; Scale is uniform, relative to the text as opened;
 * - a history tap takes back what its first finger changed in the open text.
 */
@RunWith(RobolectricTestRunner::class)
class TextPillRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    /** A 400 × 300 canvas with two pixel layers, the Text tool selected. */
    private fun setUp(): Pair<EditorController, TextTool> {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(400, 300)) }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    /** Places "Hello" (40 px, a 160 px box) at (200, 150), pending. */
    private fun place(tool: TextTool) {
        tool.startTextAt(200f, 150f)
        tool.setText("Hello pill")
        tool.setSizePx(40f)
        tool.updateBox { it.copy(width = 160f, padding = 6f) }
        tool.confirmEditor()
    }

    /** Places a text and commits it: its new layer. */
    private fun committed(c: EditorController, tool: TextTool): Layer {
        val before = c.doc.layers.toSet()
        place(tool)
        tool.commit()
        assertNull(tool.item)
        return c.doc.layers.single { it !in before && it.textData != null }
    }

    private fun label(tool: TextTool): String? = (tool as DeletingTool).objectDeletion?.deleteLabel

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun theTrashCellSaysWhatItDeletesInEveryState() {
        val (c, tool) = setUp()
        val text = PillLabels.deleteObject("text")
        assertEquals("Delete text", text)
        assertEquals(HistoryLabels.DELETE_TEXT, text)
        val table = ArrayList<Pair<String, String?>>()
        // Nothing open: no cell, no pill, no Scale row, no in-tool history.
        table += "none" to label(tool)
        assertNull(tool.pillPosition.position)
        assertNull(tool.objectScale)
        assertNull(tool.historyMark())
        // A new text, placed and not committed (with or without the editor open).
        tool.startTextAt(120f, 80f)
        table += "new, editor open" to label(tool)
        tool.setText("New")
        tool.confirmEditor()
        table += "new" to label(tool)
        tool.commit()
        table += "committed" to label(tool)
        // A text layer opened again.
        val layer = c.doc.layers.single { it.textData != null }
        assertTrue(tool.editLayer(layer))
        table += "opened layer" to label(tool)
        assertEquals(
            listOf("none" to null, "new, editor open" to text, "new" to text, "committed" to null, "opened layer" to text),
            table,
        )
        assertEquals("Center", tool.pillPosition.label)
    }

    @Test
    fun deletingANewTextClearsItWithoutAStep() {
        val (c, tool) = setUp()
        val layers = c.doc.layers.toList()
        val steps = c.undoManager.undoCount
        place(tool)
        tool.objectDeletion!!.delete()
        assertNull("cleared", tool.item)
        assertNull(tool.objectDeletion)
        assertEquals("no layer", layers, c.doc.layers.toList())
        assertEquals("no step", steps, c.undoManager.undoCount)
    }

    @Test
    fun deletingAnOpenedTextLayerIsOneStepAndOneUndoBringsItBack() {
        val (c, tool) = setUp()
        val layer = committed(c, tool)
        val index = c.doc.indexOf(layer)
        val data = layer.textData
        val ink = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount

        // Opened and changed (pending), then the trash cell.
        assertTrue(tool.editLayer(layer))
        tool.setText("Changed but not committed")
        tool.objectDeletion!!.delete()
        assertEquals("gone", -1, c.doc.indexOf(layer))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DELETE_TEXT, c.undoManager.undoLabel)
        assertNull("the session ended", tool.item)
        assertNull(tool.editingLayer)
        assertNull(tool.objectDeletion)

        // One undo: the layer as it was committed (its data and pixels), in its place.
        c.undo()
        assertEquals(index, c.doc.indexOf(layer))
        assertEquals(data, layer.textData)
        assertTrue("its pixels", ink.contentEquals(pixels(layer.bitmap)))
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        assertEquals(-1, c.doc.indexOf(layer))
        c.undo()

        // The drawing's last layer stays: the message, and the text stays open.
        val others = c.doc.layers.filter { it !== layer }
        for (l in others) c.deleteLayer(l)
        assertEquals(listOf(layer), c.doc.layers.toList())
        c.selectTool(ToolId.TEXT)
        assertTrue(tool.editLayer(layer))
        val before = c.undoManager.undoCount
        tool.objectDeletion!!.delete()
        assertEquals(LayerStructure.LAST_LAYER, c.message)
        assertSame(layer, c.doc.layers.single())
        assertNotNull(tool.item)
        assertEquals(before, c.undoManager.undoCount)
    }

    @Test
    fun thePillMovesAndScalesTheOpenText() {
        val (_, tool) = setUp()
        place(tool)
        // The pill's source is the tool's own, stable one.
        assertSame(tool.pillPosition, coordinateSourceOf(tool)!!.target)
        assertEquals(Vec2(200f, 150f), tool.pillPosition.position)
        tool.pillPosition.setPosition(230f, null)
        assertEquals(230f, tool.item!!.cx, 1e-4f)
        assertEquals(150f, tool.item!!.cy, 1e-4f)

        // Scale: uniform, 100 % = the text as placed, about its centre.
        val scale = tool.objectScale!!
        assertTrue(scale.uniformOnly)
        assertEquals(Vec2(100f, 100f), scale.scalePercent)
        scale.beginScaleEdit()
        scale.setScale(200f, null)
        scale.endScaleEdit()
        val big = tool.item!!
        assertEquals(80f, big.spec.sizePx, 1e-3f)
        assertEquals(320f, big.spec.box.width, 1e-3f)
        assertEquals(12f, big.spec.box.padding, 1e-3f)
        assertEquals(230f, big.cx, 1e-3f)
        assertEquals(150f, big.cy, 1e-3f)
        assertEquals(200f, scale.scalePercent!!.x, 1e-3f)
        // Relative to the text as placed, not to the last scale; Y alone works the same.
        scale.setScale(null, 50f)
        assertEquals(20f, tool.item!!.spec.sizePx, 1e-3f)
        assertEquals(Vec2(50f, 50f), scale.scalePercent)
        // Nonsense changes nothing.
        scale.setScale(Float.NaN, null)
        scale.setScale(-5f, null)
        assertEquals(20f, tool.item!!.spec.sizePx, 1e-3f)
    }

    @Test
    fun aHistoryTapTakesBackWhatItsFirstFingerChangedInTheOpenText() {
        val (c, tool) = setUp()
        place(tool)
        val before = tool.item!!
        assertEquals("unchanged: the same mark", tool.historyMark(), tool.historyMark())
        val mark = c.uiMark()
        // The first finger moved a slider (the size) before the second one landed.
        tool.setSizePx(90f)
        assertTrue(c.restoreUiMark(mark))
        c.releaseUiMark(mark)
        assertEquals(before, tool.item)
        // Nothing changed since the mark: nothing to take back.
        val again = c.uiMark()
        assertFalse(c.restoreUiMark(again))
        c.releaseUiMark(again)
        // A mark of another text never touches this one.
        tool.rollbackHistory(null)
        tool.rollbackHistory("not a mark")
        assertEquals(before, tool.item)
    }
}
