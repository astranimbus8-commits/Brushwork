package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 item 1 (design §3.1, area C): several points of a shape at once in Points mode. "Select
 * several" toggles points by tapping, box-selects with a drag and never inserts a point; a drag on
 * a point of the group moves the group; the gizmo scales about the box centre and its knob
 * rotates; a pinch that starts inside the gizmo scales the selection; each gesture is ONE in-tool
 * step whose undo restores the points AND the selection; "Delete selected points" is one step and
 * refuses below the minimum; a history tap over the UI rolls an in-tool change back
 * (`historyMark` / `rollbackHistory`, §3.10). The view is the identity, so document px are screen
 * px here.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeMultiPointRobolectricTest {

    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", 200, 200)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(200, 200))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, doc, scope, AppSettings(ctx)).also {
            it.color = 0xFFFF0000.toInt()
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
            it.tools
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) = drag(x to y)

    /** A pending sharp rectangle 60..140 × 70..130 in Points mode (4 points). */
    private fun rectPoints(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f,
                corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false,
            )
        }
        c.drag(60f to 70f, 100f to 100f, 140f to 130f)
        tool.setPointEditing(true)
        assertEquals(4, tool.pointCount)
        return tool
    }

    private fun positions(tool: ShapeTool): List<Vec2> = tool.docAnchors()!!.map { it.pos }

    /** The index of the point at ([x], [y]). */
    private fun at(tool: ShapeTool, x: Float, y: Float): Int {
        val i = positions(tool).indexOfFirst { it.distanceTo(Vec2(x, y)) < 0.01f }
        assertTrue("a point at $x, $y in ${positions(tool)}", i >= 0)
        return i
    }

    private fun assertVec(expected: Vec2, actual: Vec2, eps: Float = 0.01f) {
        assertEquals("x of $actual", expected.x, actual.x, eps)
        assertEquals("y of $actual", expected.y, actual.y, eps)
    }

    private fun assertPositions(expected: List<Vec2>, actual: List<Vec2>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertVec(expected[i], actual[i])
    }

    @Test
    fun selectSeveralTogglesPointsBoxSelectsAndNeverInserts() {
        val c = controller()
        val tool = rectPoints(c)
        val tl = at(tool, 60f, 70f)
        val tr = at(tool, 140f, 70f)
        val br = at(tool, 140f, 130f)
        val bl = at(tool, 60f, 130f)
        val before = positions(tool)
        tool.selectSeveral = true
        c.tap(60f, 70f)
        c.tap(140f, 130f)
        assertEquals(listOf(tl, br).sorted(), tool.pointSelection.indices)
        c.tap(60f, 70f)
        assertEquals(listOf(br), tool.pointSelection.indices)
        // A tap on the outline inserts nothing and clears the selection (empty canvas for the points).
        c.tap(100f, 70f)
        assertEquals(4, tool.pointCount)
        assertTrue(tool.pointSelection.isEmpty)
        // A drag on the canvas box-selects (the left side), a second one adds the top right.
        c.drag(40f to 45f, 80f to 100f, 105f to 140f)
        assertEquals(listOf(tl, bl).sorted(), tool.pointSelection.indices)
        c.drag(155f to 50f, 130f to 80f)
        assertEquals(listOf(tl, tr, bl).sorted(), tool.pointSelection.indices)
        assertPositions(before, positions(tool))
        assertTrue("the shape stays pending", tool.box != null)
        // The toggle ends with the tool.
        c.selectTool(ToolId.BRUSH)
        assertFalse(tool.selectSeveral)
    }

    @Test
    fun aDragOnAPointOfTheGroupMovesTheGroupAsOneStep() {
        val c = controller()
        val tool = rectPoints(c)
        val tl = at(tool, 60f, 70f)
        val tr = at(tool, 140f, 70f)
        val bl = at(tool, 60f, 130f)
        val group = PointSelection.of(4, tl, tr)
        tool.selectPoints(group)
        val before = positions(tool)
        c.drag(60f to 70f, 70f to 80f, 80f to 90f)
        val after = positions(tool)
        assertVec(Vec2(80f, 90f), after[tl])
        assertVec(Vec2(160f, 90f), after[tr])
        assertVec(Vec2(60f, 130f), after[bl])
        assertEquals("the group stays selected", group, tool.pointSelection)
        // One in-tool step: one undo restores the points AND the selection.
        tool.selectPoints(PointSelection.none(4))
        assertTrue(tool.undoStep())
        assertPositions(before, positions(tool))
        assertEquals(group, tool.pointSelection)
        // Without "Select several", a drag on a point outside the group selects only it and moves it alone (v1.6).
        c.drag(60f to 130f, 55f to 135f, 50f to 140f)
        assertEquals(PointSelection.of(4, bl), tool.pointSelection)
        assertVec(Vec2(50f, 140f), positions(tool)[bl])
        assertVec(Vec2(60f, 70f), positions(tool)[tl])
    }

    @Test
    fun aDragWithSelectSeveralOnAnUnselectedPointAddsItAndMovesTheGroup() {
        val c = controller()
        val tool = rectPoints(c)
        val tl = at(tool, 60f, 70f)
        val tr = at(tool, 140f, 70f)
        tool.selectSeveral = true
        tool.selectPoints(PointSelection.of(4, tl))
        c.drag(140f to 70f, 145f to 75f, 150f to 80f)
        assertEquals(listOf(tl, tr).sorted(), tool.pointSelection.indices)
        assertVec(Vec2(70f, 80f), positions(tool)[tl])
        assertVec(Vec2(150f, 80f), positions(tool)[tr])
        // Its undo restores the selection before the point joined.
        assertTrue(tool.undoStep())
        assertEquals(PointSelection.of(4, tl), tool.pointSelection)
        assertVec(Vec2(60f, 70f), positions(tool)[tl])
    }

    @Test
    fun theGizmoScalesAboutTheBoxCentreAndItsKnobRotates() {
        val c = controller()
        val tool = rectPoints(c)
        val before = positions(tool)
        val tl = at(tool, 60f, 70f)
        val tr = at(tool, 140f, 70f)
        val br = at(tool, 140f, 130f)
        val group = PointSelection.of(4, tl, br)
        tool.selectPoints(group)
        fun only(m: Affine2) = before.mapIndexed { i, p -> if (i in group) m.map(p) else p }
        // The NE corner handle (over the unselected top right point): proportional, 1.5 × about (100, 100).
        c.drag(140f to 70f, 150f to 62.5f, 160f to 55f)
        assertPositions(only(Affine2.scaleAbout(Vec2(100f, 100f), 1.5f, 1.5f)), positions(tool))
        assertEquals(group, tool.pointSelection)
        assertTrue(tool.undoStep())
        assertPositions(before, positions(tool))
        // The knob, 36 dp above the top edge's middle: a quarter turn about the centre.
        c.drag(100f to 34f, 140f to 50f, 166f to 100f)
        assertPositions(only(Affine2.rotateAbout(Vec2(100f, 100f), 90f)), positions(tool))
        assertTrue(tool.undoStep())
        assertPositions(before, positions(tool))
        // A drag inside the box (not on a point) moves the group.
        c.drag(100f to 100f, 105f to 100f, 110f to 95f)
        assertPositions(only(Affine2.translate(10f, -5f)), positions(tool))
        assertTrue(tool.undoStep())
        // A drag on a selected point under a handle moves the group (the point wins).
        c.drag(140f to 130f, 145f to 130f, 150f to 130f)
        assertPositions(only(Affine2.translate(10f, 0f)), positions(tool))
        assertTrue(tool.undoStep())
        // A tap on the unselected point under the NE handle selects it alone.
        c.tap(140f, 70f)
        assertEquals(PointSelection.of(4, tr), tool.pointSelection)
        assertPositions(before, positions(tool))
    }

    @Test
    fun aPinchThatStartsInsideTheGizmoScalesTheSelection() {
        val c = controller()
        val tool = rectPoints(c)
        val tl = at(tool, 60f, 70f)
        val br = at(tool, 140f, 130f)
        val before = positions(tool)
        tool.selectPoints(PointSelection.of(4, tl, br))
        assertTrue(c.twoFingerStart(Vec2(100f, 100f), Vec2(90f, 95f), Vec2(110f, 105f)))
        c.twoFingerGesture(Vec2(0f, 0f), 1.5f, 0f)
        c.twoFingerGesture(Vec2(0f, 0f), 2f, 0f)
        c.twoFingerEnd(false)
        val after = positions(tool)
        assertVec(Vec2(20f, 40f), after[tl])
        assertVec(Vec2(180f, 160f), after[br])
        for (i in listOf(at(tool, 140f, 70f), at(tool, 60f, 130f))) assertVec(before[i], after[i])
        assertTrue(tool.undoStep())
        assertPositions(before, positions(tool))
        assertEquals(PointSelection.of(4, tl, br), tool.pointSelection)
        // With "Select several" a pinch outside the gizmo is the view's.
        tool.selectSeveral = true
        tool.selectPoints(PointSelection.of(4, tl, at(tool, 140f, 70f)))
        assertFalse(c.twoFingerStart(Vec2(100f, 125f), Vec2(95f, 125f), Vec2(105f, 128f)))
    }

    @Test
    fun deleteSelectedPointsIsOneStepAndRefusesBelowTheMinimum() {
        val c = controller()
        val tool = rectPoints(c)
        tool.selectPoints(PointSelection.of(4, 0, 1))
        assertFalse(tool.deleteSelectedPoints())
        assertEquals("A shape needs at least 3 points", c.message)
        assertEquals(4, tool.pointCount)
        // A fifth point (a tap on the top edge), then 2 of the 5 go.
        tool.selectPoints(PointSelection.none(4))
        c.tap(100f, 70f)
        assertEquals(5, tool.pointCount)
        val before = positions(tool)
        val two = PointSelection.of(5, 0, 2)
        tool.selectPoints(two)
        assertTrue(tool.deleteSelectedPoints())
        assertEquals(3, tool.pointCount)
        assertTrue(tool.pointSelection.isEmpty)
        assertTrue(tool.undoStep())
        assertPositions(before, positions(tool))
        assertEquals(two, tool.pointSelection)
    }

    @Test
    fun aHistoryTapOverTheUiRollsBackTheFirstFingersPointEdit() {
        val c = controller()
        val tool = rectPoints(c)
        // One point edit taken back: something to redo.
        c.drag(60f to 70f, 55f to 65f, 50f to 60f)
        assertTrue(tool.undoStep())
        assertEquals(1, tool.redoCount)
        val before = positions(tool)
        val undoBefore = c.undoManager.undoCount
        tool.selectPoints(PointSelection.of(4, 1, 2))
        val mark = c.uiMark()
        assertEquals(tool.historyMark(), tool.historyMark())
        // The first finger nudges the points (a new in-tool step, which drops the redo step).
        tool.nudge(1, 0)
        assertNotEquals(before, positions(tool))
        assertEquals(0, tool.redoCount)
        assertTrue(c.restoreUiMark(mark))
        c.releaseUiMark(mark)
        assertPositions(before, positions(tool))
        assertEquals(PointSelection.of(4, 1, 2), tool.pointSelection)
        assertEquals("the redo step is back", 1, tool.redoCount)
        assertEquals(undoBefore, c.undoManager.undoCount)
        // Nothing changed: the marks match and nothing is rolled back.
        val again = c.uiMark()
        assertFalse(c.restoreUiMark(again))
        c.releaseUiMark(again)
    }
}
