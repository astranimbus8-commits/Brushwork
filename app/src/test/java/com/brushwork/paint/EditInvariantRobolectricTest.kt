package com.brushwork.paint

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.undoStepNamed
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 foundation, invariant I2 (§3, §5.10 item 3): whatever edit listeners do, one user action
 * is one undo step — a listener folds its follow-up edit into the triggering step with
 * amendLastStep, events arrive once the step is complete, and undo / redo never fire them.
 */
@RunWith(RobolectricTestRunner::class)
class EditInvariantRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 100
    private val h = 80

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(3) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    /**
     * Re-renders the text layer [text] (like the text re-flow does) after every edit of another
     * layer, folded into that edit's step. Counts the events it saw.
     */
    private class Reflow(private val c: EditorController, private val text: Layer) : EditListener {
        val events = ArrayList<EditEvent>()
        var runs = 0

        override fun onEdited(e: EditEvent) {
            events += e
            if (e.layer === text) return
            runs++
            c.amendLastStep {
                c.updateTextLayer(text, "{\"run\":$runs}", "Reflow", Rect(0, 0, 20, 20)) { cv ->
                    cv.drawRect(0f, 0f, 10f + runs, 10f, Paint().apply { color = 0xFF0000FF.toInt() })
                }
            }
        }
    }

    private fun touchEdit(c: EditorController, layer: Layer, label: String = "Paint"): Boolean {
        val rec = c.beginEdit(layer, EditTarget.CONTENT)
        rec.touch(Rect(30, 30, 60, 60))
        Canvas(layer.bitmap).drawRect(30f, 30f, 60f, 60f, Paint().apply { color = 0xFFCC0000.toInt() })
        return c.commitEdit(rec, label)
    }

    private fun prepare(): Triple<EditorController, Layer, Reflow> {
        val c = setup()
        val text = c.doc.layers[2]
        text.textData = "{}"
        val r = Reflow(c, text)
        c.addEditListener(r)
        return Triple(c, text, r)
    }

    @Test
    fun commitEditStaysOneStep() {
        val (c, text, r) = prepare()
        val source = c.doc.layers[1]
        assertTrue(touchEdit(c, source))
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("the step keeps the edit's own label", "Paint", c.undoManager.undoLabel)
        assertEquals(1, r.runs)
        assertEquals("{\"run\":1}", text.textData)
        assertEquals(0xFF0000FF.toInt(), text.bitmap.getPixel(5, 5))
        // One undo takes back both the edit and the listener's follow-up; redo brings both.
        val events = r.events.size
        c.undo()
        assertEquals("{}", text.textData)
        assertEquals(0, text.bitmap.getPixel(5, 5))
        assertEquals(0, source.bitmap.getPixel(40, 40))
        c.redo()
        assertEquals("{\"run\":1}", text.textData)
        assertEquals(0xFFCC0000.toInt(), source.bitmap.getPixel(40, 40))
        assertEquals("undo / redo fire no events", events, r.events.size)
        assertEquals(1, r.runs)
    }

    @Test
    fun updateLayerDataAndSetLayerDataStayOneStep() {
        val (c, _, r) = prepare()
        val source = c.doc.layers[0]
        assertTrue(c.updateLayerData(source, LayerData(vector = VectorContent.EMPTY), "Edit objects", Rect(0, 0, 10, 10)) { })
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(1, r.runs)
        c.setLayerData(source, LayerData.NONE, "Rasterize")
        assertEquals(2, c.undoManager.undoCount)
        assertEquals(2, r.runs)
    }

    @Test
    fun groupUndoAndUndoStepNamedStayOneStepAndReportAfterTheStep() {
        val (c, _, r) = prepare()
        val a = c.doc.layers[0]
        val b = c.doc.layers[1]
        c.groupUndo("Curve") {
            touchEdit(c, a)
            touchEdit(c, b)
            assertEquals("no event while the step is being built", 0, r.runs)
        }
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Curve", c.undoManager.undoLabel)
        assertEquals(2, r.runs)
        c.undoStepNamed("Shape") {
            touchEdit(c, a, "Brush")
            assertEquals(2, r.runs)
        }
        assertEquals(2, c.undoManager.undoCount)
        assertEquals("Shape", c.undoManager.undoLabel)
        assertEquals(3, r.runs)
    }

    @Test
    fun aPlacementFoldedWithItsLayerStaysOneStep() {
        val (c, _, r) = prepare()
        val before = c.undoManager.undoCount
        val layer = c.addLayer(label = EditorController.PASTE_LABEL)!!
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        val image = BitmapUtils.createLayerBitmap(20, 20).also { it.eraseColor(0xFF00AA00.toInt()) }
        tool.startPlacement(layer, image, 10f, 10f, EditorController.PASTE_LABEL)
        tool.moveBy(5f, 0f)
        tool.commit()
        assertEquals("AddLayer + placement + the listener's follow-up are one step", before + 1, c.undoManager.undoCount)
        assertEquals(EditorController.PASTE_LABEL, c.undoManager.undoLabel)
        assertEquals(1, r.runs)
        c.undo()
        assertEquals(-1, c.doc.indexOf(layer))
        assertEquals("{}", c.doc.layers[2].textData)
    }

    @Test
    fun editsMadeWhileUndoingAreNotReported() {
        val (c, _, r) = prepare()
        val source = c.doc.layers[0]
        // A step whose undo makes a tool commit pixels (like a restored selection makes the
        // Transform tool apply its pending work).
        c.pushUndo(object : UndoAction {
            override val label = "Selection"
            override val byteSize = 0L
            override fun undo(c: EditorController) { touchEdit(c, source, "Transform") }
            override fun redo(c: EditorController) { touchEdit(c, source, "Transform") }
        })
        val n = r.events.size
        c.undo()
        assertEquals(n, r.events.size)
        assertEquals(0, r.runs)
        // Afterwards listeners hear edits again.
        touchEdit(c, source)
        assertEquals(1, r.runs)
    }

    @Test
    fun amendWithoutAStepAndListenerRemoval() {
        val (c, text, r) = prepare()
        c.amendLastStep { c.setLayerData(text, LayerData(text = "x"), "Data") }
        assertEquals("with nothing to amend, the step stays as it is", 1, c.undoManager.undoCount)
        c.removeEditListener(r)
        touchEdit(c, c.doc.layers[0])
        assertEquals(0, r.runs)
        assertNull(c.doc.layers[0].vector)
    }
}
