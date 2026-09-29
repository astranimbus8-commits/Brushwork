package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.abs

/**
 * Gaussian Blur: uniform soft blur. Radius is roughly how far the blur reaches (sigma = radius / 2,
 * the same convention as FilterMath.blur). Alpha-correct: transparent areas never darken edges, and
 * line art on a transparent layer blurs outward into the transparency.
 */
class GaussianBlurFilter : Filter("blur.gaussian", "Gaussian Blur", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("radius", "Radius", 0f, 250f, 10f, pixels = true),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val sigma = ctx.px(values.float("radius")) / 2f
        if (BlurCore.boxSpec(sigma) == null) return src.copy()
        return gaussianBlur(src, sigma, ctx)
    }
}

/** Alpha-correct Gaussian blur of [src] with standard deviation [sigma] (buffer pixels). */
internal fun gaussianBlur(src: PixelBuffer, sigma: Float, ctx: FilterContext): PixelBuffer {
    val out = PixelBuffer(src.width, src.height)
    val d = out.pixels
    BlurCore.blurChannels(src, sigma, sigma, ctx) { shift, color, alpha ->
        Parallel.forRange(d.size, BlurCore.FLAT_CHUNK) { i0, i1 ->
            for (i in i0 until i1) {
                val a = if (alpha == null) 255 else ColorUtils.clamp255(alpha[i])
                if (a == 0) continue
                d[i] = d[i] or (a shl 24) or (ColorUtils.clamp255(color[i]) shl shift)
            }
        }
    }
    return out
}

/**
 * Unsharp Mask: sharpens by adding back the difference between the image and a blurred copy.
 * Radius sets the halo width (same scale as Gaussian Blur), Amount the strength, and Threshold
 * skips differences smaller than that many levels (keeps flat areas and noise untouched).
 * Alpha is kept as is.
 */
class UnsharpMaskFilter : Filter("blur.unsharp_mask", "Unsharp Mask", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("radius", "Radius", 0f, 100f, 4f, pixels = true),
        FilterParam.Slider("amount", "Amount", 0f, 500f, 100f, step = 1f, suffix = "%"),
        FilterParam.Slider("threshold", "Threshold", 0f, 255f, 0f, step = 1f),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val sigma = ctx.px(values.float("radius")) / 2f
        val amount = values.float("amount").coerceIn(0f, 1000f) / 100f
        val threshold = values.float("threshold").coerceIn(0f, 256f)
        if (BlurCore.boxSpec(sigma) == null || amount <= 0f) return src.copy()

        val s = src.pixels
        val out = PixelBuffer(src.width, src.height)
        val d = out.pixels
        BlurCore.blurChannels(src, sigma, sigma, ctx) { shift, blurred, alpha ->
            Parallel.forRange(d.size, BlurCore.FLAT_CHUNK) { i0, i1 ->
                for (i in i0 until i1) {
                    val c = s[i]
                    val a = c ushr 24
                    if (a == 0) continue
                    val x = (c shr shift) and 0xFF
                    // Where the blurred alpha is ~0 there is no meaningful neighbourhood colour.
                    val v = if (alpha != null && alpha[i] <= 1e-3f) {
                        x
                    } else {
                        val diff = x - blurred[i]
                        if (abs(diff) < threshold) x else ColorUtils.clamp255(x + amount * diff)
                    }
                    d[i] = d[i] or (a shl 24) or (v shl shift)
                }
            }
        }
        return out
    }
}
