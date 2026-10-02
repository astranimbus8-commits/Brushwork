package com.brushwork.paint

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.6 foundation (§4.3, §4.9, V3 / V4): layer-list events. Delete, duplicate, merge down,
 * add-with-content and paste each deliver ONE event, after their own step is complete; a
 * listener's amendment joins that step (one undo restores both); the tool's pending work commits
 * first as its own step; undo and redo deliver nothing.
 */
@RunWith(RobolectricTestRunner::class)
class LayerListEventTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 100
    private val h = 80

    /** Three layers ("Layer 1" at the bottom), the middle one active; [vectors]: layers 2 and 3 are vector layers. */
    private fun setup(vectors: Boolean = false): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(3) { i ->
            doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)).also {
                if (vectors && i > 0) it.vector = VectorContent.EMPTY
            }
        }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    /** What a listener saw: the event, and the history when it arrived. */
    private data class Seen(val e: LayerListEvent, val undoCount: Int, val undoLabel: String?)

    /** Records events; when [amend] is set, renames [other] inside the triggering step. */
    private class Recorder(private val c: EditorController, private val other: Layer?) : LayerListListener {
        val seen = ArrayList<Seen>()
        override fun onLayerList(e: LayerListEvent) {
            seen += Seen(e, c.undoManager.undoCount, c.undoManager.undoLabel)
            val o = other ?: return
            if (c.doc.indexOf(o) >= 0) c.amendLastStep { c.renameLayer(o, "Amended ${seen.size}") }
        }
    }

    private fun paint(layer: Layer) {
        Canvas(layer.bitmap).drawRect(10f, 10f, 40f, 40f, Paint().apply { color = 0xFF3366CC.toInt() })
        layer.markChanged()
    }

    @Test
    fun deleteDeliversOnceAfterItsStepAndTheAmendmentJoinsIt() {
        val c = setup()
        val keep = c.doc.layers[0]
        val victim = c.doc.layers[2]
        val r = Recorder(c, keep)
        c.addLayerListListener(r)
        val n0 = c.undoManager.undoCount
        c.deleteLayer(victim)
        assertEquals(1, r.seen.size)
        val s = r.seen.single()
        assertEquals(LayerListKind.REMOVED, s.e.kind)
        assertSame(victim, s.e.layer)
        assertEquals(null, s.e.source)
        assertEquals("Delete layer", s.e.label)
        assertEquals("delivered once its step is complete", n0 + 1, s.undoCount)
        assertEquals("Delete layer", s.undoLabel)
        // The amendment joined the step: still one step, and one undo restores both.
        assertEquals(n0 + 1, c.undoManager.undoCount)
        assertEquals("Amended 1", keep.name)
        r.seen.clear()
        c.undo()
        assertEquals(3, c.doc.layers.size)
        assertEquals("Layer 1", keep.name)
        c.redo()
        assertEquals(2, c.doc.layers.size)
        assertTrue("undo / redo deliver nothing", r.seen.isEmpty())
    }

    @Test
    fun duplicateMergeAddAndPasteEachDeliverOnce() {
        val c = setup()
        val r = Recorder(c, null)
        c.addLayerListListener(r)

        val original = c.doc.layers[1]
        val copy = c.duplicateLayer(original)
        assertNotNull(copy)
        assertEquals(LayerListKind.DUPLICATED, r.seen.single().e.kind)
        assertSame(copy, r.seen.single().e.layer)
        assertSame(original, r.seen.single().e.source)
        assertEquals("Duplicate layer", r.seen.single().e.label)
        assertEquals(c.undoManager.undoCount, r.seen.single().undoCount)
        r.seen.clear()

        val upper = c.doc.layers[2]
        val lower = c.doc.layers[1]
        c.mergeDown(upper)
        assertEquals(LayerListKind.MERGED, r.seen.single().e.kind)
        assertSame(upper, r.seen.single().e.layer)
        assertSame(lower, r.seen.single().e.source)
        assertEquals("Merge down", r.seen.single().undoLabel)
        r.seen.clear()

        val added = c.addLayerWithContent("Stamp", "Stamp") { cv -> cv.drawColor(0xFF00FF00.toInt()) }
        assertEquals(LayerListKind.ADDED, r.seen.single().e.kind)
        assertSame(added, r.seen.single().e.layer)
        assertEquals("Stamp", r.seen.single().e.label)
        r.seen.clear()

        paint(c.activeLayer)
        c.copySelection()
        val pasted = c.paste()
        assertNotNull(pasted)
        assertEquals("paste emits through addLayer, once", 1, r.seen.size)
        assertEquals(LayerListKind.ADDED, r.seen.single().e.kind)
        assertSame(pasted, r.seen.single().e.layer)
        assertEquals(EditorController.PASTE_LABEL, r.seen.single().e.label)
        r.seen.clear()

        val vector = c.addVectorLayer()
        assertEquals(LayerListKind.ADDED, r.seen.single().e.kind)
        assertSame(vector, r.seen.single().e.layer)
        r.seen.clear()

        var g = 20
        while (c.canUndo && g-- > 0) c.undo()
        while (c.canRedo && g++ < 40) c.redo()
        assertTrue("undo / redo deliver nothing: ${r.seen}", r.seen.isEmpty())
        c.removeLayerListListener(r)
        c.deleteLayer(c.doc.layers.last())
        assertTrue("a removed listener hears nothing", r.seen.isEmpty())
    }

    @Test
    fun theToolsPendingWorkCommitsFirstAsItsOwnStep() {
        val c = setup()
        val keep = c.doc.layers[0]
        val r = Recorder(c, keep)
        c.addLayerListListener(r)
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(50f, 40f)
        text.setText("Hi")
        text.confirmEditor()
        assertTrue(text.hasPendingWork)
        val n0 = c.undoManager.undoCount
        val victim = c.doc.layers[2]
        c.deleteLayer(victim)
        // Two steps: the text's own (its layer was ADDED in it), then the deletion.
        assertEquals(n0 + 2, c.undoManager.undoCount)
        assertEquals(listOf(LayerListKind.ADDED, LayerListKind.REMOVED), r.seen.map { it.e.kind })
        assertEquals("the text's event came with its own step", n0 + 1, r.seen[0].undoCount)
        assertEquals(r.seen[0].e.label, r.seen[0].undoLabel)
        assertEquals(n0 + 2, r.seen[1].undoCount)
        assertEquals("Delete layer", r.seen[1].undoLabel)
        // Each amendment joined its own triggering step.
        assertEquals("Amended 2", keep.name)
        c.undo()
        assertEquals("undoing the deletion undoes its amendment only", "Amended 1", keep.name)
        assertTrue(c.doc.indexOf(victim) >= 0)
    }

    /** A filled square as a vector path (document px). */
    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFFE04020.toInt()),
    )

    @Test
    fun vectorDuplicateAndVectorMergeDeliverOnceAfterTheirStep() {
        // The vector layer paths (VectorLayerOps.duplicateTouched / mergeVector) emit from their own
        // code: one event each, after the step, and an amendment joins that step.
        val c = setup(vectors = true)
        val keep = c.doc.layers[0]
        val lower = c.doc.layers[1]
        val upper = c.doc.layers[2]
        c.vectors.addObjects(lower, listOf(box(5f, 5f, 30f, 30f)), "Add")
        c.vectors.addObjects(upper, listOf(box(10f, 10f, 40f, 40f), box(60f, 40f, 90f, 70f)), "Add")
        val r = Recorder(c, keep)
        c.addLayerListListener(r)

        val sel = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8).also { m ->
            Canvas(m).drawRect(55f, 35f, 95f, 75f, Paint().apply { color = 0xFF000000.toInt() })
        }
        c.setSelection(Selection.wrap(sel), recordUndo = false)
        val n0 = c.undoManager.undoCount
        val copy = c.duplicateLayer(upper)
        assertNotNull(copy)
        assertTrue("the vector path: a vector layer of the touched object", copy!!.isVectorLayer)
        assertEquals(1, copy.vector!!.objects.size)
        val dup = r.seen.single()
        assertEquals(LayerListKind.DUPLICATED, dup.e.kind)
        assertSame(copy, dup.e.layer)
        assertSame(upper, dup.e.source)
        assertEquals("Duplicate selection", dup.e.label)
        assertEquals(n0 + 1, dup.undoCount)
        assertEquals("Duplicate selection", dup.undoLabel)
        assertEquals("the amendment joined the step", n0 + 1, c.undoManager.undoCount)
        assertEquals("Amended 1", keep.name)
        c.undo()
        assertEquals(-1, c.doc.indexOf(copy))
        assertEquals("Layer 1", keep.name)
        r.seen.clear()

        c.setSelection(null, recordUndo = false)
        val n1 = c.undoManager.undoCount
        c.mergeDown(upper)
        assertEquals(-1, c.doc.indexOf(upper))
        assertEquals("the vector merge kept the objects", 3, lower.vector!!.objects.size)
        val merged = r.seen.single()
        assertEquals(LayerListKind.MERGED, merged.e.kind)
        assertSame(upper, merged.e.layer)
        assertSame(lower, merged.e.source)
        assertEquals("Merge down", merged.e.label)
        assertEquals("delivered after the merge's own step", n1 + 1, merged.undoCount)
        assertEquals("Merge down", merged.undoLabel)
        assertEquals(n1 + 1, c.undoManager.undoCount)
        assertEquals("Amended 1", keep.name)
        r.seen.clear()
        c.undo()
        assertTrue(c.doc.indexOf(upper) >= 0)
        assertEquals("one undo restores the merge and its amendment", "Layer 1", keep.name)
        assertTrue("undo delivers nothing", r.seen.isEmpty())
    }

    @Test
    fun eventsInsideAGroupArriveWhenTheGroupEnds() {
        val c = setup()
        val r = Recorder(c, null)
        c.addLayerListListener(r)
        val n0 = c.undoManager.undoCount
        c.groupUndo("Two layers") {
            c.addLayer()
            c.addLayer()
            assertTrue("nothing delivered inside the step", r.seen.isEmpty())
        }
        assertEquals(n0 + 1, c.undoManager.undoCount)
        assertEquals(listOf(LayerListKind.ADDED, LayerListKind.ADDED), r.seen.map { it.e.kind })
        assertTrue(r.seen.all { it.undoCount == n0 + 1 && it.undoLabel == "Two layers" })
    }
}
