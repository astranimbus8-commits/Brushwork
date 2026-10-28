package com.brushwork.paint.v110.g2vector

import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.v110.a6.A6Paths
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 G2 (V2, R2 "object-only edit"): a new object whose colors were previewed through the
 * pill's Object colors editor is placed, and another object opened, in ONE call: the bars' Edit
 * ([ObjectActions.openInEditor]) in the Shape tool, `CurveTool.openObject` in the Polyline tool.
 * The tools give the user's main color and options back synchronously when the new object ends,
 * so the opened object takes the user's as the ones to give back: while it is open the main color
 * is its own (also after a frame, when the old end-of-session watch would have run), and its ✓
 * brings back the user's red and options, not the new object's blue and green. The placed object
 * keeps its previewed colors; undo and redo take its step back and forth without touching the
 * main color.
 */
@RunWith(RobolectricTestRunner::class)
class G2VectorColorsEndInSameCallTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    /** What a frame does: state to its observers (the old watch), the main looper's work. */
    private fun frame() {
        Snapshot.sendApplyNotifications()
        kit.idle()
    }

    private fun hex(c: Int) = "%08X".format(c)

    private fun assertColor(what: String, expected: Int, actual: Int) = assertEquals(what, hex(expected), hex(actual))

    @Test
    fun aShapePlacedByEditGivesTheUsersColorToTheShapeItOpens() {
        val c = kit.controller()
        c.color = RED
        val id = A6Paths.add(
            kit, c,
            VShape(0, shape = ShapeObject(ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 80f, h = 60f, style = ShapeStyle.STROKE_FILL, strokeWidth = 4f, strokeColor = PURPLE, fillColor = YELLOW)),
        ).single()
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        c.pointerDown(ToolPoint(220f, 160f))
        c.pointerMove(ToolPoint(270f, 200f))
        c.pointerUp(ToolPoint(320f, 240f))
        frame()
        val colors = requireNotNull(shape.objectColors) { "a shape is open" }
        colors.begin()
        colors.preview(ObjectColorTarget.STROKE, BLUE)
        colors.preview(ObjectColorTarget.FILL, GREEN)
        colors.commit()
        assertColor("the new shape's stroke is the main color while it is open", BLUE, c.color)

        // Edit on the other shape: the new one is placed and that one opens, in this one call.
        A6Paths.select(c, id)
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.openInEditor(c))
        assertTrue("the shape opens", kit.idleUntil { shape.editingObject && shape.hasPendingWork })
        assertEquals("the new shape's step", steps + 1, c.undoManager.undoCount)
        val placed = c.vec.vector!!.objects.last() as VShape
        assertColor("the placed shape keeps its stroke", BLUE, placed.shape.strokeColor)
        assertColor("and its fill", GREEN, placed.shape.fillColor)
        frame()
        assertColor("the opened shape's color is the main color", PURPLE, c.color)

        // ✓ on the opened shape (unchanged: no step): the user's red and options come back.
        shape.commit()
        frame()
        assertColor("the user's main color", RED, c.color)
        assertNull("the user's fill (none of its own)", shape.settings.fillColor)
        assertEquals("no step for the unchanged shape", steps + 1, c.undoManager.undoCount)

        c.undo()
        kit.idle()
        assertEquals("undo takes the new shape back", 1, c.vec.vector!!.objects.size)
        c.redo()
        kit.idle()
        assertEquals(placed, c.vec.vector!!.objects.last())
        assertColor("undo and redo leave the main color", RED, c.color)
    }

    @Test
    fun aPathPlacedByOpenObjectGivesTheUsersColorToThePathItOpens() {
        val c = kit.controller()
        c.color = RED
        val other = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(40f, 40f, true), VAnchor(120f, 40f, true), VAnchor(80f, 110f, true)), closed = true)),
            polyline = true,
            fill = VPaint.Solid(YELLOW),
            stroke = VStrokeStyle(color = PURPLE, width = 4f),
        )
        val id = A6Paths.add(kit, c, other).single()
        c.selectTool(ToolId.POLYLINE)
        val line = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        line.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 6f, useBrushSize = false, fill = true, fillColor = null, closed = true) }
        for (p in listOf(Vec2(200f, 150f), Vec2(300f, 150f), Vec2(300f, 250f), Vec2(200f, 250f))) assertTrue(line.addAnchor(p))
        line.deselect()
        frame()
        val colors = requireNotNull(line.objectColors) { "a path is open" }
        colors.begin()
        colors.preview(ObjectColorTarget.STROKE, BLUE)
        colors.preview(ObjectColorTarget.FILL, GREEN)
        colors.commit()
        assertColor("the new path's line is the main color while it is open", BLUE, c.color)

        // The other path opens: the new one is placed first, in this one call.
        val steps = c.undoManager.undoCount
        assertTrue(line.openObject(c.vec.id, id, CurveKind.POLYLINE))
        assertTrue("the path opens", kit.idleUntil { line.isReopened && line.hasPendingWork })
        assertEquals("the new path's step", steps + 1, c.undoManager.undoCount)
        val placed = c.vec.vector!!.objects.last() as VPath
        assertColor("the placed path keeps its line", BLUE, placed.stroke!!.color)
        assertEquals("and its fill", VPaint.Solid(GREEN), placed.fill)
        frame()
        assertColor("the opened path's line color is the main color", PURPLE, c.color)

        // ✓ on the opened path (unchanged: no step): the user's red and options come back.
        line.commit()
        frame()
        assertColor("the user's main color", RED, c.color)
        assertNull("the user's fill (none of its own)", line.settings.fillColor)
        assertTrue("the user's fill is on", line.settings.fill)
        assertEquals("no step for the unchanged path", steps + 1, c.undoManager.undoCount)

        c.undo()
        kit.idle()
        assertEquals("undo takes the new path back", 1, c.vec.vector!!.objects.size)
        c.redo()
        kit.idle()
        assertEquals(placed, c.vec.vector!!.objects.last())
        assertColor("undo and redo leave the main color", RED, c.color)
    }

    /**
     * V2 review: the placed path gives its restore points back in the call that places it
     * (`CurveTool.endLook`), not later through the watch. Else they outlive it: the watch fires
     * only when the opened path closes, and then takes a main color the user picked meanwhile,
     * equal to the placed path's color, for its own and gives back the red.
     */
    @Test
    fun aColorPickedOnThePathOpenObjectOpensStaysWhenItCloses() {
        val c = kit.controller()
        c.color = RED
        val other = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(40f, 40f, true), VAnchor(120f, 40f, true), VAnchor(80f, 110f, true)), closed = true)),
            polyline = true,
            fill = VPaint.Solid(YELLOW),
            stroke = VStrokeStyle(color = PURPLE, width = 4f),
        )
        val id = A6Paths.add(kit, c, other).single()
        c.selectTool(ToolId.POLYLINE)
        val line = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        line.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 6f, useBrushSize = false, fill = true, fillColor = null, closed = true) }
        for (p in listOf(Vec2(200f, 150f), Vec2(300f, 150f), Vec2(300f, 250f), Vec2(200f, 250f))) assertTrue(line.addAnchor(p))
        line.deselect()
        frame()
        val colors = requireNotNull(line.objectColors) { "a path is open" }
        colors.begin()
        colors.preview(ObjectColorTarget.STROKE, BLUE)
        colors.commit()
        assertColor("the new path's line is the main color while it is open", BLUE, c.color)

        // The other path opens: the new one is placed first, in this one call.
        assertTrue(line.openObject(c.vec.id, id, CurveKind.POLYLINE))
        assertTrue("the path opens", kit.idleUntil { line.isReopened && line.hasPendingWork })
        assertColor("the placed path keeps its line", BLUE, (c.vec.vector!!.objects.last() as VPath).stroke!!.color)
        frame()
        assertColor("the opened path's line color is the main color", PURPLE, c.color)

        // The user picks blue (the placed path's color) as the main color: the opened path takes it.
        c.color = BLUE
        frame()
        val steps = c.undoManager.undoCount
        line.commit()
        frame()
        assertColor("the color the user picked meanwhile stays", BLUE, c.color)
        assertColor("the opened path took it", BLUE, (c.vec.vector!!.byId(id) as VPath).stroke!!.color)
        assertEquals("the opened path's step", steps + 1, c.undoManager.undoCount)
        c.undo()
        kit.idle()
        assertColor("undo gives the opened path its own color back", PURPLE, (c.vec.vector!!.byId(id) as VPath).stroke!!.color)
        assertColor("undo leaves the main color", BLUE, c.color)
        c.redo()
        kit.idle()
        assertColor("redo gives it the picked color again", BLUE, (c.vec.vector!!.byId(id) as VPath).stroke!!.color)
        assertColor("redo leaves the main color", BLUE, c.color)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val YELLOW = 0xFFF0C040.toInt()

        /** The opened object's own line color: not the new object's previewed BLUE, so the check tells them apart. */
        const val PURPLE = 0xFF8020C0.toInt()
    }
}
