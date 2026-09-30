package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MattingTest {
    private val blue = SegTestImages.rgb(40, 90, 200)
    private val green = SegTestImages.rgb(40, 150, 60)
    private val white = SegTestImages.rgb(245, 245, 250)
    private val dark = SegTestImages.rgb(30, 70, 35)

    private fun image(w: Int, h: Int, f: (x: Int, y: Int) -> Int) = PixelBuffer(w, h).also { img ->
        for (y in 0 until h) for (x in 0 until w) img[x, y] = f(x, y)
    }

    /** A coarse model probability: 1 left of [at] - 3, 0 right of [at] + 3, linear between. */
    private fun ramp(w: Int, h: Int, at: Int) = FloatArray(w * h) { MaskOps.clamp01((at + 3 - (it % w)) / 6f) }

    @Test
    fun bandMattingMovesAMisalignedBoundaryToTheColorEdge() {
        val w = 96; val h = 32
        val img = image(w, h) { x, _ -> if (x < 40) blue else green }
        val a = Matting.refine(ColorPlanes.of(img), ramp(w, h, 46), Matting.Params(band = 8f, rounds = 2, radius = 2))
        for (y in 0 until h) {
            for (x in 0 until 38) assertEquals("x=$x", 1f, a[y * w + x], 0.02f)
            for (x in 41 until 47) assertTrue("x=$x ${a[y * w + x]}", a[y * w + x] < 0.1f)
            for (x in 60 until w) assertEquals(0f, a[y * w + x], 1e-6f)
        }
    }

    @Test
    fun colorsThatDoNotDifferKeepTheModelProbability() {
        val w = 64; val h = 8
        val img = PixelBuffer.filled(w, h, SegTestImages.GRAY_BG)
        val p = ramp(w, h, 30)
        val a = Matting.refine(ColorPlanes.of(img), p, Matting.Params(band = 6f, rounds = 1, radius = 2))
        // No color evidence: the edge stays where the model put it (only smoothed a little).
        val row = 4 * w
        assertTrue(a[row + 29] > 0.5f && a[row + 31] < 0.5f)
        for (x in 0 until w) assertEquals("x=$x", p[row + x], a[row + x], 0.15f)
        for (x in 0 until 20) assertEquals(1f, a[row + x], 0f)
        for (x in 40 until w) assertEquals(0f, a[row + x], 0f)
    }

    @Test
    fun multimodalForegroundKeepsCloudsInTheSky() {
        // Sky = blue with white cloud stripes; the model's edge overshoots 5 px into the trees.
        val w = 96; val h = 48
        val img = image(w, h) { x, y -> if (x < 40) (if ((y / 4) % 2 == 0) white else blue) else dark }
        val a = Matting.refine(ColorPlanes.of(img), ramp(w, h, 45), Matting.Params(band = 8f, rounds = 2, radius = 2))
        for (y in 4 until h - 4) {
            for (x in 34 until 40) assertTrue("cloud/sky x=$x y=$y ${a[y * w + x]}", a[y * w + x] > 0.85f)
            for (x in 41 until 46) assertTrue("tree x=$x y=$y ${a[y * w + x]}", a[y * w + x] < 0.15f)
        }
    }

    @Test
    fun trimapBandWidensInTexturedAreas() {
        val w = 120; val h = 40
        val rnd = Random(4)
        val img = image(w, h) { _, y ->
            if (y < 20) SegTestImages.noisy(SegTestImages.FOLIAGE, 60, rnd) else SegTestImages.FACADE
        }
        val planes = ColorPlanes.of(img)
        val p = FloatArray(w * h) { if (it % w < 60) 1f else 0f }
        val params = Matting.Params(band = 6f, widen = 1f)
        val tri = Matting.trimap(p, w, h, Matting.colorTexture(planes, 2), params)
        fun unknownWidth(y: Int) = (0 until w).count { tri[y * w + it] == Matting.UNKNOWN }
        val flat = unknownWidth(30); val textured = unknownWidth(10)
        assertTrue("flat band $flat", flat in 10..14)
        assertTrue("textured band $textured vs flat $flat", textured >= flat + 6)
        assertEquals(Matting.FOREGROUND, tri[30 * w + 5])
        assertEquals(Matting.BACKGROUND, tri[30 * w + 100])
    }

    @Test
    fun skyColorReachFindsSkyBetweenBranches() {
        // Sky above row 20; below it dark foliage with sky-colored gaps the coarse model missed:
        // one gap 6 px below the sky edge, one 30 px below (too far to be trusted).
        val w = 80; val h = 80
        fun gapNear(x: Int, y: Int) = x in 20..25 && y in 26..29
        fun gapFar(x: Int, y: Int) = x in 50..55 && y in 50..53
        val img = image(w, h) { x, y -> if (y < 20 || gapNear(x, y) || gapFar(x, y)) SegTestImages.SKY_BLUE else dark }
        val p = FloatArray(w * h) { if (it / w < 20) 1f else 0f }
        val params = Matting.Params(band = 3f, globalColorModel = true, colorReach = 12f, rounds = 2, radius = 1)
        val a = Matting.refine(ColorPlanes.of(img), p, params)
        assertTrue("near gap ${a[27 * w + 22]}", a[27 * w + 22] > 0.6f)
        assertTrue("far gap ${a[51 * w + 52]}", a[51 * w + 52] < 0.1f)
        assertTrue("foliage beside the gap ${a[27 * w + 10]}", a[27 * w + 10] < 0.15f)
        assertTrue(a[5 * w + 40] > 0.98f)
    }

    @Test
    fun emptyAndFullMasksShortCircuit() {
        val img = ColorPlanes.of(PixelBuffer.filled(10, 10, SegTestImages.RED))
        val params = Matting.Params(band = 3f)
        assertTrue(Matting.refine(img, FloatArray(100) { 0.2f }, params).all { it == 0f })
        assertTrue(Matting.refine(img, FloatArray(100) { 0.7f }, params).all { it == 1f })
    }
}
