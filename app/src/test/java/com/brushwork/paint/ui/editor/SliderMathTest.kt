package com.brushwork.paint.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderMathTest {
    @Test
    fun sizeSliderEndpoints() {
        assertEquals(0.5f, SliderMath.fractionToSize(0f), 0f)
        assertEquals(1000f, SliderMath.fractionToSize(1f), 0f)
        assertEquals(0f, SliderMath.sizeToFraction(0.5f), 1e-6f)
        assertEquals(1f, SliderMath.sizeToFraction(1000f), 1e-6f)
        // Out-of-range sizes clamp.
        assertEquals(0f, SliderMath.sizeToFraction(0.01f), 0f)
        assertEquals(1f, SliderMath.sizeToFraction(5000f), 0f)
    }

    @Test
    fun sizeSliderIsLogarithmic() {
        // The geometric middle of 0.5..1000 is at the slider's middle.
        assertEquals(0.5f, SliderMath.sizeToFraction(22.36068f), 1e-4f)
        // Small sizes get a big part of the travel.
        assertTrue(SliderMath.sizeToFraction(10f) > 0.35f)
    }

    @Test
    fun sizeRoundTripsThroughTheSlider() {
        for (s in listOf(0.5f, 1.3f, 4.7f, 12f, 88f, 350f, 1000f)) {
            assertEquals(s, SliderMath.fractionToSize(SliderMath.sizeToFraction(s)), 0.051f)
        }
    }

    @Test
    fun sizeRounding() {
        assertEquals(2.5f, SliderMath.roundSize(2.46f), 1e-6f)
        assertEquals(13f, SliderMath.roundSize(12.6f), 0f)
        assertEquals("2.5", SliderMath.formatSize(2.5f))
        assertEquals("3", SliderMath.formatSize(3f))
        assertEquals("120", SliderMath.formatSize(120f))
    }

    @Test
    fun typedSizesAreParsedClampedAndRounded() {
        assertEquals(12.5f, SliderMath.parseSize("12.5")!!, 0f)
        assertEquals(12.5f, SliderMath.parseSize(" 12,5 px ")!!, 0f)
        assertEquals(12.6f, SliderMath.parseSize("12.6")!!, 1e-5f) // the user's number, to the tenth
        assertEquals(2.3f, SliderMath.parseSize("2.34")!!, 1e-6f)
        assertEquals(250f, SliderMath.parseSize("250.04")!!, 1e-4f)
        assertEquals("12.5", SliderMath.formatSize(SliderMath.parseSize("12.5")!!))
        assertEquals(1000f, SliderMath.parseSize("5000")!!, 0f)
        assertEquals(0.5f, SliderMath.parseSize("0")!!, 0f)
        assertEquals(0.5f, SliderMath.parseSize("-3")!!, 0f)
        for (bad in listOf("", "  ", "abc", "px", "NaN", "Infinity", "-Infinity", "1e999", "1.2.3")) {
            assertNull("\"$bad\" is refused", SliderMath.parseSize(bad))
        }
    }

    @Test
    fun typedPercentsBecomeWholePercentFractions() {
        assertEquals(0.85f, SliderMath.parsePercent("85")!!, 1e-6f)
        assertEquals(0.85f, SliderMath.parsePercent("85 %")!!, 1e-6f)
        assertEquals(0.86f, SliderMath.parsePercent("85.6")!!, 1e-6f)
        assertEquals(1f, SliderMath.parsePercent("250")!!, 0f)
        assertEquals(0f, SliderMath.parsePercent("-5")!!, 0f)
        assertNull(SliderMath.parsePercent("half"))
        assertNull(SliderMath.parsePercent("NaN"))
    }

    @Test
    fun sizeStepsGrowWithTheSizeAndLandOnTheGrid() {
        assertEquals(13f, SliderMath.stepSize(12f, up = true), 1e-5f)
        assertEquals(11f, SliderMath.stepSize(12f, up = false), 1e-5f)
        assertEquals(13f, SliderMath.stepSize(12.3f, up = true), 1e-5f)
        assertEquals(12f, SliderMath.stepSize(12.3f, up = false), 1e-5f)
        assertEquals(105f, SliderMath.stepSize(100f, up = true), 1e-5f)
        assertEquals("down from 100 uses the step below", 99f, SliderMath.stepSize(100f, up = false), 1e-5f)
        assertEquals(10f, SliderMath.stepSize(9.5f, up = true), 1e-5f)
        assertEquals(9.5f, SliderMath.stepSize(10f, up = false), 1e-5f)
        assertEquals(1.4f, SliderMath.stepSize(1.3f, up = true), 1e-5f)
        assertEquals(0.5f, SliderMath.stepSize(0.5f, up = false), 0f)
        assertEquals(1000f, SliderMath.stepSize(1000f, up = true), 0f)
        // Stepping up then down comes back.
        for (s in listOf(0.7f, 3f, 42f, 400f)) assertEquals(s, SliderMath.stepSize(SliderMath.stepSize(s, true), false), 1e-5f)
        assertEquals(0.41f, SliderMath.stepPercent(0.4f, up = true), 1e-6f)
        assertEquals(0f, SliderMath.stepPercent(0f, up = false), 0f)
        assertEquals(1f, SliderMath.stepPercent(1f, up = true), 0f)
    }

    @Test
    fun percentAndZoomLabels() {
        assertEquals("85%", SliderMath.formatPercent(0.849f))
        assertEquals("100%", SliderMath.formatPercent(1.2f))
        assertEquals("150%", SliderMath.formatZoom(1.5f))
        assertEquals("6400%", SliderMath.formatZoom(64f))
        assertEquals("2.5%", SliderMath.formatZoom(0.025f))
        assertEquals("1%", SliderMath.formatZoom(0.01f))
    }
}
