package com.brushwork.paint.v110.a5

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_POINTS
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_BR
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_TL
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.idle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R12, C10): while a group edit runs (a drag of the selected points or of their gizmo),
 * the Sharp / Smooth cell is locked: a tap changes nothing and makes no step. When the finger
 * lifts, the cell works again.
 */
@RunWith(RobolectricTestRunner::class)
class SharpIgnoredDuringGroupEditTest {
    private val fx = GroupSnapFixtures()

    @After
    fun tearDown() = fx.release()

    @Test
    fun aCurveTapDuringAGroupDragIsIgnored() {
        val (c, tool) = fx.curveGroup(ToolId.CURVE)
        c.snapping.enabled = false
        val e = tool.pointCorners!!
        assertFalse(e.cornerLocked)
        // A drag of point 0 (the group 0 and 2 moves), the finger still down.
        val p = CURVE_POINTS[0]
        c.pointerDown(ToolPoint(p.x, p.y))
        c.pointerMove(ToolPoint(p.x + 12f, p.y))
        c.pointerMove(ToolPoint(p.x + 20f, p.y))
        idle()
        assertTrue("the group edit runs", e.cornerLocked)
        assertFalse(e.setSelectedSharp(true))
        assertEquals(listOf(false, false), listOf(0, 2).map { tool.anchors[it].sharp })
        c.pointerUp(ToolPoint(p.x + 20f, p.y))
        idle()
        assertFalse(e.cornerLocked)
        assertTrue(e.setSelectedSharp(true))
        assertEquals(listOf(true, true), listOf(0, 2).map { tool.anchors[it].sharp })
        tool.discard()
    }

    @Test
    fun aShapeTapDuringAGroupEditIsIgnored() {
        val (_, tool) = fx.shapeGroup()
        val e = tool.pointCorners!!
        val tl = fx.shapeIndex(tool, SHAPE_TL)
        val br = fx.shapeIndex(tool, SHAPE_BR)
        tool.beginGroupEdit("Move")
        assertTrue(e.cornerLocked)
        assertFalse(e.setSelectedSharp(false))
        assertEquals(listOf(false, false), listOf(tl, br).map { tool.docAnchors()!![it].smooth })
        tool.endGroupEdit()
        assertFalse(e.cornerLocked)
        assertTrue(e.setSelectedSharp(false))
        assertEquals(listOf(true, true), listOf(tl, br).map { tool.docAnchors()!![it].smooth })
    }
}
