package com.brushwork.paint.ui.color

import com.brushwork.paint.core.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {

    private val layout = WheelLayout(size = 200f, ringWidth = 24f, pad = 4f)

    private fun assertHue(expected: Float, actual: Float) {
        val d = abs(expected - actual).let { minOf(it, 360f - it) }
        assertEquals("hue $actual != $expected", 0f, d, 0.01f)
    }

    @Test
    fun layoutGeometry() {
        assertEquals(100f, layout.center, 0f)
        assertEquals(96f, layout.outerRadius, 1e-4f)
        assertEquals(72f, layout.innerRadius, 1e-4f)
        assertEquals(84f, layout.ringMid, 1e-4f)
        // The square fits inside the inner circle.
        val cornerDist = Math.hypot(layout.squareHalf.toDouble(), layout.squareHalf.toDouble())
        assert(cornerDist < layout.innerRadius) { "square corner $cornerDist outside inner radius" }
        assertEquals(layout.center - layout.squareHalf, layout.squareLeft, 1e-4f)
    }

    @Test
    fun hueIsZeroAtTopAndIncreasesClockwise() {
        assertHue(0f, layout.hueAt(100f, 0f))
        assertHue(90f, layout.hueAt(200f, 100f))
        assertHue(180f, layout.hueAt(100f, 200f))
        assertHue(270f, layout.hueAt(0f, 100f))
        assertHue(45f, layout.hueAt(150f, 50f))
        // Center is degenerate: defined as 0.
        assertEquals(0f, layout.hueAt(100f, 100f), 0f)
    }

    @Test
    fun hueRangeIsHalfOpen() {
        for (i in 0 until 3600) {
            val a = Math.toRadians(i / 10.0)
            val h = layout.hueAt(100f + 50f * Math.cos(a).toFloat(), 100f + 50f * Math.sin(a).toFloat())
            assert(h >= 0f && h < 360f) { "hue $h out of range" }
        }
    }

    @Test
    fun huePointIsInverseOfHueAt() {
        var h = 0f
        while (h < 360f) {
            val (x, y) = layout.huePoint(h)
            assertHue(h, layout.hueAt(x, y))
            assertEquals(layout.ringMid, Math.hypot((x - 100f).toDouble(), (y - 100f).toDouble()).toFloat(), 1e-3f)
            h += 7.5f
        }
        val (x0, y0) = layout.huePoint(0f)
        assertEquals(100f, x0, 1e-3f)
        assertEquals(16f, y0, 1e-3f)
    }

    @Test
    fun zones() {
        assertEquals(WheelLayout.Zone.SQUARE, layout.zoneAt(100f, 100f))
        assertEquals(WheelLayout.Zone.SQUARE, layout.zoneAt(100f, 100f - 71f))
        assertEquals(WheelLayout.Zone.RING, layout.zoneAt(100f, 100f - 73f))
        assertEquals(WheelLayout.Zone.RING, layout.zoneAt(100f, 10f))
        // Corners of the bounds (outside the ring) still grab the ring.
        assertEquals(WheelLayout.Zone.RING, layout.zoneAt(0f, 0f))
    }

    @Test
    fun saturationBrightnessFromSquareClamped() {
        val l = layout.squareLeft; val t = layout.squareTop; val side = layout.squareSide
        assertEquals(0f, layout.saturationAt(l), 1e-5f)
        assertEquals(1f, layout.saturationAt(l + side), 1e-5f)
        assertEquals(0.5f, layout.saturationAt(l + side / 2f), 1e-5f)
        assertEquals(0f, layout.saturationAt(l - 30f), 0f)
        assertEquals(1f, layout.saturationAt(l + side + 30f), 0f)
        assertEquals(1f, layout.brightnessAt(t), 1e-5f)
        assertEquals(0f, layout.brightnessAt(t + side), 1e-5f)
        assertEquals(1f, layout.brightnessAt(t - 50f), 0f)
        assertEquals(0f, layout.brightnessAt(t + side + 50f), 0f)
        val (x, y) = layout.svPoint(0.3f, 0.8f)
        assertEquals(0.3f, layout.saturationAt(x), 1e-5f)
        assertEquals(0.8f, layout.brightnessAt(y), 1e-5f)
    }

    @Test
    fun tinyWheelDoesNotProduceNegativeGeometry() {
        for (size in listOf(0f, 1f, 4f, 9f)) {
            val tiny = WheelLayout(size = size, ringWidth = size * 0.12f, pad = 4f)
            assert(tiny.outerRadius > 0f && tiny.ringWidth > 0f && tiny.squareHalf > 0f)
            val s = tiny.saturationAt(2f); val b = tiny.brightnessAt(2f)
            assert(s in 0f..1f && b in 0f..1f)
            tiny.hueAt(0f, 0f)
        }
    }

    @Test
    fun hsbKeepsHueForGrayAndBlack() {
        val prev = Hsb(210f, 0.6f, 0.7f)
        val gray = Hsb.fromColor(ColorUtils.rgb(128, 128, 128), prev)
        assertEquals(210f, gray.h, 0f)
        assertEquals(0f, gray.s, 0f)
        assertEquals(128 / 255f, gray.b, 1e-5f)
        val black = Hsb.fromColor(ColorUtils.rgb(0, 0, 0), prev)
        assertEquals(210f, black.h, 0f)
        assertEquals(0.6f, black.s, 0f)
        assertEquals(0f, black.b, 0f)
        val white = Hsb.fromColor(-1, prev)
        assertEquals(210f, white.h, 0f)
        assertEquals(0f, white.s, 0f)
        assertEquals(1f, white.b, 0f)
        // Chromatic colors are converted normally; no previous = plain conversion.
        val red = Hsb.fromColor(ColorUtils.rgb(255, 0, 0), prev)
        assertEquals(Hsb(0f, 1f, 1f), red)
        assertEquals(Hsb(0f, 0f, 0f), Hsb.fromColor(ColorUtils.rgb(0, 0, 0)))
    }

    @Test
    fun hsbRoundTripsOpaqueColors() {
        var prev: Hsb? = null
        for (r in 0..255 step 15) for (g in 0..255 step 17) for (b in 0..255 step 5) {
            val c = ColorUtils.rgb(r, g, b)
            val hsb = Hsb.fromColor(c, prev)
            assertEquals(c, hsb.toColor())
            prev = hsb
        }
        assertEquals(0x80FF0000.toInt(), Hsb(0f, 1f, 1f).toColor(0x80))
    }

    @Test
    fun displayValues() {
        val h = Hsb(359.7f, 0.506f, 0.004f)
        assertEquals(360, h.displayH())
        assertEquals(51, h.displayS())
        assertEquals(0, h.displayB())
    }

    @Test
    fun sliderFractionMapsThumbCenters() {
        assertEquals(0f, sliderFraction(10f, 220f, 10f), 0f)
        assertEquals(1f, sliderFraction(210f, 220f, 10f), 0f)
        assertEquals(0.5f, sliderFraction(110f, 220f, 10f), 1e-6f)
        assertEquals(0f, sliderFraction(-40f, 220f, 10f), 0f)
        assertEquals(1f, sliderFraction(400f, 220f, 10f), 0f)
        assertEquals(0f, sliderFraction(5f, 15f, 10f), 0f)
    }

    @Test
    fun hexInput() {
        assertEquals(0xFFFF0000.toInt(), parseHexInput("#FF0000", false))
        assertEquals(0xFFFF0000.toInt(), parseHexInput("ff0000", false))
        assertEquals(0xFFFF0000.toInt(), parseHexInput("f00", false))
        assertEquals(0x80FF0000.toInt(), parseHexInput("80FF0000", true))
        assertNull(parseHexInput("80FF0000", false))
        assertNull(parseHexInput("12345", true))
        assertNull(parseHexInput("GG0000", false))
        assertNull(parseHexInput("", false))
        assertNull(parseHexInput("-12345", false))
        assertEquals("FF0000", hexDigits(0xFFFF0000.toInt(), false))
        assertEquals("80FF0000", hexDigits(0x80FF0000.toInt(), true))
        assertEquals("00FF00", hexDigits(0x8000FF00.toInt(), false))
    }
}
