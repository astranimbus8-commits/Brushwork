package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.flattened
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.pixels
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.render
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.2a "To Bézier" (V7): the pending path becomes a Curve-tool path whose interior anchors
 * are smooth with both handles set, so the Curve tool draws exactly the same curve and every
 * handle can be grabbed and dragged; it is one in-tool step there (undo hands the spline back to
 * the Path tool as it was, redo converts again). A reopened spline path converted this way is
 * stored without its spline on ✓ (toast), and ✕ restores it.
 */
@RunWith(RobolectricTestRunner::class)
class ToBezierHandlesGrabbableTest {

    private val seven = listOf(40f to 220f, 90f to 60f, 150f to 200f, 210f to 50f, 260f to 230f, 320f to 70f, 370f to 210f)

    @Test
    fun theHandlesOfTheConvertedPathCanBeGrabbedAndUndoHandsTheSplineBack() {
        val c = controller()
        val path = c.tool(ToolId.PATH)
        path.plainLine()
        for ((x, y) in seven) c.tap(x, y)
        val spline = path.spline!!
        val derived = path.anchors
        val curvePts = flattened(path)
        assertEquals("order 4, 7 points: 4 spans", 5, derived.size)

        assertTrue(path.toBezier())
        assertEquals(ToolId.CURVE, c.activeToolId)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        assertFalse("the Path tool let go of it", path.hasPendingWork)
        assertNull(path.spline)
        assertEquals(derived, curve.anchors)
        assertTrue("one in-tool step", curve.canUndoStep)
        // Every anchor is smooth with both handles set: drawn exactly as the spline.
        assertTrue(curve.anchors.all { !it.sharp && it.hasCustomTangent })
        val after = flattened(curve)
        assertEquals(curvePts.size, after.size)
        for (i in curvePts.indices) assertTrue(curvePts[i].distanceTo(after[i]) < 1e-3f)
        assertEquals("the look came along", com.brushwork.paint.tools.vector.CurveStroke.PLAIN, curve.settings.stroke)

        // Select the interior anchor 2 and drag its out handle.
        val a2 = curve.anchors[2].pos
        c.tap(a2)
        assertEquals(2, curve.selected)
        val (hIn, hOut) = curve.handlesOf(2)
        val end = a2 + hOut
        assertTrue("the handle end is out of the anchor's reach", hOut.length > 4f)
        c.drag(end.x to end.y, end.x to end.y + 10f, end.x to end.y + 30f)
        val (nIn, nOut) = curve.handlesOf(2)
        assertEquals(hOut.x, nOut.x, 1e-3f)
        assertEquals(hOut.y + 30f, nOut.y, 1e-3f)
        // Smooth: the other handle stays collinear and keeps its length.
        assertEquals(hIn.length, nIn.length, 1e-3f)
        assertEquals(0f, nIn.normalized().cross(nOut.normalized()), 1e-4f)

        // Undo: the handle drag, then the conversion itself (back to Path, exactly as it was).
        c.undo()
        assertEquals(derived, curve.anchors)
        c.undo()
        assertEquals(ToolId.PATH, c.activeToolId)
        assertFalse(curve.hasPendingWork)
        assertEquals(spline, path.spline)
        assertEquals(derived, path.anchors)
        // The Path tool's own history is intact: the next undo takes back its last point.
        assertTrue(path.canUndoStep)
        // Redo converts again.
        c.redo()
        assertEquals(ToolId.CURVE, c.activeToolId)
        assertEquals(derived, curve.anchors)
        c.undo()
        assertEquals(ToolId.PATH, c.activeToolId)
        c.undo()
        assertEquals(6, path.spline!!.points.size)
        assertEquals("nothing reached the document", 0, c.undoManager.undoCount)
    }

    @Test
    fun aReopenedSplinePathEditedAsBezierDropsItsSplineAndCancelRestoresIt() {
        val c = controller()
        val layer = c.activeLayer
        val path = c.tool(ToolId.PATH)
        path.plainLine()
        for ((x, y) in seven) c.tap(x, y)
        path.commit()
        val original = layer.vector!!
        val before = pixels(layer.bitmap)
        val p = original.objects.single() as VPath
        val q = onLine(p)
        // ✕ in the Curve tool after To Bézier: the path is exactly as it was.
        c.tap(q)
        assertTrue(path.isReopened)
        assertTrue(path.toBezier())
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        assertTrue(curve.isReopened)
        assertFalse(path.isReopened)
        assertTrue("a converted path is the user's change", curve.hasUserChanges)
        curve.discard()
        assertSame(original, layer.vector)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("the user's color is back", CurveToolTestSupport.INK, c.color)

        // ✓ after a handle drag: one "Edit path" step, the same object without its spline, a toast.
        c.selectTool(ToolId.PATH)
        c.tap(q)
        assertTrue(path.isReopened)
        assertTrue(path.toBezier())
        val a = curve.anchors[2].pos
        c.tap(a)
        val end = a + curve.handlesOf(2).second
        c.drag(end.x to end.y, end.x + 10f to end.y, end.x + 25f to end.y)
        curve.commit()
        assertEquals(2, c.undoManager.undoCount)
        assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
        val edited = layer.vector!!.objects.single() as VPath
        assertEquals(p.id, edited.id)
        assertNull("a plain Bézier path now", edited.spline)
        assertEquals(CurveTool.EDITED_AS_BEZIER, c.message)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        // Tapped now, it opens in the Curve tool, not in Path.
        c.selectTool(ToolId.PATH)
        c.tap(onLine(edited))
        assertEquals(ToolId.CURVE, c.activeToolId)
        assertTrue(curve.isReopened)
        curve.discard()
        c.undo()
        assertSame(original, layer.vector)
    }

    @Test
    fun nothingToConvert() {
        val c = controller()
        val path = c.tool(ToolId.PATH)
        assertFalse(path.toBezier())
        c.tap(50f, 50f)
        assertFalse("one point is no curve yet", path.toBezier())
        assertEquals(ToolId.PATH, c.activeToolId)
        val curve = c.tool(ToolId.CURVE)
        c.tap(50f, 50f)
        c.tap(150f, 80f)
        assertFalse("only the Path tool converts", curve.toBezier())
        assertEquals(Vec2(50f, 50f), curve.anchors[0].pos)
    }
}
