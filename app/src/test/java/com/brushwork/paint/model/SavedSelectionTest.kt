package com.brushwork.paint.model

import android.graphics.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 F1 (item 14): a saved selection keeps exactly the coverage it was made from (soft edges
 * included), restores into documents of another size clipped, and [SavedSelection.isIntact]
 * catches damaged, truncated and padded data.
 */
@RunWith(RobolectricTestRunner::class)
class SavedSelectionTest {
    private val w = 64
    private val h = 48

    /** A soft-edged blob inside (10, 5)..(41, 30). */
    private fun sample(): Selection {
        val b = ByteArray(w * h)
        for (y in 5 until 30) for (x in 10 until 41) {
            b[y * w + x] = (((x * 7 + y * 13) % 255) + 1).toByte()
        }
        return Selection.fromBytes(b, w, h)
    }

    @Test
    fun emptySelectionsAreNotSaved() {
        assertNull(SavedSelection.of(1, "Selection 1", Selection.empty(w, h), 0))
    }

    @Test
    fun roundTripKeepsEveryCoverageValue() {
        val sel = sample()
        val saved = SavedSelection.of(3, "Selection 3", sel, 2)!!
        assertEquals(3L, saved.id)
        assertEquals("Selection 3", saved.name)
        assertEquals(2L, saved.revision)
        assertEquals(Rect(10, 5, 41, 30), saved.bounds)
        assertEquals(saved.packed.size.toLong(), saved.bytes)
        assertTrue(saved.isIntact())
        val back = saved.toSelection(w, h)
        assertArrayEquals(sel.toBytes(), back.toBytes())
        assertEquals(sel.bounds, back.bounds)
        // The full-canvas case takes no crop.
        val all = SavedSelection.of(4, "All", Selection.all(w, h), 0)!!
        assertEquals(Rect(0, 0, w, h), all.bounds)
        assertArrayEquals(Selection.all(w, h).toBytes(), all.toSelection(w, h).toBytes())
    }

    @Test
    fun restoringIntoASmallerDocumentClips() {
        val sel = sample()
        val saved = SavedSelection.of(1, "S", sel, 0)!!
        val small = saved.toSelection(20, 12)
        assertEquals(20, small.width)
        assertEquals(12, small.height)
        val full = sel.toBytes()
        val cut = small.toBytes()
        for (y in 0 until 12) for (x in 0 until 20) assertEquals("($x, $y)", full[y * w + x], cut[y * 20 + x])
        assertEquals(Selection.computeBounds(small.mask), small.bounds)
        // Wholly outside: nothing selected.
        assertTrue(saved.toSelection(8, 4).isEmpty)
    }

    @Test
    fun damagedDataIsCaught() {
        val saved = SavedSelection.of(1, "S", sample(), 0)!!
        val p = saved.packed
        fun with(bytes: ByteArray, bounds: Rect = saved.bounds) = SavedSelection(1, "S", bounds, bytes, 0)
        assertFalse("truncated", with(p.copyOf(p.size - 1)).isIntact())
        assertFalse("half", with(p.copyOf(p.size / 2)).isIntact())
        assertFalse("padded", with(p + byteArrayOf(0)).isIntact())
        assertFalse("empty", with(ByteArray(0)).isIntact())
        assertFalse("flipped", with(p.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x41).toByte() }).isIntact())
        assertFalse("other size", with(p, Rect(10, 5, 40, 30)).isIntact())
        assertFalse("no area", with(p, Rect(10, 5, 10, 30)).isIntact())
        // Damaged data still restores what it can, without throwing.
        with(p.copyOf(p.size / 2)).toSelection(w, h)
    }

    @Test
    fun renamedSharesTheData() {
        val saved = SavedSelection.of(1, "S", sample(), 5)!!
        val r = saved.renamed("Outline")
        assertEquals("Outline", r.name)
        assertEquals(saved.id, r.id)
        assertEquals(saved.revision, r.revision)
        assertSame(saved.packed, r.packed)
        assertEquals(saved.bounds, r.bounds)
        assertNotSame(saved.bounds, r.bounds)
    }

    @Test
    fun largeFlatSelectionsPackSmall() {
        val saved = SavedSelection.of(1, "All", Selection.all(512, 512), 0)!!
        assertTrue("${saved.bytes} bytes", saved.bytes < 4096)
        assertTrue(saved.isIntact())
    }
}
