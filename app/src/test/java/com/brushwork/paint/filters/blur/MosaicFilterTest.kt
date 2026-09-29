package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class MosaicFilterTest {
    private val mosaic = MosaicFilter()

    @Test
    fun cellsAreUniformAndHoldThePremultipliedAverage() {
        val w = 43; val h = 30; val cell = 8
        val src = randomImage(w, h, seed = 11)
        val out = mosaic.render(src, "size" to cell.toFloat())
        for (gy in 0 until (h + cell - 1) / cell) for (gx in 0 until (w + cell - 1) / cell) {
            val xs = gx * cell until minOf(w, gx * cell + cell)
            val ys = gy * cell until minOf(h, gy * cell + cell)
            var sa = 0.0; var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
            for (y in ys) for (x in xs) {
                val c = src[x, y]; val a = alpha(c).toDouble()
                sa += a; sr += ColorUtils.red(c) * a; sg += ColorUtils.green(c) * a; sb += ColorUtils.blue(c) * a; n++
            }
            val expected = ColorUtils.argb((sa / n).roundToInt(), (sr / sa).roundToInt(), (sg / sa).roundToInt(), (sb / sa).roundToInt())
            for (y in ys) for (x in xs) {
                assertTrue("cell ($gx,$gy) pixel ($x,$y)", channelDiff(expected, out[x, y]) <= 1)
                assertEquals("uniform cell", out[xs.first, ys.first], out[x, y])
            }
        }
    }

    @Test
    fun transparentPixelsDoNotDarkenTheCell() {
        // Two opaque red pixels and two transparent (black) ones -> half-transparent pure red.
        val src = PixelBuffer(2, 2, intArrayOf(OPAQUE_RED, 0, 0x00000000, OPAQUE_RED))
        val out = mosaic.render(src, "size" to 2f)
        for (c in out.pixels) {
            assertEquals(128, alpha(c))
            assertEquals(0xFF0000, c and 0xFFFFFF)
        }
        val empty = mosaic.render(PixelBuffer(9, 9), "size" to 4f)
        assertTrue(empty.pixels.all { it == 0 })
    }

    @Test
    fun centeredGridIsSymmetric() {
        val w = 50; val h = 21
        val base = randomImage(w, h, seed = 5, opaque = true)
        val src = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) src[x, y] = base[minOf(x, w - 1 - x), y]
        val out = mosaic.render(src, "size" to 16f, "grid" to 1)
        for (y in 0 until h) for (x in 0 until w) assertEquals("($x,$y)", out[x, y], out[w - 1 - x, y])
        // A cell is centred on the image centre: columns 17..32 form one cell.
        assertEquals(out[17, 10], out[32, 10])
        assertTrue(out[16, 10] != out[17, 10] || out[33, 10] != out[32, 10])
    }

    @Test
    fun fractionalCellsAtPreviewScaleAndSizeOneIdentity() {
        val src = randomImage(20, 20, seed = 2)
        assertSameImage("size 1", src, mosaic.render(src, "size" to 1f))
        // 10 px cells at scale 0.25 -> 2.5 px cells: widths alternate 2 and 3 but stay uniform.
        val out = mosaic.render(src, "size" to 10f, scale = 0.25f)
        assertEquals(out[0, 0], out[1, 1])
        assertEquals(out[2, 0], out[4, 1])
    }
}
