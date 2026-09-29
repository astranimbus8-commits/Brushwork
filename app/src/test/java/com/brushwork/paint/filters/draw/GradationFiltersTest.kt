package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.GradientStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GradationFiltersTest {
    private val ctx = FilterContext()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun alpha(c: Int) = c ushr 24

    @Test
    fun blendNormalAndMultiply() {
        val white = -1
        assertEquals(red, DrawBlend.composite(white, red, 1f, DrawBlend.NORMAL))
        assertEquals(red, DrawBlend.composite(white, red, 1f, DrawBlend.MULTIPLY))
        // Multiply of white onto red keeps red.
        assertEquals(red, DrawBlend.composite(red, white, 1f, DrawBlend.MULTIPLY))
        // On a transparent pixel the source is written with its alpha scaled by coverage.
        val half = DrawBlend.composite(0, red, 0.5f, DrawBlend.SCREEN)
        assertEquals(128, alpha(half))
        assertEquals(0xFF0000, half and 0xFFFFFF)
        // Zero coverage leaves the pixel untouched; Replace writes transparent colors too.
        assertEquals(blue, DrawBlend.composite(blue, red, 0f, DrawBlend.NORMAL))
        assertEquals(0, alpha(DrawBlend.composite(blue, 0x00FF0000, 1f, DrawBlend.REPLACE)))
    }

    @Test
    fun waveProfilesAreFilteredAndBounded() {
        for (shape in 0..3) {
            assertEquals(0.0, Wave.point(shape, 0.0), 1e-9)
            for (i in -40..40) {
                val s = i * 0.173
                val v = Wave.profile(shape, s, 0.3)
                assertTrue(v in 0.0..1.0)
            }
        }
        // A footprint of a whole period averages to the mean.
        assertEquals(0.5, Wave.profile(Wave.SAW, 0.37, 1.0), 1e-9)
        assertEquals(0.5, Wave.profile(Wave.MIRROR, 1.3, 2.0), 1e-9)
        // The saw's wrap is antialiased: exactly at the jump the pixel is half/half.
        assertEquals(0.5, Wave.profile(Wave.SAW, 1.0, 0.01), 0.01)
    }

    @Test
    fun gradientLutInterpolatesInPremultipliedSpace() {
        val lut = GradientLut(listOf(GradientStop(0f, red), GradientStop(1f, 0x000000FF)))
        val mid = lut.at(0.5f)
        // Fading to a transparent blue must not turn purple: the color stays red.
        assertEquals(0xFF0000, mid and 0xFFFFFF)
        assertEquals(128f, alpha(mid).toFloat(), 2f)
        assertEquals(red, lut.at(-3f))
        assertEquals(red, lut.at(Float.NaN))
    }

    @Test
    fun parallelDefaultFadesFromStartToEnd() {
        val f = ParallelGradationFilter()
        val src = PixelBuffer(40, 100)
        val out = f.apply(src, f.defaultValues(), ctx)
        assertEquals(255, alpha(out[5, 5]))                   // before the start point
        assertEquals(0, alpha(out[5, 95]))                    // after the end point
        assertEquals(128f, alpha(out[20, 50]).toFloat(), 6f) // halfway
        for (y in 0 until 100) for (x in 1 until 40) assertEquals(out[0, y], out[x, y]) // bands are horizontal
        for (y in 1 until 100) assertTrue(alpha(out[0, y]) <= alpha(out[0, y - 1]))       // monotone
    }

    @Test
    fun parallelTwoColorsOverOpaqueLayer() {
        val f = ParallelGradationFilter()
        val v = f.defaultValues()
            .set("colors", GradationParams.MODE_TWO_COLORS).set("color", red).set("color2", blue)
            .set("start", floatArrayOf(0f, 0f)).set("end", floatArrayOf(1f, 0f))
        val src = PixelBuffer.filled(64, 8, -1)
        val out = f.apply(src, v, ctx)
        assertTrue(ColorUtils.red(out[0, 4]) > 245 && ColorUtils.blue(out[0, 4]) < 10)
        assertTrue(ColorUtils.blue(out[63, 4]) > 245 && ColorUtils.red(out[63, 4]) < 10)
        for (x in 0 until 64) assertEquals(255, alpha(out[x, 4]))
    }

    @Test
    fun zeroOpacityReturnsUnchangedCopy() {
        for (f in listOf(ParallelGradationFilter(), ConcentricGradationFilter(), RadialLineGradationFilter())) {
            val src = PixelBuffer.filled(9, 9, 0xFF123456.toInt())
            val out = f.apply(src, f.defaultValues().set("opacity", 0f), ctx)
            assertNotSame(src, out)
            assertTrue(src.pixels.contentEquals(out.pixels))
        }
    }

    @Test
    fun repeatedStripesAtFullContrastAreHardButAntialiased() {
        val f = ParallelGradationFilter()
        val v = f.defaultValues().set("repeat", Wave.MIRROR).set("contrast", 100f)
            .set("start", floatArrayOf(0f, 0f)).set("end", floatArrayOf(0f, 0.1033f))
        val out = f.apply(PixelBuffer(4, 200), v, ctx)
        var partial = 0
        for (y in 0 until 200) { val a = alpha(out[0, y]); if (a in 20..235) partial++ }
        // 200 px at a ~20.7 px ramp -> ~10 transitions at varying sub-pixel offsets, each with
        // at most ~2 px of partial coverage.
        assertTrue("partial=$partial", partial in 3..30)
        val (lo, hi) = (0 until 200).map { alpha(out[0, it]) }.let { it.min() to it.max() }
        assertEquals(0, lo); assertEquals(255, hi)
    }

    @Test
    fun concentricIsRadiallySymmetric() {
        val f = ConcentricGradationFilter()
        val out = f.apply(PixelBuffer(61, 61), f.defaultValues(), ctx)
        for (d in 0..30) {
            val a = out[30 + d, 30]
            assertEquals(a, out[30 - d, 30]); assertEquals(a, out[30, 30 + d]); assertEquals(a, out[30, 30 - d])
        }
        assertEquals(255f, alpha(out[30, 30]).toFloat(), 3f)
        assertTrue(alpha(out[0, 0]) < 12)
    }

    @Test
    fun concentricEllipseIsNarrowerAlongMinorAxis() {
        val f = ConcentricGradationFilter()
        val v = f.defaultValues().set("ellipse", 50f)
        val out = f.apply(PixelBuffer(81, 81), v, ctx)
        // Same distance from the center: the vertical (minor) axis fades faster.
        assertTrue(alpha(out[40 + 20, 40]) > alpha(out[40, 40 + 20]) + 20)
    }

    @Test
    fun radialLinesHaveRotationalSymmetry() {
        val f = RadialLineGradationFilter()
        val v = f.defaultValues().set("count", 4f)
        val n = 64
        val out = f.apply(PixelBuffer(n, n), v, ctx)
        var maxDiff = 0
        for (y in 0 until n) for (x in 0 until n) {
            maxDiff = maxOf(maxDiff, abs(alpha(out[x, y]) - alpha(out[n - 1 - y, x])))
        }
        assertTrue("maxDiff=$maxDiff", maxDiff <= 2)
        // Rays exist: both near-transparent and near-opaque pixels on a ring.
        val ring = (0 until 360).map { a ->
            val r = 25.0; val t = Math.toRadians(a.toDouble())
            alpha(out[(32 + r * kotlin.math.cos(t)).toInt(), (32 + r * kotlin.math.sin(t)).toInt()])
        }
        assertTrue(ring.min() < 30 && ring.max() > 225)
    }

    @Test
    fun conicSweepHasNoSeamOppositeTheStart() {
        val f = RadialLineGradationFilter()
        val v = f.defaultValues().set("repeat", Wave.CLAMP).set("contrast", 0f)
        val out = f.apply(PixelBuffer(101, 101), v, ctx)
        // Rotation 0 starts the sweep pointing right: just below the +x axis is the start (opaque),
        // just above is the end (transparent). Opposite (left) side is exactly halfway, no jump.
        assertTrue(alpha(out[90, 52]) > 230)
        assertTrue(alpha(out[90, 48]) < 25)
        assertEquals(alpha(out[10, 49]).toFloat(), alpha(out[10, 51]).toFloat(), 8f)
        assertEquals(128f, alpha(out[10, 50]).toFloat(), 10f)
    }

    @Test
    fun previewScaleMatchesFullResolution() {
        val f = ConcentricGradationFilter()
        val v = f.defaultValues().set("repeat", Wave.COSINE).set("radius", 20f)
        val full = f.apply(PixelBuffer(200, 200), v, ctx)
        val small = f.apply(PixelBuffer(50, 50), v, FilterContext(scale = 0.25f))
        var maxDiff = 0
        for (y in 0 until 50) for (x in 0 until 50) {
            maxDiff = maxOf(maxDiff, abs(alpha(small[x, y]) - alpha(full[x * 4 + 2, y * 4 + 2])))
        }
        assertTrue("maxDiff=$maxDiff", maxDiff < 40)
    }

    @Test
    fun zeroLengthParallelIsAHardEdge() {
        val f = ParallelGradationFilter()
        val v = f.defaultValues().set("start", floatArrayOf(0.5f, 0.5f)).set("end", floatArrayOf(0.5f, 0.5f))
        val out = f.apply(PixelBuffer(10, 10), v, ctx)
        assertEquals(255, alpha(out[3, 2]))
        assertEquals(0, alpha(out[3, 7]))
    }
}
