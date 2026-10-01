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
import com.brushwork.paint.filters.PixelMapper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val CHANNELS = listOf("RGB", "Red", "Green", "Blue")

/** Per-channel tables for a channel choice: 0 = all channels, 1..3 = only R, G or B. */
private fun channelLuts(channel: Int, lut: IntArray): Triple<IntArray, IntArray, IntArray> {
    val id = AdjustMath.identityLut()
    return when (channel) {
        1 -> Triple(lut, id, id)
        2 -> Triple(id, lut, id)
        3 -> Triple(id, id, lut)
        else -> Triple(lut, lut, lut)
    }
}

/**
 * Brightness blends every tone toward white (positive) or black (negative); contrast then pushes
 * tones away from mid-gray (positive, +100 = hard threshold) or pulls them toward it (negative).
 */
class BrightnessContrastFilter : Filter("adjust.brightness_contrast", "Brightness & Contrast", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("brightness", "Brightness", -100f, 100f, 0f, 1f),
        FilterParam.Slider("contrast", "Contrast", -100f, 100f, 0f, 1f),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer =
        AdjustMath.applyRgbLut(src, ctx, lut(values.float("brightness") / 100f, values.float("contrast") / 100f))

    companion object {
        /** [brightness] and [contrast] in -1..1. */
        fun lut(brightness: Float, contrast: Float): IntArray {
            val b = brightness.coerceIn(-1f, 1f)
            val c = contrast.coerceIn(-1f, 1f)
            val k = if (c >= 0f) 1f / max(1e-3f, 1f - c) else 1f + c
            return AdjustMath.lut { v ->
                val v1 = if (b >= 0f) v + (1f - v) * b else v * (1f + b)
                (v1 - 0.5f) * k + 0.5f
            }
        }
    }
}

/** Remaps tones through a monotone cubic curve, on the composite or a single channel. */
class ToneCurveFilter : Filter("adjust.tone_curve", "Tone Curve", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Choice("channel", "Channel", CHANNELS, 0),
        FilterParam.Curve("curve", "Curve"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val (r, g, b) = channelLuts(values.choice("channel"), AdjustMath.curveLut(values.curve("curve")))
        return AdjustMath.applyRgbLut(src, ctx, r, g, b)
    }
}

/**
 * Input black / gamma / white and output black / white levels on the composite or one channel.
 * "Auto" first stretches every channel between its 0.1 % and 99.9 % percentiles (fixing low
 * contrast and color casts); the manual levels are applied on top.
 */
class LevelsFilter : Filter("adjust.levels", "Level Adjustment", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Choice("channel", "Channel", CHANNELS, 0),
        FilterParam.Slider("inBlack", "Input black", 0f, 255f, 0f, 1f),
        FilterParam.Slider("gamma", "Midtones (gamma)", 0.1f, 5f, 1f, 0.01f),
        FilterParam.Slider("inWhite", "Input white", 0f, 255f, 255f, 1f),
        FilterParam.Slider("outBlack", "Output black", 0f, 255f, 0f, 1f),
        FilterParam.Slider("outWhite", "Output white", 0f, 255f, 255f, 1f),
        FilterParam.Toggle("auto", "Auto levels", false),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val manual = AdjustMath.levelsLut(
            values.float("inBlack"), values.float("inWhite"), values.float("gamma"),
            values.float("outBlack"), values.float("outWhite"),
        )
        val (mr, mg, mb) = channelLuts(values.choice("channel"), manual)
        if (!values.bool("auto")) return AdjustMath.applyRgbLut(src, ctx, mr, mg, mb)
        val auto = autoLuts(src, ctx)
        return AdjustMath.applyRgbLut(src, ctx, AdjustMath.compose(mr, auto[0]), AdjustMath.compose(mg, auto[1]), AdjustMath.compose(mb, auto[2]))
    }

    companion object {
        /** Per-channel contrast stretch from the histogram of visible pixels (0.1 % clipped each end). */
        fun autoLuts(src: PixelBuffer, ctx: FilterContext): Array<IntArray> {
            val hist = Array(3) { IntArray(256) }
            val hr = hist[0]; val hg = hist[1]; val hb = hist[2]
            var count = 0L
            val p = src.pixels
            val w = src.width
            for (y in 0 until src.height) {
                if ((y and 63) == 0) ctx.checkCancelled()
                val row = y * w
                for (i in row until row + w) {
                    val c = p[i]
                    if (c ushr 24 == 0) continue
                    hr[(c shr 16) and 0xFF]++; hg[(c shr 8) and 0xFF]++; hb[c and 0xFF]++
                    count++
                }
            }
            if (count == 0L) return Array(3) { AdjustMath.identityLut() }
            val clip = (count * 0.001).toLong()
            return Array(3) { ch ->
                val hst = hist[ch]
                var lo = 0; var acc = 0L
                while (lo < 255) { acc += hst[lo]; if (acc > clip) break; lo++ }
                var hi = 255; acc = 0L
                while (hi > 0) { acc += hst[hi]; if (acc > clip) break; hi-- }
                if (hi <= lo) AdjustMath.identityLut() else AdjustMath.levelsLut(lo.toFloat(), hi.toFloat(), 1f, 0f, 255f)
            }
        }
    }
}

/** Quantizes every channel into [levels] equal bands. */
class PosterizeFilter : Filter("adjust.posterize", "Posterize", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("levels", "Levels", 2f, 64f, 4f, 1f),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer =
        AdjustMath.applyRgbLut(src, ctx, lut(values.int("levels")))

    companion object {
        fun lut(levels: Int): IntArray {
            val n = levels.coerceIn(2, 256)
            return IntArray(256) { v ->
                val bin = min(n - 1, v * n / 256)
                (bin * 255f / (n - 1)).roundToInt()
            }
        }
    }
}

/** Negative image; [amount] blends between the original (0 %) and full inversion (100 %). */
class InvertFilter : Filter("adjust.invert", "Invert Color", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("amount", "Amount", 0f, 100f, 100f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer =
        AdjustMath.applyRgbLut(src, ctx, lut(values))

    /**
     * The same mapping as [apply] per pixel (v1.5 F2: the first non-identity live effect for
     * adjustment layers): fully transparent pixels are left alone, alpha is kept.
     */
    override fun pixelMapper(values: FilterValues): PixelMapper {
        val lut = lut(values)
        return PixelMapper { px, from, until ->
            for (i in from until until) {
                val c = px[i]
                if (c ushr 24 == 0) continue
                px[i] = (c and 0xFF000000.toInt()) or (lut[(c shr 16) and 0xFF] shl 16) or (lut[(c shr 8) and 0xFF] shl 8) or lut[c and 0xFF]
            }
        }
    }

    private fun lut(values: FilterValues): IntArray {
        val s = (values.float("amount") / 100f).coerceIn(0f, 1f)
        return IntArray(256) { v -> ColorUtils.clamp255(v + (255 - 2 * v) * s) }
    }
}

/** Converts to shades of gray; [amount] < 100 % leaves some of the original color. */
class GrayscaleFilter : Filter("adjust.grayscale", "Grayscale", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Choice("method", "Method", listOf("Luminance", "Average", "Lightness"), 0),
        FilterParam.Slider("amount", "Amount", 0f, 100f, 100f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val amount = (values.float("amount") / 100f).coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()
        val method = values.choice("method")
        val k = (amount * 256f).roundToInt()
        return FilterMath.mapPixels(src, ctx) { c ->
            if (c ushr 24 == 0) c else {
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val v = when (method) {
                    1 -> (r + g + b + 1) / 3
                    2 -> (max(r, max(g, b)) + min(r, min(g, b)) + 1) / 2
                    else -> ColorUtils.luminance(r, g, b)
                }
                (c and 0xFF000000.toInt()) or
                    ((r + (((v - r) * k) shr 8)) shl 16) or
                    ((g + (((v - g) * k) shr 8)) shl 8) or
                    (b + (((v - b) * k) shr 8))
            }
        }
    }
}

/**
 * Binarizes by luminance: darker than the threshold becomes black, the rest white (alpha kept).
 * Optional smoothing blurs the luminance first (alpha-weighted, so transparent areas don't leak
 * in) for rounder contours, and anti-aliasing turns the hard cut into a ~1 px soft edge using the
 * local luminance gradient.
 */
class BlackWhiteFilter : Filter("adjust.black_white", "Black & White", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("threshold", "Threshold", 0f, 100f, 30f, 1f, "%"),
        FilterParam.Slider("smoothing", "Smoothing", 0f, 10f, 0f, 0.1f, pixels = true),
        FilterParam.Toggle("antialias", "Anti-aliasing", false),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val t = (values.float("threshold") / 100f).coerceIn(0f, 1f)
        val sigma = ctx.px(values.float("smoothing").coerceAtLeast(0f))
        val aa = values.bool("antialias")
        val black = 0xFF000000.toInt(); val white = -1
        if (sigma < 0.3f && !aa) {
            val t255 = t * 255f
            return FilterMath.mapPixels(src, ctx) { c ->
                if (c ushr 24 == 0) c else (c and black) or ((if (AdjustMath.luma(c) < t255) black else white) and 0xFFFFFF)
            }
        }
        val w = src.width; val h = src.height; val sp = src.pixels
        val lum = FloatArray(src.size) { AdjustMath.luma(sp[it]) / 255f }
        if (sigma >= 0.3f) {
            val weight = FloatArray(src.size) { (sp[it] ushr 24) / 255f }
            AdjustMath.weightedBlurInPlace(lum, weight, w, h, sigma, ctx)
        }
        val out = PixelBuffer(w, h)
        val d = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val row = y * w
                val up = max(0, y - 1) * w; val down = min(h - 1, y + 1) * w
                for (x in 0 until w) {
                    val i = row + x
                    val a = sp[i] ushr 24
                    if (a == 0) { d[i] = sp[i]; continue }
                    val v = lum[i]
                    val cov = when {
                        t <= 0f -> 1f
                        t >= 1f -> if (v >= 1f) 1f else 0f
                        !aa -> if (v < t) 0f else 1f
                        else -> {
                            val gx = (lum[row + min(w - 1, x + 1)] - lum[row + max(0, x - 1)]) * 0.5f
                            val gy = (lum[down + x] - lum[up + x]) * 0.5f
                            val g = sqrt(gx * gx + gy * gy)
                            ((v - t) / max(g, 1e-3f) + 0.5f).coerceIn(0f, 1f)
                        }
                    }
                    d[i] = ColorUtils.gray((cov * 255f + 0.5f).toInt(), a)
                }
            }
        }
        return out
    }
}
