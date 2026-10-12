package com.brushwork.paint.qa18

import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (item 3) on a narrow phone (360 dp), by finger, on the Path tool (8 points per turn
 * by default): the Curves sheet's "Draws N petals" and point-limit lines fit the width on one line
 * each, in both states of the limit line, right under their sliders; the sheet keeps its height
 * when the line switches; the sliders still lie across the phone.
 *
 * - A 48-petal rose (Petals 24 over 11) at the default 8 per turn: 384 samples, "At most 400
 *   points in all"; at 9 per turn, "At the 400-point limit: more add nothing" and the spline has
 *   its 400 samples; at 30 the very same spline.
 * - A render of the sheet at the limit for the lead.
 *
 * One UI test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polisha11ycurvesnarrowsandbox"])
class Qa18PolishA11yCurvesNarrowUiTest {

    @Test
    fun theCurvesSheetLinesFitA360DpPhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 360f)
        h.section("the Path tool's Curves sheet lines at 360 dp") { flow(q) }
        dog.interrupt()
        h.finish()
    }

    /** The slider under [label] lies wholly across the phone's width. */
    private fun across(q: Qa18CurvesUi, label: String) {
        val e = q.visible { q.sliderUnder(label) }
        assertTrue("\"$label\": its slider across the phone (${e.bounds} of ${e.window.width} px)", e.bounds.left >= 0f && e.bounds.right <= e.window.width + 0.5f)
    }

    private fun flow(q: Qa18CurvesUi) {
        val k = Qa18PolishA11yCurvesSheet
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Path")
        val tool = q.c.currentTool as CurveTool
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.ROSE)
        assertEquals(CurveGenKind.ROSE, tool.generator?.kind)
        StripTestKit.closeMoreRow()
        val stepsBefore = q.steps()
        q.tap(CurveGenLabels18.CHANGE)
        for (label in listOf(CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR, CurveGenLabels18.POINTS_PER_TURN)) across(q, label)

        k.type(q, CurveGenLabels18.PETALS, "24")
        k.type(q, CurveGenLabels18.PETAL_DIVISOR, "11")
        k.assertUnder(q, "Draws 48 petals", CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR)
        val under = k.assertUnder(q, k.LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        val top = q.sheetTop()
        assertEquals("48 petals × 8: 384 samples", 48 * 8, tool.spline!!.points.size)

        k.type(q, CurveGenLabels18.POINTS_PER_TURN, "9")
        val at = k.assertUnder(q, k.AT_LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        assertFalse(has(k.LIMIT, exact = true))
        assertEquals("the same height in both states", under, at, 0.5f)
        assertEquals("the sheet keeps its height", top, q.sheetTop(), 0.5f)
        val atNine = tool.spline!!
        assertEquals("at the limit: 400 samples", CurveGenerators.MAX_ANCHORS, atNine.points.size)
        Qa18CurvesShots.save(q.capture(), "polish-a11y-curves-narrow-limit.png")
        k.type(q, CurveGenLabels18.POINTS_PER_TURN, "30")
        assertEquals(30, tool.generator!!.density)
        assertEquals("30 per turn: the same spline", atNine, tool.spline)
        k.assertUnder(q, k.AT_LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        for (label in listOf(CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR, CurveGenLabels18.POINTS_PER_TURN)) across(q, label)

        val final = tool.generator!!
        Finger.back()
        assertFalse("Back closed the sheet", has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
        q.applyEdit("Apply path edit")
        assertEquals("the ✓ is one step", stepsBefore + 1, q.steps())
        val stored = q.c.activeLayer.vector!!.objects.last() as VPath
        assertEquals("stored with its settings", final, stored.generator)
        assertTrue("a cyclic spline", stored.spline?.cyclic == true)
        Smoke.assertQuiet(q.c, "the Curves sheet's lines at 360 dp")
    }
}
