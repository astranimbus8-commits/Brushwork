package com.brushwork.paint.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.SliderMath
import com.brushwork.paint.ui.editor.ValueInputDialog
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
 * v1.7 (item 15's UI, design §3.15; area I): the value input dialog's operator keys and live
 * readout. It opens with "120" selected; "+" APPENDS ("120+"); "120+30" reads "= 150 px"; OK
 * applies 150. "/0" reads "Can't divide by 0", OK is disabled and Done applies nothing. The key
 * row is tagged, its six keys are labelled, and each is at least 40 dp wide on the 392 dp phone.
 * Own sandbox, one UI test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.operatorkeyssandbox"])
class OperatorKeysRobolectricTest {

    @Test
    fun plusAppendsTheReadoutShowsTheResultAndOkAppliesIt() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var size by mutableFloatStateOf(120f)
        var open by mutableStateOf(true)
        activity.setContent {
            BrushworkTheme {
                if (open) {
                    ValueInputDialog(
                        title = "Brush size",
                        label = "Size",
                        initial = size,
                        format = { SliderMath.formatSize(it) },
                        parse = SliderMath::parseSize,
                        step = SliderMath::stepSize,
                        toFraction = SliderMath::sizeToFraction,
                        fromFraction = SliderMath::fractionToSize,
                        rangeText = "0.5 – 1000 px",
                        suffix = "px",
                        onApply = { size = it },
                        onDismiss = { open = false },
                    )
                }
            }
        }
        SmokeUi.settle()

        // The six keys, labelled, in one tagged row; each key a finger-sized target.
        for (label in listOf(ExpressionLabels.PLUS, ExpressionLabels.MINUS, ExpressionLabels.TIMES, ExpressionLabels.DIVIDED, ExpressionLabels.OPEN, ExpressionLabels.CLOSE)) {
            assertTrue("key $label", SmokeUi.has(label, exact = true))
        }
        val row = RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsProperties.TestTag) == V17Tags.OPERATOR_KEYS }
        val density = activity.resources.displayMetrics.density
        val rowDp = row.bounds.width / density
        println("operator key row in the value dialog at 392 dp: $rowDp dp")
        assertTrue("the row is at most 312 dp ($rowDp)", rowDp <= 312.5f)
        assertTrue("every key is at least 40 dp wide ($rowDp / 6)", rowDp / 6f >= 40f)
        assertTrue("the keys are 40 dp tall", row.bounds.height / density >= 39.5f)

        // The dialog opens with the whole value selected.
        val field = SmokeUi.field("Size")
        assertEquals("120", field.text)
        assertEquals(TextRange(0, 3), field.node.config.getOrNull(SemanticsProperties.TextSelectionRange))

        // "+" over the selected value appends.
        SmokeUi.click(ExpressionLabels.PLUS, exact = true)
        assertEquals("120+", SmokeUi.field("Size").text)
        assertFalse("no readout for an unfinished expression's value", SmokeUi.has("= 150 px", exact = true))

        // "120+30": the readout shows the result, and OK applies it.
        SmokeUi.field("Size").type("120+30")
        SmokeUi.settle(4)
        assertTrue("readout; shown: ${SmokeUi.shown().take(40)}", SmokeUi.has("= 150 px", exact = true))
        assertTrue("OK is enabled", SmokeUi.isEnabled("OK"))
        SmokeUi.click("OK", exact = true)
        assertEquals(150f, size, 1e-6f)
        assertFalse("the dialog closed", open)

        // "/0": the error, OK disabled, Done applies nothing (the dialog stays).
        open = true
        SmokeUi.settle()
        SmokeUi.field("Size").type("/0")
        SmokeUi.settle(4)
        assertTrue(SmokeUi.has(ExpressionLabels.DIV_ZERO, exact = true))
        assertFalse("OK is disabled", SmokeUi.isEnabled("OK"))
        requireNotNull(SmokeUi.field("Size").node.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()
        SmokeUi.settle(4)
        assertTrue("still open", open)
        assertEquals(150f, size, 1e-6f)

        // "×" on a typed value goes in at the cursor; the readout follows ("150×2" = 300).
        SmokeUi.field("Size").type("150")
        SmokeUi.settle(2)
        SmokeUi.click(ExpressionLabels.TIMES, exact = true)
        assertEquals("150×", SmokeUi.field("Size").text)
        SmokeUi.field("Size").type("150×2")
        SmokeUi.settle(4)
        assertTrue(SmokeUi.has("= 300 px", exact = true))
        assertTrue(SmokeUi.has("Check the expression", exact = true).not())
        SmokeUi.click("OK", exact = true)
        assertEquals(300f, size, 1e-6f)
    }
}
