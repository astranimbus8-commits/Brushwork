package com.brushwork.paint.v110.a6

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VShape
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
 * v1.10 A6 (R11, review): a closed path with a fill and no line, turned into a shape, opens in the
 * Shape tool with the fill's color as the main color (the Shape tool shows a shape's line color
 * there), never a transparent one, so a color picked while it is open recolors the fill, as for a
 * fill the Shape tool drew. ✓ without a change records nothing and gives the user's color back;
 * a picked color is ONE step "Edit shape"; two Undos bring the path back exactly.
 */
@RunWith(RobolectricTestRunner::class)
class TurnIntoShapeFillOnlyColorTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    @Test
    fun theFillsColorIsTheMainColorWhileOpen() {
        val c = kit.controller()
        c.color = BLUE
        val id = A6Paths.add(kit, c, A6Paths.curve(closed = true).copy(fill = VPaint.Solid(RED), stroke = null)).single()
        val stored = c.vec.vector!!.byId(id)
        A6Paths.select(c, id)
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.turnIntoShape(c))
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(kit.idleUntil { tool.editingObject && !c.vectors.isRendering })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ObjectBarLabels110.TO_SHAPE, c.undoManager.undoLabel)
        val converted = c.vec.vector!!.byId(id) as VShape
        assertEquals(ShapeStyle.FILL, converted.shape.style)
        assertEquals("the main color is the fill's while the shape is open", RED, c.color)

        // ✓ without a change: no step, the same shape, the user's color back.
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertFalse(tool.editingObject)
        assertEquals("nothing changed, nothing recorded", steps + 1, c.undoManager.undoCount)
        assertEquals(converted, c.vec.vector!!.byId(id))
        assertEquals("the user's color is back", BLUE, c.color)

        // Opened again with Edit, a picked color recolors the fill: ONE step.
        A6Paths.select(c, id)
        assertTrue(ObjectActions.openInEditor(c))
        assertTrue(kit.idleUntil { tool.editingObject && !c.vectors.isRendering })
        assertEquals(RED, c.color)
        c.color = GREEN
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("one more step", steps + 2, c.undoManager.undoCount)
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        val recolored = (c.vec.vector!!.byId(id) as VShape).shape
        assertEquals("the fill took the picked color", GREEN, recolored.fillColor)
        assertEquals(ShapeStyle.FILL, recolored.style)

        // Two Undos: the path exactly as it was.
        c.undo()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals(converted, c.vec.vector!!.byId(id))
        c.undo()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("the path again", stored, c.vec.vector!!.byId(id))
        assertEquals(steps, c.undoManager.undoCount)
    }

    private companion object {
        const val RED = 0xFFE04020.toInt()
        const val BLUE = 0xFF203080.toInt()
        const val GREEN = 0xFF20A040.toInt()
    }
}
