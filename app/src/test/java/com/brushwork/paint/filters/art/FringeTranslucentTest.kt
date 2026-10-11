package com.brushwork.paint.filters.art

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.ArtTestUtil.a
import com.brushwork.paint.filters.art.ArtTestUtil.b
import com.brushwork.paint.filters.art.ArtTestUtil.g
import com.brushwork.paint.filters.art.ArtTestUtil.r
import com.brushwork.paint.filters.art.ArtTestUtil.run
import com.brushwork.paint.filters.art.FringeFixtures.G
import com.brushwork.paint.filters.art.FringeFixtures.NONE
import com.brushwork.paint.filters.art.FringeFixtures.R
import com.brushwork.paint.filters.art.FringeFixtures.contexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * v1.8 (item 5, design §3.6 (c)): the general path of the fringe colors on translucent pictures,
 * against the formula written out here from the design (not from [FringeColors.Mix]): channel c
 * is `Σ_i F_i,c · P_i,c / Σ_i F_i,c` with P_i,c sample i's alpha-weighted channel; a channel no
 * swatch covers is the middle sample's; the alpha A is the largest among the colored samples; each
 * channel is divided by A (its own coverage, v1.7's rule), and an uncovered channel by the larger
 * of A and the middle sample's alpha, so it never comes out brighter than the middle sample.
 *
 * Chromatic aberration's samples come from v1.7's own sampler ([ChannelShift.sample]). Glitch's
 * three sample positions per pixel are read off two index pictures (every channel = x, then = y)
 * run through the fast RGB split with the same seed and size: the band layout depends on nothing
 * else, and an opaque split copies each sample's channel exactly. JVM.
 */
class FringeTranslucentTest {
    private val moving = ChromaticAberrationMovingFilter()
    private val zooming = ChromaticAberrationZoomingFilter()
    private val glitch = GlitchFilter()

    /** General-path colors: custom ones, no middle color, uncovered channels, a black middle. */
    private val sets: List<IntArray> = listOf(
        intArrayOf(0xFFFF8000.toInt(), 0xFF8040C0.toInt(), 0xFF00A0A0.toInt()),
        intArrayOf(0xFFFF8000.toInt(), NONE, 0xFF008080.toInt()),
        intArrayOf(R, NONE, R),
        intArrayOf(NONE, NONE, 0xFF0000C0.toInt()),
        intArrayOf(R, 0xFF000000.toInt(), FringeColors.CYAN),
        intArrayOf(R, G, 0xFF00FF80.toInt()),
    )

    /** Random colors; a third of the pixels transparent or translucent, in patches and alone. */
    private fun translucent(w: Int, h: Int, seed: Int): PixelBuffer {
        val rnd = Random(seed)
        val p = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = when {
                (x / 5 + y / 4) % 4 == 0 -> 0
                (x / 3 + 2 * y) % 5 == 0 -> 1 + rnd.nextInt(254)
                rnd.nextInt(6) == 0 -> 1 + rnd.nextInt(254)
                else -> 255
            }
            p[x, y] = if (a == 0) 0 else (a shl 24) or rnd.nextInt(0x1000000)
        }
        return p
    }

    private fun channel(c: Int, k: Int): Int = (c shr (16 - 8 * k)) and 0xFF

    /**
     * The design's pixel: [pre] (i, c at 3i + c) the samples' alpha-weighted channels, [alpha] their
     * alphas. Null when no colored sample has any alpha (a transparent pixel); else A, R, G, B.
     */
    private fun expected(colors: IntArray, pre: DoubleArray, alpha: DoubleArray, transparentBelow: Double): DoubleArray? {
        var big = 0.0
        for (i in 0 until 3) if (colors[i] != NONE) big = max(big, alpha[i])
        if (big < transparentBelow) return null
        val out = DoubleArray(4)
        out[0] = big
        for (c in 0 until 3) {
            val sum = (0 until 3).sumOf { i -> if (colors[i] == NONE) 0 else channel(colors[i], c) }
            val v = if (sum == 0) {
                pre[3 + c] / max(big, alpha[1])
            } else {
                (0 until 3).sumOf { i -> if (colors[i] == NONE) 0.0 else channel(colors[i], c).toDouble() / sum * pre[3 * i + c] } / big
            }
            out[1 + c] = v.coerceIn(0.0, 255.0)
        }
        return out
    }

    private fun assertPixel(what: String, e: DoubleArray?, got: Int, alphaTolerance: Double) {
        if (e == null) {
            assertEquals("$what: transparent", 0, got)
            return
        }
        assertTrue("$what: alpha ${a(got)} vs ${e[0]}", abs(a(got) - e[0]) <= alphaTolerance)
        val rgb = intArrayOf(r(got), g(got), b(got))
        for (c in 0 until 3) assertTrue("$what: channel $c ${rgb[c]} vs ${e[1 + c]}", abs(rgb[c] - e[1 + c]) <= 1.0)
    }

    /** Chromatic aberration's general path at every pixel, from v1.7's sampler at the three positions. */
    private fun checkChromatic(what: String, src: PixelBuffer, out: PixelBuffer, colors: IntArray, positions: (x: Int, y: Int, i: Int) -> Pair<Float, Float>) {
        val w = src.width; val h = src.height
        val acc = FloatArray(2)
        val pre = DoubleArray(9); val alpha = DoubleArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            for (i in 0 until 3) {
                val (fx, fy) = positions(x, y, i)
                for (c in 0 until 3) {
                    ChannelShift.sample(src.pixels, w, h, fx, fy, 16 - 8 * c, acc)
                    pre[3 * i + c] = acc[0].toDouble()
                }
                alpha[i] = acc[1].toDouble()
            }
            // The filter rounds its float alpha; a pixel under half an alpha level is transparent.
            assertPixel("$what at $x,$y", expected(colors, pre, alpha, 0.5), out[x, y], alphaTolerance = 0.5)
        }
    }

    @Test
    fun chromaticAberrationsGeneralPathOnATranslucentPicture() {
        val src = translucent(47, 39, 3)
        for (ctx in contexts) for (colors in sets) {
            assertNull(FringeColors.oneHotFactors(colors))
            val name = "${colors.map { Integer.toHexString(it) }} at scale ${ctx.scale}"
            val dist = ctx.px(6f)
            val vx = ArtMath.unitX(25f) * dist; val vy = ArtMath.unitY(25f) * dist
            val m = moving.apply(src, moving.defaultValues().set("distance", 6f).set("angle", 25f).set(FringeColors.KEY, colors), ctx)
            checkChromatic("Moving $name", src, m, colors) { x, y, i ->
                val cx = x + 0.5f; val cy = y + 0.5f
                when (i) { 0 -> (cx - vx) to (cy - vy); 1 -> cx to cy; else -> (cx + vx) to (cy + vy) }
            }
            val w = src.width; val h = src.height
            val zx = 0.3f * w; val zy = 0.7f * h
            val rRef = max(max(hypot(zx, zy), hypot(w - zx, zy)), max(hypot(zx, h - zy), hypot(w - zx, h - zy)))
            val s = (ctx.px(8f) / rRef).coerceAtMost(0.9f)
            val z = zooming.apply(src, zooming.defaultValues().set("distance", 8f).set("center", floatArrayOf(0.3f, 0.7f)).set(FringeColors.KEY, colors), ctx)
            checkChromatic("Zooming $name", src, z, colors) { x, y, i ->
                val dx = x + 0.5f - zx; val dy = y + 0.5f - zy
                when (i) { 0 -> (zx + dx * (1f - s)) to (zy + dy * (1f - s)); 1 -> (zx + dx) to (zy + dy); else -> (zx + dx * (1f + s)) to (zy + dy * (1f + s)) }
            }
        }
    }

    @Test
    fun anUncoveredChannelNeverComesOutBrighterThanTheMiddleSample() {
        // Red forward and back, no middle color: green and blue are the middle sample's. At x = 4
        // the middle is opaque gray 64 and the first and last samples a faint white (alpha 40): the
        // alpha is 40, red the white's, and green and blue stay the gray's 64 (not 64 · 255 / 40).
        val src = PixelBuffer(9, 3)
        for (y in 0 until 3) {
            src[4, y] = 0xFF404040.toInt()
            src[2, y] = 0x28FFFFFF
            src[6, y] = 0x28FFFFFF
        }
        val out = run(moving, src, "distance" to 2f, "angle" to 0f, FringeColors.KEY to intArrayOf(R, NONE, R))
        assertEquals(0x28FF4040, out[4, 1])
        // Where a colored sample is more opaque than the middle (x = 2: the last sample is the
        // gray), the middle's channels keep their coverage, v1.7's rule: green and blue are the
        // faint white's 255 at 40 of 255 (40), red half the gray's 64 (the first sample is empty).
        assertEquals(0xFF202828.toInt(), out[2, 1])
    }

    private fun glitchValues(colors: IntArray): FilterValues = glitch.defaultValues().apply {
        set("strength", 100f); set("noise", 0f); set("blocks", 40f); set("colorShift", 15f); set("height", 8f); set("seed", 77)
        set(FringeColors.KEY, colors)
    }

    /** Every channel of each pixel is its [x] (or its y): opaque, so the RGB split copies them. */
    private fun probe(w: Int, h: Int, byX: Boolean): PixelBuffer {
        val p = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (byX) x else y
            p[x, y] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return p
    }

    @Test
    fun glitchsGeneralPathOnATranslucentPicture() {
        val w = 120; val h = 90
        val src = translucent(w, h, 8)
        var unequal = 0
        var capped = 0
        for (ctx in contexts) {
            val sw = src.width; val sh = src.height
            val xs = glitch.apply(probe(sw, sh, true), glitchValues(FringeColors.rgb), ctx)
            val ys = glitch.apply(probe(sw, sh, false), glitchValues(FringeColors.rgb), ctx)
            for (colors in sets) {
                assertNull(FringeColors.oneHotFactors(colors))
                val anyUncovered = (0 until 3).any { k -> (0 until 3).all { i -> colors[i] == NONE || channel(colors[i], k) == 0 } }
                val out = glitch.apply(src, glitchValues(colors), ctx)
                val pre = DoubleArray(9); val alpha = DoubleArray(3)
                for (y in 0 until sh) for (x in 0 until sw) {
                    val px = xs[x, y]; val py = ys[x, y]
                    for (i in 0 until 3) {
                        val c = src[channel(px, i), channel(py, i)]
                        alpha[i] = (c ushr 24).toDouble()
                        for (k in 0 until 3) pre[3 * i + k] = alpha[i] * channel(c, k)
                    }
                    if (alpha[0] != alpha[1] || alpha[1] != alpha[2]) unequal++
                    val e = expected(colors, pre, alpha, 1.0)
                    if (e != null && anyUncovered && alpha[1] > e[0]) capped++
                    assertPixel("Glitch ${colors.map { Integer.toHexString(it) }} at $x,$y, scale ${ctx.scale}", e, out[x, y], alphaTolerance = 0.0)
                }
            }
        }
        // The pixels whose samples differ in alpha (the float arithmetic) and the uncovered
        // channels' cap were both reached.
        assertTrue("translucent samples: $unequal", unequal > 1000)
        assertTrue("middle alpha above the colored ones: $capped", capped > 100)
    }
}
