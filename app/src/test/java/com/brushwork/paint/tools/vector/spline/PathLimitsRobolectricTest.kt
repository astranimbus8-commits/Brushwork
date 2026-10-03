package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.INK
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 review fix (area B): a path at [VSpline.MAX_POINTS] control points takes no more, and
 * says so (a tap that adds nothing used to do so silently).
 */
@RunWith(RobolectricTestRunner::class)
class PathLimitsRobolectricTest {

    @Test
    fun aFullPathSaysSo() {
        val c = controller()
        val layer = c.activeLayer
        // A zigzag of MAX_POINTS points (order 2: the polygon itself), stored as a Path would.
        val pts = List(VSpline.MAX_POINTS) { i -> VSplinePoint(20f + (i % 100) * 3.6f, 20f + (i / 100) * 13f) }
        val sp = VSpline(pts, order = 2)
        val vp = VPath(id = 0, subpaths = listOf(SplineBezier.toSubpath(sp)), stroke = VStrokeStyle(color = INK, width = 2f), spline = sp)
        assertTrue(SplineBezier.matches(vp))
        c.vectors.addObjects(layer, listOf(vp), "Import")
        val tool = c.tool(ToolId.PATH)
        c.tap(onLine(layer.vector!!.objects.single() as VPath))
        assertTrue(tool.isReopened)
        assertEquals(VSpline.MAX_POINTS, tool.pointCount)

        // A tap far from the path would append a point: it is full.
        c.tap(395f, 295f)
        assertEquals(VSpline.MAX_POINTS, tool.pointCount)
        assertEquals(CurveTool.TOO_MANY_POINTS, c.message)
        assertFalse(tool.hasUserChanges)
        // Numbers › Add point too.
        assertFalse(tool.addAnchor(Vec2(5f, 5f)))
        assertEquals(VSpline.MAX_POINTS, tool.pointCount)
        tool.discard()
    }
}
