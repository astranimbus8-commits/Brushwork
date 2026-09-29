package com.brushwork.paint.tools.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RegionFillTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun fill(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, params: RegionParams, clip: ByteArray? = null): Region? {
        val map = RegionFill.buildMap(px, w, h, sx, sy, params.tolerance, clip)
        return RegionFill.compute(map, w, h, sx, sy, params, clip)
    }

    private fun Region.countFull(): Int = coverage.count { it.toInt() == -1 }

    @Test
    fun colorDistanceIsMaxChannelDifference() {
        assertEquals(0, RegionFill.colorDistance(red, red))
        assertEquals(255, RegionFill.colorDistance(black, white))
        assertEquals(30, RegionFill.colorDistance(0xFF102030.toInt(), 0xFF2E2030.toInt()))
        assertEquals(40, RegionFill.colorDistance(0xFF808080.toInt(), 0xD7808080.toInt()))
        // Fully transparent pixels are all equal, whatever their stored RGB.
        assertEquals(0, RegionFill.colorDistance(0x00FF0000, 0x0000FF00))
    }

    @Test
    fun toleranceLimitsSimilarPixels() {
        val w = 26; val h = 2
        val px = IntArray(w * h) { gray((it % w) * 10) }
        val r = fill(px, w, h, 0, 0, RegionParams(tolerance = 25, antiAlias = false))!!
        for (x in 0 until w) {
            val expected = if (x <= 2) 255 else 0
            assertEquals("x=$x", expected, r.at(x, 0))
            assertEquals("x=$x", expected, r.at(x, 1))
        }
        val r0 = fill(px, w, h, 5, 0, RegionParams(tolerance = 0, antiAlias = false))!!
        assertEquals(2, r0.countFull())
        val all = fill(px, w, h, 5, 0, RegionParams(tolerance = 255, antiAlias = false))!!
        assertEquals(w * h, all.countFull())
    }

    @Test
    fun contiguousVersusGlobal() {
        val w = 20; val h = 8
        val px = IntArray(w * h) { white }
        for (y in 2 until 6) for (x in 2 until 6) { px[y * w + x] = red; px[y * w + x + 10] = red }
        val contiguous = fill(px, w, h, 3, 3, RegionParams(tolerance = 10, contiguous = true, antiAlias = false))!!
        assertEquals(16, contiguous.countFull())
        assertEquals(0, contiguous.at(13, 3))
        val global = fill(px, w, h, 3, 3, RegionParams(tolerance = 10, contiguous = false, antiAlias = false))!!
        assertEquals(32, global.countFull())
        assertEquals(255, global.at(13, 3))
        assertEquals(0, global.at(8, 3))
    }

    /** A 2 px thick ring of radius ~12 with a gap at the right side, on a transparent layer. */
    private fun ringWithGap(w: Int, h: Int, gap: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val d = hypot(x + 0.5 - 20.0, y + 0.5 - 20.0)
            val inGap = x > 25 && kotlin.math.abs(y + 0.5 - 20.0) < gap / 2.0
            if (d in 11.0..13.0 && !inGap) px[y * w + x] = black
        }
        return px
    }

    @Test
    fun gapClosingKeepsFillInsideBrokenOutline() {
        val w = 40; val h = 40
        val px = ringWithGap(w, h, gap = 3)
        val leak = fill(px, w, h, 20, 20, RegionParams(tolerance = 10, antiAlias = false))!!
        assertEquals("without gap closing the fill leaks outside", 255, leak.at(0, 0))

        val closed = fill(px, w, h, 20, 20, RegionParams(tolerance = 10, gapClose = 2, antiAlias = false))!!
        assertEquals(0, closed.at(0, 0))
        assertEquals(0, closed.at(39, 20))
        var inside = 0; var filled = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (hypot(x + 0.5 - 20.0, y + 0.5 - 20.0) < 10.5) { inside++; if (closed.at(x, y) == 255) filled++ }
        }
        assertTrue("interior mostly filled ($filled / $inside)", filled > inside * 0.97)
        // The ring's outer edge is at x ~ 33; at most a pixel or two may bleed past the gap.
        for (y in 0 until h) for (x in 35 until w) assertEquals("($x,$y)", 0, closed.at(x, y))
    }

    @Test
    fun gapClosingWorksWhenTappingNextToTheLine() {
        val w = 40; val h = 40
        val px = ringWithGap(w, h, gap = 3)
        // (20, 10) is inside the ring, 1 px from the line: within the eroded band.
        val closed = fill(px, w, h, 20, 10, RegionParams(tolerance = 10, gapClose = 2, antiAlias = false))!!
        assertEquals(0, closed.at(0, 0))
        assertEquals(255, closed.at(20, 20))
    }

    @Test
    fun expandGrowsUnderTheBarrier() {
        val w = 30; val h = 10
        val px = IntArray(w * h) { if (it % w >= 15) black else 0 }
        val r = fill(px, w, h, 2, 5, RegionParams(tolerance = 0, expand = 3, antiAlias = false))!!
        assertEquals(255, r.at(14, 5))
        assertEquals(255, r.at(17, 5))
        assertEquals(0, r.at(18, 5))
        val aa = fill(px, w, h, 2, 5, RegionParams(tolerance = 0, expand = 3, antiAlias = true))!!
        assertEquals(255, aa.at(17, 5))
        assertEquals(0, aa.at(18, 5)) // distance 4 = expand + 1 -> 0 coverage
    }

    @Test
    fun antiAliasAddsSoftRing() {
        val w = 30; val h = 10
        val px = IntArray(w * h) { if (it % w >= 15) black else 0 }
        val r = fill(px, w, h, 2, 5, RegionParams(tolerance = 0, antiAlias = true))!!
        assertEquals(255, r.at(14, 5))
        assertEquals(3 * 255 / 8, r.at(15, 5))
        assertEquals(0, r.at(16, 5))
        val hard = fill(px, w, h, 2, 5, RegionParams(tolerance = 0, antiAlias = false))!!
        assertEquals(0, hard.at(15, 5))
    }

    @Test
    fun clipLimitsTheFillAndBlocksOutsideTaps() {
        val w = 10; val h = 10
        val px = IntArray(w * h) { white }
        val clip = ByteArray(w * h) { if (it / w < 5) -1 else if (it / w == 5) 100 else 0 }
        val r = fill(px, w, h, 3, 2, RegionParams(tolerance = 0, antiAlias = false), clip)!!
        assertEquals(255, r.at(3, 0))
        assertEquals(100, r.at(3, 5))
        assertEquals(0, r.at(3, 6))
        assertNull(fill(px, w, h, 3, 8, RegionParams(tolerance = 0), clip))
    }

    @Test
    fun largeCanvasFloodFillIsFast() {
        val w = 4000; val h = 4000
        val map = ByteArray(w * h) { RegionFill.PASSABLE.toByte() }
        // A wall with one hole so the fill has to snake around.
        for (y in 0 until h - 10) map[y * w + 2000] = 0
        val t0 = System.nanoTime()
        val r = RegionFill.compute(map, w, h, 10, 10, RegionParams(tolerance = 0, antiAlias = true))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull(r)
        assertEquals(255, r!!.at(3999, 0))
        assertTrue("took $ms ms", ms < 5000)
    }
}
