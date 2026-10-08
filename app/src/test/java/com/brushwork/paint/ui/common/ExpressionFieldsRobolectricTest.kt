package com.brushwork.paint.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 15's UI, design §3.15; area I): the operator keys and the live readout in the other
 * typed places.
 * - A [LabeledSlider]'s value editor: "+" appends to the selected 120, "120+30" reads "= 150 px"
 *   and Done applies 150; "/0" reads "Can't divide by 0" and Done applies NOTHING (the gate 2
 *   review: parseTyped's lenient v1.6 filter would read "0").
 * - A [NumberField]: the keys show while it is typed in; "/0" reads the error and the commit is
 *   refused (the text goes back to the value); "×" goes in at the cursor and "4×3" applies 12.
 * - The Step popup: "/0" disables OK; "*2" reads the doubled step and OK applies it.
 * 392 dp phone; own sandbox, one UI test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.expressionfieldssandbox"])
class ExpressionFieldsRobolectricTest {

    @Test
    fun sliderFieldAndStepPopupShowTheReadoutAndRefuseErrors() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(64, 64, layers = 1))
        var spacing by mutableFloatStateOf(120f)
        var thickness by mutableDoubleStateOf(4.0)
        var stepPopup by mutableStateOf(false)
        activity.setContent {
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides c.increments) {
                    Column {
                        LabeledSlider("Spacing", spacing, { spacing = it }, 1f..500f, typing = SliderTyping(1f, 0, "px"))
                        NumberField("Thickness", thickness, { thickness = it }, decimals = 1, suffix = "px", min = 0.0, max = 100.0, adjust = NumberAdjust.NONE)
                        if (stepPopup) StepPopupPanel(c.increments, StepTarget(IncrementKind.LENGTH, null), onDismiss = { stepPopup = false })
                    }
                }
            }
        }
        SmokeUi.settle()
        assertFalse("no keys before anything is typed in", SmokeUi.has(ExpressionLabels.PLUS, exact = true))

        // ---- the slider's value editor
        SmokeUi.click("Type a value for Spacing")
        assertEquals("120", SmokeUi.field("Spacing").text)
        assertTrue("the keys show while the value is typed", SmokeUi.has(ExpressionLabels.PLUS, exact = true))
        SmokeUi.click(ExpressionLabels.PLUS, exact = true)
        assertEquals("120+", SmokeUi.field("Spacing").text)
        SmokeUi.field("Spacing").type("120+30")
        SmokeUi.settle(4)
        assertTrue("readout; shown: ${SmokeUi.shown().take(40)}", SmokeUi.has("= 150 px", exact = true))
        imeDone("Spacing")
        assertEquals(150f, spacing, 1e-6f)
        assertFalse("the keys went with the editor", SmokeUi.has(ExpressionLabels.PLUS, exact = true))

        SmokeUi.click("Type a value for Spacing")
        SmokeUi.field("Spacing").type("/0")
        SmokeUi.settle(4)
        assertTrue("\"/0\" is an error; shown: ${SmokeUi.shown().take(40)}", SmokeUi.has(ExpressionLabels.DIV_ZERO, exact = true))
        imeDone("Spacing")
        assertEquals("\"/0\" applies nothing (not 0, not 1)", 150f, spacing, 1e-6f)

        // ---- a NumberField
        SmokeUi.field("Thickness").focus()
        SmokeUi.settle(2)
        assertTrue("the keys show while the field is typed in", SmokeUi.has(ExpressionLabels.TIMES, exact = true))
        SmokeUi.field("Thickness").type("/0")
        SmokeUi.settle(4)
        assertTrue(SmokeUi.has(ExpressionLabels.DIV_ZERO, exact = true))
        imeDone("Thickness")
        assertEquals(4.0, thickness, 1e-9)
        assertEquals("the refused text goes back to the value", "4", SmokeUi.field("Thickness").text)
        SmokeUi.field("Thickness").type("4")
        SmokeUi.settle(2)
        SmokeUi.click(ExpressionLabels.TIMES, exact = true)
        assertEquals("4×", SmokeUi.field("Thickness").text)
        SmokeUi.field("Thickness").type("4×3")
        SmokeUi.settle(4)
        assertTrue(SmokeUi.has("= 12 px", exact = true))
        imeDone("Thickness")
        assertEquals(12.0, thickness, 1e-9)
        SmokeUi.field("Thickness").window.clearFocus()
        SmokeUi.settle(4)
        assertFalse("the keys go when the field loses focus", SmokeUi.has(ExpressionLabels.TIMES, exact = true))

        // ---- the Step popup
        val before = c.increments.state.step(IncrementKind.LENGTH)
        stepPopup = true
        SmokeUi.settle()
        val stepField = "${IncrementKind.LENGTH.label} step"
        assertTrue("the popup has the keys", SmokeUi.has(ExpressionLabels.DIVIDED, exact = true))
        SmokeUi.field(stepField).type("/0")
        SmokeUi.settle(4)
        assertTrue(SmokeUi.has(ExpressionLabels.DIV_ZERO, exact = true))
        assertFalse("OK is disabled", SmokeUi.isEnabled("OK"))
        imeDone(stepField)
        assertTrue("still open", stepPopup)
        assertEquals(before, c.increments.state.step(IncrementKind.LENGTH), 1e-6f)
        SmokeUi.field(stepField).type("*2")
        SmokeUi.settle(4)
        assertTrue(SmokeUi.has("= ${IncrementStepping.format(before * 2f)} px", exact = true))
        SmokeUi.click("OK", exact = true)
        assertFalse(stepPopup)
        assertEquals(before * 2f, c.increments.state.step(IncrementKind.LENGTH), 1e-6f)
        c.dispose()
    }

    /** The keyboard's Done key on the field labelled [label]. */
    private fun imeDone(label: String) {
        requireNotNull(SmokeUi.field(label).node.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()
        SmokeUi.settle(4)
    }
}
