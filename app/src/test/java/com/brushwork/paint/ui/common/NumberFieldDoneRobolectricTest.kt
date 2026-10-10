package com.brushwork.paint.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
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
 * v1.7 final QA polish (verify): Done on a [NumberField] commits, closes the operator keys and
 * lets the focus go, and the focus loss that follows sends NOTHING again.
 * - The motivating caller is the Path point's "Weight" (`CurveToolOptions`): its `onValueChange`
 *   begins a numeric edit when none is held, and only `onValueChangeFinished` ends it. A change
 *   sent again after the finish began an edit nothing ended; the tool then merged the next
 *   same-kind edits into one undo step whatever the pause between them.
 * - So: typed, Done → applied, the keys gone, the focus gone, ONE edit begun and finished, none
 *   left open. The field focused again and left untouched sends nothing.
 * - A value typed and left by moving the focus (no Done) is still sent and finished as one edit.
 * 392 dp phone; own sandbox, one UI test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.numberfielddonesandbox"])
class NumberFieldDoneRobolectricTest {

    @Test
    fun doneSendsTheValueOnceAndLeavesAPairedEditClosed() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var weight by mutableDoubleStateOf(1.0)
        var held = false
        var begins = 0
        var finishes = 0
        val sent = ArrayList<Double>()
        activity.setContent {
            BrushworkTheme {
                Column {
                    // As the Path point's "Weight": one edit from the first change to the finish.
                    NumberField(
                        label = "Weight",
                        value = weight,
                        onValueChange = { v ->
                            if (!held) { held = true; begins++ }
                            sent += v
                            weight = v
                        },
                        decimals = 2,
                        min = 0.1,
                        max = 10.0,
                        step = 0.1,
                        logSlider = true,
                        onValueChangeFinished = { held = false; finishes++ },
                    )
                    NumberField("Thickness", 4.0, {}, decimals = 1, suffix = "px", min = 0.0, max = 100.0, adjust = NumberAdjust.NONE)
                }
            }
        }
        SmokeUi.settle()

        // Typed, Done.
        SmokeUi.field("Weight").focus()
        SmokeUi.settle(2)
        assertTrue("the keys show while the field is typed in", keysShown())
        SmokeUi.field("Weight").type("2")
        SmokeUi.settle(2)
        imeDone("Weight")
        assertEquals("Done applies 2", 2.0, weight, 1e-9)
        assertFalse("Done closes the keys row at once", keysShown())
        assertFalse("Done lets the focus go", SmokeUi.field("Weight").focused)
        assertEquals("ONE edit begun", 1, begins)
        assertEquals("and finished once", 1, finishes)
        assertFalse("no edit left open after Done (the focus loss sent nothing again): sent $sent", held)
        val n = sent.size

        // Focused again and left untouched (another field takes the focus): nothing is sent.
        SmokeUi.field("Weight").focus()
        SmokeUi.settle(2)
        SmokeUi.field("Thickness").focus()
        SmokeUi.settle(4)
        assertFalse(SmokeUi.field("Weight").focused)
        assertEquals("focused and left untouched: nothing sent", n, sent.size)
        assertFalse("no edit begun", held)
        assertEquals(1, begins)

        // Typed and left by the focus (no Done): sent and finished as ONE edit.
        SmokeUi.field("Weight").focus()
        SmokeUi.settle(2)
        SmokeUi.field("Weight").type("3")
        SmokeUi.settle(2)
        SmokeUi.field("Thickness").focus()
        SmokeUi.settle(4)
        assertEquals("the focus-loss commit applies 3", 3.0, weight, 1e-9)
        assertEquals(2, begins)
        assertEquals(2, finishes)
        assertFalse(held)
    }

    private fun keysShown(): Boolean = RobolectricUi.elements().any { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) == V17Tags.OPERATOR_KEYS
    }

    /** The keyboard's Done key on the field labelled [label]. */
    private fun imeDone(label: String) {
        requireNotNull(SmokeUi.field(label).node.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()
        SmokeUi.settle(4)
    }
}
