package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.tools.vector.toCurveAnchor
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.2c / I9 on the Path tool: its pending anchors come from the incremental conversion
 * ([SplineBezier.Incremental], only the spans an edit touched) and are exactly a fresh
 * [SplineBezier.toSubpath] of its spline after every kind of edit — a touch drag, numeric moves,
 * weights, widths, order, Cyclic, Endpoint, a delete, undo and redo — and ✓ stores the fresh form.
 */
@RunWith(RobolectricTestRunner::class)
class PathIncrementalAnchorsRobolectricTest {

    private fun assertFresh(tool: CurveTool, what: String) {
        val s = tool.spline ?: return assertEquals("$what: no spline, no anchors", emptyList<Any>(), tool.anchors)
        assertEquals("$what: the anchors are the spline's fresh Bézier form", SplineBezier.toSubpath(s).anchors.map { it.toCurveAnchor() }, tool.anchors)
    }

    @Test
    fun pendingAnchorsAreTheFreshConversionAfterEveryEdit() {
        val c = CurveToolTestSupport.controller()
        val tool = c.tool(ToolId.PATH)
        // A zigzag of 24 points across the canvas.
        for (i in 0 until 24) assertTrue(tool.addAnchor(Vec2(20f + i * 15f, if (i % 2 == 0) 60f else 220f)))
        assertFresh(tool, "added")
        tool.setOrder(5)
        assertFresh(tool, "order 5")
        tool.setWeight(10, 4f)
        assertFresh(tool, "weight")
        // A touch drag of point 12 (each move converts a few spans).
        tool.deselect()
        c.drag(200f to 60f, 204f to 70f, 211f to 85f, 219f to 101f)
        assertTrue("point 12 moved", SplineEditing.pos(tool.spline!!.points[12]).distanceTo(Vec2(200f, 60f)) > 20f)
        assertFresh(tool, "dragged")
        for (k in 0 until 6) {
            tool.moveAnchor(3, Vec2(65f + k * 2f, 220f - k * 3f))
            assertFresh(tool, "move $k")
        }
        tool.setWidth(7, 2.5f)
        assertFresh(tool, "width")
        tool.setCyclic(true)
        assertFresh(tool, "cyclic")
        tool.moveAnchor(0, Vec2(30f, 70f))
        assertFresh(tool, "the first point of a cyclic one")
        tool.setCyclic(false)
        tool.setEndpoint(false)
        assertFresh(tool, "no endpoint")
        tool.deleteAnchor(5)
        assertFresh(tool, "deleted")
        while (tool.undoStep()) assertFresh(tool, "undo")
        while (tool.redoStep()) assertFresh(tool, "redo")

        val sp = tool.spline!!
        tool.commit()
        val p = c.activeLayer.vector!!.objects.single() as VPath
        assertEquals("I9: ✓ stores the spline's Bézier form", listOf(SplineBezier.toSubpath(sp)), p.subpaths)
        assertTrue(SplineBezier.matches(p))
    }
}
