package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.GradientStop
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Helpers shared by the color-adjustment filters: lookup tables, tone curves, HSL math and a
 * memory-frugal in-place gaussian blur. Pure Kotlin and thread-safe. [sampleCurve] is the exact
 * curve the Tone Curve filter applies, so a curve editor can draw it.
 */
object AdjustMath {

    private const val ALPHA_MASK = 0xFF000000.toInt()

    /** Rec.601 luma of a packed color's straight RGB, 0..255. */
    fun luma(c: Int): Float = ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f

    fun identityLut(): IntArray = IntArray(256) { it }

    /** 256-entry table from a 0..1 -> 0..1 function. */
    inline fun lut(f: (Float) -> Float): IntArray = IntArray(256) { ColorUtils.clamp255(f(it / 255f) * 255f) }

    /** outer(inner(v)). */
    fun compose(outer: IntArray, inner: IntArray): IntArray = IntArray(256) { outer[inner[it]] }

    fun isIdentity(lut: IntArray): Boolean {
        for (i in 0 until 256) if (lut[i] != i) return false
        return true
    }

    /**
     * Maps R, G, B through per-channel tables. Alpha is kept and fully transparent pixels are
     * copied unchanged (their color is meaningless and should stay stable).
     */
    fun applyRgbLut(src: PixelBuffer, ctx: FilterContext, lr: IntArray, lg: IntArray = lr, lb: IntArray = lr): PixelBuffer {
        if (isIdentity(lr) && isIdentity(lg) && isIdentity(lb)) return src.copy()
        return FilterMath.mapPixels(src, ctx) { c ->
            if (c ushr 24 == 0) c
            else (c and ALPHA_MASK) or (lr[(c shr 16) and 0xFF] shl 16) or (lg[(c shr 8) and 0xFF] shl 8) or lb[c and 0xFF]
        }
    }

    /**
     * Like [FilterMath.mapPixels] but hands [f] two per-thread 3-float scratch arrays (for RGB and
     * HSL math) so the per-pixel code allocates nothing.
     */
    inline fun mapWithScratch(src: PixelBuffer, ctx: FilterContext, crossinline f: (c: Int, rgb: FloatArray, hsl: FloatArray) -> Int): PixelBuffer {
        val out = PixelBuffer(src.width, src.height)
        val s = src.pixels; val d = out.pixels; val w = src.width
        Parallel.forRows(src.height) { y0, y1 ->
            ctx.checkCancelled()
            val rgb = FloatArray(3); val hsl = FloatArray(3)
            for (i in y0 * w until y1 * w) d[i] = f(s[i], rgb, hsl)
        }
        return out
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 == e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---------------------------------------------------------------- tone curves

    /**
     * Samples a monotone cubic (PCHIP / Fritsch–Butland) interpolation through [points] into a
     * 256-entry table. The curve passes exactly through every point, never overshoots between
     * neighbours, holds the end values outside the first/last point and tolerates unsorted,
     * duplicated or out-of-range input.
     */
    fun curveLut(points: List<CurvePoint>): IntArray {
        val f = sampleCurve(points, 256)
        return IntArray(256) { ColorUtils.clamp255(f[it] * 255f) }
    }

    /**
     * [n] evenly spaced samples (x = i / (n - 1); a single sample is taken at x = 0) of the
     * monotone cubic curve through [points], each in 0..1. Returns an empty array for n <= 0.
     */
    fun sampleCurve(points: List<CurvePoint>, n: Int): FloatArray {
        if (n <= 0) return FloatArray(0)
        val pts = points
            .filter { !it.x.isNaN() && !it.y.isNaN() }
            .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.x }
        // Merge points that share an x (keep the last one) so the secants stay finite.
        val xs = ArrayList<Float>(pts.size); val ys = ArrayList<Float>(pts.size)
        for (p in pts) {
            if (xs.isNotEmpty() && p.x - xs.last() < 1e-4f) { ys[ys.lastIndex] = p.y; continue }
            xs += p.x; ys += p.y
        }
        val out = FloatArray(n)
        val step = if (n > 1) 1f / (n - 1) else 0f
        when (xs.size) {
            0 -> { for (i in 0 until n) out[i] = i * step; return out }
            1 -> { out.fill(ys[0]); return out }
        }
        val k = xs.size
        val h = FloatArray(k - 1) { xs[it + 1] - xs[it] }
        val d = FloatArray(k - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val m = FloatArray(k)
        if (k == 2) {
            m[0] = d[0]; m[1] = d[0]
        } else {
            for (i in 1 until k - 1) {
                m[i] = if (d[i - 1] * d[i] <= 0f) 0f else {
                    val w1 = 2f * h[i] + h[i - 1]
                    val w2 = h[i] + 2f * h[i - 1]
                    (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
                }
            }
            m[0] = endSlope(h[0], h[1], d[0], d[1])
            m[k - 1] = endSlope(h[k - 2], h[k - 3], d[k - 2], d[k - 3])
        }
        var seg = 0
        for (i in 0 until n) {
            val x = i * step
            out[i] = when {
                x <= xs[0] -> ys[0]
                x >= xs[k - 1] -> ys[k - 1]
                else -> {
                    while (seg < k - 2 && x > xs[seg + 1]) seg++
                    val hh = h[seg]
                    val t = (x - xs[seg]) / hh
                    val t2 = t * t; val t3 = t2 * t
                    val h00 = 2f * t3 - 3f * t2 + 1f
                    val h10 = t3 - 2f * t2 + t
                    val h01 = -2f * t3 + 3f * t2
                    val h11 = t3 - t2
                    h00 * ys[seg] + h10 * hh * m[seg] + h01 * ys[seg + 1] + h11 * hh * m[seg + 1]
                }
            }.coerceIn(0f, 1f)
        }
        return out
    }

    /** Shape-preserving one-sided three-point slope at a curve end (as in PCHIP). */
    private fun endSlope(h0: Float, h1: Float, d0: Float, d1: Float): Float {
        var s = ((2f * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        if (s * d0 <= 0f) s = 0f
        else if (d0 * d1 <= 0f && abs(s) > abs(3f * d0)) s = 3f * d0
        return s
    }

    // ---------------------------------------------------------------- levels

    /**
     * Photoshop-style levels table. Inputs in 0..255; [gamma] > 1 brightens midtones. If the white
     * point is not above the black point the input stage becomes a hard threshold at [inBlack].
     */
    fun levelsLut(inBlack: Float, inWhite: Float, gamma: Float, outBlack: Float, outWhite: Float): IntArray {
        val g = gamma.coerceIn(0.01f, 100f)
        val inv = 1.0 / g
        return IntArray(256) { i ->
            val v = if (inWhite > inBlack) ((i - inBlack) / (inWhite - inBlack)).coerceIn(0f, 1f)
            else if (i >= inBlack) 1f else 0f
            val gv = if (v <= 0f || v >= 1f || g == 1f) v.toDouble() else Math.pow(v.toDouble(), inv)
            ColorUtils.clamp255(outBlack + gv.toFloat() * (outWhite - outBlack))
        }
    }

    // ---------------------------------------------------------------- gradients

    /**
     * 256 ARGB samples of a gradient (linear interpolation of straight ARGB between stops, end
     * colors held outside the first/last stop).
     */
    fun gradientLut(stops: List<GradientStop>): IntArray {
        val s = stops.filter { !it.position.isNaN() }.sortedBy { it.position }
        if (s.isEmpty()) return IntArray(256) { ColorUtils.gray(it) }
        if (s.size == 1) return IntArray(256) { s[0].color }
        var seg = 0
        return IntArray(256) { i ->
            val x = i / 255f
            when {
                x <= s[0].position -> s[0].color
                x >= s.last().position -> s.last().color
                else -> {
                    while (seg < s.size - 2 && x > s[seg + 1].position) seg++
                    val a = s[seg]; val b = s[seg + 1]
                    val span = b.position - a.position
                    if (span <= 1e-6f) b.color else ColorUtils.lerp(a.color, b.color, (x - a.position) / span)
                }
            }
        }
    }

    // ---------------------------------------------------------------- HSL (all components 0..1)

    /** Straight RGB 0..1 -> HSL written into [out] as (h in [0,1), s, l). */
    fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) * 0.5f
        val d = mx - mn
        if (d <= 1e-6f) { out[0] = 0f; out[1] = 0f; out[2] = l; return }
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        var h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        if (h >= 1f) h -= 1f
        out[0] = h; out[1] = s.coerceIn(0f, 1f); out[2] = l
    }

    /** HSL (h wraps, s and l clamped) -> RGB 0..1 written into [out]. */
    fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        val ss = s.coerceIn(0f, 1f); val ll = l.coerceIn(0f, 1f)
        if (ss <= 0f) { out[0] = ll; out[1] = ll; out[2] = ll; return }
        var hh = h - kotlin.math.floor(h)
        if (hh >= 1f) hh = 0f
        val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
        val p = 2f * ll - q
        out[0] = hueToRgb(p, q, hh + 1f / 3f)
        out[1] = hueToRgb(p, q, hh)
        out[2] = hueToRgb(p, q, hh - 1f / 3f)
    }

    private fun hueToRgb(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** Packs 0..1 floats (clamped) with an alpha byte. */
    fun pack(a: Int, r: Float, g: Float, b: Float): Int =
        (a shl 24) or (ColorUtils.clamp255(r * 255f) shl 16) or (ColorUtils.clamp255(g * 255f) shl 8) or ColorUtils.clamp255(b * 255f)

    /**
     * Hue / saturation / brightness adjustment of one color held in [rgb] (0..1, in place).
     * [hueShift] in turns, [sat] and [light] in -1..1. Negative saturation scales toward gray,
     * positive saturation divides by (1 - sat) so grays stay gray and +1 fully saturates every
     * tinted color. Brightness blends toward white (positive) or black (negative).
     */
    fun adjustHsb(rgb: FloatArray, hsl: FloatArray, hueShift: Float, sat: Float, light: Float) {
        if (hueShift != 0f || sat != 0f) {
            rgbToHsl(rgb[0], rgb[1], rgb[2], hsl)
            val s = hsl[1]
            val s2 = if (sat < 0f) s * (1f + sat) else if (sat > 0f) min(1f, s / max(1e-3f, 1f - sat)) else s
            hslToRgb(hsl[0] + hueShift, s2, hsl[2], rgb)
        }
        if (light > 0f) for (i in 0..2) rgb[i] += (1f - rgb[i]) * light
        else if (light < 0f) for (i in 0..2) rgb[i] *= 1f + light
    }

    // ---------------------------------------------------------------- planes

    /**
     * Gaussian blur of a float plane IN PLACE. Needs only small per-thread scratch buffers (one
     * padded row, or a strip of columns), so 20-megapixel planes don't need extra full-size copies.
     */
    fun gaussianBlurInPlace(plane: FloatArray, w: Int, h: Int, sigma: Float, ctx: FilterContext?) {
        if (sigma < 0.3f || plane.isEmpty()) return
        val kernel = FilterMath.gaussianKernel(sigma)
        val r = kernel.size / 2
        Parallel.forRows(h) { y0, y1 ->
            ctx?.checkCancelled()
            val pad = FloatArray(w + 2 * r)
            for (y in y0 until y1) {
                val row = y * w
                for (i in pad.indices) pad[i] = plane[row + (i - r).coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    var acc = 0f
                    for (k in kernel.indices) acc += pad[x + k] * kernel[k]
                    plane[row + x] = acc
                }
            }
        }
        val strip = 32
        val strips = (w + strip - 1) / strip
        Parallel.forRange(strips, 1) { s0, s1 ->
            val buf = FloatArray((h + 2 * r) * strip)
            val acc = FloatArray(strip)
            for (s in s0 until s1) {
                ctx?.checkCancelled()
                val x0 = s * strip
                val sw = min(strip, w - x0)
                for (yy in 0 until h + 2 * r) {
                    val sy = (yy - r).coerceIn(0, h - 1) * w + x0
                    System.arraycopy(plane, sy, buf, yy * strip, sw)
                }
                for (y in 0 until h) {
                    java.util.Arrays.fill(acc, 0, sw, 0f)
                    for (k in kernel.indices) {
                        val kv = kernel[k]
                        val base = (y + k) * strip
                        for (xi in 0 until sw) acc[xi] += buf[base + xi] * kv
                    }
                    System.arraycopy(acc, 0, plane, y * w + x0, sw)
                }
            }
        }
    }

    /**
     * Blurs [value] weighted by [weight] (e.g. luminance weighted by alpha) so fully transparent
     * pixels don't bleed their meaningless color into visible ones. Both arrays are modified: on
     * return [value] holds the weighted average (0 where no weight reaches a pixel) and [weight]
     * the blurred weights.
     */
    fun weightedBlurInPlace(value: FloatArray, weight: FloatArray, w: Int, h: Int, sigma: Float, ctx: FilterContext?) {
        if (sigma < 0.3f) return
        for (i in value.indices) value[i] *= weight[i]
        gaussianBlurInPlace(value, w, h, sigma, ctx)
        gaussianBlurInPlace(weight, w, h, sigma, ctx)
        for (i in value.indices) {
            val wt = weight[i]
            value[i] = if (wt > 1e-7f) value[i] / wt else 0f
        }
    }
}
