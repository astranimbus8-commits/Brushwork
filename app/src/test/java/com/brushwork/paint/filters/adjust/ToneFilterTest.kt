package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** v1.5 §4.8: the Tone filter (Exposure, Contrast, Highlights, Shadows, Whites, Blacks). */
class ToneFilterTest {
    private val tone = ToneFilter()

    private fun values(
        exposure: Float = 0f, contrast: Float = 0f, highlights: Float = 0f,
        shadows: Float = 0f, whites: Float = 0f, blacks: Float = 0f,
    ): FilterValues = tone.defaultValues()
        .set(ToneFilter.EXPOSURE, exposure).set(ToneFilter.CONTRAST, contrast).set(ToneFilter.HIGHLIGHTS, highlights)
        .set(ToneFilter.SHADOWS, shadows).set(ToneFilter.WHITES, whites).set(ToneFilter.BLACKS, blacks)

    private fun map(v: FilterValues, vararg colors: Int): IntArray {
        val px = colors.copyOf()
        tone.pixelMapper(v).map(px, 0, px.size)
        return px
    }

    private fun mapGray(v: FilterValues, code: Int): Int = ColorUtils.red(map(v, ColorUtils.gray(code))[0])

    private fun randomImage(w: Int, h: Int, seed: Int): PixelBuffer {
        val rnd = Random(seed)
        val b = PixelBuffer(w, h)
        for (i in b.pixels.indices) b.pixels[i] = if (i % 9 == 0) rnd.nextInt() and 0x00FFFFFF else rnd.nextInt()
        return b
    }

    @Test
    fun isFirstInColorAdjustmentAndAdjustmentCapable() {
        assertEquals(ToneFilter.ID, adjustFilters.first().id)
        assertTrue(tone.isAdjustmentCapable)
        assertEquals(listOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks"), tone.params.map { it.key })
    }

    @Test
    fun theDefaultsAreTheExactIdentity() {
        val src = sampleImage(40, 30)
        assertArrayEquals(src.pixels, tone.apply(src, tone.defaultValues(), FilterContext()).pixels)
        val rnd = randomImage(64, 64, 3)
        val px = rnd.pixels.copyOf()
        tone.pixelMapper(tone.defaultValues()).map(px, 0, px.size)
        assertArrayEquals(rnd.pixels, px)
        assertTrue(ToneSettings.of(tone.defaultValues()).isIdentity)
    }

    @Test
    fun oneStopDoublesLinearMidGray() {
        for (code in listOf(60, 100, 118, 140)) {
            val lin = ToneMath.srgbToLinear(code / 255.0)
            val up = (ToneMath.linearToSrgb(lin * 2.0) * 255.0).roundToInt()
            val down = (ToneMath.linearToSrgb(lin / 2.0) * 255.0).roundToInt()
            assertEquals("+1 EV of $code", up.toFloat(), mapGray(values(exposure = 1f), code).toFloat(), 1f)
            assertEquals("-1 EV of $code", down.toFloat(), mapGray(values(exposure = -1f), code).toFloat(), 1f)
        }
        // Linear mid-gray (18 %) doubles: 118 -> about 162.
        val out = mapGray(values(exposure = 1f), 118)
        assertEquals(2.0, ToneMath.srgbToLinear(out / 255.0) / ToneMath.srgbToLinear(118 / 255.0), 0.03)
    }

    @Test
    fun theToneCurveIsMonotoneOverA5x6Grid() {
        val steps = floatArrayOf(-100f, -50f, 0f, 50f, 100f)
        val evs = floatArrayOf(-5f, -2.5f, 0f, 2.5f, 5f)
        val ramp = IntArray(256) { ColorUtils.gray(it) }
        var combos = 0
        for (ev in evs) for (c in steps) for (hi in steps) for (sh in steps) for (wh in steps) for (bl in steps) {
            val s = ToneSettings(ev, c, hi, sh, wh, bl)
            if (ev == evs[0]) {
                // The curve itself does not depend on the exposure: check it once per tone setting.
                val curve = ToneMath.toneCurve(s)
                for (i in 1 until curve.size) assertTrue("curve $s at $i", curve[i] >= curve[i - 1])
                assertTrue(curve.first() >= 0f && curve.last() <= 1f)
            }
            // And the whole mapping keeps grays in order (exposure + shoulder + curve).
            val px = ramp.copyOf()
            (ToneMath.mapper(s) ?: AdjustMath.IDENTITY_MAPPER).map(px, 0, px.size)
            for (i in 1 until 256) assertTrue("ramp $s at $i", ColorUtils.red(px[i]) >= ColorUtils.red(px[i - 1]))
            combos++
        }
        assertEquals(15625, combos)
    }

    @Test
    fun alphaIsPreservedAndTransparentPixelsAreLeftAlone() {
        val rnd = Random(5)
        val src = randomImage(50, 40, 7)
        repeat(20) {
            val v = values(
                rnd.nextFloat() * 10f - 5f, rnd.nextFloat() * 200f - 100f, rnd.nextFloat() * 200f - 100f,
                rnd.nextFloat() * 200f - 100f, rnd.nextFloat() * 200f - 100f, rnd.nextFloat() * 200f - 100f,
            )
            val out = tone.apply(src, v, FilterContext()).pixels
            for (i in out.indices) {
                assertEquals("alpha at $i", src.pixels[i] ushr 24, out[i] ushr 24)
                if (src.pixels[i] ushr 24 == 0) assertEquals(src.pixels[i], out[i])
            }
        }
    }

    private fun hueOf(c: Int): Float {
        val r = ColorUtils.red(c); val g = ColorUtils.green(c); val b = ColorUtils.blue(c)
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val d = (mx - mn).toFloat()
        val h = when (mx) {
            r -> 60f * ((g - b) / d)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return (h + 360f) % 360f
    }

    private fun hueDistance(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return min(d, 360f - d) }

    @Test
    fun saturatedColorsKeepTheirHue() {
        val colors = listOf(
            ColorUtils.rgb(160, 40, 20), ColorUtils.rgb(40, 150, 30), ColorUtils.rgb(30, 60, 170), ColorUtils.rgb(170, 140, 20),
            ColorUtils.rgb(150, 30, 140), ColorUtils.rgb(20, 140, 150), ColorUtils.rgb(200, 120, 90), ColorUtils.rgb(90, 60, 40),
            ColorUtils.rgb(120, 30, 60), ColorUtils.rgb(60, 110, 20),
        )
        val settings = listOf(values(exposure = 1f), values(exposure = -1f), values(shadows = 50f), values(shadows = -50f))
        var checked = 0
        for (v in settings) for (c in colors) {
            val out = map(v, c)[0]
            val mx = max(ColorUtils.red(out), max(ColorUtils.green(out), ColorUtils.blue(out)))
            if (mx >= 254) continue // clipping: the desaturation is allowed to move it
            assertTrue("hue of ${ColorUtils.toHex(c)} -> ${ColorUtils.toHex(out)}", hueDistance(hueOf(c), hueOf(out)) <= 2f)
            checked++
        }
        assertTrue("most samples are unclipped", checked >= 35)
    }

    /** The sRGB value (0..1) a gray of value [v] maps to. */
    private fun grayOut(s: FilterValues, v: Float): Float {
        val code = (v * 255f).roundToInt()
        return mapGray(s, code) / 255f - (code / 255f - v)
    }

    @Test
    fun highlightsAndShadowsWorkOnTheirHalfAndMirrorEachOther() {
        // The design formula: v += (highlights / 100) * 0.18 * sin²(π(v − 0.4) / 0.6). At v = 0.75
        // that is −0.168 for −100 (the design text estimates "about −0.12"; the formula governs).
        val expected = -0.18f * ToneMath.highlightsWeight(0.75f)
        assertEquals(-0.168f, expected, 0.002f)
        assertEquals(expected, ToneMath.curveValue(0.75f, ToneSettings(highlights = -100f)) - 0.75f, 1e-5f)
        assertEquals(0.75f + expected, grayOut(values(highlights = -100f), 0.75f), 0.006f)
        assertTrue(abs(grayOut(values(highlights = -100f), 0.25f) - 0.25f) < 0.01f)
        // Shadows is the mirror: it lifts v = 0.25 by the same amount and leaves 0.75 alone.
        assertEquals(0.25f - expected, grayOut(values(shadows = 100f), 0.25f), 0.006f)
        assertTrue(abs(grayOut(values(shadows = 100f), 0.75f) - 0.75f) < 0.01f)
        assertEquals(ToneMath.shadowsWeight(0.25f), ToneMath.highlightsWeight(0.75f), 1e-6f)
        // Positive highlights brighten the upper middle, negative shadows darken the lower one.
        assertTrue(grayOut(values(highlights = 100f), 0.7f) > 0.8f)
        assertTrue(grayOut(values(shadows = -100f), 0.3f) < 0.2f)
    }

    @Test
    fun whitesAndBlacksMoveTheEndsAndNotTheMiddle() {
        for (amount in listOf(-100f, 100f)) {
            assertTrue(abs(grayOut(values(whites = amount), 0.5f) - 0.5f) < 0.02f)
            assertTrue(abs(grayOut(values(blacks = amount), 0.5f) - 0.5f) < 0.02f)
        }
        assertTrue(grayOut(values(whites = 100f), 0.9f) > 0.95f)
        assertTrue(grayOut(values(whites = -100f), 0.9f) < 0.85f)
        assertTrue(grayOut(values(whites = -100f), 1f) < 0.92f)
        assertTrue(grayOut(values(blacks = -100f), 0.1f) < 0.05f)
        assertTrue(grayOut(values(blacks = 100f), 0.1f) > 0.15f)
        assertTrue("lifted blacks", grayOut(values(blacks = 100f), 0f) > 0.08f)
    }

    @Test
    fun contrastIsAnSCurveAroundMidGray() {
        assertTrue(grayOut(values(contrast = 100f), 0.25f) < 0.18f)
        assertTrue(grayOut(values(contrast = 100f), 0.75f) > 0.82f)
        assertTrue(abs(grayOut(values(contrast = 100f), 0.5f) - 0.5f) < 0.01f)
        assertTrue(grayOut(values(contrast = -100f), 0.25f) > 0.32f)
        assertTrue(grayOut(values(contrast = -100f), 0.75f) < 0.68f)
    }

    @Test
    fun theExposureShoulderNeverDimsWhiteAndMatchesTheDesignForBigStops() {
        assertEquals(Float.POSITIVE_INFINITY, ToneMath.shoulderWidth(0f), 0f)
        assertEquals(Float.POSITIVE_INFINITY, ToneMath.shoulderWidth(-2f), 0f)
        for (ev in listOf(2f, 3f, 5f)) assertEquals("width at $ev EV", 0.2f, ToneMath.shoulderWidth(ev), 1e-3f)
        for (ev in listOf(0.001f, 0.01f, 0.1f, 0.5f, 1f, 2f, 5f)) {
            val s = ToneMath.shoulderWidth(ev)
            // White x 2^ev lands on white; the shoulder starts without a kink and stays below 1.
            assertEquals("white at $ev EV", 1f, ToneMath.shoulder(2f.pow(ev), s), 2e-4f)
            assertEquals(0.8f, ToneMath.shoulder(0.8f, s), 0f)
            assertTrue(ToneMath.shoulder(0.9f * 2f.pow(ev), s) <= 1f)
            assertTrue(ToneMath.shoulder(0.9f * 2f.pow(ev), s) > 0.9f)
            assertEquals("white pixel at $ev EV", 255, mapGray(values(exposure = ev), 255))
            // Brightening never darkens anything.
            for (code in listOf(10, 128, 200, 230, 250)) assertTrue("$code at $ev EV", mapGray(values(exposure = ev), code) >= code)
        }
        // A saturated color pushed past white keeps its luminance order and stays a valid color.
        val out = map(values(exposure = 3f), ColorUtils.rgb(255, 0, 0))[0]
        assertTrue(ColorUtils.red(out) == 255 && ColorUtils.green(out) > 0)
    }

    @Test
    fun mapperEqualsApplyAndChunksDoNotMatter() {
        val src = randomImage(37, 23, 11)
        val rnd = Random(13)
        repeat(30) {
            val v = values(
                rnd.nextFloat() * 10f - 5f, rnd.nextFloat() * 200f - 100f, rnd.nextFloat() * 200f - 100f,
                rnd.nextFloat() * 200f - 100f, rnd.nextFloat() * 200f - 100f, rnd.nextFloat() * 200f - 100f,
            )
            val expected = tone.apply(src, v, FilterContext()).pixels
            val px = src.pixels.copyOf()
            val m = tone.pixelMapper(v)
            m.map(px, 0, 100); m.map(px, 100, 101); m.map(px, 101, px.size)
            assertArrayEquals(expected, px)
        }
    }

    @Test
    fun mappingIsFastEnough() {
        // A loose guard (the device budget is ~15 ns per pixel and core): every stage enabled.
        val m = ToneMath.mapper(ToneSettings(0.7f, 30f, -40f, 35f, 20f, -15f))!!
        val px = randomImage(512, 512, 1).pixels
        m.map(px.copyOf(), 0, px.size) // warm up
        val t0 = System.nanoTime()
        repeat(4) { m.map(px.copyOf(), 0, px.size) }
        val nsPerPx = (System.nanoTime() - t0).toDouble() / (4.0 * px.size)
        println("Tone mapper: %.1f ns/px".format(nsPerPx))
        assertTrue("Tone mapper took $nsPerPx ns/px", nsPerPx < 1000.0)
    }
}
