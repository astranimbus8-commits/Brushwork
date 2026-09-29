package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class FrostedGlassFiltersTest {
    private val normal = FrostedGlassFilter()
    private val zooming = FrostedGlassZoomingFilter()
    private val moving = FrostedGlassMovingFilter()
    private val all = listOf(normal, zooming, moving)

    @Test
    fun radiusZeroIsIdentityAndFlatImagesStayFlat() {
        val src = randomImage(30, 20)
        val flat = PixelBuffer.filled(30, 20, 0xFF55AA33.toInt())
        for (f in all) {
            assertSameImage("${f.id} radius 0", src, f.render(src, "radius" to 0f))
            assertUniform("${f.id} flat", f.render(flat, "radius" to 40f, "smoothness" to 50f), 0xFF55AA33.toInt(), 0)
        }
    }

    @Test
    fun deterministicPerSeed() {
        val src = randomImage(40, 30, seed = 1, opaque = true)
        for (f in all) {
            val a = f.render(src, "seed" to 5)
            assertSameImage("${f.id} same seed", a, f.render(src, "seed" to 5))
            assertFalse("${f.id} different seed", a.pixels.contentEquals(f.render(src, "seed" to 6).pixels))
            assertFalse("${f.id} changes the image", a.pixels.contentEquals(src.pixels))
        }
    }

    @Test
    fun randomValuesStayBelowOne() {
        // hash01 divides a 31-bit int by 2^31 in float, which rounds up to 1f for the top ~64 values;
        // those must not index past the lookup tables (crashed full-size renders).
        val s = FrostedGlass.streamSeed(1, 0)
        var found = false
        var y = 0
        while (!found && y < 20_000) {
            for (x in 0 until 10_000) if (FilterMath.hash01(x, y, s) >= 1f) {
                assertTrue(FrostedGlass.random(x, y, 1, 0) < 1f)
                found = true
                break
            }
            y++
        }
        assertTrue("no hash01 == 1f case found", found)
    }

    @Test
    fun normalScatterStaysWithinRadius() {
        // A single opaque column on a transparent layer: scattered pixels may land at most
        // radius (+1 for bilinear sampling) away, and grain must actually spread it.
        val src = PixelBuffer(61, 40)
        for (y in 0 until 40) src[30, y] = OPAQUE_RED
        val out = normal.render(src, "radius" to 5f, "variance" to 100f)
        var spread = 0
        for (y in 0 until 40) for (x in 0 until 61) if (alpha(out[x, y]) > 0) {
            assertTrue("pixel at x=$x too far", abs(x - 30) <= 6)
            assertEquals("colour stays pure", 0xFF0000, out[x, y] and 0xFFFFFF)
            if (abs(x - 30) >= 2) spread++
        }
        assertTrue("grain spreads sideways", spread > 20)
    }

    @Test
    fun lowVarianceKeepsMostPixelsCloser() {
        val src = PixelBuffer(81, 60)
        for (y in 0 until 60) src[40, y] = OPAQUE_RED
        fun meanDistance(out: PixelBuffer): Double {
            var sum = 0.0; var weight = 0.0
            for (y in 0 until 60) for (x in 0 until 81) { val a = alpha(out[x, y]).toDouble(); sum += abs(x - 40) * a; weight += a }
            return sum / weight
        }
        val wide = meanDistance(normal.render(src, "radius" to 12f, "variance" to 100f))
        val narrow = meanDistance(normal.render(src, "radius" to 12f, "variance" to 15f))
        assertTrue("variance 15 % ($narrow) should scatter less than 100 % ($wide)", narrow < wide * 0.6)
    }

    @Test
    fun smoothnessAveragesGrain() {
        val src = randomImage(50, 40, seed = 3, opaque = true)
        fun roughness(img: PixelBuffer): Double {
            var s = 0.0
            for (y in 0 until img.height) for (x in 1 until img.width) s += abs(red(img[x, y]) - red(img[x - 1, y]))
            return s
        }
        val crisp = roughness(normal.render(src, "radius" to 6f, "smoothness" to 0f))
        val smooth = roughness(normal.render(src, "radius" to 6f, "smoothness" to 100f))
        assertTrue("smooth ($smooth) < crisp ($crisp)", smooth < crisp * 0.7)
    }

    @Test
    fun zoomingScattersOnlyAlongRays() {
        // Top half red, bottom half blue, centre on the boundary: every ray stays within its half,
        // so radial displacement changes nothing away from the boundary line.
        val w = 81; val h = 81
        val src = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) src[x, y] = if (y < 40) OPAQUE_RED else OPAQUE_BLUE
        val out = zooming.render(src, "center" to floatArrayOf(0.5f, 40f / 81f), "radius" to 6f)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + 0.5f - 40.5f; val dy = y + 0.5f - 40f
            if (abs(dy) >= 4f && sqrt(dx * dx + dy * dy) > 12f) assertEquals("($x,$y)", src[x, y], out[x, y])
        }
        // Concentric rings are scrambled by the radial scatter.
        val rings = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val r = sqrt(((x - 40) * (x - 40) + (y - 40) * (y - 40)).toFloat()).toInt()
            rings[x, y] = if ((r / 3) % 2 == 0) OPAQUE_RED else OPAQUE_BLUE
        }
        assertFalse(rings.pixels.contentEquals(zooming.render(rings, "radius" to 6f).pixels))
    }

    @Test
    fun zoomingKeepsCenterRadiusClear() {
        val src = randomImage(61, 61, seed = 8, opaque = true)
        val out = zooming.render(src, "centerRadius" to 15f, "radius" to 30f)
        for (y in 0 until 61) for (x in 0 until 61) {
            val dx = x + 0.5f - 30.5f; val dy = y + 0.5f - 30.5f
            if (dx * dx + dy * dy <= 15f * 15f) assertEquals(src[x, y], out[x, y])
        }
    }

    @Test
    fun movingScattersOnlyAlongItsAxis() {
        val w = 40; val h = 30
        val rows = PixelBuffer(w, h)
        val cols = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            rows[x, y] = ColorUtils.gray(y * 8)
            cols[x, y] = ColorUtils.gray(x * 6)
        }
        assertSameImage("horizontal axis keeps rows", rows, moving.render(rows, "angle" to 0f, "radius" to 10f))
        assertFalse(cols.pixels.contentEquals(moving.render(cols, "angle" to 0f, "radius" to 10f).pixels))
        assertSameImage("vertical axis keeps columns", cols, moving.render(cols, "angle" to 90f, "radius" to 10f), tolerance = 1)
        assertFalse(rows.pixels.contentEquals(moving.render(rows, "angle" to 90f, "radius" to 10f).pixels))
    }
}
