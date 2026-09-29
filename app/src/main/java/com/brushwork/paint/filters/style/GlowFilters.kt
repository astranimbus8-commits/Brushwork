package com.brushwork.paint.filters.style

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.max

/**
 * Light spreading outward from the drawn shapes (ibisPaint "Glow (Outer)", a neon look). Normal
 * mode is a gaussian falloff of the layer alpha boosted by Strength; "Crystal glow" uses a crisp
 * linear ramp of the distance to the shape. Spread turns part of the size into a solid core.
 */
class GlowOuterFilter : Filter("style.glow_outer", "Glow (Outer)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("size", "Size", 1f, 300f, 20f, 1f, pixels = true),
        FilterParam.Slider("spread", "Spread", 0f, 100f, 0f, 1f, "%"),
        FilterParam.Slider("strength", "Strength", 0f, 300f, 150f, 1f, "%"),
        FilterParam.Color("color", "Color", 0xFF40C4FF.toInt()),
        StyleMath.opacityParam(),
        FilterParam.Toggle("crystal", "Crystal glow", false),
        StyleMath.outputParam("Glow behind layer", "Glow only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val size = max(0.5f, ctx.px(values.float("size").coerceAtLeast(0f)))
        val spread = StyleMath.percent(values.float("spread")).coerceIn(0f, 1f)
        val strength = StyleMath.percent(values.float("strength")).coerceAtLeast(0f)
        val color = values.color("color")
        val opacity = StyleMath.percent(values.float("opacity")).coerceIn(0f, 1f)
        val crystal = values.bool("crystal")
        val only = values.choice("output") == 1
        val core = spread * size
        val fall = (1f - spread) * size
        return StyleMath.aroundContent(src, StyleMath.margin(core + fall * 1.6f)) { img, _, _ ->
            val w = img.width; val h = img.height
            val g: FloatArray
            if (crystal) {
                g = StyleMath.signedDistance(img, ctx)
                val inv = 1f / max(0.5f, fall)
                for (i in g.indices) g[i] = (1f - max(0f, g[i] - core) * inv).coerceIn(0f, 1f)
            } else {
                if (core > 0.01f) {
                    g = StyleMath.signedDistance(img, ctx)
                    val p = img.pixels
                    for (i in g.indices) g[i] = max((p[i] ushr 24) / 255f, StyleMath.coverageWithin(g[i], core, 1f))
                } else {
                    g = StyleMath.alphaPlane(img, ctx)
                }
                StyleMath.gaussianInPlace(g, w, h, StyleMath.sigmaForRadius(fall), ctx)
                for (i in g.indices) g[i] = (g[i] * strength).coerceIn(0f, 1f)
            }
            FilterMath.mapXY(img, ctx) { x, y, c ->
                val cov = g[y * w + x] * opacity
                if (only) StyleMath.solid(color, cov) else StyleMath.behind(c, color, cov)
            }
        }
    }
}

/**
 * Light glowing inward from the edges of the shapes (ibisPaint "Glow (Inner)"), clipped to the
 * layer's alpha. "Crystal glow" gives a sharp, faceted rim instead of a soft falloff.
 */
class GlowInnerFilter : Filter("style.glow_inner", "Glow (Inner)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("size", "Size", 1f, 300f, 16f, 1f, pixels = true),
        FilterParam.Slider("strength", "Strength", 0f, 300f, 100f, 1f, "%"),
        FilterParam.Color("color", "Color", 0xFFFFFFFF.toInt()),
        StyleMath.opacityParam(),
        FilterParam.Toggle("crystal", "Crystal glow", false),
        StyleMath.outputParam("Glow on layer", "Glow only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val size = max(0.5f, ctx.px(values.float("size").coerceAtLeast(0f)))
        val strength = StyleMath.percent(values.float("strength")).coerceAtLeast(0f)
        val color = values.color("color")
        val opacity = StyleMath.percent(values.float("opacity")).coerceIn(0f, 1f)
        val crystal = values.bool("crystal")
        val only = values.choice("output") == 1
        return StyleMath.aroundContent(src, StyleMath.margin(size * 1.6f)) { img, _, _ ->
            val w = img.width; val h = img.height
            val g: FloatArray
            if (crystal) {
                g = StyleMath.signedDistance(img, ctx)
                val inv = 1f / size
                for (i in g.indices) g[i] = ((1f + g[i] * inv) * max(1f, strength)).coerceIn(0f, 1f) * minOf(1f, strength)
            } else {
                g = StyleMath.alphaPlane(img, ctx, normalized = true)
                StyleMath.gaussianInPlace(g, w, h, StyleMath.sigmaForRadius(size), ctx)
                for (i in g.indices) g[i] = (2f * strength * (1f - g[i])).coerceIn(0f, 1f)
            }
            FilterMath.mapXY(img, ctx) { x, y, c ->
                val cov = g[y * w + x] * opacity
                if (only) StyleMath.solid(color, cov * (c ushr 24) / 255f) else StyleMath.atop(c, color, cov)
            }
        }
    }
}
