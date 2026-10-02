package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * v1.5 A3: the shape tool on vector layers (§4.9): a shape is ONE object and ONE undo step (no
 * new layer: "Editable" is implied), brush outlines shown live are kept and equal their replay
 * (the preview's seed is stored), a tap reopens a shape object (✓ is one step "Edit shape" in
 * place, ✕ restores the pixels exactly), a reopened brush outline shows no ghost, and the layer
 * stays a vector layer after every action.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeVectorLayerTest {
    private val red = 0xFFFF0000.toInt()
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 300
    private val h = 240

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("t", "t", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.color = red
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[1]

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) = drag(x to y)

    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    @Test
    fun aShapeOnAVectorLayerIsOneObjectAndOneStep() {
        val c = controller()
        val tool = shapeTool(c)
        assertTrue(tool.drawsOnVectorLayer)
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 5f, fillColor = 0xFF20A040.toInt()) }
        c.drag(40f to 40f, 120f to 100f, 180f to 160f)
        assertTrue(tool.hasPendingWork)
        // Previewed in the layer, which is untouched until the shape is placed.
        assertTrue(pixels(c.vec.bitmap).all { it == 0 })
        assertNotNull(c.renderOverride)
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertEquals(2, c.doc.layers.size)
        assertSame(c.vec, c.activeLayer)
        val s = c.vec.vector!!.objects.single() as VShape
        assertEquals(ShapeType.ELLIPSE, s.shape.type)
        assertEquals(ShapeBox(110f, 100f, 140f, 120f, 0f), s.shape.box)
        assertEquals(0xFF20A040.toInt(), s.shape.fillColor)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(ShapeTool.SHAPE_LABEL, c.undoManager.undoLabel)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        assertNull(c.renderOverride)
        // A second shape on top; undo / redo.
        c.drag(60f to 150f, 150f to 200f, 250f to 220f)
        tool.commit()
        assertEquals(2, c.vec.vector!!.objects.size)
        assertEquals(2, c.undoManager.undoCount)
        c.undo()
        assertEquals(1, c.vec.vector!!.objects.size)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        c.redo()
        assertEquals(2, c.vec.vector!!.objects.size)
        assertTrue(c.vec.isVectorLayer)
    }

    @Test
    fun aLiveBrushOutlineIsKeptAndEqualsItsReplay() {
        for (brush in listOf("pen", "chalk")) {
            val c = controller()
            c.brush = BrushLibrary.byId(brush)!!.copy(size = 7f)
            val tool = shapeTool(c)
            tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
            c.drag(40f to 40f, 150f to 120f, 230f to 190f)
            tool.flushPreview()
            // The brush stroke is live on the layer, inside the shape's preview.
            val shown = BitmapUtils.createLayerBitmap(w, h)
            assertTrue(c.renderOverride!!.drawContent(Canvas(shown)))
            assertTrue("$brush outline shown live", shown.getPixel(40, 100) != 0)
            tool.commit()
            val s = c.vec.vector!!.objects.single() as VShape
            assertTrue(s.shape.paintsWithBrush)
            assertEquals(brush, s.shape.brushPreset!!.id)
            assertEquals(1, c.undoManager.undoCount)
            assertEquals(ShapeTool.SHAPE_LABEL, c.undoManager.undoLabel)
            assertArrayEquals("$brush: the kept live pixels are the object's replay", render(c.vec.vector!!), pixels(c.vec.bitmap))
            assertNull(c.renderOverride)
            c.undo()
            assertTrue(pixels(c.vec.bitmap).all { it == 0 })
            assertEquals(VectorContent.EMPTY, c.vec.vector)
        }
    }

    @Test
    fun aBrushOutlineWithAFillIsRenderedFromItsData() {
        val c = controller()
        c.brush = BrushLibrary.byId("chalk")!!.copy(size = 9f)
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.STAR, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.BRUSH, fillColor = 0xFF3050F0.toInt()) }
        c.drag(60f to 40f, 160f to 120f, 240f to 200f)
        tool.flushPreview()
        tool.commit()
        assertEquals(1, c.vec.vector!!.objects.size)
        assertEquals(1, c.undoManager.undoCount)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
    }

    /** Places a plain rectangle (stroke + fill) and returns it. */
    private fun placeRect(c: EditorController, tool: ShapeTool): VShape {
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = 0xFF40C0E0.toInt()) }
        c.drag(50f to 50f, 100f to 80f, 150f to 130f)
        tool.commit()
        return c.vec.vector!!.objects.last() as VShape
    }

    @Test
    fun aTapReopensAShapeObjectAndCheckIsOneStepInPlace() {
        val c = controller()
        val tool = shapeTool(c)
        val first = placeRect(c, tool)
        // A second object on top, so the edited one has a z position to keep.
        tool.update { it.copy(type = ShapeType.ELLIPSE, fillColor = 0xFFE0A020.toInt()) }
        c.drag(180f to 60f, 230f to 160f)
        tool.commit()
        val before = c.vec.vector!!
        val steps = c.undoManager.undoCount
        c.tap(100f, 90f)
        assertTrue(tool.editingObject)
        assertTrue(tool.hasPendingWork)
        assertEquals(first.shape.box, tool.box)
        assertTrue(c.renderOverride is VectorEditSession)
        // Untouched: ✓ records nothing.
        assertFalse(tool.hasUserChanges)
        tool.commit()
        assertFalse(tool.editingObject)
        assertEquals(steps, c.undoManager.undoCount)
        assertSame(before, c.vec.vector)
        // Reopen, move and resize it, ✓.
        c.tap(100f, 90f)
        val b = tool.box!!
        tool.place(b.copy(cx = b.cx + 30f, w = b.w + 20f))
        assertTrue(tool.hasUserChanges)
        tool.commit()
        val after = c.vec.vector!!
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        assertEquals(before.objects.map { it.id }, after.objects.map { it.id })
        val edited = after.byId(first.id) as VShape
        assertEquals(b.cx + 30f, edited.shape.cx)
        assertEquals(b.w + 20f, edited.shape.w)
        assertEquals(first.shape.fillColor, edited.shape.fillColor)
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
        assertNull(c.renderOverride)
        // The user's own color came back.
        assertEquals(red, c.color)
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(render(before), pixels(c.vec.bitmap))
        assertTrue(c.vec.isVectorLayer)
    }

    @Test
    fun cancelLeavesTheLayerExactlyAsItWas() {
        val c = controller()
        val tool = shapeTool(c)
        placeRect(c, tool)
        val before = c.vec.vector!!
        val cache = pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        c.tap(100f, 90f)
        val b = tool.box!!
        tool.place(b.copy(cx = b.cx + 50f, rotationDeg = 20f))
        tool.discard()
        assertFalse(tool.editingObject)
        assertNull(c.renderOverride)
        assertSame(before, c.vec.vector)
        assertArrayEquals(cache, pixels(c.vec.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        // Undo while it is open (and changed) throws the change away first.
        c.tap(100f, 90f)
        tool.place(tool.box!!.copy(cx = 200f))
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertSame(before, c.vec.vector)
        assertArrayEquals(cache, pixels(c.vec.bitmap))
    }

    @Test
    fun aReopenedBrushOutlineShowsNoGhost() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        c.drag(40f to 40f, 90f to 90f, 140f to 140f)
        tool.flushPreview()
        tool.commit()
        val placed = c.vec.vector!!.objects.single() as VShape
        assertTrue(c.vec.bitmap.getPixel(40, 90) != 0)
        // Reopen it (tap its left edge) and move it 100 px to the right.
        c.tap(40f, 90f)
        assertTrue(tool.editingObject)
        val b = tool.box!!
        tool.place(b.copy(cx = b.cx + 100f))
        tool.flushPreview()
        val session = c.renderOverride as VectorEditSession
        assertNotNull("the live stroke is adopted", session.inner)
        val shown = BitmapUtils.createLayerBitmap(w, h)
        session.drawContent(Canvas(shown))
        // The old outline is gone from the preview (the hole), the moved one is the live stroke.
        assertEquals(0, shown.getPixel(40, 90))
        assertTrue(shown.getPixel(140, 90) != 0)
        assertTrue(shown.getPixel(240, 90) != 0)
        // The layer itself is untouched until ✓.
        assertTrue(c.vec.bitmap.getPixel(40, 90) != 0)
        tool.commit()
        val moved = c.vec.vector!!.objects.single() as VShape
        assertEquals(placed.id, moved.id)
        assertEquals(placed.seed, moved.seed)
        assertEquals(0, c.vec.bitmap.getPixel(40, 90))
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        assertEquals(2, c.undoManager.undoCount)
    }

    @Test
    fun outlinesThatMovePixelsAreRefusedOnVectorLayers() {
        val c = controller()
        // The smudge tool was the last painting tool (it paints brush outlines).
        c.selectTool(ToolId.SMUDGE)
        val tool = shapeTool(c)
        assertEquals(ToolId.SMUDGE, c.lastPaintTool)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        assertTrue(tool.outlineNeedsRaster)
        c.drag(40f to 40f, 140f to 140f)
        tool.flushPreview()
        // No live smudge on the layer's pixels; the outline is a guide.
        assertTrue(pixels(c.vec.bitmap).all { it == 0 })
        tool.commit()
        assertTrue(tool.hasPendingWork)
        assertTrue(c.message!!.contains("need a raster layer"))
        assertEquals(VectorContent.EMPTY, c.vec.vector)
        assertEquals(0, c.undoManager.undoCount)
        // A plain line works.
        tool.update { it.copy(strokeWith = ShapeStroke.PLAIN) }
        tool.commit()
        assertEquals(1, c.vec.vector!!.objects.size)
    }

    @Test
    fun onARasterLayerShapesStillGetTheirOwnLayer() {
        val c = controller()
        val vector = c.vec
        c.selectLayer(c.doc.layers[0])
        val tool = shapeTool(c)
        assertFalse(tool.drawsOnVectorLayer)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL) }
        c.drag(20f to 20f, 100f to 80f)
        tool.commit()
        assertEquals(3, c.doc.layers.size)
        assertTrue(c.activeLayer.isShapeLayer)
        assertEquals(VectorContent.EMPTY, vector.vector)
    }
}
