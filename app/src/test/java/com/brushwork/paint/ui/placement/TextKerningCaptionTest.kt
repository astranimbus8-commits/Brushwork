package com.brushwork.paint.ui.placement

import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.ui.common.KerningLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.7 Kerning row caption ([gapCaption]) and state ([kerningRow], item 17, area D): the caption
 * names the letter before the first gap and the one after the last, whole (an emoji with its skin
 * tone, a letter with its accent: one letter, never part of one), a line break as ↵. The row is
 * disabled, saying why, where no gap named can take a kern; a selection edits only the gaps a
 * kern applies to.
 */
class TextKerningCaptionTest {

    @Test
    fun theCaptionNamesTheLettersAroundTheGaps() {
        assertEquals("Between “A” and “V”", gapCaption("AV", 0..0))
        assertEquals("Between “V” and “A”", gapCaption("AVATAR", 1..3))
        assertEquals("Between “A” and “↵”", gapCaption("A\nB", 0..0))
    }

    @Test
    fun anEmojiBesideTheGapIsNamedWhole() {
        val smile = "😀"
        val text = "A${smile}B"
        assertEquals("Between “A” and “$smile”", gapCaption(text, 0..0))
        assertEquals("Between “$smile” and “B”", gapCaption(text, 2..2))
        assertEquals("Between “A” and “B”", gapCaption(text, 0..2))
        // Ends of the text are clamped, never out of range.
        assertEquals("Between “$smile” and “B”", gapCaption(text, 2..9))
        // An emoji with a skin tone and a letter with a combining accent are one letter each.
        val thumb = "👍🏽"
        assertEquals("Between “A” and “$thumb”", gapCaption("A${thumb}B", listOf(0)))
        assertEquals("Between “$thumb” and “B”", gapCaption("A${thumb}B", listOf(4)))
        assertEquals("Between “f” and “é”", gapCaption("café!", listOf(2)))
        assertEquals("Between “é” and “!”", gapCaption("café!", listOf(4)))
    }

    @Test
    fun theRowIsDisabledSayingWhyWhereNoKernCanApply() {
        val none = emptyList<TextKern>()
        // Beside a line break, on either side.
        for (cursor in listOf(2, 3)) {
            val row = kerningRow("AB\nCD", none, cursor, cursor, vertical = false, onPath = false)
            assertNull(row.active)
            assertEquals(KERNING_LINE_REFUSAL, row.caption)
        }
        val ok = kerningRow("AB\nCD", none, 1, 1, vertical = false, onPath = false)
        assertEquals(listOf(0), ok.active)
        assertEquals(KerningLabels.between("A", "B"), ok.caption)
        // An Arabic paragraph, and a Hebrew word in a Latin paragraph: the paragraph keeps its shaping.
        val arabic = kerningRow("AV\nمرحبا", none, 5, 5, vertical = false, onPath = false)
        assertNull(arabic.active)
        assertEquals(KERNING_SCRIPT_REFUSAL, arabic.caption)
        assertEquals(KERNING_SCRIPT_REFUSAL, kerningRow("Shalom שלום", none, 2, 2, vertical = false, onPath = false).caption)
        // ... while a Latin paragraph above it is kerned.
        assertEquals(listOf(0), kerningRow("Hi\nShalom שלום", none, 1, 1, vertical = false, onPath = false).active)
        // A selection in which some gaps apply: enabled, editing those only (here "Mixed").
        val mixed = kerningRow("AB\nCD", listOf(TextKern(0, 50)), 0, 5, vertical = false, onPath = false)
        assertEquals(listOf(0, 3), mixed.active)
        assertNull(mixed.common)
        assertEquals(KerningLabels.between("A", "D"), mixed.caption)
        assertEquals(50, kerningRow("AB\nCD", listOf(TextKern(0, 50), TextKern(3, 50)), 0, 5, vertical = false, onPath = false).common)
        // Vertical text says so first; along a shape it is kerned.
        assertEquals(KerningLabels.VERTICAL_REFUSAL, kerningRow("AB\nCD", none, 2, 2, vertical = true, onPath = false).caption)
        assertEquals(listOf(0), kerningRow("AB", none, 1, 1, vertical = true, onPath = true).active)
        // No gap named (an end of the text, inside an emoji): how to pick one.
        assertEquals(KERNING_HINT, kerningRow("AB", none, 0, 0, vertical = false, onPath = false).caption)
        val inside = kerningRow("A👍🏽B", none, 3, 3, vertical = false, onPath = false)
        assertNull(inside.active)
        assertEquals(KERNING_HINT, inside.caption)
    }
}
