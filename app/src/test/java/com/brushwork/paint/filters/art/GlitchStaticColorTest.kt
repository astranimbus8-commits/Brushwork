package com.brushwork.paint.filters.art

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.ArtTestUtil.a
import com.brushwork.paint.filters.art.ArtTestUtil.b
import com.brushwork.paint.filters.art.ArtTestUtil.g
import com.brushwork.paint.filters.art.ArtTestUtil.r
import com.brushwork.paint.filters.art.FringeFixtures.NONE
import com.brushwork.paint.filters.art.FringeFixtures.contexts
import com.brushwork.paint.filters.art.FringeFixtures.random
import com.brushwork.paint.filters.art.v17.GlitchV17
import com.brushwork.paint.ui.common.FringeLabels18
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 (item 5, design §3.6): Glitch's static color. The static of a band mixes toward `n · S`
 * (v1.7: `n · 255`), so white gives v1.7's bytes, also when the values do not hold the color; the
 * color's alpha scales the mix; and neither the static color nor the fringe colors move the
 * bands: a seed keeps its layout (no random draw is added). JVM.
 */
class GlitchStaticColorTest {
    private val glitch = GlitchFilter()
    private val red = 0xFFFF0000.toInt()

    /** Only static: no band slides or splits. */
    private fun staticOnly(seed: Int) = glitch.defaultValues().apply { set("strength", 0f); set("noise", 100f); set("seed", seed) }

    private fun rowsChanged(out: PixelBuffer, src: PixelBuffer): Set<Int> =
        (0 until src.height).filter { y -> (0 until src.width).any { x -> out[x, y] != src[x, y] } }.toSet()

    @Test
    fun theParamIsAWhiteColorAfterTheStaticSlider() {
        val p = glitch.params.single { it.key == GlitchFilter.STATIC_COLOR_KEY } as FilterParam.Color
        assertEquals(FringeLabels18.STATIC_COLOR, p.label)
        assertEquals(FringeColors.WHITE, p.default)
        val keys = glitch.params.map { it.key }
        assertEquals(keys.indexOf("noise") + 1, keys.indexOf(GlitchFilter.STATIC_COLOR_KEY))
        assertEquals(keys.indexOf("colorShift") + 1, keys.indexOf(FringeColors.KEY))
    }

    @Test
    fun whiteIsV17ByteForByteAndAbsentIsWhite() {
        val src = random(57, 83, 21)
        for (ctx in contexts) for (seed in listOf(3, 99)) for (noise in listOf(20f, 100f)) {
            val v = glitch.defaultValues().apply { set("noise", noise); set("seed", seed) }
            val want = GlitchV17.apply(src, v.copy(), ctx).pixels
            assertArrayEquals("seed $seed, noise $noise, scale ${ctx.scale}", want, glitch.apply(src, v.copy(), ctx).pixels)
            // The v1.7 keys alone (no fringes, no static color): the defaults' bytes.
            val bare = FilterValues(mapOf("height" to 20f, "strength" to 50f, "colorShift" to 10f, "blocks" to 30f, "noise" to noise, "seed" to seed))
            assertArrayEquals("v1.7 keys only, seed $seed", want, glitch.apply(src, bare, ctx).pixels)
        }
    }

    @Test
    fun theStaticTakesItsColor() {
        // A gray picture with red static: red moves toward n·255, green and blue only toward 0.
        val src = PixelBuffer.filled(64, 120, 0xFF808080.toInt())
        val out = glitch.apply(src, staticOnly(5).set(GlitchFilter.STATIC_COLOR_KEY, red), FilterContext())
        var darker = 0
        for (c in out.pixels) {
            assertEquals(255, a(c))
            assertTrue(Integer.toHexString(c), g(c) <= 0x80 && b(c) <= 0x80 && g(c) == b(c))
            if (g(c) < 0x80) darker++
        }
        assertTrue("static bands ($darker)", darker > 100)
        assertTrue("red rises somewhere", out.pixels.any { r(it) > 0x80 })
    }

    @Test
    fun theColorsAlphaScalesTheStatic() {
        val src = random(40, 90, 4)
        val ctx = FilterContext()
        assertArrayEquals(src.pixels, glitch.apply(src, staticOnly(8).set(GlitchFilter.STATIC_COLOR_KEY, 0x00FFFFFF), ctx).pixels)
        val half = glitch.apply(src, staticOnly(8).set(GlitchFilter.STATIC_COLOR_KEY, 0x80FFFFFF.toInt()), ctx)
        val full = glitch.apply(src, staticOnly(8), ctx)
        var less = 0
        for (i in src.pixels.indices) {
            val s = src.pixels[i]; val h = half.pixels[i]; val f = full.pixels[i]
            if (a(s) == 0) continue
            for (ch in listOf(16, 8, 0)) {
                val d = { c: Int -> kotlin.math.abs(((c shr ch) and 0xFF) - ((s shr ch) and 0xFF)) }
                assertTrue(d(h) <= d(f) + 1)
                if (d(h) + 1 < d(f)) less++
            }
        }
        assertTrue("half alpha mixes less ($less)", less > 50)
    }

    @Test
    fun seedsKeepTheirBandsWhateverTheColors() {
        val src = random(48, 140, 17)
        val ctx = FilterContext()
        for (seed in listOf(2, 77, 4242)) {
            // The static bands: the same rows as v1.7's with any static color.
            val v17 = rowsChanged(GlitchV17.apply(src, staticOnly(seed), ctx), src)
            assertTrue(v17.isNotEmpty())
            assertEquals("seed $seed", v17, rowsChanged(glitch.apply(src, staticOnly(seed).set(GlitchFilter.STATIC_COLOR_KEY, red), ctx), src))
            // The glitched bands: the same rows with other fringe colors.
            val split = glitch.defaultValues().apply { set("strength", 100f); set("noise", 0f); set("colorShift", 30f); set("seed", seed) }
            val rows = rowsChanged(GlitchV17.apply(src, split.copy(), ctx), src)
            for (colors in listOf(intArrayOf(red, NONE, FringeColors.CYAN), intArrayOf(0xFFFF8000.toInt(), NONE, 0xFF008080.toInt()))) {
                assertEquals("seed $seed, ${colors.toList()}", rows, rowsChanged(glitch.apply(src, split.copy().set(FringeColors.KEY, colors), ctx), src))
            }
        }
    }
}
