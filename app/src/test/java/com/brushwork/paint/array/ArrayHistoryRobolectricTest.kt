package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 3, §3.3 a/b; area E review): every array action, on every source kind, is ONE step
 * whose undo gives the document back EXACTLY (the layer stack, every layer's data, cache pixels and
 * source pixels, and the active row when the step adds a layer: history moves the active row only
 * with the layer stack, as in v1.6) and whose redo gives the edited document back exactly: making
 * an array (from a selection, from objects, a whole text, shape or vector layer), "Edit array" in
 * every mode, "Apply array", "Remove array", "Edit source pixels" and "Finish source edit". Also:
 * arrays made inside a folder stay in it directly above their source; a selection over an arrayed
 * layer bakes that array in the same step ("Array applied"); and the limits ("Too many copies to
 * apply", the memory rule) refuse with their message and record nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayHistoryRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val red = 0xFFDD2211.toInt()

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun settle(c: EditorController) {
        Smoke.pumpUntil { !c.vectors.isRendering && c.busyMessage == null }
        Smoke.pump(40)
    }

    private fun rect(c: EditorController, l: Float, t: Float, r: Float, b: Float): Selection =
        Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false)

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFF2244CC.toInt()),
    )

    // ------------------------------------------------------------------ exact states

    private class LayerState(val id: Long, val name: String, val parentId: Long, val data: LayerData, val pixels: IntArray, val source: IntArray?)

    private class DocState(val active: Int, val layers: List<LayerState>)

    private fun state(c: EditorController) = DocState(
        c.doc.activeLayerIndex,
        c.doc.layers.map { l ->
            val d = l.dataSnapshot()
            LayerState(l.id, l.name, l.parentId, d, pixels(l.bitmap), d.array?.pixels?.bitmap?.let { pixels(it) })
        },
    )

    private fun assertState(where: String, want: DocState, got: DocState, activeRow: Boolean = true) {
        assertEquals("$where: the layers", want.layers.map { it.id }, got.layers.map { it.id })
        if (activeRow) assertEquals("$where: the active row", want.active, got.active)
        for ((a, b) in want.layers.zip(got.layers)) {
            assertEquals("$where: \"${a.name}\"'s name", a.name, b.name)
            assertEquals("$where: \"${a.name}\"'s folder", a.parentId, b.parentId)
            assertEquals("$where: \"${a.name}\"'s data", a.data, b.data)
            assertArrayEquals("$where: \"${a.name}\"'s pixels", a.pixels, b.pixels)
            assertArrayEquals("$where: \"${a.name}\"'s source pixels", a.source, b.source)
        }
    }

    /**
     * [block] records ONE step [label]; undo gives the document before it back exactly, redo the
     * document after it, a second undo the one before again; with [keep] a last redo keeps the
     * action for what follows.
     */
    private fun roundTrip(c: EditorController, label: String, what: String, keep: Boolean = false, block: () -> Unit) {
        settle(c)
        val before = state(c)
        val steps = c.undoManager.undoCount
        block()
        settle(c)
        assertEquals("$what: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(what, label, c.undoManager.undoLabel)
        val after = state(c)
        val stack = before.layers.map { it.id } != after.layers.map { it.id }
        c.undo(); settle(c)
        assertState("$what, undone", before, state(c), stack)
        c.redo(); settle(c)
        assertState("$what, redone", after, state(c), stack)
        c.undo(); settle(c)
        assertState("$what, undone again", before, state(c), stack)
        if (keep) {
            c.redo(); settle(c)
            assertState("$what, kept", after, state(c), stack)
        }
    }

    /** A text, a shape and a vector layer above the two plain ones; a red square painted on layer 2. */
    private fun sources(c: EditorController): Map<String, Layer> {
        val doc = c.doc
        val raster = doc.layers[1]
        Canvas(raster.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = red })
        raster.markChanged()
        val item = TextItem("Ab", spec = TextSpec(sizePx = 32f, color = 0xFF000000.toInt()), cx = 250f, cy = 50f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 300f, cy = 200f, w = 40f, h = 30f, style = ShapeStyle.FILL)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, doc.width, doc.height))!!
        val vector = c.addVectorLayer()!!
        c.vectors.update(vector, VectorContent.EMPTY.plus(listOf(box(40f, 200f, 70f, 230f), box(150f, 120f, 170f, 140f))).first, "Add")
        settle(c)
        c.vectors.flushPending()
        return mapOf("raster" to raster, "text" to text, "shape" to shape, "vector" to vector)
    }

    @Test
    fun everyActionOnEveryKindUndoesAndRedoesExactly() {
        val c = Smoke.controller(app)
        val src = sources(c)
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool

        // Making: from a selection (a new layer above), from objects (a new layer above), and
        // whole text, shape and vector layers in place.
        c.selectLayer(src.getValue("raster"))
        c.setSelection(rect(c, 10f, 20f, 70f, 80f), recordUndo = false)
        roundTrip(c, ArrayLabels.BUTTON, "Array from selection", keep = true) { assertTrue(c.arrayFromSelection()) }
        c.setSelection(null, recordUndo = false)
        val pixels = c.activeLayer
        assertNotNull(pixels.array?.pixels)
        roundTrip(c, ArrayLabels.BUTTON, "Array of a text layer", keep = true) { assertTrue(c.arrayWholeLayer(src.getValue("text"))) }
        roundTrip(c, ArrayLabels.BUTTON, "Array of a shape layer", keep = true) { assertTrue(c.arrayWholeLayer(src.getValue("shape"))) }
        val v = src.getValue("vector")
        c.selectLayer(v)
        roundTrip(c, ArrayLabels.BUTTON, "Array from objects", keep = true) { assertTrue(c.arrayFromObjects(setOf(v.vector!!.objects.first().id))) }
        val objects = c.activeLayer
        assertTrue(objects !== v && objects.array != null && objects.vector != null)
        roundTrip(c, ArrayLabels.BUTTON, "Array of a vector layer", keep = true) { assertTrue(c.arrayWholeLayer(v)) }

        val arrays = mapOf("pixels" to pixels, "text" to src.getValue("text"), "shape" to src.getValue("shape"), "objects" to objects, "vector" to v)
        val guide = VSubpath(listOf(VAnchor(55f, 215f), VAnchor(150f, 270f), VAnchor(250f, 215f)))
        val edits = listOf(
            ArraySpec(mode = ArrayMode.LINE, count = 4, relativeX = 0.5f, relativeY = 1f, constantX = 6f),
            ArraySpec(mode = ArrayMode.CIRCLE, count = 5, sweepDeg = 180f, rotateCopies = false),
            ArraySpec(mode = ArrayMode.CURVE, count = 4, guide = guide, alignToCurve = true),
            ArraySpec(mode = ArrayMode.TRANSFORM, count = 4, moveX = 20f, moveY = 5f, turnDeg = 25f, scale = 0.9f),
        )
        for ((kind, l) in arrays) {
            c.selectLayer(l)
            for (spec in edits) roundTrip(c, ArrayLabels.EDIT, "$kind: Edit array, ${spec.mode}") { assertTrue(ArrayOps.edit(c, l, spec)) }
            roundTrip(c, ArrayLabels.REMOVE, "$kind: Remove array") { assertTrue(ArrayOps.remove(c, l)) }
            roundTrip(c, ArrayLabels.APPLY, "$kind: Apply array") {
                assertTrue(ArrayOps.apply(c, l))
                // A text array asks first ("Apply turns the text into pixels").
                if (kind == "text") { assertSame(l, tool.pendingTextApply); tool.answerTextApply(true) }
            }
            assertNotNull("$kind: the live array is back", l.array)
        }

        // A raster source: "Edit source pixels", a pixel edit (not baked), "Finish source edit",
        // and "Apply array" while the source is being edited.
        c.selectLayer(pixels)
        roundTrip(c, ArrayLabels.EDIT_SOURCE, "Edit source pixels", keep = true) { assertTrue(ArrayOps.editSource(c, pixels)) }
        val rec = c.beginEdit(pixels)
        rec.touch(Rect(30, 40, 50, 60))
        Canvas(pixels.bitmap).drawRect(30f, 40f, 50f, 60f, Paint().apply { color = 0xFF11AA33.toInt() })
        assertTrue(c.commitEdit(rec, "Paint"))
        assertTrue("not baked", pixels.array!!.spec.editingSource)
        roundTrip(c, ArrayLabels.APPLY, "Apply array while editing the source") { assertTrue(ArrayOps.apply(c, pixels)) }
        roundTrip(c, ArrayLabels.FINISH_SOURCE, "Finish source edit") { assertTrue(ArrayOps.finishSource(c, pixels)) }
        Smoke.assertQuiet(c, "every action")
    }

    @Test
    fun arraysMadeInsideAFolderStayInItAboveTheirSource() {
        val c = Smoke.controller(app)
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = red })
        src.markChanged()
        val folder = c.putInNewFolder(src)!!
        assertEquals(folder.id, src.parentId)

        c.selectLayer(src)
        c.setSelection(rect(c, 10f, 20f, 70f, 80f), recordUndo = false)
        roundTrip(c, ArrayLabels.BUTTON, "Array from selection in a folder", keep = true) { assertTrue(c.arrayFromSelection()) }
        c.setSelection(null, recordUndo = false)
        val made = c.activeLayer
        assertEquals("in the source's folder", folder.id, made.parentId)
        assertEquals("directly above the source", c.doc.indexOf(src) + 1, c.doc.indexOf(made))
        assertNull(LayerTree.check(c.doc.layers))

        // A vector layer in the folder: its objects' array goes in the folder too.
        c.selectLayer(src)
        val v = c.addVectorLayer()!!
        assertEquals(folder.id, v.parentId)
        c.vectors.update(v, VectorContent.EMPTY.plus(listOf(box(200f, 100f, 240f, 140f))).first, "Add")
        settle(c)
        roundTrip(c, ArrayLabels.BUTTON, "Array from objects in a folder", keep = true) { assertTrue(c.arrayFromObjects(setOf(v.vector!!.objects.first().id))) }
        val objects = c.activeLayer
        assertEquals(folder.id, objects.parentId)
        assertEquals(c.doc.indexOf(v) + 1, c.doc.indexOf(objects))
        assertNull(LayerTree.check(c.doc.layers))

        // The folder itself is refused; the arrays inside it edit like any other.
        c.message = null
        assertFalse(c.arrayWholeLayer(folder))
        assertEquals(ArrayLabels.FOLDER_REFUSAL, c.message)
        roundTrip(c, ArrayLabels.EDIT, "Edit array in a folder") { assertTrue(ArrayOps.edit(c, made, ArraySpec(mode = ArrayMode.CIRCLE, count = 6))) }
        Smoke.assertQuiet(c, "folder")
    }

    @Test
    fun aSelectionOverAnArrayedLayerBakesItInTheSameStep() {
        val c = Smoke.controller(app)
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = red })
        src.markChanged()
        c.selectLayer(src)
        c.setSelection(rect(c, 10f, 20f, 70f, 80f), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        settle(c)
        val first = c.activeLayer
        // Copy 1 sits at 60..100: a selection over it makes a new array of those pixels.
        c.setSelection(rect(c, 62f, 20f, 98f, 80f), recordUndo = false)
        c.message = null
        roundTrip(c, ArrayLabels.BUTTON, "Array from a selection over an array", keep = true) { assertTrue(c.arrayFromSelection()) }
        assertEquals(ArrayLabels.APPLIED, c.message)
        assertNull("the layer under the selection is baked", first.array)
        val second = c.activeLayer
        assertTrue(second !== first)
        assertEquals("Array 2", second.name)
        assertEquals(62, second.array!!.pixels!!.left)
        assertEquals(red, second.bitmap.getPixel(70, 40))
        assertEquals("cut from the baked copies", 0, first.bitmap.getPixel(70, 40))
        assertEquals("the source stays", red, first.bitmap.getPixel(30, 40))
        c.undo(); settle(c)
        assertNotNull("one undo brings the live array back", first.array)
        Smoke.assertQuiet(c, "baked")
    }

    @Test
    fun theLimitsRefuseWithTheirMessageAndRecordNothing() {
        // "Too many copies to apply": 101 objects × 200 copies passes the 20 000 objects.
        val c = Smoke.controller(app)
        val v = c.addVectorLayer()!!
        val boxes = List(101) { i -> box(2f + (i % 20) * 9f, 2f + (i / 20) * 9f, 8f + (i % 20) * 9f, 8f + (i / 20) * 9f) }
        c.vectors.update(v, VectorContent.EMPTY.plus(boxes).first, "Add")
        settle(c)
        v.array = LayerArray(ArraySpec(count = ArraySpec.MAX_COUNT))
        val steps = c.undoManager.undoCount
        c.message = null
        assertFalse(ArrayOps.apply(c, v))
        settle(c)
        assertEquals(ArrayLabels.TOO_MANY, c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertNotNull(v.array)
        assertEquals(101, v.vector!!.objects.size)
        v.array = null
        Smoke.assertQuiet(c, "too many")

        // The memory rule (64 × 64 px layers, so the 100-layer cap is reached cheaply): the
        // array's source pixels count as a layer of their own.
        fun full(layers: Int): EditorController {
            val ctl = Smoke.controller(app, Smoke.document(64, 64, layers = layers))
            assumeTrue("the heap allows the 100-layer cap", ctl.maxLayers == 100)
            val l = ctl.doc.layers.last()
            Canvas(l.bitmap).drawRect(10f, 10f, 30f, 30f, Paint().apply { color = red })
            l.markChanged()
            ctl.selectLayer(l)
            ctl.setSelection(rect(ctl, 0f, 0f, 40f, 40f), recordUndo = false)
            return ctl
        }
        for (layers in listOf(99, 100)) {
            val ctl = full(layers)
            val n = ctl.undoManager.undoCount
            ctl.message = null
            assertFalse("$layers layers", ctl.arrayFromSelection())
            assertEquals("$layers layers", ArrayOps.MEMORY_REFUSAL, ctl.message)
            assertEquals(n, ctl.undoManager.undoCount)
            assertEquals(layers, ctl.doc.layers.size)
            Smoke.assertQuiet(ctl, "$layers layers")
        }
        val ok = full(98)
        assertTrue(ok.arrayFromSelection())
        assertEquals("the new layer and its source pixels", 100, ok.effectiveLayerCount)
        assertFalse(ok.canAddLayer)
        Smoke.assertQuiet(ok, "98 layers")
    }
}
