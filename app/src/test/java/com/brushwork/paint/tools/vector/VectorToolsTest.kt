package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Drives the shape / curve / polyline tools through the controller's real input dispatch. */
@RunWith(RobolectricTestRunner::class)
class VectorToolsTest {

    private val red = 0xFFFF0000.toInt()

    private fun controller(w: Int = 200, h: Int = 200): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = red
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) = drag(x to y)

    private fun EditorController.composite(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        compositor.drawDocument(Canvas(out), null)
        return out
    }

    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    // ------------------------------------------------------------------ shapes

    @Test
    fun dragCreatesEditableShapeThenCommitsWithUndo() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL) }
        c.drag(20f to 20f, 60f to 50f, 100f to 80f)
        assertTrue(tool.hasPendingWork)
        assertEquals(ShapeBox(60f, 50f, 80f, 60f, 0f), tool.box)
        val layer = c.doc.activeLayer
        assertEquals(0, layer.bitmap.getPixel(60, 50))       // not baked yet
        assertEquals(red, c.composite().getPixel(60, 50))    // but previewed
        assertFalse(c.canUndo)
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        assertEquals(red, layer.bitmap.getPixel(60, 50))
        assertEquals(0, layer.bitmap.getPixel(150, 150))
        assertTrue(c.canUndo)
        c.undo()
        assertEquals(0, layer.bitmap.getPixel(60, 50))
    }

    @Test
    fun cancelLeavesNoTrace() {
        val c = controller()
        val tool = shapeTool(c)
        c.pointerDown(ToolPoint(20f, 20f))
        c.pointerMove(ToolPoint(120f, 120f))
        c.pointerCancel()
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        assertFalse(c.canUndo)
        // Cancelling an edit of a pending shape restores it.
        c.drag(20f to 20f, 100f to 80f)
        val before = tool.box
        c.pointerDown(ToolPoint(60f, 50f))
        c.pointerMove(ToolPoint(90f, 90f))
        c.pointerCancel()
        assertEquals(before, tool.box)
        assertFalse(c.canUndo)
    }

    @Test
    fun tapOutsideCommitsAndDragOutsideStartsANewShape() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(style = ShapeStyle.FILL) }
        c.drag(10f to 10f, 50f to 50f)
        // Dragging outside: the old shape is only baked when the new one is released.
        c.pointerDown(ToolPoint(120f, 120f))
        c.pointerMove(ToolPoint(180f, 180f))
        assertFalse(c.canUndo)
        c.pointerCancel()
        assertEquals(ShapeBox(30f, 30f, 40f, 40f, 0f), tool.box) // still the first one
        c.drag(120f to 120f, 180f to 180f)
        assertTrue(c.canUndo)
        assertEquals(red, c.doc.activeLayer.bitmap.getPixel(30, 30))
        assertEquals(ShapeBox(150f, 150f, 60f, 60f, 0f), tool.box)
        // A tap outside commits the second.
        c.tap(10f, 190f)
        assertFalse(tool.hasPendingWork)
        assertEquals(red, c.doc.activeLayer.bitmap.getPixel(150, 150))
    }

    @Test
    fun moveResizeAndRotateHandles() {
        val c = controller(400, 400)
        val tool = shapeTool(c)
        tool.update { it.copy(snapAngle = true) }
        c.drag(100f to 100f, 200f to 160f)                      // box (150,130) 100x60
        c.drag(150f to 130f, 160f to 150f)                      // move by (10, 20)
        assertEquals(ShapeBox(160f, 150f, 100f, 60f, 0f), tool.box)
        c.drag(210f to 180f, 250f to 200f)                      // bottom-right handle
        assertEquals(ShapeBox(180f, 160f, 140f, 80f, 0f), tool.box)
        // Rotation handle sits 34 dp above the top edge (density 1, zoom 1).
        c.drag(180f to 86f, 300f to 158f)                       // drag to the right of the center
        assertEquals(90f, tool.box!!.rotationDeg, 1e-3f)
        // Numeric placement and nudging.
        tool.place(tool.box!!.copy(cx = 50f, w = 10f))
        tool.update { it.copy(nudgeStepPx = 3f) }
        tool.nudge(-1, 0)
        assertEquals(47f, tool.box!!.cx, 1e-4f)
        assertEquals(10f, tool.box!!.w, 1e-4f)
    }

    @Test
    fun tapsOnAPendingShapeDoNotChangeIt() {
        val c = controller(400, 400)
        val tool = shapeTool(c)
        tool.update { it.copy(snapAngle = true) }
        c.drag(103f to 104f, 200f to 170f)
        val before = tool.box!!
        // Grid snapping would move the shape if a tap counted as a drag.
        c.updateGrid(GridSettings(enabled = true, snap = true, spacingPx = 25f))
        c.tap(150f, 137f)                                 // inside (move)
        c.tap(before.cx + before.w / 2f + 1f, before.cy)  // right handle (resize), with jitter
        c.tap(before.cx + 3f, before.cy - before.h / 2f - 34f) // rotation handle, off-center
        assertEquals(before, tool.box)
        assertFalse(c.canUndo)
    }

    @Test
    fun rotationIsRelativeToWhereTheHandleWasGrabbed() {
        val c = controller(400, 400)
        val tool = shapeTool(c)
        tool.update { it.copy(snapAngle = false) }
        c.drag(100f to 100f, 200f to 160f)                // box (150,130) 100x60, handle at (150, 66)
        // Grabbed 12 px right of the handle: the first move past the slop must not jump.
        c.pointerDown(ToolPoint(162f, 66f))
        c.pointerMove(ToolPoint(162f, 80f))
        c.pointerUp(ToolPoint(162f, 80f))
        val v0 = Vec2(12f, -64f); val v1 = Vec2(12f, -50f)
        val expected = Math.toDegrees(ShapeGeometry.signedAngle(v0, v1).toDouble()).toFloat()
        assertEquals(expected, tool.box!!.rotationDeg, 1e-3f)
        assertTrue(kotlin.math.abs(tool.box!!.rotationDeg) < 5f)
    }

    @Test
    fun smallDragOutsideCommitsLikeATap() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(style = ShapeStyle.FILL) }
        c.drag(10f to 10f, 50f to 50f)
        // A flat drag outside makes no new shape (zero height) but still commits the pending one.
        c.drag(120f to 150f, 180f to 150f)
        assertFalse(tool.hasPendingWork)
        assertTrue(c.canUndo)
        assertEquals(red, c.doc.activeLayer.bitmap.getPixel(30, 30))
        assertNull(c.renderOverride)
    }

    @Test
    fun nonFiniteNumbersAreIgnored() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(strokeWidth = 12f) }
        tool.update { it.copy(strokeWidth = Float.NaN, cornerRadius = Float.POSITIVE_INFINITY) }
        assertEquals(12f, tool.settings.strokeWidth, 0f)
        assertEquals(30f, tool.settings.cornerRadius, 0f)
        assertTrue(tool.ensurePending())
        val b = tool.box!!
        tool.place(b.copy(cx = Float.NaN))
        tool.place(b.copy(rotationDeg = Float.NEGATIVE_INFINITY))
        assertEquals(b, tool.box)
        val curve = curveTool(c, polyline = false)
        assertFalse(curve.addAnchor(Vec2(Float.NaN, 3f)))
        assertTrue(curve.addAnchor(Vec2(10f, 10f)))
        curve.moveAnchor(0, Vec2(5f, Float.NaN))
        assertEquals(Vec2(10f, 10f), curve.anchors[0].pos)
        curve.update { it.copy(plainWidth = Float.NaN, tension = 0.3f) }
        assertEquals(6f, curve.settings.plainWidth, 0f)
        assertEquals(0.3f, curve.settings.tension, 0f)
    }

    @Test
    fun linesSnapAngleAndGrid() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.LINE, snapAngle = true) }
        c.drag(10f to 10f, 100f to 16f)
        val b = tool.box!!
        assertEquals(0f, b.rotationDeg, 1e-3f)
        assertEquals(10f, b.end.y, 1e-3f)
        tool.discard()
        c.updateGrid(GridSettings(enabled = true, snap = true, spacingPx = 10f))
        tool.update { it.copy(type = ShapeType.RECTANGLE) }
        c.drag(12f to 13f, 57f to 44f)
        assertEquals(ShapeBox(35f, 25f, 50f, 30f, 0f), tool.box)
    }

    @Test
    fun keepProportionsAndFromCenter() {
        val c = controller(400, 400)
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.ELLIPSE, keepProportions = true, fromCenter = true) }
        c.drag(200f to 200f, 260f to 220f)
        assertEquals(ShapeBox(200f, 200f, 120f, 120f, 0f), tool.box)
        // Switching to a line converts the pending shape.
        tool.update { it.copy(type = ShapeType.LINE) }
        assertEquals(0f, tool.box!!.h, 0f)
        assertEquals(Vec2(140f, 140f).x, tool.box!!.start.x, 1e-3f)
    }

    @Test
    fun lockedLayerRefusesAndUndoDiscardsPending() {
        val c = controller()
        val tool = shapeTool(c)
        c.doc.activeLayer.locked = true
        c.drag(20f to 20f, 100f to 100f)
        assertFalse(tool.hasPendingWork)
        assertNotNull(c.message)
        c.doc.activeLayer.locked = false
        c.drag(20f to 20f, 100f to 100f)
        assertTrue(tool.hasPendingWork)
        c.undo() // with pending work, undo throws the shape away
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        assertFalse(c.canUndo)
    }

    @Test
    fun switchingToolsCommitsPendingShape() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(style = ShapeStyle.FILL) }
        c.drag(20f to 20f, 100f to 100f)
        c.selectTool(ToolId.BRUSH)
        assertFalse(tool.hasPendingWork)
        assertEquals(red, c.doc.activeLayer.bitmap.getPixel(60, 60))
    }

    // ------------------------------------------------------------------ curves

    private fun curveTool(c: EditorController, polyline: Boolean): CurveTool {
        val id = if (polyline) ToolId.POLYLINE else ToolId.CURVE
        c.selectTool(id)
        return c.tools.getValue(id) as CurveTool
    }

    @Test
    fun polylinePlainLineCommit() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 6f) }
        c.tap(20f, 50f)
        c.tap(180f, 50f)
        c.tap(180f, 150f)
        assertEquals(3, tool.anchors.size)
        assertTrue(tool.anchors.all { it.sharp })
        assertEquals(red, c.composite().getPixel(100, 50)) // plain line preview
        tool.commit()
        val bmp = c.doc.activeLayer.bitmap
        assertEquals(red, bmp.getPixel(100, 50))
        assertEquals(red, bmp.getPixel(180, 100))
        assertEquals(0, bmp.getPixel(100, 100))
        assertFalse(tool.hasPendingWork)
        c.undo()
        assertEquals(0, bmp.getPixel(100, 50))
    }

    @Test
    fun curveEditingInsertSelectSharpDeleteUndo() {
        val c = controller()
        val tool = curveTool(c, polyline = false)
        c.tap(20f, 150f)
        c.tap(100f, 30f)
        c.tap(180f, 150f)
        assertEquals(3, tool.anchors.size)
        // Tapping on the path inserts an anchor into that segment.
        val onPath = CurveGeometry.nearest(tool.anchors, Vec2(55f, 80f), false, 0f, false)!!.point
        c.tap(onPath.x, onPath.y)
        assertEquals(4, tool.anchors.size)
        assertEquals(onPath.x, tool.anchors[1].x, 1e-3f)
        // Long-press selects an anchor; the rest of the gesture is ignored.
        c.pointerDown(ToolPoint(100f, 30f))
        assertTrue(c.pointerLongPress(ToolPoint(100f, 30f)))
        c.pointerMove(ToolPoint(140f, 60f))
        c.pointerUp(ToolPoint(140f, 60f))
        assertEquals(2, tool.selected)
        assertEquals(Vec2(100f, 30f), tool.anchors[2].pos)
        tool.setSharp(2, true)
        assertTrue(tool.anchors[2].sharp)
        tool.deleteAnchor(0)
        assertEquals(3, tool.anchors.size)
        // In-tool undo walks back delete, sharp and insert.
        tool.undoStep()
        assertEquals(4, tool.anchors.size)
        tool.undoStep()
        assertFalse(tool.anchors[2].sharp)
        tool.undoStep()
        assertEquals(3, tool.anchors.size)
        // Dragging an anchor moves it.
        c.drag(180f to 150f, 170f to 170f)
        assertEquals(Vec2(170f, 170f), tool.anchors[2].pos)
        // Cancel removes a point added in the cancelled gesture.
        c.pointerDown(ToolPoint(30f, 30f))
        c.pointerCancel()
        assertEquals(3, tool.anchors.size)
        assertFalse(c.canUndo)
    }

    @Test
    fun tangentHandleOverridesAndKeepsSmoothness() {
        val c = controller()
        val tool = curveTool(c, polyline = false)
        c.tap(20f, 100f); c.tap(100f, 40f); c.tap(180f, 100f)
        tool.select(1)
        val (hIn, hOut) = tool.handlesOf(1)
        val grab = tool.anchors[1].pos + hOut
        c.drag(grab.x to grab.y, 150f to 40f)
        val a = tool.anchors[1]
        assertTrue(a.hasCustomTangent)
        assertEquals(Vec2(50f, 0f), a.handleOut)
        assertEquals(-hIn.length, a.handleIn!!.x, 1e-3f) // collinear, keeps its length
        tool.resetTangent(1)
        assertFalse(tool.anchors[1].hasCustomTangent)
    }

    @Test
    fun closedFilledPathWithoutStroke() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.NONE, fill = true, closed = true) }
        c.tap(40f, 40f); c.tap(160f, 40f); c.tap(160f, 160f); c.tap(40f, 160f)
        tool.commit()
        val bmp = c.doc.activeLayer.bitmap
        assertEquals(red, bmp.getPixel(100, 100))
        assertEquals(0, bmp.getPixel(20, 20))
        assertTrue(c.canUndo)
    }

    @Test
    fun coalescingNeedsQuickSuccession() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        var now = 10_000L
        tool.clock = { now }
        tool.addAnchor(Vec2(10f, 10f))
        tool.addAnchor(Vec2(50f, 10f))
        repeat(3) { tool.nudge(1, 0); now += 60 }         // one held run
        now += 5_000
        tool.nudge(1, 0)                                  // a separate tap much later
        assertEquals(54f, tool.anchors[1].x, 1e-4f)
        tool.undoStep()
        assertEquals(53f, tool.anchors[1].x, 1e-4f)
        tool.undoStep()
        assertEquals(50f, tool.anchors[1].x, 1e-4f)
        // Typing in the field again later starts a new step too.
        tool.moveAnchor(1, Vec2(70f, 10f)); now += 300
        tool.moveAnchor(1, Vec2(75f, 10f)); now += 4_000
        tool.moveAnchor(1, Vec2(80f, 10f))
        tool.undoStep()
        assertEquals(75f, tool.anchors[1].x, 1e-4f)
        tool.undoStep()
        assertEquals(50f, tool.anchors[1].x, 1e-4f)
    }

    @Test
    fun deletingTheLastAnchorEndsThePendingPath() {
        val c = controller()
        val tool = curveTool(c, polyline = false)
        c.tap(20f, 20f)
        assertTrue(tool.hasPendingWork)
        tool.deleteAnchor(0)
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        // The next path goes to whatever layer is active then.
        val second = c.addLayer("Second")!!
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        c.tap(20f, 20f); c.tap(180f, 20f)
        tool.commit()
        assertEquals(red, second.bitmap.getPixel(100, 20))
        assertEquals(0, c.doc.layers[0].bitmap.getPixel(100, 20))
    }

    @Test
    fun numericAnchorsAndCoalescedHistory() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.clock = { 1_000L }
        tool.update { it.copy(nudgeStepPx = 3f) }
        assertTrue(tool.addAnchor(Vec2(10f, 10f)))
        assertTrue(tool.addAnchor(Vec2(90f, 10f)))
        assertEquals(1, tool.selected)                      // the new point is selected
        assertTrue(tool.anchors.all { it.sharp })
        // Typing a coordinate commits on every keystroke: one undo step for the whole edit.
        tool.moveAnchor(1, Vec2(1f, 10f))
        tool.moveAnchor(1, Vec2(12f, 10f))
        tool.moveAnchor(1, Vec2(120f, 10f))
        // Holding a nudge arrow: one undo step for the run.
        repeat(5) { tool.nudge(-1, 0) }
        assertEquals(105f, tool.anchors[1].x, 1e-4f)
        tool.undoStep()
        assertEquals(120f, tool.anchors[1].x, 1e-4f)
        tool.undoStep()
        assertEquals(90f, tool.anchors[1].x, 1e-4f)
        // Nudging with nothing selected moves the whole path.
        tool.deselect()
        tool.nudge(0, 1)
        assertEquals(listOf(13f, 13f), tool.anchors.map { it.y })
        tool.undoStep(); tool.undoStep(); tool.undoStep()
        assertFalse(tool.hasPendingWork)
        assertFalse(tool.canUndoStep)
        assertFalse(c.canUndo)
        // A locked layer refuses numeric creation too.
        c.doc.activeLayer.locked = true
        assertFalse(tool.addAnchor(Vec2(5f, 5f)))
        assertFalse(tool.hasPendingWork)
    }

    @Test
    fun numericShapeOnEmptyToolCreatesADefaultShape() {
        val c = controller(300, 150)
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.STAR, keepProportions = false) }
        assertTrue(tool.ensurePending())
        val b = tool.box!!
        assertEquals(150f, b.cx, 1e-4f)
        assertEquals(75f, b.cy, 1e-4f)
        assertEquals(50f, b.w, 1e-4f)
        assertFalse(c.canUndo)
        // Placement is clamped to sane sizes and normalized rotation.
        tool.place(b.copy(w = -5f, rotationDeg = 270f))
        assertEquals(1f, tool.box!!.w, 0f)
        assertEquals(-90f, tool.box!!.rotationDeg, 1e-4f)
        tool.discard()
        c.doc.activeLayer.locked = true
        assertFalse(tool.ensurePending())
        assertFalse(tool.hasPendingWork)
    }

    @Test
    fun brushStrokeCommitClearsThePath() {
        val c = controller()
        val tool = curveTool(c, polyline = false)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = true) }
        c.tap(20f, 20f); c.tap(180f, 180f)
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        // A single anchor cannot be committed: it is simply dropped.
        c.tap(50f, 50f)
        tool.commit()
        assertFalse(tool.hasPendingWork)
    }
}
