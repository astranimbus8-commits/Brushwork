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

/**
 * Noise: adds grayscale or colored random grain. [strength] is the size of each deviation,
 * [amount] the fraction of grains that receive noise, [size] the grain size (grains larger than a
 * pixel are smoothly interpolated, like film grain). Alpha is kept; transparent pixels stay untouched.
 */
class NoiseFilter : Filter("art.noise", "Noise", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("strength", "Strength", 0f, 100f, 25f, step = 1f, suffix = "%"),
        FilterParam.Slider("amount", "Amount", 0f, 100f, 100f, step = 1f, suffix = "%"),
        FilterParam.Choice("mode", "Mode", listOf("Grayscale", "Color"), 0),
        FilterParam.Choice("distribution", "Distribution", listOf("Uniform", "Gaussian"), 1),
        FilterParam.Slider("size", "Grain size", 1f, 20f, 1f, step = 0.5f, suffix = "px", pixels = true),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val strength = (values.float("strength") / 100f).coerceIn(0f, 1f)
        val amount = (values.float("amount") / 100f).coerceIn(0f, 1f)
        if (strength <= 0f || amount <= 0f) return src.copy()
        val color = values.choice("mode") == 1
        val gaussian = values.choice("distribution") == 1
        val cell = ctx.px(values.float("size").coerceIn(1f, 20f))
        val seed = values.seed()
        val channels = if (color) 3 else 1
        val seeds = IntArray(channels) { seed * 31 + it * 1013 + 17 }
        val coverSeed = seed * 31 + 99991
        val amp = strength * if (gaussian) 128f else 255f
        val w = src.width; val h = src.height

        fun noiseAt(i: Int, j: Int, s: Int): Float =
            if (gaussian) ArtMath.gaussHash(i, j, s) else ArtMath.signedHash(i, j, s)

        val out = PixelBuffer(w, h)
        val sp = src.pixels; val dp = out.pixels
        if (cell <= 1.0001f) {
            // One independent value per pixel (or per full-resolution grain in a downscaled preview).
            val inv = 1f / cell
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                val nv = FloatArray(3)
                for (y in y0 until y1) {
                    val j = floor(y * inv).toInt()
                    for (x in 0 until w) {
                        val idx = y * w + x
                        val c = sp[idx]
                        if (c ushr 24 == 0) { dp[idx] = c; continue }
                        val i = floor(x * inv).toInt()
                        if (amount < 1f && FilterMath.hash01(i, j, coverSeed) >= amount) { dp[idx] = c; continue }
                        for (ch in 0 until channels) nv[ch] = noiseAt(i, j, seeds[ch]) * amp
                        dp[idx] = addNoise(c, nv, color)
                    }
                }
            }
            return out
        }

        // Smooth grains: noise on a lattice with spacing [cell], interpolated with smoothstep weights.
        // Interpolation lowers the variance; 1.346 restores the per-pixel standard deviation.
        val lw = floor((w - 1) / cell).toInt() + 2
        val lh = floor((h - 1) / cell).toInt() + 2
        val comp = 1.346f * amp
        val lattice = Array(channels) { ch ->
            val l = FloatArray(lw * lh)
            Parallel.forRows(lh) { j0, j1 ->
                for (j in j0 until j1) for (i in 0 until lw) l[j * lw + i] = noiseAt(i, j, seeds[ch]) * comp
            }
            l
        }
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val nv = FloatArray(3)
            for (y in y0 until y1) {
                val fy = y / cell
                val j = floor(fy).toInt()
                val sy = ArtMath.smoothstep(0f, 1f, fy - j)
                for (x in 0 until w) {
                    val idx = y * w + x
                    val c = sp[idx]
                    if (c ushr 24 == 0) { dp[idx] = c; continue }
                    val fx = x / cell
                    val i = floor(fx).toInt()
                    if (amount < 1f && FilterMath.hash01(i, j, coverSeed) >= amount) { dp[idx] = c; continue }
                    val sx = ArtMath.smoothstep(0f, 1f, fx - i)
                    val o = j * lw + i
                    for (ch in 0 until channels) {
                        val l = lattice[ch]
                        val top = l[o] + (l[o + 1] - l[o]) * sx
                        val bot = l[o + lw] + (l[o + lw + 1] - l[o + lw]) * sx
                        nv[ch] = top + (bot - top) * sy
                    }
                    dp[idx] = addNoise(c, nv, color)
                }
            }
        }
        return out
    }

    private fun addNoise(c: Int, n: FloatArray, color: Boolean): Int {
        val nr = n[0]
        val ng = if (color) n[1] else nr
        val nb = if (color) n[2] else nr
        val r = ((c shr 16) and 0xFF) + nr
        val g = ((c shr 8) and 0xFF) + ng
        val b = (c and 0xFF) + nb
        return (c and 0xFF000000.toInt()) or (ChannelShift.clamp(r) shl 16) or (ChannelShift.clamp(g) shl 8) or ChannelShift.clamp(b)
    }
}
