package com.brushwork.paint.filters.style

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Crepuscular light shafts (ibisPaint "God Rays"): light emitted by the painted areas (or by the
 * bright areas, with dark areas occluding) is scattered along rays spreading away from the light
 * source point. Screen-space volumetric scattering: every pixel averages the emission on the
 * segment towards the light with an exponential falloff.
 *
 * The 512 samples per pixel are evaluated exactly as three passes of 8 zoom samples each, spaced
 * in log-scale (base-8 digit decomposition), at up to [WORK_SIZE] px and upsampled.
 */
class GodRaysFilter : Filter("style.god_rays", "God Rays", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("light", "Light source", 0.5f, 0.1f),
        FilterParam.Choice("source", "Light comes from", listOf("Painted areas", "Bright areas"), 0),
        FilterParam.Slider("threshold", "Brightness threshold", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("length", "Length", 1f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("brightness", "Brightness", 0f, 300f, 100f, 1f, "%"),
        FilterParam.Slider("falloff", "Falloff", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Color("color", "Color", 0xFFFFF1CC.toInt()),
        StyleMath.outputParam("Rays on layer", "Rays only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        if (StyleMath.contentBounds(src) == null) return src.copy()
        val w = src.width; val h = src.height
        val light = values.point("light")
        val bright = values.choice("source") == 1
        val thr = StyleMath.percent(values.float("threshold")).coerceIn(0f, 0.99f)
        val length = StyleMath.percent(values.float("length")).coerceIn(0.01f, 1f)
        val brightness = StyleMath.percent(values.float("brightness")).coerceAtLeast(0f)
        val falloff = StyleMath.percent(values.float("falloff")).coerceIn(0f, 1f)
        val color = values.color("color")
        val only = values.choice("output") == 1

        // Emission at working resolution (box-averaged).
        val f = max(1, ceil(max(w, h) / WORK_SIZE.toFloat()).toInt())
        val ws = (w + f - 1) / f; val hs = (h + f - 1) / f
        val emission = FloatArray(ws * hs)
        val lumScale = 1f / (1f - thr)
        Parallel.forRows(hs) { y0, y1 ->
            ctx.checkCancelled()
            for (sy in y0 until y1) for (sx in 0 until ws) {
                var acc = 0f; var cnt = 0
                val yEnd = min(h, (sy + 1) * f); val xEnd = min(w, (sx + 1) * f)
                for (y in sy * f until yEnd) for (x in sx * f until xEnd) {
                    val c = src.pixels[y * w + x]
                    val a = (c ushr 24) / 255f
                    acc += if (bright) a * ((ColorUtils.luminance(c) / 255f - thr) * lumScale).coerceIn(0f, 1f) else a
                    cnt++
                }
                emission[sy * ws + sx] = acc / cnt
            }
        }

        // Light position in working-plane coordinates (pixel centres at integers).
        val cx = (light[0] * w - 0.5f - (f - 1) * 0.5f) / f
        val cy = (light[1] * h - 0.5f - (f - 1) * 0.5f) / f
        val total = -ln(1f - 0.98f * length)
        val lambda = falloff * 5f / total
        var taps = 1
        repeat(PASSES) { taps *= TAPS }
        val delta1 = total / (taps - 1)
        var plane = emission
        var scratch = FloatArray(ws * hs)
        var delta = delta1
        repeat(PASSES) {
            val scales = FloatArray(TAPS) { i -> exp(-i * delta) }
            val weights = FloatArray(TAPS) { i -> exp(-lambda * i * delta) }
            val norm = 1f / weights.sum()
            for (i in weights.indices) weights[i] *= norm
            val input = plane; val output = scratch
            Parallel.forRows(hs) { y0, y1 ->
                ctx.checkCancelled()
                for (y in y0 until y1) {
                    val dy = y - cy
                    for (x in 0 until ws) {
                        val dx = x - cx
                        var acc = 0f
                        for (i in 0 until TAPS) {
                            val s = scales[i]
                            acc += weights[i] * StyleMath.sampleZero(input, ws, hs, cx + dx * s, cy + dy * s)
                        }
                        output[y * ws + x] = acc
                    }
                }
            }
            scratch = input; plane = output
            delta *= TAPS
        }
        val rays = plane
        val gain = brightness * GAIN
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val v = if (f == 1) rays[y * ws + x]
            else StyleMath.sample(rays, ws, hs, (x - (f - 1) * 0.5f) / f, (y - (f - 1) * 0.5f) / f)
            val cov = (v * gain).coerceIn(0f, 1f)
            if (only) StyleMath.solid(color, cov) else StyleMath.screen(c, color, cov)
        }
    }

    private companion object {
        const val WORK_SIZE = 2560
        const val TAPS = 8
        const val PASSES = 3
        const val GAIN = 2.5f
    }
}
