package com.brushwork.paint.v110.a6

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R10): the object bar's Edit ([ObjectActions.openInEditor]) opens the one selected
 * object in its own tool: a shape in the Shape tool, a path in the Curve, Polyline or Path tool
 * (`CurveTool.kindOf`). The object selection is cleared, the layer stays active, and the button
 * itself records no undo step.
 */
@RunWith(RobolectricTestRunner::class)
class EditOpensRightToolTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    /** Selects [o] alone, taps Edit and checks what every kind shares; its id. */
    private fun edit(c: EditorController, o: VObject): Long {
        val id = A6Paths.add(kit, c, o).single()
        A6Paths.select(c, id)
        assertNull(ObjectActions.editRefusal(c))
        val steps = c.undoManager.undoCount
        assertTrue("Edit opens it", ObjectActions.openInEditor(c))
        kit.idle()
        assertTrue("the object selection is cleared", c.vectors.selectedIds.isEmpty())
        assertSame("the layer stays active", c.vec, c.activeLayer)
        assertEquals("the button records no step", steps, c.undoManager.undoCount)
        return id
    }

    private fun assertOpensIn(c: EditorController, o: VPath, kind: CurveKind, toolId: ToolId) {
        assertEquals(kind, CurveTool.kindOf(o))
        edit(c, o)
        assertEquals(toolId, c.activeToolId)
        val tool = c.tools.getValue(toolId) as CurveTool
        assertTrue("$kind: the path is open", kit.idleUntil { tool.isReopened && tool.hasPendingWork })
    }

    @Test
    fun aShapeOpensInTheShapeTool() {
        val c = kit.controller()
        edit(c, kit.rectangle(200f, 150f, 120f, 80f))
        assertEquals(ToolId.SHAPE, c.activeToolId)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue("the shape is open", kit.idleUntil { tool.editingObject && tool.hasPendingWork })
    }

    @Test
    fun aCurveOpensInTheCurveTool() {
        assertOpensIn(kit.controller(), A6Paths.curve(), CurveKind.CURVE, ToolId.CURVE)
        // A closed one, and a box of sharp corners (a Curve-tool path too).
        assertOpensIn(kit.controller(), A6Paths.curve(closed = true), CurveKind.CURVE, ToolId.CURVE)
        assertOpensIn(kit.controller(), kit.box(60f, 40f, 160f, 140f), CurveKind.CURVE, ToolId.CURVE)
    }

    @Test
    fun aPolylineOpensInThePolylineTool() {
        assertOpensIn(kit.controller(), A6Paths.polyline(), CurveKind.POLYLINE, ToolId.POLYLINE)
        assertOpensIn(kit.controller(), A6Paths.polyline(closed = true), CurveKind.POLYLINE, ToolId.POLYLINE)
    }

    @Test
    fun aPathOpensInThePathTool() {
        assertOpensIn(kit.controller(), A6Paths.spline(), CurveKind.PATH, ToolId.PATH)
        assertOpensIn(kit.controller(), A6Paths.spline(cyclic = true), CurveKind.PATH, ToolId.PATH)
    }

    @Test
    fun fromAnotherToolWithWorkPendingThatWorkIsKeptFirst() {
        // A curve being drawn in the Curve tool is applied before a polyline opens.
        val c = kit.controller()
        val id = A6Paths.add(kit, c, A6Paths.polyline()).single()
        val drawn = A6Paths.add(kit, c, A6Paths.curve()).single()
        c.selectTool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.reopen(c.vec.vector!!.byId(drawn) as VPath)
        assertTrue(kit.idleUntil { curve.isReopened })
        curve.moveAnchor(0, com.brushwork.paint.core.Vec2(70f, 160f))
        A6Paths.select(c, id)
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.openInEditor(c))
        assertTrue(kit.idleUntil { c.activeToolId == ToolId.POLYLINE && !c.vectors.isRendering })
        assertEquals("the moved curve became one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ToolId.POLYLINE, c.activeToolId)
        val polyline = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        assertTrue(kit.idleUntil { polyline.isReopened })
    }
}
