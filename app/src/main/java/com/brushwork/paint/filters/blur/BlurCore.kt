package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gaussian blur core shared by the blur-category filters.
 *
 * Each axis is blurred with three "extended box" passes (Gwosdek et al., 2011): a box of integer
 * radius r plus a fractional weight on its two end taps, chosen so the variance matches sigma
 * exactly. Unlike plain box approximations the result changes smoothly with sigma (no jumps between
 * box widths, real blur even below 1 px) and costs O(1) per pixel for any radius.
 *
 * Planes are blurred in place and colour channels are processed one at a time, so a 12 MP image
 * needs only two float planes of scratch memory instead of four premultiplied planes plus copies.
 */
internal object BlurCore {
    /** Columns handled together in the vertical pass (transposed into a contiguous strip). */
    private const val STRIP = 32

    /** Elements per task for flat per-pixel loops. */
    const val FLAT_CHUNK = 1 shl 16

    /** One extended-box pass: taps |k| <= [r] weigh 1, taps at |k| = r + 1 weigh [alpha]. */
    class BoxSpec(val r: Int, val alpha: Float) {
        val norm: Double = 1.0 / (2 * r + 1 + 2.0 * alpha)
    }

    /** Box spec whose [passes]-fold repetition has standard deviation [sigma], or null for ~no blur. */
    fun boxSpec(sigma: Float, passes: Int = 3): BoxSpec? {
        if (!(sigma > 0.05f)) return null // also rejects NaN
        val s2 = sigma.toDouble() * sigma / passes
        val r = floor((sqrt(1.0 + 12.0 * s2) - 1.0) / 2.0).toInt().coerceAtLeast(0)
        val alpha = (2 * r + 1) * (s2 - r * (r + 1) / 3.0) / (2.0 * ((r + 1.0) * (r + 1.0) - s2))
        return BoxSpec(r, alpha.toFloat().coerceIn(0f, 1f))
    }

    /** Gaussian-blurs [plane] (w x h, row-major) in place; edges are clamped (replicated). */
    fun blurPlane(plane: FloatArray, w: Int, h: Int, sigmaX: Float, sigmaY: Float, ctx: FilterContext) {
        boxSpec(sigmaX)?.let { if (w > 1) horizontal(plane, w, h, it, ctx) }
        boxSpec(sigmaY)?.let { if (h > 1) vertical(plane, w, h, it, ctx) }
    }

    private fun horizontal(plane: FloatArray, w: Int, h: Int, spec: BoxSpec, ctx: FilterContext) {
        Parallel.forRows(h) { y0, y1 ->
            val a = FloatArray(w)
            val b = FloatArray(w)
            for (y in y0 until y1) {
                if ((y - y0) and 31 == 0) ctx.checkCancelled()
                val off = y * w
                System.arraycopy(plane, off, a, 0, w)
                boxLine(a, 0, b, 0, w, spec)
                boxLine(b, 0, a, 0, w, spec)
                boxLine(a, 0, plane, off, w, spec)
            }
        }
    }

    private fun vertical(plane: FloatArray, w: Int, h: Int, spec: BoxSpec, ctx: FilterContext) {
        Parallel.forRange(w, STRIP) { x0, x1 ->
            val strip = FloatArray(min(STRIP, x1 - x0) * h)
            val a = FloatArray(h)
            val b = FloatArray(h)
            var sx = x0
            while (sx < x1) {
                ctx.checkCancelled()
                val n = min(STRIP, x1 - sx)
                // Gather the columns transposed so each one is contiguous (cache-friendly reads).
                for (y in 0 until h) {
                    val row = y * w + sx
                    for (k in 0 until n) strip[k * h + y] = plane[row + k]
                }
                for (k in 0 until n) {
                    val off = k * h
                    boxLine(strip, off, a, 0, h, spec)
                    boxLine(a, 0, b, 0, h, spec)
                    boxLine(b, 0, strip, off, h, spec)
                }
                for (y in 0 until h) {
                    val row = y * w + sx
                    for (k in 0 until n) plane[row + k] = strip[k * h + y]
                }
                sx += n
            }
        }
    }

    /**
     * One extended-box pass over [n] samples of [s] starting at [so], written to [d] at [dOff]:
     * `d[i] = (sum(s[i-r..i+r]) + alpha * (s[i-r-1] + s[i+r+1])) * norm`, indices clamped to the line.
     * Running sum in double, so the cost is independent of r and drift is negligible.
     */
    private fun boxLine(s: FloatArray, so: Int, d: FloatArray, dOff: Int, n: Int, spec: BoxSpec) {
        val r = spec.r
        val alpha = spec.alpha.toDouble()
        val norm = spec.norm
        val last = n - 1
        var sum = 0.0
        for (k in -r..r) sum += s[so + (if (k < 0) 0 else if (k > last) last else k)]
        for (i in 0 until n) {
            val lo = i - r - 1
            val hi = i + r + 1
            val sLo = s[so + (if (lo < 0) 0 else lo)]
            val sHi = s[so + (if (hi > last) last else hi)]
            d[dOff + i] = ((sum + alpha * (sLo + sHi)) * norm).toFloat()
            val rem = i - r
            sum += sHi - s[so + (if (rem < 0) 0 else rem)]
        }
    }

    /** True if every pixel is fully opaque (lets callers skip the alpha plane entirely). */
    fun isOpaque(src: PixelBuffer): Boolean {
        val p = src.pixels
        for (c in p) if (c ushr 24 != 255) return false
        return true
    }

    /**
     * Alpha-correct Gaussian blur of [src], delivered channel by channel to limit memory.
     *
     * Colours are blurred premultiplied, so transparent pixels never pull dark fringes into edges.
     * [consume] is called for R, G and B in turn (`shift` = 16, 8, 0) with `color[i]` the blurred,
     * UN-premultiplied channel value (0..255; undefined where alpha[i] is ~0) and `alpha` the blurred
     * alpha plane (0..255), or null when [src] is fully opaque (the result then is opaque too).
     * The `color` array is reused between calls.
     */
    fun blurChannels(
        src: PixelBuffer,
        sigmaX: Float,
        sigmaY: Float,
        ctx: FilterContext,
        consume: (shift: Int, color: FloatArray, alpha: FloatArray?) -> Unit,
    ) {
        val w = src.width
        val h = src.height
        val n = src.size
        val px = src.pixels
        val alpha: FloatArray? = if (isOpaque(src)) null else FloatArray(n).also { a ->
            Parallel.forRange(n, FLAT_CHUNK) { i0, i1 -> for (i in i0 until i1) a[i] = (px[i] ushr 24).toFloat() }
            blurPlane(a, w, h, sigmaX, sigmaY, ctx)
        }
        val plane = FloatArray(n)
        for (shift in CHANNEL_SHIFTS) {
            ctx.checkCancelled()
            Parallel.forRange(n, FLAT_CHUNK) { i0, i1 ->
                if (alpha == null) {
                    for (i in i0 until i1) plane[i] = ((px[i] shr shift) and 0xFF).toFloat()
                } else {
                    for (i in i0 until i1) { val c = px[i]; plane[i] = (((c shr shift) and 0xFF) * (c ushr 24)).toFloat() }
                }
            }
            blurPlane(plane, w, h, sigmaX, sigmaY, ctx)
            if (alpha != null) {
                Parallel.forRange(n, FLAT_CHUNK) { i0, i1 ->
                    for (i in i0 until i1) { val a = alpha[i]; plane[i] = if (a > 1e-3f) plane[i] / a else 0f }
                }
            }
            consume(shift, plane, alpha)
        }
    }

    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
}
