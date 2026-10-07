package com.brushwork.paint.core

import com.brushwork.paint.ui.common.ExpressionLabels
import com.brushwork.paint.ui.editor.SliderMath
import com.brushwork.paint.ui.editor.relativeBaseOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7 (item 15, design §3.15, I13): the expression grammar, relative text and the parse sites' fallback. */
class ExpressionsTest {

    private fun value(text: String, current: Double? = null): Double {
        val r = Expressions.evaluate(text, current)
        assertTrue("\"$text\" gives a value, not $r", r is Expressions.Result.Value)
        return (r as Expressions.Result.Value).value
    }

    private fun error(text: String, current: Double? = null): String {
        val r = Expressions.evaluate(text, current)
        assertTrue("\"$text\" is an error, not $r", r is Expressions.Result.Error)
        return (r as Expressions.Result.Error).message
    }

    @Test
    fun arithmeticFollowsTheGrammar() {
        assertEquals(14.0, value("2+3×4"), 0.0)
        assertEquals(20.0, value("(2+3)*4"), 0.0)
        assertEquals(2.5, value("10/4"), 0.0)
        assertEquals(3.0, value("1,5*2"), 0.0)
        assertEquals(5.0, value("-(-5)"), 0.0)
        assertEquals(8.0, value("5--3"), 0.0)
        assertEquals(1000.0, value("1e3"), 0.0)
        assertEquals(6.0, value("2x3"), 0.0)
        assertEquals(6.0, value("2 X 3"), 0.0)
        assertEquals(2.0, value("8÷4"), 0.0)
        assertEquals(-1.0, value("2−3"), 0.0)
        assertEquals(50.0, value("50 %"), 0.0)
        assertEquals(12.0, value("12mm"), 0.0)
        assertEquals(45.0, value("45°"), 0.0)
        assertEquals(0.025, value("2.5e-2"), 1e-15)
        assertEquals(-4.0, value(" -4 "), 0.0)
        assertEquals(4.0, value("+4"), 0.0)
    }

    @Test
    fun errorsAreReported() {
        assertEquals(ExpressionLabels.INVALID, error("--5"))
        assertEquals(ExpressionLabels.INVALID, error("2**3"))
        assertEquals(ExpressionLabels.INVALID, error("("))
        assertEquals(ExpressionLabels.INVALID, error("(2+3"))
        assertEquals(ExpressionLabels.INVALID, error("2+"))
        assertEquals(ExpressionLabels.INVALID, error(""))
        assertEquals(ExpressionLabels.INVALID, error("abc"))
        assertEquals(ExpressionLabels.INVALID, error("1 000"))
        assertEquals(ExpressionLabels.INVALID, error("1.2.3"))
        assertEquals(ExpressionLabels.INVALID, error("1e999"))
        assertEquals(ExpressionLabels.INVALID, error("1e308*10"))
        assertEquals(ExpressionLabels.DIV_ZERO, error("1/0"))
        assertEquals(ExpressionLabels.DIV_ZERO, error("4/(2-2)"))
        // Longer than MAX_LENGTH.
        assertEquals(ExpressionLabels.INVALID, error("1+".repeat(Expressions.MAX_LENGTH / 2) + "1"))
        assertEquals(ExpressionLabels.DIV_ZERO, "Can't divide by 0")
        assertEquals(ExpressionLabels.INVALID, "Check the expression")
    }

    @Test
    fun relativeTextAppliesToTheCurrentValue() {
        assertEquals(240.0, value("*2", 120.0), 0.0)
        assertEquals(60.0, value("/2", 120.0), 0.0)
        assertEquals(240.0, value("x2", 120.0), 0.0)
        assertEquals(240.0, value("X 2", 120.0), 0.0)
        assertEquals(40.0, value("÷3", 120.0), 0.0)
        assertEquals(360.0, value("×(2+1)", 120.0), 0.0)
        assertEquals(ExpressionLabels.DIV_ZERO, error("/0", 120.0))
        // Without a current value relative text is an error.
        assertEquals(ExpressionLabels.INVALID, error("*2"))
        assertEquals(ExpressionLabels.INVALID, error("/0"))
        // A leading sign is a sign.
        assertEquals(-2.0, value("-2", 120.0), 0.0)
        assertEquals(2.0, value("+2", 120.0), 0.0)
        assertTrue(Expressions.isRelative(" *2"))
        assertTrue(Expressions.isRelative("÷2"))
        assertFalse(Expressions.isRelative("-2"))
        assertFalse(Expressions.isRelative("+2"))
        assertFalse(Expressions.isRelative("2*3"))
    }

    @Test
    fun resolveRelativeWritesTheCurrentValueFirst() {
        assertEquals("120/2", Expressions.resolveRelative("/2", 120f))
        assertNull(Expressions.resolveRelative("-2", 120f))
        assertNull(Expressions.resolveRelative("2", 120f))
        assertNull(Expressions.resolveRelative("/2", Float.NaN))
        assertEquals("0.1*3", Expressions.resolveRelative("*3", 0.1f))
        assertEquals("-7.5x2", Expressions.resolveRelative(" x2 ", -7.5f))
        // More than a number after the operator keeps its meaning.
        assertEquals("120*(2+1)", Expressions.resolveRelative("*2+1", 120f))
        assertEquals(360.0, value(Expressions.resolveRelative("*2+1", 120f)!!), 0.0)
        assertEquals(value("*2+1", 120.0), value(Expressions.resolveRelative("*2+1", 120f)!!), 0.0)
        // Tiny and huge values in exponent form still read back.
        assertEquals(2e-5, value(Expressions.resolveRelative("*2", 1e-5f)!!), 1e-12)
        assertEquals(6e20, value(Expressions.resolveRelative("*2", 3e20f)!!), 1e14)
        assertEquals("0.25/2", Expressions.resolveRelative("/2", 0.25))
    }

    @Test
    fun plainNumbersAreRecognized() {
        for (plain in listOf("5", "-5", "+5", "1.5", "1,5", ".5", "5.", "1e3", "2.5E-2", "12 px", "12mm", "57 %", "-30°", "  7  ")) {
            assertTrue("\"$plain\" is plain", Expressions.isPlainNumber(plain))
        }
        for (expr in listOf("2+3", "*2", "/2", "--5", "1 000", "−5", "(5)", "2x3", "abc", "", "1.2.3")) {
            assertFalse("\"$expr\" is not plain", Expressions.isPlainNumber(expr))
        }
    }

    @Test
    fun unitsParseKeepsTheV16Results() {
        assertEquals(12.5, Units.parse("12,5")!!, 1e-9)
        assertEquals(0.5, Units.parse(".5")!!, 0.0)
        assertEquals(50.0, Units.parse("100/2")!!, 0.0)
        // Errors fall back to the v1.6 reading.
        assertNull(Units.parse("1 000"))
        assertNull(Units.parse("abc"))
        assertEquals(Double.POSITIVE_INFINITY, Units.parse("Infinity")!!, 0.0)
        assertNull(Units.parse("*2"))
    }

    @Test
    fun sliderMathReadsExpressions() {
        assertEquals(50.0, SliderMath.parseValue("100/2", 0.0, 100.0)!!, 0.0)
        assertEquals(100.0, SliderMath.parseValue("60*2", 0.0, 100.0)!!, 0.0)
        assertEquals(0.3f, SliderMath.parsePercent("60/2")!!, 1e-6f)
        assertEquals(12.5f, SliderMath.parseSize(" 12,5 px ")!!, 0f)
        assertNull(SliderMath.parseSize("1.2.3"))
        assertNull(SliderMath.parsePercent("NaN"))
    }

    @Test
    fun valueDialogsResolveAgainstTheShownValue() {
        // An opacity held as 0..1 shows (and parses) percents.
        assertEquals(60f, relativeBaseOf("60", 0.6f), 0f)
        assertEquals(0.3f, SliderMath.parsePercent(Expressions.resolveRelative("/2", relativeBaseOf("60", 0.6f))!!)!!, 1e-6f)
        assertEquals(12.5f, relativeBaseOf("12,5 px", 12.5f), 0f)
        assertEquals(3f, relativeBaseOf("", 3f), 0f)
    }
}
