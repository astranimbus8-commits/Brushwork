package com.brushwork.paint.tools.text

import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9 F1a (R4, R5, R7, R14, design 2.3.1): the new text fields round-trip through the codec, and
 * their sanitizing: [TextListSpec.sanitized]'s ranges, a negative [TextItem.id], no list on
 * vertical or path text, and the kerning mode kept while kerning is off. JVM.
 */
class TextModelV19Test {
    private fun roundTrip(item: TextItem): TextItem = TextCodec.decode(TextCodec.encode(item))!!

    @Test
    fun theEnumsAreAsDesigned() {
        assertEquals(listOf("OFF", "ON", "ALL"), TextJustify.entries.map { it.name })
        assertEquals(listOf("OPTICAL", "METRICS_LATIN"), TextKerningMode.entries.map { it.name })
        assertEquals(listOf("NONE", "BULLETS", "NUMBERS"), TextListType.entries.map { it.name })
        assertEquals(listOf("1.", "1)", "(1)", "a.", "A.", "i.", "I.", "①"), TextNumbering.entries.map { it.sample })
        assertEquals(
            listOf("DECIMAL_DOT", "DECIMAL_PAREN", "DECIMAL_PARENS", "LOWER_ALPHA", "UPPER_ALPHA", "LOWER_ROMAN", "UPPER_ROMAN", "CIRCLED"),
            TextNumbering.entries.map { it.name },
        )
        val d = TextSpec()
        assertEquals(TextJustify.OFF, d.justify)
        assertNull(d.kerningMode)
        assertEquals(TextListSpec(), d.list)
        assertEquals(0, TextItem().id)
    }

    @Test
    fun everyNewTextFieldRoundTrips() {
        val list = TextListSpec(
            type = TextListType.NUMBERS, bullet = "★", numbering = TextNumbering.UPPER_ROMAN, start = 12, markerScale = 1.5f,
            markerWeight = 0.25f, gapEm = 2f, markerFont = TextFont.SERIF, markerFontId = "0123456789abcdef0123456789abcdef", markerFontName = "Marker Face",
        )
        for (spec in listOf(
            TextSpec(justify = TextJustify.ON),
            TextSpec(justify = TextJustify.ALL, kerningMode = TextKerningMode.METRICS_LATIN),
            TextSpec(kerningMode = TextKerningMode.OPTICAL, fontKerning = false),
            TextSpec(list = list),
            TextSpec(list = TextListSpec(type = TextListType.BULLETS, bullet = "–")),
        )) {
            val item = TextItem("Line one\nLine two", spec, 10f, 20f, id = 5)
            assertEquals(item.sanitized(), roundTrip(item))
            assertEquals(spec.justify, roundTrip(item).spec.justify)
            assertEquals(spec.kerningMode, roundTrip(item).spec.kerningMode)
            assertEquals(spec.list, roundTrip(item).spec.list)
            assertEquals(5, roundTrip(item).id)
        }
    }

    @Test
    fun offKeepsTheStoredKerningMode() {
        // R5(b): Off is fontKerning false, which keeps the mode; sanitizing does not switch kerning on (F1a deviation a).
        val off = TextSpec(fontKerning = false, kerningMode = TextKerningMode.OPTICAL)
        val back = roundTrip(TextItem("k", off)).spec
        assertFalse(back.fontKerning)
        assertEquals(TextKerningMode.OPTICAL, back.kerningMode)
    }

    @Test
    fun aNegativeIdBecomesZero() {
        assertEquals(0, TextItem("x", id = -4).sanitized().id)
        assertEquals(0, roundTrip(TextItem("x", id = -4)).id)
        assertEquals(17, TextItem("x", id = 17).sanitized().id)
    }

    @Test
    fun verticalAndPathTextHaveNoListAndKeepJustify() {
        val list = TextListSpec(type = TextListType.BULLETS)
        val vertical = TextItem("v", TextSpec(vertical = true, list = list, justify = TextJustify.ON)).sanitized()
        assertEquals(TextListSpec(), vertical.spec.list)
        assertEquals("justify is kept (the layout ignores it, C23)", TextJustify.ON, vertical.spec.justify)
        val circle = TextPathSpec(type = TextPathType.CIRCLE, cx = 50f, cy = 50f, radius = 40f)
        val onPath = TextItem("p", TextSpec(list = list, justify = TextJustify.ALL), path = circle).sanitized()
        assertEquals(TextListSpec(), onPath.spec.list)
        assertEquals(TextJustify.ALL, onPath.spec.justify)
        // A path switched off (NONE) is no path: the list stays.
        val off = TextItem("n", TextSpec(list = list), path = TextPathSpec(nodes = VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(9f, 9f))))).sanitized()
        assertEquals(list, off.spec.list)
        val plain = TextItem("h", TextSpec(list = list))
        assertEquals("horizontal text keeps its list", list, plain.sanitized().spec.list)
    }

    @Test
    fun theListIsSanitized() {
        val ok = TextListSpec(type = TextListType.BULLETS)
        assertSame("this instance when nothing changes", ok, ok.sanitized())
        assertEquals("the first grapheme, trimmed", "★", TextListSpec(bullet = "  ★x ").sanitized().bullet)
        assertEquals("a combining mark stays with its letter", "é", TextListSpec(bullet = "éz").sanitized().bullet)
        assertEquals("a surrogate pair is one character", "🔸", TextListSpec(bullet = "🔸🔹").sanitized().bullet)
        assertEquals("blank gives the dot", TextListSpec.DEFAULT_BULLET, TextListSpec(bullet = "   ").sanitized().bullet)
        assertEquals("•", TextListSpec.DEFAULT_BULLET)
        assertEquals(0, TextListSpec(start = -5).sanitized().start)
        assertEquals(TextListSpec.MAX_START, TextListSpec(start = 20_000).sanitized().start)
        assertEquals(9999, TextListSpec.MAX_START)
        assertEquals(2f, TextListSpec(markerScale = 5f).sanitized().markerScale)
        assertEquals(0.5f, TextListSpec(markerScale = 0.1f).sanitized().markerScale)
        assertEquals(1f, TextListSpec(markerScale = Float.NaN).sanitized().markerScale)
        assertEquals(0f, TextListSpec(markerWeight = -1f).sanitized().markerWeight)
        assertEquals(1f, TextListSpec(markerWeight = 2f).sanitized().markerWeight)
        assertEquals(0f, TextListSpec(markerWeight = Float.POSITIVE_INFINITY).sanitized().markerWeight)
        assertEquals(3f, TextListSpec(gapEm = 9f).sanitized().gapEm)
        assertEquals(0f, TextListSpec(gapEm = -1f).sanitized().gapEm)
        assertEquals(0.5f, TextListSpec(gapEm = Float.NaN).sanitized().gapEm)
        val badFont = TextListSpec(markerFontId = "not a font id", markerFontName = "Name").sanitized()
        assertNull(badFont.markerFontId)
        assertNull("the name goes with the id", badFont.markerFontName)
        val goodFont = TextListSpec(markerFontId = "0123456789abcdef0123456789abcdef", markerFontName = "  My\u0007 Font  ").sanitized()
        assertEquals("0123456789abcdef0123456789abcdef", goodFont.markerFontId)
        assertEquals("My Font", goodFont.markerFontName)
        assertFalse(goodFont.sameFontAsText)
        assertTrue(TextListSpec().sameFontAsText)
        assertFalse(TextListSpec(markerFont = TextFont.SERIF).sameFontAsText)
        assertFalse(TextListSpec().isOn)
        assertTrue(ok.isOn)
        // Through the codec: a damaged list is read sanitized.
        val damaged = TextItem("d", TextSpec(list = TextListSpec(type = TextListType.NUMBERS, start = -1, markerScale = 99f)))
        assertEquals(TextListSpec(type = TextListType.NUMBERS, start = 0, markerScale = 2f), roundTrip(damaged).spec.list)
    }
}
