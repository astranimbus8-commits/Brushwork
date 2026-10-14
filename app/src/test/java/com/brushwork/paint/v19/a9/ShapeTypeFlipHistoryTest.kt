package com.brushwork.paint.v19.a9

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.ShapeType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A9, R17 (design 3.2 A9 step 2): a change between a line-like type (Line, Arrow) and a
 * box-like one clears the in-tool history (the boxes before it do not map onto the new geometry);
 * the edits after it undo one by one, and an undo at the bottom acts as in v1.8. A change within
 * one kind keeps the history.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeTypeFlipHistoryTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    @Test
    fun aFlipToALineClearsTheHistoryAndTheEditsAfterItUndoOneByOne() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        rig.drag(c, 70f to 60f, 80f to 60f, 90f to 60f)
        rig.drag(c, 90f to 60f, 90f to 70f, 90f to 80f)
        assertTrue(tool.canUndoStep)
        tool.update { it.copy(type = ShapeType.LINE) }
        assertFalse("the box edits do not map onto a line", tool.canUndoStep)
        assertEquals(0, tool.redoCount)
        val line = tool.box!!
        val steps = c.undoManager.undoCount
        // Edits after the flip are steps again.
        tool.nudge(0, 5)
        val nudged = tool.box!!
        assertNotEquals(line, nudged)
        assertTrue(tool.canUndoStep)
        val end = nudged.end
        rig.drag(c, end.x to end.y, end.x + 10f to end.y, end.x + 20f to end.y + 10f)
        assertNotEquals(nudged, tool.box)
        c.undo()
        assertEquals(nudged, tool.box)
        c.undo()
        assertEquals(line, tool.box)
        assertEquals(ShapeType.LINE, tool.settings.type)
        assertEquals(steps, c.undoManager.undoCount)
        // At the bottom: the new shape is discarded (v1.8).
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertEquals(1, c.doc.layers.size)
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun aFlipFromALineToABoxTypeClearsTheHistoryToo() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        tool.update { it.copy(type = ShapeType.ARROW) }
        rig.drag(c, 40f to 40f, 100f to 70f, 160f to 100f)
        rig.drag(c, 100f to 70f, 100f to 80f, 100f to 90f)
        assertTrue(tool.canUndoStep)
        tool.update { it.copy(type = ShapeType.STAR) }
        assertFalse(tool.canUndoStep)
        c.undo()
        assertFalse("nothing to step back to: undo discards the new shape", tool.hasPendingWork)
    }

    @Test
    fun aFlipWithinOneKindKeepsTheHistory() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        val drawn = tool.box!!
        rig.drag(c, 70f to 60f, 80f to 60f, 90f to 60f)
        tool.update { it.copy(type = ShapeType.ELLIPSE) }
        assertTrue("rectangle to ellipse keeps the steps", tool.canUndoStep)
        c.undo()
        assertEquals(drawn, tool.box)
        assertEquals("undo takes back the move, not the type", ShapeType.ELLIPSE, tool.settings.type)
        // Line to arrow: both line-like.
        tool.update { it.copy(type = ShapeType.LINE) }
        val line = tool.box!!
        tool.nudge(3, 0)
        tool.update { it.copy(type = ShapeType.ARROW) }
        assertTrue("line to arrow keeps the steps", tool.canUndoStep)
        c.undo()
        assertEquals(line, tool.box)
        assertEquals(ShapeType.ARROW, tool.settings.type)
    }

    @Test
    fun aFlipOfAShapeWithItsOwnPointsStillStartsAgainAsInV18() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        tool.setPointEditing(true)
        val p0 = tool.docAnchors()!![0].pos
        tool.movePoint(0, Vec2(p0.x - 10f, p0.y - 5f))
        assertTrue(tool.canUndoStep)
        tool.update { it.copy(type = ShapeType.ELLIPSE) }
        assertNull("the points belong to the old type", tool.docAnchors())
        assertFalse(tool.canUndoStep)
    }

    @Test
    fun aReopenedShapeLayerFlippedToALineIsThrownAwayByUndoAsInV18() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        tool.commit()
        val layer = c.activeLayer
        val data = layer.shapeData
        val steps = c.undoManager.undoCount
        rig.tap(c, 30f, 60f)
        assertSame(layer, tool.editingLayer)
        rig.drag(c, 70f to 60f, 80f to 60f, 90f to 60f)
        tool.update { it.copy(type = ShapeType.LINE) }
        assertFalse(tool.canUndoStep)
        assertTrue("changed: a line now", tool.hasUserChanges)
        // At the bottom with changes: undo throws them away (like ✕) and undoes nothing else.
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertEquals(data, layer.shapeData)
        assertEquals(2, c.doc.layers.size)
        assertEquals(steps, c.undoManager.undoCount)
    }
}
