package com.brushwork.paint.qa16

import androidx.compose.ui.semantics.SemanticsActions
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (increments everywhere: a Path control point's Thickness, the Percent kind) on the
 * real editor at the user's phone size: Path in the tool menu, Shapes ▾ › Capsule, a tap on point 4.
 * With "#" on (More › Increments…, Percent 10 %): the strip's ‹ › go to the next multiple of 10 %,
 * its slider lands on multiples, a typed thickness is kept exactly; the Numbers sheet's Thickness
 * slider lands on multiples too. With a finer Percent step (1 %) the Numbers sheet's slider must
 * reach every multiple of it (it used to keep v1.5's fixed 5 % ticks, so 142 % became 140 %).
 * With "#" off both sliders and the arrows keep v1.5's 5 % grid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.paththicknesssandbox"])
class QaPathThicknessIncrementsUiTest {

    /** The Numbers sheet's Thickness slider: the slider placed right under the "Thickness" row. */
    private fun setSheetThickness(percent: Float) {
        val label = SmokeUi.find("Thickness", exact = true)?.bounds ?: throw AssertionError("no Thickness row: ${SmokeUi.shown().take(80)}")
        val slider = RobolectricUi.elements().filter { e ->
            e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) && e.bounds.top >= label.top
        }.minByOrNull { it.bounds.top - label.top } ?: throw AssertionError("no slider under Thickness")
        requireNotNull(slider.node.config[SemanticsActions.SetProgress].action).invoke(percent)
        settle(4)
    }

    private fun capsulePoint4(s: ChromeScreen): CurveTool {
        val c = s.c
        QaCurves.tool(s, "Path")
        assertEquals(ToolId.PATH, c.activeToolId)
        val tool = c.currentTool as CurveTool
        click("Path shapes", exact = true)
        click("Capsule", exact = true)
        val p3 = tool.spline!!.points[3]
        QaCurves.tap(s, p3.x, p3.y)
        assertEquals("the tap selected point 4", 3, tool.selectedPoint)
        assertTrue(has("Point thickness 100 %", exact = true))
        return tool
    }

    @Test
    fun aPathPointsThicknessStepsByThePercentIncrement() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("\"#\" on: the strip's ‹ ›, slider and typed value; the Numbers sheet at 10 % and at 1 %") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(percent = "10")
            val tool = capsulePoint4(s)
            fun pct() = tool.spline!!.points[3].width * 100f

            click("Thicker point")
            assertEquals("› : 110 %", 110f, pct(), 1e-3f)
            click("Thicker point")
            click("Thinner point")
            assertEquals("‹ : back to 110 %", 110f, pct(), 1e-3f)
            click("Type the point thickness")
            SmokeUi.typeAndDone("Thickness", "137")
            assertEquals("typed: exact", 137f, pct(), 1e-3f)
            click("Thicker point")
            assertEquals("› from 137 %: the next multiple", 140f, pct(), 1e-3f)
            click("Type the point thickness")
            SmokeUi.typeAndDone("Thickness", "137")
            click("Thinner point")
            assertEquals("‹ from 137 %: the multiple below", 130f, pct(), 1e-3f)
            QaCurves.scrollStripTo(s, "Point thickness slider")
            QaCurves.setSlider("Point thickness slider", 163f)
            assertEquals("the strip's slider on a multiple of 10 %", 160f, pct(), 1e-3f)

            // The Numbers sheet.
            QaCurves.scrollStripTo(s, "Numbers")
            click("Numbers", exact = true)
            assertTrue("Point 4 in the sheet: ${SmokeUi.shown().take(60)}", has("Point 4 of 12", exact = true))
            setSheetThickness(187f)
            assertEquals("the sheet's slider on a multiple of 10 %", 190f, pct(), 1e-3f)
            click("Type a value for Thickness", exact = true)
            SmokeUi.typeAndDone("Thickness", "137")
            assertEquals("typed in the sheet: exact", 137f, pct(), 1e-3f)
            click("Close", exact = true)

            // A finer step: 1 %. Every multiple is reachable in both sliders.
            QaCurves.incrementsOn(length = null, size = null, scale = null, angle = null, percent = "1")
            QaCurves.scrollStripTo(s, "Point thickness slider")
            QaCurves.setSlider("Point thickness slider", 142f)
            assertEquals("the strip's slider at 1 %", 142f, pct(), 1e-3f)
            QaCurves.scrollStripTo(s, "Numbers")
            click("Numbers", exact = true)
            setSheetThickness(163f)
            assertEquals("the sheet's slider at 1 % (not v1.5's 5 % ticks)", 163f, pct(), 1e-3f)
            setSheetThickness(42f)
            assertEquals(42f, pct(), 1e-3f)
            click("Close", exact = true)
            Smoke.assertQuiet(c, "thickness on")
        }
        h.section("\"#\" off: the 5 % grid of v1.5 in the strip and the sheet") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOff()
            val tool = capsulePoint4(s)
            fun pct() = tool.spline!!.points[3].width * 100f
            click("Thicker point")
            assertEquals("› : +5 %", 105f, pct(), 1e-3f)
            QaCurves.scrollStripTo(s, "Point thickness slider")
            QaCurves.setSlider("Point thickness slider", 142f)
            assertEquals("the strip's slider on 5 %", 140f, pct(), 1e-3f)
            QaCurves.scrollStripTo(s, "Numbers")
            click("Numbers", exact = true)
            setSheetThickness(163f)
            assertEquals("the sheet's slider on 5 %", 165f, pct(), 1e-3f)
            click("Close", exact = true)
            Smoke.assertQuiet(c, "thickness off")
        }
        dog.interrupt()
        h.finish()
    }
}
