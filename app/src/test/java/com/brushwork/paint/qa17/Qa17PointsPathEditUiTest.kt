package com.brushwork.paint.qa17

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa17.PointsQa.Companion.composite
import com.brushwork.paint.qa17.PointsQa.Companion.inked
import com.brushwork.paint.qa17.PointsQa.Companion.save
import com.brushwork.paint.qa17.PointsQa.Companion.steps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.vector.PATH_WIDTH_CAPTION
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.roundToInt

/**
 * v1.7 final QA, items 19 and 4 on the user's phone (392 dp), with fingers. Item 19 as the user
 * put it ("when selecting the first point, it seems to put a new point after the first one"):
 * with the first point of a Path tapped, taps and a drag on the canvas grow the path from its
 * START, the new first point stays selected, the canvas shows the longer path before ✓, and each
 * new point is one in-tool step (undo keeps extending from the start). Item 4: "Sharp corner" on
 * a middle point makes the line pass through it at once, the point keeps its corner while it is
 * dragged, the ends say "Ends are always sharp" (off), and each is one step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.pointspatheditsandbox"])
class Qa17PointsPathEditUiTest {

    @Test
    fun aPathGrowsFromItsStartAndKeepsItsSharpCorners() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        val qa = PointsQa(h)
        h.section("19: the first point selected, taps grow the path from its start") { qa.prepend() }
        h.section("4: a sharp middle point, dragged") { qa.sharp() }
        dog.interrupt()
        h.finish()
    }

    private fun PointsQa.prepend() {
        editor()
        c.brush = BrushLibrary.defaultBrush.copy(size = 8f, opacity = 1f)
        val tool = tool("Path")
        tap(150f, 200f)
        tap(250f, 120f)
        tap(330f, 200f)
        fun xs() = tool.spline!!.points.map { it.x.roundToInt() to it.y.roundToInt() }
        assertEquals(listOf(150 to 200, 250 to 120, 330 to 200), xs())

        // The first point, tapped; then two taps on empty canvas: each lands BEFORE it.
        tap(150f, 200f)
        assertEquals("the first point, tapped", 0, tool.selectedPoint)
        val n = steps(tool)
        tap(90f, 120f)
        assertEquals("the tap grew the path from its start", listOf(90 to 120, 150 to 200, 250 to 120, 330 to 200), xs())
        assertEquals("the new start is selected", 0, tool.selectedPoint)
        tap(50f, 220f)
        assertEquals(listOf(50 to 220, 90 to 120, 150 to 200, 250 to 120, 330 to 200), xs())
        assertEquals(0, tool.selectedPoint)
        assertEquals("one step per point", n + 2, steps(tool))
        // The canvas shows the longer path at once (before ✓), from its new start.
        assertTrue("the path reaches the new start before ✓", Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); inked(composite(c), 44, 210, 60, 230) > 0 })
        save(composite(c), "prepend-doc")
        save(PointsQa.window(s), "prepend-screen")

        // "Undo last point" takes back the last start; the selection stays on the first point, so
        // the next point (a drag from empty canvas) is again put at the start and follows the finger.
        press("Undo last point", 44f)
        assertEquals(listOf(90 to 120, 150 to 200, 250 to 120, 330 to 200), xs())
        assertEquals("still the first point", 0, tool.selectedPoint)
        QaCurves.drag(s, Vec2(60f, 60f), Vec2(10f, 20f))
        assertEquals("dragged into place at the start", listOf(70 to 80, 90 to 120, 150 to 200, 250 to 120, 330 to 200), xs())
        assertEquals(0, tool.selectedPoint)
        assertEquals(n + 2, steps(tool))
        press("Undo last point", 44f)
        press("Redo point", 44f)
        assertEquals(listOf(70 to 80, 90 to 120, 150 to 200, 250 to 120, 330 to 200), xs())

        // The last point selected: a tap still extends the END (v1.6).
        tap(330f, 200f)
        assertEquals(4, tool.selectedPoint)
        tap(370f, 150f)
        assertEquals("after the last point", 370 to 150, xs().last())
        assertEquals(5, tool.selectedPoint)
        apply("Apply path edit")
        assertTrue("drawn from the new start", inked(composite(c), 62, 72, 78, 88) > 0)
        Smoke.assertQuiet(c, "prepend")
    }

    private fun PointsQa.sharp() {
        editor(vector = true)
        c.brush = BrushLibrary.defaultBrush.copy(size = 6f, opacity = 1f)
        val tool = tool("Path")
        val pts = listOf(60f to 150f, 130f to 80f, 200f to 220f, 270f to 80f, 340f to 150f)
        for ((x, y) in pts) tap(x, y)
        assertEquals(5, tool.pointCount)
        // The middle point: smooth, so the line passes above it (and the caption says why).
        tap(200f, 220f)
        assertEquals(2, tool.selectedPoint)
        assertTrue("the caption", has(PATH_WIDTH_CAPTION, exact = true))
        idle()
        assertTrue("the path shows", Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); inked(composite(c), 0, 0, 400, 300) > 100 })
        assertEquals("smooth: the line passes above the point", 0, inked(composite(c), 195, 205, 205, 222))
        val n = steps(tool)
        press("Sharp corner")
        assertTrue("sharp", tool.spline!!.points[2].sharp)
        assertTrue("the chip says Smooth now", has("Smooth", exact = true))
        assertFalse("no caption for a sharp point", has(PATH_WIDTH_CAPTION, exact = true))
        assertEquals("one step", n + 1, steps(tool))
        assertTrue("sharp: the line passes through the point at once", Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); inked(composite(c), 196, 214, 204, 222) > 0 })
        save(composite(c), "sharp-doc")

        // Dragged: it keeps its corner; one step; the line follows through it.
        QaCurves.drag(s, Vec2(200f, 220f), Vec2(0f, 40f))
        assertEquals(200 to 260, tool.spline!!.points[2].let { it.x.roundToInt() to it.y.roundToInt() })
        assertTrue("still sharp after the drag", tool.spline!!.points[2].sharp)
        assertEquals(n + 2, steps(tool))
        assertTrue("through the dragged corner", Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); inked(composite(c), 196, 254, 204, 262) > 0 })
        // Undo: back in place, still sharp; undo again: smooth; redo both.
        press("Undo last point", 44f)
        assertEquals(220f, tool.spline!!.points[2].y, 0.01f)
        assertTrue(tool.spline!!.points[2].sharp)
        press("Undo last point", 44f)
        assertFalse(tool.spline!!.points[2].sharp)
        press("Redo point", 44f)
        press("Redo point", 44f)
        assertTrue(tool.spline!!.points[2].sharp)
        assertEquals(260f, tool.spline!!.points[2].y, 0.01f)

        // The ends: always sharp; the chip says so and is off.
        tap(60f, 150f)
        assertEquals(0, tool.selectedPoint)
        assertTrue(has(PointLabels.ENDS_SHARP, exact = true))
        assertFalse(SmokeUi.isEnabled(PointLabels.ENDS_SHARP))

        // ✓: the path object keeps the corner.
        apply("Apply path edit")
        val p = c.activeLayer.vector!!.objects.single() as VPath
        assertEquals(listOf(false, false, true, false, false), p.spline!!.points.map { it.sharp })
        assertTrue("drawn through the corner", inked(composite(c), 196, 254, 204, 262) > 0)
        Smoke.assertQuiet(c, "sharp")
    }
}
