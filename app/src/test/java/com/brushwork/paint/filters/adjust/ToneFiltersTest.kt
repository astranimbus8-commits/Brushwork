package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneFiltersTest {

    @Test
    fun registryContainsEveryAdjustFilter() {
        // v1.5: Tone joined, first in the list.
        assertEquals(16, adjustFilters.size)
        assertEquals("adjust.tone", adjustFilters.first().id)
        assertEquals(adjustFilters.size, adjustFilters.map { it.id }.toSet().size)
        for (f in adjustFilters) {
            assertTrue(f.id, f.id.startsWith("adjust."))
            assertEquals(f.id, FilterCategory.ADJUST, f.category)
            assertTrue(f.id, FilterRegistry.byId(f.id) === f)
            assertEquals(f.id, f.params.size, f.params.map { it.key }.toSet().size)
        }
    }

    @Test
    fun neutralSettingsAreIdentity() {
        val src = sampleImage()
        val cases = listOf(
            adjust<BrightnessContrastFilter>().run(src),
            adjust<ToneCurveFilter>().run(src),
            adjust<ToneCurveFilter>().run(src, "curve" to listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.3f), CurvePoint(1f, 1f))),
            adjust<LevelsFilter>().run(src),
            adjust<LevelsFilter>().run(src, "channel" to 2),
            adjust<InvertFilter>().run(src, "amount" to 0f),
            adjust<GrayscaleFilter>().run(src, "amount" to 0f),
            adjust<ColorBalanceFilter>().run(src),
            adjust<HueSaturationFilter>().run(src),
            adjust<HueSaturationFilter>().run(src, "hue" to 360f),
            adjust<ReplaceColorFilter>().run(src, "mode" to 0),
            adjust<GradationMapFilter>().run(src, "opacity" to 0f),
            adjust<MonocolorFilter>().run(src, "amount" to 0f),
            adjust<ChangeDrawingColorFilter>().run(src, "amount" to 0f),
        )
        cases.forEachIndexed { i, out ->
            for (p in src.pixels.indices) {
                val s = src.pixels[p]; val o = out.pixels[p]
                if (a(s) == 0) continue
                // Hue 360 goes through an HSL round trip, which may be off by one.
                val tol = if (i == 9) 1 else 0
                assertTrue("case $i pixel $p: ${Integer.toHexString(s)} -> ${Integer.toHexString(o)}",
                    a(s) == a(o) && abs(r(s) - r(o)) <= tol && abs(g(s) - g(o)) <= tol && abs(b(s) - b(o)) <= tol)
            }
        }
    }

    @Test
    fun colorAdjustmentsPreserveAlphaAndTransparentPixels() {
        val src = sampleImage()
        val tweaked = listOf(
            adjust<BrightnessContrastFilter>().run(src, "brightness" to 40f, "contrast" to 30f),
            adjust<ToneCurveFilter>().run(src, "curve" to listOf(CurvePoint(0f, 0.1f), CurvePoint(0.5f, 0.8f), CurvePoint(1f, 0.9f))),
            adjust<LevelsFilter>().run(src, "inBlack" to 30f, "gamma" to 1.7f, "auto" to true),
            adjust<PosterizeFilter>().run(src),
            adjust<InvertFilter>().run(src),
            adjust<GrayscaleFilter>().run(src, "method" to 2),
            adjust<BlackWhiteFilter>().run(src, "smoothing" to 2f, "antialias" to true),
            adjust<ColorBalanceFilter>().run(src, "midtonesCR" to 60f, "shadowsYB" to -40f),
            adjust<HueSaturationFilter>().run(src, "hue" to 77f, "saturation" to 50f, "brightness" to -20f),
            adjust<HueSaturationFilter>().run(src, "colorize" to true),
            adjust<ReplaceColorFilter>().run(src, "tolerance" to 100f),
            adjust<GradationMapFilter>().run(src, "opacity" to 70f),
            adjust<MonocolorFilter>().run(src),
            adjust<ChangeDrawingColorFilter>().run(src, "amount" to 60f),
        )
        tweaked.forEachIndexed { i, out ->
            for (p in src.pixels.indices) {
                assertEquals("case $i alpha at $p", a(src.pixels[p]), a(out.pixels[p]))
                if (a(src.pixels[p]) == 0) assertEquals("case $i transparent pixel $p changed", src.pixels[p], out.pixels[p])
            }
        }
    }

    @Test
    fun brightnessAndContrastExtremes() {
        val src = row(gray(0), gray(64), gray(100), gray(200), gray(255))
        val f = adjust<BrightnessContrastFilter>()
        assertTrue(f.run(src, "brightness" to 100f).pixels.all { it == gray(255) })
        assertTrue(f.run(src, "brightness" to -100f).pixels.all { it == gray(0) })
        assertTrue(f.run(src, "contrast" to -100f).pixels.all { it == gray(128) })
        val hard = f.run(src, "contrast" to 100f).pixels
        assertArrayEquals(intArrayOf(gray(0), gray(0), gray(0), gray(255), gray(255)), hard)
        // Positive brightness lightens monotonically without touching white.
        val lighter = f.run(src, "brightness" to 30f).pixels
        for (i in 0 until 4) assertTrue(r(lighter[i]) > r(src.pixels[i]))
        assertEquals(255, r(lighter[4]))
        // Contrast pivots around mid-gray (127.5): its neighbours barely move.
        val mid = f.run(row(gray(127), gray(128)), "contrast" to 60f).pixels
        assertTrue(r(mid[0]) in 126..127 && r(mid[1]) in 128..129)
    }

    @Test
    fun toneCurvePassesThroughPointsWithoutOvershoot() {
        val lut = AdjustMath.curveLut(listOf(CurvePoint(0f, 0.2f), CurvePoint(0.4f, 0.2f), CurvePoint(0.6f, 0.8f), CurvePoint(1f, 0.8f)))
        for (i in 0..102) assertEquals("flat low segment at $i", 51, lut[i])
        for (i in 153..255) assertEquals("flat high segment at $i", 204, lut[i])
        for (i in 1..255) assertTrue("monotone at $i", lut[i] >= lut[i - 1])

        val s = AdjustMath.curveLut(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.5f), CurvePoint(1f, 1f)))
        assertEquals(0, s[0]); assertEquals(255, s[255])
        assertTrue(abs(s[64] - 128) <= 1)
        for (i in 1..255) assertTrue(s[i] >= s[i - 1])

        // Robust to unsorted / duplicate / degenerate input.
        assertArrayEquals(IntArray(256) { it }, AdjustMath.curveLut(emptyList()))
        assertTrue(AdjustMath.curveLut(listOf(CurvePoint(0.3f, 0.6f))).all { it == 153 })
        val messy = AdjustMath.curveLut(listOf(CurvePoint(1f, 1f), CurvePoint(0f, 0f), CurvePoint(0.5f, 0.2f), CurvePoint(0.5f, 0.7f), CurvePoint(1.4f, -3f)))
        // Sorted, clamped and de-duplicated (last point wins): (0,0) (0.5,0.7) (1,0).
        assertTrue(messy.all { it in 0..255 })
        assertTrue(abs(messy[127] - 179) <= 2)
        assertEquals(0, messy[255])
    }

    @Test
    fun sampleCurveIsWhatTheFilterApplies() {
        val pts = listOf(CurvePoint(0f, 0.1f), CurvePoint(0.3f, 0.6f), CurvePoint(0.7f, 0.65f), CurvePoint(1f, 0.95f))
        val s = AdjustMath.sampleCurve(pts, 256)
        val lut = AdjustMath.curveLut(pts)
        for (i in 0 until 256) assertEquals("at $i", lut[i], Math.round(s[i] * 255f))
        // Arbitrary resolutions hit the control points exactly (x = i / (n - 1)).
        val fine = AdjustMath.sampleCurve(pts, 11)
        assertEquals(11, fine.size)
        assertEquals(0.1f, fine[0], 1e-5f); assertEquals(0.6f, fine[3], 1e-5f)
        assertEquals(0.65f, fine[7], 1e-5f); assertEquals(0.95f, fine[10], 1e-5f)
        assertTrue(fine.all { it in 0f..1f })
        // Degenerate sizes don't throw.
        assertEquals(0, AdjustMath.sampleCurve(pts, 0).size)
        assertEquals(0, AdjustMath.sampleCurve(pts, -3).size)
        assertArrayEquals(floatArrayOf(0.1f), AdjustMath.sampleCurve(pts, 1), 1e-6f)
        // The filter maps a gray ramp through exactly that curve.
        val ramp = PixelBuffer(256, 1).also { for (x in 0 until 256) it[x, 0] = gray(x) }
        val out = adjust<ToneCurveFilter>().run(ramp, "curve" to pts).pixels
        for (x in 0 until 256) assertEquals(gray(lut[x]), out[x])
    }

    @Test
    fun toneCurveSingleChannel() {
        val src = row(rgb(100, 100, 100), rgb(10, 200, 50))
        val out = adjust<ToneCurveFilter>().run(src, "channel" to 1, "curve" to listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f))).pixels
        assertEquals(rgb(155, 100, 100), out[0])
        assertEquals(rgb(245, 200, 50), out[1])
    }

    @Test
    fun levelsMapBlackGammaWhiteAndOutput() {
        val f = adjust<LevelsFilter>()
        val src = row(gray(64), gray(128), gray(192), gray(0), gray(255))
        val stretched = f.run(src, "inBlack" to 64f, "inWhite" to 192f).pixels
        assertEquals(0, r(stretched[0])); assertEquals(128, r(stretched[1])); assertEquals(255, r(stretched[2]))
        val gamma = f.run(row(gray(128)), "gamma" to 2f).pixels[0]
        assertEquals(181, r(gamma))
        val out = f.run(src, "outBlack" to 50f, "outWhite" to 200f).pixels
        assertEquals(50, r(out[3])); assertEquals(200, r(out[4]))
        val inverted = f.run(row(gray(0)), "outBlack" to 255f, "outWhite" to 0f).pixels[0]
        assertEquals(255, r(inverted))
        // Only the green channel.
        val green = f.run(row(rgb(64, 64, 64)), "channel" to 2, "inBlack" to 64f).pixels[0]
        assertEquals(rgb(64, 0, 64), green)
        // Crossed input points degrade to a threshold instead of failing.
        val crossed = f.run(src, "inBlack" to 150f, "inWhite" to 100f).pixels
        assertEquals(0, r(crossed[1])); assertEquals(255, r(crossed[2]))
    }

    @Test
    fun autoLevelsStretchesEachChannel() {
        val w = 64
        val src = PixelBuffer(w, 1)
        for (x in 0 until w) src[x, 0] = rgb(64 + x * 2, 100 + x, 30)
        src[5, 0] = 0 // transparent pixels are ignored by the histogram
        val out = adjust<LevelsFilter>().run(src, "auto" to true).pixels
        assertEquals(0, r(out[0])); assertEquals(255, r(out[w - 1]))
        assertEquals(0, g(out[0])); assertEquals(255, g(out[w - 1]))
        assertEquals(30, b(out[10])) // a flat channel is left alone
        assertEquals(0, out[5])
    }

    @Test
    fun posterizeInvertGrayscale() {
        val two = adjust<PosterizeFilter>().run(row(gray(0), gray(127), gray(128), gray(255)), "levels" to 2f).pixels
        assertArrayEquals(intArrayOf(gray(0), gray(0), gray(255), gray(255)), two)
        val four = adjust<PosterizeFilter>().run(sampleImage(), "levels" to 4f).pixels
        assertTrue(four.all { a(it) == 0 || (r(it) in setOf(0, 85, 170, 255) && g(it) in setOf(0, 85, 170, 255)) })

        val inv = adjust<InvertFilter>().run(row(rgb(10, 200, 255, 77))).pixels[0]
        assertEquals(rgb(245, 55, 0, 77), inv)
        val half = adjust<InvertFilter>().run(row(rgb(0, 255, 30)), "amount" to 50f).pixels[0]
        assertTrue(listOf(r(half), g(half), b(half)).all { it in 127..128 })

        val gs = adjust<GrayscaleFilter>()
        assertEquals(gray(76), gs.run(row(rgb(255, 0, 0))).pixels[0])
        assertEquals(gray(85), gs.run(row(rgb(255, 0, 0)), "method" to 1).pixels[0])
        assertEquals(gray(128), gs.run(row(rgb(255, 0, 0)), "method" to 2).pixels[0])
        val partial = gs.run(row(rgb(255, 0, 0)), "amount" to 50f).pixels[0]
        assertTrue(r(partial) in 160..170 && g(partial) in 35..40 && g(partial) == b(partial))
    }

    @Test
    fun blackAndWhiteThresholds() {
        val f = adjust<BlackWhiteFilter>()
        val src = row(gray(40), gray(100), gray(200, 120), rgb(255, 255, 0))
        val out = f.run(src, "threshold" to 50f).pixels
        assertArrayEquals(intArrayOf(gray(0), gray(0), gray(255, 120), gray(255)), out)
        // Default threshold is 30 %: 76 is darker than 30 % of 255, 100 is not.
        val def = f.run(row(gray(70), gray(100))).pixels
        assertArrayEquals(intArrayOf(gray(0), gray(255)), def)
        assertTrue(f.run(src, "threshold" to 0f).pixels.all { r(it) == 255 })
        assertTrue(f.run(src, "threshold" to 100f, "antialias" to true).pixels.all { r(it) == 0 || r(it) == 255 })
    }

    @Test
    fun blackAndWhiteSmoothingAntialiasesOnlyNearEdges() {
        val w = 40; val h = 8
        val src = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) src[x, y] = if (x < 20) gray(0) else gray(255)
        // At 30 % the smoothed contour falls between pixel centers, so one column gets partial coverage.
        val out = adjust<BlackWhiteFilter>().run(src, "threshold" to 30f, "smoothing" to 3f, "antialias" to true)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = r(out[x, y])
                if (x < 16) assertEquals(0, v)
                if (x > 23) assertEquals(255, v)
            }
            assertTrue("soft edge expected", (17..22).any { r(out[it, y]) in 1..254 })
        }
        // Transparent (black-colored) neighbours must not darken opaque white pixels.
        val lone = PixelBuffer(9, 9)
        lone[4, 4] = gray(255)
        val o2 = adjust<BlackWhiteFilter>().run(lone, "threshold" to 50f, "smoothing" to 4f, "antialias" to true)
        assertEquals(gray(255), o2[4, 4])
    }

    @Test
    fun previewScaleShrinksSmoothing() {
        val src = sampleImage(48, 40)
        val full = adjust<BlackWhiteFilter>().run(src, "smoothing" to 1f, "antialias" to true)
        val preview = adjust<BlackWhiteFilter>().run(src, "smoothing" to 1f, "antialias" to true, ctx = FilterContext(scale = 0.25f))
        // At 1/4 scale a 1 px smoothing is below the blur floor, so the result equals no smoothing.
        val none = adjust<BlackWhiteFilter>().run(src, "smoothing" to 0f, "antialias" to true)
        assertArrayEquals(none.pixels, preview.pixels)
        assertTrue(!full.pixels.contentEquals(none.pixels))
    }
}
