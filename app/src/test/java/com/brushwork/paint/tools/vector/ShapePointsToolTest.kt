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
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.editor.HistoryLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * "Points" in the shape tool: converting keeps the outline, points can be inserted (outline
 * unchanged), dragged, deleted, made smooth or sharp, undo takes back one edit, the box keeps
 * transforming the custom outline, and custom shapes are placed and re-opened like any shape.
 */
@RunWith(RobolectricTestRunner::class)
class ShapePointsToolTest {

    private val red = 0xFFFF0000.toInt()

    private val scopes = ArrayList<CoroutineScope>()

    /** Ends what the test's editors still observe (a global snapshot observer would keep them). */
    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private fun controller(w: Int = 200, h: Int = 200): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, doc, scope, AppSettings(ctx)).also {
            it.color = red
            it.tools
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

    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    /** Pixels whose channels differ by more than [tol]. */
    private fun diff(a: Bitmap, b: Bitmap, tol: Int = 1): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if (p == q) continue
            var worst = 0
            for (s in 0..24 step 8) worst = maxOf(worst, abs(((p ushr s) and 0xFF) - ((q ushr s) and 0xFF)))
            if (worst > tol) n++
        }
        return n
    }

    private fun assertVec(expected: Vec2, actual: Vec2, eps: Float = 1e-2f) {
        assertEquals("x of $actual", expected.x, actual.x, eps)
        assertEquals("y of $actual", expected.y, actual.y, eps)
    }

    /** A pending shape of [type] placed exactly at [box]. */
    private fun pending(c: EditorController, tool: ShapeTool, type: ShapeType, box: ShapeBox, settings: (ShapeSettings) -> ShapeSettings = { it }) {
        tool.update { settings(it.copy(type = type, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 6f, fillColor = 0xFF00AA00.toInt())) }
        assertTrue(tool.ensurePending())
        tool.place(box)
    }

    @Test
    fun convertingToPointsKeepsTheOutline() {
        val c = controller()
        val tool = shapeTool(c)
        val box = ShapeBox(100f, 100f, 120f, 90f, 25f)
        val cases = listOf(
            ShapeType.RECTANGLE to { s: ShapeSettings -> s.copy(corner = CornerStyle.ROUND, cornerRadius = 12f) },
            ShapeType.RECTANGLE to { s: ShapeSettings -> s.copy(corner = CornerStyle.SHARP) },
            ShapeType.POLYGON to { s: ShapeSettings -> s.copy(sides = 7, corner = CornerStyle.BEVEL, cornerRadius = 8f) },
            ShapeType.STAR to { s: ShapeSettings -> s.copy(starPoints = 6, innerRatio = 0.5f, corner = CornerStyle.INVERTED, cornerRadius = 6f) },
            ShapeType.ELLIPSE to { s: ShapeSettings -> s },
        )
        for ((type, settings) in cases) {
            pending(c, tool, type, box, settings)
            val before = c.composite()
            tool.setPointEditing(true)
            assertTrue(tool.pointsMode)
            assertNotNull(tool.points)
            assertEquals("$type: outline unchanged", 0, diff(before, c.composite()))
            tool.discard()
        }
    }

    @Test
    fun insertingOnAnEdgeOrAPlusKeepsTheOutline() {
        val c = controller()
        val tool = shapeTool(c)
        pending(c, tool, ShapeType.RECTANGLE, ShapeBox(100f, 100f, 100f, 80f))
        tool.setPointEditing(true)
        assertEquals(4, tool.points!!.size)
        val before = c.composite()
        // A tap on the top edge (away from its "+").
        c.tap(125f, 61f)
        assertEquals(5, tool.points!!.size)
        assertEquals(1, tool.selectedPoint)
        assertVec(Vec2(125f, 60f), tool.docAnchors()!![1].pos)
        assertEquals(0, diff(before, c.composite()))
        // A tap on the "+" in the middle of the right edge.
        c.tap(150f, 100f)
        assertEquals(6, tool.points!!.size)
        assertVec(Vec2(150f, 100f), tool.docAnchors()!![3].pos)
        assertEquals(0, diff(before, c.composite()))
        // Pressing on the outline and dragging inserts a point and moves it.
        c.drag(75f to 140f, 75f to 160f, 75f to 175f)
        assertEquals(7, tool.points!!.size)
        assertTrue(tool.docAnchors()!!.any { it.pos.distanceTo(Vec2(75f, 175f)) < 1e-2f })
        tool.discard()
        // Big round corners (limited by half the shorter edge): a point added on an edge, also
        // close to a corner, doesn't make the corners smaller.
        pending(c, tool, ShapeType.RECTANGLE, ShapeBox(100f, 100f, 100f, 80f)) { it.copy(corner = CornerStyle.ROUND, cornerRadius = 60f) }
        tool.setPointEditing(true)
        val rounded = c.composite()
        c.tap(125f, 61f)
        c.tap(100f, 140f)
        assertEquals(6, tool.points!!.size)
        assertEquals(0, diff(rounded, c.composite()))
        // Pulled off the edge, the new point is a round corner like the others.
        c.drag(100f to 140f, 100f to 160f, 100f to 180f)
        assertTrue(diff(rounded, c.composite()) > 100)
    }

    @Test
    fun resetShapeAfterFlatteningTheShape() {
        val c = controller()
        val tool = shapeTool(c)
        pending(c, tool, ShapeType.RECTANGLE, ShapeBox(100f, 100f, 100f, 80f))
        tool.setPointEditing(true)
        // Every point onto one horizontal line: the custom outline has no height.
        for (i in 0 until 4) tool.movePoint(i, Vec2(tool.docAnchors()!![i].pos.x, 100f))
        assertEquals(0f, tool.box!!.h, 1e-3f)
        tool.resetShape()
        assertNull(tool.points)
        assertTrue("a regular rectangle has a size: ${tool.box}", tool.box!!.h >= 1f && tool.box!!.w >= 1f)
        tool.commit()
        assertTrue(c.activeLayer.isShapeLayer)
    }

    @Test
    fun draggingPointsChangesTheShapeAndTheBoxFollows() {
        val c = controller()
        val tool = shapeTool(c)
        pending(c, tool, ShapeType.RECTANGLE, ShapeBox(100f, 100f, 100f, 80f))
        tool.setPointEditing(true)
        val before = c.composite()
        // Grabbed a little off the point: it moves with the finger, it doesn't jump.
        c.drag(53f to 63f, 40f to 50f, 33f to 43f)
        assertVec(Vec2(30f, 40f), tool.docAnchors()!![0].pos)
        assertEquals("the dragged point is selected", 0, tool.selectedPoint)
        assertTrue(diff(before, c.composite()) > 100)
        val b = tool.box!!
        assertEquals(30f, b.cx - b.w / 2f, 1e-2f)
        assertEquals(40f, b.cy - b.h / 2f, 1e-2f)
        assertEquals(150f, b.cx + b.w / 2f, 1e-2f)
        assertEquals(140f, b.cy + b.h / 2f, 1e-2f)
        // Long press on a point selects it (no color picking) and it can still be dragged.
        c.pointerDown(ToolPoint(150f, 140f))
        assertTrue(c.pointerLongPress(ToolPoint(150f, 140f)))
        assertFalse(c.holdPicking)
        c.pointerUp(ToolPoint(150f, 140f))
        assertEquals(2, tool.selectedPoint)
        // A tap on a selected point deselects it, a tap selects it again.
        c.tap(150f, 140f)
        assertEquals(-1, tool.selectedPoint)
        c.tap(150f, 140f)
        assertEquals(2, tool.selectedPoint)
        // Numbers: X / Y of the selected point.
        tool.movePoint(2, Vec2(160f, 150f))
        assertVec(Vec2(160f, 150f), tool.docAnchors()!![2].pos)
    }

    @Test
    fun deleteSharpSmoothAndTangents() {
        val c = controller()
        val tool = shapeTool(c)
        pending(c, tool, ShapeType.POLYGON, ShapeBox(100f, 100f, 120f, 120f)) { it.copy(sides = 4) }
        tool.setPointEditing(true)
        assertEquals(4, tool.points!!.size)
        tool.deletePoint(0)
        assertEquals(3, tool.points!!.size)
        c.message = null
        tool.deletePoint(0)
        assertEquals("a closed shape keeps 3 points", 3, tool.points!!.size)
        assertNotNull(c.message)
        val sharp = c.composite()
        tool.setPointSmooth(1, true)
        assertTrue(tool.points!![1].smooth)
        assertTrue(diff(sharp, c.composite()) > 50)
        // The selected smooth point's tangent handle can be dragged.
        tool.selectPoint(1)
        val a = tool.docAnchors()!!
        val (_, hOut) = ShapePoints.handles(a, 1, true)
        val grab = a[1].pos + hOut
        c.drag(grab.x to grab.y, grab.x + 10f to grab.y + 20f, grab.x + 20f to grab.y + 30f)
        val a2 = tool.docAnchors()!!
        assertNotNull(a2[1].handleOut)
        assertVec(hOut + Vec2(20f, 30f), a2[1].handleOut!!, 0.05f)
        // Back to the automatic tangent, then a sharp corner again: the outline is the triangle.
        tool.resetTangent(1)
        assertNull(tool.points!![1].handleOut)
        tool.setPointSmooth(1, false)
        assertEquals(0, diff(sharp, c.composite()))
    }

    @Test
    fun undoTakesBackOnePointEditAtATime() {
        val c = controller()
        val tool = shapeTool(c)
        pending(c, tool, ShapeType.RECTANGLE, ShapeBox(100f, 100f, 100f, 80f))
        assertFalse(tool.canUndoStep)
        tool.setPointEditing(true)
        assertTrue(tool.canUndoStep)
        c.drag(50f to 60f, 40f to 50f, 30f to 40f)              // move point 0
        c.tap(120f, 140f)                                      // insert on the bottom edge
        assertEquals(5, tool.points!!.size)
        assertEquals("Undo: last shape edit", HistoryLabels.undo(c))
        c.undo()
        assertTrue(tool.hasPendingWork)
        assertEquals(4, tool.points!!.size)
        assertVec(Vec2(30f, 40f), tool.docAnchors()!![0].pos)
        c.undo()
        assertVec(Vec2(50f, 60f), tool.docAnchors()!![0].pos)
        assertTrue(tool.canRedoStep)
        c.redo()
        assertVec(Vec2(30f, 40f), tool.docAnchors()!![0].pos)
        c.undo()
        // The conversion itself: back to the regular rectangle, points mode off.
        c.undo()
        assertNull(tool.points)
        assertFalse(tool.pointsMode)
        assertTrue(tool.hasPendingWork)
        // Nothing left to step back: undo discards the shape.
        c.undo()
        assertFalse(tool.hasPendingWork)
    }

    @Test
    fun theBoxKeepsTransformingTheCustomOutline() {
        val c = controller()
        val tool = shapeTool(c)
        pending(c, tool, ShapeType.STAR, ShapeBox(100f, 100f, 100f, 100f))
        tool.setPointEditing(true)
        val before = tool.docAnchors()!!
        tool.setPointEditing(false)
        assertNotNull("leaving points mode keeps the points", tool.points)
        // The right edge handle doubles the width around the fixed left edge.
        c.drag(150f to 100f, 200f to 100f, 250f to 100f)
        val after = tool.docAnchors()!!
        for (i in before.indices) {
            assertEquals(50f + (before[i].pos.x - 50f) * 2f, after[i].pos.x, 1e-2f)
            assertEquals(before[i].pos.y, after[i].pos.y, 1e-2f)
        }
        // Reset shape: the regular star in the same box.
        tool.resetShape()
        assertNull(tool.points)
        assertEquals(200f, tool.box!!.w, 1e-3f)
    }

    @Test
    fun linesGetMorePointsAndArrowHeadsFollowTheEnds() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.ARROW, useBrushSize = false, strokeWidth = 4f, arrowHeads = ArrowHeads.END) }
        c.drag(20f to 40f, 180f to 40f)
        tool.setPointEditing(true)
        assertEquals(2, tool.points!!.size)
        // Insert in the middle and pull it down: a bent arrow.
        c.drag(100f to 40f, 100f to 100f, 100f to 120f)
        assertEquals(3, tool.points!!.size)
        val o = tool.pendingObject()!!
        val head = ShapeOutlines.arrow(o).fill
        assertFalse(head.isEmpty)
        val tip = (head.ops[0] as PathOp.MoveTo).p
        assertVec(Vec2(180f, 40f), tip, 0.05f)
        // A line keeps at least 2 points.
        tool.deletePoint(0)
        tool.deletePoint(0)
        assertEquals(2, tool.points!!.size)
        tool.commit()
        val layer = c.activeLayer
        assertTrue(layer.isShapeLayer)
        assertEquals(2, ShapeCodec.decode(layer.shapeData)!!.points!!.size)
        // From (100, 120) to (180, 40).
        assertTrue((layer.bitmap.getPixel(140, 80) ushr 24) > 0)
    }

    @Test
    fun customShapesAreReopenedWithTheirPoints() {
        val c = controller()
        val tool = shapeTool(c)
        pending(c, tool, ShapeType.RECTANGLE, ShapeBox(100f, 100f, 100f, 80f))
        tool.setPointEditing(true)
        c.drag(50f to 60f, 40f to 50f, 30f to 40f)
        tool.commit()
        val layer = c.activeLayer
        val shown = c.composite()
        tool.update { it.copy(type = ShapeType.ELLIPSE) }
        c.tap(150f, 140f)
        assertEquals(layer, tool.editingLayer)
        assertEquals(ShapeType.RECTANGLE, tool.settings.type)
        assertFalse(tool.pointsMode)
        assertVec(Vec2(30f, 40f), tool.docAnchors()!![0].pos)
        assertEquals(0, diff(shown, c.composite()))
        tool.setPointEditing(true)
        c.drag(150f to 140f, 160f to 150f, 170f to 160f)
        tool.commit()
        assertEquals("Edit shape", c.undoManager.undoLabel)
        val o = ShapeCodec.decode(layer.shapeData)!!
        assertVec(Vec2(170f, 160f), ShapePoints.docAnchors(o.box, o.points!!)[2].pos)
        // The user's own type is back.
        assertEquals(ShapeType.ELLIPSE, tool.settings.type)
    }

    @Test
    fun cancelledGestureLeavesNoTrace() {
        val c = controller()
        val tool = shapeTool(c)
        pending(c, tool, ShapeType.RECTANGLE, ShapeBox(100f, 100f, 100f, 80f))
        tool.setPointEditing(true)
        val pts = tool.points
        // A press on the outline inserts a point; a second finger cancels it.
        c.pointerDown(ToolPoint(120f, 60f))
        assertEquals(5, tool.points!!.size)
        c.pointerCancel()
        assertEquals(pts, tool.points)
        c.pointerDown(ToolPoint(50f, 60f))
        c.pointerMove(ToolPoint(20f, 30f))
        c.pointerCancel()
        assertEquals(pts, tool.points)
        assertEquals("only the conversion can be undone", 1, generateSequence { if (tool.undoStep()) 1 else null }.count())
    }
}
