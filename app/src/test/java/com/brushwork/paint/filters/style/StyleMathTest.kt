package com.brushwork.paint.filters.style

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class StyleMathTest {
    private val ctx = FilterContext()

    @Test
    fun signedDistanceOfAntialiasedDiscMatchesAnalyticDistance() {
        val cx = 60.3f; val cy = 58.7f; val r = 37.4f
        val img = StyleTestImages.disc(120, 120, cx, cy, r)
        val sd = StyleMath.signedDistance(img, ctx)
        var maxErr = 0f
        var sumErr = 0.0
        var n = 0
        var worst = ""
        for (y in 0 until 120) for (x in 0 until 120) {
            val truth = hypot(x + 0.5f - cx, y + 0.5f - cy) - r
            if (abs(truth) > 40f) continue
            val e = abs(sd[y * 120 + x] - truth)
            if (e > maxErr) { maxErr = e; worst = "($x,$y) truth $truth got ${sd[y * 120 + x]}" }
            sumErr += e; n++
        }
        assertTrue("max error $maxErr at $worst", maxErr < 0.35f)
        assertTrue("mean error ${sumErr / n}", sumErr / n < 0.02)
        // Gradient direction error inside the disc (drives the shading of domes and bevels).
        var maxAng = 0f
        var sumAng = 0.0
        var m = 0
        for (y in 1 until 119) for (x in 1 until 119) {
            val rx = x + 0.5f - cx; val ry = y + 0.5f - cy
            val rr = hypot(rx, ry)
            if (rr < 5f || rr > r - 2f) continue
            val gx = sd[y * 120 + x + 1] - sd[y * 120 + x - 1]
            val gy = sd[(y + 1) * 120 + x] - sd[(y - 1) * 120 + x]
            val gl = hypot(gx, gy)
            val cross = abs(gx * ry - gy * rx) / (gl * rr)
            maxAng = max(maxAng, cross); sumAng += cross; m++
        }
        assertTrue("max gradient error $maxAng", maxAng < 0.12f)
        assertTrue("mean gradient error ${sumAng / m}", sumAng / m < 0.012)
    }

    @Test
    fun signedDistanceOfRectangleIsExactIncludingCorners() {
        // Hard-edged rectangle [20, 70) x [15, 45): outside distances to the corners are Euclidean.
        val img = StyleTestImages.rect(100, 70, 20, 15, 70, 45)
        val sd = StyleMath.signedDistance(img, ctx)
        var maxErr = 0f
        var worst = ""
        for (y in 0 until 70) for (x in 0 until 100) {
            val cx = x + 0.5f; val cy = y + 0.5f
            val ox = max(20f - cx, cx - 70f); val oy = max(15f - cy, cy - 45f)
            val truth = if (ox <= 0f && oy <= 0f) max(ox, oy) else hypot(max(ox, 0f), max(oy, 0f))
            val e = abs(sd[y * 100 + x] - truth)
            if (e > maxErr) { maxErr = e; worst = "($x,$y) truth $truth got ${sd[y * 100 + x]}" }
        }
        // Only the pixels touching a hard corner are off, by at most (sqrt(2) - 1) / 2.
        assertTrue("max error $maxErr at $worst", maxErr < 0.21f)
    }

    @Test
    fun signedDistanceIsFarForEmptyImages() {
        val sd = StyleMath.signedDistance(PixelBuffer(5, 4), ctx)
        assertTrue(sd.all { it == StyleMath.FAR })
    }

    @Test
    fun signedDistanceOfFullyOpaqueImageIsDeepInside() {
        val sd = StyleMath.signedDistance(PixelBuffer(6, 3).fill(-1), ctx)
        assertTrue(sd.all { it <= -StyleMath.FAR / 2 })
    }

    @Test
    fun signedDistanceNormalizesTranslucentLayers() {
        // A layer painted at 40 % opacity still has a solid shape.
        val img = StyleTestImages.rect(40, 40, 10, 10, 30, 30, 0x66FF0000)
        val sd = StyleMath.signedDistance(img, ctx)
        assertTrue(sd[20 * 40 + 20] < -9f)
        assertEquals(5.5f, sd[20 * 40 + 35], 0.01f)
    }

    @Test
    fun gaussianInPlacePreservesMassAndConstants() {
        val w = 64; val h = 48
        val plane = FloatArray(w * h) { 0.25f }
        StyleMath.gaussianInPlace(plane, w, h, 7f, ctx)
        assertTrue(plane.all { abs(it - 0.25f) < 1e-4f })
        val spike = FloatArray(w * h)
        spike[24 * w + 32] = 1f
        StyleMath.gaussianInPlace(spike, w, h, 1.5f, ctx)
        assertEquals(1f, spike.sum(), 1e-3f)
        StyleMath.gaussianInPlace(spike, w, h, 4f, ctx)
        assertEquals(1f, spike.sum(), 1e-3f)
    }

    @Test
    fun compositingHelpers() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        // behind: an opaque pixel hides the effect; a transparent one shows it.
        assertEquals(red, StyleMath.behind(red, blue, 1f))
        assertEquals(blue, StyleMath.behind(0, blue, 1f))
        // over: the effect covers the pixel.
        assertEquals(blue, StyleMath.over(red, blue, 1f))
        assertEquals(red, StyleMath.over(red, blue, 0f))
        // atop keeps the pixel's alpha and never paints on transparent pixels.
        assertEquals(0x800000FF.toInt(), StyleMath.atop(0x80FF0000.toInt(), blue, 1f))
        assertEquals(0, StyleMath.atop(0, blue, 1f))
        // screen with black leaves the pixel unchanged, with white turns it white.
        assertEquals(red, StyleMath.screen(red, 0xFF000000.toInt(), 1f))
        assertEquals(-1, StyleMath.screen(red, -1, 1f))
    }

    @Test
    fun contentBoundsAndCropping() {
        val img = StyleTestImages.rect(50, 40, 12, 7, 20, 31)
        val bb = StyleMath.contentBounds(img)!!
        assertEquals(listOf(12, 7, 20, 31), bb.toList())
        assertEquals(null, StyleMath.contentBounds(PixelBuffer(3, 3)))
        val out = StyleMath.aroundContent(img, 2) { sub, offX, offY ->
            assertEquals(10, offX); assertEquals(5, offY)
            assertEquals(12, sub.width); assertEquals(28, sub.height)
            PixelBuffer(sub.width, sub.height).fill(-1)
        }
        assertEquals(-1, out[10, 5])
        assertEquals(0, out[9, 5])
        assertEquals(0, out[22, 20])
    }

    @Test
    fun shadowSweepShadowsTheFarSideOfABump() {
        val w = 60; val h = 20
        val z = FloatArray(w * h)
        for (y in 0 until h) for (x in 28..31) z[y * w + x] = 10f
        // Light from the left (+x is away from it) at 45 degrees.
        val s = StyleMath.shadowSweep(z, w, h, -1f, 0f, 1f, 0.5f, ctx)
        assertEquals(1f, s[10 * w + 35], 1e-3f)
        assertEquals(0f, s[10 * w + 20], 1e-3f)
        assertEquals(0f, s[10 * w + 45], 1e-3f)
    }
}
