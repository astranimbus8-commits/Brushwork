package com.brushwork.paint.filters.art.v17

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * v1.8 F0: a FROZEN copy of the v1.7 Glitch filter (`filters/art/GlitchFilter.kt` at 885793b4),
 * in test sources only: v1.8's Glitch is checked against it (white static is byte-equal, seeds
 * keep the bands) and timed against it. The same arithmetic, random draws and parallel rows as
 * v1.7, so the output is byte-equal and the cost the same. Never change it.
 *
 * [apply] takes what v1.7's `apply` took: the values `height`, `strength`, `colorShift`,
 * `blocks`, `noise` and `seed`, and the context's scale.
 */
object GlitchV17 {
    private class Band(val y0: Int, val y1: Int) {
        var glitched = false
        var dx = 0f
        var colorShift = 0f
        var blockStarts: FloatArray? = null
        var blockDx: FloatArray? = null
        var tornFrom = -1f
        var noise = 0f
    }

    /** v1.7's Glitch. */
    fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext = FilterContext()): PixelBuffer {
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

        val out = src.copy()
        val sp = src.pixels; val dp = out.pixels
        Parallel.forRows(h) { r0, r1 ->
            ctx.checkCancelled()
            val dxRow = IntArray(w)
            for (row in r0 until r1) {
                val band = rowBand[row] ?: continue
                val srcRow = if (band.tornFrom >= 0f) {
                    ArtMathV17.clampInt((band.tornFrom * h).toInt() + (row - band.y0), 0, h - 1)
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

    private fun addStatic(dp: IntArray, o: Int, w: Int, row: Int, scale: Float, amount: Float, seed: Int) {
        val fy = floor(row / scale).toInt()
        val inv = 1f / scale
        for (x in 0 until w) {
            val c = dp[o + x]
            if (c ushr 24 == 0) continue
            val fx = floor(x * inv).toInt()
            val n = ArtMathV17.hash01(fx / 3, fy, seed * 13 + 5)
            val k = amount * ArtMathV17.hash01(fx / 11, fy, seed * 13 + 9)
            val v = n * 255f
            val r = ((c shr 16) and 0xFF); val g = ((c shr 8) and 0xFF); val b = c and 0xFF
            dp[o + x] = (c and 0xFF000000.toInt()) or
                (ChannelShiftV17.clamp(r + (v - r) * k) shl 16) or
                (ChannelShiftV17.clamp(g + (v - g) * k) shl 8) or
                ChannelShiftV17.clamp(b + (v - b) * k)
        }
    }
}
