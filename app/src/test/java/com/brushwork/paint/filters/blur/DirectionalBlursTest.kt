package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
