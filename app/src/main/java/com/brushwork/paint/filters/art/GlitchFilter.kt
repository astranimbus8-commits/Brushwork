package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
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
 */
class GlitchFilter : Filter("art.glitch", "Glitch", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("height", "Height", 1f, 200f, 20f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("strength", "Strength", 0f, 100f, 50f, step = 1f, suffix = "%"),
        FilterParam.Slider("colorShift", "Color shift", 0f, 100f, 10f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("blocks", "Block noise", 0f, 100f, 30f, step = 1f, suffix = "%"),
        FilterParam.Slider("noise", "Static", 0f, 100f, 20f, step = 1f, suffix = "%"),
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

        // (2) Render rows.
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
                    for (x in 0 until w) {
                        val sx = x - dxRow[x]
                        val cr = sp[so + Math.floorMod(sx - cs, w)]
                        val cg = sp[so + Math.floorMod(sx, w)]
                        val cb = sp[so + Math.floorMod(sx + cs, w)]
                        dp[o + x] = combine(cr, cg, cb)
                    }
                } else if (srcRow != row) {
                    System.arraycopy(sp, so, dp, o, w)
                }
                if (band.noise > 0f) addStatic(dp, o, w, row, scale, band.noise, seed)
            }
        }
        return out
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

    /** Digital static: short horizontal dashes of random gray mixed in by [amount]. */
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
}
