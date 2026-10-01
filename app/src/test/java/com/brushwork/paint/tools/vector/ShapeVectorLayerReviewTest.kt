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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.5 A3 review: the shape tool on vector layers while a finger drags (plain shapes are drawn
 * in the overlay, as on raster layers, so the canvas tiles are not recomposited every frame,
 * and go back into the layer when it lifts), brush outlines that can't be live are shown as
 * guides, a live outline that leaves no pixels is placed from its data, and a reopened shape's
 * unused floating preview is freed.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeVectorLayerReviewTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 300
    private val h = 240
    private val fillColor = 0xFF20A040.toInt()

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
            it.color = 0xFFFF0000.toInt()
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[1]

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

    /** What the tool draws in the screen overlay (identity view). */
    private fun overlay(c: EditorController, tool: ShapeTool): Bitmap =
        BitmapUtils.createLayerBitmap(w, h).also { tool.drawOverlay(Canvas(it), c.viewTransform) }

    /** The layer as the compositor shows it now (its render override, else its pixels). */
    private fun shown(c: EditorController): Bitmap = BitmapUtils.createLayerBitmap(w, h).also { b ->
        val ov = c.renderOverride
        if (ov != null) ov.drawContent(Canvas(b)) else Canvas(b).drawBitmap(c.vec.bitmap, 0f, 0f, null)
    }

    @Test
    fun aPlainShapeIsDrawnInTheOverlayWhileDraggedOutAndInTheLayerAfterwards() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL, fillColor = fillColor) }
        c.pointerDown(ToolPoint(40f, 40f))
        c.pointerMove(ToolPoint(120f, 100f))
        c.pointerMove(ToolPoint(200f, 160f))
        // Mid-drag: the layer's tiles show the layer as it is, the shape is in the overlay.
        assertNull(c.renderOverride)
        assertEquals(fillColor, overlay(c, tool).getPixel(120f.toInt(), 100))
        c.pointerUp(ToolPoint(200f, 160f))
        // Lifted: the pending shape is drawn inside the layer again (with its order and look).
        assertNotNull(c.renderOverride)
        assertEquals(fillColor, shown(c).getPixel(120, 100))
        assertEquals(0, overlay(c, tool).getPixel(120, 100))
        // Moving it with a finger: in the overlay again until the finger lifts.
        c.pointerDown(ToolPoint(120f, 100f))
        c.pointerMove(ToolPoint(140f, 110f))
        c.pointerMove(ToolPoint(160f, 120f))
        assertNull(c.renderOverride)
        assertEquals(fillColor, overlay(c, tool).getPixel(180, 140))
        c.pointerUp(ToolPoint(160f, 120f))
        assertEquals(fillColor, shown(c).getPixel(180, 140))
        tool.commit()
        assertEquals(1, c.vec.vector!!.objects.size)
        assertEquals(1, c.undoManager.undoCount)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
    }

    @Test
    fun aReopenedShapeDraggedByAFingerIsDrawnInTheOverlayOverItsHole() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL, fillColor = fillColor) }
        c.pointerDown(ToolPoint(40f, 40f))
        c.pointerMove(ToolPoint(100f, 80f))
        c.pointerUp(ToolPoint(140f, 120f))
        tool.commit()
        val placed = c.vec.vector!!.objects.single()
        // Reopen it with a tap, then drag it.
        c.pointerDown(ToolPoint(90f, 80f))
        c.pointerUp(ToolPoint(90f, 80f))
        assertTrue(tool.editingObject)
        val session = c.renderOverride as VectorEditSession
        assertNotNull(session.drawPreview)
        c.pointerDown(ToolPoint(90f, 80f))
        c.pointerMove(ToolPoint(130f, 100f))
        c.pointerMove(ToolPoint(170f, 120f))
        assertTrue(c.renderOverride === session)
        assertNull(session.drawPreview)
        // The hole: the old place is empty in the layer's preview; the shape follows the finger.
        assertEquals(0, shown(c).getPixel(60, 60))
        assertEquals(fillColor, overlay(c, tool).getPixel(180, 140))
        c.pointerUp(ToolPoint(170f, 120f))
        assertNotNull(session.drawPreview)
        assertEquals(fillColor, shown(c).getPixel(180, 140))
        tool.commit()
        val moved = c.vec.vector!!.objects.single() as VShape
        assertEquals(placed.id, moved.id)
        assertEquals(2, c.undoManager.undoCount)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
    }

    @Test
    fun aBrushOutlineThatCannotBeLiveIsShownAsAGuide() {
        val c = controller()
        // The smudge tool paints brush outlines: it can't on a vector layer (only as a guide).
        c.selectTool(ToolId.SMUDGE)
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        c.pointerDown(ToolPoint(40f, 40f))
        c.pointerMove(ToolPoint(140f, 110f))
        c.pointerUp(ToolPoint(240f, 180f))
        tool.flushPreview()
        // Box (140, 110) 200 x 140: a point of the ellipse at 45 degrees, away from the handles.
        val x = (140f + 100f * cos(Math.PI / 4)).toInt()
        val y = (110f + 70f * sin(Math.PI / 4)).toInt()
        val o = overlay(c, tool)
        var painted = false
        for (dy in -2..2) for (dx in -2..2) if (o.getPixel(x + dx, y + dy) ushr 24 != 0) painted = true
        assertTrue("the outline is drawn as a guide", painted)
        assertTrue(pixels(c.vec.bitmap).all { it == 0 })
    }

    @Test
    fun aLiveOutlineThatLeftNoPixelsIsPlacedFromItsData() {
        val c = controller()
        // A brush that paints nothing (opacity 0): the live stroke commits no pixels.
        c.brush = BrushLibrary.defaultBrush.copy(size = 6f, opacity = 0f)
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        c.pointerDown(ToolPoint(40f, 40f))
        c.pointerMove(ToolPoint(100f, 90f))
        c.pointerUp(ToolPoint(160f, 140f))
        tool.flushPreview()
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertEquals(1, c.vec.vector!!.objects.size)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(ShapeTool.SHAPE_LABEL, c.undoManager.undoLabel)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        c.undo()
        assertEquals(VectorContent.EMPTY, c.vec.vector)
    }

    @Test
    fun theReopenedShapesFloatingPreviewIsFreed() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL, fillColor = fillColor) }
        c.pointerDown(ToolPoint(40f, 40f))
        c.pointerMove(ToolPoint(100f, 80f))
        c.pointerUp(ToolPoint(140f, 120f))
        tool.commit()
        // ✕
        c.pointerDown(ToolPoint(90f, 80f))
        c.pointerUp(ToolPoint(90f, 80f))
        val first = (c.renderOverride as VectorEditSession).floating
        assertNotNull(first)
        tool.discard()
        assertTrue(first!!.isRecycled)
        // ✓ with a change
        c.pointerDown(ToolPoint(90f, 80f))
        c.pointerUp(ToolPoint(90f, 80f))
        val second = (c.renderOverride as VectorEditSession).floating!!
        tool.place(tool.box!!.copy(cx = 160f))
        tool.commit()
        assertTrue(second.isRecycled)
        assertEquals(2, c.undoManager.undoCount)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
    }
}
