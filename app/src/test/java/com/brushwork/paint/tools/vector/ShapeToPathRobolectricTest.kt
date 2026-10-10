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
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
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
 * v1.7 item 6 (design §3.6, area C): "Turn into path". A shape layer becomes a vector layer with
 * one path that has a spline (data and pixels together, the vector renderer's), the Path tool is
 * active, and ONE undo restores the shape layer's data byte for byte and its pixels; a shape
 * object of a vector layer keeps its id, place and opacity; the pending edit is committed first
 * as its own step; arrows are refused. (That the converted corner is selected in the Path tool
 * needs area B's `openPath` body: checked at C's merge gate.)
 */
@RunWith(RobolectricTestRunner::class)
class ShapeToPathRobolectricTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 240
    private val h = 200

    /** A raster layer (active, or the vector layer when [vector]) and a vector layer; identity view. */
    private fun controller(vector: Boolean): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("to-path", "to-path", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = if (vector) 1 else 0
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

    private fun shapeTool(c: EditorController, type: ShapeType = ShapeType.RECTANGLE, editable: Boolean = true): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(
                type = type, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = 0xFF40C0E0.toInt(),
                corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false, editable = editable,
            )
        }
        return tool
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    @Test
    fun aShapeLayerBecomesAVectorLayerWithOnePathInOneStep() {
        val c = controller(vector = false)
        val tool = shapeTool(c)
        c.drag(50f to 50f, 100f to 90f, 150f to 130f)
        // Placed first (its own step "Shape"), so the shape layer is what one undo gives back.
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        val data = layer.shapeData!!
        val before = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        // Reopen it and turn it into a path: nothing pending changed, so ONE step.
        assertTrue(tool.editLayer(layer))
        assertTrue(tool.turnIntoPath())
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        assertTrue(layer.isVectorLayer)
        assertNull(layer.shapeData)
        val path = layer.vector!!.objects.single() as VPath
        assertNotNull("a Path tool path", path.spline)
        assertEquals(true, path.spline!!.cyclic)
        // No corner selected: every corner stays sharp and nothing moves.
        assertEquals(4, path.spline.points.size)
        assertTrue(path.spline.points.all { it.sharp })
        assertEquals(0xFF40C0E0.toInt(), (path.fill as com.brushwork.paint.vector.VPaint.Solid).color)
        // Data and pixels together: the layer shows the vector renderer's path.
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        assertEquals(ToolId.PATH, c.activeToolId)
        assertSame(layer, c.doc.activeLayer)
        // The Path tool holds the path unchanged, so undo passes it by.
        assertFalse(c.currentTool.hasUserChanges)
        // ONE undo: the shape layer's data byte for byte, and its pixels.
        c.undo()
        assertEquals(data, layer.shapeData)
        assertNull(layer.vector)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        assertTrue(layer.isVectorLayer)
        assertEquals(path, layer.vector!!.objects.single())
    }

    @Test
    fun inPointsModeTheSelectedCornerBecomesAnEditableArc() {
        val c = controller(vector = false)
        val tool = shapeTool(c)
        c.drag(50f to 50f, 100f to 90f, 150f to 130f)
        tool.commit()
        val layer = c.doc.activeLayer
        val data = layer.shapeData!!
        val steps = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer))
        tool.setPointEditing(true)
        tool.selectPoints(PointSelection.of(4, 0))
        assertTrue(tool.turnIntoPath())
        // The pending Points edit (the rectangle's own points) lands first, as its own step.
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        val sp = (layer.vector!!.objects.single() as VPath).spline!!
        // The selected corner is a sharp - smooth - sharp arc: 6 control points, ONE smooth
        // with weight cos(90° / 2).
        assertEquals(6, sp.points.size)
        val smooth = sp.points.filter { !it.sharp }
        assertEquals(1, smooth.size)
        assertEquals(Math.cos(Math.PI / 4).toFloat(), smooth[0].weight, 1e-4f)
        assertEquals(ToolId.PATH, c.activeToolId)
        assertFalse(c.currentTool.hasUserChanges)
        // Undo: the shape layer (as the Points edit left it); again: as it was.
        c.undo()
        assertTrue(layer.isShapeLayer)
        c.undo()
        assertEquals(data, layer.shapeData)
        assertEquals(steps, c.undoManager.undoCount)
    }

    /** Design §3.6, §5.1 C: the converted corner is selected in the Path tool (area B's `CurveTool.openPath`). */
    @Test
    fun theConvertedCornerIsSelectedInThePathTool() {
        val c = controller(vector = false)
        val tool = shapeTool(c)
        c.drag(50f to 50f, 100f to 90f, 150f to 130f)
        tool.setPointEditing(true)
        tool.selectPoints(PointSelection.of(4, 0))
        assertTrue(tool.turnIntoPath())
        val path = c.doc.activeLayer.vector!!.objects.single() as VPath
        val smooth = path.spline!!.points.indexOfFirst { !it.sharp }
        assertTrue(smooth >= 0)
        val pathTool = c.currentTool as CurveTool
        assertEquals(ToolId.PATH, c.activeToolId)
        assertTrue(pathTool.isReopened)
        assertEquals(smooth, pathTool.selectedIndex)
    }

    @Test
    fun aNewShapeIsPlacedFirstAsItsOwnStep() {
        val c = controller(vector = false)
        val tool = shapeTool(c)
        val layers = c.doc.layers.size
        c.drag(50f to 50f, 150f to 130f)
        assertTrue(tool.hasPendingWork)
        assertTrue(tool.turnIntoPath())
        assertEquals(layers + 1, c.doc.layers.size)
        assertEquals(2, c.undoManager.undoCount)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        val layer = c.doc.activeLayer
        assertTrue(layer.isVectorLayer)
        assertEquals(ToolId.PATH, c.activeToolId)
        // One undo: the shape layer; another: nothing placed.
        c.undo()
        assertTrue(layer.isShapeLayer)
        c.undo()
        assertEquals(layers, c.doc.layers.size)
    }

    @Test
    fun withEditableOffANewShapeStillGetsALayerOfItsOwn() {
        val c = controller(vector = false)
        val tool = shapeTool(c, editable = false)
        val raster = c.doc.layers[0]
        c.drag(50f to 50f, 150f to 130f)
        assertTrue(tool.turnIntoPath())
        // The raster layer is untouched; the path is in a new vector layer.
        assertTrue(pixels(raster.bitmap).all { it == 0 })
        assertTrue(c.doc.activeLayer.isVectorLayer)
        assertTrue(c.doc.activeLayer !== raster)
        assertEquals(ToolId.PATH, c.activeToolId)
    }

    @Test
    fun aShapeObjectKeepsItsIdPlaceAndOpacity() {
        val c = controller(vector = true)
        val tool = shapeTool(c)
        c.drag(50f to 50f, 150f to 130f)
        tool.commit()
        // A second object on top, so the converted one has a place to keep.
        tool.update { it.copy(type = ShapeType.ELLIPSE) }
        c.drag(160f to 20f, 230f to 90f)
        tool.commit()
        val layer = c.doc.layers[1]
        val first = layer.vector!!.objects[0] as VShape
        val ids = layer.vector!!.objects.map { it.id }
        // Reopen the rectangle with a tap and turn it into a path.
        c.drag(100f to 90f)
        assertTrue(tool.editingObject)
        val steps = c.undoManager.undoCount
        assertTrue(tool.turnIntoPath())
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        val after = layer.vector!!
        assertEquals(ids, after.objects.map { it.id })
        val path = after.objects[0] as VPath
        assertEquals(first.id, path.id)
        assertEquals(first.opacity, path.opacity)
        assertNotNull(path.spline)
        assertTrue(after.objects[1] is VShape)
        assertArrayEquals(render(after), pixels(layer.bitmap))
        assertEquals(ToolId.PATH, c.activeToolId)
        // The Path tool holds the path unchanged (a plain line has no grain to keep), so undo passes it by.
        assertFalse(c.currentTool.hasUserChanges)
        c.undo()
        assertEquals(first, layer.vector!!.objects[0])
    }

    @Test
    fun aBrushOutlinedShapeObjectKeepsItsBrushAndGrain() {
        val c = controller(vector = true)
        val tool = shapeTool(c)
        tool.update { it.copy(style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        c.drag(50f to 50f, 100f to 90f, 150f to 130f)
        tool.flushPreview()
        tool.commit()
        val layer = c.doc.layers[1]
        val shape = layer.vector!!.objects.single() as VShape
        c.drag(50f to 90f)
        assertTrue(tool.editingObject)
        assertTrue(tool.turnIntoPath())
        val path = layer.vector!!.objects.single() as VPath
        val st = path.stroke!!
        assertEquals(com.brushwork.paint.vector.VStrokeKind.BRUSH, st.kind)
        assertEquals(shape.shape.brushPreset, st.brush)
        assertEquals(shape.seed, st.seed)
        assertEquals(ToolId.PATH, c.activeToolId)
        // Unchanged in the Path tool, so ONE undo gives the shape object back.
        assertFalse(c.currentTool.hasUserChanges)
        c.undo()
        assertEquals(shape, layer.vector!!.objects.single())
    }

    /**
     * Review: a path lives in a vector layer, which can't hold an outline painted by a tool that
     * moves pixels. A shape layer outlined with a watercolor brush is refused before anything is
     * placed (the message a shape object of a vector layer gets); with a plain line it converts.
     */
    @Test
    fun anOutlineOfAToolThatMovesPixelsIsRefused() {
        val c = controller(vector = false)
        c.brush = c.brush.copy(tip = com.brushwork.paint.brush.BrushTip.WATERCOLOR)
        val tool = shapeTool(c)
        tool.update { it.copy(style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        c.drag(50f to 50f, 100f to 90f, 150f to 130f)
        tool.flushPreview()
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        val data = layer.shapeData
        val steps = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer))
        val refusal = tool.turnIntoPathRefusal
        assertNotNull(refusal)
        assertTrue(refusal!!, refusal.endsWith("outlines need a raster layer: choose \"Plain line\" or a painting brush"))
        assertFalse(tool.canTurnIntoPath)
        assertFalse(tool.turnIntoPath())
        assertEquals(refusal, c.message)
        assertEquals(ToolId.SHAPE, c.activeToolId)
        assertTrue("still open", tool.hasPendingWork)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(data, layer.shapeData)
        // A plain line: converted (the outline change lands first, as its own step).
        tool.update { it.copy(strokeWith = ShapeStroke.PLAIN) }
        assertNull(tool.turnIntoPathRefusal)
        assertTrue(tool.turnIntoPath())
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertTrue(layer.isVectorLayer)
        assertEquals(com.brushwork.paint.vector.VStrokeKind.PLAIN, (layer.vector!!.objects.single() as VPath).stroke!!.kind)
    }

    @Test
    fun arrowsAreRefused() {
        val c = controller(vector = false)
        val tool = shapeTool(c, ShapeType.ARROW)
        c.drag(50f to 50f, 150f to 130f)
        assertFalse(tool.canTurnIntoPath)
        assertEquals(PointLabels.ARROW_REFUSAL, tool.turnIntoPathRefusal)
        assertFalse(tool.turnIntoPath())
        assertEquals(PointLabels.ARROW_REFUSAL, c.message)
        assertTrue(tool.hasPendingWork)
        assertEquals(0, c.undoManager.undoCount)
        assertEquals(ToolId.SHAPE, c.activeToolId)
    }
}
