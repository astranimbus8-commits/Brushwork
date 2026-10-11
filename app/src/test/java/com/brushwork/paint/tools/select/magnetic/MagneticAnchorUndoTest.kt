package com.brushwork.paint.tools.select.magnetic

import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.SelectionAction
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.tools.coordinateSourceOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, design §3.4): the magnetic lasso's in-tool steps. Adding, moving (a drag, a pill edit)
 * and deleting an anchor are one step each for the top row's Undo / Redo; the strip's discard is
 * one step that Undo takes back; none of them is a document step. Closing (the first anchor, the
 * strip's close, a double tap) is ONE selection step named "Magnetic lasso", and the tool's steps
 * end with it.
 */
@RunWith(RobolectricTestRunner::class)
class MagneticAnchorUndoTest {

    private fun setUp(): Pair<EditorController, MagneticLassoTool> {
        ShadowLog.stream = null
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), Smoke.document(400, 300, whiteBottom = true))
        c.selectTool(ToolId.MAGNETIC_LASSO)
        val tool = c.currentTool as MagneticLassoTool
        assertTrue("the edge map is built", Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        return c to tool
    }

    private fun EditorController.tap(x: Float, y: Float, time: Long = 0L) {
        pointerDown(ToolPoint(x, y, time = time))
        pointerUp(ToolPoint(x, y, time = time))
    }

    private fun EditorController.drag(from: Pair<Float, Float>, to: Pair<Float, Float>) {
        pointerDown(ToolPoint(from.first, from.second))
        for (k in 1..5) {
            val f = k / 5f
            pointerMove(ToolPoint(from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f))
        }
        pointerUp(ToolPoint(to.first, to.second))
    }

    private fun anchor(t: MagneticLassoTool, i: Int) = t.points.x(i) to t.points.y(i)

    private fun idle(t: MagneticLassoTool) = assertTrue(Smoke.pumpUntil { t.idle })

    @Test
    fun addMoveDeleteAndDiscardAreOneStepEach() {
        val (c, t) = setUp()
        val docSteps = c.undoManager.undoCount
        c.tap(100f, 100f); c.tap(300f, 100f); c.tap(300f, 250f)
        assertEquals(3, t.anchorCount)
        assertEquals(2, t.activeAnchor)
        assertTrue(t.hasPendingWork && t.canUndoStep)

        // Add: one step each.
        c.undo()
        assertEquals(2, t.anchorCount)
        c.redo()
        assertEquals(3, t.anchorCount)
        assertEquals(300f to 250f, anchor(t, 2))

        // Move (a drag of anchor 1): one step, whatever the number of moves.
        c.drag(300f to 100f, 330f to 130f)
        assertEquals(330f to 130f, anchor(t, 1))
        assertEquals(1, t.activeAnchor)
        c.undo()
        assertEquals(300f to 100f, anchor(t, 1))
        c.redo()
        assertEquals(330f to 130f, anchor(t, 1))

        // A tap on an anchor makes it the active one, no step.
        val steps = t.points.undoCount
        c.tap(302f, 248f)
        assertEquals(2, t.activeAnchor)
        assertEquals(3, t.anchorCount)
        assertEquals(steps, t.points.undoCount)

        // Delete (the strip's "Delete point"): one step; its neighbours are joined.
        c.tap(330f, 130f)
        t.deleteActiveAnchor()
        assertEquals(2, t.anchorCount)
        assertEquals(300f to 250f, anchor(t, 1))
        c.undo()
        assertEquals(3, t.anchorCount)
        assertEquals(330f to 130f, anchor(t, 1))

        // The strip's discard: one step, Undo brings the outline back.
        t.discardOutline()
        assertEquals(0, t.anchorCount)
        assertTrue("undo can bring the outline back", t.hasPendingWork && t.canUndoStep)
        c.undo()
        assertEquals(3, t.anchorCount)
        assertEquals(docSteps, c.undoManager.undoCount)
        assertNull(c.selection)

        // Close on the first anchor: ONE selection step, named after the tool.
        idle(t)
        c.tap(100f, 100f)
        idle(t)
        assertNotNull(c.selection)
        assertEquals(docSteps + 1, c.undoManager.undoCount)
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        assertFalse(t.hasPendingWork)
        assertFalse(t.canUndoStep || t.canRedoStep)
        val sel = c.selection!!
        assertEquals(255, sel.alphaAt(250, 150))
        assertEquals(0, sel.alphaAt(50, 50))
        assertTrue("one selection step", c.undoManager.popLast() is SelectionAction)
        Smoke.assertQuiet(c, "magnetic steps")
    }

    @Test
    fun aCancelledDragPutsTheAnchorBack() {
        val (c, t) = setUp()
        c.tap(100f, 100f); c.tap(300f, 100f)
        val steps = t.points.undoCount
        c.pointerDown(ToolPoint(300f, 100f))
        c.pointerMove(ToolPoint(320f, 140f))
        assertEquals(320f to 140f, anchor(t, 1))
        c.pointerCancel()
        assertEquals(300f to 100f, anchor(t, 1))
        assertEquals(steps, t.points.undoCount)
        assertNull(t.loupe)
    }

    @Test
    fun theStripsCloseAndADoubleTapCloseToo() {
        val (c, t) = setUp()
        // The strip's close.
        c.tap(100f, 100f); c.tap(300f, 100f); c.tap(300f, 250f)
        t.commit()
        idle(t)
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        val after = c.undoManager.undoCount
        c.deselect()

        // A double tap: the second tap closes, it never adds an anchor.
        c.tap(100f, 100f, time = 1_000L); c.tap(300f, 100f, time = 2_000L); c.tap(300f, 250f, time = 3_000L)
        assertEquals(3, t.anchorCount)
        c.tap(302f, 252f, time = 3_200L)
        idle(t)
        assertEquals(0, t.anchorCount)
        assertNotNull(c.selection)
        assertEquals(after + 2, c.undoManager.undoCount) // the deselect, then the outline
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)

        // With fewer than three anchors the second tap does nothing.
        c.tap(50f, 50f, time = 10_000L); c.tap(52f, 50f, time = 10_100L)
        assertEquals(1, t.anchorCount)
        // Slow taps are two taps.
        c.tap(150f, 50f, time = 11_000L); c.tap(250f, 60f, time = 12_000L); c.tap(250f, 60f, time = 12_500L)
        assertEquals(3, t.anchorCount)
        // Fewer than three anchors close nothing: the outline is dropped quietly.
        t.discard()
        c.tap(50f, 50f)
        val steps = c.undoManager.undoCount
        t.commit()
        idle(t)
        assertEquals(0, t.anchorCount)
        assertFalse(t.hasPendingWork)
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun allStepsTakenBackThenRedoBringsTheFirstAnchorBack() {
        val (c, t) = setUp()
        c.tap(100f, 100f); c.tap(300f, 100f)
        c.undo(); c.undo()
        assertEquals(0, t.anchorCount)
        assertFalse("nothing pending", t.hasPendingWork)
        c.redo()
        assertEquals(1, t.anchorCount)
        c.redo()
        assertEquals(2, t.anchorCount)
        // Leaving the tool with every anchor taken back ends its steps.
        c.undo(); c.undo()
        c.selectTool(ToolId.BRUSH)
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertFalse(t.canRedoStep)
    }

    @Test
    fun thePillMovesAndDeletesTheActiveAnchor() {
        val (c, t) = setUp()
        assertNull(t.pillPosition.position)
        assertNull(t.objectDeletion)
        c.tap(100f, 100f); c.tap(300f, 100f); c.tap(300f, 250f)
        val source = coordinateSourceOf(t)
        assertNotNull(source)
        assertEquals("Point 3", t.pillPosition.label)
        assertEquals(300f, t.pillPosition.position!!.x)
        // A pill drag: one step.
        val steps = t.points.undoCount
        t.pillPosition.beginPositionEdit()
        t.pillPosition.setPosition(310f, null)
        t.pillPosition.setPosition(320f, 260f)
        t.pillPosition.endPositionEdit()
        assertEquals(steps + 1, t.points.undoCount)
        assertEquals(320f to 260f, anchor(t, 2))
        // A typed value: one step.
        t.pillPosition.setPosition(null, 240f)
        assertEquals(steps + 2, t.points.undoCount)
        c.undo(); c.undo()
        assertEquals(300f to 250f, anchor(t, 2))
        // The trash: the active anchor.
        val del = t.objectDeletion!!
        assertEquals(PillLabels.DELETE_POINTS, del.deleteLabel)
        del.delete()
        assertEquals(2, t.anchorCount)
        assertEquals("Point 2", t.pillPosition.label)
        assertSame(t.pillPosition, t.pillPosition)
        c.undo()
        assertEquals(3, t.anchorCount)
    }
}
