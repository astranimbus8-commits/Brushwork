package com.brushwork.paint.tools.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        assertEquals(listOf(1), TextKerns.gaps(2, 2, "WAVE"))
        // At either end of the text there is no gap.
        assertNull(TextKerns.gaps(0, 0, "WAVE"))
        assertNull(TextKerns.gaps(4, 4, "WAVE"))
        // A selection names every gap inside it, either direction.
        assertEquals(listOf(0, 1, 2), TextKerns.gaps(0, 4, "WAVE"))
        assertEquals(listOf(1, 2), TextKerns.gaps(4, 1, "WAVE"))
        // One selected character holds no gap.
        assertNull(TextKerns.gaps(1, 2, "WAVE"))
    }

    @Test
    fun gapsAreBetweenWholeLettersSoNoDeadKernIsStored() {
        // "A👍🏽B": the thumbs up (2 chars) and its skin tone (2 chars) are ONE letter, 1 until 5.
        val thumb = "A👍🏽B"
        assertEquals(6, thumb.length)
        // Selecting through it names the gaps around it only, never one inside it.
        assertEquals(listOf(0, 4), TextKerns.gaps(0, 6, thumb))
        // A selection ending inside the emoji grows to all of it: the gap before it.
        assertEquals(listOf(0), TextKerns.gaps(0, 3, thumb))
        // ... one starting inside it too: the gap after it.
        assertEquals(listOf(4), TextKerns.gaps(2, 6, thumb))
        // The emoji alone (or part of it) is one letter: no gap.
        assertNull(TextKerns.gaps(1, 5, thumb))
        assertNull(TextKerns.gaps(2, 4, thumb))
        // A cursor between its base and its skin tone names nothing; beside it, its gaps.
        assertNull(TextKerns.gaps(3, 3, thumb))
        assertEquals(listOf(4), TextKerns.gaps(5, 5, thumb))
        // Setting a value on the selected gaps stores kerns that all apply.
        val set = TextKerns.withValue(emptyList(), TextKerns.gaps(0, 3, thumb)!!, 120, thumb.length)
        assertEquals(k(0 to 120), set)
        val all = TextKerns.withValue(emptyList(), TextKerns.gaps(0, 6, thumb)!!, 120, thumb.length)
        assertEquals(k(0 to 120, 4 to 120), all)
        assertTrue(all.all { TextKerns.applies(thumb, it.index) })
        val px = TextKerns.advancesPx(thumb, all, 0, 100f)!!
        assertEquals(2, px.count { it != 0f })

        // "café!" with e + combining acute (U+0301): é is one letter, 3 until 5.
        val cafe = "café!"
        assertEquals(listOf(2, 4), TextKerns.gaps(2, 6, cafe))
        // Selecting é alone, or ending between the e and its accent: no gap inside it.
        assertNull(TextKerns.gaps(3, 5, cafe))
        assertEquals(listOf(2), TextKerns.gaps(2, 4, cafe))
        assertNull(TextKerns.gaps(4, 4, cafe))
        assertEquals(k(2 to -60, 4 to -60), TextKerns.withValue(emptyList(), TextKerns.gaps(0, 6, cafe)!!.drop(2), -60, cafe.length))
        assertFalse(TextKerns.applies(cafe, 3))
        // One value or "Mixed" over the gaps named, ignoring characters inside letters.
        assertEquals(-60, TextKerns.commonValue(k(2 to -60, 4 to -60), listOf(2, 4)))
    }

    @Test
    fun aGapBesideALineBreakOrInAShapedScriptTakesNoKern() {
        val text = "AB\nCD"
        assertTrue(TextKerns.applies(text, 0))
        assertFalse(TextKerns.applies(text, 1))
        assertFalse(TextKerns.applies(text, 2))
        assertEquals(TextKerns.Refusal.LINE_BREAK, TextKerns.refusal(text, listOf(1)))
        assertEquals(TextKerns.Refusal.LINE_BREAK, TextKerns.refusal(text, TextKerns.gaps(1, 4, text)!!))
        // A selection with one gap that applies is no refusal: only that gap is edited.
        assertNull(TextKerns.refusal(text, TextKerns.gaps(0, 5, text)!!))
        assertEquals(listOf(0, 3), TextKerns.applying(text, TextKerns.gaps(0, 5, text)!!))
        // Right-to-left (Arabic, Hebrew) and shaped (Devanagari) paragraphs keep their shaping.
        val arabic = "AV\nمرحبا"
        assertEquals(TextKerns.Refusal.SCRIPT, TextKerns.refusal(arabic, TextKerns.gaps(4, 4, arabic)!!))
        assertEquals(TextKerns.Refusal.SCRIPT, TextKerns.refusal(arabic, TextKerns.gaps(1, 8, arabic)!!))
        assertEquals(listOf(0), TextKerns.applying(arabic, TextKerns.gaps(0, 8, arabic)!!))
        val hebrew = "Shalom שלום"
        assertEquals(TextKerns.Refusal.SCRIPT, TextKerns.refusal(hebrew, listOf(1)))
        assertEquals(TextKerns.Refusal.SCRIPT, TextKerns.refusal("नमस्ते", listOf(0)))
        // The same rule as the renderer's, gap by gap.
        for (t in listOf(text, arabic, hebrew, "café!", "A👍🏽B")) {
            for (g in t.indices) {
                val drawn = TextKerns.advancesPx(t, listOf(TextKern(g, 100)), 0, 100f) != null
                assertEquals("$t gap $g", drawn, TextKerns.applies(t, g))
            }
        }
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
    fun aMixedRangeIsNudgedGapByGap() {
        // −/+ on "Mixed" moves each gap by the step and keeps their differences.
        val kerns = k(0 to 5, 1 to -50, 3 to 80)
        assertEquals(k(0 to 5, 1 to -40, 2 to 10, 3 to 90), TextKerns.nudged(kerns, 1..3, 10, 6))
        // A gap the nudge brings to 0 loses its kern; gaps outside the range keep theirs.
        assertEquals(k(0 to 5, 2 to -10, 3 to 70), TextKerns.nudged(k(0 to 5, 1 to 10, 3 to 80), 1..3, -10, 6))
        // Clamped, kept inside the text, and the same list for no nudge.
        assertEquals(k(1 to TextKern.MAX_VALUE), TextKerns.nudged(k(1 to TextKern.MAX_VALUE - 5), 1..1, 10, 3))
        assertEquals(k(0 to 10, 1 to 10), TextKerns.nudged(emptyList(), 0..5, 10, 3))
        assertSame(kerns, TextKerns.nudged(kerns, 1..3, 0, 6))
    }

    @Test
    fun aCursorNoSmallestEditEndsAtIsNotUsed() {
        // The editor's whole text replaced at once (the cursor goes to its end): "x" between A and
        // V is still the edit, so the kerns of V and E stay with them.
        assertEquals(TextKerns.Edit(2, 0, 1), TextKerns.diff("WAVE", "WAxVE", cursor = 5))
        assertEquals(k(1 to -120, 3 to 40), TextKerns.edited(k(1 to -120, 2 to 40), "WAVE", "WAxVE", cursor = 5))
    }

    @Test
    fun onlyUsableKernsReachTheRenderer() {
        // −200 at a 100 px font is 20 px.
        val px = TextKerns.advancesPx("AV", k(0 to -200), 0, 100f)!!
        assertArrayEquals(floatArrayOf(-20f, 0f), px, 1e-4f)
        // Not around a line break, not inside a cluster (e + combining acute).
        assertNull(TextKerns.advancesPx("A\nV", k(0 to 100, 1 to 100), 0, 100f))
        assertNull(TextKerns.advancesPx("éx", k(0 to 100), 0, 100f))
        assertArrayEquals(floatArrayOf(0f, 10f, 0f), TextKerns.advancesPx("éx", k(1 to 100), 0, 100f)!!, 1e-4f)
        // Not in a right-to-left paragraph; the Latin paragraph next to it keeps its kern.
        val arabic = "مرحبا"
        assertNull(TextKerns.advancesPx(arabic, k(1 to 100), 0, 100f))
        val mixed = TextKerns.advancesPx("AV\n$arabic", k(0 to 100, 4 to 100), 0, 100f)!!
        assertEquals(10f, mixed[0], 1e-4f)
        assertEquals(0f, mixed[4], 1e-4f)
        // A story's tail from 3: story kern 4 is the tail's gap 1; scaled letters scale it.
        val tail = TextKerns.advancesPx("xyzabcd", k(1 to 500, 4 to 100), 3, 50f, FloatArray(7) { if (it == 4) 0.5f else 1f })!!
        assertArrayEquals(floatArrayOf(0f, 2.5f, 0f, 0f), tail, 1e-4f)
    }
}
