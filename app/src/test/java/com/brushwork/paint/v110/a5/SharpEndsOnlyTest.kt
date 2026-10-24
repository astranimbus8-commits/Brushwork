package com.brushwork.paint.v110.a5

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.v110.a5.SharpMixedMakesSharpTest.Companion.FIVE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R12): the ends of an open Path are always sharp (the curve reaches them). With only
 * ends selected the cell is greyed ("Ends are always sharp", [CornerState.ENDS_ONLY]) and a tap
 * changes nothing; with an inner point too, the inner points decide the state and a tap changes
 * them only.
 */
@RunWith(RobolectricTestRunner::class)
class SharpEndsOnlyTest {
    private val fx = GroupSnapFixtures()

    @After
    fun tearDown() = fx.release()

    @Test
    fun onlyTheEndsOfAnOpenPathSelectedIsEndsOnly() {
        val (_, tool) = fx.curve(ToolId.PATH, FIVE)
        val e = tool.pointCorners!!
        val before = tool.spline
        for (sel in listOf(PointSelection.of(5, 0), PointSelection.of(5, 4), PointSelection.of(5, 0, 4))) {
            tool.selectPoints(sel)
            assertEquals("$sel", CornerState.ENDS_ONLY, e.corner)
            assertFalse(e.setSelectedSharp(true))
            assertFalse(e.setSelectedSharp(false))
        }
        assertTrue("nothing changed", tool.spline === before)
        tool.discard()
    }

    @Test
    fun withAnInnerPointTheInnerPointsDecide() {
        val (_, tool) = fx.curve(ToolId.PATH, FIVE)
        val e = tool.pointCorners!!
        tool.selectPoints(PointSelection.of(5, 0, 2, 4))
        assertEquals(CornerState.SMOOTH, e.corner)
        assertTrue(e.setSelectedSharp(true))
        assertEquals(listOf(false, false, true, false, false), tool.spline!!.points.map { it.sharp })
        assertEquals(CornerState.SHARP, e.corner)
        tool.discard()
    }

    @Test
    fun aCurvesEndsCanBeSharp() {
        // Only the Path keeps its ends: a Curve anchor at an end is a corner like any other.
        val (_, tool) = fx.curve(ToolId.CURVE, FIVE)
        val e = tool.pointCorners!!
        tool.selectPoints(PointSelection.of(5, 0, 4))
        assertEquals(CornerState.SMOOTH, e.corner)
        assertTrue(e.setSelectedSharp(true))
        assertEquals(listOf(true, false, false, false, true), tool.anchors.map { it.sharp })
        tool.discard()
    }
}
