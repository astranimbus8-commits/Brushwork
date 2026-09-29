package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Averages bilinear samples of a NON-premultiplied ARGB image in premultiplied space, so
 * transparent pixels never bleed dark fringes. Create one per worker chunk (not thread-safe);
 * call [reset], add samples, then read [result].
 */
internal class SampleAccumulator(private val px: IntArray, private val w: Int, private val h: Int) {
    private val maxX = (w - 1).toFloat()
    private val maxY = (h - 1).toFloat()
    private var sa = 0f
    private var sr = 0f
    private var sg = 0f
    private var sb = 0f
    private var count = 0

    fun reset() {
        sa = 0f; sr = 0f; sg = 0f; sb = 0f; count = 0
    }

    /**
     * Adds the bilinear sample at ([fx], [fy]) (continuous coordinates, pixel centres at +0.5).
     * Points outside the image are skipped, so edges keep their opacity instead of fading.
     */
    fun addInside(fx: Float, fy: Float) {
        if (fx >= 0f && fy >= 0f && fx <= w && fy <= h) addClamped(fx, fy) // NaN fails every test
    }

    /** Adds the bilinear sample at ([fx], [fy]) with the position clamped to the image. */
    fun addClamped(fx: Float, fy: Float) {
        var x = fx - 0.5f
        var y = fy - 0.5f
        if (!(x > 0f)) x = 0f else if (x > maxX) x = maxX
        if (!(y > 0f)) y = 0f else if (y > maxY) y = maxY
        val x0 = x.toInt()
        val y0 = y.toInt()
        val tx = x - x0
        val ty = y - y0
        val x1 = if (x0 < w - 1) x0 + 1 else x0
        val r0 = y0 * w
        val r1 = if (y0 < h - 1) r0 + w else r0
        tap(px[r0 + x0], (1f - tx) * (1f - ty))
        tap(px[r0 + x1], tx * (1f - ty))
        tap(px[r1 + x0], (1f - tx) * ty)
        tap(px[r1 + x1], tx * ty)
        count++
    }

    private fun tap(c: Int, weight: Float) {
        val a = (c ushr 24) * weight
        if (a <= 0f) return
        sa += a
        sr += ((c shr 16) and 0xFF) * a
        sg += ((c shr 8) and 0xFF) * a
        sb += (c and 0xFF) * a
    }

    /** Mean of the added samples as NON-premultiplied ARGB, or [fallback] when nothing was added. */
    fun result(fallback: Int): Int {
        if (count == 0) return fallback
        val alpha = (sa / count + 0.5f).toInt()
        if (alpha <= 0) return 0
        val inv = 1f / sa
        val r = min(255, (sr * inv + 0.5f).toInt())
        val g = min(255, (sg * inv + 0.5f).toInt())
        val b = min(255, (sb * inv + 0.5f).toInt())
        return (min(255, alpha) shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Long, smooth 1-D blurs (motion, zoom, spin) as a cascade of cheap resampling passes.
 *
 * A blur that averages M samples along a family of commuting transforms T(t) (translations,
 * rotations or scalings about one centre) equals P passes that each average only [TAPS] samples,
 * pass j stepping TAPS^j times further than pass 0: TAPS^P evenly spaced samples for TAPS*P reads
 * per pixel. A 1000 px streak costs 20 bilinear reads per pixel instead of 1000.
 */
internal object Progressive {
    const val TAPS = 4
    private const val MAX_PASSES = 7

    /** Number of passes giving at least [samples] effective samples (1..[MAX_PASSES]). */
    fun passesFor(samples: Float): Int {
        if (!(samples > TAPS)) return 1
        val p = ceil(ln(samples.toDouble()) / ln(TAPS.toDouble()) - 1e-9).toInt()
        return p.coerceIn(1, MAX_PASSES)
    }

    /** TAPS^passes: the effective number of evenly spaced samples of a cascade. */
    fun effectiveSamples(passes: Int): Int {
        var m = 1
        repeat(passes) { m *= TAPS }
        return m
    }

    /**
     * Parameter values for pass [pass]: `offset + i * step * TAPS^pass` for i in 0 until TAPS,
     * where the [offset] is applied in pass 0 only. Summed over all passes this enumerates
     * `offset + m * step` for m in 0 until TAPS^passes.
     */
    fun passParams(pass: Int, offset: Double, step: Double): DoubleArray {
        var stride = step
        repeat(pass) { stride *= TAPS }
        val base = if (pass == 0) offset else 0.0
        return DoubleArray(TAPS) { base + it * stride }
    }

    /**
     * Runs [passes] passes, ping-ponging between two scratch buffers. [body] fills `output` from
     * `input` for one pass (and must check cancellation). Returns a new buffer, never [src].
     */
    inline fun run(
        src: PixelBuffer,
        passes: Int,
        ctx: FilterContext,
        body: (pass: Int, input: PixelBuffer, output: IntArray) -> Unit,
    ): PixelBuffer {
        val total = max(1, passes)
        var input = src
        var bufA: PixelBuffer? = null
        var bufB: PixelBuffer? = null
        for (j in 0 until total) {
            val out = if (j % 2 == 0) {
                bufA ?: PixelBuffer(src.width, src.height).also { bufA = it }
            } else {
                bufB ?: PixelBuffer(src.width, src.height).also { bufB = it }
            }
            body(j, input, out.pixels)
            input = out
            ctx.progress((j + 1f) / total)
        }
        return input
    }
}
