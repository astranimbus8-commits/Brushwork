package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.GradientStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorFiltersTest {

    private fun near(expected: Int, actual: Int, tol: Int = 1) =
        abs(r(expected) - r(actual)) <= tol && abs(g(expected) - g(actual)) <= tol &&
            abs(b(expected) - b(actual)) <= tol && a(expected) == a(actual)

    private fun assertNear(expected: Int, actual: Int, tol: Int = 1) =
        assertTrue("expected ${ColorUtils.toHex(expected, true)} got ${ColorUtils.toHex(actual, true)}", near(expected, actual, tol))

    // ---------------------------------------------------------------- color balance

    @Test
    fun colorBalanceSameShiftInAllRangesMovesEveryToneEqually() {
        val f = adjust<ColorBalanceFilter>()
        val out = f.run(
            row(gray(60), gray(128), gray(200)),
            "shadowsCR" to 50f, "midtonesCR" to 50f, "highlightsCR" to 50f, "preserveLuminosity" to false,
        ).pixels
        assertNear(rgb(149, 60, 60), out[0])
        assertNear(rgb(217, 128, 128), out[1])
        assertNear(rgb(255, 200, 200), out[2])
    }

    @Test
    fun colorBalanceRangesAreSeparated() {
        val f = adjust<ColorBalanceFilter>()
        val sh = f.run(row(gray(40), gray(220)), "shadowsYB" to 100f, "preserveLuminosity" to false).pixels
        assertTrue(b(sh[0]) > 200)
        assertEquals(gray(220), sh[1])
        val hi = f.run(row(gray(40), gray(220)), "highlightsMG" to -100f, "preserveLuminosity" to false).pixels
        assertEquals(gray(40), hi[0])
        assertTrue(g(hi[1]) < 100 && r(hi[1]) == 220)
    }

    @Test
    fun colorBalancePreservesLuminosity() {
        val out = adjust<ColorBalanceFilter>().run(row(gray(128), rgb(90, 140, 60)), "midtonesCR" to 80f, "midtonesYB" to -30f).pixels
        for ((i, src) in listOf(gray(128), rgb(90, 140, 60)).withIndex()) {
            assertTrue(abs(ColorUtils.luminance(out[i]) - ColorUtils.luminance(src)) <= 1)
            assertTrue(r(out[i]) > r(src))
        }
    }

    // ---------------------------------------------------------------- hue / saturation / brightness

    @Test
    fun hueRotation() {
        val f = adjust<HueSaturationFilter>()
        assertNear(rgb(0, 255, 0), f.run(row(rgb(255, 0, 0)), "hue" to 120f).pixels[0])
        assertNear(rgb(0, 0, 255), f.run(row(rgb(255, 0, 0)), "hue" to -120f).pixels[0])
        assertNear(rgb(255, 0, 0, 40), f.run(row(rgb(0, 255, 0, 40)), "hue" to -120f).pixels[0])
        assertNear(rgb(0, 255, 255), f.run(row(rgb(255, 0, 0)), "hue" to 180f).pixels[0])
    }

    @Test
    fun saturationAndBrightness() {
        val f = adjust<HueSaturationFilter>()
        assertNear(gray(128), f.run(row(rgb(255, 0, 0)), "saturation" to -100f).pixels[0])
        assertEquals(gray(90), f.run(row(gray(90)), "saturation" to 100f).pixels[0])
        val boosted = f.run(row(rgb(140, 110, 100)), "saturation" to 50f).pixels[0]
        assertTrue(r(boosted) - b(boosted) > 40)
        assertEquals(gray(255), f.run(row(rgb(20, 90, 200)), "brightness" to 100f).pixels[0])
        assertEquals(gray(0), f.run(row(rgb(20, 90, 200)), "brightness" to -100f).pixels[0])
    }

    @Test
    fun colorizeUsesHueAndKeepsLuminanceOrder() {
        val f = adjust<HueSaturationFilter>()
        val out = f.run(row(gray(128), gray(60), gray(200, 99)), "colorize" to true, "hue" to 120f, "saturation" to 100f).pixels
        assertTrue(g(out[0]) == 255 && r(out[0]) <= 2 && b(out[0]) <= 2)
        assertTrue(g(out[1]) < g(out[0]) && g(out[1]) > r(out[1]))
        assertEquals(99, a(out[2]))
        assertTrue(ColorUtils.luminance(out[2]) > ColorUtils.luminance(out[0]))
    }

    // ---------------------------------------------------------------- replace color

    private val yellow = rgb(240, 200, 40)
    private val shade = rgb(180, 150, 30)

    @Test
    fun replaceSolidColorOnlyTouchesMatchingPixels() {
        val f = adjust<ReplaceColorFilter>()
        val src = row(yellow, rgb(0, 80, 0), rgb(240, 200, 40, 100), rgb(30, 60, 200))
        val out = f.run(src, "source" to 1, "target" to yellow, "mode" to 2, "replacement" to rgb(0, 0, 255)).pixels
        assertEquals(rgb(0, 0, 255), out[0])
        assertEquals(src.pixels[1], out[1])
        assertEquals(rgb(0, 0, 255, 100), out[2])
        assertEquals(src.pixels[3], out[3])
    }

    @Test
    fun replaceColorKeepsShading() {
        val red = rgb(229, 57, 53)
        val out = adjust<ReplaceColorFilter>().run(row(yellow, shade), "source" to 1, "target" to yellow, "mode" to 1, "replacement" to red).pixels
        assertNear(red, out[0], 2)
        val s = out[1]
        assertTrue(r(s) > g(s) + 50 && r(s) > b(s) + 50)
        assertTrue(ColorUtils.luminance(s) < ColorUtils.luminance(red))
    }

    @Test
    fun replaceColorShiftMode() {
        val out = adjust<ReplaceColorFilter>().run(row(yellow, rgb(30, 60, 200)), "source" to 1, "target" to yellow, "mode" to 0, "hue" to 180f).pixels
        assertTrue(b(out[0]) > r(out[0]))
        assertEquals(rgb(30, 60, 200), out[1])
    }

    @Test
    fun replaceColorToleranceAndSoftEdge() {
        val f = adjust<ReplaceColorFilter>()
        val near = rgb(240, 200, 41)
        val exact = f.run(row(yellow, near), "source" to 1, "target" to yellow, "mode" to 2, "replacement" to rgb(0, 0, 255), "tolerance" to 0f).pixels
        assertEquals(rgb(0, 0, 255), exact[0])
        assertEquals(near, exact[1])
        val soft = f.run(row(shade), "source" to 1, "target" to yellow, "mode" to 2, "replacement" to rgb(0, 0, 255), "tolerance" to 25f, "softness" to 100f).pixels[0]
        assertTrue(b(soft) in 31..254 && r(soft) in 1..179)
    }

    @Test
    fun replaceColorSamplesUnderReferencePoint() {
        val w = 20; val h = 10
        val src = PixelBuffer(w, h)
        val cyan = rgb(40, 200, 240)
        for (y in 0 until h) for (x in 0 until w) src[x, y] = if (x < 10) yellow else cyan
        val f = adjust<ReplaceColorFilter>()
        val left = f.run(src, "point" to floatArrayOf(0.25f, 0.5f), "mode" to 2, "replacement" to rgb(0, 0, 0))
        assertEquals(rgb(0, 0, 0), left[2, 3]); assertEquals(cyan, left[15, 3])
        val right = f.run(src, "point" to floatArrayOf(0.75f, 0.5f), "mode" to 2, "replacement" to rgb(0, 0, 0))
        assertEquals(yellow, right[2, 3]); assertEquals(rgb(0, 0, 0), right[15, 3])
        // A point over transparency has nothing to replace.
        val holes = src.copy().also { for (y in 0 until h) for (x in 0 until 10) it[x, y] = 0 }
        assertTrue(f.run(holes, "point" to floatArrayOf(0.25f, 0.5f)).pixels.contentEquals(holes.pixels))
    }

    // ---------------------------------------------------------------- gradation map

    @Test
    fun gradationMapFollowsGradientByLuminance() {
        val f = adjust<GradationMapFilter>()
        val grad = listOf(GradientStop(0f, rgb(255, 0, 0)), GradientStop(1f, rgb(0, 0, 255)))
        val out = f.run(row(gray(0), gray(255), gray(128), gray(128, 50)), "gradient" to grad).pixels
        assertEquals(rgb(255, 0, 0), out[0])
        assertEquals(rgb(0, 0, 255), out[1])
        assertNear(rgb(127, 0, 128), out[2])
        assertNear(rgb(127, 0, 128, 50), out[3])
        assertEquals(rgb(0, 0, 255), f.run(row(gray(0)), "gradient" to grad, "reverse" to true).pixels[0])
        assertNear(rgb(128, 0, 0), f.run(row(gray(0)), "gradient" to grad, "opacity" to 50f).pixels[0])
        // Stop alpha scales pixel alpha.
        val fade = listOf(GradientStop(0f, rgb(0, 0, 0)), GradientStop(1f, rgb(255, 255, 255, 0)))
        assertEquals(0, a(f.run(row(gray(255)), "gradient" to fade).pixels[0]))
        // Defaults map black / white to the ends of the default gradient.
        val def = f.run(row(gray(0), gray(255))).pixels
        assertEquals(0xFF14213D.toInt(), def[0]); assertEquals(0xFFFFF1D0.toInt(), def[1])
    }

    // ---------------------------------------------------------------- monocolor / change drawing color

    @Test
    fun monocolorRampsThroughChosenColor() {
        val c = rgb(200, 100, 50)
        val out = adjust<MonocolorFilter>().run(row(gray(0), gray(128), gray(255), gray(90, 60), rgb(255, 0, 0)), "color" to c).pixels
        assertEquals(gray(0), out[0])
        assertNear(c, out[1], 2)
        assertEquals(gray(255), out[2])
        assertEquals(60, a(out[3]))
        assertTrue(r(out[3]) < r(out[1]))
        // Every output lies on the black -> color -> white ramp: hue of the chosen color.
        val hsv = FloatArray(3); val chsv = FloatArray(3)
        ColorUtils.colorToHsv(c, chsv); ColorUtils.colorToHsv(out[4], hsv)
        assertTrue(abs(hsv[0] - chsv[0]) < 3f)
    }

    @Test
    fun changeDrawingColorKeepsAlpha() {
        val blue = rgb(20, 60, 230)
        val src = row(rgb(0, 0, 0, 100), rgb(0, 0, 0, 255), 0, rgb(250, 10, 10, 30))
        val out = adjust<ChangeDrawingColorFilter>().run(src, "color" to blue).pixels
        assertEquals(rgb(20, 60, 230, 100), out[0])
        assertEquals(blue, out[1])
        assertEquals(0, out[2])
        assertEquals(rgb(20, 60, 230, 30), out[3])
        val half = adjust<ChangeDrawingColorFilter>().run(row(rgb(0, 0, 0)), "color" to rgb(200, 100, 0), "amount" to 50f).pixels[0]
        assertNear(rgb(100, 50, 0), half)
    }
}
