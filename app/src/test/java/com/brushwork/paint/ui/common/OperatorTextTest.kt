package com.brushwork.paint.ui.common

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.brushwork.paint.core.Expressions
import com.brushwork.paint.core.Units
import com.brushwork.paint.ui.common.ExpressionReadout.blocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 (item 15's UI, design §3.15; area I): the pure parts of the operator keys. A binary
 * operator over a fully selected value appends; otherwise keys go in at the cursor or replace
 * the selection. The readout: none for plain numbers, the field's own reading for expressions,
 * and relative errors block even where the lenient fallback reads a number. The popup sits
 * under its field when that fits, else above it.
 */
class OperatorTextTest {
    private fun key(label: String) = OPERATOR_KEYS.first { it.label == label }
    private fun all(s: String) = TextFieldValue(s, TextRange(0, s.length))

    @Test
    fun keysAreTheSixOfTheDesign() {
        assertEquals(
            listOf("Plus", "Minus", "Times", "Divided by", "Open parenthesis", "Close parenthesis"),
            OPERATOR_KEYS.map { it.label },
        )
        assertEquals(312f, OperatorKeyWidth.value * OPERATOR_KEYS.size, 0f)
        assertEquals(40f, OperatorKeyHeight.value, 0f)
    }

    @Test
    fun anOperatorOverTheSelectedValueAppends() {
        assertEquals(TextFieldValue("120+", TextRange(4)), OperatorText.insert(all("120"), key(ExpressionLabels.PLUS)))
        assertEquals("120-", OperatorText.insert(all("120"), key(ExpressionLabels.MINUS)).text)
        assertEquals("120×", OperatorText.insert(all("120"), key(ExpressionLabels.TIMES)).text)
        assertEquals("120÷", OperatorText.insert(all("120"), key(ExpressionLabels.DIVIDED)).text)
        // A parenthesis replaces it, as a typed character would (a new expression starts).
        assertEquals(TextFieldValue("(", TextRange(1)), OperatorText.insert(all("120"), key(ExpressionLabels.OPEN)))
    }

    @Test
    fun otherwiseAKeyGoesInAtTheCursorOrReplacesTheSelection() {
        assertEquals(TextFieldValue("12+0", TextRange(3)), OperatorText.insert(TextFieldValue("120", TextRange(2)), key(ExpressionLabels.PLUS)))
        assertEquals(TextFieldValue("(120", TextRange(1)), OperatorText.insert(TextFieldValue("120", TextRange(0)), key(ExpressionLabels.OPEN)))
        assertEquals(TextFieldValue("1×0", TextRange(2)), OperatorText.insert(TextFieldValue("120", TextRange(1, 2)), key(ExpressionLabels.TIMES)))
        assertEquals(TextFieldValue("+", TextRange(1)), OperatorText.insert(TextFieldValue(""), key(ExpressionLabels.PLUS)))
        // A reversed selection is the same selection.
        assertEquals("1×0", OperatorText.insert(TextFieldValue("120", TextRange(2, 1)), key(ExpressionLabels.TIMES)).text)
        // Full text takes no more keys.
        val full = "1".repeat(Expressions.MAX_LENGTH)
        assertEquals(full, OperatorText.insert(TextFieldValue(full, TextRange(full.length)), key(ExpressionLabels.PLUS)).text)
    }

    private val units: (String) -> Double? = { Units.parse(it) }

    @Test
    fun theReadoutShowsWhatTheFieldWouldApply() {
        assertNull("empty", ExpressionReadout.of("", 120.0, units))
        assertNull("a plain number shows none (v1.6)", ExpressionReadout.of("150", 120.0, units))
        assertNull(ExpressionReadout.of("12,5 px", 120.0, units))
        assertEquals(Readout.Value(150.0), ExpressionReadout.of("120+30", 120.0, units))
        assertEquals(Readout.Error(ExpressionLabels.INVALID), ExpressionReadout.of("120+", 120.0, units))
        assertEquals(Readout.Error(ExpressionLabels.DIV_ZERO), ExpressionReadout.of("5/0", 120.0, units))
        // Relative text resolved by the field's own reading.
        val relative: (String) -> Double? = { t -> Units.parse(Expressions.resolveRelative(t, 120.0) ?: t) }
        assertEquals(Readout.Value(60.0), ExpressionReadout.of("/2", 120.0, relative))
        assertEquals(Readout.Error(ExpressionLabels.DIV_ZERO), ExpressionReadout.of("/0", 120.0, relative))
        // Clamping is the field's: the readout shows what applies.
        assertEquals(Readout.Value(100.0), ExpressionReadout.of("80+40", null) { t -> Units.parse(t)?.coerceAtMost(100.0) })
    }

    @Test
    fun aRelativeErrorBlocksEvenWhereTheLenientFallbackReadsANumber() {
        // v1.6 LabeledSlider reading: digits . , - + only ("/0" -> "0").
        val lenient: (String) -> Double? = { t -> t.filter { it.isDigit() || it in ".,-+" }.toDoubleOrNull() }
        val r = ExpressionReadout.of("/0", 120.0, lenient)
        assertEquals(Readout.Error(ExpressionLabels.DIV_ZERO), r)
        assertTrue(blocks(r))
        assertEquals(Readout.Error(ExpressionLabels.INVALID), ExpressionReadout.of("/2+", 120.0, lenient))
        // Not relative: the lenient fallback still reads "1 000" (§3.15), so no error.
        assertEquals(Readout.Value(1000.0), ExpressionReadout.of("1 000", 120.0, lenient))
        // Relative text without a current value is an error.
        assertEquals(Readout.Error(ExpressionLabels.INVALID), ExpressionReadout.of("*2", null, lenient))
    }

    @Test
    fun readoutText() {
        val fmt: (Double) -> String = { Units.formatNumber(it, 1) }
        assertEquals("= 150 px", ExpressionReadout.text(Readout.Value(150.0), fmt, "px"))
        assertEquals("= 2.5", ExpressionReadout.text(Readout.Value(2.5), fmt, ""))
        assertEquals("Can't divide by 0", ExpressionReadout.text(Readout.Error(ExpressionLabels.DIV_ZERO), fmt, "px"))
    }

    @Test
    fun thePopupSitsUnderItsFieldWhenItFitsElseAbove() {
        // Window 1000 high, popup 100: under a field ending at 500; above one ending at 950.
        assertEquals(504, OperatorPopupMath.y(top = 450, bottom = 500, height = 100, window = 1000, gap = 4))
        assertEquals(346, OperatorPopupMath.y(top = 450, bottom = 950, height = 100, window = 1000, gap = 4))
        assertEquals(0, OperatorPopupMath.y(top = 50, bottom = 990, height = 100, window = 1000, gap = 4))
        // Centred on the field, kept 8 px inside the window.
        assertEquals(150, OperatorPopupMath.x(left = 200, right = 400, width = 300, window = 1000, margin = 8))
        assertEquals(8, OperatorPopupMath.x(left = 0, right = 40, width = 300, window = 1000, margin = 8))
        assertEquals(692, OperatorPopupMath.x(left = 960, right = 1000, width = 300, window = 1000, margin = 8))
        // A window narrower than the popup: centred.
        assertEquals(0, OperatorPopupMath.x(left = 0, right = 40, width = 300, window = 290, margin = 8))
    }
}
