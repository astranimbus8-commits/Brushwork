package com.brushwork.paint.v19.a3

import android.graphics.Paint
import android.graphics.Typeface
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.tools.text.OpticalKerning
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 4 (R5 c): the "Optical" pair kerning's letter profiles are a display cache.
 *
 * - A letter is measured once per font (at [OpticalKerning.REF_PX], whatever the text size) and
 *   the same profile is measured again after the cache is emptied.
 * - The cache keeps [OpticalKerning.CACHE_SIZE] profiles, the least recently used dropped first.
 * - A word's kerns are kept per font, size and letter spacing (a text laid out again measures
 *   nothing; typed into, only its new word), in an LRU of [OpticalKerning.WORD_CACHE_SIZE].
 * - It is registered with [MemoryGovernor] at priority 0 with its bytes; its trim halves it on
 *   MODERATE pressure and empties it on LOW and CRITICAL.
 */
@RunWith(RobolectricTestRunner::class)
class OpticalKerningCacheTest {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f }

    @Before
    fun empty() {
        OpticalKerning.trim(MemoryPressure.CRITICAL)
    }

    @After
    fun emptyAfter() {
        OpticalKerning.trim(MemoryPressure.CRITICAL)
    }

    private fun entry(): MemoryGovernor.Entry = MemoryGovernor.entries().single { it.name == OpticalKerning.GOVERNOR_NAME }

    @Test
    fun aLetterIsMeasuredOncePerFont() {
        val a = OpticalKerning.profile(paint, "A")
        assertEquals(2 * OpticalKerning.BANDS, a.size)
        assertEquals(1, OpticalKerning.size())
        assertSame("cached", a, OpticalKerning.profile(paint, "A"))
        assertSame("any text size shares it", a, OpticalKerning.profile(Paint(paint).apply { textSize = 96f }, "A"))
        assertEquals(1, OpticalKerning.size())
        OpticalKerning.profile(Paint(paint).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }, "A")
        assertEquals("another font, another profile", 2, OpticalKerning.size())
        // Emptied, the letter is measured again, the same.
        OpticalKerning.trim(MemoryPressure.CRITICAL)
        assertEquals(0, OpticalKerning.size())
        val again = OpticalKerning.profile(paint, "A")
        assertNotSame(a, again)
        assertArrayEquals("deterministic", a, again)
    }

    @Test
    fun theCacheIsABoundedLruTheGovernorTrims() {
        val e = entry()
        assertEquals("a display cache: trimmed first", 0, e.priority)
        assertEquals(0L, e.bytes())
        val first = OpticalKerning.profile(paint, "A")
        assertEquals(OpticalKerning.BYTES_PER_PROFILE, e.bytes())

        // More letters than it keeps (CJK ideographs, measured or not by the test's fonts).
        val extra = 100
        for (k in 0 until OpticalKerning.CACHE_SIZE + extra) OpticalKerning.profile(paint, String(Character.toChars(0x4E00 + k)))
        assertEquals("bounded", OpticalKerning.CACHE_SIZE, OpticalKerning.size())
        assertEquals(OpticalKerning.CACHE_SIZE * OpticalKerning.BYTES_PER_PROFILE, e.bytes())
        assertTrue("well under a megabyte: ${e.bytes()}", e.bytes() < 1_000_000L)
        // The least recently used went first: "A" and the first ideographs; the last ones stay.
        val last = String(Character.toChars(0x4E00 + OpticalKerning.CACHE_SIZE + extra - 1))
        val kept = OpticalKerning.profile(paint, last)
        assertSame(kept, OpticalKerning.profile(paint, last))
        assertEquals(OpticalKerning.CACHE_SIZE, OpticalKerning.size())
        assertNotSame("\"A\" was dropped and is measured again", first, OpticalKerning.profile(paint, "A"))

        // NORMAL frees nothing; MODERATE drops the older half; CRITICAL empties it.
        assertEquals(0L, e.trimmable.trim(MemoryPressure.NORMAL))
        assertEquals(OpticalKerning.CACHE_SIZE, OpticalKerning.size())
        val freed = e.trimmable.trim(MemoryPressure.MODERATE)
        assertEquals(OpticalKerning.CACHE_SIZE / 2, OpticalKerning.size())
        assertEquals(OpticalKerning.CACHE_SIZE / 2 * OpticalKerning.BYTES_PER_PROFILE, freed)
        assertSame("the most recent stay", kept, OpticalKerning.profile(paint, last))
        assertTrue(e.trimmable.trim(MemoryPressure.CRITICAL) > 0L)
        assertEquals(0, OpticalKerning.size())
        assertEquals(0L, e.bytes())
        // LOW empties it too.
        OpticalKerning.profile(paint, "V")
        e.trimmable.trim(MemoryPressure.LOW)
        assertEquals(0, OpticalKerning.size())
    }

    @Test
    fun wordsAreKeptPerSizeAndTheGovernorTrimsThem() {
        val e = entry()
        val first = OpticalKerning.advancesPx("AVATAR Tony", 0, paint, null)
        assertEquals("two words", 2, OpticalKerning.wordCount())
        assertEquals(OpticalKerning.size() * OpticalKerning.BYTES_PER_PROFILE + 2 * OpticalKerning.BYTES_PER_WORD, e.bytes())
        // Laid out again: nothing new, the same kerns.
        val profiles = OpticalKerning.size()
        assertArrayEquals(first, OpticalKerning.advancesPx("AVATAR Tony", 0, paint, null), 0f)
        assertEquals(2, OpticalKerning.wordCount())
        assertEquals(profiles, OpticalKerning.size())
        // Typed into: one new word. Another size: its own words.
        OpticalKerning.advancesPx("AVATAR Tony!", 0, paint, null)
        assertEquals(3, OpticalKerning.wordCount())
        OpticalKerning.advancesPx("AVATAR Tony", 0, Paint(paint).apply { textSize = 41f }, null)
        assertEquals(5, OpticalKerning.wordCount())
        // A word alone is the same in any text (its pairs never reach across a space).
        val alone = OpticalKerning.advancesPx("Tony", 0, paint, null)!!
        assertArrayEquals(first!!.copyOfRange(7, 11), alone, 0f)

        // Bounded: more words than it keeps.
        for (k in 0 until OpticalKerning.WORD_CACHE_SIZE + 50) OpticalKerning.advancesPx("T$k", 0, paint, null)
        assertEquals(OpticalKerning.WORD_CACHE_SIZE, OpticalKerning.wordCount())
        e.trimmable.trim(MemoryPressure.MODERATE)
        assertEquals(OpticalKerning.WORD_CACHE_SIZE / 2, OpticalKerning.wordCount())
        e.trimmable.trim(MemoryPressure.CRITICAL)
        assertEquals(0, OpticalKerning.wordCount())
        assertEquals(0L, e.bytes())
    }
}
