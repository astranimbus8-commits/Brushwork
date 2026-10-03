package com.brushwork.paint.qa16

import androidx.compose.ui.semantics.SemanticsProperties
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (increments everywhere): the layer window's opacity on the real editor. The row's
 * − / + and slider step by the Percent increment (ARCHITECTURE: "Percent increments"), and so
 * must the typed-value dialog its value opens — its − / + to the next multiple, its slider onto
 * the multiples (as the brush opacity's dialog does) — while a typed value stays exact; with
 * increments off everything is v1.5 (1 % steps).
 *
 * (Before the fix the dialog ignored the increments: − from 90 % gave 89 %, its slider 63 %.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.layeropacitysandbox"])
class QaLayerOpacityDialogIncrementsUiTest {

    private fun text(label: String): String = SmokeUi.field(label).node.config[SemanticsProperties.EditableText].text

    @Test
    fun theLayerOpacityDialogStepsByThePercentIncrement() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("increments on (Percent 10): the row and its dialog step by 10 %, typed is exact") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(percent = "10")
            assertEquals(10f, c.increments.step(com.brushwork.paint.model.IncrementKind.PERCENT)!!, 0f)
            click("Open layers")
            assertTrue("the layer window: ${SmokeUi.shown()}", has(LayerLabels.TYPE_OPACITY))
            val layer = c.activeLayer
            assertEquals(1f, layer.opacity, 0f)
            click(LayerLabels.LESS_OPACITY, exact = true)
            assertEquals("the row's −: 90 %", 0.9f, layer.opacity, 1e-5f)

            click(LayerLabels.TYPE_OPACITY)
            assertEquals("90", text(LayerLabels.OPACITY))
            click("Decrease ${LayerLabels.OPACITY}", exact = true)
            assertEquals("the dialog's −: the next multiple of 10", "80", text(LayerLabels.OPACITY))
            click("Increase ${LayerLabels.OPACITY}", exact = true)
            click("Increase ${LayerLabels.OPACITY}", exact = true)
            assertEquals("+ +: 100", "100", text(LayerLabels.OPACITY))
            QaCurves.setSlider("${LayerLabels.OPACITY} slider", 0.63f)
            assertEquals("the dialog's slider lands on 10 % multiples", "60", text(LayerLabels.OPACITY))
            click("OK", exact = true)
            assertEquals(0.6f, layer.opacity, 1e-5f)

            // Typed: exact.
            click(LayerLabels.TYPE_OPACITY)
            SmokeUi.typeAndDone(LayerLabels.OPACITY, "37")
            assertEquals(0.37f, layer.opacity, 1e-5f)
            Smoke.assertQuiet(c, "on")
        }
        h.section("increments off: the dialog is v1.5 (1 % steps)") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOff()
            assertFalse(c.increments.enabled)
            click("Open layers")
            click(LayerLabels.TYPE_OPACITY)
            assertEquals("100", text(LayerLabels.OPACITY))
            click("Decrease ${LayerLabels.OPACITY}", exact = true)
            assertEquals("99", text(LayerLabels.OPACITY))
            QaCurves.setSlider("${LayerLabels.OPACITY} slider", 0.63f)
            assertEquals("63", text(LayerLabels.OPACITY))
            click("OK", exact = true)
            assertEquals(0.63f, c.activeLayer.opacity, 1e-5f)
            Smoke.assertQuiet(c, "off")
        }
        dog.interrupt()
        h.finish()
    }
}
