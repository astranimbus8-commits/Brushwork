package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 10, design §3.10 and §4.4): the curve tools' in-tool history under a history tap
 * over the UI. [CurveTool.historyMark] is equal while nothing changed; [CurveTool.rollbackHistory]
 * puts the points, the selection and the in-tool undo and redo back as they were at the mark (the
 * steps since are dropped, not redone), also an edit that joined the step before the mark, and
 * does nothing once the tool edits another path.
 */
@RunWith(RobolectricTestRunner::class)
class CurveHistoryMarkRobolectricTest {

    private val five = listOf(40f to 240f, 110f to 50f, 190f to 250f, 270f to 40f, 350f to 230f)

    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }

    @Test
    fun aMarkIsEqualUntilSomethingChangesAndTheRollbackPutsBackPointsSelectionAndSteps() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = controller()
            val t = c.tool(id)
            t.plainLine()
            for ((x, y) in five) c.tap(x, y)
            t.selectPoints(PointSelection.of(5, 1, 2))
            val n = steps(t)
            val m = t.historyMark()
            assertEquals("$id: nothing changed", m, t.historyMark())
            val points = (0 until t.pointCount).map { t.pointAt(it) }

            // The first finger's change over the UI: a typed thickness, then a deletion.
            t.setWidths(listOf(1, 2), floatArrayOf(0.5f, 0.5f))
            t.selectPoints(PointSelection.of(5, 3))
            assertTrue(t.deleteSelectedPoints())
            assertNotEquals(m, t.historyMark())
            t.rollbackHistory(m)
            // (Compared first: counting the steps undoes and redoes them, which makes new states.)
            assertEquals("$id: as at the mark", m, t.historyMark())
            assertEquals("$id: the points", points, (0 until t.pointCount).map { t.pointAt(it) })
            assertEquals("$id: the selection", PointSelection.of(5, 1, 2), t.pointSelection)
            assertFalse("$id: not redone", t.redoStep())
            assertEquals("$id: the steps since are dropped", n, steps(t))
            t.discard()
        }
    }

    @Test
    fun anEditThatJoinedTheStepBeforeTheMarkAndAnInToolUndoAreRolledBack() {
        val c = controller()
        val t = c.tool(ToolId.POLYLINE)
        for ((x, y) in five) assertTrue(t.addAnchor(Vec2(x, y)))
        var now = 1_000L
        t.clock = { now }
        // (Counted before the nudges: counting undoes and redoes, which ends a run of nudges.)
        val n = steps(t)
        t.select(2)
        val x0 = t.anchors[2].x
        t.nudge(1, 0)
        val x1 = t.anchors[2].x
        val m = t.historyMark()
        // A held arrow: this nudge joins the step before the mark (same key, quickly).
        now += 10
        t.nudge(1, 0)
        assertNotEquals(x1, t.anchors[2].x)
        assertNotEquals(m, t.historyMark())
        t.rollbackHistory(m)
        assertEquals(x1, t.anchors[2].x)
        assertEquals(m, t.historyMark())
        // The step before the mark is whole again: one undo takes back the first nudge.
        assertTrue(t.undoStep())
        assertEquals(x0, t.anchors[2].x)
        assertTrue(t.redoStep())
        assertEquals(x1, t.anchors[2].x)

        // The tool's own Undo button pressed by the first finger: the undo and its redo go back.
        val m2 = t.historyMark()
        assertTrue(t.undoStep())
        t.rollbackHistory(m2)
        assertEquals(x1, t.anchors[2].x)
        assertEquals(m2, t.historyMark())
        assertFalse("the redo is as at the mark", t.redoStep())
        assertEquals("the nudge is one step", n + 1, steps(t))
        t.discard()
    }

    @Test
    fun throughTheControllerTheNextUndoTakesBackExactlyTheStepBeforeTheMark() {
        val c = controller()
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        for ((x, y) in five) c.tap(x, y)
        val count = t.pointCount
        val ui = c.uiMark()
        t.select(0)
        t.setWidths(listOf(0), floatArrayOf(2f))
        assertTrue(c.restoreUiMark(ui))
        c.releaseUiMark(ui)
        assertEquals(1f, t.anchors[0].width)
        c.undo()
        assertEquals("one undo takes back the last point", count - 1, t.pointCount)
        t.discard()
    }

    @Test
    fun aMarkOfAnotherPathDoesNothing() {
        val c = controller()
        val t = c.tool(ToolId.PATH)
        t.plainLine()
        for ((x, y) in five) c.tap(x, y)
        val m = t.historyMark()
        t.commit()
        for ((x, y) in five.take(3)) c.tap(x, y + 20f)
        val after = t.spline
        t.rollbackHistory(m)
        assertEquals(after, t.spline)
        assertEquals(3, t.pointCount)
        t.discard()
    }
}
