package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.vector.select.ObjectEdits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 review (R14, R19; I1): every new action on a text layer of several is ONE step that
 * Undo takes back exactly (the stored texts byte for byte, the layer's pixels) and Redo brings
 * back exactly: a new text joining the layer ("Add text"), an edit of one of its texts ("Edit
 * text"), Duplicate ("Duplicate in layer"), Bring forward and Send backward, and an emptied text
 * leaving the others ("Edit text").
 */
@RunWith(RobolectricTestRunner::class)
class MultiTextLayerUndoRedoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val black = 0xFF000000.toInt()

    private fun newController(): Pair<EditorController, TextTool> {
        val d = Document("t", "t", 400, 300).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300)) }
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, d, CoroutineScope(Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The layer as stored: its texts' JSON and its pixels. */
    private class State(val data: String?, val pixels: IntArray, val layers: Int)

    private fun state(c: EditorController, layer: Layer) = State(layer.textData, pixels(layer.bitmap), c.doc.layers.size)

    private fun assertState(what: String, want: State, c: EditorController, layer: Layer) {
        assertEquals("$what: the stored texts", want.data, layer.textData)
        assertEquals("$what: the layers", want.layers, c.doc.layers.size)
        assertArrayEquals("$what: the pixels", want.pixels, pixels(layer.bitmap))
    }

    /** Runs [action] (one step labelled [label]), then checks Undo and Redo restore exactly. */
    private fun oneStep(c: EditorController, tool: TextTool, layer: Layer, label: String, action: () -> Unit) {
        val before = state(c, layer)
        val steps = c.undoManager.undoCount
        action()
        assertNull("$label: nothing left open", tool.item)
        assertEquals("$label: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
        val after = state(c, layer)
        c.undo()
        assertState("$label, undone", before, c, layer)
        c.redo()
        assertState("$label, redone", after, c, layer)
    }

    @Test
    fun everyActionOnALayerOfSeveralTextsIsOneExactStep() {
        val (c, tool) = newController()
        tap(c, 80f, 60f)
        tool.setText("Alpha")
        tool.updateSpec { it.copy(sizePx = 40f, color = black) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)

        oneStep(c, tool, layer, "Add text") {
            tap(c, 300f, 220f)
            assertTrue(tool.joining)
            tool.setText("Beta")
            tool.confirmEditor()
            assertTrue(tool.commitItem())
        }
        assertEquals(listOf("Alpha", "Beta"), TextCodec.decodeAll(layer.textData).map { it.text })

        oneStep(c, tool, layer, "Edit text") {
            tap(c, 80f, 60f)
            assertEquals("Alpha", tool.item?.text)
            tool.updateSpec { it.copy(color = 0xFF2266CC.toInt()) }
            assertTrue(tool.commitItem())
        }

        oneStep(c, tool, layer, LayerLabels19.DUPLICATE_STEP) {
            tap(c, 80f, 60f)
            assertTrue(tool.duplicateOpen())
            tool.discard()
        }
        assertEquals(3, TextCodec.decodeAll(layer.textData).size)

        oneStep(c, tool, layer, ObjectEdits.Arrange.FORWARD.label) {
            assertTrue(tool.editLayer(layer, index = 0))
            assertTrue(tool.arrangeOpen(1))
            tool.discard()
        }

        oneStep(c, tool, layer, ObjectEdits.Arrange.BACKWARD.label) {
            assertTrue(tool.editLayer(layer, index = 1))
            assertTrue(tool.arrangeOpen(-1))
            tool.discard()
        }

        // An emptied text goes; the others stay.
        oneStep(c, tool, layer, "Edit text") {
            tap(c, 300f, 220f)
            assertEquals("Beta", tool.item?.text)
            tool.setText("")
            assertTrue(tool.commitItem())
        }
        assertEquals(2, TextCodec.decodeAll(layer.textData).size)
        c.dispose()
    }
}
