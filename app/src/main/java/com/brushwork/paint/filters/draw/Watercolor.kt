package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.max

/**
 * Photo -> watercolor painting: edge-preserving smoothing and color simplification form flat
 * washes, pigment pools darker along wash edges, color bleeds with a turbulent wobble, pigment
 * granulates, and the result sits on textured white paper. Sizes are relative to the image.
 */
class WatercolorFilter : Filter("draw.watercolor", "Watercolor", FilterCategory.DRAW) {

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("wash", "Wash smoothing", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("simplify", "Color simplification", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("edges", "Edge darkening", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("bleed", "Bleeding", 0f, 100f, 40f, 1f, "%"),
        FilterParam.Slider("granulation", "Granulation", 0f, 100f, 35f, 1f, "%"),
        FilterParam.Slider("paper", "Paper texture", 0f, 100f, 45f, 1f, "%"),
        FilterParam.Slider("lightness", "Paper whiteness", 0f, 100f, 25f, 1f, "%"),
        FilterParam.Slider("saturation", "Saturation", -100f, 100f, 0f, 1f),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val size = Stylize.workSize(src.width, src.height, WORK_LONG)
        val work = Stylize.downsampleLab(src, size[0], size[1], ctx)
        val ww = work.w; val wh = work.h
        val u = max(ww, wh) / 800f
        Stylize.fillTransparent(work, 3, 3, 4f * u, ctx)
        val seed = values.seed()
        val wash = values.float("wash").coerceIn(0f, 100f) / 100f
        val iterations = if (wash <= 0f) 0 else 1 + (wash * 2.99f).toInt()
        Stylize.bilateral(work, 3, 3, u * (1.5f + 3f * wash), 8f + 12f * wash, iterations, ctx)
        ctx.progress(0.4f)

        val simplify = values.float("simplify").coerceIn(0f, 100f) / 100f
        if (simplify > 0f) {
            val k = (24 - 18 * simplify).toInt().coerceIn(2, 24)
            val centers = Stylize.kMeans(work, k, seed, ctx)
            Stylize.quantizeTowards(work, centers, simplify * 0.75f, ctx)
        }
        ctx.progress(0.55f)
        // Wet-edge pigment pooling: strength from the lightness gradient of the washes.
        val edges = values.float("edges").coerceIn(0f, 100f) / 100f
        val edgeMap = if (edges > 0f) {
            val g = Stylize.gradient(work[0], ww, wh, ctx)
            val blurred = FilterMath.gaussianBlurPlane(g, ww, wh, 0.8f * u, ctx)
            for (i in blurred.indices) blurred[i] = (blurred[i] / 12f).coerceIn(0f, 1f)
            blurred
        } else null
        ctx.progress(0.7f)

        val bufLong = max(src.width, src.height).toFloat()
        val ub = bufLong / 800f // relative unit in output pixels
        val bleedAmp = values.float("bleed").coerceIn(0f, 100f) / 100f * 4f * ub
        val bleedPeriod = 28f * ub
        val gran = values.float("granulation").coerceIn(0f, 100f) / 100f
        val grain = max(0.75f, 1.3f * ub)
        val paper = values.float("paper").coerceIn(0f, 100f) / 100f
        val lift = 1f - 0.6f * values.float("lightness").coerceIn(0f, 100f) / 100f
        val sat = max(0f, 1f + values.float("saturation").coerceIn(-100f, 100f) / 100f)
        val sx = ww.toFloat() / src.width; val sy = wh.toFloat() / src.height
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val alpha = c ushr 24
            if (alpha == 0) return@mapXY 0
            val fx = x + 0.5f; val fy = y + 0.5f
            var px = fx; var py = fy
            if (bleedAmp > 0f) {
                px += bleedAmp * Noise.perlin(fx / bleedPeriod, fy / bleedPeriod, seed)
                py += bleedAmp * Noise.perlin(fx / bleedPeriod + 17.3f, fy / bleedPeriod - 9.1f, seed + 1)
            }
            val wx = px * sx; val wy = py * sy
            val l = work.sample(0, wx, wy)
            val a = work.sample(1, wx, wy) * sat
            val b = work.sample(2, wx, wy) * sat
            val rgb = Lab.toRgb(l, a, b)
            // Pigment density per channel (0 = white paper).
            var density = 1f
            if (edgeMap != null) density += 0.9f * edges * Planes.sample(edgeMap, ww, wh, wx, wy)
            if (gran > 0f) {
                val g = FilterMath.valueNoise(fx / grain, fy / grain, seed + 11) * 0.6f +
                    FilterMath.valueNoise(fx / (grain * 3.1f), fy / (grain * 3.1f), seed + 12) * 0.4f
                density *= 1f + 1.1f * gran * (g - 0.5f)
            }
            var shade = 1f
            if (paper > 0f) {
                // Signed paper height: pigment settles in the valleys, the peaks catch light.
                val h = Noise.fbm(fx, fy, 14f * ub, 1.2f * ub, 0.55f, seed + 23, Noise.PLAIN, skipBelow = 0.75f)
                density *= 1f - 0.5f * paper * h
                shade = 1f + 0.12f * paper * h
            }
            density *= lift
            val r = pigment((rgb shr 16) and 0xFF, density, shade)
            val gg = pigment((rgb shr 8) and 0xFF, density, shade)
            val bb = pigment(rgb and 0xFF, density, shade)
            (alpha shl 24) or (r shl 16) or (gg shl 8) or bb
        }
    }

    private fun pigment(v: Int, density: Float, shade: Float): Int {
        val absorb = (255 - v) * density
        return ((255f - absorb) * shade).toInt().coerceIn(0, 255)
    }

    private companion object { const val WORK_LONG = 1280 }
}
