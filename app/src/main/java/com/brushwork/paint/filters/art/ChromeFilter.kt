package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Chrome: a polished-metal look. The luminance (times alpha, plus a bulge near transparent edges
 * so flat fills get rounded borders) is treated as a height field; its smoothed normals bend a
 * banded environment reflection, v = 0.5 + 0.5 cos(2π (1 + Detail) e), sharpened with a
 * smoothstep. The result is gray, optionally tinted (e.g. gold) with white specular highlights.
 * Alpha is kept.
 */
class ChromeFilter : Filter("art.chrome", "Chrome", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("detail", "Detail", 0f, 10f, 3f),
        FilterParam.Slider("smoothness", "Smoothness", 0f, 10f, 4f),
        FilterParam.Slider("slope", "Slope", 0f, 100f, 40f, step = 1f),
        FilterParam.Color("tint", "Tint", -1),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height; val n = src.size
        val detail = values.float("detail").coerceIn(0f, 10f)
        val smooth = values.float("smoothness").coerceIn(0f, 10f)
        val slope = values.float("slope").coerceIn(0f, 100f)
        val tint = values.color("tint")
        val sp = src.pixels

        // (1) Height field: luma × alpha plus a bulge that rises over 8 px from transparent edges
        // (a constant when nothing is transparent). The distance array is reused as the height
        // field to keep peak memory at two float planes.
        var anyTransparent = false
        for (i in 0 until n) if (sp[i] ushr 24 < 128) { anyTransparent = true; break }
        val height = if (anyTransparent) {
            val clear = FloatArray(n) { 1f - (sp[it] ushr 24) / 255f }
            val dist = FilterMath.distanceToCoverage(clear, w, h, 0.5f, ctx)
            val bulge = max(ctx.px(8f), 1e-3f)
            for (i in 0 until n) dist[i] = 0.5f * (dist[i] / bulge).coerceIn(0f, 1f)
            dist
        } else {
            FloatArray(n) { 0.5f }
        }
        for (i in 0 until n) {
            val c = sp[i]
            height[i] += ArtMath.luma((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) * (c ushr 24) / 65025f
        }
        ctx.checkCancelled()

        // (2) Smooth the metal surface.
        ArtMath.gaussInPlace(height, w, h, ctx.px(0.5f + smooth * 1.5f), ctx)

        // (3) Reflection bands via a lookup over the phase, then tint.
        val lut = FloatArray(LUT_SIZE + 1) { i ->
            val v = 0.5f + 0.5f * cos(2.0 * Math.PI * i / LUT_SIZE).toFloat()
            ArtMath.smoothstep(0.1f, 0.9f, v)
        }
        val freq = 1f + detail
        // Height derivatives per full-resolution pixel (the buffer may be a downscaled preview).
        val k = slope * ctx.scale * 0.5f
        val tr = ((tint shr 16) and 0xFF) / 255f; val tg = ((tint shr 8) and 0xFF) / 255f; val tb = (tint and 0xFF) / 255f
        val out = PixelBuffer(w, h)
        val dp = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val up = (if (y > 0) y - 1 else y) * w
                val dn = (if (y < h - 1) y + 1 else y) * w
                val row = y * w
                for (x in 0 until w) {
                    val c = sp[row + x]
                    val a = c ushr 24
                    if (a == 0) continue
                    val l = if (x > 0) x - 1 else x
                    val r = if (x < w - 1) x + 1 else x
                    val nx = -k * (height[row + r] - height[row + l])
                    val ny = -k * (height[dn + x] - height[up + x])
                    val e = height[row + x] + 0.5f * ny / sqrt(nx * nx + ny * ny + 1f)
                    val ph = freq * e
                    val v = lut[((ph - floor(ph)) * LUT_SIZE).toInt().coerceIn(0, LUT_SIZE)]
                    val spec = ((v - 0.8f) / 0.2f).coerceIn(0f, 1f).let { it * it }
                    val cr = (v * tr + (1f - tr) * spec) * 255f
                    val cg = (v * tg + (1f - tg) * spec) * 255f
                    val cb = (v * tb + (1f - tb) * spec) * 255f
                    dp[row + x] = (a shl 24) or (ChannelShift.clamp(cr) shl 16) or (ChannelShift.clamp(cg) shl 8) or ChannelShift.clamp(cb)
                }
            }
        }
        return out
    }

    private companion object {
        const val LUT_SIZE = 4096
    }
}
