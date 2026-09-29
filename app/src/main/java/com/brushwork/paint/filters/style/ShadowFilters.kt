package com.brushwork.paint.filters.style

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Blurred, offset copy of the layer's silhouette placed behind it (ibisPaint "Drop Shadow").
 * Angle is the direction the shadow is cast (0 = right, 90 = up); Spread hardens part of the blur
 * into a solid core, like Photoshop's spread.
 */
class DropShadowFilter : Filter("style.drop_shadow", "Drop Shadow", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("angle", "Angle", 0f, 360f, 315f, 1f, "°"),
        FilterParam.Slider("distance", "Distance", 0f, 500f, 12f, 1f, pixels = true),
        FilterParam.Slider("blur", "Blur", 0f, 300f, 10f, 1f, pixels = true),
        FilterParam.Slider("spread", "Spread", 0f, 100f, 0f, 1f, "%"),
        FilterParam.Color("color", "Color", 0xFF000000.toInt()),
        StyleMath.opacityParam(60f),
        StyleMath.outputParam("Shadow behind layer", "Shadow only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val dir = StyleMath.direction(values.float("angle"))
        val dist = ctx.px(values.float("distance").coerceAtLeast(0f))
        val ox = dir[0] * dist; val oy = dir[1] * dist
        val blur = ctx.px(values.float("blur").coerceAtLeast(0f))
        val spread = StyleMath.percent(values.float("spread")).coerceIn(0f, 1f)
        val core = blur * spread
        val sigma = StyleMath.sigmaForRadius(blur * (1f - spread))
        val color = values.color("color")
        val opacity = StyleMath.percent(values.float("opacity")).coerceIn(0f, 1f)
        val only = values.choice("output") == 1
        val bb = StyleMath.contentBounds(src) ?: return src.copy()
        val m = StyleMath.margin(core + sigma * 3f)
        val l = min(bb[0], floor(bb[0] + ox).toInt()) - m
        val t = min(bb[1], floor(bb[1] + oy).toInt()) - m
        val r = max(bb[2], ceil(bb[2] + ox).toInt()) + m
        val b = max(bb[3], ceil(bb[3] + oy).toInt()) + m
        return StyleMath.cropped(src, l, t, r, b) { img, _, _ ->
            val w = img.width; val h = img.height
            val a: FloatArray
            if (core > 0.01f) {
                a = StyleMath.signedDistance(img, ctx)
                val p = img.pixels
                for (i in a.indices) a[i] = max((p[i] ushr 24) / 255f, StyleMath.coverageWithin(a[i], core, 1f))
            } else {
                a = StyleMath.alphaPlane(img, ctx)
            }
            StyleMath.gaussianInPlace(a, w, h, sigma, ctx)
            FilterMath.mapXY(img, ctx) { x, y, c ->
                val cov = StyleMath.sample(a, w, h, x - ox, y - oy) * opacity
                if (only) StyleMath.solid(color, cov) else StyleMath.behind(c, color, cov)
            }
        }
    }
}

/**
 * Satin (Photoshop-style satin model, as in ibisPaint): two blurred copies of the shape offset
 * towards and away from the light are differenced, producing glossy bands that follow the
 * contours inside the shape.
 */
class SatinFilter : Filter("style.satin", "Satin", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Color("color", "Color", 0xFF000000.toInt()),
        FilterParam.Point("light", "Light source", 0.25f, 0.25f),
        StyleMath.opacityParam(50f),
        FilterParam.Slider("distance", "Distance", 1f, 300f, 12f, 1f, pixels = true),
        FilterParam.Slider("size", "Size", 0f, 300f, 14f, 1f, pixels = true),
        FilterParam.Toggle("invert", "Invert", false),
        StyleMath.outputParam("Satin on layer", "Satin only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val light = values.point("light")
        var dx = light[0] * src.width - src.width * 0.5f
        var dy = light[1] * src.height - src.height * 0.5f
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-3f || len.isNaN()) { dx = -0.7071f; dy = -0.7071f } else { dx /= len; dy /= len }
        val dist = ctx.px(values.float("distance").coerceAtLeast(0f))
        val size = ctx.px(values.float("size").coerceAtLeast(0f))
        val color = values.color("color")
        val opacity = StyleMath.percent(values.float("opacity")).coerceIn(0f, 1f)
        val invert = values.bool("invert")
        val only = values.choice("output") == 1
        val vx = dx * dist; val vy = dy * dist
        return StyleMath.aroundContent(src, StyleMath.margin(dist + size * 1.6f)) { img, _, _ ->
            val w = img.width; val h = img.height
            val a = StyleMath.alphaPlane(img, ctx, normalized = true)
            StyleMath.gaussianInPlace(a, w, h, StyleMath.sigmaForRadius(size), ctx)
            FilterMath.mapXY(img, ctx) { x, y, c ->
                if (c ushr 24 == 0) {
                    if (only) 0 else c
                } else {
                    val s1 = StyleMath.sample(a, w, h, x - vx, y - vy)
                    val s2 = StyleMath.sample(a, w, h, x + vx, y + vy)
                    var s = abs(s1 - s2).coerceIn(0f, 1f)
                    if (invert) s = 1f - s
                    val cov = s * opacity
                    if (only) StyleMath.solid(color, cov * (c ushr 24) / 255f) else StyleMath.atop(c, color, cov)
                }
            }
        }
    }
}
