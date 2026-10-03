package com.brushwork.paint.qa16

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (increments everywhere: grid and ruler) on the real editor.
 *
 * - The Grid panel's "Spacing" (a number field in px: the frozen inference px → SIZE, §3.4 (c))
 *   with increments on (Length 25, Size 4): − / + go to the next multiple of 4 px, its slider lands
 *   on them, a typed spacing stays exact; with increments off − / + are 1 px (v1.5).
 * - The ruler is not stepped (§3.4 (c), §7): with increments on, dragging the ruler on the canvas
 *   moves it by exactly the finger's move, with no step readout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.gridrulersandbox"])
class QaGridRulerIncrementsUiTest {

    @Test
    fun theGridSpacingStepsAndTheRulerDragDoesNot() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("grid spacing with increments on (Length 25, Size 4)") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(length = "25", size = "4")
            click("Grid", exact = true)
            assertTrue("the Grid panel: ${SmokeUi.shown()}", has("Show grid", exact = true))
            assertEquals(100f, c.grid.spacingPx, 0f)
            click("Increase Spacing", exact = true)
            assertEquals("+ : the next multiple of the Size step", 104f, c.grid.spacingPx, 1e-3f)
            click("Decrease Spacing", exact = true)
            assertEquals("− : 100", 100f, c.grid.spacingPx, 1e-3f)
            // The slider (1 … 400 px, linear): 0.37 is 148.6 px → 148.
            QaCurves.setSlider("Spacing", 0.37f)
            assertEquals("the slider lands on 148", 148f, c.grid.spacingPx, 1e-3f)
            SmokeUi.typeAndDone("Spacing", "37.5")
            assertEquals("typed: exact", 37.5f, c.grid.spacingPx, 1e-4f)
            Smoke.assertQuiet(c, "grid on")
        }
        h.section("the ruler dragged on the canvas: not stepped") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(length = "25", size = "4")
            assertTrue(c.increments.enabled)
            click("Ruler", exact = true)
            assertTrue("the Ruler panel: ${SmokeUi.shown()}", has("Use ruler", exact = true))
            click("Edit on canvas", exact = true)
            assertEquals(ToolId.RULER, c.activeToolId)
            QaCurves.snapOff(c)
            val r0 = c.ruler
            assertTrue("placed: ${r0.centerX}, ${r0.centerY}", r0.enabled && r0.centerX >= 0f)
            var held: String? = "unset"
            QaCurves.drag(s, Vec2(r0.centerX, r0.centerY), Vec2(37f, 12f)) { held = c.increments.readout }
            assertNull("no step readout", held)
            assertEquals("x: the finger's move", r0.centerX + 37f, c.ruler.centerX, 0.05f)
            assertEquals("y: the finger's move", r0.centerY + 12f, c.ruler.centerY, 0.05f)
            Smoke.assertQuiet(c, "ruler")
        }
        h.section("increments off: v1.5") {
            val s = h.editor()
            QaCurves.incrementsOff()
            click("Grid", exact = true)
            assertTrue(has("Show grid", exact = true))
            val g0 = s.c.grid.spacingPx
            click("Increase Spacing", exact = true)
            assertEquals("+ 1 px", g0 + 1f, s.c.grid.spacingPx, 1e-3f)
            QaCurves.setSlider("Spacing", 0.37f)
            assertEquals("free", 148.6f, s.c.grid.spacingPx, 1e-3f)
            Smoke.assertQuiet(s.c, "off")
        }
        dog.interrupt()
        h.finish()
    }
}
