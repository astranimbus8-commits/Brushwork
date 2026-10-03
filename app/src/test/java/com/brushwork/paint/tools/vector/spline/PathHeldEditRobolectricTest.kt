package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * I2 across paths: a held numeric edit (a slider that never reported its release, e.g. it left
 * the screen mid-drag) ends with its path, so the next path's edits are steps of their own.
 */
@RunWith(RobolectricTestRunner::class)
class PathHeldEditRobolectricTest {

    @Test
    fun aHeldEditEndsWithItsPath() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        var now = 100_000L
        tool.clock = { now }
        for ((x, y) in listOf(60f to 200f, 150f to 80f, 260f to 210f)) c.tap(x, y)
        tool.select(1)
        tool.beginNumericEdit()
        tool.setWeight(1, 3f)
        // (No endNumericEdit.)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)

        // The next path: two nudges of a point ten seconds apart are two steps.
        for ((x, y) in listOf(70f to 150f, 200f to 60f, 330f to 150f)) c.tap(x, y)
        tool.select(1)
        val start = SplineEditing.pos(tool.spline!!.points[1])
        val step = tool.settings.nudgeStepPx
        tool.nudge(1, 0)
        now += 10_000L
        tool.nudge(1, 0)
        assertEquals(start + Vec2(2 * step, 0f), SplineEditing.pos(tool.spline!!.points[1]))
        assertTrue(tool.undoStep())
        assertEquals("one nudge taken back", start + Vec2(step, 0f), SplineEditing.pos(tool.spline!!.points[1]))
        assertTrue(tool.undoStep())
        assertEquals(start, SplineEditing.pos(tool.spline!!.points[1]))
    }
}
