package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class StylizeFiltersTest {
    private val ctx = FilterContext()

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun chroma(c: Int) = max(r(c), max(g(c), b(c))) - min(r(c), min(g(c), b(c)))

    private fun noisy(w: Int, h: Int, base: Int, amp: Int): PixelBuffer {
        val out = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val n = ((Noise.hash(x, y, 3) and 0xFF) - 128) * amp / 128
            out[x, y] = ColorUtils.argb(255, r(base) + n, g(base) + n, b(base) + n)
        }
        return out
    }

    private fun variance(p: IntArray): Double {
        val l = p.map { ColorUtils.luminance(it).toDouble() }
        val m = l.average()
        return l.sumOf { (it - m) * (it - m) } / l.size
    }

    @Test
    fun labRoundTripIsLossless() {
        val lab = FloatArray(3)
        for (c in intArrayOf(0, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0x123456, 0x808080, 0xFEDCBA, 0x010203)) {
            Lab.fromRgb(r(c), g(c), b(c), lab)
            val back = Lab.toRgb(lab[0], lab[1], lab[2])
            assertTrue("%06X -> %06X".format(c, back), abs(r(back) - r(c)) <= 1 && abs(g(back) - g(c)) <= 1 && abs(b(back) - b(c)) <= 1)
        }
        Lab.fromRgb(255, 255, 255, lab)
        assertEquals(100f, lab[0], 0.1f)
        assertEquals(0f, lab[1], 0.1f)
    }

    @Test
    fun softQuantizerIsMonotoneAndBanded() {
        val q = SoftQuantizer(5, 2.5f)
        var prev = -1f
        for (i in 0..1000) {
            val v = q.apply(i / 10f)
            assertTrue(v >= prev - 1e-4f); prev = v
        }
        // Band centres are kept, values near them are pulled in.
        assertEquals(40f, q.apply(40f), 1e-3f)
        assertTrue(abs(q.apply(43f) - 40f) < 1.5f)
    }

    @Test
    fun animeKeepsFlatAreasFlatAndPreservesAlpha() {
        val f = AnimeBackgroundFilter()
        val src = PixelBuffer.filled(40, 30, 0x80668844.toInt())
        src[0, 0] = 0
        val out = f.apply(src, f.defaultValues(), ctx)
        val ref = out[20, 15]
        assertEquals(0x80, ref ushr 24)
        assertEquals(0, out[0, 0])
        for (i in 1 until out.size) {
            val c = out.pixels[i]
            assertEquals(0x80, c ushr 24)
            assertTrue(abs(r(c) - r(ref)) <= 2 && abs(g(c) - g(ref)) <= 2 && abs(b(c) - b(ref)) <= 2)
        }
    }

    @Test
    fun animeSaturationAndSmoothing() {
        val f = AnimeBackgroundFilter()
        val src = noisy(80, 60, 0xFF4A7AB0.toInt(), 40)
        val vivid = f.apply(src, f.defaultValues().set("saturation", 100f).set("sky", 0f), ctx)
        val gray = f.apply(src, f.defaultValues().set("saturation", -100f), ctx)
        assertTrue(vivid.pixels.map { chroma(it) }.average() > src.pixels.map { chroma(it) }.average() + 20)
        assertTrue(gray.pixels.all { chroma(it) <= 3 })
        // Smoothing sizes are relative to the image: use a photo-like size.
        val big = noisy(400, 300, 0xFF4A7AB0.toInt(), 40)
        val flat = f.apply(big, f.defaultValues().set("outline", 0f).set("levels", 16f).set("smoothing", 100f), ctx)
        assertTrue(variance(flat.pixels) < variance(big.pixels) * 0.5)
    }

    @Test
    fun animeColorSimplificationReducesThePalette() {
        val f = AnimeBackgroundFilter()
        val src = PixelBuffer(360, 90)
        for (y in 0 until 90) for (x in 0 until 360) src[x, y] = ColorUtils.hsvToColor(x.toFloat(), 0.7f, 0.85f)
        val base = f.defaultValues().set("smoothing", 0f).set("outline", 0f).set("sky", 0f)
            .set("saturation", 0f).set("contrast", 0f).set("brightness", 0f).set("levels", 16f)
        // Fraction of columns whose hue/chroma (Lab a, b) matches the column 4 px further on:
        // a smooth rainbow changes everywhere, a simplified one is made of flat color runs.
        fun flatRuns(img: PixelBuffer): Double {
            val lab = FloatArray(3)
            val ab = (0 until 360).map { val c = img[it, 45]; Lab.fromRgb(r(c), g(c), b(c), lab); lab[1] to lab[2] }
            return (0 until 356).count { val (a0, b0) = ab[it]; val (a1, b1) = ab[it + 4]; kotlin.math.hypot(a1 - a0, b1 - b0) < 1.5f } / 356.0
        }
        val smooth = flatRuns(f.apply(src, base.copy().set("colors", 0f), ctx))
        val simple = flatRuns(f.apply(src, base.copy().set("colors", 100f), ctx))
        assertTrue("smooth=$smooth simple=$simple", smooth < 0.2 && simple > 0.6)
        // Flat input stays flat at any simplification.
        val flat = f.apply(PixelBuffer.filled(30, 20, 0xFF668844.toInt()), base.copy().set("colors", 100f), ctx)
        assertTrue(flat.pixels.all { it == flat.pixels[0] })
    }

    @Test
    fun paletteCentersMatchBetweenPreviewAndFullSize() {
        // Patchwork of 12 colors with grain: the palette found on a 4x smaller working copy
        // (as in the live preview) is the one found at full size.
        val src = PixelBuffer(400, 300)
        val tiles = intArrayOf(
            0xFFB03030.toInt(), 0xFF30A040.toInt(), 0xFF3050C0.toInt(), 0xFFE0C040.toInt(),
            0xFF804020.toInt(), 0xFF20A0A0.toInt(), 0xFFA040A0.toInt(), 0xFFF0F0F0.toInt(),
            0xFF202020.toInt(), 0xFF808080.toInt(), 0xFFF08040.toInt(), 0xFF90C0F0.toInt(),
        )
        for (y in 0 until 300) for (x in 0 until 400) {
            val t = tiles[(y / 100) * 4 + x / 100]
            val n = ((Noise.hash(x, y, 5) and 0xFF) - 128) * 12 / 128
            src[x, y] = ColorUtils.argb(255, r(t) + n, g(t) + n, b(t) + n)
        }
        val full = Stylize.kMeans(Stylize.downsampleLab(src, 400, 300, ctx), 12, 1, ctx)
        val small = Stylize.kMeans(Stylize.downsampleLab(src, 100, 75, ctx), 12, 1, ctx)
        assertEquals(full.size, small.size)
        for (i in 0 until small.size / 3) {
            val d = (0 until full.size / 3).minOf { j ->
                val dl = small[i * 3] - full[j * 3]; val da = small[i * 3 + 1] - full[j * 3 + 1]; val db = small[i * 3 + 2] - full[j * 3 + 2]
                kotlin.math.sqrt(dl * dl + da * da + db * db)
            }
            assertTrue("center $i is $d from the full-size palette", d < 3f)
        }
    }

    @Test
    fun animeOutlinesDarkenStrongEdges() {
        val f = AnimeBackgroundFilter()
        val src = PixelBuffer.filled(400, 400, -1)
        for (y in 100 until 300) for (x in 100 until 300) src[x, y] = 0xFF9090A0.toInt()
        val v = f.defaultValues().set("sky", 0f).set("saturation", 0f).set("contrast", 0f).set("brightness", 0f)
        val plain = f.apply(src, v.copy().set("outline", 0f), ctx)
        val lined = f.apply(src, v.copy().set("outline", 100f), ctx)
        val darkestPlain = (0 until 400).minOf { ColorUtils.luminance(plain[it, 200]) }
        val darkestLined = (0 until 400).minOf { ColorUtils.luminance(lined[it, 200]) }
        assertTrue("plain=$darkestPlain lined=$darkestLined", darkestLined < darkestPlain - 25)
        // Far from edges nothing changes.
        assertEquals(plain[200, 200], lined[200, 200])
        assertEquals(plain[20, 20], lined[20, 20])
    }

    @Test
    fun layerTransparencyDoesNotCreateOutlines() {
        val f = AnimeBackgroundFilter()
        val src = PixelBuffer(400, 300)
        for (y in 0 until 300) for (x in 0 until 250) src[x, y] = 0xFF5588CC.toInt()
        val out = f.apply(src, f.defaultValues().set("outline", 100f), ctx)
        val inside = out[100, 150]
        // The last opaque columns next to the transparent area look like the rest of the area.
        for (x in 240 until 250) assertTrue(abs(ColorUtils.luminance(out[x, 150]) - ColorUtils.luminance(inside)) <= 3)
        assertEquals(0, out[300, 150])
    }

    @Test
    fun watercolorLiftsTowardsPaperAndKeepsAlpha() {
        val f = WatercolorFilter()
        val src = noisy(64, 48, 0xFF305080.toInt(), 20)
        src[3, 3] = 0
        src[4, 3] = 0x40305080
        val out = f.apply(src, f.defaultValues().set("lightness", 100f), ctx)
        assertEquals(0, out[3, 3])
        assertEquals(0x40, out[4, 3] ushr 24)
        val lin = src.pixels.filter { it ushr 24 == 255 }.map { ColorUtils.luminance(it) }.average()
        val lout = out.pixels.filter { it ushr 24 == 255 }.map { ColorUtils.luminance(it) }.average()
        assertTrue("in=$lin out=$lout", lout > lin + 30)
    }

    @Test
    fun watercolorPaperAndGrainAreTheOnlyTextureOnWhite() {
        val f = WatercolorFilter()
        val white = PixelBuffer.filled(60, 60, -1)
        val clean = f.defaultValues().set("paper", 0f).set("granulation", 0f).set("bleed", 0f).set("edges", 0f)
        assertTrue(f.apply(white, clean, ctx).pixels.all { it == -1 })
        val paper = f.apply(white, clean.copy().set("paper", 100f), ctx)
        assertTrue(paper.pixels.toSet().size > 5)
        assertTrue(paper.pixels.all { ColorUtils.luminance(it) > 225 })
        // Deterministic per seed.
        val a = f.apply(noisy(30, 30, 0xFF884422.toInt(), 30), f.defaultValues(), ctx)
        val b2 = f.apply(noisy(30, 30, 0xFF884422.toInt(), 30), f.defaultValues(), ctx)
        val c = f.apply(noisy(30, 30, 0xFF884422.toInt(), 30), f.defaultValues().set("seed", 5), ctx)
        assertTrue(a.pixels.contentEquals(b2.pixels))
        assertFalse(a.pixels.contentEquals(c.pixels))
    }

    @Test
    fun mangaIsBlackAndWhiteWithToneCoverageMatchingDensity() {
        val f = MangaBackgroundFilter()
        for (tone in 0..2) {
            val v = f.defaultValues().set("tone", tone)
            val white = f.apply(PixelBuffer.filled(50, 50, -1), v, ctx)
            assertTrue(white.pixels.all { it == -1 })
            val black = f.apply(PixelBuffer.filled(50, 50, 0xFF000000.toInt()), v, ctx)
            assertTrue(black.pixels.all { it == 0xFF000000.toInt() })
            // Mid gray: contrast keeps 0.5, density (1 - (0.5-0.22)/0.5) = 0.44 -> step 2 of 3 = 50%.
            val mid = f.apply(PixelBuffer.filled(120, 120, 0xFF777777.toInt()), v, ctx)
            assertTrue(mid.pixels.all { r(it) == g(it) && g(it) == b(it) })
            val ink = 1.0 - mid.pixels.map { r(it) / 255.0 }.average()
            assertEquals("tone $tone", 0.5, ink, 0.08)
        }
    }

    @Test
    fun mangaTracesEdgesAndHonoursLineDarkness() {
        val f = MangaBackgroundFilter()
        val src = PixelBuffer.filled(400, 400, -1)
        for (y in 0 until 400) for (x in 200 until 400) src[x, y] = 0xFFD8D8D8.toInt() // both sides white after levels
        val lined = f.apply(src, f.defaultValues().set("edges", 100f).set("detail", 100f), ctx)
        val none = f.apply(src, f.defaultValues().set("edges", 0f), ctx)
        assertTrue(none.pixels.all { it == -1 })
        val column = (190 until 210).minOf { r(lined[it, 200]) }
        assertTrue("darkest=$column", column < 128)
        // Lines only appear near the edge.
        assertTrue((0 until 150).all { lined[it, 200] == -1 })
    }

    @Test
    fun mangaTonePitchScalesWithPreview() {
        val f = MangaBackgroundFilter()
        val v = f.defaultValues().set("tone_size", 16f)
        val gray = 0xFF777777.toInt()
        val full = f.apply(PixelBuffer.filled(160, 160, gray), v, ctx)
        val small = f.apply(PixelBuffer.filled(40, 40, gray), v, FilterContext(scale = 0.25f))
        val inkFull = 1.0 - full.pixels.map { r(it) / 255.0 }.average()
        val inkSmall = 1.0 - small.pixels.map { r(it) / 255.0 }.average()
        assertEquals(inkFull, inkSmall, 0.1)
    }

    @Test
    fun stylizersKeepSmallAndHugeRatiosWorking() {
        // Very elongated images and downscaled working copies still map pixels consistently.
        for (f in listOf(WatercolorFilter(), AnimeBackgroundFilter(), MangaBackgroundFilter())) {
            val tall = f.apply(PixelBuffer.filled(2, 3000, 0xFF336699.toInt()), f.defaultValues(), ctx)
            assertEquals(2, tall.width)
            assertTrue(tall.pixels.all { it ushr 24 == 255 })
        }
    }
}
