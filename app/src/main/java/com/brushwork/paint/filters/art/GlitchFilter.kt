package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.ui.common.FringeLabels18
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Glitch: the picture looks as if shown on a broken monitor. Rows are split into random bands;
 * some bands slide sideways (wrapping around), some are cut into blocks that slide independently,
 * RGB channels split inside glitched bands, a few bands are replaced by a copy of another part of
 * the image ("torn frame") and some bands get digital static.
 *
 * The band layout is generated in full-resolution coordinates from the seed, so a downscaled
 * preview matches the final result.
 *
 * v1.8 (item 5): the channel split takes its colors from the fringe colors ([FringeColors]: the
 * first sampled at `sx - cs`, the middle at `sx`, the last at `sx + cs`), and the static from
 * [STATIC_COLOR_KEY] (the static of a band mixes toward `n · S` instead of `n · 255`; the swatch's
 * alpha scales the mix). The defaults (RGB, white) give v1.7's bytes; no random draw is added,
 * so a seed keeps its bands and blocks.
 */
class GlitchFilter : Filter("art.glitch", "Glitch", FilterCategory.ART) {
    private val fringes = FringeColors.param(null)

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("height", "Height", 1f, 200f, 20f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("strength", "Strength", 0f, 100f, 50f, step = 1f, suffix = "%"),
        FilterParam.Slider("colorShift", "Color shift", 0f, 100f, 10f, step = 1f, suffix = "px", pixels = true),
        fringes,
        FilterParam.Slider("blocks", "Block noise", 0f, 100f, 30f, step = 1f, suffix = "%"),
        FilterParam.Slider("noise", "Static", 0f, 100f, 20f, step = 1f, suffix = "%"),
        FilterParam.Color(STATIC_COLOR_KEY, FringeLabels18.STATIC_COLOR, FringeColors.WHITE),
        FilterParam.Seed(),
    )

    /** One horizontal band in buffer rows [y0, y1). Offsets are fractions of the image width. */
    private class Band(val y0: Int, val y1: Int) {
        var glitched = false
        var dx = 0f
        var colorShift = 0f
        /** Block starts (fractions of width, ascending, first = 0) and their offsets, or null. */
        var blockStarts: FloatArray? = null
        var blockDx: FloatArray? = null
        /** Source row (fraction of height) of a torn band, or -1. */
        var tornFrom = -1f
        var noise = 0f
    }

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val strength = (values.float("strength") / 100f).coerceIn(0f, 1f)
        val noiseLevel = (values.float("noise") / 100f).coerceIn(0f, 1f)
        if (strength <= 0f && noiseLevel <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val scale = max(ctx.scale, 1e-4f)
        val bandHeight = values.float("height").coerceIn(1f, 200f)
        val shiftFull = values.float("colorShift").coerceIn(0f, 100f)
        val blockChance = (values.float("blocks") / 100f).coerceIn(0f, 1f)
        val seed = values.seed()
        val rng = Random(seed)

        // (1) Bands in full-resolution rows; every band consumes the same number of random values
        // so the layout does not depend on the parameter outcomes of earlier bands.
        val fullH = h / scale
        val bands = ArrayList<Band>()
        var y = 0f
        while (y < fullH) {
            val r0 = rng.nextFloat()
            val bh = max(1f, (bandHeight * (0.25f + 1.5f * r0 * r0)).roundToInt().toFloat())
            val band = Band(min(h, (y * scale).roundToInt()), min(h, ((y + bh) * scale).roundToInt()))
            val rGlitch = rng.nextFloat(); val rDx = rng.nextFloat(); val rBlocks = rng.nextFloat()
            val rShift = rng.nextFloat(); val rTorn = rng.nextFloat(); val rTornFrom = rng.nextFloat()
            val rNoise = rng.nextFloat(); val rNoiseAmt = rng.nextFloat(); val blockSeed = rng.nextInt()
            if (rGlitch < strength) {
                band.glitched = true
                band.dx = (rDx * 2f - 1f) * strength * 0.25f
                band.colorShift = shiftFull * (0.5f + rShift)
                if (rBlocks < blockChance) makeBlocks(band, blockSeed, strength)
                if (rTorn < 0.05f + 0.1f * strength) band.tornFrom = rTornFrom
            }
            if (rNoise < noiseLevel * 0.35f) band.noise = noiseLevel * (0.3f + 0.7f * rNoiseAmt)
            if ((band.glitched || band.noise > 0f) && band.y1 > band.y0) bands += band
            y += bh
        }
        val rowBand = arrayOfNulls<Band>(h)
        for (b in bands) for (row in b.y0 until b.y1) rowBand[row] = b

        // (2) The colors: the fringes' path (v1.7's combine on one-hot colors, else the mix; with
        // no fringe color the band only slides) and the static's color.
        val colors = FringeColors.resolve(values, fringes)
        val none = FringeColors.allNone(colors)
        val k = if (none) null else FringeColors.oneHotFactors(colors)
        val rgb = k != null && k[0] == 1 && k[1] == 0 && k[2] == -1
        val mix = if (none || k != null) null else FringeColors.Mix(colors)
        val static = (values.raw(STATIC_COLOR_KEY) as? Number)?.toInt() ?: FringeColors.WHITE
        val sr = ((static shr 16) and 0xFF) / 255f; val sg = ((static shr 8) and 0xFF) / 255f; val sb = (static and 0xFF) / 255f
        val sa = (static ushr 24) / 255f
        val white = static == FringeColors.WHITE

        // (3) Render rows.
        val out = src.copy()
        val sp = src.pixels; val dp = out.pixels
        Parallel.forRows(h) { r0, r1 ->
            ctx.checkCancelled()
            val dxRow = IntArray(w)
            for (row in r0 until r1) {
                val band = rowBand[row] ?: continue
                val srcRow = if (band.tornFrom >= 0f) {
                    ArtMath.clampInt((band.tornFrom * h).toInt() + (row - band.y0), 0, h - 1)
                } else row
                val so = srcRow * w
                val o = row * w
                if (band.glitched) {
                    fillOffsets(band, w, dxRow)
                    val cs = (band.colorShift * scale).roundToInt()
                    when {
                        rgb -> splitRgb(sp, so, dp, o, w, dxRow, cs)
                        k != null -> split(sp, so, dp, o, w, dxRow, -k[0] * cs, -k[1] * cs, -k[2] * cs)
                        mix != null -> mixRow(sp, so, dp, o, w, dxRow, cs, mix)
                        else -> for (x in 0 until w) dp[o + x] = sp[so + Math.floorMod(x - dxRow[x], w)]
                    }
                } else if (srcRow != row) {
                    System.arraycopy(sp, so, dp, o, w)
                }
                if (band.noise > 0f) if (white) addStatic(dp, o, w, row, scale, band.noise, seed) else addColoredStatic(dp, o, w, row, scale, band.noise * sa, seed, sr, sg, sb)
            }
        }
        return out
    }

    companion object {
        /** v1.8 (item 5): the param key of the static's color (default white). */
        const val STATIC_COLOR_KEY = "static_color"
    }

    /** Splits a band into blocks 5–30 % of the width wide, each with its own offset. */
    private fun makeBlocks(band: Band, blockSeed: Int, strength: Float) {
        val r = Random(blockSeed)
        val starts = ArrayList<Float>(); val dxs = ArrayList<Float>()
        var x = 0f
        while (x < 1f) {
            starts += x
            dxs += if (r.nextFloat() < 0.6f) (r.nextFloat() * 2f - 1f) * strength * 0.3f else band.dx
            x += 0.05f + 0.25f * r.nextFloat()
        }
        band.blockStarts = starts.toFloatArray()
        band.blockDx = dxs.toFloatArray()
    }

    private fun fillOffsets(band: Band, w: Int, dxRow: IntArray) {
        val starts = band.blockStarts
        val dxs = band.blockDx
        if (starts == null || dxs == null) {
            dxRow.fill((band.dx * w).roundToInt())
            return
        }
        for (i in starts.indices) {
            val x0 = (starts[i] * w).roundToInt().coerceIn(0, w)
            val x1 = if (i + 1 < starts.size) (starts[i + 1] * w).roundToInt().coerceIn(0, w) else w
            if (x1 > x0) dxRow.fill((dxs[i] * w).roundToInt(), x0, x1)
        }
    }

    /**
     * v1.7's channel split, the default colors (red first, green, blue last): its loop as it was.
     * Each row path is a method of its own, so each loop is compiled on its own (as fast as v1.7's).
     */
    private fun splitRgb(sp: IntArray, so: Int, dp: IntArray, o: Int, w: Int, dxRow: IntArray, cs: Int) {
        for (x in 0 until w) {
            val sx = x - dxRow[x]
            val cr = sp[so + Math.floorMod(sx - cs, w)]
            val cg = sp[so + Math.floorMod(sx, w)]
            val cb = sp[so + Math.floorMod(sx + cs, w)]
            dp[o + x] = combine(cr, cg, cb)
        }
    }

    /** The fast path's split for other one-hot colors: channel c sampled at `sx + off_c` (`-k_c · cs`). */
    private fun split(sp: IntArray, so: Int, dp: IntArray, o: Int, w: Int, dxRow: IntArray, offR: Int, offG: Int, offB: Int) {
        for (x in 0 until w) {
            val sx = x - dxRow[x]
            val cr = sp[so + Math.floorMod(sx + offR, w)]
            val cg = sp[so + Math.floorMod(sx + offG, w)]
            val cb = sp[so + Math.floorMod(sx + offB, w)]
            dp[o + x] = combine(cr, cg, cb)
        }
    }

    /**
     * The general path's split: the first sample at `sx - cs`, the middle at `sx`, the last at
     * `sx + cs`. Channel c is the swatch-weighted mean `Σ_i w[3i + c] · P_i,c` of the three
     * samples' coverage-weighted channels over the output alpha, the largest alpha among the
     * colored samples (design §3.6 (c), as Chromatic aberration's). Where the three share one alpha
     * (an opaque picture) that is the weighted mean of the straight channels, here in 16.16 fixed
     * point ([FringeColors.Mix.fixed]: a uniform picture stays exact); elsewhere in floats. The
     * weights live in locals, and a sample inside the row skips [Math.floorMod]'s division
     * ([wrap]): the general path's budget is 1.3 x v1.7's time.
     */
    private fun mixRow(sp: IntArray, so: Int, dp: IntArray, o: Int, w: Int, dxRow: IntArray, cs: Int, mix: FringeColors.Mix) {
        val f = mix.fixed
        val f0r = f[0]; val f0g = f[1]; val f0b = f[2]; val f1r = f[3]; val f1g = f[4]; val f1b = f[5]
        val f2r = f[6]; val f2g = f[7]; val f2b = f[8]
        val m = mix.w
        val w0r = m[0]; val w0g = m[1]; val w0b = m[2]; val w1r = m[3]; val w1g = m[4]; val w1b = m[5]
        val w2r = m[6]; val w2g = m[7]; val w2b = m[8]
        val k0 = mix.colored[0]; val k1 = mix.colored[1]; val k2 = mix.colored[2]
        for (x in 0 until w) {
            val sx = x - dxRow[x]
            val c0 = sp[so + wrap(sx - cs, w)]
            val c1 = sp[so + wrap(sx, w)]
            val c2 = sp[so + wrap(sx + cs, w)]
            val a0 = c0 ushr 24; val a1 = c1 ushr 24; val a2 = c2 ushr 24
            dp[o + x] = if (a0 == a1 && a0 == a2) {
                if (a0 == 0) 0 else (a0 shl 24) or
                    (((f0r * ((c0 shr 16) and 0xFF) + f1r * ((c1 shr 16) and 0xFF) + f2r * ((c2 shr 16) and 0xFF) + 0x8000) ushr 16) shl 16) or
                    (((f0g * ((c0 shr 8) and 0xFF) + f1g * ((c1 shr 8) and 0xFF) + f2g * ((c2 shr 8) and 0xFF) + 0x8000) ushr 16) shl 8) or
                    ((f0b * (c0 and 0xFF) + f1b * (c1 and 0xFF) + f2b * (c2 and 0xFF) + 0x8000) ushr 16)
            } else {
                var a = 0
                if (k0) a = a0
                if (k1 && a1 > a) a = a1
                if (k2 && a2 > a) a = a2
                if (a == 0) 0 else {
                    val p0 = a0.toFloat(); val p1 = a1.toFloat(); val p2 = a2.toFloat()
                    val r = w0r * p0 * ((c0 shr 16) and 0xFF) + w1r * p1 * ((c1 shr 16) and 0xFF) + w2r * p2 * ((c2 shr 16) and 0xFF)
                    val g = w0g * p0 * ((c0 shr 8) and 0xFF) + w1g * p1 * ((c1 shr 8) and 0xFF) + w2g * p2 * ((c2 shr 8) and 0xFF)
                    val b = w0b * p0 * (c0 and 0xFF) + w1b * p1 * (c1 and 0xFF) + w2b * p2 * (c2 and 0xFF)
                    val inv = 1f / a
                    (a shl 24) or (ChannelShift.clamp(r * inv) shl 16) or (ChannelShift.clamp(g * inv) shl 8) or ChannelShift.clamp(b * inv)
                }
            }
        }
    }

    /** [Math.floorMod] (i, w) with no division when i is already inside the row. */
    private fun wrap(i: Int, w: Int): Int = if (i >= 0 && i < w) i else Math.floorMod(i, w)

    /** Red, green and blue from three samples; alpha is the largest of the three sampled alphas. */
    private fun combine(cr: Int, cg: Int, cb: Int): Int {
        val ar = cr ushr 24; val ag = cg ushr 24; val ab = cb ushr 24
        val a = max(ar, max(ag, ab))
        if (a == 0) return 0
        if (ar == a && ag == a && ab == a) return (cr and 0xFFFF0000.toInt()) or (cg and 0xFF00) or (cb and 0xFF)
        val r = ((cr shr 16) and 0xFF) * ar / a
        val g = ((cg shr 8) and 0xFF) * ag / a
        val b = (cb and 0xFF) * ab / a
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Digital static: short horizontal dashes of random gray mixed in by [amount] (v1.7's, for white). */
    private fun addStatic(dp: IntArray, o: Int, w: Int, row: Int, scale: Float, amount: Float, seed: Int) {
        val fy = floor(row / scale).toInt()
        val inv = 1f / scale
        for (x in 0 until w) {
            val c = dp[o + x]
            if (c ushr 24 == 0) continue
            val fx = floor(x * inv).toInt()
            val n = FilterMath.hash01(fx / 3, fy, seed * 13 + 5)
            val k = amount * FilterMath.hash01(fx / 11, fy, seed * 13 + 9)
            val v = n * 255f
            val r = ((c shr 16) and 0xFF); val g = ((c shr 8) and 0xFF); val b = c and 0xFF
            dp[o + x] = (c and 0xFF000000.toInt()) or
                (ChannelShift.clamp(r + (v - r) * k) shl 16) or
                (ChannelShift.clamp(g + (v - g) * k) shl 8) or
                ChannelShift.clamp(b + (v - b) * k)
        }
    }

    /**
     * v1.8: [addStatic] in the static's color: the dashes mix toward `n · S_c`, here
     * `(n · 255) · (S_c / 255)` with [sr], [sg], [sb] the color's channels over 255 (white gives
     * [addStatic]'s bytes; this form measured as fast as it, `n · S_c` slower).
     */
    private fun addColoredStatic(dp: IntArray, o: Int, w: Int, row: Int, scale: Float, amount: Float, seed: Int, sr: Float, sg: Float, sb: Float) {
        val fy = floor(row / scale).toInt()
        val inv = 1f / scale
        for (x in 0 until w) {
            val c = dp[o + x]
            if (c ushr 24 == 0) continue
            val fx = floor(x * inv).toInt()
            val n = FilterMath.hash01(fx / 3, fy, seed * 13 + 5)
            val k = amount * FilterMath.hash01(fx / 11, fy, seed * 13 + 9)
            val v = n * 255f
            val r = ((c shr 16) and 0xFF); val g = ((c shr 8) and 0xFF); val b = c and 0xFF
            dp[o + x] = (c and 0xFF000000.toInt()) or
                (ChannelShift.clamp(r + (v * sr - r) * k) shl 16) or
                (ChannelShift.clamp(g + (v * sg - g) * k) shl 8) or
                ChannelShift.clamp(b + (v * sb - b) * k)
        }
    }
}
