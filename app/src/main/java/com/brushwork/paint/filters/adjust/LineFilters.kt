package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Exponent e for density = t^e (t: 0 = no line, 1 = full line) such that the point [middle] of
 * the way from the Black to the White level (t = 1 - middle) gets 50 % density; 0.5 is linear,
 * lower values give lighter lines and higher values darker ones.
 */
private fun middleExponent(middle: Float): Float {
    val m = middle.coerceIn(0.01f, 0.99f)
    return (ln(0.5) / ln(1.0 - m)).toFloat()
}

/** Luma (0..255) of a straight color composited over white. */
private fun lumaOverWhite(c: Int): Float {
    val a = c ushr 24
    val y = AdjustMath.luma(c)
    return if (a == 255) y else (y * a + 255f * (255 - a)) / 255f
}

/**
 * Darkest channel (0..255) of a straight color composited over white: how far the pixel is from
 * white paper in any channel. A saturated color (e.g. yellow) is bright by luma but far from white
 * in one channel, so this keeps colored lines visible and lets [unmix] reproduce them exactly.
 */
private fun minChannelOverWhite(c: Int): Float {
    val a = c ushr 24
    val m = min((c shr 16) and 0xFF, min((c shr 8) and 0xFF, c and 0xFF)).toFloat()
    return if (a == 255) m else (m * a + 255f * (255 - a)) / 255f
}

/**
 * Line color channel for a pixel channel [ch] with alpha [a] whose over-white composite should be
 * reproduced by a line of density [k] (0..1] over white: solves  over = line * k + 255 * (1 - k).
 */
private fun unmix(ch: Int, a: Int, k: Float): Int {
    val over = (ch * a + 255 * (255 - a)) / 255f
    return ColorUtils.clamp255((over - 255f * (1f - k)) / k)
}

/**
 * Turns a scanned or photographed drawing into line art on transparency: paper (at or above the
 * White level) becomes transparent, ink (at or below the Black level) fully opaque, with the
 * Middle value bending the transition. Lines are drawn in one color (density from luminance), or
 * keep their own color: density then comes from the darkest channel (so colored lines such as
 * yellow pencil survive) and the color is un-mixed from the white paper, so the result composites
 * back over white like the original.
 */
class ExtractLineDrawingFilter : Filter("adjust.extract_line_drawing", "Extract Line Drawing", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("black", "Black", 0f, 100f, 20f, 1f, "%"),
        FilterParam.Slider("white", "White", 0f, 100f, 85f, 1f, "%"),
        FilterParam.Slider("middle", "Middle value", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Toggle("keepColor", "Keep line color", false),
        FilterParam.Color("lineColor", "Line color", 0xFF000000.toInt()),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val density = densityLut(values.float("black") / 100f, values.float("white") / 100f, values.float("middle") / 100f)
        val keepColor = values.bool("keepColor")
        val lineRgb = values.color("lineColor") and 0xFFFFFF
        return FilterMath.mapPixels(src, ctx) { c ->
            val a = c ushr 24
            val y = if (keepColor) minChannelOverWhite(c) else lumaOverWhite(c)
            val na = density[(y + 0.5f).toInt().coerceIn(0, 255)]
            when {
                na == 0 -> 0
                !keepColor -> (na shl 24) or lineRgb
                else -> {
                    val k = na / 255f
                    ColorUtils.argbUnchecked(na, unmix((c shr 16) and 0xFF, a, k), unmix((c shr 8) and 0xFF, a, k), unmix(c and 0xFF, a, k))
                }
            }
        }
    }

    companion object {
        /** Output alpha for every composited luminance 0..255. */
        fun densityLut(black: Float, white: Float, middle: Float): IntArray {
            val b = black.coerceIn(0f, 1f); val w = white.coerceIn(0f, 1f)
            val e = middleExponent(middle)
            return IntArray(256) { i ->
                val y = i / 255f
                val t = if (w > b) ((w - y) / (w - b)).coerceIn(0f, 1f) else if (y < (w + b) * 0.5f) 1f else 0f
                ColorUtils.clamp255(t.pow(e) * 255f)
            }
        }
    }
}

/**
 * Outlines of a picture as a line drawing. Luminance (composited over white) is smoothed, edge
 * strength measured with Sobel (gradient magnitude), Laplacian or DoG (difference of gaussians;
 * both only on the dark side of an edge, giving single lines), then mapped to line density with
 * Black (edge strength that gives a full line), White (strength below which there is no line) and
 * a Middle value. Lines go on transparency or on white.
 */
class FindEdgesFilter : Filter("adjust.find_edges", "Find Edges", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Choice("algorithm", "Algorithm", listOf("Sobel", "Laplacian", "DoG"), 0),
        FilterParam.Slider("smoothness", "Smoothness", 0f, 10f, 1f, 0.1f, pixels = true),
        FilterParam.Slider("black", "Black", 0f, 100f, 40f, 1f, "%"),
        FilterParam.Slider("white", "White", 0f, 100f, 8f, 1f, "%"),
        FilterParam.Slider("middle", "Middle value", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Choice("background", "Background", listOf("Transparent", "White"), 0),
        FilterParam.Color("lineColor", "Line color", 0xFF000000.toInt()),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val algorithm = values.choice("algorithm")
        val sigma = ctx.px(values.float("smoothness").coerceAtLeast(0f))
        // Output color for every quantized edge strength.
        val density = densityLut(values.float("black") / 100f, values.float("white") / 100f, values.float("middle") / 100f)
        val lineRgb = values.color("lineColor") and 0xFFFFFF
        val onWhite = values.choice("background") == 1
        val lr = (lineRgb shr 16) and 0xFF; val lg = (lineRgb shr 8) and 0xFF; val lb = lineRgb and 0xFF
        val colors = IntArray(LUT_SIZE) { i ->
            val na = density[i]
            if (onWhite) {
                val inv = 255 - na
                ColorUtils.argbUnchecked(255, (lr * na + 255 * inv + 127) / 255, (lg * na + 255 * inv + 127) / 255, (lb * na + 255 * inv + 127) / 255)
            } else if (na == 0) 0 else (na shl 24) or lineRgb
        }

        val sp = src.pixels
        val lum = FloatArray(src.size) { lumaOverWhite(sp[it]) / 255f }
        val out = PixelBuffer(w, h)
        val d = out.pixels
        if (algorithm == 2) {
            // Floor in THIS buffer's pixels: a full-resolution floor would fall below the blur
            // threshold on a downscaled preview and leave the DoG (and the preview) empty.
            val s1 = max(sigma, 0.5f)
            val wide = lum.copyOf()
            AdjustMath.gaussianBlurInPlace(lum, w, h, s1, ctx)
            AdjustMath.gaussianBlurInPlace(wide, w, h, s1 * 1.6f, ctx)
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                for (i in y0 * w until y1 * w) d[i] = colors[lutIndex(max(0f, wide[i] - lum[i]) * DOG_NORM)]
            }
        } else {
            AdjustMath.gaussianBlurInPlace(lum, w, h, sigma, ctx)
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                for (y in y0 until y1) {
                    val up = max(0, y - 1) * w; val row = y * w; val dn = min(h - 1, y + 1) * w
                    for (x in 0 until w) {
                        val xl = max(0, x - 1); val xr = min(w - 1, x + 1)
                        val tl = lum[up + xl]; val tc = lum[up + x]; val tr = lum[up + xr]
                        val ml = lum[row + xl]; val mc = lum[row + x]; val mr = lum[row + xr]
                        val bl = lum[dn + xl]; val bc = lum[dn + x]; val br = lum[dn + xr]
                        val strength = if (algorithm == 1) {
                            max(0f, tl + tc + tr + ml + mr + bl + bc + br - 8f * mc) / 3f
                        } else {
                            val gx = (tr + 2f * mr + br) - (tl + 2f * ml + bl)
                            val gy = (bl + 2f * bc + br) - (tl + 2f * tc + tr)
                            sqrt(gx * gx + gy * gy) * 0.25f
                        }
                        d[row + x] = colors[lutIndex(strength)]
                    }
                }
            }
        }
        return out
    }

    private fun lutIndex(strength: Float): Int = (strength.coerceIn(0f, 1f) * (LUT_SIZE - 1) + 0.5f).toInt()

    companion object {
        private const val LUT_SIZE = 1024
        /** A unit step edge gives a DoG (k = 1.6) peak of ~0.112; this maps it to ~1. */
        private const val DOG_NORM = 1f / 0.112f

        /** Line alpha for edge strengths 0..1 sampled at [LUT_SIZE] steps. */
        fun densityLut(black: Float, white: Float, middle: Float): IntArray {
            val b = black.coerceIn(0f, 1f); val wt = white.coerceIn(0f, 1f)
            val e = middleExponent(middle)
            return IntArray(LUT_SIZE) { i ->
                val g = i / (LUT_SIZE - 1f)
                val t = if (b > wt) ((g - wt) / (b - wt)).coerceIn(0f, 1f) else if (g > (b + wt) * 0.5f) 1f else 0f
                ColorUtils.clamp255(t.pow(e) * 255f)
            }
        }
    }
}
