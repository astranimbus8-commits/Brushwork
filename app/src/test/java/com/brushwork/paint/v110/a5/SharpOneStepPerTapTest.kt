package com.brushwork.paint.v110.a5

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.v110.a5.SharpMixedMakesSharpTest.Companion.FIVE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R12): a tap on the Sharp / Smooth cell is ONE in-tool step however many points it
 * changes (one Undo gives every point back, one Redo applies it again), and a tap that changes
 * nothing makes no step.
 */
@RunWith(RobolectricTestRunner::class)
class SharpOneStepPerTapTest {
    private val fx = GroupSnapFixtures()

    @After
    fun tearDown() = fx.release()

    private fun sharps(tool: CurveTool): List<Boolean> =
        if (tool.isPath) tool.spline!!.points.map { it.sharp } else tool.anchors.map { it.sharp }

    @Test
    fun aCurveOrPathTapIsOneStep() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val (_, tool) = fx.curve(id, FIVE)
            val e = tool.pointCorners!!
            tool.selectPoints(PointSelection.all(5))
            val smooth = sharps(tool)
            assertTrue("$id", e.setSelectedSharp(true))
            val sharp = sharps(tool)
            assertEquals("$id: the inner points (every Curve point) became sharp", if (id == ToolId.PATH) listOf(false, true, true, true, false) else List(5) { true }, sharp)
            // All sharp already: a second tap for sharp changes nothing and makes no step.
            assertFalse("$id", e.setSelectedSharp(true))
            assertTrue("$id", tool.undoStep())
            assertEquals("$id: one Undo gives every point back", smooth, sharps(tool))
            assertTrue("$id", tool.redoStep())
            assertEquals("$id: one Redo applies the tap again", sharp, sharps(tool))
            tool.discard()
        }
    }

    @Test
    fun aShapeTapIsOneStep() {
        val (_, tool) = fx.shapeGroup()
        val e = tool.pointCorners!!
        tool.selectPoints(PointSelection.all(4))
        assertEquals(CornerState.SHARP, e.corner)
        fun smooth() = tool.docAnchors()!!.map { it.smooth }
        assertTrue(e.setSelectedSharp(false))
        assertEquals(List(4) { true }, smooth())
        assertFalse(e.setSelectedSharp(false))
        assertTrue(tool.undoStep())
        assertEquals("one Undo gives every point back", List(4) { false }, smooth())
        assertTrue(tool.redoStep())
        assertEquals(List(4) { true }, smooth())
    }
}
