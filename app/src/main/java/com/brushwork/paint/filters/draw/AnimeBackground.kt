package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Photo -> anime-style background: edge-preserving smoothing flattens texture into clean
 * areas, colors are simplified towards a small palette (soft k-means), lightness is softly
 * quantized into cel-like bands, colors are made more vivid (with an optional clean-blue sky
 * push) and thin dark outlines are added along strong edges.
 * Sizes are relative to the image, so the preview matches the full-resolution result.
 */
class AnimeBackgroundFilter : Filter("draw.anime_background", "Anime Background", FilterCategory.DRAW) {

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("smoothing", "Smoothing", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("colors", "Color simplification", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("levels", "Shading levels", 2f, 16f, 7f, 1f),
        FilterParam.Slider("brightness", "Brightness", -100f, 100f, 5f, 1f),
        FilterParam.Slider("contrast", "Contrast", -100f, 100f, 10f, 1f),
        FilterParam.Slider("saturation", "Saturation", -100f, 100f, 40f, 1f),
        FilterParam.Slider("sky", "Sky color", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("outline", "Outlines", 0f, 100f, 35f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val size = Stylize.workSize(src.width, src.height, WORK_LONG)
        val work = Stylize.downsampleLab(src, size[0], size[1], ctx)
        val u = max(size[0], size[1]) / 800f
        Stylize.fillTransparent(work, 3, 3, 4f * u, ctx)
        val smooth = values.float("smoothing").coerceIn(0f, 100f) / 100f
        val iterations = if (smooth <= 0f) 0 else 1 + (smooth * 2.99f).toInt()
        Stylize.bilateral(work, 3, 3, u * (1.5f + 5f * smooth), 5f + 9f * smooth, iterations, ctx)
        ctx.progress(0.6f)
        val outline = values.float("outline").coerceIn(0f, 100f) / 100f
        // Outlines follow the smoothed photo, not the boundaries the palette step creates.
        val dog = if (outline > 0f) Stylize.dog(work[0], work.w, work.h, 0.9f * u, ctx) else null
        val colors = values.float("colors").coerceIn(0f, 100f) / 100f
        if (colors > 0f) {
            // Hue and chroma snap to a palette of 28..8 colors; lightness is only pulled halfway
            // because the shading bands below quantize it anyway.
            val centers = Stylize.kMeans(work, (28 - 20 * colors).toInt().coerceIn(2, 28), PALETTE_SEED, ctx)
            Stylize.quantizeTowards(work, centers, 0.9f * sqrt(colors), ctx, lightWeight = 0.5f, softness = 6f)
        }
        // Edges need a lightness drop of about 4..12 L units to be outlined.
        val eps = 1.4f - 1.0f * outline
        val inkGain = 1.2f
        val quant = SoftQuantizer(values.int("levels").coerceIn(1, 64), 2.5f)
        val contrast = 1f + 0.8f * values.float("contrast").coerceIn(-100f, 100f) / 100f
        val bright = 30f * values.float("brightness").coerceIn(-100f, 100f) / 100f
        val sat = max(0f, 1f + values.float("saturation").coerceIn(-100f, 100f) / 100f)
        val sky = values.float("sky").coerceIn(0f, 100f) / 100f
        ctx.progress(0.7f)
        return Stylize.render(src, work, ctx) { _, _, wx, wy, c ->
            val alpha = c ushr 24
            if (alpha == 0) return@render 0
            var l = work.sample(0, wx, wy)
            var a = work.sample(1, wx, wy) * sat
            var b = work.sample(2, wx, wy) * sat
            l = quant.apply((50f + (l - 50f) * contrast + bright).coerceIn(0f, 100f))
            if (sky > 0f) {
                val chroma = sqrt(a * a + b * b)
                if (chroma > 4f && l > 35f) {
                    var hue = atan2(b, a) * (180f / PI.toFloat())
                    if (hue < 0f) hue += 360f
                    val wgt = sky * (1f - Coverage.smoothstep(10f, 55f, kotlin.math.abs(hue - SKY_HUE))) *
                        Coverage.smoothstep(4f, 14f, chroma) * Coverage.smoothstep(35f, 60f, l)
                    if (wgt > 0f) {
                        val h2 = (hue + (SKY_HUE - hue) * 0.6f * wgt) * (PI.toFloat() / 180f)
                        val c2 = chroma * (1f + 0.5f * wgt)
                        a = c2 * cos(h2); b = c2 * sin(h2)
                        l = (l + 6f * wgt).coerceAtMost(100f)
                    }
                }
            }
            var rgb = Lab.toRgb(l, a, b)
            if (dog != null) {
                val d = Planes.sample(dog, work.w, work.h, wx, wy)
                val ink = ((-d - eps) * inkGain).coerceIn(0f, 1f) * outline
                if (ink > 0f) rgb = darken(rgb, 1f - 0.8f * ink)
            }
            (alpha shl 24) or rgb
        }
    }

    private fun darken(rgb: Int, k: Float): Int {
        val r = (((rgb shr 16) and 0xFF) * k + 0.5f).toInt()
        val g = (((rgb shr 8) and 0xFF) * k + 0.5f).toInt()
        val b = ((rgb and 0xFF) * k + 0.5f).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val WORK_LONG = 1280
        const val PALETTE_SEED = 1
        /** Lab hue (degrees) of a clean, slightly cyan anime sky blue. */
        const val SKY_HUE = 262f
    }
}
