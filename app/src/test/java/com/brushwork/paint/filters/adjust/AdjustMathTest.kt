package com.brushwork.paint.filters.adjust

import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.GradientStop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AdjustMathTest {

    @Test
    fun inPlaceGaussianMatchesReferenceBlur() {
        val rnd = Random(7)
        for ((w, h) in listOf(45 to 37, 1 to 9, 70 to 1, 33 to 64)) {
            for (sigma in listOf(0.8f, 2.3f, 6f)) {
                val plane = FloatArray(w * h) { rnd.nextFloat() }
                val expected = FilterMath.gaussianBlurPlane(plane, w, h, sigma)
                val actual = plane.copyOf()
                AdjustMath.gaussianBlurInPlace(actual, w, h, sigma, null)
                for (i in plane.indices) assertEquals("${w}x$h sigma $sigma at $i", expected[i], actual[i], 1e-4f)
            }
        }
    }

    @Test
    fun hslRoundTrip() {
        val hsl = FloatArray(3); val rgb = FloatArray(3)
        val rnd = Random(3)
        repeat(2000) {
            val r = rnd.nextInt(256); val g = rnd.nextInt(256); val b = rnd.nextInt(256)
            AdjustMath.rgbToHsl(r / 255f, g / 255f, b / 255f, hsl)
            assertTrue(hsl[0] in 0f..1f && hsl[1] in 0f..1f && hsl[2] in 0f..1f)
            AdjustMath.hslToRgb(hsl[0], hsl[1], hsl[2], rgb)
            val c = AdjustMath.pack(255, rgb[0], rgb[1], rgb[2])
            assertTrue("$r,$g,$b", abs(r(c) - r) <= 1 && abs(g(c) - g) <= 1 && abs(b(c) - b) <= 1)
        }
    }

    @Test
    fun levelsAndGradientTables() {
        assertArrayEquals(IntArray(256) { it }, AdjustMath.levelsLut(0f, 255f, 1f, 0f, 255f))
        val lut = AdjustMath.levelsLut(0f, 255f, 0.5f, 0f, 255f)
        for (i in 1..254) assertTrue(lut[i] <= i)

        val g = AdjustMath.gradientLut(listOf(GradientStop(0.75f, rgb(0, 0, 255)), GradientStop(0.25f, rgb(255, 0, 0))))
        assertEquals(rgb(255, 0, 0), g[0]); assertEquals(rgb(255, 0, 0), g[60])
        assertEquals(rgb(0, 0, 255), g[200]); assertEquals(rgb(0, 0, 255), g[255])
        assertTrue(abs(r(g[128]) - 127) <= 2)
        assertTrue(AdjustMath.gradientLut(listOf(GradientStop(0.3f, rgb(1, 2, 3)))).all { it == rgb(1, 2, 3) })
        assertEquals(gray(77), AdjustMath.gradientLut(emptyList())[77])
    }
}
