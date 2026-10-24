package com.brushwork.paint.v110.a5

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_BR
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_TL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R12, UQ9): the pill's Sharp / Smooth cell. With some selected points sharp and some
 * smooth its state is Mixed, and a tap makes every selected point sharp ("Mixed means Sharp", in
 * every tool: Shape's v1.9 Mixed rule, which made them smooth, is aligned to it). All smooth: a tap
 * makes them sharp; all sharp: smooth. No selected point: no cell.
 */
@RunWith(RobolectricTestRunner::class)
class SharpMixedMakesSharpTest {
    private val fx = GroupSnapFixtures()

    @After
    fun tearDown() = fx.release()

    private fun sharpOf(tool: CurveTool, i: Int): Boolean =
        if (tool.isPath) tool.spline!!.points[i].sharp else tool.anchors[i].sharp

    @Test
    fun curveAndPathPointsMixedBecomeSharp() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val (_, tool) = fx.curve(id, FIVE)
            val e = requireNotNull(tool.pointCorners) { "$id has a corner editor" }
            assertNull("$id: no point selected, no cell", e.corner)
            tool.selectPoints(PointSelection.of(5, 1, 2, 3))
            assertEquals("$id", CornerState.SMOOTH, e.corner)
            tool.setSharpPoints(listOf(2), true)
            assertEquals("$id", CornerState.MIXED, e.corner)
            assertTrue("$id: the tap changes points", e.setSelectedSharp(true))
            assertEquals("$id", listOf(true, true, true), listOf(1, 2, 3).map { sharpOf(tool, it) })
            assertEquals("$id", CornerState.SHARP, e.corner)
            // All sharp: the tap asks for smooth.
            assertTrue(e.setSelectedSharp(false))
            assertEquals("$id", listOf(false, false, false), listOf(1, 2, 3).map { sharpOf(tool, it) })
            assertEquals("$id", CornerState.SMOOTH, e.corner)
            tool.discard()
        }
    }

    @Test
    fun shapePointsMixedBecomeSharp() {
        val (_, tool) = fx.shapeGroup()
        val e = tool.pointCorners!!
        val tl = fx.shapeIndex(tool, SHAPE_TL)
        val br = fx.shapeIndex(tool, SHAPE_BR)
        // The rectangle's corners are sharp.
        assertEquals(CornerState.SHARP, e.corner)
        tool.setPointSmooth(br, true)
        assertEquals(CornerState.MIXED, e.corner)
        assertTrue(e.setSelectedSharp(true))
        assertEquals(listOf(false, false), listOf(tl, br).map { tool.docAnchors()!![it].smooth })
        assertEquals(CornerState.SHARP, e.corner)
        // Outside Points mode there is no cell.
        tool.setPointEditing(false)
        assertNull(e.corner)
    }

    companion object {
        /** Five smooth points of an open path (the Path's ends, 0 and 4, are always sharp). */
        val FIVE = listOf(Vec2(60f, 150f), Vec2(130f, 80f), Vec2(200f, 200f), Vec2(270f, 90f), Vec2(340f, 160f))
    }
}
