package com.brushwork.paint.tools.vector.spline

import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.H
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.W
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 review fixes (area B): a look that belongs to ONE pending path never becomes how the
 * tool's next path looks. The Capsule quick start (fill on, no line) and a path handed over by
 * To Bézier (or by undoing it) show their look while they are pending; ✓, ✕, undoing them away
 * or handing them on brings the tool's own settings back, and its saved preferences never
 * change. Also: a tap just beyond a control point (outside a corner) never doubles that point.
 */
@RunWith(RobolectricTestRunner::class)
class PathLookRobolectricTest {

    private val area = RectF(0f, 0f, W.toFloat(), H.toFloat())

    private fun EditorController.saved(key: String): CurveSettings? = settings.getObject(key, CurveSettings.serializer())

    @Test
    fun theCapsuleLookIsTheCapsulesOnly() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        tool.plainLine()
        val own = tool.settings
        assertEquals(own, c.saved("vec.path"))

        assertTrue(tool.startShape(CurveTool.PathShape.CAPSULE, area))
        assertTrue("the capsule shows its fill", tool.settings.fill)
        assertEquals(CurveStroke.NONE, tool.settings.stroke)
        assertEquals("nothing saved", own, c.saved("vec.path"))
        tool.commit()
        val capsule = c.activeLayer.vector!!.objects.single() as VPath
        assertNotNull(capsule.fill)
        assertNull(capsule.stroke)

        // The next path looks as the user set the tool up: a plain line, no fill, open, order 4.
        assertEquals(own, tool.settings)
        assertFalse(tool.pathCyclic)
        assertEquals(4, tool.pathOrder)
        for ((x, y) in listOf(60f to 200f, 160f to 80f, 260f to 220f)) c.tap(x, y)
        assertFalse("open", tool.spline!!.cyclic)
        tool.commit()
        val next = c.activeLayer.vector!!.objects.last() as VPath
        assertNotNull("a line", next.stroke)
        assertNull("no fill", next.fill)
        assertEquals(own, c.saved("vec.path"))
    }

    @Test
    fun changesDuringTheCapsuleAreTheCapsulesAndUndoRedoFollowIt() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        tool.plainLine()
        val own = tool.settings
        assertTrue(tool.startShape(CurveTool.PathShape.CAPSULE, area))
        // The strip edits the pending capsule's look; only the unit / nudge step are the user's.
        tool.update { it.copy(stroke = CurveStroke.BRUSH, nudgeStepPx = 5f) }
        assertEquals(CurveStroke.BRUSH, tool.settings.stroke)
        assertEquals(own.copy(nudgeStepPx = 5f), c.saved("vec.path"))
        tool.update { it.copy(stroke = CurveStroke.NONE) }

        // Undone away (in-tool undo): the user's look is back at once.
        assertTrue(tool.undoStep())
        assertFalse(tool.hasPendingWork)
        assertEquals(own.copy(nudgeStepPx = 5f), tool.settings)
        // The strip's Redo brings the capsule back with its look.
        assertTrue(tool.redoStep())
        assertEquals(12, tool.spline!!.points.size)
        assertTrue(tool.settings.fill)
        assertEquals(CurveStroke.NONE, tool.settings.stroke)
        // ✕: the user's look again.
        tool.discard()
        assertEquals(own.copy(nudgeStepPx = 5f), tool.settings)
        assertEquals(own.copy(nudgeStepPx = 5f), c.saved("vec.path"))

        // A Circle has no look of its own: the user's line, and the next path is open again.
        assertTrue(tool.startShape(CurveTool.PathShape.CIRCLE, area))
        assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
        assertTrue(tool.pathCyclic)
        tool.commit()
        assertFalse(tool.pathCyclic)
        assertNotNull((c.activeLayer.vector!!.objects.single() as VPath).stroke)
    }

    @Test
    fun toBezierNeverChangesTheCurveToolsSettings() {
        val c = controller()
        val curve = c.tool(ToolId.CURVE)
        val curveOwn = curve.settings
        val path = c.tool(ToolId.PATH)
        path.plainLine()
        val pathOwn = path.settings
        assertTrue(path.startShape(CurveTool.PathShape.CAPSULE, area))
        assertTrue(path.toBezier())
        assertEquals(ToolId.CURVE, c.activeToolId)
        // The Path tool let go of the capsule and of its look.
        assertEquals(pathOwn, path.settings)
        // The Curve tool shows the capsule's look (closed, filled, no line) without saving it.
        assertTrue(curve.settings.closed)
        assertTrue(curve.settings.fill)
        assertEquals(CurveStroke.NONE, curve.settings.stroke)
        assertNull("nothing saved for the Curve tool", c.saved("vec.curve"))

        // Undo hands it back to Path with its look; Redo converts again.
        c.undo()
        assertEquals(ToolId.PATH, c.activeToolId)
        assertEquals(curveOwn, curve.settings)
        assertTrue(path.settings.fill)
        assertEquals(CurveStroke.NONE, path.settings.stroke)
        assertEquals(pathOwn, c.saved("vec.path"))
        c.redo()
        assertEquals(ToolId.CURVE, c.activeToolId)
        assertEquals(pathOwn, path.settings)
        assertTrue(curve.settings.fill)

        // ✓ in the Curve tool: the filled capsule; then the Curve tool's own look again.
        curve.commit()
        val obj = c.activeLayer.vector!!.objects.single() as VPath
        assertNotNull(obj.fill)
        assertNull(obj.stroke)
        assertNull("a plain Bézier path", obj.spline)
        assertEquals(curveOwn, curve.settings)
        assertNull(c.saved("vec.curve"))
        // Its next curve is open and stroked, as before.
        c.tap(50f, 50f)
        c.tap(150f, 80f)
        c.tap(250f, 40f)
        assertFalse(curve.settings.closed)
        assertEquals(curveOwn.stroke, curve.settings.stroke)
    }

    @Test
    fun aTapJustBeyondAPointNeverDoublesIt() {
        val c = controller()
        // Handle size 75 %: the points' grab radius (18 dp) is smaller than the polygon's insert
        // distance (24 dp).
        c.settings.curveHandleScale = 0.75f
        val tool = c.tool(ToolId.PATH)
        c.tap(100f, 150f)
        c.tap(300f, 150f)
        // 20 px past the last point, on the polygon's line: outside its grab radius, and the
        // nearest place on the polygon is the point itself. A new point at the end, not a copy.
        c.tap(320f, 150f)
        assertEquals(listOf(100f, 300f, 320f), tool.spline!!.points.map { it.x })
        // The polygon turns up at (320, 150); a tap 21 px outside that corner (past both of its
        // segments' ends) adds a point at the end too.
        c.tap(320f, 60f)
        c.tap(335f, 165f)
        val pts = tool.spline!!.points
        assertEquals(5, pts.size)
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            assertTrue("points $i and $j coincide", SplineEditing.pos(pts[i]).distanceTo(SplineEditing.pos(pts[j])) > 1f)
        }
        assertEquals("appended at the end", 335f, pts.last().x, 0f)
        assertEquals(165f, pts.last().y, 0f)
        // Between two points a tap near the polygon still inserts there.
        c.tap(200f, 160f)
        assertEquals(6, tool.spline!!.points.size)
        assertEquals(200f, tool.spline!!.points[1].x, 1e-3f)
        assertEquals(150f, tool.spline!!.points[1].y, 1e-3f)
    }
}
