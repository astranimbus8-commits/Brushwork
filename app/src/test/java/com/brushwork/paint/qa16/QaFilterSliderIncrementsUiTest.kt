package com.brushwork.paint.qa16

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.filters.SliderFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (increments everywhere: filter sliders) on the real editor at the user's phone
 * size: Filters in the tool menu, "unsharp" searched, Unsharp Mask picked; its Radius (px: the
 * Size kind), Amount (%: Percent) and Threshold (no unit: a step of its own, set by a long-press on
 * its value) sliders, their − / + and typed values. With "#" on (More › Increments…: Size 3 px,
 * Percent 10 %) each slider lands on multiples of its step, − / + go to the next multiple, typed
 * values are kept exactly, and Threshold steps by the 16 set in its Step popup. With "#" off every
 * slider and button is v1.5's (the parameter's own resolution and nudge).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.filterslidersandbox"])
class QaFilterSliderIncrementsUiTest {

    /** The slider right under the parameter label [label] in the filter sheet. */
    private fun setParam(label: String, v: Float) {
        val at = SmokeUi.find(label, exact = true)?.bounds ?: throw AssertionError("no \"$label\": ${SmokeUi.shown().take(80)}")
        val slider = RobolectricUi.elements().filter { e ->
            e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) && e.node.positionInWindow.y >= at.top
        }.minByOrNull { it.node.positionInWindow.y - at.top } ?: throw AssertionError("no slider under \"$label\"")
        requireNotNull(slider.node.config[SemanticsActions.SetProgress].action).invoke(v)
        settle(4)
    }

    private fun openUnsharpMask(s: ChromeScreen) {
        QaCurves.tool(s, "Filters")
        SmokeUi.assertPanelShown("Filters")
        SmokeUi.field("Search filters").let { it.focus(); settle(2); it.type("unsharp") }
        settle(4)
        click("Unsharp Mask", exact = true)
        val session = s.c.filterSession ?: throw AssertionError("no filter session")
        assertEquals("blur.unsharp_mask", session.filter.id)
        assertTrue("its sliders: ${SmokeUi.shown().take(80)}", has("Radius", exact = true) && has("Amount", exact = true) && has("Threshold", exact = true))
    }

    private fun value(s: ChromeScreen, key: String): Float = s.c.filterSession!!.values.float(key)

    private fun param(s: ChromeScreen, key: String): FilterParam.Slider =
        s.c.filterSession!!.filter.params.filterIsInstance<FilterParam.Slider>().single { it.key == key }

    private fun press(label: String) {
        SmokeUi.tap(label, exact = true)
        settle(2)
    }

    @Test
    fun filterSlidersStepByTheirIncrementsOnAndAreV15Off() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("\"#\" on (Size 3, Percent 10, Threshold 16): sliders, − / +, typed") {
            val s = h.editor()
            QaCurves.incrementsOn(size = "3", percent = "10")
            openUnsharpMask(s)

            // Radius (px → Size 3).
            for (v in listOf(37.4f, 61.2f, 8.9f)) {
                setParam("Radius", v)
                assertTrue("Radius $v → ${value(s, "radius")}: a multiple of 3", QaCurves.onStep(value(s, "radius"), 3f, 1e-3f))
            }
            setParam("Radius", 37.4f)
            val r = value(s, "radius")
            press("Increase Radius")
            assertEquals("+ : the next multiple of 3", r + 3f, value(s, "radius"), 1e-3f)
            press("Decrease Radius")
            press("Decrease Radius")
            assertEquals(r - 3f, value(s, "radius"), 1e-3f)

            // Amount (% → Percent 10).
            setParam("Amount", 137f)
            assertEquals("Amount on a multiple of 10 %", 140f, value(s, "amount"), 1e-3f)
            press("Decrease Amount")
            assertEquals(130f, value(s, "amount"), 1e-3f)
            click("Type a value for Amount", exact = true)
            SmokeUi.typeAndDone("Amount", "137")
            assertEquals("typed: exact", 137f, value(s, "amount"), 1e-3f)
            press("Increase Amount")
            assertEquals("+ from 137: the next multiple", 140f, value(s, "amount"), 1e-3f)

            // Threshold (no unit): no step of its own yet, so its own resolution; then a long-press sets 16.
            setParam("Threshold", 37.4f)
            assertEquals("no step yet: its own resolution", SliderFormat.snap(param(s, "threshold"), 37.4f), value(s, "threshold"), 1e-3f)
            val valueBox = RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == "Type a value for Threshold" }
            val long = valueBox.node.config.getOrNull(SemanticsActions.OnLongClick)
            assertNotNull("a long-press on Threshold's value opens its Step popup", long)
            requireNotNull(long!!.action).invoke()
            settle()
            assertTrue("the popup: ${SmokeUi.shown().take(60)}", has("Step for Threshold", exact = true))
            SmokeUi.typeAndDone("Threshold step", "16")
            setParam("Threshold", 37.4f)
            assertEquals("Threshold on a multiple of 16", 32f, value(s, "threshold"), 1e-3f)
            press("Increase Threshold")
            assertEquals(48f, value(s, "threshold"), 1e-3f)
            QaCurves.shot(s, "filter-sliders-stepped")
            Smoke.assertQuiet(s.c, "filters on")
        }
        h.section("\"#\" off: every slider and button as in v1.5") {
            val s = h.editor()
            QaCurves.incrementsOff()
            openUnsharpMask(s)
            val radius = param(s, "radius")
            setParam("Radius", 37.4f)
            val r = value(s, "radius")
            assertEquals("Radius: its own resolution", SliderFormat.snap(radius, 37.4f), r, 1e-3f)
            press("Increase Radius")
            assertEquals("+ : its own nudge", SliderFormat.nudge(radius, r, 1), value(s, "radius"), 1e-3f)
            setParam("Amount", 137f)
            assertEquals(137f, value(s, "amount"), 1e-3f)
            press("Decrease Amount")
            assertEquals(136f, value(s, "amount"), 1e-3f)
            setParam("Threshold", 37.4f)
            assertEquals("the custom step is off too", 37f, value(s, "threshold"), 1e-3f)
            press("Increase Threshold")
            assertEquals(38f, value(s, "threshold"), 1e-3f)
            Smoke.assertQuiet(s.c, "filters off")
        }
        dog.interrupt()
        h.finish()
    }
}
