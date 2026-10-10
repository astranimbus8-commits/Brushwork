package com.brushwork.paint.qa17

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa17.PointsQa.Companion.steps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.vector.POINT_THICKNESS_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v1.7 final QA, item 1 ("select and edit more than one point at once ... move more than one
 * point at once, scale them apart, and also edit other properties like thickness") in Curve,
 * Polyline and Path on the user's phone (392 dp), with fingers: "Select several", three points
 * tapped, dragged together; scaled apart by the gizmo's corner and by a real two-finger pinch
 * inside its box; the pinch taken back by a two-finger tap and brought back by a three-finger
 * tap; "Mixed" thickness and "*1.5" typed (each point × 1.5, 300 % at most); "Delete selected
 * points" refused when fewer than 2 points would be left, then deleting three of five, taken
 * back by Undo; "Select all points". Each action is ONE step; the other points never move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.pointsgroupsandbox"])
class Qa17PointsGroupUiTest {

    @Test
    fun severalPointsMovedScaledThickenedAndDeletedInEachCurveTool() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        val qa = PointsQa(h)
        for ((label, noun) in listOf("Curve" to "curve", "Polyline" to "polyline", "Path" to "path")) {
            h.section("$label: several points") { qa.group(label, noun) }
        }
        dog.interrupt()
        h.finish()
    }

    private val five = listOf(Vec2(80f, 200f), Vec2(140f, 110f), Vec2(200f, 190f), Vec2(260f, 110f), Vec2(320f, 200f))

    private fun points(t: CurveTool): List<Vec2> = (0 until t.pointCount).map { t.pointAt(it) }

    private fun widths(t: CurveTool): List<Float> = if (t.isPath) t.spline!!.points.map { it.width } else t.anchors.map { it.width }

    private fun assertNear(what: String, a: Vec2, b: Vec2, tol: Float = 0.05f) =
        assertTrue("$what: $a vs $b", abs(a.x - b.x) <= tol && abs(a.y - b.y) <= tol)

    private fun PointsQa.group(label: String, noun: String) {
        editor()
        val tool = tool(label)
        for (p in five) tap(p.x, p.y)
        assertEquals("$label: five points by five taps", 5, tool.pointCount)
        val start = points(tool)

        // "Select several", three points tapped.
        press(PointLabels.SELECT_SEVERAL, 44f)
        assertTrue(tool.selectSeveral)
        assertTrue("the hint", has(PointLabels.SEVERAL_HINT, exact = true))
        for (i in 1..3) tap(start[i].x, start[i].y)
        assertEquals("three taps pick three points", listOf(1, 2, 3), tool.pointSelection.indices)
        assertTrue("the pill names them", has(CurveTool.SELECTED_POINTS_LABEL, exact = true))
        // The middle one is the lowest of a V: it sits ON the box's bottom handle, and a tap still
        // toggles it (the point wins the tie with the handle, as in the Shape tool).
        tap(start[2].x, start[2].y)
        assertEquals("$label: a tap on the point on the bottom handle takes it out", listOf(1, 3), tool.pointSelection.indices)
        tap(start[2].x, start[2].y)
        assertEquals("$label: and back in", listOf(1, 2, 3), tool.pointSelection.indices)

        // Dragged together by the middle one: one step, the others stay.
        val n0 = steps(tool)
        QaCurves.drag(s, start[2], Vec2(20f, 30f))
        val moved = points(tool)
        for (i in 0 until 5) assertNear("point $i of $start -> $moved, sel ${tool.pointSelection.indices}",if (i in 1..3) start[i] + Vec2(20f, 30f) else start[i], moved[i], 0.1f)
        assertEquals("$label: the group drag is one step", n0 + 1, steps(tool))

        // Scaled apart by the gizmo's SE corner (proportions kept): one step.
        val t = c.viewTransform
        val gizmo = PointGizmo()
        val l0 = requireNotNull(gizmo.layout((1..3).map { moved[it] }, t))
        val se = t.screenToDoc(l0.cornersScreen[2])
        QaCurves.drag(s, se, Vec2(30f, 30f))
        val scaled = points(tool)
        fun spanX(ps: List<Vec2>) = (1..3).maxOf { ps[it].x } - (1..3).minOf { ps[it].x }
        fun spanY(ps: List<Vec2>) = (1..3).maxOf { ps[it].y } - (1..3).minOf { ps[it].y }
        assertTrue("$label: the corner scaled them apart: ${spanX(moved)} -> ${spanX(scaled)}", spanX(scaled) > spanX(moved) * 1.15f)
        assertEquals("proportions kept", spanX(scaled) / spanX(moved), spanY(scaled) / spanY(moved), 0.02f)
        for (i in listOf(0, 4)) assertNear("unselected point $i", start[i], scaled[i])
        assertEquals("$label: the gizmo scale is one step", n0 + 2, steps(tool))

        // A real pinch inside the box: two fingers 40 dp apart spread to 80 dp: ×2 about its centre.
        val l1 = requireNotNull(gizmo.layout((1..3).map { scaled[it] }, t))
        val pivot = l1.pivotDoc
        val (px, py) = s.screen(pivot.x, pivot.y)
        val d = 20f * s.density
        s.touch.idle(400)
        s.touch.pinch(px - d to py, px + d to py, px - 2 * d to py, px + 2 * d to py)
        settle(4)
        val pinched = points(tool)
        for (i in 1..3) {
            val r0 = (scaled[i] - pivot).let { sqrt(it.x * it.x + it.y * it.y) }
            val r1 = (pinched[i] - pivot).let { sqrt(it.x * it.x + it.y * it.y) }
            assertEquals("$label: point $i twice as far from the centre", r0 * 2f, r1, 0.5f + r0 * 0.02f)
        }
        for (i in listOf(0, 4)) assertNear("unselected point $i after the pinch", start[i], pinched[i])
        assertEquals("$label: the pinch is one step (its first finger left no step or move behind)", n0 + 3, steps(tool))

        // Two fingers tap: the pinch is taken back; three fingers: it comes back.
        ui.twoFingerUndo()
        for (i in 0 until 5) assertNear("$label: undone point $i", scaled[i], points(tool)[i])
        ui.threeFingerRedo()
        for (i in 0 until 5) assertNear("$label: redone point $i", pinched[i], points(tool)[i])
        assertEquals(n0 + 3, steps(tool))

        // "Select several" off; two points get their own thickness (one tapped at a time).
        press(PointLabels.SELECT_SEVERAL, 44f)
        assertFalse(tool.selectSeveral)
        val now = points(tool)
        for ((i, v) in listOf(2 to "50", 4 to "250")) {
            tap(now[i].x, now[i].y)
            assertEquals("$label: point $i alone", listOf(i), tool.pointSelection.indices)
            press("Type the point thickness")
            SmokeUi.typeAndDone("Thickness", v)
        }
        assertEquals(listOf(1f, 1f, 0.5f, 1f, 2.5f), widths(tool))

        // Every point: their thickness reads "Mixed"; "*1.5" makes each 1.5 × (300 % at most), one step.
        press(PointLabels.SELECT_ALL, 44f)
        assertEquals(5, tool.pointSelection.count)
        assertTrue("$label: \"Mixed\"", has(PointLabels.MIXED, exact = true))
        val n1 = steps(tool)
        press("Type $POINT_THICKNESS_LABEL")
        SmokeUi.typeAndDone(POINT_THICKNESS_LABEL, "*1.5")
        val w = widths(tool)
        val want = listOf(1.5f, 1.5f, 0.75f, 1.5f, 3f)
        for (i in 0 until 5) assertEquals("$label: point $i × 1.5: $w", want[i], w[i], 1e-4f)
        assertEquals("$label: one step for all five", n1 + 1, steps(tool))
        assertEquals("the selection stays", 5, tool.pointSelection.count)

        // The pill's trash: every point selected = the whole object; one less = the selected points.
        assertTrue(has(PillLabels.deleteObject(noun), exact = true))
        press(PointLabels.SELECT_SEVERAL, 44f)
        tap(now[0].x, now[0].y)
        assertEquals(listOf(1, 2, 3, 4), tool.pointSelection.indices)
        assertTrue("the trash says what it deletes", has(PillLabels.DELETE_POINTS, exact = true))
        val n2 = steps(tool)
        click(PillLabels.DELETE_POINTS, exact = true, settleAfter = false)
        val refusal = "A $noun needs at least 2 points"
        assertEquals("$label: refused: one point would be left", refusal, c.message)
        settle()
        assertEquals("nothing deleted", 5, tool.pointCount)
        assertEquals("no step", n2, steps(tool))
        tap(now[1].x, now[1].y)
        assertEquals(listOf(2, 3, 4), tool.pointSelection.indices)
        click(PillLabels.DELETE_POINTS, exact = true)
        assertEquals("$label: three of five deleted", 2, tool.pointCount)
        assertNear("the first point stays", now[0], tool.pointAt(0))
        assertNear("the second point stays", now[1], tool.pointAt(1))
        assertEquals("$label: one step", n2 + 1, steps(tool))
        // The top bar's Undo takes the deletion back as one step; Redo deletes them again.
        click("Undo", exact = true)
        assertEquals("$label: Undo brings the three back", 5, tool.pointCount)
        for (i in 0 until 5) assertNear("$label: point $i back", now[i], tool.pointAt(i))
        click("Redo", exact = true)
        assertEquals(2, tool.pointCount)
        click("Undo", exact = true)
        assertEquals(5, tool.pointCount)

        // "Select all points" selects every point; "Deselect all points" none.
        if (tool.selectSeveral) press(PointLabels.SELECT_SEVERAL, 44f)
        press(PointLabels.SELECT_ALL, 44f)
        assertEquals(5, tool.pointSelection.count)
        assertTrue(has(PointLabels.DESELECT_ALL, exact = true))
        press(PointLabels.DESELECT_ALL, 44f)
        assertTrue(tool.pointSelection.isEmpty)
        Smoke.assertQuiet(c, "several points in $label")
        tool.discard()
    }
}
