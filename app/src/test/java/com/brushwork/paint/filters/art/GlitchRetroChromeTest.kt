package com.brushwork.paint.filters.art

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.art.ArtTestUtil.a
import com.brushwork.paint.filters.art.ArtTestUtil.b
import com.brushwork.paint.filters.art.ArtTestUtil.g
import com.brushwork.paint.filters.art.ArtTestUtil.r
import com.brushwork.paint.filters.art.ArtTestUtil.run
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GlitchRetroChromeTest {
    private val glitch = GlitchFilter()
    private val retro = RetroGameFilter()
    private val chrome = ChromeFilter()

    /** Every row and column is distinct. */
    private fun unique(w: Int, h: Int) = PixelBuffer(w, h).also { p ->
        for (y in 0 until h) for (x in 0 until w) p[x, y] = ColorUtils.argb(255, x * 5, y * 3, (x * 7 + y * 11) and 0xFF)
    }

    // ------------------------------------------------------------------ Glitch

    @Test
    fun glitchIdentityWithoutStrengthOrStatic() {
        val src = unique(40, 30)
        assertArrayEquals(src.pixels, run(glitch, src, "strength" to 0f, "noise" to 0f).pixels)
    }

    @Test
    fun glitchRowsAreWrappedShiftsOfSourceRows() {
        val w = 50; val h = 120
        val src = unique(w, h)
        val out = run(glitch, src, "strength" to 100f, "colorShift" to 0f, "noise" to 0f, "blocks" to 0f, "height" to 6f)
        fun rowOf(p: PixelBuffer, y: Int) = IntArray(w) { p[it, y] }
        val srcRows = (0 until h).map { rowOf(src, it) }
        var shifted = 0
        for (y in 0 until h) {
            val o = rowOf(out, y)
            val match = srcRows.any { s -> (0 until w).any { d -> (0 until w).all { x -> o[x] == s[Math.floorMod(x - d, w)] } } }
            assertTrue("row $y is not a wrapped shift of a source row", match)
            if (!o.contentEquals(srcRows[y])) shifted++
        }
        assertTrue("only $shifted rows glitched", shifted > h / 3)
    }

    @Test
    fun glitchSplitsChannelsAndIsSeeded() {
        val src = PixelBuffer.filled(60, 80, 0xFF000000.toInt())
        for (y in 0 until 80) src[30, y] = -1
        val out = run(glitch, src, "strength" to 100f, "colorShift" to 4f, "blocks" to 0f, "noise" to 0f)
        var split = 0
        for (y in 0 until 80) {
            val redX = (0 until 60).firstOrNull { r(out[it, y]) == 255 }
            val blueX = (0 until 60).firstOrNull { b(out[it, y]) == 255 }
            if (redX != null && blueX != null && redX != blueX) split++
        }
        assertTrue("split rows $split", split > 20)
        assertArrayEquals(out.pixels, run(glitch, src, "strength" to 100f, "colorShift" to 4f, "blocks" to 0f, "noise" to 0f).pixels)
        val other = run(glitch, src, "strength" to 100f, "colorShift" to 4f, "blocks" to 0f, "noise" to 0f, "seed" to 5)
        assertTrue(ArtTestUtil.countDifferent(out, other) > 50)
    }

    /** Distinct colors in every row and column (no channel saturates). */
    private fun distinct(w: Int, h: Int) = PixelBuffer(w, h).also { p ->
        for (y in 0 until h) for (x in 0 until w) p[x, y] = ColorUtils.argb(255, x * 3, y * 2, (x * 5 + y * 7) and 0xFF)
    }

    /** Offset d if [out] row y is [src] row y shifted right by d (wrapping), else null. */
    private fun shiftOf(out: PixelBuffer, src: PixelBuffer, y: Int): Int? {
        val w = src.width
        return (0 until w).firstOrNull { d -> (0 until w).all { x -> out[x, y] == src[Math.floorMod(x - d, w), y] } }
    }

    @Test
    fun glitchPreviewHasTheSameBandLayoutAsTheFinalResult() {
        val params = arrayOf<Pair<String, Any>>("strength" to 60f, "height" to 10f, "colorShift" to 0f, "blocks" to 0f, "noise" to 0f)
        val big = distinct(80, 120)
        val full = run(glitch, big, *params)
        val small = distinct(40, 60)
        val preview = run(glitch, small, *params, scale = 0.5f)
        var agree = 0; var glitched = 0
        for (y in 0 until 60) {
            val prevChanged = (0 until 40).any { preview[it, y] != small[it, y] }
            val fullChanged = listOf(2 * y, 2 * y + 1).map { fy -> (0 until 80).any { full[it, fy] != big[it, fy] } }
            if (prevChanged == fullChanged[0] || prevChanged == fullChanged[1]) agree++
            // Shifted rows move by half as many preview pixels.
            val d = shiftOf(preview, small, y) ?: continue
            if (d == 0) continue
            glitched++
            val matches = listOf(2 * y, 2 * y + 1).mapNotNull { shiftOf(full, big, it) }
                .any { dd -> Math.floorMod(dd - 2 * d + 1, 80) <= 2 }
            assertTrue("row $y shifts $d in the preview", matches)
        }
        assertTrue("agree $agree", agree >= 54)
        assertTrue("glitched $glitched", glitched > 10)
    }

    @Test
    fun glitchStaticKeepsTransparency() {
        val src = PixelBuffer(40, 200)
        for (y in 0 until 200) for (x in 10 until 20) src[x, y] = 0xFF3366CC.toInt()
        val out = run(glitch, src, "strength" to 0f, "noise" to 100f, "height" to 5f)
        for (i in src.pixels.indices) assertEquals(a(src.pixels[i]), a(out.pixels[i]))
        assertTrue(ArtTestUtil.countDifferent(src, out) > 20)
    }

    // ------------------------------------------------------------------ Retro Game

    @Test
    fun retroOutputUsesOnlyPaletteColors() {
        val src = ArtTestUtil.gradient(64, 48)
        for (y in 0 until 48) for (x in 0 until 5) src[x, y] = 0
        for ((i, palette) in RetroPalette.all.withIndex()) {
            val out = run(retro, src, "palette" to i, "dotSize" to 3f, "saturation" to 40f)
            val allowed = palette.colors.toSet() + 0
            for (c in out.pixels) assertTrue("${palette.title} produced ${Integer.toHexString(c)}", c in allowed)
            for (y in 0 until 48) assertEquals(0, out[0, y])
        }
    }

    @Test
    fun retroFillsWholeDots() {
        val out = run(retro, ArtTestUtil.gradient(40, 40), "dotSize" to 8f)
        for (by in 0 until 5) for (bx in 0 until 5) {
            val c = out[bx * 8, by * 8]
            for (y in 0 until 8) for (x in 0 until 8) assertEquals(c, out[bx * 8 + x, by * 8 + y])
        }
    }

    @Test
    fun retroEightColorsThresholdChannels() {
        fun one(c: Int, vararg extra: Pair<String, Any>) =
            run(retro, PixelBuffer.filled(8, 8, c), "dither" to 0f, "dotSize" to 1f, *extra)[3, 3]
        assertEquals(0xFFFF0000.toInt(), one(0xFFC82020.toInt()))
        assertEquals(0xFF00FFFF.toInt(), one(0xFF20C0D0.toInt()))
        assertEquals(0xFF000000.toInt(), one(0xFF646464.toInt()))
        assertEquals(-1, one(0xFFA0A0A0.toInt()))
        // Desaturated input can only give black or white.
        val gray = run(retro, ArtTestUtil.gradient(32, 32), "dither" to 0f, "saturation" to -100f)
        for (c in gray.pixels) assertTrue(c == -1 || c == 0xFF000000.toInt())
    }

    @Test
    fun retroDitherMixesColorsOnFlatGray() {
        val flat = PixelBuffer.filled(32, 32, 0xFF808080.toInt())
        val plain = run(retro, flat, "dither" to 0f, "dotSize" to 1f)
        assertEquals(1, plain.pixels.toSet().size)
        val dithered = run(retro, flat, "dither" to 100f, "dotSize" to 1f)
        val whites = dithered.pixels.count { it == -1 }
        val blacks = dithered.pixels.count { it == 0xFF000000.toInt() }
        assertTrue("whites $whites blacks $blacks", whites in 400..624 && blacks in 400..624)
    }

    @Test
    fun retroGameBoyMapsDarkToDarkest() {
        val gb = RetroPalette.all.indexOfFirst { it.title == "Game Boy" }
        val src = PixelBuffer.filled(8, 4, 0xFF000000.toInt())
        for (y in 0 until 4) for (x in 4 until 8) src[x, y] = -1
        val out = run(retro, src, "palette" to gb, "dither" to 0f, "dotSize" to 1f)
        assertEquals(RetroPalette.all[gb].colors.first(), out[0, 0])
        assertEquals(RetroPalette.all[gb].colors.last(), out[7, 0])
    }

    // ------------------------------------------------------------------ Chrome

    private fun dome(n: Int, alphaOutside: Boolean): PixelBuffer {
        val p = PixelBuffer(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val d = hypot(x - n / 2.0, y - n / 2.0) / (n / 2.0)
            val v = ((1.0 - d).coerceIn(0.0, 1.0) * 255).toInt()
            p[x, y] = if (alphaOutside && d > 0.9) 0 else ColorUtils.argb(255, v, v, v)
        }
        return p
    }

    @Test
    fun chromeIsGrayAndKeepsAlpha() {
        val src = dome(60, alphaOutside = true)
        val out = run(chrome, src)
        for (i in src.pixels.indices) {
            val c = out.pixels[i]
            assertEquals(a(src.pixels[i]), a(c))
            assertEquals(r(c), g(c)); assertEquals(g(c), b(c))
        }
        // The metal shows both dark reflections and bright highlights.
        val values = out.pixels.filter { a(it) > 0 }.map { r(it) }
        assertTrue(values.min() < 60 && values.max() > 200)
    }

    @Test
    fun chromeFlatFillIsUniformAndDetailAddsBands() {
        val flat = run(chrome, PixelBuffer.filled(20, 20, 0xFF808080.toInt()))
        assertEquals(1, flat.pixels.toSet().size)
        fun transitions(p: PixelBuffer): Int {
            var t = 0
            for (x in 1 until p.width) if ((r(p[x, 30]) > 128) != (r(p[x - 1, 30]) > 128)) t++
            return t
        }
        val low = run(chrome, dome(60, false), "detail" to 0f, "smoothness" to 1f)
        val high = run(chrome, dome(60, false), "detail" to 8f, "smoothness" to 1f)
        assertTrue("low ${transitions(low)} high ${transitions(high)}", transitions(high) > transitions(low))
    }

    @Test
    fun chromeTintColorsTheMetal() {
        val gold = 0xFFD4A017.toInt()
        val out = run(chrome, dome(50, true), "tint" to gold)
        val visible = out.pixels.filter { a(it) > 0 && r(it) < 250 }
        assertTrue(visible.isNotEmpty())
        for (c in visible) assertTrue(r(c) >= g(c) && g(c) >= b(c))
    }
}
