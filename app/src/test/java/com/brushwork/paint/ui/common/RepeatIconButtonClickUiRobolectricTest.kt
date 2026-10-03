package com.brushwork.paint.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6 integration: the hold-to-repeat arrows ([RepeatIconButton]: a number field's −/+, the
 * nudge pad, the Curve and Shape tools' ‹ ›) are buttons a screen reader can press. Their click
 * action does one step and then what lifting the finger does (a number field's "change finished",
 * i.e. one undo step, I2); a disabled arrow says so and does nothing. A finger still gets the
 * repeat (pointer input, unchanged).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.repeatclicksandbox"])
class RepeatIconButtonClickUiRobolectricTest {

    /** The node with the click action of the arrow described [label]. */
    private fun button(label: String): SemanticsNode {
        var n: SemanticsNode? = RobolectricUi.byDescription(label).node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n ?: throw AssertionError("\"$label\" has no click action")
    }

    @Test
    fun theArrowsArePressedByTheirClickAction() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var amount by mutableDoubleStateOf(5.0)
        val changes = mutableListOf<Double>()
        var finished = 0
        var disabledClicks = 0
        activity.setContent {
            BrushworkTheme {
                Column {
                    NumberField(
                        "Amount", amount, { amount = it; changes += it },
                        step = 1.0, min = 0.0, max = 10.0, decimals = 0,
                        onValueChangeFinished = { finished++ },
                    )
                    RepeatIconButton(Icons.Filled.Add, "Never", enabled = false) { disabledClicks++ }
                }
            }
        }
        SmokeUi.settle()

        val plus = button("Increase Amount")
        assertEquals("a button", Role.Button, plus.config.getOrNull(SemanticsProperties.Role))
        assertTrue("read as one control with its icon's description", plus.config.isMergingSemanticsOfDescendants)
        SmokeUi.click("Increase Amount", exact = true)
        assertEquals("one step", 6.0, amount, 1e-9)
        assertEquals(listOf(6.0), changes)
        assertEquals("then done, as when the finger lifts (one undo step)", 1, finished)
        SmokeUi.click("Decrease Amount", exact = true)
        SmokeUi.click("Decrease Amount", exact = true)
        assertEquals(4.0, amount, 1e-9)
        assertEquals(3, finished)

        val never = button("Never")
        assertNotNull("a disabled arrow says so", never.config.getOrNull(SemanticsProperties.Disabled))
        assertFalse(SmokeUi.isEnabled("Never"))
        val handled = never.config[SemanticsActions.OnClick].action!!.invoke()
        assertFalse("not handled", handled)
        assertEquals(0, disabledClicks)

        // A finger: one tap is one step too (the press steps, the lift finishes).
        RobolectricUi.byDescription("Increase Amount").tap()
        SmokeUi.settle()
        assertEquals(5.0, amount, 1e-9)
        assertEquals(4, finished)
    }
}
