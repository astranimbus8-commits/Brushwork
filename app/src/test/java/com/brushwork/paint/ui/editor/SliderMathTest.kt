package com.brushwork.paint.ui.editor

import org.junit.Assert.assertEquals
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
    fun percentAndZoomLabels() {
        assertEquals("85%", SliderMath.formatPercent(0.849f))
        assertEquals("100%", SliderMath.formatPercent(1.2f))
        assertEquals("150%", SliderMath.formatZoom(1.5f))
        assertEquals("6400%", SliderMath.formatZoom(64f))
        assertEquals("2.5%", SliderMath.formatZoom(0.025f))
        assertEquals("1%", SliderMath.formatZoom(0.01f))
    }
}
