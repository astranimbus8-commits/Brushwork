package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.layers.LayerOps
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 3, §3.3 d; area E, I14): a live array stays right through the edits around it.
 * Making an array from a selection is undone in one step, exactly; editing a source text with the
 * Text tool re-renders the copies; painting bakes the array in the same step and one undo brings
 * it back; "Edit source pixels", a brush stroke and "Finish source edit" (run by reopening the
 * Array tool) change every copy without baking, and three undos give back the original array;
 * a canvas flip maps a vector array's spec (and bakes a raster one).
 */
@RunWith(RobolectricTestRunner::class)
class ArrayEditRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val red = 0xFFDD2211.toInt()
    private val blue = 0xFF2060C0.toInt()

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun controller(): EditorController = Smoke.controller(app).also {
        it.viewTransform.set(Matrix())
        it.color = blue
        it.brush = BrushLibrary.byId("hardround")!!.copy(size = 6f, pressureSize = false)
    }

    private fun settle(c: EditorController) {
        Smoke.pumpUntil { !c.vectors.isRendering && c.busyMessage == null }
        Smoke.pump(40)
    }

    /** A 40 × 40 red square at (20, 30) on layer 2, selected with a margin. */
    private fun paintedSource(c: EditorController): Layer {
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = red })
        c.selectLayer(src)
        c.setSelection(Selection.fromPath(Path().apply { addRect(10f, 20f, 70f, 80f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        return src
    }

    /** The red square as a raster array (count 3, side by side): the new, active layer; the selection is dropped (it would clip the strokes). */
    private fun rasterArray(c: EditorController): Layer {
        paintedSource(c)
        assertTrue(c.arrayFromSelection())
        settle(c)
        c.setSelection(null, recordUndo = false)
        return c.activeLayer.also { assertNotNull(it.array) }
    }

    private fun stroke(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..16) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / 16f, y0 + (y1 - y0) * i / 16f))
        c.pointerUp(ToolPoint(x1, y1))
        settle(c)
    }

    @Test
    fun anArrayFromASelectionIsUndoneExactlyInOneStep() {
        val c = controller()
        val src = paintedSource(c)
        val before = pixels(src.bitmap)
        val data = src.dataSnapshot()
        val layers = c.doc.layers.toList()
        val steps = c.undoManager.undoCount
        assertTrue(c.arrayFromSelection())
        settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        val layer = c.activeLayer
        val made = layer.array!!
        val cache = pixels(layer.bitmap)

        c.undo()
        settle(c)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals("the new layer is gone", layers, c.doc.layers.toList())
        assertSame(src, c.activeLayer)
        assertArrayEquals("the source has its pixels back", before, pixels(src.bitmap))
        assertEquals(data, src.dataSnapshot())
        // Redo brings the same array back.
        c.redo()
        settle(c)
        assertSame(layer, c.doc.layers[2])
        assertEquals(made, layer.array)
        assertArrayEquals(cache, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "array from a selection")
    }

    @Test
    fun editingTheSourceTextWithTheTextToolReRendersTheCopies() {
        val c = controller()
        val item = TextItem("Hi", spec = TextSpec(sizePx = 40f, color = 0xFF000000.toInt()), cx = 70f, cy = 100f)
        val layer = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        assertTrue(c.arrayWholeLayer(layer))
        settle(c)
        val spec = layer.array!!.spec
        val narrow = byHand(c, spec, item)
        assertArrayEquals("three copies of the text", narrow, pixels(layer.bitmap))

        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        assertTrue(text.editLayer(layer))
        text.setText("Hello")
        assertTrue(text.commitItem())
        settle(c)
        val edited = TextCodec.decode(layer.textData)!!
        assertEquals("Hello", edited.text)
        assertEquals("the array stays", spec, layer.array!!.spec)
        val wide = byHand(c, spec, edited)
        assertNotEquals(narrow.toList(), wide.toList())
        assertArrayEquals("the copies follow the wider text, nothing stale", wide, pixels(layer.bitmap))
        c.undo()
        settle(c)
        assertArrayEquals(narrow, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "text source")
    }

    @Test
    fun paintingBakesTheArrayAndOneUndoBringsItBack() {
        val c = controller()
        val layer = rasterArray(c)
        val array = layer.array!!
        val cache = pixels(layer.bitmap)
        c.selectTool(ToolId.BRUSH)
        val steps = c.undoManager.undoCount
        c.message = null
        stroke(c, 30f, 120f, 150f, 120f)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertNull("baked", layer.array)
        assertEquals(ArrayLabels.APPLIED, c.message)
        assertEquals("the copies stay as pixels", red, layer.bitmap.getPixel(30 + 40 * 2, 40))
        assertNotEquals(0, layer.bitmap.getPixel(90, 120))

        c.undo()
        settle(c)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals("the live array is back", array, layer.array)
        assertArrayEquals(cache, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "painting bakes")
    }

    @Test
    fun aSourcePixelEditChangesEveryCopyAndThreeUndosGiveTheArrayBack() {
        val c = controller()
        val layer = rasterArray(c)
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
        val array = layer.array!!
        val source = pixels(array.pixels!!.bitmap)
        val cache = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount

        // "Edit source pixels": the source alone, then the last painting tool.
        tool.editSource()
        settle(c)
        assertEquals(ArrayLabels.EDIT_SOURCE, c.undoManager.undoLabel)
        assertEquals(ToolId.BRUSH, c.activeToolId)
        assertTrue(layer.array!!.spec.editingSource)
        assertEquals(red, layer.bitmap.getPixel(30, 40))
        assertEquals("the copies are gone", 0, layer.bitmap.getPixel(30 + 40, 40))

        // A stroke across the source: an ordinary pixel step, nothing baked.
        c.message = null
        stroke(c, 26f, 50f, 54f, 50f)
        assertNotNull("not baked", layer.array)
        assertNull(c.message)
        val painted = layer.bitmap.getPixel(40, 50)
        assertNotEquals(red, painted)

        // Reopening the Array tool finishes the source edit: one step, the copies show the stroke.
        c.selectTool(ToolId.ARRAY)
        settle(c)
        assertEquals(steps + 3, c.undoManager.undoCount)
        assertEquals(ArrayLabels.FINISH_SOURCE, c.undoManager.undoLabel)
        val finished = layer.array!!
        assertFalse(finished.spec.editingSource)
        assertEquals(array.spec, finished.spec)
        assertEquals(20, finished.pixels!!.left)
        assertEquals(30, finished.pixels.top)
        assertEquals(painted, finished.pixels.bitmap.getPixel(20, 20))
        for (k in 0 until 3) {
            assertEquals("copy $k shows the stroke", painted, layer.bitmap.getPixel(40 + 40 * k, 50))
            assertEquals("copy $k", red, layer.bitmap.getPixel(30 + 40 * k, 35))
        }

        repeat(3) { c.undo(); settle(c) }
        assertEquals(steps, c.undoManager.undoCount)
        val back = layer.array!!
        assertEquals(array.spec, back.spec)
        assertEquals(array.pixels.left, back.pixels!!.left)
        assertEquals(array.pixels.top, back.pixels.top)
        assertArrayEquals("the original source", source, pixels(back.pixels.bitmap))
        assertArrayEquals("the original copies", cache, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "source edit")
    }

    @Test
    fun aCanvasFlipMapsAVectorArraysSpecAndBakesARasterOne() {
        val c = controller()
        val raster = rasterArray(c)
        val rasterArray = raster.array!!
        val rasterCache = pixels(raster.bitmap)
        val v = c.addVectorLayer()!!
        val box = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(20f, 150f, true), VAnchor(50f, 150f, true), VAnchor(50f, 180f, true), VAnchor(20f, 180f, true)), closed = true)),
            fill = VPaint.Solid(red),
        )
        c.vectors.update(v, VectorContent.EMPTY.plus(listOf(box)).first, "Add")
        settle(c)
        assertTrue(c.arrayWholeLayer(v))
        settle(c)
        val spec = v.array!!.spec
        assertEquals(1f, spec.relativeX)
        for (k in 0 until 3) assertEquals("copy $k", red, v.bitmap.getPixel(35 + 30 * k, 165))
        val content = v.vector
        val vCache = pixels(v.bitmap)

        assertTrue(LayerOps.flipCanvas(c, horizontal = true))
        settle(c)
        assertEquals("Flip canvas horizontally", c.undoManager.undoLabel)
        val w = c.doc.width
        val flipped = v.array!!.spec
        assertEquals("the copies now go left", -1f, flipped.relativeX)
        assertEquals(spec.count, flipped.count)
        for (k in 0 until 3) assertEquals("copy $k", red, v.bitmap.getPixel(w - 1 - (35 + 30 * k), 165))
        assertEquals(0, v.bitmap.getPixel(w - 1 - (35 + 30 * 3), 165))
        assertNull("a raster array is baked", raster.array)
        for (k in 0 until 3) assertEquals("raster copy $k", red, raster.bitmap.getPixel(w - 1 - (30 + 40 * k), 40))

        c.undo()
        settle(c)
        assertEquals(spec, v.array!!.spec)
        assertSame(content, v.vector)
        assertArrayEquals(vCache, pixels(v.bitmap))
        assertEquals(rasterArray, raster.array)
        assertArrayEquals(rasterCache, pixels(raster.bitmap))
        Smoke.assertQuiet(c, "canvas flip")
    }

    /** What a text array's cache must be: [item] under each `ArrayLayout` matrix, k = N - 1 down to 0. */
    private fun byHand(c: EditorController, spec: ArraySpec, item: TextItem): IntArray {
        val b = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        val cv = Canvas(b)
        val prepared = TextRenderer.prepare(item)
        val ms = ArrayLayout.matrices(spec, RectF(prepared.docBounds(item)))
        assertEquals(spec.count, ms.size)
        for (k in ms.size - 1 downTo 0) {
            cv.save()
            cv.concat(Matrix().apply { setValues(ms[k]) })
            TextRenderer.drawItem(cv, item, prepared, null)
            cv.restore()
        }
        return pixels(b)
    }
}
