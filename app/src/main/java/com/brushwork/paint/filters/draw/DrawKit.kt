package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.GradientStop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Blend modes the drawing filters use to put generated content onto the layer's own pixels
 * (W3C compositing formulas on non-premultiplied colors). On a transparent layer every mode
 * except [REPLACE] behaves like [NORMAL].
 */
object DrawBlend {
    val NAMES = listOf(
        "Normal", "Multiply", "Screen", "Overlay", "Soft light", "Hard light", "Color burn",
        "Color dodge", "Add", "Darken", "Lighten", "Difference", "Replace",
    )
    const val NORMAL = 0
    const val MULTIPLY = 1
    const val SCREEN = 2
    const val OVERLAY = 3
    const val SOFT_LIGHT = 4
    const val HARD_LIGHT = 5
    const val COLOR_BURN = 6
    const val COLOR_DODGE = 7
    const val ADD = 8
    const val DARKEN = 9
    const val LIGHTEN = 10
    const val DIFFERENCE = 11
    /** Writes the color (including its alpha) instead of compositing over the layer. */
    const val REPLACE = 12

    fun param(): FilterParam.Choice = FilterParam.Choice("blend", "Blend mode", NAMES, NORMAL)

    fun opacityParam(default: Float = 100f): FilterParam.Slider =
        FilterParam.Slider("opacity", "Opacity", 0f, 100f, default, 1f, "%")

    /**
     * Composites [src] (ARGB, its alpha scaled by [coverage] 0..1) onto [dst] with [mode].
     * Returns [dst] untouched when the coverage is zero.
     */
    fun composite(dst: Int, src: Int, coverage: Float, mode: Int): Int {
        if (!(coverage > 0f)) return dst
        val cov = if (coverage > 1f) 1f else coverage
        if (mode == REPLACE) return lerpPremul(dst, src, cov)
        val sa = (src ushr 24) * (cov / 255f)
        if (sa <= 0f) return dst
        val daI = dst ushr 24
        if (daI == 0) return ColorUtils.withAlpha(src, ColorUtils.clamp255(sa * 255f))
        val da = daI / 255f
        val sr = ((src shr 16) and 0xFF) / 255f
        val sg = ((src shr 8) and 0xFF) / 255f
        val sb = (src and 0xFF) / 255f
        val dr = ((dst shr 16) and 0xFF) / 255f
        val dg = ((dst shr 8) and 0xFF) / 255f
        val db = (dst and 0xFF) / 255f
        val br: Float; val bg: Float; val bb: Float
        if (mode == NORMAL) {
            br = sr; bg = sg; bb = sb
        } else {
            val keep = 1f - da
            br = keep * sr + da * channel(dr, sr, mode)
            bg = keep * sg + da * channel(dg, sg, mode)
            bb = keep * sb + da * channel(db, sb, mode)
        }
        val oa = sa + da * (1f - sa)
        val kd = da * (1f - sa)
        val inv = 255f / oa
        return ColorUtils.argbUnchecked(
            ColorUtils.clamp255(oa * 255f),
            ColorUtils.clamp255((br * sa + dr * kd) * inv),
            ColorUtils.clamp255((bg * sa + dg * kd) * inv),
            ColorUtils.clamp255((bb * sa + db * kd) * inv),
        )
    }

    /** Separable blend function B(backdrop, source) for one channel in 0..1. */
    fun channel(d: Float, s: Float, mode: Int): Float = when (mode) {
        MULTIPLY -> d * s
        SCREEN -> d + s - d * s
        OVERLAY -> if (d <= 0.5f) 2f * d * s else 1f - 2f * (1f - d) * (1f - s)
        HARD_LIGHT -> if (s <= 0.5f) 2f * d * s else 1f - 2f * (1f - d) * (1f - s)
        SOFT_LIGHT -> if (s <= 0.5f) {
            d - (1f - 2f * s) * d * (1f - d)
        } else {
            val dd = if (d <= 0.25f) ((16f * d - 12f) * d + 4f) * d else sqrt(d)
            d + (2f * s - 1f) * (dd - d)
        }
        COLOR_BURN -> when {
            d >= 1f -> 1f
            s <= 0f -> 0f
            else -> 1f - min(1f, (1f - d) / s)
        }
        COLOR_DODGE -> when {
            d <= 0f -> 0f
            s >= 1f -> 1f
            else -> min(1f, d / (1f - s))
        }
        ADD -> min(1f, d + s)
        DARKEN -> min(d, s)
        LIGHTEN -> max(d, s)
        DIFFERENCE -> abs(d - s)
        else -> s
    }

    /** Interpolates two non-premultiplied colors in premultiplied space (no dark fringes). */
    fun lerpPremul(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0
        if (t >= 1f) return c1
        val a0 = (c0 ushr 24).toFloat(); val a1 = (c1 ushr 24).toFloat()
        val a = a0 + (a1 - a0) * t
        if (a <= 0.5f) return 0
        val w0 = a0 * (1f - t); val w1 = a1 * t
        val inv = 1f / (w0 + w1)
        return ColorUtils.argbUnchecked(
            ColorUtils.clamp255(a),
            ColorUtils.clamp255((((c0 shr 16) and 0xFF) * w0 + ((c1 shr 16) and 0xFF) * w1) * inv),
            ColorUtils.clamp255((((c0 shr 8) and 0xFF) * w0 + ((c1 shr 8) and 0xFF) * w1) * inv),
            ColorUtils.clamp255(((c0 and 0xFF) * w0 + (c1 and 0xFF) * w1) * inv),
        )
    }
}

/** Precomputed color lookup of a gradient (stops interpolated in premultiplied space). */
class GradientLut(stops: List<GradientStop>, private val size: Int = 1024) {
    private val colors = IntArray(size)

    init {
        val sorted = stops.filter { it.position.isFinite() }.sortedBy { it.position }
            .ifEmpty { listOf(GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, -1)) }
        for (i in 0 until size) colors[i] = sample(sorted, i / (size - 1f))
    }

    /** Color at [t] in 0..1 (clamped; NaN maps to the start). */
    fun at(t: Float): Int {
        val i = (t * (size - 1) + 0.5f).toInt()
        return colors[if (i < 0) 0 else if (i >= size) size - 1 else i]
    }

    private fun sample(stops: List<GradientStop>, t: Float): Int {
        if (t <= stops.first().position) return stops.first().color
        if (t >= stops.last().position) return stops.last().color
        for (k in 1 until stops.size) {
            val b = stops[k]
            if (t <= b.position) {
                val a = stops[k - 1]
                val span = b.position - a.position
                return if (span <= 1e-6f) b.color else DrawBlend.lerpPremul(a.color, b.color, (t - a.position) / span)
            }
        }
        return stops.last().color
    }
}

/**
 * Periodic 0..1 profiles used by the gradation filters, box-filtered over a pixel footprint so
 * hard wraps (sawtooth seams, high contrast stripes) stay antialiased at any wavelength.
 * The parameter `s` is in "ramp" units: the profile goes 0 -> 1 as s goes 0 -> 1.
 */
object Wave {
    /** Single ramp, clamped outside 0..1. */
    const val CLAMP = 0
    /** Repeating ramp 0 -> 1, 0 -> 1 ... (period 1). */
    const val SAW = 1
    /** Ramp there and back 0 -> 1 -> 0 (period 2). */
    const val MIRROR = 2
    /** Smooth cosine wave 0 -> 1 -> 0 (period 2). */
    const val COSINE = 3

    /** Mean of the profile over [s - fw/2, s + fw/2]. */
    fun profile(shape: Int, s: Double, fw: Double): Double {
        val h = fw * 0.5
        if (!(h > 1e-6)) return point(shape, s)
        val v = when (shape) {
            CLAMP -> (clampIntegral(s + h) - clampIntegral(s - h)) / fw
            SAW -> {
                val u = s - floor(s)
                (sawIntegral(u + h) - sawIntegral(u - h)) / fw
            }
            MIRROR -> {
                val u = s - 2.0 * floor(s * 0.5)
                (triIntegral(u + h) - triIntegral(u - h)) / fw
            }
            else -> 0.5 - 0.5 * cos(PI * s) * sinc(PI * h)
        }
        return if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
    }

    /** Unfiltered profile value. */
    fun point(shape: Int, s: Double): Double = when (shape) {
        CLAMP -> if (s < 0.0) 0.0 else if (s > 1.0) 1.0 else s
        SAW -> s - floor(s)
        MIRROR -> {
            val u = s * 0.5 - floor(s * 0.5)
            1.0 - abs(2.0 * u - 1.0)
        }
        else -> 0.5 - 0.5 * cos(PI * s)
    }

    /**
     * Gain that sharpens the profile around 0.5 for [contrast] 0..1, limited so the steepest
     * transition still spans about one pixel ([fw] = ramp units per pixel).
     */
    fun contrastGain(contrast: Double, shape: Int, fw: Double): Double {
        val k = 1.0 / max(1e-3, 1.0 - contrast)
        val slope = if (shape == COSINE) PI / 2 else 1.0
        val cap = max(1.0, 1.0 / (slope * max(fw, 1e-9)))
        return min(k, cap)
    }

    fun applyContrast(t: Double, gain: Double): Double {
        if (gain <= 1.0) return t
        val v = (t - 0.5) * gain + 0.5
        return if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
    }

    private fun clampIntegral(x: Double): Double = when {
        x <= 0.0 -> 0.0
        x <= 1.0 -> x * x * 0.5
        else -> 0.5 + (x - 1.0)
    }

    private fun sawIntegral(x: Double): Double {
        val f = floor(x)
        val r = x - f
        return f * 0.5 + r * r * 0.5
    }

    private fun triIntegral(x: Double): Double {
        val f = floor(x * 0.5)
        val u = x - 2.0 * f
        return f + if (u <= 1.0) u * u * 0.5 else 2.0 * u - u * u * 0.5 - 1.0
    }

    private fun sinc(z: Double): Double = if (abs(z) < 1e-4) 1.0 else sin(z) / z
}

/** Antialiased coverage helpers for strokes drawn analytically per pixel. */
object Coverage {
    /**
     * Coverage (0..1) of a pixel whose center is [dist] px from the axis of a stroke with half
     * width [halfWidth]. Strokes thinner than a pixel fade out instead of aliasing.
     */
    fun line(halfWidth: Float, dist: Float): Float {
        if (halfWidth >= 0.5f) {
            val v = halfWidth + 0.5f - dist
            return if (v <= 0f) 0f else if (v >= 1f) 1f else v
        }
        if (halfWidth <= 0f) return 0f
        val v = 1f - dist
        return if (v <= 0f) 0f else v * 2f * halfWidth
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).let { if (it < 0f) 0f else if (it > 1f) 1f else it }
        return t * t * (3f - 2f * t)
    }
}

/** Deterministic 2D gradient (Perlin) noise and fractal sums of it. */
object Noise {
    fun hash(x: Int, y: Int, seed: Int): Int {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    /** Uniform random in [0,1) for integer lattice point (x, y). */
    fun rand01(x: Int, y: Int, seed: Int): Float = (hash(x, y, seed) and 0xFFFFFF) / 16777216f

    /** Gradient noise with a unit lattice, roughly in [-1, 1], zero at lattice points. */
    fun perlin(x: Float, y: Float, seed: Int): Float {
        val xf = floor(x); val yf = floor(y)
        val xi = xf.toInt(); val yi = yf.toInt()
        val tx = x - xf; val ty = y - yf
        val u = tx * tx * tx * (tx * (tx * 6f - 15f) + 10f)
        val v = ty * ty * ty * (ty * (ty * 6f - 15f) + 10f)
        val n00 = grad(hash(xi, yi, seed), tx, ty)
        val n10 = grad(hash(xi + 1, yi, seed), tx - 1f, ty)
        val n01 = grad(hash(xi, yi + 1, seed), tx, ty - 1f)
        val n11 = grad(hash(xi + 1, yi + 1, seed), tx - 1f, ty - 1f)
        val a = n00 + (n10 - n00) * u
        val b = n01 + (n11 - n01) * u
        return a + (b - a) * v
    }

    private fun grad(h: Int, x: Float, y: Float): Float = when ((h ushr 8) and 7) {
        0 -> x + y
        1 -> x - y
        2 -> -x + y
        3 -> -x - y
        4 -> 1.4142f * x
        5 -> -1.4142f * x
        6 -> 1.4142f * y
        else -> -1.4142f * y
    }

    const val PLAIN = 0
    const val BILLOW = 1
    const val RIDGED = 2

    /**
     * Fractal sum over octaves whose period (pixels) goes from [basePeriod] down to
     * [minPeriod]; octaves finer than [skipBelow] px are skipped but still counted in the
     * normalization so a downscaled preview keeps the same contrast. PLAIN is roughly in
     * [-0.7, 0.7]; BILLOW and RIDGED in [0, 1].
     */
    fun fbm(
        x: Float, y: Float, basePeriod: Float, minPeriod: Float, persistence: Float, seed: Int,
        mode: Int = PLAIN, skipBelow: Float = 0f, maxOctaves: Int = 14,
    ): Float {
        var period = max(1e-3f, basePeriod)
        var amp = 1f
        var sum = 0f
        var norm = 0f
        var o = 0
        while (o < maxOctaves && (o == 0 || period >= minPeriod)) {
            norm += amp
            if (period >= skipBelow || o == 0) {
                val n = perlin(x / period + o * 17.13f, y / period - o * 31.71f, seed + o * 1013)
                sum += amp * when (mode) {
                    BILLOW -> abs(n)
                    RIDGED -> { val r = 1f - abs(n); r * r }
                    else -> n
                }
            } else if (mode == RIDGED) {
                sum += amp * 0.45f // mean of the skipped sub-pixel octave
            } else if (mode == BILLOW) {
                sum += amp * 0.3f
            }
            amp *= persistence
            period *= 0.5f
            o++
        }
        return if (norm > 0f) sum / norm else 0f
    }
}
