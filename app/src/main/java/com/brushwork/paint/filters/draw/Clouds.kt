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
 * Procedural fractal clouds (Perlin fBm) colored through two colors or a gradient. The noise
 * range is auto-leveled so every type and roughness spans the whole color range; brightness and
 * contrast then shift and stretch it.
 */
class CloudsFilter : Filter("draw.clouds", "Clouds", FilterCategory.DRAW) {
    override val generatesContent = true

    override val params: List<FilterParam> = GradationParams.colorParams(GradationParams.MODE_TWO_COLORS) + listOf(
        FilterParam.Choice("type", "Type", listOf("Soft", "Billowy", "Ridged"), Noise.PLAIN),
        FilterParam.Slider("scale", "Scale", 2f, 200f, 40f, 1f, "%"),
        FilterParam.Slider("rough", "Roughness", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("stretch", "Horizontal stretch", 0f, 100f, 0f, 1f, "%"),
        FilterParam.Slider("brightness", "Brightness", -100f, 100f, 0f, 1f),
        FilterParam.Slider("contrast", "Contrast", -100f, 100f, 0f, 1f),
        FilterParam.Toggle("reverse", "Reverse", false),
        DrawBlend.opacityParam(),
        DrawBlend.param(),
        FilterParam.Seed(),
    )

    /** Everything needed to evaluate the normalized (0..1, before brightness) cloud value. */
    internal class Field(
        private val base: Float, private val minPeriod: Float, private val persistence: Float,
        private val seed: Int, private val mode: Int, private val xScale: Float,
    ) {
        var lo = 0f; var hi = 1f

        fun raw(x: Float, y: Float): Float =
            Noise.fbm(x * xScale, y, base, minPeriod, persistence, seed, mode, skipBelow = 1f)

        fun value(x: Float, y: Float): Float = (raw(x, y) - lo) / (hi - lo)
    }

    internal fun field(values: FilterValues, w: Int, h: Int, ctx: FilterContext): Field {
        val longSide = max(w, h).toFloat()
        val base = max(0.5f, values.float("scale").coerceIn(0.5f, 1000f) / 100f * longSide)
        val rough = values.float("rough").coerceIn(0f, 100f) / 100f
        val stretch = values.float("stretch").coerceIn(0f, 100f) / 100f
        val f = Field(
            base = base,
            // Finest octave: ~2 full-resolution pixels (sub-pixel octaves of a preview are skipped
            // but still normalized, so the preview matches the full render).
            minPeriod = max(1e-3f, ctx.px(2f)),
            persistence = 0.3f + 0.45f * rough,
            seed = values.seed(),
            mode = values.choice("type").coerceIn(Noise.PLAIN, Noise.RIDGED),
            xScale = 1f / (1f + 3f * stretch),
        )
        // Auto-levels from a fixed grid of samples at the same relative positions at any scale.
        val grid = 48
        val samples = FloatArray(grid * grid)
        for (j in 0 until grid) for (i in 0 until grid) {
            samples[j * grid + i] = f.raw((i + 0.5f) / grid * w, (j + 0.5f) / grid * h)
        }
        samples.sort()
        f.lo = samples[(samples.size * 0.01f).toInt()]
        f.hi = samples[(samples.size * 0.99f).toInt()]
        if (!(f.hi - f.lo > 1e-4f)) { f.lo -= 0.5f; f.hi = f.lo + 1f }
        return f
    }

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val opacity = (values.float("opacity") / 100f).coerceIn(0f, 1f)
        if (opacity <= 0f) return src.copy()
        val field = field(values, src.width, src.height, ctx)
        val lut = GradientLut(GradationParams.stops(values))
        val blend = values.choice("blend").coerceIn(0, DrawBlend.NAMES.lastIndex)
        val b = values.float("brightness").coerceIn(-100f, 100f) / 100f
        val c = values.float("contrast").coerceIn(-100f, 100f) / 100f
        val gain = if (c >= 0f) 1f + 4f * c else 1f + c
        val reverse = values.bool("reverse")
        return FilterMath.mapXY(src, ctx) { x, y, px ->
            var t = (field.value(x + 0.5f, y + 0.5f) - 0.5f) * gain + 0.5f + 0.5f * b
            if (reverse) t = 1f - t
            DrawBlend.composite(px, lut.at(t), opacity, blend)
        }
    }
}
