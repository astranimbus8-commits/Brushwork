package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.HandleSide
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.atan2

/**
 * v1.6 §3.3: scaling Bézier handles in the Curve tool. One in-tool step per slider drag, held
 * arrow, typed value and pinch; the value is relative to the lengths when the change began and
 * back at 100 % at rest; In and out / In / Out and All points; a pinch on the selected point (or its
 * handle ends) scales its handles, rotation ignored, anywhere else it is the view's; with
 * increments on the steps are 110 % and 120 %. "Handle size" scales the grab radius.
 */
@RunWith(RobolectricTestRunner::class)
class HandleScaleRobolectricTest {

    private fun curveWithFourPoints(c: com.brushwork.paint.EditorController): CurveTool {
        val t = c.tool(ToolId.CURVE)
        for ((x, y) in listOf(60f to 200f, 150f to 80f, 260f to 210f, 350f to 90f)) c.tap(x, y)
        return t
    }

    private fun angle(v: Vec2) = atan2(v.y.toDouble(), v.x.toDouble())

    @Test
    fun oneStepPerDragArrowRunOrTypedValueAndTheValueRestsAt100() {
        val c = controller()
        val t = curveWithFourPoints(c)
        c.tap(150f, 80f)
        assertEquals(1, t.selected)
        val (i0, o0) = t.handlesOf(1)
        val others = t.anchors.toList()
        // A slider drag: several values, one step, back to 100 % when it ends.
        t.beginHandleScale()
        t.scaleHandles(1.3f)
        assertEquals(1.3f, t.handleScale, 0f)
        t.scaleHandles(1.5f)
        t.endHandleScale()
        assertEquals(1f, t.handleScale, 0f)
        val (i1, o1) = t.handlesOf(1)
        assertEquals(i0.length * 1.5f, i1.length, 1e-3f)
        assertEquals(o0.length * 1.5f, o1.length, 1e-3f)
        assertEquals(angle(i0), angle(i1), 1e-5)
        assertEquals("the other points are untouched", others[0], t.anchors[0])
        assertTrue(t.undoStep())
        assertEquals("one step for the whole drag", others, t.anchors)
        // A typed value is exact and one step, relative to the lengths now.
        t.applyHandleScale(200f)
        assertEquals(o0.length * 2f, t.handlesOf(1).second.length, 1e-3f)
        t.applyHandleScale(50f)
        assertEquals(o0.length, t.handlesOf(1).second.length, 1e-3f)
        t.undoStep()
        assertEquals(o0.length * 2f, t.handlesOf(1).second.length, 1e-3f)
        t.undoStep()
        assertEquals(others, t.anchors)
        // ‹ › × 1.1 per press while held, one step for the run.
        t.stepHandleScale(up = true)
        t.stepHandleScale(up = true)
        assertEquals(1.21f, t.handleScale, 1e-4f)
        t.endHandleScale()
        assertEquals(o0.length * 1.21f, t.handlesOf(1).second.length, 1e-2f)
        t.undoStep()
        assertEquals(others, t.anchors)
    }

    @Test
    fun backAt100PercentTheHandlesAreExactlyAsTheyWere() {
        val c = controller()
        val t = curveWithFourPoints(c)
        c.tap(150f, 80f)
        val before = t.anchors
        assertFalse("automatic tangents", before[1].hasCustomTangent)
        // A slider drag that comes back to 100 %: the automatic tangents stay automatic (they
        // still follow the neighbours), and the drag records nothing.
        t.beginHandleScale()
        t.scaleHandles(1.4f)
        assertTrue(t.anchors[1].hasCustomTangent)
        t.scaleHandles(1f)
        t.endHandleScale()
        assertEquals(before, t.anchors)
        // (The next undo takes back the last point placed, not a drag that changed nothing.)
        assertTrue(t.undoStep())
        assertEquals(3, t.anchors.size)
        assertTrue(t.redoStep())
        assertEquals(before, t.anchors)
        // A typed 100 % changes nothing and is no step either.
        t.select(1)
        t.applyHandleScale(100f)
        assertEquals(before, t.anchors)
        assertTrue(t.undoStep())
        assertEquals(3, t.anchors.size)
        assertTrue(t.redoStep())
        // Moving a neighbour still turns the point's automatic tangent.
        val (_, o0) = t.handlesOf(1)
        c.drag(260f to 210f, 270f to 230f, 290f to 260f)
        assertTrue(t.handlesOf(1).second.distanceTo(o0) > 1f)
    }

    @Test
    fun inOutAndAllPoints() {
        val c = controller()
        val t = curveWithFourPoints(c)
        c.tap(150f, 80f)
        val (i0, o0) = t.handlesOf(1)
        t.handleSide = HandleSide.OUT
        t.applyHandleScale(300f)
        val (i1, o1) = t.handlesOf(1)
        assertEquals(i0.length, i1.length, 1e-3f)
        assertEquals(o0.length * 3f, o1.length, 1e-3f)
        assertEquals("never a direction", angle(o0), angle(o1), 1e-5)
        t.undoStep()
        t.handleSide = HandleSide.IN
        t.applyHandleScale(50f)
        assertEquals(i0.length * 0.5f, t.handlesOf(1).first.length, 1e-3f)
        assertEquals(o0.length, t.handlesOf(1).second.length, 1e-3f)
        t.undoStep()
        // All points: each anchor relative to its own handles.
        t.handleSide = HandleSide.BOTH
        t.handleAllPoints = true
        val before = t.anchors.indices.map { t.handlesOf(it) }
        t.applyHandleScale(50f)
        for (i in t.anchors.indices) {
            assertEquals(before[i].first.length * 0.5f, t.handlesOf(i).first.length, 1e-3f)
            assertEquals(before[i].second.length * 0.5f, t.handlesOf(i).second.length, 1e-3f)
        }
        t.undoStep()
        // No point selected: all points too.
        t.handleAllPoints = false
        t.deselect()
        t.applyHandleScale(200f)
        assertEquals(before[3].first.length * 2f, t.handlesOf(3).first.length, 1e-3f)
        // Polyline and Path have no handles to scale.
        assertFalse(c.tool(ToolId.POLYLINE).canScaleHandles)
        assertFalse(c.tool(ToolId.PATH).canScaleHandles)
    }

    @Test
    fun aPinchOnThePointScalesItsHandlesElsewhereTheView() {
        val c = controller()
        val t = curveWithFourPoints(c)
        c.tap(150f, 80f)
        val (i0, o0) = t.handlesOf(1)
        val before = t.anchors
        // One finger 20 px (dp, at zoom 1) from the point: the tool takes it; the rotation is ignored.
        assertTrue(c.twoFingerStart(Vec2(200f, 120f), Vec2(170f, 80f), Vec2(230f, 160f)))
        c.twoFingerGesture(Vec2(5f, 5f), 1.2f, 40f)
        c.twoFingerGesture(Vec2(9f, 3f), 1.5f, 70f)
        c.twoFingerEnd(false)
        val (i1, o1) = t.handlesOf(1)
        assertEquals(o0.length * 1.5f, o1.length, 1e-3f)
        assertEquals(angle(o0), angle(o1), 1e-5)
        assertEquals(angle(i0), angle(i1), 1e-5)
        assertEquals("the point didn't move", before[1].pos, t.anchors[1].pos)
        assertEquals(1f, t.handleScale, 0f)
        assertTrue(t.undoStep())
        assertEquals("one step per pinch", before, t.anchors)
        // On a handle's end: also the tool's.
        val end = t.anchors[1].pos + o0
        assertTrue(c.twoFingerStart(end, end + Vec2(10f, 0f), Vec2(390f, 290f)))
        c.twoFingerEnd(true)
        assertEquals("a cancelled pinch leaves nothing", before, t.anchors)
        // Far from the point and its handles: the view's (the tool refuses it).
        assertFalse(c.twoFingerStart(Vec2(300f, 250f), Vec2(320f, 280f), Vec2(380f, 260f)))
        // No point selected: always the view's.
        t.deselect()
        assertFalse(c.twoFingerStart(Vec2(150f, 80f), Vec2(150f, 80f), Vec2(200f, 100f)))
    }

    @Test
    fun incrementsGive110And120Percent() {
        val c = controller()
        val t = curveWithFourPoints(c)
        c.tap(150f, 80f)
        val o0 = t.handlesOf(1).second
        c.increments.update { it.copy(enabled = true) }
        t.stepHandleScale(up = true)
        assertEquals(1.1f, t.handleScale, 1e-5f)
        assertEquals("110 %", c.increments.readout)
        t.stepHandleScale(up = true)
        assertEquals(1.2f, t.handleScale, 1e-5f)
        t.endHandleScale()
        assertNull(c.increments.readout)
        assertEquals(o0.length * 1.2f, t.handlesOf(1).second.length, 1e-3f)
        t.undoStep()
        // A pinch lands on the steps too, relative to its start.
        assertTrue(c.twoFingerStart(Vec2(160f, 90f), Vec2(155f, 85f), Vec2(260f, 160f)))
        c.twoFingerGesture(Vec2.ZERO, 1.14f, 0f)
        assertEquals(1.1f, t.handleScale, 1e-5f)
        c.twoFingerGesture(Vec2.ZERO, 1.17f, 0f)
        assertEquals(1.2f, t.handleScale, 1e-5f)
        c.twoFingerEnd(false)
        assertEquals(o0.length * 1.2f, t.handlesOf(1).second.length, 1e-3f)
        // The slider's value lands on the steps; typed values are exact.
        assertEquals(1.3f, t.steppedHandleScale(1.27f), 1e-5f)
        t.applyHandleScale(137f)
        assertEquals(o0.length * 1.2f * 1.37f, t.handlesOf(1).second.length, 1e-2f)
        // Off: × 1.1 per press.
        c.increments.update { it.copy(enabled = false) }
        t.stepHandleScale(up = true)
        t.stepHandleScale(up = true)
        assertEquals(1.21f, t.handleScale, 1e-4f)
        t.endHandleScale()
        assertEquals(1.27f, t.steppedHandleScale(1.27f), 0f)
    }

    @Test
    fun handleSizeScalesTheGrabRadius() {
        val c = controller()
        val t = curveWithFourPoints(c)
        assertEquals(1f, t.handleSize, 0f)
        // 30 px from a point (zoom 1): beyond the 24 dp grab radius, so a new point is added.
        c.drag(150f to 110f, 150f to 111f)
        assertEquals(5, t.anchors.size)
        t.undoStep()
        t.setHandleSize(2f)
        assertEquals(2f, c.settings.curveHandleScale, 0f)
        c.drag(150f to 110f, 150f to 130f, 150f to 140f)
        assertEquals("grabbed (48 dp reach) and dragged", 4, t.anchors.size)
        assertEquals(Vec2(150f, 140f), t.anchors[1].pos)
        t.setHandleSize(9f)
        assertEquals("held to 200 %", 2f, t.handleSize, 0f)
        // Path control points too.
        t.discard()
        val p = c.tool(ToolId.PATH)
        c.tap(100f, 100f)
        c.tap(300f, 100f)
        c.drag(100f to 140f, 100f to 150f)
        assertEquals("40 px away: grabbed and moved", 2, p.spline!!.points.size)
        assertEquals(150f, p.spline!!.points[0].y, 0f)
    }
}
