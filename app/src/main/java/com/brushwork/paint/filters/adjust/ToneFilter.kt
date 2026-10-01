package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.PixelMapper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightroom-style tone controls (v1.5 §4.8): Exposure (EV), Contrast, Highlights, Shadows,
 * Whites and Blacks. First in Color Adjustment and the default effect of adjustment layers.
 *
 * Every pixel is worked on in linear light: exposure scales it, a soft shoulder keeps brightened
 * highlights from clipping, and the other five sliders shape a monotone curve applied to the
 * pixel's LUMINANCE. The color is then scaled by the luminance ratio, so hues stay put (a
 * per-channel curve would shift skin and saturated colors); a color pushed past white is
 * desaturated toward its new luminance instead of clipping channel by channel. Alpha is never
 * changed and fully transparent pixels are left alone. [apply] and [pixelMapper] run the same
 * mapper ([ToneMath.mapper]), so an adjustment layer shows exactly what applying the filter gives.
 */
class ToneFilter : Filter(ID, "Tone", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider(EXPOSURE, "Exposure", -5f, 5f, 0f, 0.01f, suffix = "EV"),
        FilterParam.Slider(CONTRAST, "Contrast", -100f, 100f, 0f, 1f),
        FilterParam.Slider(HIGHLIGHTS, "Highlights", -100f, 100f, 0f, 1f),
        FilterParam.Slider(SHADOWS, "Shadows", -100f, 100f, 0f, 1f),
        FilterParam.Slider(WHITES, "Whites", -100f, 100f, 0f, 1f),
        FilterParam.Slider(BLACKS, "Blacks", -100f, 100f, 0f, 1f),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val m = ToneMath.mapper(ToneSettings.of(values)) ?: return src.copy()
        return AdjustMath.applyMapper(src, ctx, m)
    }

    override fun pixelMapper(values: FilterValues): PixelMapper = ToneMath.mapper(ToneSettings.of(values)) ?: AdjustMath.IDENTITY_MAPPER

    companion object {
        const val ID = "adjust.tone"
        const val EXPOSURE = "exposure"
        const val CONTRAST = "contrast"
        const val HIGHLIGHTS = "highlights"
        const val SHADOWS = "shadows"
        const val WHITES = "whites"
        const val BLACKS = "blacks"
    }
}

/** Tone's slider values: [exposure] in EV (−5..5), the others −100..100 (sanitized). */
data class ToneSettings(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
) {
    /** True when the curve on luminance does something (any slider but Exposure moved). */
    val shapesTone: Boolean get() = contrast != 0f || highlights != 0f || shadows != 0f || whites != 0f || blacks != 0f

    /** True when nothing changes at all (the defaults). */
    val isIdentity: Boolean get() = exposure == 0f && !shapesTone

    companion object {
        fun of(v: FilterValues): ToneSettings = ToneSettings(
            exposure = clean(v.float(ToneFilter.EXPOSURE), 5f),
            contrast = clean(v.float(ToneFilter.CONTRAST), 100f),
            highlights = clean(v.float(ToneFilter.HIGHLIGHTS), 100f),
            shadows = clean(v.float(ToneFilter.SHADOWS), 100f),
            whites = clean(v.float(ToneFilter.WHITES), 100f),
            blacks = clean(v.float(ToneFilter.BLACKS), 100f),
        )

        private fun clean(x: Float, limit: Float): Float = if (x.isNaN()) 0f else x.coerceIn(-limit, limit)
    }
}

/**
 * The math of [ToneFilter] (pure Kotlin, thread-safe): sRGB transfer tables, the exposure
 * shoulder, the tone curve and the per-pixel mapper.
 */
object ToneMath {
    /** Entries (minus one) of the transfer tables and of the tone curve over 0..1. */
    const val CURVE_SIZE = 4096

    /** Luminance at which the exposure shoulder starts (linear light). */
    const val SHOULDER_START = 0.8f

    // Rec. 709 / sRGB primaries.
    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f

    /** 8-bit sRGB code value -> linear light. */
    val TO_LINEAR: FloatArray = FloatArray(256) { srgbToLinear(it / 255.0).toFloat() }

    fun srgbToLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    fun linearToSrgb(x: Double): Double = if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1.0 / 2.4) - 0.055

    /** Linear interpolation in a table of evenly spaced samples over 0..1 ([x] is clamped). */
    fun lookup(table: FloatArray, x: Float): Float {
        val last = table.size - 1
        if (!(x > 0f)) return table[0]
        if (x >= 1f) return table[last]
        val f = x * last
        val i = f.toInt()
        val t = f - i
        val a = table[i]
        return a + (table[i + 1] - a) * t
    }

    // ---------------------------------------------------------------- exposure shoulder

    /**
     * Width s of the shoulder for [ev] stops (+∞ for ev ≤ 0: no shoulder). The shoulder maps
     * luminance L above [SHOULDER_START] to 0.8 + s·(1 − e^−(L − 0.8)/s): it starts with slope 1
     * (no kink) and s is chosen so that the brightest possible input, white × 2^ev, lands exactly
     * on white. For ev ≥ 2 this is the design's 0.8 + 0.2·(1 − e^−(L − 0.8)/0.2) (s = 0.2 within
     * 1e-3); for small ev the shoulder fades into the identity, so nudging Exposure above 0 never
     * dims what was already white.
     */
    fun shoulderWidth(ev: Float): Float {
        if (!(ev > 0f)) return Float.POSITIVE_INFINITY
        val m = 2.0.pow(ev.toDouble())
        val span = m - SHOULDER_START
        // s(1 − e^−span/s) = 1 − 0.8 with q = span / s:  q = c·(1 − e^−q), c = span / 0.2 > 1.
        val c = span / (1.0 - SHOULDER_START)
        if (c <= 1.0 + 1e-9) return Float.POSITIVE_INFINITY
        // Newton from the right of the root: f is convex there, so it converges monotonically.
        var q = c
        for (k in 0 until 200) {
            val e = exp(-q)
            val f = q - c * (1.0 - e)
            val fp = 1.0 - c * e
            if (fp <= 1e-12) break
            val next = q - f / fp
            if (!(next > 0.0)) break
            if (abs(next - q) < 1e-12 * max(1.0, q)) { q = next; break }
            q = next
        }
        return (span / q).toFloat()
    }

    /** The shoulder of width [s] (see [shoulderWidth]) applied to luminance [y]. */
    fun shoulder(y: Float, s: Float): Float {
        if (y <= SHOULDER_START || s == Float.POSITIVE_INFINITY) return y
        return SHOULDER_START - s * expm1(-(y - SHOULDER_START) / s)
    }

    // ---------------------------------------------------------------- tone curve

    private const val WB_STRENGTH = 0.12f
    private const val HS_STRENGTH = 0.18f
    private const val CONTRAST_STRENGTH = 0.12f

    /** Shadows weight: sin²(πv / 0.6) on 0..0.6 (peak at 0.3), 0 elsewhere. */
    fun shadowsWeight(v: Float): Float {
        if (v <= 0f || v >= 0.6f) return 0f
        val s = sin(PI * v / 0.6).toFloat()
        return s * s
    }

    /** Highlights weight: sin²(π(v − 0.4) / 0.6) on 0.4..1 (peak at 0.7), 0 elsewhere. */
    fun highlightsWeight(v: Float): Float {
        if (v <= 0.4f || v >= 1f) return 0f
        val s = sin(PI * (v - 0.4f) / 0.6).toFloat()
        return s * s
    }

    /**
     * The tone curve at sRGB-encoded luminance [v] before the monotone pass: whites and blacks
     * move the ends, then highlights and shadows the upper and lower middle, then contrast bends
     * around mid-gray (each step clamped to 0..1).
     */
    fun curveValue(v0: Float, s: ToneSettings): Float {
        var v = v0
        v += (s.whites / 100f) * WB_STRENGTH * AdjustMath.smoothstep(0.65f, 1f, v) +
            (s.blacks / 100f) * WB_STRENGTH * (1f - AdjustMath.smoothstep(0f, 0.35f, v))
        v = v.coerceIn(0f, 1f)
        v += (s.shadows / 100f) * HS_STRENGTH * shadowsWeight(v) + (s.highlights / 100f) * HS_STRENGTH * highlightsWeight(v)
        v = v.coerceIn(0f, 1f)
        v -= (s.contrast / 100f) * CONTRAST_STRENGTH * sin(2.0 * PI * v).toFloat()
        return v.coerceIn(0f, 1f)
    }

    /**
     * The tone curve as [CURVE_SIZE] + 1 samples over v = 0..1 (read with [lookup]), clamped and
     * made monotone (a running maximum), so tones never swap order.
     */
    fun toneCurve(s: ToneSettings): FloatArray {
        val out = FloatArray(CURVE_SIZE + 1)
        var prev = 0f
        for (i in 0..CURVE_SIZE) {
            var v = curveValue(i.toFloat() / CURVE_SIZE, s)
            if (i > 0 && v < prev) v = prev
            out[i] = v
            prev = v
        }
        return out
    }

    // ---------------------------------------------------------------- per-pixel mapping

    /**
     * The new luminance (linear light, 0..1) of a pixel whose luminance is [y0], following the
     * design's steps exactly: exposure gain, the shoulder (only when brightening), then the tone
     * curve on the sRGB-encoded luminance.
     */
    fun finalLuminance(y0: Float, s: ToneSettings): Float = luminanceFunction(s)(y0)

    /** [finalLuminance] for [s], with its shoulder and curve prepared once. */
    private fun luminanceFunction(s: ToneSettings): (Float) -> Float {
        val gain = 2f.pow(s.exposure)
        val brighten = s.exposure > 0f
        val width = shoulderWidth(s.exposure)
        val curve = if (s.shapesTone) toneCurve(s) else null
        return { y0 ->
            var y = y0 * gain
            if (brighten) y = shoulder(y, width)
            if (curve != null) y = srgbToLinear(lookup(curve, linearToSrgb(y.coerceIn(0f, 1f).toDouble()).toFloat()).toDouble()).toFloat()
            y
        }
    }

    /** Entries of the 8-bit output encoder (linear light 0..1 -> sRGB code). */
    private const val ENCODE8_SIZE = 1 shl 16

    /** Linear light -> 8-bit sRGB code, sampled finely enough that no read is off by more than rounding. */
    private val ENCODE8: ByteArray = ByteArray(ENCODE8_SIZE) { i ->
        (linearToSrgb(i.toDouble() / (ENCODE8_SIZE - 1)) * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
    }

    /** The mapper for [s]; null when nothing changes (the defaults are the exact identity). */
    fun mapper(s: ToneSettings): PixelMapper? = if (s.isIdentity) null else ToneMapper(s)

    /**
     * Exposure, shoulder and curve depend only on the luminance, so they are folded into ONE
     * table: the new luminance over √(old luminance) (√ spends the samples where the eye needs
     * them, in the shadows). Per pixel that is a square root, one interpolated read and the ratio.
     */
    private class ToneMapper(s: ToneSettings) : PixelMapper {
        private val table: FloatArray = run {
            val f = luminanceFunction(s)
            FloatArray(CURVE_SIZE + 1) { i -> val u = i.toFloat() / CURVE_SIZE; f(u * u) }
        }

        override fun map(px: IntArray, from: Int, until: Int) {
            val lin = TO_LINEAR
            val table = table
            val black = table[0]
            val last = table.size - 1
            for (i in from until until) {
                val c = px[i]
                if (c ushr 24 == 0) continue
                var r = lin[(c shr 16) and 0xFF]
                var g = lin[(c shr 8) and 0xFF]
                var b = lin[c and 0xFF]
                val y0 = KR * r + KG * g + KB * b
                val y: Float
                if (y0 > 1e-9f) {
                    // lookup(table, sqrt(y0)), inlined (y0 ≤ 1 for 8-bit input).
                    val f = sqrt(y0) * last
                    var j = f.toInt()
                    if (j >= last) j = last - 1
                    val a = table[j]
                    y = a + (table[j + 1] - a) * (f - j)
                    // The luminance ratio scales every channel alike: the hue stays.
                    val k = y / y0
                    r *= k; g *= k; b *= k
                } else {
                    // Black has no hue to keep: lifted blacks become gray.
                    y = black
                    r = y; g = y; b = y
                }
                val mx = if (r > g) (if (r > b) r else b) else (if (g > b) g else b)
                if (mx > 1f) {
                    // Past white: desaturate toward the luminance (which is ≤ 1) until it fits.
                    val d = mx - y
                    val t = if (d > 1e-7f) ((1f - y) / d).coerceIn(0f, 1f) else 0f
                    r = y + (r - y) * t
                    g = y + (g - y) * t
                    b = y + (b - y) * t
                }
                px[i] = (c and ALPHA) or (out8(r) shl 16) or (out8(g) shl 8) or out8(b)
            }
        }

        private fun out8(x: Float): Int {
            val k = (x * (ENCODE8_SIZE - 1) + 0.5f).toInt()
            return ENCODE8[if (k < 0) 0 else if (k >= ENCODE8_SIZE) ENCODE8_SIZE - 1 else k].toInt() and 0xFF
        }
    }

    private const val ALPHA = 0xFF000000.toInt()
}
