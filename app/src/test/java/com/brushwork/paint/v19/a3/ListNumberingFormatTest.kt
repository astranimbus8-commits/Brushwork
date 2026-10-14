package com.brushwork.paint.v19.a3

import com.brushwork.paint.tools.text.ListMarkers
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextNumbering
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.9 A3 step 5 (R7 b): the numbering styles. "1." "1)" "(1)" write any number; letters go
 * a … z, aa … (bijective base 26); Roman numerals go to 3999 and circled numbers to ㊿; a number
 * a style can't write (0, or past its range) falls back to "1." style. Each style's chip sample is
 * its number 1. Blank paragraphs are neither marked nor counted; bullets don't count.
 */
class ListNumberingFormatTest {

    private fun f(style: TextNumbering, n: Int) = ListMarkers.format(style, n)

    @Test
    fun decimalStylesWriteAnyNumber() {
        assertEquals("1.", f(TextNumbering.DECIMAL_DOT, 1))
        assertEquals("1)", f(TextNumbering.DECIMAL_PAREN, 1))
        assertEquals("(1)", f(TextNumbering.DECIMAL_PARENS, 1))
        assertEquals("0.", f(TextNumbering.DECIMAL_DOT, 0))
        assertEquals("(0)", f(TextNumbering.DECIMAL_PARENS, 0))
        assertEquals("10001)", f(TextNumbering.DECIMAL_PAREN, 10001))
    }

    @Test
    fun lettersAreBijectiveBase26() {
        val lower = mapOf(1 to "a", 2 to "b", 26 to "z", 27 to "aa", 28 to "ab", 52 to "az", 53 to "ba", 702 to "zz", 703 to "aaa")
        for ((n, s) in lower) {
            assertEquals("$n", "$s.", f(TextNumbering.LOWER_ALPHA, n))
            assertEquals("$n", "${s.uppercase()}.", f(TextNumbering.UPPER_ALPHA, n))
        }
        assertEquals("0 falls back", "0.", f(TextNumbering.LOWER_ALPHA, 0))
        assertEquals("0.", f(TextNumbering.UPPER_ALPHA, 0))
    }

    @Test
    fun romanNumeralsGoTo3999() {
        val roman = mapOf(
            1 to "I", 2 to "II", 3 to "III", 4 to "IV", 5 to "V", 9 to "IX", 14 to "XIV", 40 to "XL", 44 to "XLIV",
            90 to "XC", 400 to "CD", 900 to "CM", 1994 to "MCMXCIV", 2026 to "MMXXVI", 3999 to "MMMCMXCIX",
        )
        for ((n, s) in roman) {
            assertEquals("$n", "$s.", f(TextNumbering.UPPER_ROMAN, n))
            assertEquals("$n", "${s.lowercase()}.", f(TextNumbering.LOWER_ROMAN, n))
        }
        assertEquals(3999, ListMarkers.MAX_ROMAN)
        assertEquals("past 3999: \"1.\" style", "4000.", f(TextNumbering.UPPER_ROMAN, 4000))
        assertEquals("4000.", f(TextNumbering.LOWER_ROMAN, 4000))
        assertEquals("0.", f(TextNumbering.UPPER_ROMAN, 0))
    }

    @Test
    fun circledNumbersGoTo50() {
        assertEquals("①", f(TextNumbering.CIRCLED, 1))
        assertEquals("⑳", f(TextNumbering.CIRCLED, 20))
        assertEquals("㉑", f(TextNumbering.CIRCLED, 21))
        assertEquals("㉟", f(TextNumbering.CIRCLED, 35))
        assertEquals("㊱", f(TextNumbering.CIRCLED, 36))
        assertEquals("㊿", f(TextNumbering.CIRCLED, 50))
        assertEquals(50, ListMarkers.MAX_CIRCLED)
        // Every one is a single distinct code point.
        val all = (1..50).map { f(TextNumbering.CIRCLED, it) }
        assertEquals(50, all.toSet().size)
        for (s in all) assertEquals(s, 1, s.codePointCount(0, s.length))
        assertEquals("past ㊿: \"1.\" style", "51.", f(TextNumbering.CIRCLED, 51))
        assertEquals("0.", f(TextNumbering.CIRCLED, 0))
    }

    @Test
    fun eachChipSampleIsItsNumberOne() {
        for (style in TextNumbering.entries) assertEquals(style.name, style.sample, f(style, 1))
    }

    @Test
    fun paragraphsCountFromTheFirstNumber() {
        val text = "One\n\nTwo\n  \nThree\n"
        assertArrayEquals(intArrayOf(0, 4, 5, 9, 12, 18), ListMarkers.paragraphStarts(text))
        val numbers = TextListSpec(type = TextListType.NUMBERS)
        assertArrayEquals(arrayOf("1.", null, "2.", null, "3.", null), ListMarkers.texts(numbers, text))
        assertArrayEquals(arrayOf("3998.", null, "3999.", null, "4000.", null), ListMarkers.texts(numbers.copy(start = 3998), text))
        assertArrayEquals(
            "Roman falls back past its range, per number",
            arrayOf("MMMCMXCVIII.", null, "MMMCMXCIX.", null, "4000.", null),
            ListMarkers.texts(numbers.copy(start = 3998, numbering = TextNumbering.UPPER_ROMAN), text),
        )
        assertArrayEquals(arrayOf("(0)", null, "(1)", null, "(2)", null), ListMarkers.texts(numbers.copy(start = 0, numbering = TextNumbering.DECIMAL_PARENS), text))
        assertArrayEquals(arrayOf("✓", null, "✓", null, "✓", null), ListMarkers.texts(TextListSpec(type = TextListType.BULLETS, bullet = "✓"), text))
        assertArrayEquals("no list, no markers", arrayOfNulls<String>(6), ListMarkers.texts(TextListSpec(), text))
        assertArrayEquals(arrayOf<String?>("1."), ListMarkers.texts(numbers, "x"))
        assertArrayEquals(arrayOf<String?>(null), ListMarkers.texts(numbers, ""))
    }
}
