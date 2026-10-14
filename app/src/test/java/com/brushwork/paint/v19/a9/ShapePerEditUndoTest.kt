package com.brushwork.paint.v19.a9

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.ShapeBox
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VShape
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
 * v1.9 A9, R17 (design 3.2 A9 step 1): in the Shape tool every finished edit of the open shape is
 * one in-tool step, for every shape (not only one with its own points, as in v1.8), on legacy
 * shape layers and on the shape objects of vector layers (C19). Undo (the app's, as the button and
 * the two-finger tap run it) takes back the last edit, not the placement; redo brings it back; the
 * commit is still one document step; at the bottom of the in-tool history undo acts as in v1.8 (a
 * new shape is discarded, an opened one that is back as it was opened undoes the step before it).
 */
@RunWith(RobolectricTestRunner::class)
class ShapePerEditUndoTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    /** Runs [edit], checks it changed the open shape and was one in-tool step, and records the box. */
    private fun edited(tool: ShapeTool, states: MutableList<ShapeBox>, what: String, edit: () -> Unit) {
        edit()
        val b = tool.box!!
        assertNotEquals("$what changed the shape", states.last(), b)
        assertTrue("$what is an in-tool step", tool.canUndoStep)
        states += b
    }

    /** Undoes every recorded edit with the app's undo, newest first; the document keeps its steps. */
    private fun undoAll(c: EditorController, tool: ShapeTool, states: List<ShapeBox>, docSteps: Int) {
        for (i in states.size - 2 downTo 0) {
            c.undo()
            assertTrue("undo $i keeps the shape open", tool.hasPendingWork)
            assertEquals("undo back to state $i", states[i], tool.box)
            assertEquals("the document has no new step", docSteps, c.undoManager.undoCount)
        }
        assertFalse("at the bottom nothing is left to take back in the tool", tool.canUndoStep)
    }

    @Test
    fun aNewRectangleUndoesItsMovesNudgesTypedValuesAndPinchesOneByOne() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        var now = 10_000L
        tool.clock = { now }
        rig.drawRect(c, tool)
        assertEquals(ShapeBox(70f, 60f, 80f, 60f, 0f), tool.box)
        assertNull("a rectangle has no points of its own", tool.docAnchors())
        assertFalse("drawing it is the placement, not an edit", tool.canUndoStep)
        val steps = c.undoManager.undoCount
        val states = mutableListOf(tool.box!!)
        // Two moves by dragging the shape.
        edited(tool, states, "move 1") { rig.drag(c, 70f to 60f, 80f to 60f, 90f to 60f) }
        edited(tool, states, "move 2") { rig.drag(c, 90f to 60f, 90f to 70f, 90f to 80f) }
        // A run of nudges is one step; a nudge after a pause is the next.
        edited(tool, states, "nudges") { tool.nudge(1, 0); tool.nudge(1, 0); tool.nudge(1, 0) }
        assertEquals(93f, tool.box!!.cx, 1e-3f)
        now += 5_000L
        edited(tool, states, "a later nudge") { tool.nudge(0, 1) }
        // A typed value (each value after a pause is a step).
        now += 5_000L
        edited(tool, states, "typed width") { tool.place(tool.box!!.copy(w = 50f)) }
        now += 5_000L
        edited(tool, states, "typed angle") { tool.place(tool.box!!.copy(rotationDeg = 30f)) }
        // A pinch.
        edited(tool, states, "pinch") {
            val b = tool.box!!
            assertTrue(tool.onTwoFingerStart(b.center, b.center, Vec2(b.cx + 20f, b.cy + 20f)))
            tool.onTwoFingerGesture(Vec2(15f, 0f), 1.2f, 0f)
            tool.onTwoFingerEnd(cancelled = false)
        }
        assertEquals(8, states.size)
        // Undo takes back one edit at a time; redo brings them back.
        c.undo(); c.undo()
        assertEquals(states[5], tool.box)
        assertEquals(2, tool.redoCount)
        c.redo()
        assertEquals(states[6], tool.box)
        c.redo()
        assertEquals(states[7], tool.box)
        undoAll(c, tool, states, steps)
        // At the bottom undo discards the new shape (v1.8): no layer, no step.
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertEquals(1, c.doc.layers.size)
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun theCommitAfterUndoneEditsIsOneStepWithTheShapeAsShown() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        rig.drag(c, 70f to 60f, 80f to 60f, 100f to 60f)
        rig.drag(c, 100f to 60f, 100f to 80f, 100f to 100f)
        c.undo()
        val shown = tool.box!!
        assertEquals(ShapeBox(100f, 60f, 80f, 60f, 0f), shown)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ShapeTool.SHAPE_LABEL, c.undoManager.undoLabel)
        val layer = c.activeLayer
        assertTrue(layer.isShapeLayer)
        assertEquals(shown, ShapeCodec.decode(layer.shapeData)!!.box)
    }

    @Test
    fun aLineUndoesItsMoveAndItsEndDrag() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        tool.update { it.copy(type = ShapeType.LINE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 6f) }
        rig.drag(c, 40f to 40f, 100f to 70f, 160f to 100f)
        val steps = c.undoManager.undoCount
        val states = mutableListOf(tool.box!!)
        edited(tool, states, "line move") { rig.drag(c, 100f to 70f, 100f to 80f, 100f to 90f) }
        val end = tool.box!!.end
        edited(tool, states, "line end") { rig.drag(c, end.x to end.y, end.x + 10f to end.y, end.x + 20f to end.y + 10f) }
        undoAll(c, tool, states, steps)
    }

    @Test
    fun aPinchThatChangesNothingIsNoStepAndACancelledOneLeavesNone() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        val b = tool.box!!
        assertTrue(tool.onTwoFingerStart(b.center, b.center, Vec2(b.cx + 20f, b.cy + 20f)))
        tool.onTwoFingerGesture(Vec2.ZERO, 1f, 0f)
        tool.onTwoFingerEnd(cancelled = false)
        assertEquals(b, tool.box)
        assertFalse(tool.canUndoStep)
        assertTrue(tool.onTwoFingerStart(b.center, b.center, Vec2(b.cx + 20f, b.cy + 20f)))
        tool.onTwoFingerGesture(Vec2(30f, 0f), 1f, 0f)
        tool.onTwoFingerEnd(cancelled = true)
        assertEquals(b, tool.box)
        assertFalse(tool.canUndoStep)
    }

    @Test
    fun aReopenedShapeLayerUndoesEachEditBackToHowItWasOpened() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        tool.commit()
        val layer = c.activeLayer
        assertTrue(layer.isShapeLayer)
        val data = layer.shapeData
        val steps = c.undoManager.undoCount
        rig.tap(c, 30f, 60f)
        assertSame(layer, tool.editingLayer)
        val states = mutableListOf(tool.box!!)
        edited(tool, states, "move 1") { rig.drag(c, 70f to 60f, 80f to 60f, 90f to 60f) }
        edited(tool, states, "move 2") { rig.drag(c, 90f to 60f, 90f to 70f, 90f to 80f) }
        edited(tool, states, "nudge") { tool.nudge(-1, 0) }
        undoAll(c, tool, states, steps)
        assertSame("still open", layer, tool.editingLayer)
        assertFalse("back as it was opened", tool.hasUserChanges)
        assertEquals("the layer is untouched meanwhile", data, layer.shapeData)
        // Redo, then the commit is one "Edit shape" step with the shape as shown.
        c.redo()
        assertEquals(states[1], tool.box)
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        assertEquals(states[1], ShapeCodec.decode(layer.shapeData)!!.box)
        c.undo()
        assertEquals(data, layer.shapeData)
    }

    @Test
    fun atTheBottomAnOpenedShapeLayerUndoesTheStepBeforeItAsInV18() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        tool.commit()
        val layer = c.activeLayer
        rig.tap(c, 30f, 60f)
        assertSame(layer, tool.editingLayer)
        rig.drag(c, 70f to 60f, 80f to 60f, 90f to 60f)
        c.undo()
        assertSame("the move is taken back, the shape stays open", layer, tool.editingLayer)
        assertEquals(2, c.doc.layers.size)
        // Untouched again: undo closes it and takes back the shape itself (v1.8).
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertEquals(1, c.doc.layers.size)
    }

    @Test
    fun aShapeObjectOfAVectorLayerUndoesEachEditBackToHowItWasOpened() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        assertTrue(tool.drawsOnVectorLayer)
        val vec = c.doc.layers[1]
        // A new shape object: its edits are steps too, and at the bottom undo drops it.
        rig.drawRect(c, tool)
        val created = tool.box!!
        rig.drag(c, 70f to 60f, 80f to 60f, 90f to 60f)
        c.undo()
        assertEquals(created, tool.box)
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertTrue(vec.vector!!.objects.isEmpty())
        // Placed, then reopened by a tap.
        rig.drawRect(c, tool)
        tool.commit()
        val placed = vec.vector!!
        val id = (placed.objects.single() as VShape).id
        val steps = c.undoManager.undoCount
        rig.tap(c, 30f, 60f)
        assertTrue(tool.editingObject)
        val states = mutableListOf(tool.box!!)
        edited(tool, states, "move 1") { rig.drag(c, 70f to 60f, 80f to 60f, 90f to 60f) }
        edited(tool, states, "move 2") { rig.drag(c, 90f to 60f, 90f to 70f, 90f to 80f) }
        edited(tool, states, "typed height") { tool.place(tool.box!!.copy(h = 30f)) }
        undoAll(c, tool, states, steps)
        assertTrue("still open", tool.editingObject)
        assertFalse(tool.hasUserChanges)
        c.redo(); c.redo()
        assertEquals(states[2], tool.box)
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        assertEquals(states[2], (vec.vector!!.byId(id) as VShape).shape.box)
        assertTrue(vec.isVectorLayer)
        c.undo()
        assertSame(placed, vec.vector)
    }
}
