package com.brushwork.paint.qa17

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa17.PointsQa.Companion.steps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v1.7 final QA, items 12 and 13 ("while in curve, polyline and path you are allowed to transform
 * (move and scale) the curves" and "a delete option ... next to the transform X Y UI") on the
 * user's phone (392 dp): with NO point selected the pill reads "Center" and its X / Y and Scale
 * row move and scale the whole Path and Polyline (thickness kept), each typed value one step
 * that the top bar's Undo takes back alone; after "Select all points" the gizmo moves the whole
 * path by a drag inside its box and turns it by its knob; the trash cell reads "Delete curve" /
 * "Delete path" with no point selected and deletes a pending curve as an in-tool step and a
 * reopened (applied) path as ONE app step, and Undo brings each back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.pointswholesandbox"])
class Qa17PointsWholeUiTest {

    @Test
    fun theWholeCurveIsMovedScaledAndDeletedFromThePill() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        val qa = PointsQa(h)
        h.section("Path: the pill and the gizmo on the whole path") { qa.whole("Path") }
        h.section("Polyline: the pill on the whole polyline") { qa.whole("Polyline") }
        h.section("Curve: the trash deletes a pending curve") { qa.trashPending() }
        h.section("Path: the trash deletes an applied path") { qa.trashReopened() }
        dog.interrupt()
        h.finish()
    }

    private val four = listOf(Vec2(80f, 200f), Vec2(160f, 100f), Vec2(240f, 200f), Vec2(320f, 100f))

    private fun points(t: CurveTool): List<Vec2> = (0 until t.pointCount).map { t.pointAt(it) }

    private fun widths(t: CurveTool): List<Float> = if (t.isPath) t.spline!!.points.map { it.width } else t.anchors.map { it.width }

    private fun assertNear(what: String, a: Vec2, b: Vec2, tol: Float = 0.05f) =
        assertTrue("$what: $a vs $b", abs(a.x - b.x) <= tol && abs(a.y - b.y) <= tol)

    private fun centre(ps: List<Vec2>) = Vec2((ps.minOf { it.x } + ps.maxOf { it.x }) / 2f, (ps.minOf { it.y } + ps.maxOf { it.y }) / 2f)

    private fun PointsQa.whole(label: String) {
        editor()
        val tool = tool(label)
        for (p in four) tap(p.x, p.y)
        assertEquals(4, tool.pointCount)
        // One point thinner (it must stay so: scaling never scales thickness).
        tap(four[1].x, four[1].y)
        assertEquals(listOf(1), tool.pointSelection.indices)
        press("Type the point thickness")
        SmokeUi.typeAndDone("Thickness", "50")
        press("Deselect point", 44f)
        assertTrue("$label: no point selected", tool.pointSelection.isEmpty)
        assertTrue("$label: the pill reads Center", has(CurveTool.CENTER_LABEL, exact = true))
        assertNotNull("the Scale row", s.tagged(V17Tags.PILL_SCALE_ROW))
        val start = points(tool)
        val w0 = widths(tool)
        val c0 = centre(start)
        assertNear("the pill shows the centre", c0, requireNotNull(tool.pillPosition.position))

        // X typed: every point moves; one step.
        val n = steps(tool)
        click("Type X")
        SmokeUi.typeAndDone("X", "${c0.x.roundToInt() + 30}")
        val dx = c0.x.roundToInt() + 30 - c0.x
        click("Type Y")
        SmokeUi.typeAndDone("Y", "${c0.y.roundToInt() - 20}")
        val dy = c0.y.roundToInt() - 20 - c0.y
        val moved = points(tool)
        for (i in start.indices) assertNear("$label: point $i moved with the others", start[i] + Vec2(dx, dy), moved[i])
        assertEquals("$label: one step each", n + 2, steps(tool))
        assertTrue("still none selected", tool.pointSelection.isEmpty)

        // Scale X typed (proportions kept): the whole path 1.5 × about its centre, thickness kept.
        val c1 = centre(moved)
        click("Type ${PillLabels.SCALE_X}")
        SmokeUi.typeAndDone(PillLabels.SCALE_X, "150")
        val scaled = points(tool)
        for (i in start.indices) assertNear("$label: point $i scaled about the centre", c1 + (moved[i] - c1) * 1.5f, scaled[i], 0.1f)
        assertEquals("$label: thickness is not scaled", w0, widths(tool))
        assertEquals("$label: one step", n + 3, steps(tool))

        // The top bar's Undo takes back the scale alone; Redo brings it back.
        click("Undo", exact = true)
        for (i in start.indices) assertNear("$label: undone point $i", moved[i], points(tool)[i])
        click("Redo", exact = true)
        for (i in start.indices) assertNear("$label: redone point $i", scaled[i], points(tool)[i])

        if (tool.isPath) {
            // "Select all points": the gizmo; a drag inside its box (not on a point) moves the path.
            press(PointLabels.SELECT_ALL, 44f)
            assertEquals(4, tool.pointSelection.count)
            val inside = centre(scaled)
            assertTrue("no point at the box centre", scaled.all { it.distanceTo(inside) > 40f })
            QaCurves.drag(s, inside, Vec2(-25f, 15f))
            val dragged = points(tool)
            for (i in start.indices) assertNear("the whole path moved: point $i", scaled[i] + Vec2(-25f, 15f), dragged[i], 0.1f)
            assertEquals(n + 4, steps(tool))
            // The knob turns it about the box centre: distances kept, one step.
            val t = c.viewTransform
            val l = requireNotNull(PointGizmo().layout(dragged, t))
            val pivot = l.pivotDoc
            QaCurves.drag(s, t.screenToDoc(l.rotateHandleScreen), Vec2(80f, 40f))
            val turned = points(tool)
            for (i in start.indices) {
                val r0 = (dragged[i] - pivot).let { sqrt(it.x * it.x + it.y * it.y) }
                val r1 = (turned[i] - pivot).let { sqrt(it.x * it.x + it.y * it.y) }
                assertEquals("point $i keeps its distance to the centre", r0, r1, 0.05f + r0 * 1e-4f)
            }
            assertTrue("turned", turned.zip(dragged).any { (a, b) -> abs(a.x - b.x) > 1f })
            assertEquals(n + 5, steps(tool))
            assertEquals("thickness kept", w0, widths(tool))
        }
        // ✓: one app step with what was shown.
        val before = c.undoManager.undoCount
        apply("Apply ${label.lowercase()}")
        assertEquals("$label: applied as one step", before + 1, c.undoManager.undoCount)
        assertTrue("$label: drawn", PointsQa.inked(PointsQa.composite(c), 0, 0, 400, 300) > 100)
        Smoke.assertQuiet(c, "whole $label")
    }

    private fun PointsQa.trashPending() {
        editor()
        val tool = tool("Curve")
        for (p in four) tap(p.x, p.y)
        assertTrue("taps leave no point selected", tool.pointSelection.isEmpty)
        val start = points(tool)
        val n = steps(tool)
        assertTrue("with no point selected the trash deletes the curve", has(PillLabels.deleteObject("curve"), exact = true))
        click(PillLabels.deleteObject("curve"), exact = true)
        assertEquals("the curve is gone", 0, tool.pointCount)
        assertNull("nothing left to delete: no trash cell", s.tagged(V17Tags.PILL_TRASH))
        assertEquals("nothing on the layer", 0, PointsQa.inked(PointsQa.composite(c), 0, 0, 400, 300))
        click("Undo", exact = true)
        assertEquals("Undo brings it back", start, points(tool))
        assertEquals("one in-tool step", n, steps(tool))
        Smoke.assertQuiet(c, "trash a pending curve")
        tool.discard()
    }

    private fun PointsQa.trashReopened() {
        editor(vector = true)
        val tool = tool("Path")
        tap(80f, 200f)
        tap(200f, 100f)
        tap(320f, 200f)
        apply("Apply path")
        val layer = c.activeLayer
        val made = layer.vector!!.objects.single() as VPath
        val steps0 = c.undoManager.undoCount
        // A tap on the path reopens it, nothing selected: the trash deletes the whole path.
        tap(200f, 150f)
        assertTrue("reopened", tool.isReopened)
        assertTrue(tool.pointSelection.isEmpty)
        assertTrue(has(PillLabels.deleteObject("path"), exact = true))
        click(PillLabels.deleteObject("path"), exact = true)
        assertTrue("the layer has no path", layer.vector!!.objects.isEmpty())
        assertFalse("the session ended", tool.hasPendingWork)
        assertEquals("one app step", steps0 + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DELETE_PATH, c.undoManager.undoLabel)
        assertEquals(0, PointsQa.inked(PointsQa.composite(c), 0, 0, 400, 300))
        click("Undo", exact = true)
        assertEquals("Undo brings the path back", made, layer.vector!!.objects.single())
        assertTrue("drawn again", PointsQa.inked(PointsQa.composite(c), 0, 0, 400, 300) > 100)
        Smoke.assertQuiet(c, "trash an applied path")
    }
}
