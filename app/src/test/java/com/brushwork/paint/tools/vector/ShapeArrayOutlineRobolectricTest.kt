package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (I14, gate-2 gap, area C): "Edit shape" on a shape layer with a live array. The brush
 * outline is replayed inside the draw `updateShapeLayer` repeats per copy, so every copy gets it
 * (the painting tool's stroke reached the source alone), with the source's pixels exactly those
 * of the same edit on a layer without an array; one undo step restores the pixels. While the
 * layer is open for editing its copies follow the edited shape (the edit override draws it once
 * per copy). Whole-pixel copies (constant offsets), so each copy's pixels are the source's moved.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeArrayOutlineRobolectricTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        ArrayDraw.clearCaches()
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 480
    private val h = 200

    /** Copies 150 px apart to the right. */
    private val spec = ArraySpec(count = 3, relativeX = 0f, constantX = 150f)

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("array-outline", "array-outline", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
            it.tools
            it.color = 0xFF2040C0.toInt()
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The [width] px wide columns from [x0] of every row. */
    private fun columns(p: IntArray, x0: Int, width: Int): IntArray = IntArray(width * h) { i -> p[(i / width) * w + x0 + i % width] }

    /** Pixels (alpha > 0) in the column [x], rows [y0]..[y1]. */
    private fun thickness(p: IntArray, x: Int, y0: Int, y1: Int): Int = (y0..y1).count { (p[it * w + x] ushr 24) > 0 }

    /** A brush-outlined line from (20, 50) to (120, 50), placed into its own shape layer. */
    private fun placeBrushLine(c: EditorController, tool: ShapeTool): Layer {
        tool.update { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.BRUSH, editable = true) }
        c.drag(20f to 50f, 70f to 50f, 120f to 50f)
        tool.flushPreview()
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        assertTrue(ShapeCodec.decode(layer.shapeData)!!.paintsWithBrush)
        return layer
    }

    /** Opens [layer], moves its line down by 60 px and commits ("Edit shape"). */
    private fun moveDown(c: EditorController, tool: ShapeTool, layer: Layer) {
        assertTrue(tool.editLayer(layer))
        assertSame(layer, tool.editingLayer)
        c.drag(70f to 50f, 70f to 80f, 70f to 110f)
        tool.flushPreview()
        tool.commit()
        assertNull(tool.editingLayer)
        assertEquals("Edit shape", c.undoManager.undoLabel)
    }

    @Test
    fun editingAnArrayedBrushShapePaintsTheOutlineOnEveryCopyInOneStep() {
        val c = controller()
        val tool = shapeTool(c)
        val plain = placeBrushLine(c, tool)
        val arrayed = c.duplicateLayer(plain)!!
        assertEquals(plain.shapeData, arrayed.shapeData)
        assertArrayEquals(pixels(plain.bitmap), pixels(arrayed.bitmap))
        // The array (data only: the source's pixels stay as they are until the next redraw).
        assertTrue(c.updateLayerData(arrayed, arrayed.dataSnapshot().copy(array = LayerArray(spec)), "Array", null, draw = null))
        assertEquals(LayerArray(spec), arrayed.array)
        c.selectTool(ToolId.SHAPE)

        moveDown(c, tool, plain)
        val reference = pixels(plain.bitmap)
        assertTrue("the plain layer's outline moved", thickness(reference, 70, 95, 125) in 4..9)
        assertEquals(0, thickness(reference, 70, 30, 70))

        val before = pixels(arrayed.bitmap)
        val steps = c.undoManager.undoCount
        moveDown(c, tool, arrayed)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertTrue(arrayed.isShapeLayer)
        assertEquals(LayerArray(spec), arrayed.array)
        val p = pixels(arrayed.bitmap)
        // The source: exactly the same edit's pixels on the layer without an array.
        assertArrayEquals(columns(reference, 0, 150), columns(p, 0, 150))
        // Every copy: the source moved by 150 px, outline included.
        for (k in 1..2) {
            assertArrayEquals("copy $k", columns(p, 0, 150), columns(p, 150 * k, 150))
            assertTrue("copy $k has the outline", thickness(p, 70 + 150 * k, 95, 125) in 4..9)
            assertEquals("copy $k left its old place", 0, thickness(p, 70 + 150 * k, 30, 70))
        }

        c.undo()
        assertArrayEquals(before, pixels(arrayed.bitmap))
        assertEquals(LayerArray(spec), arrayed.array)
    }

    @Test
    fun theCopiesFollowTheShapeWhileItIsOpenForEditing() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL, fillColor = 0xFF40C0E0.toInt(), editable = true, keepProportions = false, fromCenter = false) }
        c.drag(20f to 40f, 60f to 70f, 100f to 100f)
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        assertTrue(c.updateLayerData(layer, layer.dataSnapshot().copy(array = LayerArray(spec)), "Array", null, draw = null))
        c.selectTool(ToolId.SHAPE)

        assertTrue(tool.editLayer(layer))
        assertNotNull(c.renderOverride)
        val shown = BitmapUtils.createLayerBitmap(w, h)
        c.compositor.drawDocument(Canvas(shown), null, target = null)
        val p = pixels(shown)
        val inside = p[70 * w + 60]
        assertTrue("the source is shown", (inside ushr 24) > 0)
        assertNotEquals("the source differs from an empty place", p[180 * w + 470], inside)
        // The copies are drawn too, though the layer's own pixels (hidden while editing) have none.
        assertEquals(0, layer.bitmap.getPixel(60 + 150, 70) ushr 24)
        for (k in 1..2) assertEquals("copy $k", inside, p[70 * w + 60 + 150 * k])
        tool.discard()
        assertNull(c.renderOverride)
    }

    /**
     * Review (§3.6, I14): "Turn into path" on a shape layer with a live array keeps the array: the
     * vector layer's pixels are its EXPANDED content (every copy of the path), exactly as a fresh
     * render, and one undo gives the shape layer back with its array and pixels.
     */
    @Test
    fun turningAnArrayedShapeLayerIntoAPathKeepsTheArray() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL, fillColor = 0xFF40C0E0.toInt(), editable = true, keepProportions = false, fromCenter = false) }
        c.drag(20f to 40f, 60f to 70f, 100f to 100f)
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(c.updateLayerData(layer, layer.dataSnapshot().copy(array = LayerArray(spec)), "Array", null, draw = null))
        c.selectTool(ToolId.SHAPE)
        val data = layer.shapeData
        val before = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer))
        assertTrue(tool.turnIntoPath())
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue(layer.isVectorLayer)
        assertEquals(LayerArray(spec), layer.array)
        val fresh = BitmapUtils.createLayerBitmap(w, h)
        val whole = android.graphics.Rect(0, 0, w, h)
        VectorLayerRenderer.render(Canvas(fresh), ArrayDraw.effectiveVector(layer.dataSnapshot())!!, whole, tips = TipCache(), document = whole)
        val p = pixels(layer.bitmap)
        assertArrayEquals(pixels(fresh), p)
        for (k in 1..2) assertTrue("copy $k", (p[70 * w + 60 + 150 * k] ushr 24) > 0)
        c.undo()
        assertEquals(data, layer.shapeData)
        assertNull(layer.vector)
        assertEquals(LayerArray(spec), layer.array)
        assertArrayEquals(before, pixels(layer.bitmap))
    }
}
