package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Matrix
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Vector mode and the Text tool (v1.5 §4.9): text layers stay text layers, and after ✓ or ✕ of
 * a text placed or opened while a vector layer was active, that vector layer is active again, so
 * vector mode doesn't flip off.
 */
@RunWith(RobolectricTestRunner::class)
class TextVectorReselectRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A new canvas (Background + empty "Layer 1"), Vector tapped: Layer 1 becomes "Vector 1". */
    private fun vectorCanvas(): Triple<EditorController, Layer, TextTool> {
        val doc = Document("v", "v", 300, 200)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(300, 200)).also { it.bitmap.eraseColor(0xFFFFFFFF.toInt()) }
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(300, 200))
        doc.activeLayerIndex = 1
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.toggleVectorMode()
        assertTrue(c.isVectorMode)
        val vector = c.activeLayer
        assertEquals("Vector 1", vector.name)
        c.selectTool(ToolId.TEXT)
        return Triple(c, vector, c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun place(tool: TextTool, text: String, x: Float, y: Float) {
        tool.startTextAt(x, y)
        tool.setText(text)
        tool.confirmEditor()
    }

    @Test
    fun afterCheckTheVectorLayerIsActiveAgain() {
        val (c, vector, tool) = vectorCanvas()
        place(tool, "Title", 150f, 60f)
        assertSame("placing doesn't change the layer yet", vector, c.activeLayer)
        tool.commit()
        assertSame(vector, c.activeLayer)
        assertTrue(c.isVectorMode)
        val text = c.doc.layers[c.doc.indexOf(vector) + 1]
        assertTrue("a text layer above the vector layer", text.isTextLayer)
        assertTrue(vector.isVectorLayer)
        assertEquals("convert + add text", 2, c.undoManager.undoCount)
    }

    @Test
    fun openingATextAndCancellingGoesBackToo() {
        val (c, vector, tool) = vectorCanvas()
        place(tool, "Edit me", 150f, 60f)
        tool.commit()
        val text = c.doc.layers.first { it.isTextLayer }
        // Tap the text: it opens (its layer becomes active, vector mode is off meanwhile).
        c.pointerDown(ToolPoint(150f, 60f))
        c.pointerUp(ToolPoint(150f, 60f))
        assertSame(text, tool.editingLayer)
        assertSame(text, c.activeLayer)
        assertFalse(c.isVectorMode)
        tool.discard()
        assertSame(vector, c.activeLayer)
        assertTrue(c.isVectorMode)
        // Opened again and changed, then ✓: back to the vector layer as well.
        assertTrue(tool.editLayer(text))
        tool.setText("Edited")
        tool.commit()
        assertSame(vector, c.activeLayer)
        assertEquals("Edited", TextCodec.decode(text.textData)!!.text)
    }

    @Test
    fun switchingToolsWithATextPendingStaysInVectorMode() {
        val (c, vector, tool) = vectorCanvas()
        place(tool, "Pending", 150f, 60f)
        c.selectTool(ToolId.BRUSH)
        assertNull(tool.item)
        assertSame(vector, c.activeLayer)
        assertTrue(c.isVectorMode)
        assertTrue(c.doc.layers.any { it.isTextLayer })
    }

    @Test
    fun theVectorButtonWithATextPendingLeavesVectorMode() {
        val (c, vector, tool) = vectorCanvas()
        place(tool, "Pending", 150f, 60f)
        // Vector shows "on" (the vector layer is active); tapping it bakes the text and goes back.
        c.toggleVectorMode()
        assertFalse(c.isVectorMode)
        assertNull(tool.item)
        assertTrue(c.doc.layers.any { it.isTextLayer })
        assertTrue(vector.isVectorLayer)
        assertEquals("no extra vector layer was added", 1, c.doc.layers.count { it.isVectorLayer })
    }

    @Test
    fun aRasterLayerIsLeftAsIt() {
        val doc = Document("r", "r", 300, 200)
        val raster = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(300, 200))
        doc.layers += raster
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        place(tool, "Raster", 150f, 60f)
        tool.commit()
        assertTrue("the new text layer stays active, as before", c.activeLayer.isTextLayer)
        // An emptied new text placed nothing: no layer change.
        place(tool, "", 50f, 50f)
        assertNull(tool.item)
        assertTrue(c.activeLayer.isTextLayer)
    }
}
