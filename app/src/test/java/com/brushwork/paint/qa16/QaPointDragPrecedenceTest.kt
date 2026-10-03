package com.brushwork.paint.qa16

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 final QA (§3.4 "object guide, then grid, then increment, per axis") for Curve and Path
 * point drags: an axis an object guide or the grid placed keeps that place even when the finger
 * is exactly on the guide or grid line; any other axis moves by whole Length steps from where the
 * point was; increments off is exactly v1.5.
 *
 * (Before the fix a drag told "snapped" from "free" by comparing the snapped coordinate with the
 * finger's: a finger exactly on a grid line or a guide — at 100 % zoom, every whole pixel of a
 * whole-pixel grid — was stepped off it, e.g. to (185, 105) instead of the grid's (192, 96).)
 */
@RunWith(RobolectricTestRunner::class)
class QaPointDragPrecedenceTest {

    /** Grid-aligned points (16 px cells): taps land on them whether the grid snaps or not. */
    private val pts = listOf(Vec2(64f, 192f), Vec2(160f, 80f), Vec2(256f, 208f), Vec2(352f, 96f))

    private fun setUp(id: ToolId, step: Float? = 25f, grid: Boolean = false, objects: Boolean = false): Pair<EditorController, CurveTool> {
        val c = controller()
        if (step != null) c.increments.update { it.copy(enabled = true).with(IncrementKind.LENGTH, step) }
        if (grid) c.updateGrid(GridSettings(enabled = true, spacingPx = 16f, snap = true))
        val t = c.tool(id)
        for (p in pts) c.tap(p)
        c.snapping.enabled = objects
        return c to t
    }

    private fun CurveTool.pos(i: Int): Vec2 = if (isPath) spline!!.points[i].let { Vec2(it.x, it.y) } else anchors[i].pos

    private fun dragPoint1To(c: EditorController, to: Vec2) = c.drag(160f to 80f, 170f to 85f, to.x to to.y)

    @Test
    fun aFingerExactlyOnAGridLineKeepsTheGridPlace() {
        for (id in listOf(ToolId.PATH, ToolId.CURVE)) {
            val (c, t) = setUp(id, grid = true)
            assertEquals(4, t.pointCount)
            dragPoint1To(c, Vec2(192f, 96f))
            assertEquals("$id: the grid placed x", 192f, t.pos(1).x, 1e-3f)
            assertEquals("$id: the grid placed y", 96f, t.pos(1).y, 1e-3f)
        }
    }

    @Test
    fun aFingerExactlyOnAGuideKeepsTheGuidePlace() {
        for (id in listOf(ToolId.PATH, ToolId.CURVE)) {
            val (c, t) = setUp(id, objects = true)
            // x = 256: point 3's vertical line; y = 120: no line near → whole steps (+40 → +50).
            dragPoint1To(c, Vec2(256f, 120f))
            assertEquals("$id: the guide placed x", 256f, t.pos(1).x, 1e-3f)
            assertEquals("$id: y steps", 130f, t.pos(1).y, 1e-3f)
        }
    }

    @Test
    fun offTheLinesTheGridStillWinsAndElseTheStep() {
        for (id in listOf(ToolId.PATH, ToolId.CURVE)) {
            val (c, t) = setUp(id, grid = true)
            dragPoint1To(c, Vec2(187f, 93f))
            assertEquals("$id: grid", Vec2(192f, 96f), t.pos(1))
            val (c2, t2) = setUp(id)
            dragPoint1To(c2, Vec2(197f, 93f))
            assertEquals("$id: +37, +13 → +25, +25", Vec2(185f, 105f), t2.pos(1))
        }
    }

    @Test
    fun incrementsOffIsTheFingerExactly() {
        for (id in listOf(ToolId.PATH, ToolId.CURVE)) {
            val (c, t) = setUp(id, step = null)
            dragPoint1To(c, Vec2(187.25f, 93.5f))
            assertEquals("$id: v1.5", Vec2(187.25f, 93.5f), t.pos(1))
        }
    }
}
