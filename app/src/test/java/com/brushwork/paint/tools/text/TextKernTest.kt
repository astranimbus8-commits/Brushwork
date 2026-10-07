package com.brushwork.paint.tools.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v1.7 §3.17d (item 17, area D): kerns follow their characters through edits ([TextKerns.remap]
 * and the cursor-aware [TextKerns.diff]), the editor's cursor and selection name gaps, and the
 * renderer applies only usable kerns ([TextKerns.advancesPx]). Pure JVM.
 */
class TextKernTest {

    private fun k(vararg pairs: Pair<Int, Int>) = pairs.map { TextKern(it.first, it.second) }

    @Test
    fun anInsertMovesTheKernsAfterIt() {
        // "WAVE": kerns after W (0) and after A (1); "x" typed between A and V (at 2).
        val kerns = k(0 to -80, 1 to -120, 2 to 40)
        assertEquals(k(0 to -80, 1 to -120, 3 to 40), TextKerns.remap(kerns, 2, 0, 1))
        // Through the editor: the A keeps its kern (now before the x), the V moves on.
        assertEquals(k(0 to -80, 1 to -120, 3 to 40), TextKerns.edited(kerns, "WAVE", "WAxVE", cursor = 3))
        // An insertion at the very start moves everything.
        assertEquals(k(2 to -80, 3 to -120, 4 to 40), TextKerns.edited(kerns, "WAVE", ">>WAVE", cursor = 2))
    }

    @Test
    fun aDeleteDropsTheKernsOfRemovedCharacters() {
        val kerns = k(0 to -80, 1 to -120, 2 to 40)
        // Backspace over the A (index 1): its kern goes, W keeps its own, V's moves back.
        assertEquals(k(0 to -80, 1 to 40), TextKerns.edited(kerns, "WAVE", "WVE", cursor = 1))
        // Deleting the last character: the kern before it would need a next character.
        assertEquals(k(0 to -80, 1 to -120), TextKerns.edited(kerns, "WAVE", "WAV", cursor = 3))
    }

    @Test
    fun aReplaceKeepsTheKernsAroundIt() {
        // "Hello world" with kerns after "H" (0), the space (5) and "w" (6); "world" pasted over by "there".
        val kerns = k(0 to 50, 5 to -30, 6 to 70)
        assertEquals(k(0 to 50, 5 to -30), TextKerns.edited(kerns, "Hello world", "Hello there", cursor = 11))
        // A longer replacement shifts what follows it.
        // "AB CD!": the C (3) replaced by "XYZ"; the D's kern moves by 2.
        val tail = k(0 to 50, 3 to 10, 4 to 20)
        assertEquals(TextKerns.Edit(3, 1, 3), TextKerns.diff("AB CD!", "AB XYZD!", cursor = 6))
        assertEquals(k(0 to 50, 6 to 20), TextKerns.edited(tail, "AB CD!", "AB XYZD!", cursor = 6))
    }

    @Test
    fun theCursorSettlesAnEditInARunOfEqualCharacters() {
        // "aab" → "aaab": typed at 1 (cursor 2), not at 2.
        assertEquals(TextKerns.Edit(1, 0, 1), TextKerns.diff("aab", "aaab", cursor = 2))
        // Without a cursor the common start wins.
        assertEquals(TextKerns.Edit(2, 0, 1), TextKerns.diff("aab", "aaab"))
        // Backspace in "aaa" with the cursor left at 1: the second a went.
        assertEquals(TextKerns.Edit(1, 1, 0), TextKerns.diff("aaa", "aa", cursor = 1))
        assertNull(TextKerns.diff("same", "same", cursor = 2))
        // So the kern after the first "a" stays with it.
        assertEquals(k(0 to 90, 3 to -10), TextKerns.edited(k(0 to 90, 2 to -10), "aab+", "aaab+", cursor = 2))
    }

    @Test
    fun editedKernsAreSanitized() {
        val kerns = k(0 to -80, 1 to -120)
        // Everything but the first character deleted: no gap is left.
        assertEquals(emptyList<TextKern>(), TextKerns.edited(kerns, "WAV", "W", cursor = 1))
        // Unchanged text: the same list.
        assertSame(kerns, TextKerns.edited(kerns, "WAV", "WAV", cursor = 1))
        val out = TextKerns.edited(k(0 to 10, 3 to 20, 5 to 30), "abcdefg", "abXYcdefg", cursor = 4)
        assertSame(out, TextKern.sanitized(out, 9))
    }

    @Test
    fun slicesAndShiftsReindex() {
        val story = k(1 to 10, 4 to 20, 5 to 30, 9 to 40)
        // Frame [4, 6): only the gap inside it (after 4).
        assertEquals(k(0 to 20), TextKerns.slice(story, 4, 6))
        assertEquals(k(3 to 10, 6 to 20), TextKerns.shifted(k(1 to 10, 4 to 20), 2))
    }

    @Test
    fun theCursorAndTheSelectionNameGaps() {
        // A cursor between two characters names the gap before it.
        assertEquals(1..1, TextKerns.gaps(2, 2, 4))
        // At either end of the text there is no gap.
        assertNull(TextKerns.gaps(0, 0, 4))
        assertNull(TextKerns.gaps(4, 4, 4))
        // A selection names every gap inside it, either direction.
        assertEquals(0..2, TextKerns.gaps(0, 4, 4))
        assertEquals(1..2, TextKerns.gaps(4, 1, 4))
        // One selected character holds no gap.
        assertNull(TextKerns.gaps(1, 2, 4))
    }

    @Test
    fun aRangeIsSetAndReadAsOneValueOrMixed() {
        var kerns = TextKerns.withValue(emptyList(), 1..3, -50, 6)
        assertEquals(k(1 to -50, 2 to -50, 3 to -50), kerns)
        assertEquals(-50, TextKerns.commonValue(kerns, 1..3))
        assertEquals(0, TextKerns.commonValue(kerns, 4..4))
        kerns = TextKerns.withValue(kerns, 2..2, 80, 6)
        assertNull(TextKerns.commonValue(kerns, 1..3))
        assertEquals(80, TextKerns.valueAt(kerns, 2))
        // Some gaps kerned, some not: mixed too.
        assertNull(TextKerns.commonValue(kerns, 2..4))
        // 0 clears.
        assertEquals(k(1 to -50, 3 to -50), TextKerns.withValue(kerns, 2..2, 0, 6))
        // Clamped, and kept inside the text.
        assertEquals(k(3 to TextKern.MAX_VALUE), TextKerns.withValue(emptyList(), 3..9, 5000, 5))
    }

    @Test
    fun onlyUsableKernsReachTheRenderer() {
        // −200 at a 100 px font is 20 px.
        val px = TextKerns.advancesPx("AV", k(0 to -200), 0, 100f, supported = true)!!
        assertArrayEquals(floatArrayOf(-20f, 0f), px, 1e-4f)
        // Not around a line break, not inside a cluster (e + combining acute), not in shaping scripts.
        assertNull(TextKerns.advancesPx("A\nV", k(0 to 100, 1 to 100), 0, 100f, true))
        assertNull(TextKerns.advancesPx("éx", k(0 to 100), 0, 100f, true))
        assertArrayEquals(floatArrayOf(0f, 10f, 0f), TextKerns.advancesPx("éx", k(1 to 100), 0, 100f, true)!!, 1e-4f)
        assertNull(TextKerns.advancesPx("AV", k(0 to 100), 0, 100f, supported = false))
        // A story's tail from 3: story kern 4 is the tail's gap 1; scaled letters scale it.
        val tail = TextKerns.advancesPx("abcd", k(1 to 500, 4 to 100), 3, 50f, true, factors = FloatArray(7) { if (it == 4) 0.5f else 1f })!!
        assertArrayEquals(floatArrayOf(0f, 2.5f, 0f, 0f), tail, 1e-4f)
    }
}
