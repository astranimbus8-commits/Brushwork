package com.brushwork.paint.filters.style

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.min

/*
 * Outline ("Stroke") filters. All of them measure the distance to the 50 % alpha contour of the
 * layer with a sub-pixel signed distance field, so outlines are smooth at any width and antialiased
 * with an adjustable ramp. Widths are in full-resolution pixels (scaled by ctx.px for previews).
 */

/** Outline outside the drawn shapes (ibisPaint "Stroke (Outer)"), placed behind the content. */
class StrokeOuterFilter : Filter("style.stroke_outer", "Stroke (Outer)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("width", "Width", 1f, 300f, 10f, 1f, pixels = true),
        StyleMath.antialiasParam(),
        FilterParam.Color("color", "Color", 0xFF000000.toInt()),
        StyleMath.opacityParam(),
        StyleMath.outputParam("Stroke behind layer", "Stroke only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val width = ctx.px(values.float("width").coerceAtLeast(0f))
        val soft = StyleMath.softness(values.float("antialias"), ctx)
        val color = values.color("color")
        val opacity = StyleMath.percent(values.float("opacity")).coerceIn(0f, 1f)
        val only = values.choice("output") == 1
        return StyleMath.aroundContent(src, StyleMath.margin(width + soft)) { img, _, _ ->
            val sd = StyleMath.signedDistance(img, ctx)
            val w = img.width
            FilterMath.mapXY(img, ctx) { x, y, c ->
                // Inside pixels are fully covered too, so the stroke also fills under the artwork.
                val cov = StyleMath.coverageWithin(sd[y * w + x], width, soft) * opacity
                if (only) StyleMath.solid(color, cov) else StyleMath.behind(c, color, cov)
            }
        }
    }
}

/**
 * Outline straddling the edge (ibisPaint "Stroke (Both)"): extends [outer width] outside and
 * [inner width] inside the shape edge, drawn over the content.
 */
class StrokeBothFilter : Filter("style.stroke_both", "Stroke (Both)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("outer_width", "Outer width", 0f, 300f, 4f, 1f, pixels = true),
        FilterParam.Slider("inner_width", "Inner width", 0f, 300f, 4f, 1f, pixels = true),
        StyleMath.antialiasParam(),
        FilterParam.Color("color", "Color", 0xFF000000.toInt()),
        StyleMath.opacityParam(),
        StyleMath.outputParam("Stroke over layer", "Stroke only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val outer = ctx.px(values.float("outer_width").coerceAtLeast(0f))
        val inner = ctx.px(values.float("inner_width").coerceAtLeast(0f))
        if (outer + inner <= 0f) {
            return if (values.choice("output") == 1) PixelBuffer(src.width, src.height) else src.copy()
        }
        val soft = StyleMath.softness(values.float("antialias"), ctx)
        val color = values.color("color")
        val opacity = StyleMath.percent(values.float("opacity")).coerceIn(0f, 1f)
        val only = values.choice("output") == 1
        return StyleMath.aroundContent(src, StyleMath.margin(outer + soft)) { img, _, _ ->
            val sd = StyleMath.signedDistance(img, ctx)
            val w = img.width
            FilterMath.mapXY(img, ctx) { x, y, c ->
                val d = sd[y * w + x]
                val cov = min(StyleMath.ramp(outer - d, soft), StyleMath.ramp(d + inner, soft)) * opacity
                if (only) StyleMath.solid(color, cov) else StyleMath.over(c, color, cov)
            }
        }
    }
}

/** Outline inside the shape only, clipped to the layer's alpha. */
class StrokeInnerFilter : Filter("style.stroke_inner", "Stroke (Inner)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("width", "Width", 1f, 300f, 6f, 1f, pixels = true),
        StyleMath.antialiasParam(),
        FilterParam.Color("color", "Color", 0xFF000000.toInt()),
        StyleMath.opacityParam(),
        StyleMath.outputParam("Stroke on layer", "Stroke only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val width = ctx.px(values.float("width").coerceAtLeast(0f))
        val soft = StyleMath.softness(values.float("antialias"), ctx)
        val color = values.color("color")
        val opacity = StyleMath.percent(values.float("opacity")).coerceIn(0f, 1f)
        val only = values.choice("output") == 1
        return StyleMath.aroundContent(src, StyleMath.margin(soft)) { img, _, _ ->
            val sd = StyleMath.signedDistance(img, ctx)
            val w = img.width
            FilterMath.mapXY(img, ctx) { x, y, c ->
                val cov = StyleMath.ramp(sd[y * w + x] + width, soft) * opacity
                if (only) StyleMath.solid(color, cov * (c ushr 24) / 255f) else StyleMath.atop(c, color, cov)
            }
        }
    }
}
