package com.brushwork.paint.qa16

import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.common.SliderScale
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (increments everywhere: "canvas size dialog"): More › Canvas… › Canvas size on
 * the real editor. Its Width / Height are length fields (LENGTH, §3.4 (c)): with increments on
 * (Length 25 px) their logarithmic sliders land on multiples of 25 px, in px and in mm (the step
 * shown in the field's unit, 25 px = 1.814 mm at 350 dpi); a typed width stays exact; "Change
 * canvas size" applies the numbers and Undo puts the 400 × 300 canvas back. With increments off
 * the slider is v1.5 (free, three significant digits).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.canvassizesandbox"])
class QaCanvasSizeIncrementsUiTest {

    /** Where the Width / Height slider (1 … 10 000 px, logarithmic) puts [px], in [unit] at 350 dpi. */
    private fun pos(px: Double, unit: LengthUnit = LengthUnit.PX): Float {
        val dpi = 350.0
        return SliderScale.log(unit.fromPx(1.0, dpi), unit.fromPx(CanvasOps.MAX_SIDE.toDouble(), dpi)).fraction(unit.fromPx(px, dpi))
    }

    private fun openCanvasSize() {
        click("More options")
        click("Canvas…", exact = true)
        SmokeUi.assertPanelShown("Canvas")
        SmokeUi.clickTab("Canvas size")
        assertTrue("the Canvas size tab: ${SmokeUi.shown()}", has("Change canvas size", exact = true))
    }

    @Test
    fun theCanvasSizeFieldsStepByTheLengthIncrement() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("increments on (Length 25 px)") {
            val s = h.editor()
            val c = s.c
            assertEquals(400, c.doc.width)
            assertEquals(300, c.doc.height)
            assertEquals(350f, c.doc.dpi)
            QaCurves.incrementsOn(length = "25")
            openCanvasSize()
            assertTrue(has("400 × 300 px", exact = true))

            // The sliders: 537 → 525, 1234 → 1225 (the nearest multiples of 25).
            QaCurves.setSlider("Width", pos(537.0))
            assertTrue("Width slider: 525 (${SmokeUi.shown()})", has("525 × 300 px", exact = true))
            QaCurves.setSlider("Height", pos(1234.0))
            assertTrue("Height slider: 1225 (${SmokeUi.shown()})", has("525 × 1225 px", exact = true))

            // Typed: exact.
            SmokeUi.typeAndDone("Width", "537")
            assertTrue("typed: 537 (${SmokeUi.shown()})", has("537 × 1225 px", exact = true))

            // In mm the step is the same 25 px (1.814 mm): 600 px (43.5 mm) stays 600 px.
            click("Change unit")
            click("Millimeters (mm)", exact = true)
            settle()
            QaCurves.setSlider("Width", pos(611.0, LengthUnit.MM))
            assertTrue("mm slider: 600 px (${SmokeUi.shown()})", has("600 × 1225 px", exact = true))
            click("Change unit")
            click("Pixels (px)", exact = true)
            settle()
            SmokeUi.typeAndDone("Width", "537")

            click("Change canvas size", exact = true)
            assertTrue("canvas size finished", Smoke.pumpUntil { settle(1); c.busyMessage == null && c.doc.width == 537 })
            assertEquals(537, c.doc.width)
            assertEquals(1225, c.doc.height)
            click("Undo")
            assertTrue("undone", Smoke.pumpUntil { settle(1); c.busyMessage == null && c.doc.width == 400 })
            assertEquals(300, c.doc.height)
            Smoke.assertQuiet(c, "on")
        }
        h.section("increments off: v1.5") {
            val s = h.editor()
            QaCurves.incrementsOff()
            openCanvasSize()
            QaCurves.setSlider("Width", pos(537.0))
            assertTrue("free: 537 (${SmokeUi.shown()})", has("537 × 300 px", exact = true))
            QaCurves.setSlider("Height", pos(1234.0))
            assertTrue("free: 1230 (three significant digits) (${SmokeUi.shown()})", has("537 × 1230 px", exact = true))
            Smoke.assertQuiet(s.c, "off")
        }
        dog.interrupt()
        h.finish()
    }
}
