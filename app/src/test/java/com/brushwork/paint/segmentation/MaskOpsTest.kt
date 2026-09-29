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
    fun guidedUpsampleEvaluatesAtFullResolution() {
        // Identity coefficients (a = 1, b = 0) reproduce the full-resolution luma exactly.
        val img = PixelBuffer(6, 4)
        for (y in 0 until 4) for (x in 0 until 6) img[x, y] = SegTestImages.rgb(x * 40, x * 40, x * 40)
        val q = MaskOps.guidedUpsample(img, FloatArray(6) { 1f }, FloatArray(6) { 0f }, 3, 2)
        assertEquals(24, q.size)
        for (y in 0 until 4) for (x in 0 until 6) assertEquals(x * 40 / 255f, q[y * 6 + x], 1e-3f)
        // Clamped to 0..1.
        val clamped = MaskOps.guidedUpsample(img, FloatArray(6) { 5f }, FloatArray(6) { -0.5f }, 3, 2)
        assertTrue(clamped.all { it in 0f..1f })
    }

    @Test
    fun refineBandMovesAMisalignedBoundaryToTheColorEdge() {
        val w = 96; val h = 32
        val blue = SegTestImages.rgb(40, 90, 200); val green = SegTestImages.rgb(40, 160, 60)
        val img = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) img[x, y] = if (x < 40) blue else green
        // Coarse mask of "blue" that overshoots by 6 px.
        val m = FloatArray(w * h) { if (it % w < 46) 1f else 0f }
        val refined = MaskOps.refineBand(img, m, radius = 8)
        for (y in 0 until h) {
            for (x in 0 until 38) assertEquals("x=$x", 1f, refined[y * w + x], 0.02f)
            for (x in 40 until 46) assertTrue("x=$x ${refined[y * w + x]}", refined[y * w + x] < 0.1f)
            for (x in 56 until w) assertEquals(0f, refined[y * w + x], 1e-6f)
        }
    }

    @Test
    fun refineBandKeepsTheMaskWhenColorsDoNotDiffer() {
        val w = 64; val h = 8
        val img = PixelBuffer.filled(w, h, 0xFF808080.toInt())
        val m = FloatArray(w * h) { if (it % w < 30) 1f else 0f }
        assertArrayEquals(m, MaskOps.refineBand(img, m, radius = 6), 1e-6f)
    }

    @Test
    fun refineBandFusesExtraEvidenceOnlyNearTheBoundary() {
        val w = 64; val h = 8
        val img = PixelBuffer.filled(w, h, 0xFF808080.toInt())
        val m = FloatArray(w * h) { if (it % w < 30) 1f else 0f }
        val extra = FloatArray(w * h) { 1f } // e.g. a heuristic that says "everything"
        val out = MaskOps.refineBand(img, m, radius = 4, extra = extra)
        assertTrue(out[4 * w + 32] > 0.9f) // inside the band: added
        assertEquals(0f, out[4 * w + 50], 0f) // far away: untouched
    }

    @Test
    fun contentHashSeesEveryPixel() {
        val a = PixelBuffer.filled(50, 40, 0xFF102030.toInt())
        val b = a.copy().also { it[49, 39] = 0xFF102031.toInt() }
        assertEquals(MaskOps.contentHash(a), MaskOps.contentHash(a.copy()))
        assertNotEquals(MaskOps.contentHash(a), MaskOps.contentHash(b))
        assertNotEquals(MaskOps.contentHash(PixelBuffer(10, 20)), MaskOps.contentHash(PixelBuffer(20, 10)))
    }
}
