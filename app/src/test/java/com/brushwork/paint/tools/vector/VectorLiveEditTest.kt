package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
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
import com.brushwork.paint.ui.editor.HistoryLabels
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
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.1 editing of the vector tools: one-point undo / redo through the app's undo, editing old
 * points, the live brush stroke while a curve or shape is edited, shapes that follow the brush
 * size or are painted with the brush, and pinching a pending shape.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLiveEditTest {

    private val red = 0xFFFF0000.toInt()

    private fun controller(w: Int = 200, h: Int = 200): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = red
            // Creates the tools (the brush tools load their stored presets), then pins the presets.
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.eraser = BrushLibrary.defaultEraser.copy(size = 10f)
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
        compositor.drawDocument(Canvas(out), null, target = null)
        return out
    }

    /** The tool overlays at zoom 1 (document = screen pixels). */
    private fun EditorController.overlay(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        drawOverlays(Canvas(out), 0f)
        return out
    }

    private fun alpha(c: Int) = c ushr 24

    private fun curveTool(c: EditorController, polyline: Boolean): CurveTool {
        val id = if (polyline) ToolId.POLYLINE else ToolId.CURVE
        c.selectTool(id)
        return c.tools.getValue(id) as CurveTool
    }

    /** The shape tool drawing into the active layer (v1.3 behaviour; editable shapes: ShapeLayerEditTest). */
    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return (c.tools.getValue(ToolId.SHAPE) as ShapeTool).also { it.update { s -> s.copy(editable = false) } }
    }

    // ------------------------------------------------------------------ one point at a time

    @Test
    fun appUndoAndRedoStepThroughSinglePoints() {
        for (polyline in listOf(false, true)) {
            val c = controller()
            val tool = curveTool(c, polyline)
            tool.update { it.copy(stroke = CurveStroke.PLAIN) }
            c.tap(20f, 100f); c.tap(100f, 40f); c.tap(180f, 100f)
            assertEquals(3, tool.anchors.size)
            c.undo()
            assertEquals("undo takes back only the last point", 2, tool.anchors.size)
            assertTrue(tool.hasPendingWork)
            assertTrue(tool.canRedoStep)
            c.undo()
            assertEquals(listOf(Vec2(20f, 100f)), tool.anchors.map { it.pos })
            c.redo()
            assertEquals(Vec2(100f, 40f), tool.anchors[1].pos)
            c.redo()
            assertEquals(3, tool.anchors.size)
            assertFalse(tool.canRedoStep)
            // Moving a point is one step too.
            c.drag(100f to 40f, 110f to 60f)
            assertEquals(Vec2(110f, 60f), tool.anchors[1].pos)
            c.undo()
            assertEquals(Vec2(100f, 40f), tool.anchors[1].pos)
            c.redo()
            assertEquals(Vec2(110f, 60f), tool.anchors[1].pos)
            // A new edit drops what could be redone.
            c.undo()
            c.tap(60f, 180f)
            assertEquals(4, tool.anchors.size)
            assertFalse(tool.canRedoStep)
            assertEquals(0, tool.redoCount)
            assertFalse("the document history was not touched", c.canUndo)
            // Undoing every point ends the path; the document history is next.
            repeat(4) { c.undo() }
            assertFalse(tool.hasPendingWork)
            assertFalse(c.canUndo)
        }
    }

    @Test
    fun cancelledTouchKeepsTheRedoSteps() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        c.tap(20f, 20f); c.tap(180f, 20f); c.tap(180f, 180f)
        c.undo()
        assertEquals(1, tool.redoCount)
        // A finger lands (adding a point) and a second finger turns it into a pinch.
        c.pointerDown(ToolPoint(40f, 150f))
        assertEquals(3, tool.anchors.size)
        c.pointerCancel()
        assertEquals(2, tool.anchors.size)
        assertEquals(1, tool.redoCount)
        c.redo()
        assertEquals(Vec2(180f, 180f), tool.anchors[2].pos)
    }

    @Test
    fun oldPointsCanBeDraggedAtAnyTime() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        c.tap(20f, 20f); c.tap(100f, 20f); c.tap(180f, 20f); c.tap(180f, 100f)
        // 18 px from the first point (24 dp grab radius at zoom 1): moves it, adds nothing.
        c.drag(35f to 30f, 45f to 50f, 40f to 60f)
        assertEquals(4, tool.anchors.size)
        assertEquals(Vec2(40f, 60f), tool.anchors[0].pos)
        // Holding still on a point first (long press) still lets the finger drag it.
        c.pointerDown(ToolPoint(100f, 20f))
        assertTrue(c.pointerLongPress(ToolPoint(100f, 20f)))
        assertFalse("no color picking over a point", c.holdPicking)
        c.pointerMove(ToolPoint(100f, 50f))
        c.pointerUp(ToolPoint(100f, 50f))
        assertEquals(Vec2(100f, 50f), tool.anchors[1].pos)
        assertEquals(1, tool.selected)
        // Long press on empty canvas is left to the controller (color picking), adding nothing.
        c.pointerDown(ToolPoint(60f, 160f))
        assertTrue(c.pointerLongPress(ToolPoint(60f, 160f)))
        assertTrue(c.holdPicking)
        c.pointerUp(ToolPoint(60f, 160f))
        assertEquals(4, tool.anchors.size)
        c.undo(); c.undo()
        assertEquals(Vec2(20f, 20f), tool.anchors[0].pos)
        assertEquals(Vec2(100f, 20f), tool.anchors[1].pos)
    }

    // ------------------------------------------------------------------ live brush stroke

    @Test
    fun curveShowsTheRealBrushStrokeWhileEditing() {
        val c = controller()
        val layer = c.doc.activeLayer
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = false, taper = false) }
        c.tap(20f, 100f); c.tap(180f, 100f)
        tool.flushPreview()
        assertNotNull("the brush previews through its render override", c.renderOverride)
        assertEquals("nothing baked yet", 0, layer.bitmap.getPixel(100, 100))
        assertTrue("stroke visible", alpha(c.composite().getPixel(100, 100)) > 200)
        assertFalse(c.canUndo)
        // Dragging the end point: the preview follows.
        c.drag(180f to 100f, 180f to 160f)
        tool.flushPreview()
        val shot = c.composite()
        assertEquals(0, alpha(shot.getPixel(150, 100)))
        assertTrue(alpha(shot.getPixel(100, 130)) > 200)
        assertEquals(0, layer.bitmap.getPixel(100, 130))
        // ✓ paints exactly that as one undo step.
        tool.commit()
        assertNull(c.renderOverride)
        assertFalse(tool.hasPendingWork)
        assertTrue(alpha(layer.bitmap.getPixel(100, 130)) > 200)
        assertEquals(1, c.undoManager.undoCount)
        c.undo()
        assertEquals(0, layer.bitmap.getPixel(100, 130))
    }

    @Test
    fun discardingOrSwitchingToolsLeavesNoPreviewBehind() {
        val c = controller()
        val layer = c.doc.activeLayer
        val tool = curveTool(c, polyline = false)
        tool.update { it.copy(stroke = CurveStroke.BRUSH) }
        c.tap(20f, 100f); c.tap(180f, 100f)
        tool.flushPreview()
        assertNotNull(c.renderOverride)
        tool.discard()
        assertNull(c.renderOverride)
        assertEquals(0, alpha(c.composite().getPixel(100, 100)))
        assertFalse(c.canUndo)
        // A replay still waiting when the path is discarded never runs.
        c.tap(20f, 100f); c.tap(180f, 100f)
        tool.discard()
        tool.flushPreview()
        assertNull(c.renderOverride)
        // Switching tools commits the path; the brush has no stroke left open.
        c.tap(20f, 50f); c.tap(180f, 50f)
        tool.flushPreview()
        c.selectTool(ToolId.BRUSH)
        assertNull(c.renderOverride)
        assertTrue(alpha(layer.bitmap.getPixel(100, 50)) > 200)
        assertEquals(1, c.undoManager.undoCount)
        // Changing layers commits on the old layer.
        val tool2 = curveTool(c, polyline = false)
        c.tap(20f, 150f); c.tap(180f, 150f)
        tool2.flushPreview()
        val second = c.addLayer("Second")!!
        assertNull(c.renderOverride)
        assertTrue(alpha(layer.bitmap.getPixel(100, 150)) > 200)
        assertEquals(0, second.bitmap.getPixel(100, 150))
    }

    @Test
    fun brushStrokeAndFillAreOneUndoStep() {
        val c = controller()
        val layer = c.doc.activeLayer
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = true, fillColor = 0xFF0000FF.toInt(), closed = true) }
        c.tap(40f, 40f); c.tap(160f, 40f); c.tap(160f, 160f); c.tap(40f, 160f)
        tool.flushPreview()
        // The stroke is the brush's preview, the fill is shown in the overlay.
        assertTrue(alpha(c.composite().getPixel(100, 40)) > 200)
        assertEquals(0, c.composite().getPixel(100, 100))
        assertEquals(0xFF0000FF.toInt(), c.overlay().getPixel(100, 100))
        tool.commit()
        assertEquals(0xFF0000FF.toInt(), layer.bitmap.getPixel(100, 100))
        assertEquals("the stroke is on top of the fill", red, layer.bitmap.getPixel(100, 40))
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Polyline", c.undoManager.undoLabel)
        c.undo()
        assertEquals(0, layer.bitmap.getPixel(100, 100))
        assertEquals(0, layer.bitmap.getPixel(100, 40))
    }

    @Test
    fun theLooperRunsCoalescedReplays() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.BRUSH) }
        c.tap(20f, 100f); c.tap(180f, 100f)
        assertNull("not replayed synchronously", c.renderOverride)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertNotNull(c.renderOverride)
        tool.discard()
        assertNull(c.renderOverride)
    }

    @Test
    fun eraserPreviewHidesPixelsUntilCommittedAndCancelRestores() {
        val c = controller()
        val layer = c.doc.activeLayer
        layer.bitmap.eraseColor(red)
        val preview = BrushStrokePreview(c) { ToolId.ERASER }
        val path = VectorPath.polyline(listOf(Vec2(20f, 100f), Vec2(180f, 100f)))
        preview.request(path.ops) { brushStrokeInput(path, out = it) }
        preview.flush()
        assertTrue(preview.isLive)
        assertNotNull(c.renderOverride)
        assertEquals(red, layer.bitmap.getPixel(100, 100))
        assertEquals(0, alpha(c.composite().getPixel(100, 100)))
        preview.cancel()
        assertNull(c.renderOverride)
        assertEquals(red, c.composite().getPixel(100, 100))
        preview.request(path.ops) { brushStrokeInput(path, out = it) }
        preview.flush()
        assertTrue(preview.commit(path.ops) { brushStrokeInput(path, out = it) })
        assertEquals(0, alpha(layer.bitmap.getPixel(100, 100)))
        assertEquals(red, layer.bitmap.getPixel(100, 150))
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun smudgePreviewEditsPixelsAndCancelRestoresThemExactly() {
        val c = controller()
        val layer = c.doc.activeLayer
        c.editWholeLayer(layer, "Seed") { b ->
            Canvas(b).drawRect(0f, 0f, 100f, 200f, android.graphics.Paint().apply { color = red })
        }
        c.undoManager.clear()
        val before = IntArray(200 * 200).also { layer.bitmap.getPixels(it, 0, 200, 0, 0, 200, 200) }
        fun pixels() = IntArray(200 * 200).also { layer.bitmap.getPixels(it, 0, 200, 0, 0, 200, 200) }
        val preview = BrushStrokePreview(c) { ToolId.SMUDGE }
        val path = VectorPath.polyline(listOf(Vec2(60f, 100f), Vec2(160f, 100f)))
        preview.request(path.ops) { brushStrokeInput(path, out = it) }
        preview.flush()
        assertTrue(preview.isLive)
        assertFalse("smudge previews in the pixels", before.contentEquals(pixels()))
        preview.cancel()
        assertTrue("cancel leaves no trace", before.contentEquals(pixels()))
        assertFalse(c.canUndo)
        preview.request(path.ops) { brushStrokeInput(path, out = it) }
        preview.flush()
        assertTrue(preview.commit(path.ops) { brushStrokeInput(path, out = it) })
        assertEquals(1, c.undoManager.undoCount)
        c.undo()
        assertTrue(before.contentEquals(pixels()))
    }

    @Test
    fun lockedLayerGetsNoPreviewAndNoMessageSpam() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.BRUSH) }
        c.tap(20f, 100f); c.tap(180f, 100f)
        c.doc.activeLayer.locked = true
        c.message = null
        tool.nudge(0, 1)
        tool.flushPreview()
        assertNull(c.renderOverride)
        assertNull(c.message)
        tool.discard()
    }

    // ------------------------------------------------------------------ shapes

    @Test
    fun shapeStrokeWidthFollowsTheBrushSize() {
        val c = controller()
        val layer = c.doc.activeLayer
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = true, strokeWith = ShapeStroke.PLAIN) }
        c.brush = c.brush.copy(size = 10f)
        assertEquals(10f, tool.strokeWidth, 0f)
        c.drag(40f to 40f, 100f to 100f, 160f to 160f)
        assertTrue(alpha(c.composite().getPixel(44, 100)) > 200)
        assertEquals(0, alpha(c.composite().getPixel(52, 100)))
        // Moving the size slider changes the pending shape live.
        c.brush = c.brush.copy(size = 30f)
        Snapshot.sendApplyNotifications()
        assertEquals(30f, tool.strokeWidth, 0f)
        assertTrue(alpha(c.composite().getPixel(52, 100)) > 200)
        // Editing the width while it follows the brush resizes the brush.
        tool.setStrokeWidth(20f)
        assertEquals(20f, c.brush.size, 0f)
        tool.commit()
        assertTrue(alpha(layer.bitmap.getPixel(48, 100)) > 200)
        assertEquals(0, alpha(layer.bitmap.getPixel(52, 100)))
        // Turned off, the shape's own width is used again.
        tool.update { it.copy(useBrushSize = false, strokeWidth = 4f) }
        assertEquals(4f, tool.strokeWidth, 0f)
        tool.setStrokeWidth(7f)
        assertEquals(7f, tool.settings.strokeWidth, 0f)
        assertEquals(20f, c.brush.size, 0f)
    }

    @Test
    fun shapePaintedWithTheBrushFollowsTheOutline() {
        for (type in listOf(ShapeType.RECTANGLE, ShapeType.ELLIPSE, ShapeType.STAR, ShapeType.LINE, ShapeType.ARROW)) {
            val c = controller()
            val layer = c.doc.activeLayer
            val tool = shapeTool(c)
            tool.update { it.copy(type = type, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.BRUSH, fillColor = 0xFF00FF00.toInt(), corner = CornerStyle.ROUND, cornerRadius = 10f) }
            c.drag(40f to 60f, 100f to 100f, 160f to 140f)
            tool.flushPreview()
            assertNotNull("$type: live brush stroke", c.renderOverride)
            assertTrue("$type: nothing baked yet", (0 until 200).none { x -> alpha(layer.bitmap.getPixel(x, 100)) != 0 })
            tool.commit()
            assertNull(c.renderOverride)
            assertEquals("$type: one undo step", 1, c.undoManager.undoCount)
            val b = tool.box
            assertNull(b)
            when (type) {
                ShapeType.RECTANGLE -> {
                    assertEquals(red, layer.bitmap.getPixel(40, 100))      // left edge: brush
                    assertEquals(red, layer.bitmap.getPixel(100, 140))     // bottom edge
                    assertEquals(0xFF00FF00.toInt(), layer.bitmap.getPixel(100, 100)) // plain fill inside
                }
                ShapeType.ELLIPSE -> {
                    assertEquals(red, layer.bitmap.getPixel(160, 100))
                    assertEquals(0xFF00FF00.toInt(), layer.bitmap.getPixel(100, 100))
                }
                ShapeType.STAR -> assertEquals(0xFF00FF00.toInt(), layer.bitmap.getPixel(100, 100))
                ShapeType.LINE -> assertEquals(red, layer.bitmap.getPixel(100, 100))
                ShapeType.ARROW -> {
                    assertEquals(red, layer.bitmap.getPixel(70, 80))       // shaft
                    assertEquals(red, layer.bitmap.getPixel(158, 139))     // tip of the filled head
                }
                else -> {}
            }
            assertEquals("$type: far from the shape", 0, layer.bitmap.getPixel(10, 190))
            c.undo()
            assertTrue("$type: undone", (0 until 200).none { x -> alpha(layer.bitmap.getPixel(x, 100)) != 0 })
        }
    }

    @Test
    fun pinchInsideAPendingShapeScalesRotatesAndMovesIt() {
        val c = controller(400, 400)
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, snapAngle = false) }
        c.drag(150f to 170f, 250f to 230f)                        // (200, 200) 100 x 60
        val start = tool.box!!
        // Pinch centered on the shape: twice the size, a quarter turn, moved by (10, -20).
        assertTrue(c.twoFingerStart(Vec2(200f, 200f), Vec2(180f, 200f), Vec2(220f, 200f)))
        c.twoFingerGesture(Vec2(10f, -20f), 2f, 90f)
        val b = tool.box!!
        assertEquals(210f, b.cx, 1e-3f)
        assertEquals(180f, b.cy, 1e-3f)
        assertEquals(200f, b.w, 1e-3f)
        assertEquals(120f, b.h, 1e-3f)
        assertEquals(90f, b.rotationDeg, 1e-3f)
        c.twoFingerEnd(cancelled = false)
        assertEquals(b, tool.box)
        // Around an off-center focus the shape also moves.
        assertTrue(c.twoFingerStart(Vec2(240f, 180f), Vec2(230f, 170f), Vec2(250f, 190f)))
        c.twoFingerGesture(Vec2.ZERO, 0.5f, 0f)
        assertEquals(225f, tool.box!!.cx, 1e-3f)
        assertEquals(180f, tool.box!!.cy, 1e-3f)
        // A cancelled pinch puts it back.
        c.twoFingerEnd(cancelled = true)
        assertEquals(b, tool.box)
        // Fingers away from the shape move the view instead.
        assertFalse(c.twoFingerStart(Vec2(20f, 20f), Vec2(10f, 20f), Vec2(30f, 20f)))
        assertFalse(c.canUndo)
        assertTrue(start != b)
    }

    // ------------------------------------------------------------------ review fixes

    @Test
    fun undoFeedbackNamesTheSinglePointItTakesBack() {
        val c = controller()
        val tool = curveTool(c, polyline = false)
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        assertEquals("Nothing to undo", HistoryLabels.undo(c))
        c.tap(20f, 100f); c.tap(100f, 40f); c.tap(180f, 100f)
        assertTrue(tool.canUndoStep)
        // What the two-finger tap / undo button shows: one point, not the whole curve.
        assertEquals("Undo: last point", HistoryLabels.undo(c))
        c.undo()
        assertEquals(2, tool.anchors.size)
        assertEquals("Redo: last point", HistoryLabels.redo(c))
        c.redo()
        assertEquals(3, tool.anchors.size)
        assertEquals("Apply or discard the curve edit first", HistoryLabels.redo(c))
        tool.discard()
        // Tools without steps still say that undo throws their pending work away.
        val shape = shapeTool(c)
        c.drag(40f to 40f, 160f to 160f)
        assertTrue(shape.hasPendingWork)
        assertEquals("Undo: Shape (discarded)", HistoryLabels.undo(c))
        c.undo()
        assertFalse(shape.hasPendingWork)
    }

    @Test
    fun brushPaintedCurvesAndShapesAreUndoneUnderTheirOwnName() {
        val c = controller()
        val tool = curveTool(c, polyline = false)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = false) }
        c.tap(20f, 100f); c.tap(180f, 100f)
        tool.flushPreview()
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Curve", c.undoManager.undoLabel)
        assertEquals("Undo: Curve", HistoryLabels.undo(c))
        c.undo()
        assertEquals(0, alpha(c.doc.activeLayer.bitmap.getPixel(100, 100)))
        c.redo()
        assertTrue(alpha(c.doc.activeLayer.bitmap.getPixel(100, 100)) > 200)
        // A fill-only path keeps its own name.
        tool.update { it.copy(stroke = CurveStroke.NONE, fill = true) }
        c.tap(20f, 20f); c.tap(60f, 20f); c.tap(40f, 60f)
        tool.commit()
        assertEquals("Fill path", c.undoManager.undoLabel)
        // A shape outlined with the brush is one "Shape" step.
        val shape = shapeTool(c)
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        c.drag(100f to 120f, 180f to 190f)
        shape.flushPreview()
        shape.commit()
        assertEquals(3, c.undoManager.undoCount)
        assertEquals("Shape", c.undoManager.undoLabel)
    }

    @Test
    fun fillPreviewLeavesTheWholeLiveBrushStrokeVisible() {
        val blue = 0xFF0000FF.toInt()
        val c = controller()
        c.brush = c.brush.copy(size = 10f)
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = true, fillColor = blue, closed = true) }
        c.tap(40f, 40f); c.tap(160f, 40f); c.tap(160f, 160f); c.tap(40f, 160f)
        tool.flushPreview()
        val shot = c.composite()
        val over = c.overlay()
        // The inner half of the stroke (y 40..45, under the thin guide line at 40) is not covered
        // by the fill drawn on top.
        for (y in listOf(43, 44)) {
            assertEquals("no fill over the stroke at y=$y", 0, alpha(over.getPixel(100, y)))
            assertTrue("stroke visible at y=$y", alpha(shot.getPixel(100, y)) > 200)
        }
        assertEquals("the fill shows inside", blue, over.getPixel(100, 100))
        assertEquals(blue, over.getPixel(100, 50))
        // The band follows the path when a point moves.
        c.drag(160f to 40f, 160f to 70f)
        tool.flushPreview()
        // (120, 64) is inside the new fill, 4 px from the new top edge.
        assertEquals(0, alpha(c.overlay().getPixel(120, 64)))
        assertEquals(blue, c.overlay().getPixel(100, 100))
        // After ✓ the stroke is painted over the fill, as previewed.
        tool.commit()
        val layer = c.doc.activeLayer
        assertEquals("the new top edge passes (100, 55)", red, layer.bitmap.getPixel(100, 55))
        assertEquals(red, layer.bitmap.getPixel(120, 61))
        assertEquals(blue, layer.bitmap.getPixel(100, 100))
        assertEquals(0, alpha(c.overlay().getPixel(100, 100)))

        // Same for a filled shape outlined with the brush.
        val c2 = controller()
        c2.brush = c2.brush.copy(size = 10f)
        val shape = shapeTool(c2)
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.BRUSH, fillColor = blue) }
        c2.drag(40f to 60f, 100f to 100f, 160f to 140f)
        shape.flushPreview()
        val over2 = c2.overlay()
        // 3 px inside the top edge (away from the handles and the dashed box).
        assertEquals(0, alpha(over2.getPixel(70, 63)))
        assertTrue(alpha(c2.composite().getPixel(70, 63)) > 200)
        assertEquals(blue, over2.getPixel(100, 100))
    }

    @Test
    fun holdingAShapeHandleDoesNotPickAColor() {
        val c = controller(400, 400)
        val tool = shapeTool(c)
        c.drag(100f to 100f, 200f to 160f)                        // box (150, 130) 100 x 60
        c.pointerDown(ToolPoint(200f, 160f))                      // bottom-right handle
        assertTrue(c.pointerLongPress(ToolPoint(200f, 160f)))
        assertFalse(c.holdPicking)
        c.pointerMove(ToolPoint(240f, 200f))
        c.pointerUp(ToolPoint(240f, 200f))
        assertEquals(ShapeBox(170f, 150f, 140f, 100f, 0f), tool.box)
        // On empty canvas the long press picks a color.
        c.pointerDown(ToolPoint(350f, 350f))
        assertTrue(c.pointerLongPress(ToolPoint(350f, 350f)))
        assertTrue(c.holdPicking)
        c.pointerUp(ToolPoint(350f, 350f))
        assertTrue(tool.hasPendingWork)
    }
}
