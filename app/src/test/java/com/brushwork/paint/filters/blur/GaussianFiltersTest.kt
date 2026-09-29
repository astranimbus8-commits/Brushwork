package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaussianFiltersTest {
    private val gaussian = GaussianBlurFilter()
    private val usm = UnsharpMaskFilter()

    @Test
    fun gaussianRadiusZeroIsIdentity() {
        val src = randomImage(20, 15)
        assertSameImage("radius 0", src, gaussian.render(src, "radius" to 0f))
    }

    @Test
    fun gaussianKeepsFlatColourAndOpacity() {
        val color = 0xFF3A7BC4.toInt()
        assertUniform("flat", gaussian.render(PixelBuffer.filled(40, 30, color), "radius" to 25f), color, 0)
        val out = gaussian.render(randomImage(40, 30, opaque = true), "radius" to 12f)
        assertTrue("opaque image stays opaque", out.pixels.all { alpha(it) == 255 })
    }

    @Test
    fun gaussianIsAlphaCorrectAndSpreadsIntoTransparency() {
        // Opaque red square on a transparent layer: the blur must not pull in the (black) colour of
        // transparent pixels, and must spread coverage outward.
        val src = PixelBuffer(41, 41)
        for (y in 15..25) for (x in 15..25) src[x, y] = OPAQUE_RED
        val out = gaussian.render(src, "radius" to 8f)
        for (c in out.pixels) if (alpha(c) > 0) assertEquals("pure red kept", OPAQUE_RED and 0xFFFFFF, c and 0xFFFFFF)
        assertTrue("coverage spreads outward", alpha(out[12, 20]) in 1..254)
        assertTrue("edge becomes partial", alpha(out[15, 20]) in 60..200)
        assertTrue("centre stays mostly solid", alpha(out[20, 20]) > 150)
        assertTrue("centre more solid than edge", alpha(out[20, 20]) > alpha(out[15, 20]))
        assertEquals("far corner stays empty", 0, out[0, 0])
    }

    @Test
    fun gaussianImpulseResponseIsSymmetric() {
        val out = gaussian.render(dotImage(31, 31, 15, 15, size = 1), "radius" to 6f)
        for (y in 0 until 31) for (x in 0 until 31) {
            val c = out[x, y]
            assertTrue(channelDiff(c, out[30 - x, y]) <= 1)
            assertTrue(channelDiff(c, out[x, 30 - y]) <= 1)
            assertTrue(channelDiff(c, out[y, x]) <= 1)
        }
        assertTrue(red(out[15, 15]) > red(out[17, 15]))
        assertTrue(red(out[17, 15]) > red(out[19, 15]))
    }

    @Test
    fun unsharpMaskNeutralSettingsAreIdentity() {
        val src = randomImage(25, 18)
        assertSameImage("amount 0", src, usm.render(src, "amount" to 0f))
        assertSameImage("radius 0", src, usm.render(src, "radius" to 0f))
        val flat = PixelBuffer.filled(30, 20, 0xFF808080.toInt())
        assertSameImage("flat image", flat, usm.render(flat, "amount" to 300f))
    }

    @Test
    fun unsharpMaskAddsOvershootAtEdges() {
        val w = 40
        val src = PixelBuffer(w, 10)
        for (y in 0 until 10) for (x in 0 until w) src[x, y] = ColorUtils.gray(if (x < w / 2) 100 else 150)
        val out = usm.render(src, "radius" to 4f, "amount" to 100f)
        assertTrue("dark side darkens", red(out[w / 2 - 1, 5]) < 100)
        assertTrue("light side brightens", red(out[w / 2, 5]) > 150)
        assertEquals("far pixels unchanged", 100, red(out[0, 5]))
        assertEquals("far pixels unchanged", 150, red(out[w - 1, 5]))
        // The difference across this edge is at most ~25 levels, so a 60 threshold skips it.
        assertSameImage("threshold", src, usm.render(src, "radius" to 4f, "amount" to 100f, "threshold" to 60f))
    }

    @Test
    fun unsharpMaskKeepsAlphaExactly() {
        val src = randomImage(30, 20)
        val out = usm.render(src, "radius" to 5f, "amount" to 250f)
        for (i in src.pixels.indices) {
            assertEquals(alpha(src.pixels[i]), alpha(out.pixels[i]))
            if (alpha(src.pixels[i]) == 0) assertEquals(0, out.pixels[i])
        }
    }
}
