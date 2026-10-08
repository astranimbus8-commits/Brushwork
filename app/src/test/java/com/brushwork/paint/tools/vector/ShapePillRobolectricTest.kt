package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.tools.coordinateSourceOf
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
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
 * v1.7 items 1, 9 and 13 for shapes (design §3.1, §3.9, §3.13; area C), through the frozen pill
 * interfaces directly (the pill itself is area I's): the ONE stable position source falls back
 * from "Point n" to "Selected points" to "Center" and is what the X / Y strip routes to; the
 * Scale row scales the selected points or the whole shape about their centre, relative to the
 * size when the selection was taken, one in-tool step per edit; the trash cell's label per state,
 * and "Delete shape" on a shape layer and a shape object is ONE step that one undo takes back.
 */
@RunWith(RobolectricTestRunner::class)
class ShapePillRobolectricTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 240
    private val h = 200

    /** A raster layer and a vector layer ([vector]: active), identity view. */
    private fun controller(vector: Boolean = false): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("pill", "pill", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = if (vector) 1 else 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    /** A pending sharp rectangle 50..150 × 50..130 (centre 100, 90). */
    private fun rectangle(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f,
                fillColor = 0xFF40C0E0.toInt(), corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false,
            )
        }
        c.drag(50f to 50f, 100f to 90f, 150f to 130f)
        assertTrue(tool.hasPendingWork)
        return tool
    }

    private fun at(tool: ShapeTool, x: Float, y: Float): Int {
        val i = tool.docAnchors()!!.indexOfFirst { it.pos.distanceTo(Vec2(x, y)) < 0.01f }
        assertTrue("a point at $x, $y", i >= 0)
        return i
    }

    private fun assertVec(expected: Vec2, actual: Vec2?, tol: Float = 0.01f) {
        assertNotNull(actual)
        assertEquals("x", expected.x, actual!!.x, tol)
        assertEquals("y", expected.y, actual.y, tol)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun thePositionFallsBackFromPointToSelectedPointsToCenter() {
        val c = controller()
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        val pos = tool.pillPosition
        // Nothing pending: the pill hides, and so do its Scale row and trash cell.
        assertNull(pos.position)
        assertNull(tool.objectScale)
        assertNull(tool.objectDeletion)
        rectangle(c)
        // The X / Y strip routes to this ONE source (§4.6), the same object for the tool's lifetime.
        assertSame(pos, coordinateSourceOf(tool)!!.target)
        assertSame(pos, tool.pillPosition)
        assertEquals("Center", pos.label)
        assertVec(Vec2(100f, 90f), pos.position)
        // "Center" moves the shape (v1.6).
        pos.beginPositionEdit(); pos.setPosition(120f, null); pos.endPositionEdit()
        assertVec(Vec2(120f, 90f), tool.box!!.center)
        pos.beginPositionEdit(); pos.setPosition(100f, null); pos.endPositionEdit()
        // One point: "Point n", moved as v1.6's numbers move it, one in-tool step.
        tool.setPointEditing(true)
        val tl = at(tool, 50f, 50f)
        tool.selectPoints(PointSelection.of(4, tl))
        assertEquals("Point ${tl + 1}", pos.label)
        assertVec(Vec2(50f, 50f), pos.position)
        pos.beginPositionEdit(); pos.setPosition(40f, null); pos.setPosition(30f, 45f); pos.endPositionEdit()
        assertVec(Vec2(30f, 45f), tool.docAnchors()!![tl].pos)
        assertTrue(tool.undoStep())
        assertVec(Vec2(50f, 50f), tool.docAnchors()!![tl].pos)
        // Several: "Selected points" at their box centre; X then Y move the group, ONE step.
        val br = at(tool, 150f, 130f)
        tool.selectPoints(PointSelection.of(4, tl, br))
        assertEquals("Selected points", pos.label)
        assertVec(Vec2(100f, 90f), pos.position)
        pos.beginPositionEdit(); pos.setPosition(110f, null); pos.setPosition(null, 80f); pos.endPositionEdit()
        assertVec(Vec2(110f, 80f), pos.position)
        assertVec(Vec2(60f, 40f), tool.docAnchors()!![tl].pos)
        assertVec(Vec2(160f, 120f), tool.docAnchors()!![br].pos)
        // The others stay.
        assertVec(Vec2(150f, 50f), tool.docAnchors()!![at(tool, 150f, 50f)].pos)
        assertTrue(tool.undoStep())
        assertVec(Vec2(50f, 50f), tool.docAnchors()!![tl].pos)
        assertVec(Vec2(150f, 130f), tool.docAnchors()!![br].pos)
        assertEquals("the selection comes back with the step", 2, tool.pointSelection.count)
        // A typed value without begin / end is an edit of its own.
        pos.setPosition(105f, 95f)
        assertVec(Vec2(105f, 95f), pos.position)
        assertTrue(tool.undoStep())
        assertVec(Vec2(100f, 90f), pos.position)
        // No point selected in Points mode: "Center" again.
        tool.selectPoints(PointSelection.none(4))
        assertEquals("Center", pos.label)
        assertEquals(tool.settings.unit, tool.pillUnit)
    }

    @Test
    fun theScaleRowScalesTheSelectionOrTheShapeAboutItsCentre() {
        val c = controller()
        val tool = rectangle(c)
        val scale = tool.objectScale!!
        assertFalse(scale.uniformOnly)
        // The whole shape: 100 % as it opened; 200 % X doubles the width about the centre.
        assertVec(Vec2(100f, 100f), scale.scalePercent)
        scale.beginScaleEdit(); scale.setScale(200f, null); scale.endScaleEdit()
        assertEquals(200f, tool.box!!.w, 0.01f)
        assertEquals(80f, tool.box!!.h, 0.01f)
        assertVec(Vec2(100f, 90f), tool.box!!.center)
        assertVec(Vec2(200f, 100f), scale.scalePercent)
        scale.beginScaleEdit(); scale.setScale(100f, 100f); scale.endScaleEdit()
        // Two points: their box (100 × 80) is 100 %; 150 % scales it about its centre, ONE step.
        tool.setPointEditing(true)
        val tl = at(tool, 50f, 50f)
        val br = at(tool, 150f, 130f)
        tool.selectPoints(PointSelection.of(4, tl, br))
        assertVec(Vec2(100f, 100f), scale.scalePercent)
        scale.beginScaleEdit()
        scale.setScale(120f, 120f)
        scale.setScale(150f, 150f)
        scale.endScaleEdit()
        assertVec(Vec2(25f, 30f), tool.docAnchors()!![tl].pos)
        assertVec(Vec2(175f, 150f), tool.docAnchors()!![br].pos)
        assertVec(Vec2(150f, 150f), scale.scalePercent)
        // The unselected corners stay.
        assertVec(Vec2(150f, 50f), tool.docAnchors()!![at(tool, 150f, 50f)].pos)
        assertTrue(tool.undoStep())
        assertVec(Vec2(50f, 50f), tool.docAnchors()!![tl].pos)
        assertVec(Vec2(150f, 130f), tool.docAnchors()!![br].pos)
        // One axis: Y alone.
        scale.beginScaleEdit(); scale.setScale(null, 50f); scale.endScaleEdit()
        assertVec(Vec2(50f, 70f), tool.docAnchors()!![tl].pos)
        assertVec(Vec2(150f, 110f), tool.docAnchors()!![br].pos)
        assertTrue(tool.undoStep())
        // One point has no size: nothing to scale, and no step.
        tool.selectPoints(PointSelection.of(4, tl))
        assertVec(Vec2(100f, 100f), scale.scalePercent)
        val canUndo = tool.canUndoStep
        val before = tool.docAnchors()
        scale.beginScaleEdit(); scale.setScale(300f, 300f); scale.endScaleEdit()
        assertEquals(before, tool.docAnchors())
        assertEquals(canUndo, tool.canUndoStep)
    }

    @Test
    fun theTrashCellNamesWhatItDeletes() {
        val c = controller()
        val tool = rectangle(c)
        val del = tool.objectDeletion!!
        // Label table: no point mode, none, some, all selected.
        assertEquals(PillLabels.deleteObject("shape"), del.deleteLabel)
        assertEquals(HistoryLabels.DELETE_SHAPE, del.deleteLabel)
        tool.setPointEditing(true)
        assertEquals(HistoryLabels.DELETE_SHAPE, del.deleteLabel)
        tool.selectPoints(PointSelection.of(4, 0))
        assertEquals(PillLabels.DELETE_POINTS, del.deleteLabel)
        tool.selectPoints(PointSelection.of(4, 0, 2))
        assertEquals(PillLabels.DELETE_POINTS, del.deleteLabel)
        tool.selectPoints(PointSelection.all(4))
        assertEquals(HistoryLabels.DELETE_SHAPE, del.deleteLabel)
        // Some: the points go, one in-tool step; below the minimum: the existing message.
        tool.selectPoints(PointSelection.of(4, 0))
        del.delete()
        assertEquals(3, tool.pointCount)
        tool.selectPoints(PointSelection.of(3, 0))
        del.delete()
        assertEquals(3, tool.pointCount)
        assertEquals("A shape needs at least 3 points", c.message)
        assertTrue(tool.undoStep())
        assertEquals(4, tool.pointCount)
        // All: a shape never placed is cleared; nothing was recorded.
        tool.selectPoints(PointSelection.all(4))
        del.delete()
        assertFalse(tool.hasPendingWork)
        assertNull(tool.objectDeletion)
        assertEquals(0, c.undoManager.undoCount)
    }

    @Test
    fun deletingAShapeLayerIsOneStepThatOneUndoTakesBack() {
        val c = controller()
        val tool = rectangle(c)
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        val data = layer.shapeData!!
        val before = pixels(layer.bitmap)
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        // Reopened and changed (the change goes with it), then deleted from the pill.
        assertTrue(tool.editLayer(layer))
        tool.setPointEditing(true)
        tool.selectPoints(PointSelection.of(4, 0))
        tool.pillPosition.setPosition(30f, 30f)
        assertEquals(PillLabels.DELETE_POINTS, tool.objectDeletion!!.deleteLabel)
        // Every point selected: the whole shape.
        tool.selectPoints(PointSelection.all(4))
        assertEquals(HistoryLabels.DELETE_SHAPE, tool.objectDeletion!!.deleteLabel)
        tool.objectDeletion!!.delete()
        assertFalse(tool.hasPendingWork)
        assertEquals(layers - 1, c.doc.layers.size)
        assertTrue(c.doc.indexOf(layer) < 0)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DELETE_SHAPE, c.undoManager.undoLabel)
        // ONE undo: the layer, its data byte for byte and its pixels.
        c.undo()
        assertEquals(layers, c.doc.layers.size)
        assertTrue(c.doc.indexOf(layer) >= 0)
        assertEquals(data, layer.shapeData)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun deletingAShapeObjectIsOneStepThatOneUndoTakesBack() {
        val c = controller(vector = true)
        val tool = rectangle(c)
        tool.commit()
        val layer = c.doc.layers[1]
        val shape = layer.vector!!.objects.single() as VShape
        val before = pixels(layer.bitmap)
        c.drag(100f to 90f)
        assertTrue(tool.editingObject)
        assertEquals(HistoryLabels.DELETE_SHAPE, tool.objectDeletion!!.deleteLabel)
        val steps = c.undoManager.undoCount
        tool.objectDeletion!!.delete()
        assertFalse(tool.hasPendingWork)
        assertTrue(layer.vector!!.objects.isEmpty())
        assertTrue("the layer's pixels went with it", pixels(layer.bitmap).all { it == 0 })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DELETE_SHAPE, c.undoManager.undoLabel)
        c.undo()
        assertEquals(shape, layer.vector!!.objects.single())
        assertArrayEquals(before, pixels(layer.bitmap))
    }
}
