package com.brushwork.paint.filters.art

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.art.ArtTestUtil.a
import com.brushwork.paint.filters.art.ArtTestUtil.b
import com.brushwork.paint.filters.art.ArtTestUtil.g
import com.brushwork.paint.filters.art.ArtTestUtil.r
import com.brushwork.paint.filters.art.ArtTestUtil.run
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowFiltersTest {
    private val bloom = BloomFilter()
    private val cross = CrossFilter()
    private val black = 0xFF000000.toInt()

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected +- $tolerance but was $actual", kotlin.math.abs(expected - actual) <= tolerance)

    private fun square(n: Int, bg: Int, half: Int): PixelBuffer {
        val p = PixelBuffer.filled(n, n, bg)
        for (y in n / 2 - half..n / 2 + half) for (x in n / 2 - half..n / 2 + half) p[x, y] = -1
        return p
    }

    // ------------------------------------------------------------------ Bloom

    @Test
    fun bloomIdentityCases() {
        val src = ArtTestUtil.gradient(30, 30)
        assertArrayEquals(src.pixels, run(bloom, src, "area" to 0f).pixels)
        assertArrayEquals(src.pixels, run(bloom, src, "brightness" to 0f).pixels)
        // Nothing is bright enough: dark images are unchanged.
        val dark = PixelBuffer.filled(30, 30, 0xFF404040.toInt())
        assertArrayEquals(dark.pixels, run(bloom, dark, "area" to 40f).pixels)
    }

    @Test
    fun bloomBleedsLightAroundBrightAreas() {
        // 85 px image, bright square 32..52 (symmetric with respect to the 5 px light grid).
        val out = run(bloom, square(85, black, 10), "radius" to 20f, "area" to 50f)
        // 5 px outside the bright square's edge versus a far corner.
        val near = r(out[57, 42]); val far = r(out[1, 1])
        assertTrue("near $near far $far", near > 30 && near > far + 20)
        assertTrue(r(out[54, 42]) > r(out[60, 42]))
        // Glow is symmetric and neutral for a white source.
        assertNear(r(out[57, 42]), r(out[27, 42]), 2)
        assertNear(r(out[57, 42]), b(out[57, 42]), 1)
        for (c in out.pixels) assertEquals(255, a(c))
    }

    @Test
    fun bloomSpillsOntoTransparentPixels() {
        val out = run(bloom, square(81, 0, 3), "radius" to 20f, "area" to 50f)
        assertTrue(a(out[40 + 9, 40]) > 10)
        assertEquals(-1, out[40, 40])
        assertTrue(a(out[0, 0]) < a(out[40 + 9, 40]))
        // Spilled light keeps the color of its source.
        val c = out[40 + 9, 40]
        assertNear(r(c), b(c), 2)
    }

    @Test
    fun bloomRadiusAndBalance() {
        val small = run(bloom, square(101, black, 2), "radius" to 5f)
        val large = run(bloom, square(101, black, 2), "radius" to 40f)
        assertTrue(r(large[50 + 25, 50]) > r(small[50 + 25, 50]))
        val src = square(61, 0xFF808080.toInt(), 4)
        val add = run(bloom, src, "balanced" to false, "area" to 60f, "brightness" to 200f)
        val screen = run(bloom, src, "balanced" to true, "area" to 60f, "brightness" to 200f)
        for (i in add.pixels.indices) assertTrue(r(add.pixels[i]) >= r(screen.pixels[i]))
        assertTrue((0 until add.size).count { r(add.pixels[it]) > r(screen.pixels[it]) } > 50)
    }

    // ------------------------------------------------------------------ Cross Filter

    private fun dot(n: Int, bg: Int): PixelBuffer = PixelBuffer.filled(n, n, bg).also { it[n / 2, n / 2] = -1 }

    @Test
    fun crossIdentityWhenNothingIsBright() {
        val src = ArtTestUtil.gradient(30, 30)
        assertArrayEquals(src.pixels, run(cross, src, "area" to 0f).pixels)
        val dark = PixelBuffer.filled(30, 30, 0xFF303030.toInt())
        assertArrayEquals(dark.pixels, run(cross, dark).pixels)
    }

    @Test
    fun crossRaysFollowCountAndDirection() {
        val c = 40
        val four = run(cross, dot(81, black), "count" to 4f, "direction" to 0f, "thickness" to 1f, "length" to 60f)
        for ((dx, dy) in listOf(15 to 0, -15 to 0, 0 to 15, 0 to -15)) {
            assertTrue("ray at $dx,$dy", r(four[c + dx, c + dy]) > 60)
        }
        assertEquals(black, four[c + 15, c + 15])
        val diag = run(cross, dot(81, black), "count" to 4f, "direction" to 45f, "thickness" to 1f, "length" to 60f)
        assertTrue(r(diag[c + 10, c + 10]) > 60)
        assertTrue(r(diag[c - 10, c + 10]) > 60)
        assertTrue(r(diag[c + 15, c]) < 10)
        val two = run(cross, dot(81, black), "count" to 2f, "direction" to 0f, "thickness" to 1f, "length" to 60f)
        assertTrue(r(two[c + 15, c]) > 60 && r(two[c - 15, c]) > 60)
        assertEquals(black, two[c, c + 15])
    }

    @Test
    fun crossRaysFadeWithLength() {
        val long = run(cross, dot(121, black), "count" to 4f, "direction" to 0f, "thickness" to 1f, "length" to 200f)
        val short = run(cross, dot(121, black), "count" to 4f, "direction" to 0f, "thickness" to 1f, "length" to 10f)
        assertTrue(r(long[60 + 30, 60]) > 100)
        assertTrue(r(short[60 + 30, 60]) < 5)
        assertTrue(r(long[60 + 5, 60]) > r(long[60 + 40, 60]))
    }

    @Test
    fun crossRaysOnTransparentLayerAndChromatic() {
        val out = run(cross, dot(81, 0), "direction" to 0f, "thickness" to 1f, "length" to 80f)
        assertTrue(a(out[40 + 20, 40]) > 60)
        assertEquals(0, a(out[40 + 20, 40 + 20]))
        val chroma = run(cross, dot(81, black), "direction" to 0f, "thickness" to 1f, "length" to 80f, "chromatic" to true)
        val tip = chroma[40 + 30, 40]
        assertTrue("tip ${Integer.toHexString(tip)}", r(tip) > b(tip) + 10 && r(tip) >= g(tip))
    }
}
