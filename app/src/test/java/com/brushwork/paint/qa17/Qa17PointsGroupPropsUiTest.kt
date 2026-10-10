package com.brushwork.paint.qa17

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa17.PointsQa.Companion.composite
import com.brushwork.paint.qa17.PointsQa.Companion.inked
import com.brushwork.paint.qa17.PointsQa.Companion.save
import com.brushwork.paint.qa17.PointsQa.Companion.steps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.vector.POINT_WEIGHT_LABEL
import com.brushwork.paint.ui.vector.SHARP_MIXED
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.7 final QA (verifier), items 1 and 4 together on a narrower phone (360 dp), with fingers, in
 * the Path tool: "Select several", a box drawn on empty canvas picks the three middle points of
 * five (a tap on empty canvas drops them; no point is ever added meanwhile); the group dragged by
 * its lowest point (which sits on the gizmo's bottom handle); "Undo last point" / "Redo point"
 * bring the points AND the selection back (§3.1: in-tool undo restores the selection, checked
 * directly, not through the steps helper). Then the group's own properties: the three-state sharp
 * chip ("Sharp corner" on all three at once; "Mixed" when they differ, a tap makes all of them
 * corners; the ends are never touched), the group "Point weight" field ("Mixed", "*4" typed: each
 * × 4, 10 at most), and with every point selected the pill's trash deletes the whole pending path
 * as one in-tool step that Undo takes back with its selection. ✓ is one app step, keeps the
 * corners and weights (and the I9 Bézier form), and turns "Select several" off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.pointsgrouppropssandbox"])
class Qa17PointsGroupPropsUiTest {

    @Test
    fun aBoxPicksThreePointsWhoseCornersAndWeightsChangeTogether() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        val qa = PointsQa(h)
        h.section("Path at 360 dp: box-select, group undo, sharp chip, weights, trash") { qa.groupProps() }
        dog.interrupt()
        h.finish()
    }

    private val five = listOf(Vec2(60f, 150f), Vec2(130f, 80f), Vec2(200f, 220f), Vec2(270f, 80f), Vec2(340f, 150f))

    private fun points(t: CurveTool): List<Vec2> = (0 until t.pointCount).map { t.pointAt(it) }

    private fun assertNear(what: String, a: Vec2, b: Vec2, tol: Float = 0.1f) =
        assertTrue("$what: $a vs $b", abs(a.x - b.x) <= tol && abs(a.y - b.y) <= tol)

    private fun PointsQa.groupProps() {
        editor(widthDp = 360f, vector = true)
        c.brush = BrushLibrary.defaultBrush.copy(size = 6f, opacity = 1f)
        val tool = tool("Path")
        for (p in five) tap(p.x, p.y)
        assertEquals(5, tool.pointCount)
        val start = points(tool)

        // "Select several", then a box dragged on empty canvas around the three middle points.
        press(PointLabels.SELECT_SEVERAL, 44f)
        assertTrue(tool.selectSeveral)
        val n0 = steps(tool)
        QaCurves.drag(s, Vec2(100f, 30f), Vec2(200f, 220f))
        assertEquals("the box picked the three middle points", listOf(1, 2, 3), tool.pointSelection.indices)
        assertEquals("no point added by the box", 5, tool.pointCount)
        // A tap on empty canvas (outside the gizmo) drops them, and adds no point either.
        tap(30f, 270f)
        assertTrue("a tap on empty canvas clears the selection", tool.pointSelection.isEmpty)
        assertEquals(5, tool.pointCount)
        QaCurves.drag(s, Vec2(100f, 30f), Vec2(200f, 220f))
        assertEquals(listOf(1, 2, 3), tool.pointSelection.indices)
        assertEquals("selecting is never a step", n0, steps(tool))

        // The group dragged by its lowest point (ON the box's bottom handle): one step.
        QaCurves.drag(s, start[2], Vec2(10f, 20f))
        val moved = points(tool)
        for (i in 0 until 5) assertNear("point $i", if (i in 1..3) start[i] + Vec2(10f, 20f) else start[i], moved[i])
        assertEquals(n0 + 1, steps(tool))
        // The selection dropped (a tap on empty canvas), then in-tool undo: the points AND the
        // group's selection come back (read directly, not through the steps helper).
        tap(30f, 270f)
        assertTrue(tool.pointSelection.isEmpty)
        press("Undo last point", 44f)
        for (i in 0 until 5) assertNear("undone point $i", start[i], points(tool)[i])
        assertEquals("undo restores the group's selection", listOf(1, 2, 3), tool.pointSelection.indices)
        // Redo goes back to just before the undo: moved, nothing selected (as it was then).
        press("Redo point", 44f)
        for (i in 0 until 5) assertNear("redone point $i", moved[i], points(tool)[i])
        assertTrue("redo restores the selection of then (none)", tool.pointSelection.isEmpty)
        QaCurves.drag(s, Vec2(100f, 30f), Vec2(200f, 220f))
        assertEquals("the box picks the moved three", listOf(1, 2, 3), tool.pointSelection.indices)

        // "Select several" off: the group's sharp chip makes the three corners at once (one step).
        press(PointLabels.SELECT_SEVERAL, 44f)
        assertFalse(tool.selectSeveral)
        val n1 = steps(tool)
        press("Sharp corner")
        assertEquals("the three middle points are corners", listOf(false, true, true, true, false), tool.spline!!.points.map { it.sharp })
        assertEquals("one step for the three", n1 + 1, steps(tool))
        assertTrue("the chip says Smooth now", has("Smooth", exact = true))
        // The line passes through each corner at once (before ✓).
        for (i in 1..3) {
            val p = moved[i]
            assertTrue("through corner $i at $p", Smoke.pumpUntil(PointsQa.WAIT_MS) {
                settle(1); inked(composite(c), p.x.toInt() - 3, p.y.toInt() - 3, p.x.toInt() + 4, p.y.toInt() + 4) > 0
            })
        }

        // Point 2 alone made smooth again; then every point: the chip reads "Mixed".
        tap(moved[2].x, moved[2].y)
        assertEquals(listOf(2), tool.pointSelection.indices)
        press("Smooth")
        assertFalse(tool.spline!!.points[2].sharp)
        press(PointLabels.SELECT_ALL, 44f)
        assertEquals(5, tool.pointSelection.count)
        assertTrue("the chip says the corners differ", has(SHARP_MIXED, exact = true))
        val n2 = steps(tool)
        press(SHARP_MIXED)
        assertEquals("a tap on Mixed makes all of them corners; the ends stay", listOf(false, true, true, true, false), tool.spline!!.points.map { it.sharp })
        assertEquals(n2 + 1, steps(tool))
        assertEquals("the selection stays", 5, tool.pointSelection.count)

        // Weights: point 2 alone at 5; then every point reads "Mixed"; "*4" multiplies each (10 at most).
        tap(moved[2].x, moved[2].y)
        assertEquals(listOf(2), tool.pointSelection.indices)
        press("Type the point weight")
        SmokeUi.typeAndDone("Weight", "5")
        assertEquals(5f, tool.spline!!.points[2].weight, 1e-4f)
        press(PointLabels.SELECT_ALL, 44f)
        // (The field's own node carries the state; its visible label is a child text node.)
        var field = SmokeUi.find(POINT_WEIGHT_LABEL, exact = true)?.node
        while (field != null && field.config.getOrNull(SemanticsProperties.StateDescription) == null) field = field.parent
        val state = field?.config?.getOrNull(SemanticsProperties.StateDescription)
        assertTrue("the weight field says Mixed: $state", state?.startsWith(PointLabels.MIXED) == true)
        val n3 = steps(tool)
        press("Type $POINT_WEIGHT_LABEL")
        SmokeUi.typeAndDone(POINT_WEIGHT_LABEL, "*4")
        assertEquals("each weight × 4, 10 at most", listOf(4f, 4f, 10f, 4f, 4f), tool.spline!!.points.map { it.weight })
        assertEquals("one step for all five", n3 + 1, steps(tool))

        // Every point selected: the trash deletes the whole pending path (one in-tool step).
        assertTrue(has(PillLabels.deleteObject("path"), exact = true))
        val kept = tool.spline!!
        click(PillLabels.deleteObject("path"), exact = true)
        assertEquals("no refusal: the path is gone", 0, tool.pointCount)
        click("Undo", exact = true)
        assertEquals("Undo brings the path back as it was", kept, tool.spline)
        assertEquals("with its selection", 5, tool.pointSelection.count)

        // ✓ (with "Select several" on): one app step; the corners, the weights and the I9 form kept.
        press(PointLabels.SELECT_SEVERAL, 44f)
        assertTrue(tool.selectSeveral)
        val before = c.undoManager.undoCount
        apply("Apply path")
        assertEquals("one app step", before + 1, c.undoManager.undoCount)
        assertFalse("\"Select several\" turns itself off when the path ends", tool.selectSeveral)
        val made = c.activeLayer.vector!!.objects.single() as VPath
        val sp = made.spline!!
        assertEquals(listOf(false, true, true, true, false), sp.points.map { it.sharp })
        assertEquals(listOf(4f, 4f, 10f, 4f, 4f), sp.points.map { it.weight })
        assertEquals("I9: the stored Bézier form is the spline's", SplineBezier.toSubpath(sp), made.subpaths.single())
        assertTrue("drawn", Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); !c.vectors.isRendering })
        val shown = composite(c)
        save(shown, "group-sharp-doc")
        for (i in 1..3) {
            val p = moved[i]
            assertTrue("applied: through corner $i", inked(shown, p.x.toInt() - 3, p.y.toInt() - 3, p.x.toInt() + 4, p.y.toInt() + 4) > 0)
        }
        Smoke.assertQuiet(c, "group properties")
    }
}
