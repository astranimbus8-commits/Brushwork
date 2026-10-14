package com.brushwork.paint.v19.a9

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A9, R14 (design 3.2 A9 step 5): the Shape More row's object items through the object
 * bar's actions ([ObjectActions.duplicate], [ObjectActions.arrange]): the open shape is placed
 * (its own step; nothing for an untouched reopened one), the action is its own step, and the
 * copy (Duplicate) or the shape itself (Bring forward, Send backward) opens; no object selection
 * is left behind. They apply to a shape object of a vector layer, also a new shape that joins
 * the selected shape layer, never in Points mode nor to a shape of a layer of its own.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeArrangeDuplicateTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    private fun shapes(layer: Layer): List<VShape> = layer.vector!!.objects.filterIsInstance<VShape>()

    /** An ellipse from (140, 110) up to (60, 50), over the rectangle of [A9Rig.drawRect]; pending. */
    private fun drawOverlapping(c: EditorController, tool: ShapeTool) {
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE) }
        rig.drag(c, 140f to 110f, 100f to 80f, 60f to 50f)
        assertTrue(tool.hasPendingWork)
    }

    @Test
    fun aNewShapeIsPlacedThenDuplicatedAndTheCopyOpens() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        val vec = c.doc.layers[1]
        rig.drawRect(c, tool)
        val drawn = tool.box!!
        assertTrue(tool.objectActionsApply)
        val steps = c.undoManager.undoCount
        assertTrue(tool.duplicateOpen())
        val placed = shapes(vec)
        assertEquals("the shape and its copy", 2, placed.size)
        assertEquals("placed, then duplicated: two steps", steps + 2, c.undoManager.undoCount)
        assertEquals(ObjectActions.DUPLICATE_LABEL, c.undoManager.undoLabel)
        val d = ObjectActions.duplicateOffset(c)
        assertTrue("the copy is open", tool.editingObject)
        val open = tool.box!!
        assertEquals("right by the object bar's offset", drawn.cx + d, open.cx, 0.01f)
        assertEquals("down by the object bar's offset", drawn.cy + d, open.cy, 0.01f)
        assertEquals("the copy is the one on top", placed.last().shape.box, open)
        assertEquals(drawn.cx, placed.first().shape.box.cx, 0.01f)
        assertTrue("no object selection left: the object bar stays hidden", c.vectors.selectedIds.isEmpty())
        assertFalse(tool.hasUserChanges)

        // Undo: the untouched copy closes and goes (v1.8 at the bottom); the shape stays.
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertEquals(1, shapes(vec).size)
        assertEquals(steps + 1, c.undoManager.undoCount)
    }

    @Test
    fun anUntouchedReopenedShapeObjectIsDuplicatedInOneStep() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        val vec = c.doc.layers[1]
        rig.drawRect(c, tool)
        tool.commit()
        val id = shapes(vec).single().id
        assertTrue(tool.editShapeObject(vec, id))
        assertTrue(tool.editingObject)
        assertTrue(tool.objectActionsApply)
        val steps = c.undoManager.undoCount
        assertTrue(tool.duplicateOpen())
        assertEquals("only the copy is a step", steps + 1, c.undoManager.undoCount)
        val placed = shapes(vec)
        assertEquals(2, placed.size)
        assertTrue("the copy is open", tool.editingObject)
        assertEquals(placed.last().shape.box, tool.box)
        assertTrue(placed.last().id != id)
    }

    @Test
    fun bringForwardAndSendBackwardMoveTheOpenShapePastTheOneItOverlaps() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        val vec = c.doc.layers[1]
        rig.drawRect(c, tool)
        tool.commit()
        drawOverlapping(c, tool)
        tool.commit()
        val (a, b) = shapes(vec).map { it.id }
        assertTrue(tool.editShapeObject(vec, a))
        val boxA = tool.box!!
        val steps = c.undoManager.undoCount

        assertTrue(tool.arrangeOpen(ObjectEdits.Arrange.FORWARD))
        assertEquals("the rectangle is above the ellipse", listOf(b, a), shapes(vec).map { it.id })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ObjectEdits.Arrange.FORWARD.label, c.undoManager.undoLabel)
        assertTrue("the rectangle stays open", tool.editingObject)
        assertEquals(boxA, tool.box)
        assertTrue(c.vectors.selectedIds.isEmpty())

        assertTrue(tool.arrangeOpen(ObjectEdits.Arrange.BACKWARD))
        assertEquals("below it again", listOf(a, b), shapes(vec).map { it.id })
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(ObjectEdits.Arrange.BACKWARD.label, c.undoManager.undoLabel)
        assertTrue(tool.editingObject)
        assertEquals(boxA, tool.box)

        // A moved shape: the move is its own step ("Edit shape"), then the arrangement.
        rig.drag(c, 70f to 60f, 75f to 60f, 80f to 60f)
        val moved = tool.box!!
        assertTrue(tool.arrangeOpen(ObjectEdits.Arrange.FORWARD))
        assertEquals(steps + 4, c.undoManager.undoCount)
        assertEquals(listOf(b, a), shapes(vec).map { it.id })
        assertEquals(moved, tool.box)
        assertEquals(moved, shapes(vec).last().shape.box)
    }

    @Test
    fun aLoneShapeIsAlreadyInFrontAndAtTheBackAndStaysOpen() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        val vec = c.doc.layers[1]
        rig.drawRect(c, tool)
        val drawn = tool.box!!
        val steps = c.undoManager.undoCount
        assertFalse(tool.arrangeOpen(ObjectEdits.Arrange.FORWARD))
        assertEquals("Already in front", c.message)
        assertEquals("only the placement", steps + 1, c.undoManager.undoCount)
        assertEquals("Shape", c.undoManager.undoLabel)
        assertTrue("the shape opens again", tool.editingObject)
        assertEquals(drawn, tool.box)
        assertFalse(tool.arrangeOpen(ObjectEdits.Arrange.BACKWARD))
        assertEquals("Already at the back", c.message)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue(tool.editingObject)
        assertEquals(1, shapes(vec).size)
        assertTrue(c.vectors.selectedIds.isEmpty())
    }

    @Test
    fun aShapeJoiningTheSelectedShapeLayerIsDuplicatedThere() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        tool.commit()
        val layer = c.activeLayer
        assertTrue(layer.isShapeLayer && !layer.isVectorLayer)
        val data = layer.shapeData
        val layers = c.doc.layers.size
        drawOverlapping(c, tool)
        assertTrue("a new shape that joins the layer", tool.objectActionsApply)
        val steps = c.undoManager.undoCount
        assertTrue(tool.duplicateOpen())
        assertSame(layer, c.activeLayer)
        assertEquals("no new layer", layers, c.doc.layers.size)
        assertEquals("the rectangle, the ellipse and its copy", 3, shapes(layer).size)
        assertEquals("the join, then the copy", steps + 2, c.undoManager.undoCount)
        assertTrue("the copy is open", tool.editingObject)
        assertEquals(shapes(layer).last().shape.box, tool.box)

        c.undo()
        assertEquals(2, shapes(layer).size)
        c.undo()
        assertNull("the legacy layer again", layer.vector)
        assertEquals(data, layer.shapeData)
    }

    @Test
    fun theItemsDoNotApplyInPointsModeNorToAShapeOfALayerOfItsOwn() {
        // Points mode on a vector layer: nothing happens.
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        val vec = c.doc.layers[1]
        rig.drawRect(c, tool)
        tool.setPointEditing(true)
        assertFalse(tool.objectActionsApply)
        val steps = c.undoManager.undoCount
        assertFalse(tool.duplicateOpen())
        assertFalse(tool.arrangeOpen(ObjectEdits.Arrange.FORWARD))
        assertTrue("still pending", tool.hasPendingWork)
        assertEquals(steps, c.undoManager.undoCount)
        assertTrue(vec.vector!!.objects.isEmpty())

        // A raster layer selected: a layer of its own (v1.8).
        val c2 = rig.controller()
        val tool2 = rig.shapeTool(c2)
        rig.drawRect(c2, tool2)
        assertFalse(tool2.objectActionsApply)
        assertFalse(tool2.duplicateOpen())
        assertTrue(tool2.hasPendingWork)
        assertEquals(1, c2.doc.layers.size)
        tool2.commit()
        val layer = c2.activeLayer
        // A reopened legacy shape layer: one shape, no objects beside it.
        rig.tap(c2, 30f, 60f)
        assertSame(layer, tool2.editingLayer)
        assertFalse(tool2.objectActionsApply)
        assertFalse(tool2.arrangeOpen(ObjectEdits.Arrange.BACKWARD))
        assertSame("still open", layer, tool2.editingLayer)
        tool2.commit()
        // "New layer each time": a new shape over the shape layer gets a layer of its own.
        tool2.update { it.copy(newLayerEachTime = true) }
        drawOverlapping(c2, tool2)
        assertFalse(tool2.objectActionsApply)
        tool2.update { it.copy(newLayerEachTime = false) }
        assertTrue("it joins the layer now", tool2.objectActionsApply)
        // Editable off: painted into the layer.
        tool2.update { it.copy(editable = false) }
        assertFalse(tool2.objectActionsApply)
    }
}
