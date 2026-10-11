package com.brushwork.paint.filters.art

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.art.ArtTestUtil.a
import com.brushwork.paint.filters.art.ArtTestUtil.b
import com.brushwork.paint.filters.art.ArtTestUtil.g
import com.brushwork.paint.filters.art.ArtTestUtil.r
import com.brushwork.paint.filters.art.ArtTestUtil.run
import com.brushwork.paint.filters.art.FringeFixtures.B
import com.brushwork.paint.filters.art.FringeFixtures.G
import com.brushwork.paint.filters.art.FringeFixtures.NONE
import com.brushwork.paint.filters.art.FringeFixtures.R
import com.brushwork.paint.filters.art.FringeFixtures.contexts
import com.brushwork.paint.filters.art.FringeFixtures.gradient
import com.brushwork.paint.filters.art.FringeFixtures.random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * v1.8 (item 5, design §3.6 (c)): the general path of the fringe colors. Channel c is
 * `Σ_i F_i,c · P_i,c / Σ_i F_i,c` (the normalization: the weights of a channel sum to 1); a swatch
 * with no color takes no part; a channel no swatch covers comes from the middle (unshifted)
 * sample; the alpha is the largest among the colored samples. It generalizes v1.7: on one-hot
 * colors the general path gives the fast path's bytes. JVM.
 */
class FringeGeneralTest {
    private val moving = ChromaticAberrationMovingFilter()
    private val zooming = ChromaticAberrationZoomingFilter()
    private val orange = 0xFFFF8000.toInt()
    private val teal = 0xFF008080.toInt()
    private val cyan = FringeColors.CYAN

    /** Opaque black with a white vertical line at x = 10. */
    private fun whiteLine(): PixelBuffer {
        val p = PixelBuffer.filled(21, 5, 0xFF000000.toInt())
        for (y in 0 until 5) p[10, y] = -1
        return p
    }

    private fun rgbOf(c: Int) = listOf(r(c), g(c), b(c))

    @Test
    fun theWeightsOfEachChannelSumToOne() {
        val rnd = Random(4)
        val sets = listOf(
            intArrayOf(R, G, B), intArrayOf(orange, NONE, teal), intArrayOf(R, 0xFFFF00FF.toInt(), NONE),
            intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt(), 0xFF708090.toInt()),
        ) + List(20) { IntArray(3) { if (rnd.nextInt(4) == 0) NONE else rnd.nextInt() } }
        for (set in sets) {
            val mix = FringeColors.Mix(set)
            for (c in 0 until 3) {
                val sum = (0 until 3).sumOf { i -> mix.w[3 * i + c].toDouble() }
                assertEquals("${set.map { Integer.toHexString(it) }} channel $c", 1.0, sum, 1e-6)
                for (i in 0 until 3) if (set[i] == NONE && i != 1) assertEquals(0f, mix.w[3 * i + c])
            }
        }
        // Red and magenta share red half and half; blue is magenta's alone; green is uncovered: the middle sample's.
        val m = FringeColors.Mix(intArrayOf(R, 0xFFFF00FF.toInt(), NONE))
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0f, 0.5f, 1f, 1f, 0f, 0f, 0f), m.w, 0f)
        assertArrayEquals(booleanArrayOf(true, true, false), m.colored)
        assertArrayEquals(booleanArrayOf(true, true, false), m.needed)
        // A weight is the swatch's share of the channel: orange's green 0x80 against teal's 0x80.
        val ot = FringeColors.Mix(intArrayOf(orange, NONE, teal))
        assertEquals(0.5f, ot.w[1], 1e-6f); assertEquals(0.5f, ot.w[7], 1e-6f)
        assertEquals(1f, ot.w[0]); assertEquals(1f, ot.w[8])
        assertArrayEquals(booleanArrayOf(true, false, true), ot.needed)
    }

    @Test
    fun onOneHotColorsTheGeneralPathIsTheFastPathByteForByte() {
        val sets = (0..5).map { FringeColors.legacyOrder(it)!! } + FringeColors.PRESETS.map { it.colors }
        for (src in listOf(random(37, 29, 8), gradient(40, 33))) for (ctx in contexts) for (set in sets) {
            val k = FringeColors.oneHotFactors(set) ?: error("one-hot: ${set.toList()}")
            val mix = FringeColors.Mix(set)
            val vx = ArtMath.unitX(33f) * ctx.px(9f); val vy = ArtMath.unitY(33f) * ctx.px(9f)
            assertArrayEquals(moving.fast(src, ctx, k, vx, vy).pixels, moving.general(src, ctx, mix, vx, vy).pixels)
            val cx = 0.3f * src.width; val cy = 0.6f * src.height; val s = 0.2f
            assertArrayEquals(zooming.fast(src, ctx, k, cx, cy, s).pixels, zooming.general(src, ctx, mix, cx, cy, s).pixels)
        }
    }

    @Test
    fun theFastPathIsOnlyForOneHotColors() {
        assertNotNull(FringeColors.oneHotFactors(intArrayOf(R, G, B)))
        assertArrayEquals(intArrayOf(1, -1, -1), FringeColors.oneHotFactors(intArrayOf(R, NONE, cyan)))
        // Swatch alpha is ignored: a translucent red is still red.
        assertNotNull(FringeColors.oneHotFactors(intArrayOf(0x40FF0000, G, B)))
        assertNull(FringeColors.oneHotFactors(intArrayOf(orange, NONE, teal)))
        assertNull(FringeColors.oneHotFactors(intArrayOf(R, R, B))) // green uncovered, red twice
        assertNull(FringeColors.oneHotFactors(intArrayOf(R, NONE, B))) // green uncovered
        assertNull(FringeColors.oneHotFactors(intArrayOf(R, 0xFF000000.toInt(), cyan))) // black counts for alpha only
        assertNull(FringeColors.oneHotFactors(intArrayOf(NONE, NONE, NONE)))
    }

    @Test
    fun twoColorsSplitALineAndNoColorTakesNoPart() {
        // Red/Cyan, distance 3 along +x: red forward, cyan back, and the middle (no color) gone.
        val out = run(moving, whiteLine(), "distance" to 3f, "angle" to 0f, FringeColors.KEY to intArrayOf(R, NONE, cyan))
        assertEquals(listOf(255, 0, 0), rgbOf(out[13, 2]))
        assertEquals(listOf(0, 255, 255), rgbOf(out[7, 2]))
        assertEquals(listOf(0, 0, 0), rgbOf(out[10, 2]))
        for (x in 0 until 21) assertEquals(255, a(out[x, 2]))
    }

    @Test
    fun aChannelNoSwatchCoversComesFromTheMiddleSample() {
        // Red forward and back: red is their mean; green and blue are the unshifted picture's.
        val out = run(moving, whiteLine(), "distance" to 3f, "angle" to 0f, FringeColors.KEY to intArrayOf(R, NONE, R))
        assertEquals(listOf(0, 255, 255), rgbOf(out[10, 2]))
        assertEquals(listOf(128, 0, 0), rgbOf(out[13, 2]))
        assertEquals(listOf(128, 0, 0), rgbOf(out[7, 2]))
        assertEquals(listOf(0, 0, 0), rgbOf(out[3, 2]))
    }

    @Test
    fun theAlphaIsTheColoredSamplesOnly() {
        // A white dot on a transparent layer: with no middle color, the dot's own place empties.
        val src = PixelBuffer(15, 3)
        src[7, 1] = -1
        val rc = run(moving, src, "distance" to 2f, "angle" to 0f, FringeColors.KEY to intArrayOf(R, NONE, cyan))
        assertEquals(0xFFFF0000.toInt(), rc[9, 1])
        assertEquals(0xFF00FFFF.toInt(), rc[5, 1])
        assertEquals(0, rc[7, 1])
        // A black middle swatch counts for the alpha (the dot stays, black).
        val rk = run(moving, src, "distance" to 2f, "angle" to 0f, FringeColors.KEY to intArrayOf(R, 0xFF000000.toInt(), cyan))
        assertEquals(0xFF000000.toInt(), rk[7, 1])
        assertEquals(0xFFFF0000.toInt(), rk[9, 1])
    }

    @Test
    fun anyColorsKeepAUniformPictureAndNoColorAtAllIsTheSource() {
        val flat = PixelBuffer.filled(30, 20, 0xFF5A7896.toInt())
        val rnd = Random(11)
        repeat(12) {
            val set = IntArray(3) { if (rnd.nextInt(3) == 0) NONE else rnd.nextInt() or 0xFF000000.toInt() }
            if (FringeColors.allNone(set)) return@repeat
            for (f in listOf(moving, zooming)) {
                val out = run(f, flat, "distance" to 6f, FringeColors.KEY to set)
                for (c in out.pixels) {
                    assertEquals(255, a(c))
                    assertTrue(Integer.toHexString(c), abs(r(c) - 0x5A) <= 1 && abs(g(c) - 0x78) <= 1 && abs(b(c) - 0x96) <= 1)
                }
            }
        }
        val src = random(25, 19, 2)
        for (f in listOf(moving, zooming)) {
            assertArrayEquals(src.pixels, run(f, src, "distance" to 6f, FringeColors.KEY to intArrayOf(NONE, NONE, NONE)).pixels)
        }
    }

    @Test
    fun customColorsTintTheFringes() {
        // Orange forward and teal back on the white line: the forward fringe is orange's channels,
        // the back fringe teal's, each at the share its swatch has of the channel.
        val out = run(moving, whiteLine(), "distance" to 3f, "angle" to 0f, FringeColors.KEY to intArrayOf(orange, NONE, teal))
        // Forward (x 13): the first sample is white. Red is orange's alone (255); green half
        // orange's; blue is teal's alone (black there).
        assertEquals(listOf(255, 128, 0), rgbOf(out[13, 2]))
        assertEquals(listOf(0, 128, 255), rgbOf(out[7, 2]))
        // Zooming about the left edge: orange outward (right of the line, no blue), teal inward (left, no red).
        val z = run(zooming, whiteLine(), "distance" to 4f, "center" to floatArrayOf(0f, 0.5f), FringeColors.KEY to intArrayOf(orange, NONE, teal))
        assertTrue((11 until 21).any { r(z[it, 2]) > 0 && b(z[it, 2]) == 0 })
        assertTrue((0 until 10).any { b(z[it, 2]) > 0 && r(z[it, 2]) == 0 })
    }
}
