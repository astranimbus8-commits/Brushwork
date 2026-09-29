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
import kotlin.math.sqrt

/**
 * Crepuscular light shafts (ibisPaint "God Rays") scattered along rays spreading away from the
 * light source point. The light comes from the painted areas (ibisPaint's behaviour), from the
 * bright areas (dark areas occlude), or from a glowing light behind the layer whose painted pixels
 * occlude it, so shafts shine through the gaps between the shapes (clouds, leaves, windows).
 * Screen-space volumetric scattering: every pixel averages the emission on the segment towards the
 * light with an exponential falloff.
 *
 * The 512 samples per pixel are evaluated exactly as three passes of 8 zoom samples each, spaced
 * in log-scale (base-8 digit decomposition), at up to [WORK_SIZE] px and upsampled.
 */
class GodRaysFilter : Filter("style.god_rays", "God Rays", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("light", "Light source", 0.5f, 0.1f),
        FilterParam.Choice("source", "Light comes from", listOf("Painted areas", "Bright areas", "Light behind layer"), 0),
        FilterParam.Slider("threshold", "Bright areas: threshold", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("source_size", "Light behind layer: size", 1f, 2000f, 300f, 1f, pixels = true),
        FilterParam.Slider("length", "Length", 1f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("brightness", "Brightness", 0f, 300f, 100f, 1f, "%"),
        FilterParam.Slider("falloff", "Falloff", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Color("color", "Color", 0xFFFFF1CC.toInt()),
        StyleMath.outputParam("Rays with layer", "Rays only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        if (StyleMath.contentBounds(src) == null) return src.copy()
        val w = src.width; val h = src.height
        val light = values.point("light")
        val source = values.choice("source").coerceIn(SOURCE_PAINTED, SOURCE_BEHIND)
        val thr = StyleMath.percent(values.float("threshold")).coerceIn(0f, 0.99f)
        val radius = max(0.5f, ctx.px(values.float("source_size").coerceAtLeast(0f)))
        val length = StyleMath.percent(values.float("length")).coerceIn(0.01f, 1f)
        val brightness = StyleMath.percent(values.float("brightness")).coerceAtLeast(0f)
        val falloff = StyleMath.percent(values.float("falloff")).coerceIn(0f, 1f)
        val color = values.color("color")
        val only = values.choice("output") == 1
        // Light position in buffer pixels (pixel centres at integers).
        val lx = light[0] * w - 0.5f
        val ly = light[1] * h - 0.5f

        // Emission at working resolution (box-averaged).
        val f = max(1, ceil(max(w, h) / WORK_SIZE.toFloat()).toInt())
        val ws = (w + f - 1) / f; val hs = (h + f - 1) / f
        val emission = FloatArray(ws * hs)
        val lumScale = 1f / (1f - thr)
        val core = radius * 0.25f
        val invRim = 1f / (radius - core)
        Parallel.forRows(hs) { y0, y1 ->
            ctx.checkCancelled()
            for (sy in y0 until y1) for (sx in 0 until ws) {
                var acc = 0f; var cnt = 0
                val yEnd = min(h, (sy + 1) * f); val xEnd = min(w, (sx + 1) * f)
                for (y in sy * f until yEnd) for (x in sx * f until xEnd) {
                    val c = src.pixels[y * w + x]
                    val a = (c ushr 24) / 255f
                    acc += when (source) {
                        SOURCE_PAINTED -> a
                        SOURCE_BRIGHT -> a * ((ColorUtils.luminance(c) / 255f - thr) * lumScale).coerceIn(0f, 1f)
                        else -> {
                            // A glowing sky around the light, blocked by whatever is painted in front of it.
                            val dx = x - lx; val dy = y - ly
                            val t = max(0f, sqrt(dx * dx + dy * dy) - core) * invRim
                            exp(-t * t) * (1f - a)
                        }
                    }
                    cnt++
                }
                emission[sy * ws + sx] = acc / cnt
            }
        }

        // Light position in working-plane coordinates (pixel centres at integers).
        val cx = (lx - (f - 1) * 0.5f) / f
        val cy = (ly - (f - 1) * 0.5f) / f
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
        val behind = source == SOURCE_BEHIND
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val v = if (f == 1) rays[y * ws + x]
            else StyleMath.sample(rays, ws, hs, (x - (f - 1) * 0.5f) / f, (y - (f - 1) * 0.5f) / f)
            // Soft saturation: dense light approaches full coverage without flat clipped areas.
            val cov = 1f - exp(-max(0f, v) * gain)
            when {
                only -> StyleMath.solid(color, cov)
                // Occluders stay dark silhouettes in front of the light.
                behind -> StyleMath.behind(c, color, cov)
                else -> StyleMath.screen(c, color, cov)
            }
        }
    }

    private companion object {
        const val SOURCE_PAINTED = 0
        const val SOURCE_BRIGHT = 1
        const val SOURCE_BEHIND = 2
        const val WORK_SIZE = 2560
        const val TAPS = 8
        const val PASSES = 3
        const val GAIN = 3f
    }
}
