package com.brushwork.paint.ui.layers

import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.core.Vec2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Undo of the "Layer deleted" message: layers are deleted without asking, and that Undo may
 * only take back the deletion it announced (not whatever became the last step since).
 */
@RunWith(RobolectricTestRunner::class)
class LayerDeleteUndoRobolectricTest {

    private val red = 0xFFFF0000.toInt()

    private fun newController(layers: Int = 3): EditorController {
        val doc = Document("t", "t", 16, 16)
        repeat(layers) { doc.layers += Layer(doc.newLayerId(), "Layer ${it + 1}", BitmapUtils.createLayerBitmap(16, 16)) }
        doc.activeLayerIndex = doc.layers.lastIndex
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope = CoroutineScope(Job().apply { cancel() })
        return EditorController(context, doc, scope, AppSettings(context).also { it.prefs.edit().clear().commit() })
    }

    /** Deletes the active layer like the layers window does; returns it and the history size after. */
    private fun delete(c: EditorController): Pair<Layer, Int> {
        val layer = c.activeLayer
        c.deleteLayer(layer)
        return layer to c.undoManager.undoCount
    }

    @Test
    fun rightAfterTheDeletionUndoBringsTheLayerBack() {
        val c = newController()
        Canvas(c.activeLayer.bitmap).drawRect(2f, 2f, 10f, 10f, Paint().apply { color = red })
        val (layer, count) = delete(c)
        assertEquals(2, c.doc.layers.size)
        assertEquals(LayerDeleteUndo.LABEL, c.undoManager.undoLabel)
        assertTrue(LayerDeleteUndo.canUndo(c, layer, count))
        c.undo()
        assertEquals(3, c.doc.layers.size)
        assertSame("the same layer, active again", layer, c.activeLayer)
        assertEquals(red, layer.bitmap.getPixel(5, 5))
        assertFalse("once back, there is nothing to take back", LayerDeleteUndo.canUndo(c, layer, count))
    }

    @Test
    fun anotherStepSinceMakesItStale() {
        val c = newController()
        val (layer, count) = delete(c)
        c.addLayer()
        assertFalse("a newer step is on top", LayerDeleteUndo.canUndo(c, layer, count))
        c.undo()
        assertTrue("the deletion is on top again", LayerDeleteUndo.canUndo(c, layer, count))
        // A second deletion: the first one's message no longer applies.
        delete(c)
        assertFalse(LayerDeleteUndo.canUndo(c, layer, count))
    }

    @Test
    fun pendingToolWorkBlocksIt() {
        val c = newController()
        val (layer, count) = delete(c)
        c.selectTool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.addAnchor(Vec2(2f, 2f))
        curve.addAnchor(Vec2(12f, 12f))
        assertTrue(c.currentTool.hasUserChanges)
        assertFalse("undo would take back the curve points instead", LayerDeleteUndo.canUndo(c, layer, count))
        curve.discard()
        assertTrue(LayerDeleteUndo.canUndo(c, layer, count))
    }

    @Test
    fun theLastLayerIsNeverDeleted() {
        val c = newController(layers = 1)
        val only = c.activeLayer
        c.deleteLayer(only)
        assertEquals(1, c.doc.layers.size)
        assertEquals("A drawing needs at least one layer", c.message)
        assertFalse(LayerDeleteUndo.canUndo(c, only, c.undoManager.undoCount))
    }
}
