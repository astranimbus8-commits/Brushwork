package com.brushwork.paint.vector.select

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.lift.VectorLift
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 A2 (§4.9 Object bar): Delete, Duplicate (+16 px), Forward / Backward / Front / Back, Recolor
 * (fill + line, or lines only), Transform and Deselect on the selected objects — each edit ONE
 * undo step whose undo / redo restore data and pixels together; arranging moves past overlapping
 * objects only and keeps the selection's own order.
 */
@RunWith(RobolectricTestRunner::class)
class ObjectActionsRobolectricTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun ids(c: EditorController) = c.vec.vector!!.objects.map { it.id }

    /** [action] is one step; undo gives back [before] (data and pixels), redo the result. */
    private fun assertOneStep(c: EditorController, label: String, action: () -> Boolean): VectorContent {
        val layer = c.vec
        val before = layer.vector!!
        val pix = kit.pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        assertTrue(action())
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
        val after = layer.vector!!
        assertArrayEquals(kit.render(after), kit.pixels(layer.bitmap))
        c.undo()
        assertSame(before, layer.vector)
        assertArrayEquals(pix, kit.pixels(layer.bitmap))
        c.redo()
        assertSame(after, layer.vector)
        assertArrayEquals(kit.render(after), kit.pixels(layer.bitmap))
        return after
    }

    private fun setup(): EditorController {
        val c = kit.controller()
        c.vectors.addObjects(
            c.vec,
            listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(60f, 40f, 160f, 140f), kit.ellipse(130f, 90f), kit.box(380f, 250f, 460f, 330f)),
            "Add",
        )
        return c
    }

    @Test
    fun deleteRemovesTheSelectedObjectsAndDeselects() {
        val c = setup()
        c.vectors.setSelection(c.vec, setOf(2L, 3L))
        val after = assertOneStep(c, ObjectActions.DELETE_LABEL) { ObjectActions.delete(c) }
        assertEquals(listOf(1L, 4L), after.objects.map { it.id })
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertFalse("nothing selected: nothing to do", ObjectActions.delete(c))
    }

    @Test
    fun duplicateCopiesAboveTheSelectionMovedBy16AndSelectsTheCopies() {
        val c = setup()
        val before = c.vec.vector!!
        c.vectors.setSelection(c.vec, setOf(1L, 3L))
        val after = assertOneStep(c, ObjectActions.DUPLICATE_LABEL) { ObjectActions.duplicate(c) }
        // Copies of 1 and 3 (in their order) right above 3, the topmost of them.
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L, 4L), after.objects.map { it.id })
        assertEquals(setOf(5L, 6L), c.vectors.selectedIds)
        assertEquals(7L, after.nextId)
        val s0 = before.byId(1) as VStroke
        val s1 = after.byId(5) as VStroke
        for (i in 0 until s0.points.size) {
            assertEquals(s0.points.x[i] + 16f, s1.points.x[i], 0f)
            assertEquals(s0.points.y[i] + 16f, s1.points.y[i], 0f)
        }
        assertEquals(s0.copy(id = 5L, points = s1.points), s1)
        val e0 = before.byId(3) as VShape
        val e1 = after.byId(6) as VShape
        assertEquals(e0.shape.copy(cx = e0.shape.cx + 16f, cy = e0.shape.cy + 16f), e1.shape)
    }

    @Test
    fun arrangingMovesPastOverlappingObjectsAndKeepsTheOrder() {
        val c = setup()
        // 1 stroke, 2 box, 3 ellipse overlap one another; 4 is apart.
        c.vectors.setSelection(c.vec, setOf(1L))
        // Forward skips nothing here (2 overlaps 1): one step up.
        assertOneStep(c, ObjectEdits.Arrange.FORWARD.label) { ObjectActions.arrange(c, ObjectEdits.Arrange.FORWARD) }
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(c))
        assertOneStep(c, ObjectEdits.Arrange.FORWARD.label) { ObjectActions.arrange(c, ObjectEdits.Arrange.FORWARD) }
        assertEquals(listOf(2L, 3L, 1L, 4L), ids(c))
        // Nothing above overlaps it (4 is elsewhere): no change, said so.
        val steps = c.undoManager.undoCount
        assertFalse(ObjectActions.arrange(c, ObjectEdits.Arrange.FORWARD))
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals("Already in front", c.message)
        // To the front passes 4 too.
        assertOneStep(c, ObjectEdits.Arrange.FRONT.label) { ObjectActions.arrange(c, ObjectEdits.Arrange.FRONT) }
        assertEquals(listOf(2L, 3L, 4L, 1L), ids(c))
        // Backward goes under the nearest overlapping object below (3), skipping 4.
        assertOneStep(c, ObjectEdits.Arrange.BACKWARD.label) { ObjectActions.arrange(c, ObjectEdits.Arrange.BACKWARD) }
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(c))
        // Two selected objects keep their own order going to the back.
        c.vectors.setSelection(c.vec, setOf(3L, 4L))
        assertOneStep(c, ObjectEdits.Arrange.BACK.label) { ObjectActions.arrange(c, ObjectEdits.Arrange.BACK) }
        assertEquals(listOf(3L, 4L, 2L, 1L), ids(c))
        assertFalse(ObjectActions.arrange(c, ObjectEdits.Arrange.BACK))
        assertEquals("Already at the back", c.message)
        assertEquals(setOf(3L, 4L), c.vectors.selectedIds)
    }

    @Test
    fun recolorPaintsLinesAndFillsOrOnlyTheLines() {
        val c = setup()
        val red = 0xFFD02030.toInt()
        c.color = red
        c.vectors.setSelection(c.vec, setOf(1L, 2L, 3L))
        // Lines only (the long press): fills stay.
        var after = assertOneStep(c, ObjectActions.RECOLOR_LINES_LABEL) { ObjectActions.recolor(c, linesOnly = true) }
        assertEquals(red, (after.byId(1) as VStroke).color)
        val box = after.byId(2) as VPath
        assertEquals(red, box.stroke!!.color)
        assertEquals(VPaint.Solid(0xFFE04020.toInt()), box.fill)
        val e = after.byId(3) as VShape
        assertEquals(red, e.shape.strokeColor)
        assertEquals(0xFF60D090.toInt(), e.shape.fillColor)
        // Fill + line.
        after = assertOneStep(c, ObjectActions.RECOLOR_LABEL) { ObjectActions.recolor(c, linesOnly = false) }
        assertEquals(VPaint.Solid(red), (after.byId(2) as VPath).fill)
        assertEquals(red, (after.byId(3) as VShape).shape.fillColor)
        // Again: nothing changes.
        assertFalse(ObjectActions.recolor(c, linesOnly = false))
        assertEquals("Already in this color", c.message)
        assertSame("the unselected object is untouched", c.vec.vector!!.byId(4), after.byId(4))
    }

    @Test
    fun recolorEditsTurnGradientsIntoTheColorAndNeverAddFills() {
        val gradient = VPaint.Linear(0f, 0f, 10f, 0f, listOf(VStop(0f, -1), VStop(1f, 0xFF000000.toInt())))
        val path = kit.box(0f, 0f, 10f, 10f).copy(id = 1, fill = gradient)
        val lineOnly = path.copy(id = 2, fill = null)
        val fillOnly = path.copy(id = 3, stroke = null)
        val content = VectorContent(objects = listOf<VObject>(path, lineOnly, fillOnly), nextId = 4)
        val out = ObjectEdits.recolor(content, setOf(1L, 2L, 3L), 0xFF00FF00.toInt(), linesOnly = false)!!
        assertEquals(VPaint.Solid(0xFF00FF00.toInt()), (out.byId(1) as VPath).fill)
        assertNull((out.byId(2) as VPath).fill)
        assertNull((out.byId(3) as VPath).stroke)
        assertNull("a fill-only path has no lines", ObjectEdits.recolor(VectorContent(objects = listOf(fillOnly)), setOf(3L), 0xFF00FF00.toInt(), linesOnly = true))
        assertNull(ObjectEdits.delete(content, setOf(9L)))
        assertNull(ObjectEdits.duplicate(content, setOf(9L), 16f, 16f))
        assertNull(ObjectEdits.arrange(content, setOf(9L), ObjectEdits.Arrange.FRONT) { _, _ -> true })
        assertNull(ObjectEdits.arrange(content, setOf(3L), ObjectEdits.Arrange.FRONT) { _, _ -> true })
        assertNull(ObjectEdits.arrange(content, setOf(1L), ObjectEdits.Arrange.BACK) { _, _ -> true })
    }

    @Test
    fun transformLiftsTheSelectionAndActionsCommitAPendingTransformFirst() {
        val c = setup()
        c.vectors.setSelection(c.vec, setOf(4L))
        ObjectActions.transform(c)
        kit.idle()
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertNotNull(tool.transformState)
        assertEquals(setOf(4L), VectorLift.activeLift(c)!!.ids)
        val box0 = (c.vec.vector!!.byId(4) as VPath).subpaths[0].anchors[0]
        // Moved, then duplicated from the bar: the move is applied first (its own step), the copy
        // is made of the moved box, and the copy is lifted.
        tool.moveBy(-100f, -50f)
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.duplicate(c))
        assertEquals(steps + 2, c.undoManager.undoCount)
        val moved = (c.vec.vector!!.byId(4) as VPath).subpaths[0].anchors[0]
        assertEquals(Vec2(box0.x - 100f, box0.y - 50f), Vec2(moved.x, moved.y))
        val copy = (c.vec.vector!!.byId(5) as VPath).subpaths[0].anchors[0]
        assertEquals(Vec2(moved.x + 16f, moved.y + 16f), Vec2(copy.x, copy.y))
        assertEquals(setOf(5L), c.vectors.selectedIds)
        assertEquals(setOf(5L), VectorLift.activeLift(c)!!.ids)
        // The Transform button while lifted: lifts the selection again.
        ObjectActions.transform(c)
        assertEquals(setOf(5L), VectorLift.activeLift(c)!!.ids)
        // Deselect while lifted: everything is lifted instead.
        ObjectActions.deselect(c)
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), VectorLift.activeLift(c)!!.ids)
        tool.discard()
        // Delete while lifted: the lift ends and the objects are gone (no resurrection on ✓).
        c.vectors.setSelection(c.vec, setOf(2L))
        tool.start()
        assertEquals(setOf(2L), VectorLift.activeLift(c)!!.ids)
        assertTrue(ObjectActions.delete(c))
        assertNull(VectorLift.activeLift(c))
        assertNull(c.vec.vector!!.byId(2))
        assertFalse(tool.hasPendingWork)
    }

    @Test
    fun aLockedLayerRefusesWithAMessage() {
        val c = setup()
        c.vectors.setSelection(c.vec, setOf(1L))
        c.vec.locked = true
        val before = c.vec.vector
        val steps = c.undoManager.undoCount
        ObjectActions.delete(c)
        assertSame(before, c.vec.vector)
        assertEquals(steps, c.undoManager.undoCount)
        assertNotNull(c.message)
    }
}
