package com.brushwork.paint.filters.art

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.art.ArtTestUtil.a
import com.brushwork.paint.filters.art.ArtTestUtil.b
import com.brushwork.paint.filters.art.ArtTestUtil.g
import com.brushwork.paint.filters.art.ArtTestUtil.r
import com.brushwork.paint.filters.art.ArtTestUtil.run
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChromaticNoiseTest {
    private val moving = ChromaticAberrationMovingFilter()
    private val zooming = ChromaticAberrationZoomingFilter()
    private val noise = NoiseFilter()

    /** Opaque black image with a white vertical line at x = 10. */
    private fun whiteLine(): PixelBuffer {
        val b = PixelBuffer.filled(21, 5, 0xFF000000.toInt())
        for (y in 0 until 5) b[10, y] = -1
        return b
    }

    @Test
    fun movingZeroDistanceIsIdentity() {
        val src = ArtTestUtil.gradient(30, 20)
        assertArrayEquals(src.pixels, run(moving, src, "distance" to 0f).pixels)
    }

    @Test
    fun movingSeparatesChannelsAlongAngle() {
        val out = run(moving, whiteLine(), "distance" to 3f, "angle" to 0f, "order" to 0)
        // RGB order: red moves forward (+x), green stays, blue moves backward.
        assertEquals(255, r(out[13, 2])); assertEquals(0, g(out[13, 2])); assertEquals(0, b(out[13, 2]))
        assertEquals(0, r(out[10, 2])); assertEquals(255, g(out[10, 2])); assertEquals(0, b(out[10, 2]))
        assertEquals(0, r(out[7, 2])); assertEquals(0, g(out[7, 2])); assertEquals(255, b(out[7, 2]))
        for (x in 0 until 21) assertEquals(255, a(out[x, 2]))
    }

    @Test
    fun movingOrderMirrorsWithOppositeAngle() {
        val src = ArtTestUtil.gradient(25, 18)
        val rgb = run(moving, src, "distance" to 4f, "angle" to 30f, "order" to 0)
        val bgr = run(moving, src, "distance" to 4f, "angle" to 210f, "order" to 5)
        for (i in rgb.pixels.indices) {
            val p = rgb.pixels[i]; val q = bgr.pixels[i]
            assertTrue(abs(r(p) - r(q)) <= 1 && abs(g(p) - g(q)) <= 1 && abs(b(p) - b(q)) <= 1 && abs(a(p) - a(q)) <= 1)
        }
    }

    @Test
    fun movingGrowsFringesOnTransparentLayer() {
        val src = PixelBuffer(15, 3)
        src[7, 1] = -1
        val out = run(moving, src, "distance" to 2f, "angle" to 0f, "order" to 0)
        assertEquals(0xFFFF0000.toInt(), out[9, 1])
        assertEquals(0xFF00FF00.toInt(), out[7, 1])
        assertEquals(0xFF0000FF.toInt(), out[5, 1])
        assertEquals(0, out[0, 1])
    }

    @Test
    fun movingScalesDistanceWithPreviewScale() {
        val full = run(moving, whiteLine(), "distance" to 8f, "angle" to 0f)
        val preview = run(moving, whiteLine(), "distance" to 16f, "angle" to 0f, scale = 0.5f)
        assertArrayEquals(full.pixels, preview.pixels)
    }

    @Test
    fun zoomingKeepsCenterAndIsIdentityAtZero() {
        val src = ArtTestUtil.gradient(41, 41)
        assertArrayEquals(src.pixels, run(zooming, src, "distance" to 0f).pixels)
        val out = run(zooming, src, "distance" to 10f)
        assertEquals(src[20, 20], out[20, 20])
        // Far from the center the channels come from different places.
        assertNotEquals(src[0, 0], out[0, 0])
    }

    @Test
    fun zoomingIsRadialAroundMovableCenter() {
        // A white dot on the right of the center: the red channel moves outward (to the right).
        val src = PixelBuffer.filled(41, 21, 0xFF000000.toInt())
        src[35, 10] = -1
        val out = run(zooming, src, "distance" to 6f, "order" to 0, "center" to floatArrayOf(0.5f, 0.5f))
        var redX = -1; var blueX = -1
        for (x in 0 until 41) {
            if (r(out[x, 10]) > 128) redX = x
            if (b(out[x, 10]) > 128) blueX = x
        }
        assertTrue("red $redX blue $blueX", redX > 35 && blueX < 35)
        // With the center on the dot nothing moves there.
        val centered = run(zooming, src, "distance" to 6f, "center" to floatArrayOf(35.5f / 41f, 10.5f / 21f))
        assertEquals(-1, centered[35, 10])
    }

    @Test
    fun noiseZeroStrengthIsIdentity() {
        val src = ArtTestUtil.gradient(20, 20)
        assertArrayEquals(src.pixels, run(noise, src, "strength" to 0f).pixels)
        assertArrayEquals(src.pixels, run(noise, src, "amount" to 0f).pixels)
    }

    @Test
    fun noiseKeepsAlphaAndTransparentPixels() {
        val src = ArtTestUtil.gradient(40, 30, alpha = 140)
        for (x in 0 until 40) src[x, 0] = 0
        val out = run(noise, src, "strength" to 60f, "mode" to 1)
        for (i in src.pixels.indices) assertEquals(a(src.pixels[i]), a(out.pixels[i]))
        for (x in 0 until 40) assertEquals(0, out[x, 0])
    }

    @Test
    fun grayscaleNoiseShiftsChannelsEquallyAndIsUnbiased() {
        val src = PixelBuffer.filled(64, 64, 0xFF808080.toInt())
        for (dist in 0..1) {
            val out = run(noise, src, "strength" to 20f, "mode" to 0, "distribution" to dist)
            var sum = 0L
            var changed = 0
            for (c in out.pixels) {
                assertEquals(r(c), g(c)); assertEquals(g(c), b(c))
                sum += r(c) - 128
                if (r(c) != 128) changed++
            }
            assertTrue("mean deviation ${sum / 4096.0}", abs(sum / 4096.0) < 3.0)
            assertTrue(changed > 3500)
        }
    }

    @Test
    fun colorNoiseDiffersPerChannel() {
        val src = PixelBuffer.filled(32, 32, 0xFF808080.toInt())
        val out = run(noise, src, "strength" to 30f, "mode" to 1)
        assertTrue(out.pixels.count { r(it) != g(it) || g(it) != b(it) } > 900)
    }

    @Test
    fun noiseAmountControlsCoverageAndSeedIsDeterministic() {
        val src = PixelBuffer.filled(100, 100, 0xFF808080.toInt())
        val half = run(noise, src, "strength" to 50f, "amount" to 50f, "distribution" to 0)
        val changed = half.pixels.count { it != 0xFF808080.toInt() }
        assertTrue("changed $changed", changed in 4300..5700)
        assertArrayEquals(half.pixels, run(noise, src, "strength" to 50f, "amount" to 50f, "distribution" to 0).pixels)
        val other = run(noise, src, "strength" to 50f, "amount" to 50f, "distribution" to 0, "seed" to 2)
        assertTrue(ArtTestUtil.countDifferent(half, other) > 3000)
    }

    @Test
    fun largeGrainsAreSmooth() {
        val src = PixelBuffer.filled(80, 80, 0xFF808080.toInt())
        val fine = run(noise, src, "strength" to 40f, "size" to 1f)
        val coarse = run(noise, src, "strength" to 40f, "size" to 8f)
        fun roughness(p: PixelBuffer): Double {
            var s = 0.0
            for (y in 0 until 80) for (x in 1 until 80) s += abs(r(p[x, y]) - r(p[x - 1, y]))
            return s
        }
        assertTrue(roughness(coarse) * 3 < roughness(fine))
        // Grains still have a comparable spread of values.
        val spread = coarse.pixels.map { r(it) }.let { it.max() - it.min() }
        assertTrue("spread $spread", spread > 60)
    }
}
