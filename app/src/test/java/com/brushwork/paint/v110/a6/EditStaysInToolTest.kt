package com.brushwork.paint.v110.a6

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R10, UQ10): after Edit, ✓ or ✕ ends the edit in that tool and the user stays in it,
 * as after a tool tap in v1.9. ✓ of a change is the editor's own one step (same id and place);
 * ✕ records none.
 */
@RunWith(RobolectricTestRunner::class)
class EditStaysInToolTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    @Test
    fun afterTickOfAPathTheCurveToolStays() {
        val c = kit.controller()
        val ids = A6Paths.add(kit, c, kit.rectangle(320f, 220f, 60f, 40f), A6Paths.curve())
        val id = ids[1]
        A6Paths.select(c, id)
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.openInEditor(c))
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        assertTrue(kit.idleUntil { tool.isReopened })
        tool.moveAnchor(0, Vec2(50f, 140f))
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("the user stays in the Curve tool", ToolId.CURVE, c.activeToolId)
        assertFalse(tool.isReopened)
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        val objects = c.vec.vector!!.objects
        assertEquals("same id and place", listOf(ids[0], id), objects.map { it.id })
        assertEquals(50f, (objects[1] as VPath).subpaths[0].anchors[0].x, 1e-3f)
    }

    @Test
    fun afterCrossOfAPathThePolylineToolStays() {
        val c = kit.controller()
        val id = A6Paths.add(kit, c, A6Paths.polyline()).single()
        A6Paths.select(c, id)
        val steps = c.undoManager.undoCount
        val content = c.vec.vector
        assertTrue(ObjectActions.openInEditor(c))
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        assertTrue(kit.idleUntil { tool.isReopened })
        tool.moveAnchor(0, Vec2(200f, 50f))
        tool.discard()
        kit.idle()
        assertEquals("the user stays in the Polyline tool", ToolId.POLYLINE, c.activeToolId)
        assertEquals("✕ records nothing", steps, c.undoManager.undoCount)
        assertEquals(content, c.vec.vector)
    }

    @Test
    fun afterTickOrCrossOfAShapeTheShapeToolStays() {
        val c = kit.controller()
        val id = A6Paths.add(kit, c, kit.rectangle(200f, 150f, 120f, 80f)).single()
        val steps = c.undoManager.undoCount
        A6Paths.select(c, id)
        assertTrue(ObjectActions.openInEditor(c))
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(kit.idleUntil { tool.editingObject })
        tool.discard()
        kit.idle()
        assertEquals("✕: the user stays in the Shape tool", ToolId.SHAPE, c.activeToolId)
        assertFalse(tool.editingObject)
        assertEquals(steps, c.undoManager.undoCount)

        A6Paths.select(c, id)
        assertTrue(ObjectActions.openInEditor(c))
        assertTrue(kit.idleUntil { tool.editingObject })
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("✓: the user stays in the Shape tool", ToolId.SHAPE, c.activeToolId)
        assertFalse(tool.editingObject)
        assertEquals("the shape keeps its id", listOf(id), c.vec.vector!!.objects.map { it.id })
    }
}
