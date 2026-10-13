package com.brushwork.paint.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9 F1b (design 2.4.1): [ToolHistory], the in-tool history a session keeps of its own steps
 * (D14): record, undo, redo, the cap, reset, and the state flags the UI reads.
 */
class ToolHistoryTest {

    @Test
    fun aNewHistoryHasNothingToUndoOrRedo() {
        val h = ToolHistory("a")
        assertEquals("a", h.current)
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals(0, h.depth)
        assertNull(h.undo())
        assertNull(h.redo())
        assertEquals("a", h.current)
    }

    @Test
    fun recordUndoAndRedoWalkTheStates() {
        val h = ToolHistory(0)
        h.record(1)
        h.record(2)
        h.record(3)
        assertEquals(3, h.current)
        assertEquals(3, h.depth)
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals(2, h.undo())
        assertEquals(1, h.undo())
        assertEquals(1, h.current)
        assertEquals(1, h.depth)
        assertTrue(h.canRedo)
        assertEquals(2, h.redo())
        assertEquals(2, h.current)
        assertEquals(3, h.redo())
        assertNull("nothing left to redo", h.redo())
        assertFalse(h.canRedo)
        assertEquals(2, h.undo())
        assertEquals(1, h.undo())
        assertEquals(0, h.undo())
        assertNull("at the bottom", h.undo())
        assertFalse(h.canUndo)
        assertEquals(0, h.current)
    }

    @Test
    fun aRecordAfterAnUndoDropsTheRedoTail() {
        val h = ToolHistory("a")
        h.record("b")
        h.record("c")
        h.undo()
        assertTrue(h.canRedo)
        h.record("d")
        assertFalse(h.canRedo)
        assertNull(h.redo())
        assertEquals("d", h.current)
        assertEquals("b", h.undo())
        assertEquals("a", h.undo())
    }

    @Test
    fun recordingTheCurrentStateIsNoStep() {
        val h = ToolHistory(listOf(1, 2))
        h.record(listOf(1, 2))
        assertFalse("an equal state is no step", h.canUndo)
        h.record(listOf(1, 2, 3))
        h.undo()
        h.record(listOf(1, 2))
        assertTrue("an unchanged record keeps the redo tail", h.canRedo)
        assertEquals(0, h.depth)
    }

    @Test
    fun theOldestStepsGoPastTheCap() {
        val h = ToolHistory(0, cap = 3)
        for (i in 1..10) h.record(i)
        assertEquals(3, h.depth)
        assertEquals(9, h.undo())
        assertEquals(8, h.undo())
        assertEquals(7, h.undo())
        assertNull("only the newest 3 steps are kept", h.undo())
        assertEquals(7, h.current)
        val none = ToolHistory(0, cap = 0)
        none.record(1)
        assertFalse("cap 0 keeps no step", none.canUndo)
        assertEquals(1, none.current)
        val negative = ToolHistory(0, cap = -5)
        negative.record(1)
        assertEquals(0, negative.depth)
    }

    @Test
    fun theDefaultCapIsAHundredSteps() {
        val h = ToolHistory(0)
        for (i in 1..150) h.record(i)
        assertEquals(100, h.depth)
    }

    @Test
    fun resetMakesANewBottom() {
        val h = ToolHistory("a")
        h.record("b")
        h.record("c")
        h.undo()
        h.reset("z")
        assertEquals("z", h.current)
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals(0, h.depth)
        assertNull(h.undo())
        assertNull(h.redo())
    }
}
