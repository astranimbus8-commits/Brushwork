package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.StubToolFixtures
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.After
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
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 3, §3.3 a; area E): the array operations on every source kind (the F5 stub test,
 * rewritten for the real `ArrayOps`). The Array tool still leaves no trace on a layer without an
 * array; making, editing, applying and removing an array is ONE step each, with its history
 * label; refusals come with their message and record nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayOpsRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun rectSelection(c: EditorController, l: Float, t: Float, r: Float, b: Float): Selection =
        Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false)

    private fun settle(c: EditorController) {
        Smoke.pumpUntil { !c.vectors.isRendering }
        Smoke.pump(40)
    }

    /** One step named [label] was recorded by [block]. */
    private fun <T> oneStep(c: EditorController, label: String, block: () -> T): T {
        val steps = c.undoManager.undoCount
        val out = block()
        settle(c)
        assertEquals("$label: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
        return out
    }

    /** [block] records nothing and shows [message]. */
    private fun refused(c: EditorController, message: String, block: () -> Boolean) {
        val steps = c.undoManager.undoCount
        c.message = null
        assertFalse(message, block())
        settle(c)
        assertEquals("$message: no step", steps, c.undoManager.undoCount)
        assertEquals(message, c.message)
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, id: Long = 0) = VPath(
        id,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFFE04020.toInt()),
    )

    @Test
    fun theArrayToolLeavesNoTraceOnLayersWithoutAnArray() {
        val c = Smoke.controller(app)
        StubToolFixtures.assertLeavesNoTrace(c, ToolId.ARRAY, StubToolFixtures.everyKind(c))
    }

    @Test
    fun refusalsComeWithTheirMessageAndRecordNothing() {
        val c = Smoke.controller(app)
        val kinds = StubToolFixtures.everyKind(c)
        refused(c, ArrayLabels.FOLDER_REFUSAL) { c.arrayWholeLayer(kinds.getValue("folder")) }
        refused(c, ArrayOps.ADJUSTMENT_REFUSAL) { c.arrayWholeLayer(kinds.getValue("adjustment")) }
        val raster = kinds.getValue("raster")
        c.selectLayer(raster)
        c.setSelection(null, recordUndo = false)
        refused(c, ArrayLabels.PLAIN_REFUSAL) { c.arrayFromSelection() }
        refused(c, ArrayLabels.PLAIN_REFUSAL) { c.arrayWholeLayer(raster) }
        // A selection over unpainted pixels.
        c.setSelection(rectSelection(c, 300f, 200f, 340f, 240f), recordUndo = false)
        refused(c, ArrayOps.EMPTY_SELECTION) { c.arrayFromSelection() }
        // An empty vector layer has nothing to repeat.
        val vector = kinds.getValue("vector")
        c.setSelection(null, recordUndo = false)
        c.selectLayer(vector)
        refused(c, ArrayOps.emptyLayer(vector.name)) { c.arrayWholeLayer(vector) }
        refused(c, ArrayOps.NO_OBJECTS) { c.arrayFromObjects(emptySet()) }
        // Without an array there is nothing to edit, apply, remove or finish.
        for (l in kinds.values) {
            assertFalse(ArrayOps.edit(c, l, ArraySpec(count = 5)))
            assertFalse(ArrayOps.remove(c, l))
            assertFalse(ArrayOps.finishSource(c, l))
            assertFalse(ArrayOps.editSource(c, l))
            assertNull(l.array)
        }
        assertNull(LayerTree.check(c.doc.layers))
    }

    @Test
    fun anArrayFromASelectionMovesThePixelsToANewLayerAbove() {
        val c = Smoke.controller(app)
        val src = c.doc.layers[1]
        src.opacity = 0.5f
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = 0xFFDD2211.toInt() })
        val before = pixels(src.bitmap)
        c.selectLayer(src)
        c.setSelection(rectSelection(c, 10f, 20f, 70f, 80f), recordUndo = false)
        oneStep(c, ArrayLabels.BUTTON) { assertTrue(c.arrayFromSelection()) }
        assertEquals(3, c.doc.layers.size)
        val layer = c.doc.layers[2]
        assertSame("the new layer is active", layer, c.activeLayer)
        assertEquals("Array 1", layer.name)
        assertEquals(0.5f, layer.opacity)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val a = layer.array!!
        assertEquals(ArraySpec(), a.spec)
        assertEquals(20, a.pixels!!.left)
        assertEquals(30, a.pixels.top)
        assertEquals(40, a.pixels.bitmap.width)
        // The source lost them; the copies sit side by side (relative X 100 %).
        assertEquals(0, src.bitmap.getPixel(30, 40))
        for (k in 0 until 3) assertEquals("copy $k", 0xFFDD2211.toInt(), layer.bitmap.getPixel(30 + 40 * k, 40))
        assertEquals(0, layer.bitmap.getPixel(30 + 40 * 3, 40))
        c.undo()
        settle(c)
        assertEquals(2, c.doc.layers.size)
        assertArrayEquals("one undo restores the source", before, pixels(src.bitmap))
    }

    @Test
    fun textAndShapeLayersAreArrayedInPlaceAndStayEditable() {
        val c = Smoke.controller(app)
        val item = TextItem("Hi", spec = TextSpec(sizePx = 40f), cx = 80f, cy = 60f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        oneStep(c, ArrayLabels.BUTTON) { assertTrue(c.arrayWholeLayer(text)) }
        assertNotNull(text.textData)
        assertEquals(ArraySpec(), text.array!!.spec)
        oneStep(c, ArrayLabels.EDIT) { assertTrue(ArrayOps.edit(c, text, ArraySpec(mode = ArrayMode.CIRCLE, count = 6))) }
        assertEquals(6, text.array!!.spec.count)
        // An equal spec records nothing.
        val steps = c.undoManager.undoCount
        assertTrue(ArrayOps.edit(c, text, ArraySpec(mode = ArrayMode.CIRCLE, count = 6)))
        assertEquals(steps, c.undoManager.undoCount)
        // Apply asks first for text; the answer applies (pixels, no text data).
        assertTrue(ArrayOps.apply(c, text))
        val tool = c.tools[ToolId.ARRAY] as ArrayTool
        assertSame(text, tool.pendingTextApply)
        oneStep(c, ArrayLabels.APPLY) { tool.answerTextApply(true) }
        assertNull(text.textData)
        assertNull(text.array)

        val o = ShapeObject(ShapeType.RECTANGLE, cx = 60f, cy = 200f, w = 40f, h = 30f)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { cv ->
            cv.drawRect(40f, 185f, 80f, 215f, Paint().apply { color = 0xFF2244CC.toInt() })
        }!!
        oneStep(c, ArrayLabels.BUTTON) { assertTrue(c.arrayWholeLayer(shape)) }
        oneStep(c, ArrayLabels.REMOVE) { assertTrue(ArrayOps.remove(c, shape)) }
        assertNull(shape.array)
        assertNotNull(shape.shapeData)
        oneStep(c, ArrayLabels.BUTTON) { assertTrue(c.arrayWholeLayer(shape)) }
        // A shape array applied: a vector layer with one shape per copy.
        oneStep(c, ArrayLabels.APPLY) { assertTrue(ArrayOps.apply(c, shape)) }
        assertNull(shape.shapeData)
        assertNull(shape.array)
        val objects = shape.vector!!.objects
        assertEquals(3, objects.size)
        assertTrue(objects.all { it is VShape })
        assertEquals("ids are distinct", 3, objects.map { it.id }.toSet().size)
    }

    @Test
    fun vectorObjectsMoveToANewLayerAndApplyAsRealObjects() {
        val c = Smoke.controller(app)
        val v = c.addVectorLayer()!!
        c.vectors.update(v, VectorContent.EMPTY.plus(listOf(box(20f, 20f, 50f, 50f), box(100f, 100f, 130f, 140f))).first, "Add")
        settle(c)
        val ids = v.vector!!.objects.map { it.id }
        oneStep(c, ArrayLabels.BUTTON) { assertTrue(c.arrayFromObjects(setOf(ids[0]))) }
        val layer = c.activeLayer
        assertTrue(layer !== v)
        assertEquals(listOf(ids[1]), v.vector!!.objects.map { it.id })
        assertEquals(listOf(ids[0]), layer.vector!!.objects.map { it.id })
        assertNotNull(layer.array)
        assertEquals("the cache shows copy 2", 0xFFE04020.toInt(), layer.bitmap.getPixel(35 + 2 * 30, 35))
        oneStep(c, ArrayLabels.EDIT) { assertTrue(ArrayOps.edit(c, layer, ArraySpec(count = 4, relativeX = 0f, constantY = 40f))) }
        oneStep(c, ArrayLabels.APPLY) { assertTrue(ArrayOps.apply(c, layer)) }
        assertNull(layer.array)
        val objects = layer.vector!!.objects
        assertEquals(4, objects.size)
        assertEquals("new ids", 4, objects.map { it.id }.toSet().size)
        assertTrue("real ids", objects.all { it.id < (1L shl ArrayDraw.COPY_ID_SHIFT) })
        val bounds = RectF().also { r -> objects.forEach { r.union(com.brushwork.paint.vector.VectorOps.bounds(it)) } }
        assertTrue("the copies went down", bounds.bottom >= 50f + 3 * 30f)
    }

    @Test
    fun aWholeVectorLayerIsArrayedInPlace() {
        val c = Smoke.controller(app)
        val v = c.addVectorLayer()!!
        c.vectors.update(v, VectorContent.EMPTY.plus(listOf(box(20f, 20f, 50f, 50f))).first, "Add")
        settle(c)
        val content = v.vector
        oneStep(c, ArrayLabels.BUTTON) { assertTrue(c.arrayWholeLayer(v)) }
        assertSame("the objects stay", content, v.vector)
        assertEquals(ArraySpec(), v.array!!.spec)
        // A second "Array…" opens the tool on it (no step).
        val steps = c.undoManager.undoCount
        assertTrue(c.arrayWholeLayer(v))
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ToolId.ARRAY, c.activeToolId)
    }
}
