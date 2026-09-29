package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Marks reference pixels whose samples leave the image (edge handling differs by design). */
private const val OUTSIDE = 0x00123456

class DirectionalBlursTest {
    private val zoom = ZoomingBlurFilter()
    private val spin = SpinBlurFilter()
    private val motion = MotionBlurFilter()
    private val center = "center" to floatArrayOf(0.5f, 0.5f)

    /** 81x81 black image, white 3x3 dot 20 px right of the centre pixel (40, 40). */
    private fun dot() = dotImage(81, 81, 60, 40)

    @Test
    fun neutralSettingsAreIdentityAndFlatImagesStayFlat() {
        val src = randomImage(33, 27)
        assertSameImage("zoom 0", src, zoom.render(src, "strength" to 0f))
        assertSameImage("spin 0", src, spin.render(src, "angle" to 0f))
        assertSameImage("motion 0", src, motion.render(src, "distance" to 0f))
        val flat = PixelBuffer.filled(40, 30, 0xFF4488CC.toInt())
        assertUniform("zoom flat", zoom.render(flat, "strength" to 100f, "direction" to 1), 0xFF4488CC.toInt(), 0)
        assertUniform("spin flat", spin.render(flat, "angle" to 180f), 0xFF4488CC.toInt(), 0)
        assertUniform("motion flat", motion.render(flat, "distance" to 500f, "angle" to 33f), 0xFF4488CC.toInt(), 0)
    }

    @Test
    fun zoomStreaksAreRadialAndOutward() {
        val out = zoom.render(dot(), center, "strength" to 30f)
        assertTrue("streak continues outward", red(out[68, 40]) > 20)
        assertEquals("nothing toward the centre", 0, red(out[52, 40]))
        assertEquals("nothing sideways", 0, red(out[60, 48]))
        assertEquals("centre untouched", 0, red(out[40, 40]))
        val both = zoom.render(dot(), center, "strength" to 60f, "direction" to 1)
        val outward = zoom.render(dot(), center, "strength" to 60f, "direction" to 0)
        assertTrue("both ways reaches inward", red(both[57, 40]) > 10)
        assertEquals("outward does not", 0, red(outward[57, 40]))
    }

    @Test
    fun zoomStreakNearTheEdgeHasNoGaps() {
        // A thin vertical line close to the right edge, zoom centre on the left: the streak must be a
        // smooth ramp, not a few ghost copies (regression: passes sampled past the image edge).
        val w = 200; val h = 41
        val src = PixelBuffer.filled(w, h, OPAQUE_BLACK)
        for (y in 0 until h) { src[160, y] = OPAQUE_WHITE; src[161, y] = OPAQUE_WHITE }
        val out = zoom.render(src, "center" to floatArrayOf(0f, 0.5f), "strength" to 30f)
        // Outward streak covers radii 161.5 .. 161.5 / 0.715 (~226, past the edge).
        for (x in 163 until w) assertTrue("gap at x=$x", red(out[x, 20]) > 5)
        for (x in 170 until w - 1) assertTrue("jump at x=$x", kotlin.math.abs(red(out[x + 1, 20]) - red(out[x, 20])) <= 3)
    }

    @Test
    fun zoomAveragesUniformlyAlongTheStreak() {
        // One row, centre on the left edge: every ray runs along +x. The source is a linear ramp, so
        // a uniform average over radii [sMin * r, sMax * r] equals the ramp at the midpoint radius.
        // (Regression: log-spaced cascade samples used to weight the streak toward the centre.)
        val w = 401
        val src = PixelBuffer(w, 1)
        for (x in 0 until w) src[x, 0] = com.brushwork.paint.core.ColorUtils.gray(x * 255 / (w - 1))
        fun ramp(radius: Double) = (radius - 0.5) * 255.0 / (w - 1)
        val origin = "center" to floatArrayOf(0f, 0.5f)
        val outward = zoom.render(src, origin, "strength" to 100f)
        for (x in listOf(100, 250, 380)) {
            val r = x + 0.5
            assertEquals("outward at $x", ramp(r * (0.05 + 1.0) / 2), red(outward[x, 0]).toDouble(), 4.0)
        }
        val both = zoom.render(src, origin, "strength" to 100f, "direction" to 1)
        for (x in listOf(60, 150, 260)) { // sMax * r stays inside the image
            assertEquals("both ways at $x", ramp(x + 0.5), red(both[x, 0]).toDouble(), 4.0)
        }
    }

    /**
     * Independent reference: premultiplied mean of [n] bilinear samples at `pos(x, y, t)` for t evenly
     * spaced in 0..1. Pixels with any sample outside the image are marked with [OUTSIDE].
     */
    private fun bruteForce(src: PixelBuffer, n: Int, pos: (x: Float, y: Float, t: Float, out: FloatArray) -> Unit): PixelBuffer {
        val out = PixelBuffer(src.width, src.height)
        val p = FloatArray(2)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            var sa = 0.0; var sr = 0.0; var sg = 0.0; var sb = 0.0
            var inside = true
            for (i in 0 until n) {
                pos(x + 0.5f, y + 0.5f, i / (n - 1f), p)
                if (p[0] < 0.5f || p[1] < 0.5f || p[0] > src.width - 0.5f || p[1] > src.height - 0.5f) inside = false
                val c = src.sampleBilinear(p[0], p[1])
                val a = alpha(c).toDouble()
                sa += a; sr += red(c) * a; sg += ((c shr 8) and 0xFF) * a; sb += (c and 0xFF) * a
            }
            out[x, y] = if (!inside) OUTSIDE else if (sa <= 0.0) 0 else
                com.brushwork.paint.core.ColorUtils.argb((sa / n).roundToInt(), (sr / sa).roundToInt(), (sg / sa).roundToInt(), (sb / sa).roundToInt())
        }
        return out
    }

    /** Smooth test pattern (no detail finer than a few pixels) with varying opacity. */
    private fun smoothImage(w: Int, h: Int) = PixelBuffer(w, h).also { b ->
        for (y in 0 until h) for (x in 0 until w) {
            val v = 0.5 + 0.5 * sin(x * 0.31) * cos(y * 0.23)
            b[x, y] = com.brushwork.paint.core.ColorUtils.argb(
                (120 + 135 * (0.5 + 0.5 * sin((x + y) * 0.17))).roundToInt(),
                (255 * v).roundToInt(), (x * 255) / w, (y * 255) / h,
            )
        }
    }

    private fun assertCloseToReference(what: String, ref: PixelBuffer, got: PixelBuffer) {
        var sum = 0L; var count = 0; var worst = 0
        for (i in ref.pixels.indices) {
            if (ref.pixels[i] == OUTSIDE) continue
            val d = channelDiff(ref.pixels[i], got.pixels[i])
            sum += d; count++; worst = maxOf(worst, d)
        }
        assertTrue("$what: too few comparable pixels ($count)", count > ref.size / 4)
        val mean = sum.toDouble() / count
        assertTrue("$what: mean difference $mean", mean < 1.5)
        assertTrue("$what: max difference $worst", worst <= 12)
    }

    @Test
    fun cascadesMatchBruteForceAverages() {
        val w = 96; val h = 72
        val src = smoothImage(w, h)
        val cx = w / 2f; val cy = h / 2f

        val k = 0.6f * 0.95f
        val zoomRef = bruteForce(src, 400) { x, y, t, p ->
            val s = (1f - k) + k * t
            p[0] = cx + (x - cx) * s; p[1] = cy + (y - cy) * s
        }
        assertCloseToReference("zoom", zoomRef, zoom.render(src, center, "strength" to 60f))

        val theta = Math.toRadians(40.0)
        val spinRef = bruteForce(src, 400) { x, y, t, p ->
            val phi = -theta / 2 + theta * t
            val c = cos(phi).toFloat(); val s = sin(phi).toFloat()
            p[0] = cx + (x - cx) * c - (y - cy) * s; p[1] = cy + (x - cx) * s + (y - cy) * c
        }
        assertCloseToReference("spin", spinRef, spin.render(src, center, "angle" to 40f))

        val len = 30f
        val a = Math.toRadians(25.0)
        val motionRef = bruteForce(src, 400) { x, y, t, p ->
            val d = -len / 2 + len * t
            p[0] = x + d * cos(a).toFloat(); p[1] = y - d * sin(a).toFloat()
        }
        assertCloseToReference("motion", motionRef, motion.render(src, "angle" to 25f, "distance" to len))
    }

    @Test
    fun zoomKeepsCenterRadiusSharp() {
        val src = randomImage(61, 61, seed = 4, opaque = true)
        val out = zoom.render(src, center, "strength" to 80f, "centerRadius" to 12f)
        for (y in 0 until 61) for (x in 0 until 61) {
            val dx = x + 0.5f - 30.5f; val dy = y + 0.5f - 30.5f
            if (dx * dx + dy * dy <= 12f * 12f) assertEquals("($x,$y)", src[x, y], out[x, y])
        }
        assertTrue("outside is blurred", (0 until 61).any { out[it, 0] != src[it, 0] })
    }

    @Test
    fun spinKeepsDistanceToCenterAndHonoursDirection() {
        val out = spin.render(dot(), center, "angle" to 60f)
        assertTrue("arc above", red(out[59, 33]) > 10)
        assertTrue("arc below", red(out[59, 47]) > 10)
        for (y in 0 until 81) for (x in 0 until 81) {
            val dx = x - 40; val dy = y - 40
            val r2 = dx * dx + dy * dy
            if (r2 < 15 * 15 || r2 > 25 * 25) assertEquals("off the circle ($x,$y)", 0, red(out[x, y]))
        }
        // Clockwise spin: the dot (at 3 o'clock) came from above, so the trail is above it.
        val cw = spin.render(dot(), center, "angle" to 60f, "direction" to 1)
        assertTrue(red(cw[59, 33]) > 10)
        assertEquals(0, red(cw[59, 47]))
        val ccw = spin.render(dot(), center, "angle" to 60f, "direction" to 2)
        assertEquals(0, red(ccw[59, 33]))
        assertTrue(red(ccw[59, 47]) > 10)
    }

    @Test
    fun motionBlurFollowsAngleAndLength() {
        val src = dotImage(81, 41, 40, 20)
        val out = motion.render(src, "angle" to 0f, "distance" to 20f)
        for (x in 29..51) assertTrue("lit at $x", red(out[x, 20]) > 0)
        // Exactly at the streak ends only float rounding in the bilinear taps may leak through.
        assertTrue(red(out[52, 20]) <= 2)
        assertTrue(red(out[28, 20]) <= 2)
        assertEquals(0, red(out[54, 20]))
        assertEquals(0, red(out[26, 20]))
        assertEquals("no vertical spread", 0, red(out[40, 23]))
        // Moving up (90°) with a trailing streak: the trail is below the dot.
        val up = motion.render(src, "angle" to 90f, "distance" to 20f, "direction" to 1)
        assertTrue(red(up[40, 28]) > 0)
        assertEquals(0, red(up[40, 12]))
        assertEquals(0, red(up[45, 28]))
    }

    @Test
    fun blursKeepEdgesOpaque() {
        val src = randomImage(40, 30, seed = 9, opaque = true)
        for (out in listOf(
            motion.render(src, "distance" to 25f, "angle" to 30f),
            zoom.render(src, "strength" to 90f, "direction" to 1),
            spin.render(src, "angle" to 90f, "center" to floatArrayOf(0f, 0f)),
        )) assertTrue(out.pixels.all { alpha(it) == 255 })
    }
}
