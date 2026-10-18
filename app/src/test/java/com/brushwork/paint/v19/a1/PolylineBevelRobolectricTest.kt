package com.brushwork.paint.v19.a1

import com.brushwork.paint.AppSettings
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointBevel
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.v19.a1.A1Tools.steps
import com.brushwork.paint.v19.a1.A1Tools.withPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.hypot

/**
 * v1.9 R18 (design 1 R18 (a), (b), (e)): Polyline's "Bevel points", tool level (no composition:
 * the More-row item calls [CurveTool.bevelPoints], the popup [CurveTool.setBevelAmount],
 * [CurveTool.setBevelSegments] and [CurveTool.closeBevelPopup]). One bevel and every popup change
 * after it are ONE in-tool step; the new points are selected; a re-tap while they stay selected
 * reopens the popup instead of bevelling again; the canvas tap that closes the popup adds no
 * point; only sharp corners bevel; the 2000-point cap; the remembered values.
 */
@RunWith(RobolectricTestRunner::class)
class PolylineBevelRobolectricTest {

    /** A polyline of [A1Tools.FIVE] with [selected] selected. */
    private fun polyline(vararg selected: Int): CurveTool {
        val (_, t) = withPoints(ToolId.POLYLINE)
        t.selectPoints(PointSelection.of(t.pointCount, *selected))
        return t
    }

    private fun CurveTool.positions() = anchors.map { it.pos }

    @Test
    fun aBevelIsOneStepAndSelectsTheNewPoints() {
        val t = polyline(1, 3)
        val before = t.anchors
        val stepsBefore = steps(t)
        assertTrue(t.bevelPoints())
        // Segments 1 (the default): each corner becomes two points.
        assertEquals(7, t.pointCount)
        assertEquals("the new points are selected", listOf(1, 2, 4, 5), t.pointSelection.indices)
        assertTrue("the popup opens", t.bevelPopupShown)
        assertNotNull(t.bevelSession)
        val s = t.bevelSession!!
        assertEquals(AppSettings.DEFAULT_BEVEL_AMOUNT, s.amount, 0f)
        assertEquals(AppSettings.DEFAULT_BEVEL_SEGMENTS, s.segments)
        // The untouched points are the same anchors; the new ones lie on the two edges.
        assertSame(before[0], t.anchors[0])
        assertSame(before[2], t.anchors[3])
        assertSame(before[4], t.anchors[6])
        val cut = AppSettings.DEFAULT_BEVEL_AMOUNT * minOf(dist(before[0].pos, before[1].pos), dist(before[1].pos, before[2].pos))
        assertEquals(cut, dist(t.anchors[1].pos, before[1].pos), 1e-3f)
        assertEquals(cut, dist(t.anchors[2].pos, before[1].pos), 1e-3f)
        assertTrue("new polyline points are sharp", t.anchors.all { it.sharp })
        val bevelled = t.anchors
        // steps() walks undo and redo, which end the session: count last.
        assertEquals("one bevel, one step", stepsBefore + 1, steps(t))
        assertTrue(t.undoStep())
        assertEquals("one undo takes it back", before, t.anchors)
        assertEquals("with the selection then", listOf(1, 3), t.pointSelection.indices)
        assertFalse(t.bevelPopupShown)
        // One redo brings the bevel back exactly, its new points selected; the popup stays closed.
        assertTrue(t.redoStep())
        assertEquals("one redo", bevelled, t.anchors)
        assertEquals(listOf(1, 2, 4, 5), t.pointSelection.indices)
        assertNull("the session ended with the undo", t.bevelSession)
        assertFalse(t.bevelPopupShown)
    }

    @Test
    fun thePopupAdjustsTheSameStepFromThePreBevelCopy() {
        val t = polyline(2)
        val before = t.anchors
        // (steps() walks undo and redo, which keep only a selection of several points.)
        val stepsBefore = steps(t).also { t.selectPoints(PointSelection.of(t.pointCount, 2)) }
        assertTrue(t.bevelPoints())
        assertTrue(t.setBevelSegments(3))
        assertEquals("n + 1 points on the arc", 5 - 1 + 4, t.pointCount)
        assertEquals(listOf(2, 3, 4, 5), t.pointSelection.indices)
        assertTrue(t.setBevelAmount(0.4f))
        assertTrue(t.setBevelSegments(2))
        assertEquals(5 - 1 + 3, t.pointCount)
        val s = t.bevelSession!!
        assertEquals(0.4f, s.amount, 0f)
        assertEquals(2, s.segments)
        // Made again from the copy, not from the last result: the same as one bevel at these values.
        val (_, fresh) = withPoints(ToolId.POLYLINE)
        fresh.controller.settings.curveBevelAmount = 0.4f
        fresh.controller.settings.curveBevelSegments = 2
        fresh.selectPoints(PointSelection.of(fresh.pointCount, 2))
        assertTrue(fresh.bevelPoints())
        assertEquals(fresh.positions(), t.positions())
        // The values are remembered for the next bevel.
        assertEquals(0.4f, t.controller.settings.curveBevelAmount, 0f)
        assertEquals(2, t.controller.settings.curveBevelSegments)
        assertEquals("every change stays in the bevel's step", stepsBefore + 1, steps(t))
        assertTrue(t.undoStep())
        assertEquals(before, t.anchors)
    }

    @Test
    fun aReTapReopensThePopupWhileTheNewPointsStaySelected() {
        val t = polyline(1)
        assertTrue(t.bevelPoints())
        t.setBevelSegments(2)
        val shown = t.anchors
        t.closeBevelPopup()
        assertFalse(t.bevelPopupShown)
        assertNotNull("the session outlives its popup", t.bevelSession)
        assertTrue(t.bevelPoints())
        assertTrue("reopened", t.bevelPopupShown)
        assertSame("not bevelled again", shown, t.anchors)
        assertEquals(2, t.bevelSession!!.segments)
        // Another selection: the next tap bevels the selected point anew, as a step of its own.
        t.closeBevelPopup()
        t.selectPoints(PointSelection.of(t.pointCount, t.pointCount - 2))
        assertNull(t.bevelSession)
        val k = t.pointCount - 2
        val stepsBefore = steps(t).also { t.selectPoints(PointSelection.of(t.pointCount, k)) }
        val n = t.pointCount
        assertTrue(t.bevelPoints())
        assertEquals("with the remembered 2 segments", n + 2, t.pointCount)
        assertEquals(stepsBefore + 1, steps(t))
    }

    @Test
    fun anotherEditEndsTheSession() {
        val t = polyline(1, 2)
        assertTrue(t.bevelPoints())
        val sel = t.pointSelection.indices
        t.setWidths(sel, FloatArray(sel.size) { 2f })
        t.endNumericEdit()
        assertNull("a thickness edit of the new points is the latest step now", t.bevelSession)
        assertFalse(t.bevelPopupShown)
        assertFalse(t.setBevelSegments(4))
        // ...so the item bevels those points (each a corner) again.
        val n = t.pointCount
        assertTrue(t.bevelPoints())
        assertEquals(n + sel.size, t.pointCount)
    }

    @Test
    fun theTapThatClosesThePopupAddsNoPoint() {
        val t = polyline(2)
        val c = t.controller
        assertTrue(t.bevelPoints())
        val n = t.pointCount
        val empty = Vec2(200f, 260f)
        c.pointerDown(ToolPoint(empty.x, empty.y))
        c.pointerUp(ToolPoint(empty.x, empty.y))
        assertEquals("the closing tap is consumed", n, t.pointCount)
        assertFalse(t.bevelPopupShown)
        assertNotNull(t.bevelSession)
        // A second finger (pan, zoom) passes it by: the popup stays.
        assertTrue(t.bevelPoints())
        t.onDown(ToolPoint(empty.x, empty.y))
        t.onCancel()
        assertTrue("pan and zoom pass by", t.bevelPopupShown)
        assertFalse("two fingers move the view", t.onTwoFingerStart(empty, empty, Vec2(empty.x + 40f, empty.y)))
        t.closeBevelPopup()
        // With the popup closed a tap adds a point again.
        c.pointerDown(ToolPoint(empty.x, empty.y))
        c.pointerUp(ToolPoint(empty.x, empty.y))
        assertEquals(n + 1, t.pointCount)
    }

    @Test
    fun onlySharpCornersBevel() {
        // The ends of an open line are not corners.
        val t = polyline(0, 4)
        val before = t.anchors
        t.controller.message = null
        assertFalse(t.bevelPoints())
        assertEquals(PointLabels19.BEVEL_SHARP_ONLY, t.controller.message)
        assertSame(before, t.anchors)
        assertNull(t.bevelSession)
        // A closed line: every point is one.
        t.setClosed(true)
        t.selectPoints(PointSelection.of(t.pointCount, 0))
        assertTrue(t.bevelPoints())
        assertEquals(6, t.pointCount)
        assertEquals(listOf(0, 1), t.pointSelection.indices)
        // Nothing selected: nothing to bevel.
        val (_, u) = withPoints(ToolId.POLYLINE)
        assertFalse(u.bevelPoints())
        assertEquals(5, u.pointCount)
    }

    @Test
    fun theWidthsAreInterpolated() {
        val t = polyline(2)
        t.setWidths(listOf(1, 2, 3), floatArrayOf(0.5f, 2f, 1f))
        t.endNumericEdit()
        assertTrue(t.bevelPoints())
        val w = t.anchors.map { it.width }
        assertEquals(0.5f, w[1], 0f)
        assertEquals(1f, w[4], 0f)
        assertTrue("between their edge's ends: $w", w[2] in 0.5f..2f && w[3] in 1f..2f)
    }

    @Test
    fun aBevelPastTheCapChangesNothing() {
        val pts = (0 until 400).map { i -> Vec2(10f + i * 0.9f, if (i % 2 == 0) 20f else 60f) }
        val (_, t) = withPoints(ToolId.POLYLINE, pts)
        t.controller.settings.curveBevelSegments = PointBevel.MAX_SEGMENTS
        t.toggleSelectAll()
        val before = t.anchors
        val stepsBefore = steps(t)
        t.controller.message = null
        assertFalse(t.bevelPoints())
        assertEquals(PointLabels19.BEVEL_TOO_MANY.format(PointBevel.MAX_POINTS_POLYLINE), t.controller.message)
        assertSame(before, t.anchors)
        assertEquals(stepsBefore, steps(t))
        // In the popup: more segments past the cap keep the values it had.
        t.controller.settings.curveBevelSegments = 1
        assertTrue(t.bevelPoints())
        val shown = t.anchors
        assertFalse(t.setBevelSegments(PointBevel.MAX_SEGMENTS))
        assertEquals(1, t.bevelSession!!.segments)
        assertSame(shown, t.anchors)
    }

    @Test
    fun onlyPolylineBevels() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val (_, t) = withPoints(id)
            t.selectPoints(PointSelection.of(t.pointCount, 2))
            assertFalse("$id", t.bevelPoints())
            assertEquals("$id", 5, t.pointCount)
        }
    }

    private fun dist(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)
}
