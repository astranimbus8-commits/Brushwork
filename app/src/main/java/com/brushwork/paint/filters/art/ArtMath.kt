package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Small helpers shared by the art filters (pure Kotlin, thread-safe).
 *
 * Angle convention used by every art filter: 0° points to +x (right) and positive angles turn
 * clockwise on screen (image y grows downwards), so 90° points down.
 */
internal object ArtMath {

    /** Unit vector for [degrees] in image space (see the angle convention above). */
    fun unitX(degrees: Float): Float = cos(Math.toRadians(degrees.toDouble())).toFloat()
    fun unitY(degrees: Float): Float = sin(Math.toRadians(degrees.toDouble())).toFloat()

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun clampInt(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    /** Rec.601 luma of 0..255 channels as a float 0..255. */
    fun luma(r: Int, g: Int, b: Int): Float = r * 0.299f + g * 0.587f + b * 0.114f

    /** Signed uniform random in [-1, 1) from the integer hash. */
    fun signedHash(x: Int, y: Int, seed: Int): Float = FilterMath.hash01(x, y, seed) * 2f - 1f

    /**
     * Approximately standard-normal random value from four hashes (Irwin-Hall, variance 1).
     * Cheap and deterministic; tails are limited to about ±3.5.
     */
    fun gaussHash(x: Int, y: Int, seed: Int): Float {
        val s = FilterMath.hash01(x, y, seed) + FilterMath.hash01(x, y, seed + 7919) +
            FilterMath.hash01(x, y, seed + 15887) + FilterMath.hash01(x, y, seed + 23831)
        return (s - 2f) * 1.7320508f
    }

    /**
     * The six channel orders offered by the chromatic-aberration filters. For each entry the first
     * channel moves forward, the second stays and the third moves backward.
     */
    val colorOrderNames = listOf("RGB", "RBG", "GRB", "GBR", "BRG", "BGR")

    /** Direction factors (+1 forward, 0 centered, -1 backward) for R, G and B of [order]. */
    fun colorOrderFactors(order: Int): IntArray {
        val name = colorOrderNames[clampInt(order, 0, colorOrderNames.lastIndex)]
        val k = IntArray(3)
        for ((i, ch) in name.withIndex()) {
            val factor = 1 - i // first +1, second 0, third -1
            when (ch) { 'R' -> k[0] = factor; 'G' -> k[1] = factor; else -> k[2] = factor }
        }
        return k
    }

    /**
     * In-place separable box blur of a float plane with integer radius [r] (edge clamped).
     * Uses one row/column scratch buffer per worker instead of full-size temporaries.
     */
    fun boxBlurInPlace(plane: FloatArray, w: Int, h: Int, r: Int, ctx: FilterContext? = null) {
        if (r <= 0 || plane.isEmpty()) return
        val norm = 1f / (2 * r + 1)
        Parallel.forRows(h) { y0, y1 ->
            ctx?.checkCancelled()
            val line = FloatArray(w)
            for (y in y0 until y1) {
                val off = y * w
                System.arraycopy(plane, off, line, 0, w)
                boxLine(line, w, r, norm, plane, off, 1)
            }
        }
        Parallel.forRange(w, 4) { x0, x1 ->
            ctx?.checkCancelled()
            val line = FloatArray(h)
            for (x in x0 until x1) {
                for (y in 0 until h) line[y] = plane[y * w + x]
                boxLine(line, h, r, norm, plane, x, w)
            }
        }
    }

    private fun boxLine(line: FloatArray, n: Int, r: Int, norm: Float, dst: FloatArray, off: Int, stride: Int) {
        val last = n - 1
        var acc = 0f
        for (k in -r..r) acc += line[min(last, max(0, k))]
        var o = off
        for (i in 0 until n) {
            dst[o] = acc * norm
            o += stride
            acc += line[min(last, i + r + 1)] - line[max(0, i - r)]
        }
    }

    /** In-place gaussian-like blur (three box passes) with standard deviation [sigma] in pixels. */
    fun gaussInPlace(plane: FloatArray, w: Int, h: Int, sigma: Float, ctx: FilterContext? = null) {
        if (sigma < 0.4f) return
        for (b in FilterMath.boxesForGauss(sigma, 3)) boxBlurInPlace(plane, w, h, (b - 1) / 2, ctx)
    }
}
