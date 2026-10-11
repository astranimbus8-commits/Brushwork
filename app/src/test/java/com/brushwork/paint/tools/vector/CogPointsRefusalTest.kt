package com.brushwork.paint.tools.vector

import com.brushwork.paint.ui.common.CogLabels18
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.8): Points mode is refused while the cog has a hole (two contours; points
 * make one): "Set the hole size to 0 to edit points" ([CogLabels18.HOLE_REFUSAL]), nothing
 * changes and no step is kept. At hole 0 the cog turns into points; every other shape type is
 * never refused.
 */
@RunWith(RobolectricTestRunner::class)
class CogPointsRefusalTest {
    private val rig = CogRig()

    @After
    fun close() = rig.close()

    @Test
    fun aCogWithAHoleRefusesPointsUntilTheHoleIsZero() {
        val c = rig.controller()
        val tool = rig.cogTool(c, CogParams(teeth = 10, hole = 0.4f))
        rig.drawCog(c)
        val before = tool.pendingObject()!!
        val steps = c.undoManager.undoCount
        c.message = null

        tool.setPointEditing(true)
        assertEquals(CogLabels18.HOLE_REFUSAL, c.message)
        assertFalse(tool.pointsMode)
        assertNull(tool.points)
        assertFalse("no in-tool step", tool.canUndoStep)
        assertFalse(tool.selectSeveralAvailable)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(before, tool.pendingObject())
        // Still the cog with its hole: two contours.
        assertEquals(2, ShapeOutlines.outline(tool.pendingObject()!!).ops.count { it is PathOp.MoveTo })

        // Hole size 0: points are allowed.
        tool.update { it.copy(cogHole = 0f) }
        c.message = null
        tool.setPointEditing(true)
        assertNull(c.message)
        assertTrue(tool.pointsMode)
        assertNotNull(tool.points)
        assertTrue(tool.selectSeveralAvailable)
        // A hole set on a cog with its own points changes nothing: the points are its outline.
        val pointed = ShapeOutlines.outline(tool.pendingObject()!!)
        tool.update { it.copy(cogHole = 0.5f) }
        val o = tool.pendingObject()!!
        assertEquals(pointed.ops, ShapeOutlines.outline(o).ops)
        assertFalse(ShapeToSpline.isCompound(o))
        tool.discard()
    }

    @Test
    fun onlyACogWithAHoleIsRefused() {
        for (t in ShapeType.entries) {
            assertNull("$t", ShapePoints.refusal(t, OutlineParams()))
            assertNull("$t with a hole field", ShapePoints.refusal(t, OutlineParams(cog = CogParams(hole = 0.3f))).takeIf { t != ShapeType.COG })
        }
        assertEquals(CogLabels18.HOLE_REFUSAL, ShapePoints.refusal(ShapeType.COG, OutlineParams(cog = CogParams(hole = 0.01f))))
        assertNull(ShapePoints.refusal(ShapeType.COG, OutlineParams(cog = CogParams(hole = 0f))))
    }
}
