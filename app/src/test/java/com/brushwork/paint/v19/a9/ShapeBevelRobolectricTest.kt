package com.brushwork.paint.v19.a9

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointBevel
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PointLabels19
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.9 A9 (R18): "Bevel points" of the Shape tool's Points mode. The rig's rectangle has its
 * corners at (30, 30), (110, 30), (110, 90) and (30, 90): edges of 80 and 60 px.
 * - the selected sharp corners are bevelled at once with the remembered Amount and Segments, as
 *   ONE in-tool step; the new points are selected and the popup opens on that step;
 * - points the bevel does not touch keep their smoothness, handles and roundness exactly; the
 *   new points are sharp, without handles, with a roundness of 0;
 * - a smooth point, or a corner beside a curved segment, is skipped, with "Bevel works on sharp
 *   corners" when nothing is left; too many points are refused with their message;
 * - the popup re-applies from the points before the bevel, within its one step, and remembers
 *   its values; a second tap while the new points stay selected reopens it instead of bevelling
 *   again; the tap on the canvas that closes it is consumed (no point, no commit, no shape).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShapeBevelRobolectricTest {
    private val rig = A9Rig()

    @After
    fun tearDown() = rig.close()

    /** The rig's rectangle in Points mode (the conversion is one step). */
    private fun pointsRect(c: EditorController): ShapeTool {
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        tool.setPointEditing(true)
        assertTrue(tool.pointsMode)
        assertEquals(4, tool.pointCount)
        return tool
    }

    /** The index of the point at ([x], [y]) (document px). */
    private fun at(tool: ShapeTool, x: Float, y: Float): Int {
        val a = tool.docAnchors()!!
        val i = a.indices.minByOrNull { a[it].pos.distanceTo(Vec2(x, y)) }!!
        assertTrue("a point at ($x, $y): ${a[i].pos}", a[i].pos.distanceTo(Vec2(x, y)) < 0.01f)
        return i
    }

    private fun select(tool: ShapeTool, vararg idx: Int) =
        tool.selectPoints(PointSelection.none(tool.pointCount).plusAll(idx.toList()))

    private fun undoDepth(tool: ShapeTool): Int {
        var n = 0
        while (tool.undoStep()) n++
        repeat(n) { tool.redoStep() }
        return n
    }

    private fun near(expected: Vec2, actual: Vec2, what: String) =
        assertTrue("$what: expected $expected, got $actual", expected.distanceTo(actual) < 0.01f)

    @Test
    fun aChamferOfTheSelectedCornerIsOneStepAndSelectsTheNewPoints() {
        val c = rig.controller()
        val tool = pointsRect(c)
        val depth = undoDepth(tool)
        val tl = at(tool, 30f, 30f)
        select(tool, tl)
        assertTrue(tool.canBevel)
        assertTrue(tool.bevelPoints())
        // Amount 0.15 of the shorter adjacent edge (60 px): 9 px along each edge; Segments 1.
        assertEquals(5, tool.pointCount)
        val a = tool.docAnchors()!!
        val made = tool.pointSelection.indices
        assertEquals("the two new points are selected", 2, made.size)
        val pos = made.map { a[it].pos }
        assertTrue("new point on the top edge: $pos", pos.any { it.distanceTo(Vec2(39f, 30f)) < 0.01f })
        assertTrue("new point on the left edge: $pos", pos.any { it.distanceTo(Vec2(30f, 39f)) < 0.01f })
        for (i in made) {
            assertFalse(a[i].smooth)
            assertNull(a[i].handleIn)
            assertNull(a[i].handleOut)
            assertEquals("a new point's own roundness is 0", 0f, a[i].radius)
        }
        assertTrue("the popup opens on the step", tool.bevelPopupOpen)
        assertEquals("one in-tool step", depth + 1, undoDepth(tool))
        // One undo takes it back and closes the popup.
        assertTrue(tool.undoStep())
        assertEquals(4, tool.pointCount)
        assertFalse(tool.bevelPopupOpen)
        assertTrue(tool.redoStep())
        assertEquals(5, tool.pointCount)
        // The commit is one document step.
        val steps = c.undoManager.undoCount
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertEquals(steps + 1, c.undoManager.undoCount)
    }

    @Test
    fun untouchedPointsKeepSmoothnessHandlesAndRoundnessExactly() {
        val c = rig.controller()
        val tool = pointsRect(c)
        val tl = at(tool, 30f, 30f)
        val tr = at(tool, 110f, 30f)
        val br = at(tool, 110f, 90f)
        val bl = at(tool, 30f, 90f)
        // Bottom left: a roundness of its own (while its corner is between straight edges);
        // bottom right: smooth, with handles of its own.
        select(tool, bl)
        tool.setPointRoundness(12f)
        select(tool, br)
        tool.setSelectedSmooth(true)
        tool.scaleHandles(1.5f)
        assertTrue(tool.selectionHasExplicitHandles)
        val before = tool.points!!
        val beforeAnchors = tool.docAnchors()!!
        assertTrue(before[br].smooth && before[br].handleIn != null && before[br].handleOut != null)
        assertEquals(12f, before[bl].radius)
        // Top right is beside the curved segment to the smooth point: only top left is bevelled.
        select(tool, tl, tr)
        assertTrue(tool.bevelPoints())
        val after = tool.points!!
        assertEquals(5, after.size)
        val afterAnchors = tool.docAnchors()!!
        for (old in listOf(tr, br, bl)) {
            val i = afterAnchors.indexOfFirst { it.pos.distanceTo(beforeAnchors[old].pos) < 0.01f }
            assertTrue("point $old is kept", i >= 0)
            assertEquals("point $old: smoothness, handles and roundness", before[old], after[i])
        }
        assertEquals("only the two new points are selected", 2, tool.pointSelection.count)
    }

    @Test
    fun aSmoothPointOrACornerBesideACurveIsSkippedWithTheMessage() {
        val c = rig.controller()
        val tool = pointsRect(c)
        val tr = at(tool, 110f, 30f)
        val br = at(tool, 110f, 90f)
        select(tool, br)
        tool.setSelectedSmooth(true)
        val shape = tool.points
        val depth = undoDepth(tool)
        for ((what, i) in listOf("the smooth point" to br, "the corner beside its curve" to tr)) {
            c.message = null
            select(tool, i)
            assertTrue(tool.canBevel)
            assertFalse(what, tool.bevelPoints())
            assertEquals(what, PointLabels19.BEVEL_SHARP_ONLY, c.message)
            assertEquals(what, shape, tool.points)
            assertFalse(what, tool.bevelPopupOpen)
            assertEquals("$what: no step", depth, undoDepth(tool))
        }
        // A rounded shape has no sharp corner either.
        c.message = null
        tool.update { it.copy(corner = CornerStyle.ROUND, cornerRadius = 10f) }
        select(tool, at(tool, 30f, 30f))
        assertFalse(tool.bevelPoints())
        assertEquals(PointLabels19.BEVEL_SHARP_ONLY, c.message)
    }

    @Test
    fun tooManyPointsAreRefusedWithTheirMessage() {
        val c = rig.controller()
        c.settings.shapeBevelSegments = 8
        val tool = rig.shapeTool(c)
        tool.update { it.copy(starPoints = 64) }
        rig.drawRect(c, tool, ShapeType.STAR)
        tool.setPointEditing(true)
        assertEquals(128, tool.pointCount)
        tool.selectPoints(PointSelection.all(128))
        val shape = tool.points
        assertFalse(tool.bevelPoints())
        assertEquals("Bevel would make more than ${PointBevel.MAX_POINTS_SHAPE} points", c.message)
        assertEquals(shape, tool.points)
        assertFalse(tool.bevelPopupOpen)
    }

    @Test
    fun thePopupReappliesFromThePointsBeforeTheBevelWithinItsStep() {
        val c = rig.controller()
        val tool = pointsRect(c)
        val depth = undoDepth(tool)
        select(tool, at(tool, 30f, 30f))
        assertTrue(tool.bevelPoints())
        assertEquals(0.15f, tool.bevelAmount, 1e-6f)
        assertEquals(1, tool.bevelSegments)
        // Three segments: four points on the arc in place of the corner.
        tool.changeBevelSegments(3)
        assertEquals(7, tool.pointCount)
        assertEquals(4, tool.pointSelection.count)
        // A third of the 60 px edge: the arc ends 20 px from the corner.
        tool.changeBevelAmount(1f / 3f)
        val a = tool.docAnchors()!!
        val ends = tool.pointSelection.indices.map { a[it].pos }
        assertTrue("arc end on the top edge: $ends", ends.any { it.distanceTo(Vec2(50f, 30f)) < 0.01f })
        assertTrue("arc end on the left edge: $ends", ends.any { it.distanceTo(Vec2(30f, 50f)) < 0.01f })
        assertTrue(tool.bevelPopupOpen)
        // Remembered for the next bevel.
        assertEquals(1f / 3f, c.settings.shapeBevelAmount, 1e-6f)
        assertEquals(3, c.settings.shapeBevelSegments)
        // "Done" closes the popup; the bevel stays.
        tool.closeBevelPopup()
        assertFalse(tool.bevelPopupOpen)
        assertEquals(7, tool.pointCount)
        assertEquals("still one step", depth + 1, undoDepth(tool))
        // One undo takes the whole bevel back.
        assertTrue(tool.undoStep())
        assertEquals(4, tool.pointCount)
        // The next bevel starts from the remembered values.
        select(tool, at(tool, 110f, 90f))
        assertTrue(tool.bevelPoints())
        assertEquals(7, tool.pointCount)
        assertEquals(3, tool.bevelSegments)
    }

    @Test
    fun aSecondTapWhileTheNewPointsStaySelectedReopensThePopup() {
        val c = rig.controller()
        val tool = pointsRect(c)
        val depth = undoDepth(tool)
        select(tool, at(tool, 30f, 30f))
        assertTrue(tool.bevelPoints())
        tool.changeBevelSegments(2)
        val bevelled = tool.points
        tool.closeBevelPopup()
        assertFalse(tool.bevelPopupOpen)
        assertTrue(tool.bevelPoints())
        assertTrue("reopened", tool.bevelPopupOpen)
        assertEquals("not bevelled again", bevelled, tool.points)
        assertEquals("with its values", 2, tool.bevelSegments)
        // Its values still re-apply from the points before it.
        tool.changeBevelSegments(1)
        assertEquals(5, tool.pointCount)
        assertEquals("on the same step", depth + 1, undoDepth(tool))
        // An undo (or another edit) ends the bevel: the next tap bevels the selected (new) points.
        assertFalse(tool.bevelPopupOpen)
        assertEquals(2, tool.pointSelection.count)
        assertTrue(tool.bevelPoints())
        assertEquals(7, tool.pointCount)
        tool.movePoint(at(tool, 110f, 90f), Vec2(120f, 100f))
        assertFalse(tool.bevelPopupOpen)
    }

    @Test
    fun aPinchBesideTheShapePansAndZoomsWithThePopupOpen() {
        val c = rig.controller()
        val tool = pointsRect(c)
        select(tool, at(tool, 30f, 30f))
        assertTrue(tool.bevelPoints())
        val shape = tool.points
        // The first finger lands on the canvas; the second turns the gesture into a pinch: the
        // view cancels the one-finger gesture, then offers the pinch to the tool, which leaves
        // one beside the shape to the view (pan and zoom).
        c.pointerDown(ToolPoint(250f, 200f))
        c.pointerCancel()
        assertFalse(c.twoFingerStart(Vec2(265f, 210f), Vec2(250f, 200f), Vec2(280f, 220f)))
        assertTrue("the popup stays", tool.bevelPopupOpen)
        assertEquals(shape, tool.points)
        // A pinch on the shape edits it: another action, which ends the bevel.
        assertTrue(c.twoFingerStart(Vec2(70f, 60f), Vec2(40f, 40f), Vec2(100f, 80f)))
        c.twoFingerGesture(Vec2(10f, 0f), 1f, 0f)
        c.twoFingerEnd(cancelled = false)
        assertFalse(tool.bevelPopupOpen)
    }

    @Test
    fun theTapThatClosesThePopupIsConsumed() {
        val c = rig.controller()
        val tool = pointsRect(c)
        val depth = undoDepth(tool)
        select(tool, at(tool, 30f, 30f))
        assertTrue(tool.bevelPoints())
        val shape = tool.points
        val box = tool.box
        val selection = tool.pointSelection
        // A tap outside the shape would place it: it only closes the popup.
        rig.tap(c, 220f, 200f)
        assertFalse(tool.bevelPopupOpen)
        assertTrue("still open", tool.hasPendingWork)
        assertEquals(shape, tool.points)
        assertEquals(box, tool.box)
        assertEquals(selection, tool.pointSelection)
        assertEquals(1, c.doc.layers.size)
        // Reopened, a drag from a point neither selects nor moves it.
        assertTrue(tool.bevelPoints())
        assertTrue(tool.bevelPopupOpen)
        val corner = at(tool, 110f, 90f)
        rig.drag(c, 110f to 90f, 130f to 100f, 140f to 110f)
        assertFalse(tool.bevelPopupOpen)
        assertEquals(shape, tool.points)
        assertEquals(selection, tool.pointSelection)
        assertEquals("no step but the bevel", depth + 1, undoDepth(tool))
        // The next tap acts again.
        rig.tap(c, 110f, 90f)
        assertEquals(listOf(corner), tool.pointSelection.indices)
    }
}
