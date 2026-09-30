package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MaskOpsTest {

    @Test
    fun boxMeanMatchesBruteForce() {
        val w = 17; val h = 11
        val rnd = Random(1)
        val src = FloatArray(w * h) { rnd.nextFloat() }
        for (r in listOf(1, 3, 20)) {
            val fast = MaskOps.boxMean(src, w, h, r)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0.0; var n = 0
                for (yy in maxOf(0, y - r)..minOf(h - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(w - 1, x + r)) { s += src[yy * w + xx]; n++ }
                assertEquals("r=$r ($x,$y)", (s / n).toFloat(), fast[y * w + x], 1e-5f)
            }
        }
    }

    @Test
    fun boxMeanWorksInPlace() {
        val w = 9; val h = 7
        val rnd = Random(2)
        val src = FloatArray(w * h) { rnd.nextFloat() }
        val expected = MaskOps.boxMean(src, w, h, 2)
        val inPlace = src.copyOf()
        MaskOps.boxMean(inPlace, w, h, 2, inPlace, FloatArray(w * h))
        assertArrayEquals(expected, inPlace, 1e-6f)
    }

    @Test
    fun fitWithinNeverEnlarges() {
        assertArrayEquals(intArrayOf(1280, 960), MaskOps.fitWithin(4000, 3000, 1280))
        assertArrayEquals(intArrayOf(1024, 1280), MaskOps.fitWithin(4000, 5000, 1280))
        assertArrayEquals(intArrayOf(800, 600), MaskOps.fitWithin(800, 600, 1280))
        assertArrayEquals(intArrayOf(1280, 1), MaskOps.fitWithin(20000, 2, 1280))
    }

    @Test
    fun resampleAveragesAndFlattensAlpha() {
        val src = PixelBuffer(4, 2)
        src[0, 0] = 0xFF000000.toInt(); src[1, 0] = 0xFFFFFFFF.toInt(); src[0, 1] = 0xFF000000.toInt(); src[1, 1] = 0xFFFFFFFF.toInt()
        for (x in 2..3) for (y in 0..1) src[x, y] = 0x00123456 // fully transparent -> white paper
        val out = MaskOps.resample(src, 2, 1)
        assertEquals(0xFF808080.toInt(), out[0, 0])
        assertEquals(0xFFFFFFFF.toInt(), out[1, 0])
        // Enlarging degenerates to nearest neighbour.
        val up = MaskOps.resample(PixelBuffer.filled(1, 1, 0xFF336699.toInt()), 3, 2)
        assertTrue(up.pixels.all { it == 0xFF336699.toInt() })
        // Half-transparent black over white = mid gray, opaque.
        val half = MaskOps.flattenOverWhite(0x80000000.toInt())
        assertEquals(0xFF7F7F7F.toInt(), half)
        assertEquals(1f, MaskOps.luma01(0x00000000), 0f)
    }

    @Test
    fun resizeBilinearKeepsConstantsAndInterpolates() {
        val c = FloatArray(6) { 0.4f }
        assertTrue(MaskOps.resizeBilinear(c, 3, 2, 30, 20).all { kotlin.math.abs(it - 0.4f) < 1e-6f })
        val ramp = floatArrayOf(0f, 1f)
        val up = MaskOps.resizeBilinear(ramp, 2, 1, 4, 1)
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.75f, 1f), up, 1e-6f)
    }

    @Test
    fun resampleSmoothEnlargesBilinearlyAndShrinksByArea() {
        val src = PixelBuffer(2, 1)
        src[0, 0] = 0xFF000000.toInt(); src[1, 0] = 0xFFC8C8C8.toInt()
        val up = MaskOps.resampleSmooth(src, 8, 1)
        // A ramp, not two blocks: strictly increasing in the middle.
        val reds = IntArray(8) { (up[it, 0] shr 16) and 0xFF }
        assertEquals(0, reds[0]); assertEquals(200, reds[7])
        for (x in 2..5) assertTrue("x=$x ${reds.toList()}", reds[x] > reds[x - 1])
        // Shrinking is the area average (same as resample).
        val big = PixelBuffer(4, 2, IntArray(8) { if (it % 2 == 0) 0xFF000000.toInt() else -1 })
        assertArrayEquals(MaskOps.resample(big, 2, 1).pixels, MaskOps.resampleSmooth(big, 2, 1).pixels)
        // Transparent pixels are white paper here too.
        assertEquals(-1, MaskOps.resampleSmooth(PixelBuffer.filled(1, 1, 0), 3, 3)[1, 1])
    }

    @Test
    fun cropRepeatsEdgesOutsideTheImage() {
        val src = PixelBuffer(3, 2, IntArray(6) { i -> SegTestImages.rgb(i * 10, 0, 0) })
        val c = MaskOps.crop(src, -1, 0, 4, 2)
        assertEquals(5, c.width); assertEquals(2, c.height)
        assertEquals(src[0, 0], c[0, 0]) // repeated left edge
        assertEquals(src[0, 0], c[1, 0])
        assertEquals(src[2, 1], c[4, 1]) // repeated right edge
        val plane = FloatArray(12) { it.toFloat() }
        assertArrayEquals(floatArrayOf(5f, 6f, 9f, 10f), MaskOps.cropPlane(plane, 4, 1, 1, 3, 3), 0f)
    }

    @Test
    fun resizeAreaAveragesBlocks() {
        val src = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 1f)
        assertArrayEquals(floatArrayOf(0.25f, 1f), MaskOps.resizeArea(src, 4, 2, 2, 1), 1e-6f)
        // Enlarging falls back to bilinear.
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.75f, 1f), MaskOps.resizeArea(floatArrayOf(0f, 1f), 2, 1, 4, 1), 1e-6f)
    }

    @Test
    fun clamp01MapsNaNToZero() {
        assertEquals(0f, MaskOps.clamp01(Float.NaN), 0f)
        assertEquals(0f, MaskOps.clamp01(-0.5f), 0f)
        assertEquals(1f, MaskOps.clamp01(1.5f), 0f)
        assertEquals(1f, MaskOps.clamp01(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.3f, MaskOps.clamp01(0.3f), 0f)
    }

    @Test
    fun contentHashSeesEveryPixel() {
        val a = PixelBuffer.filled(50, 40, 0xFF102030.toInt())
        val b = a.copy().also { it[49, 39] = 0xFF102031.toInt() }
        assertEquals(MaskOps.contentHash(a), MaskOps.contentHash(a.copy()))
        assertNotEquals(MaskOps.contentHash(a), MaskOps.contentHash(b))
        assertNotEquals(MaskOps.contentHash(PixelBuffer(10, 20)), MaskOps.contentHash(PixelBuffer(20, 10)))
        // Segmentation treats transparency as white paper: same pixels to it, same key.
        val clear = a.copy().also { it[3, 3] = 0x00000000; it[4, 4] = 0x80FF0000.toInt() }
        val white = a.copy().also { it[3, 3] = -1; it[4, 4] = MaskOps.flattenOverWhite(0x80FF0000.toInt()) }
        assertEquals(MaskOps.contentHash(white), MaskOps.contentHash(clear))
        assertNotEquals(MaskOps.contentHash(a), MaskOps.contentHash(clear))
    }
}
