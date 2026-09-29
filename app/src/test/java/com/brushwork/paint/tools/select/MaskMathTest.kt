package com.brushwork.paint.tools.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MaskMathTest {
    private fun ByteArray.at(w: Int, x: Int, y: Int) = this[y * w + x].toInt() and 0xFF

    @Test
    fun growSinglePixelMakesAntiAliasedDisc() {
        val w = 21; val h = 21
        val m = ByteArray(w * h).also { it[10 * w + 10] = -1 }
        val g = MaskMath.grow(m, w, h, 3)
        assertEquals(255, g.at(w, 10, 10))
        assertEquals(255, g.at(w, 13, 10))
        assertEquals(0, g.at(w, 14, 10))
        assertEquals(255, g.at(w, 12, 12))            // d = 2.83 -> 3 + 1 - 2.83 >= 1
        assertEquals(101, g.at(w, 13, 12))            // d = 3.61 -> 0.394 * 255
        assertEquals(0, g.at(w, 13, 13))              // d = 4.24
    }

    @Test
    fun shrinkSquareByTwo() {
        val w = 20; val h = 20
        val m = ByteArray(w * h) { i -> if (i % w in 4 until 16 && i / w in 4 until 16) -1 else 0 }
        val s = MaskMath.shrink(m, w, h, 2)
        var full = 0; var partial = 0
        for (v in s) { val u = v.toInt() and 0xFF; if (u == 255) full++ else if (u > 0) partial++ }
        assertEquals(64, full)            // 8 x 8 core
        assertEquals(0, partial)          // axis-aligned edges land exactly on pixel borders
        assertEquals(0, s.at(w, 5, 10))
        assertEquals(255, s.at(w, 6, 10))
        assertEquals(255, s.at(w, 6, 6))
    }

    @Test
    fun shrinkDoesNotEatDocumentEdges() {
        val w = 12; val h = 12
        val all = ByteArray(w * h) { -1 }
        val s = MaskMath.shrink(all, w, h, 3)
        assertTrue(s.all { it.toInt() == -1 })
    }

    @Test
    fun growThenShrinkRestoresConvexShape() {
        val w = 40; val h = 40
        val m = ByteArray(w * h) { i -> if (i % w in 12 until 28 && i / w in 10 until 30) -1 else 0 }
        val back = MaskMath.shrink(MaskMath.grow(m, w, h, 4), w, h, 4)
        var diff = 0
        for (i in m.indices) if (((m[i].toInt() and 0xFF) >= 128) != ((back[i].toInt() and 0xFF) >= 128)) diff++
        // Rounded corners may differ by a few pixels; edges must match.
        assertTrue("diff=$diff", diff <= 12)
        assertEquals(255, back.at(w, 12, 20))
        assertEquals(0, back.at(w, 11, 20))
    }

    @Test
    fun featherIsSymmetricAndPreservesMass() {
        val w = 60; val h = 20
        val m = ByteArray(w * h) { i -> if (i % w < 30) -1 else 0 }
        val f = MaskMath.feather(m, w, h, 8f)
        for (y in 0 until h) {
            assertEquals(255, f.at(w, 0, y))
            assertEquals(0, f.at(w, 59, y))
            for (x in 1 until w) assertTrue(f.at(w, x, y) <= f.at(w, x - 1, y))
            // 50% at the original edge (between pixels 29 and 30).
            assertTrue(abs(f.at(w, 29, y) + f.at(w, 30, y) - 255) <= 4)
        }
        assertTrue(f.at(w, 26, 5) in 150..254)
        assertTrue(f.at(w, 33, 5) in 1..105)
        val before = m.sumOf { (it.toInt() and 0xFF).toLong() }
        val after = f.sumOf { (it.toInt() and 0xFF).toLong() }
        assertTrue("mass $before -> $after", abs(before - after) < before / 100)
    }

    @Test
    fun featherMarginCoversTheBlurSupport() {
        // A single selected pixel feathered in a window of featherMargin must not touch the edges.
        val r = 10f
        val margin = MaskMath.featherMargin(r)
        val w = 2 * margin + 1
        val m = ByteArray(w * w).also { it[margin * w + margin] = -1 }
        val f = MaskMath.feather(m, w, w, r)
        for (i in 0 until w) {
            assertEquals(0, f.at(w, i, 0)); assertEquals(0, f.at(w, 0, i))
            assertEquals(0, f.at(w, i, w - 1)); assertEquals(0, f.at(w, w - 1, i))
        }
    }
}
