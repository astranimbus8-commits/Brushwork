package com.brushwork.paint.ui.placement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.7 Kerning row caption ([gapCaption], item 17, area D): it names the letter before the first
 * gap and the one after the last, whole (an emoji is one letter, never half of a surrogate pair),
 * a line break as ↵.
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
    }
}
