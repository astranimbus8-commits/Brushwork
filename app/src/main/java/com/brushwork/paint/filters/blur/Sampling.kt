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
    private var total = 0f

    fun reset() {
        sa = 0f; sr = 0f; sg = 0f; sb = 0f; total = 0f
    }

    /**
     * Adds the bilinear sample at ([fx], [fy]) (continuous coordinates, pixel centres at +0.5).
     * Positions outside the image are clamped to its edge, so edges never fade out.
     */
    fun addClamped(fx: Float, fy: Float) = addClamped(fx, fy, 1f)

    /** Like [addClamped] with a relative [weight] (> 0) in the average. */
    fun addClamped(fx: Float, fy: Float, weight: Float) {
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
        val wx0 = (1f - tx) * weight
        val wx1 = tx * weight
        tap(px[r0 + x0], wx0 * (1f - ty))
        tap(px[r0 + x1], wx1 * (1f - ty))
        tap(px[r1 + x0], wx0 * ty)
        tap(px[r1 + x1], wx1 * ty)
        total += weight
    }

    private fun tap(c: Int, weight: Float) {
        val a = (c ushr 24) * weight
        if (a <= 0f) return
        sa += a
        sr += ((c shr 16) and 0xFF) * a
        sg += ((c shr 8) and 0xFF) * a
        sb += (c and 0xFF) * a
    }

    /** Weighted mean of the added samples as NON-premultiplied ARGB, or [fallback] when nothing was added. */
    fun result(fallback: Int): Int {
        if (!(total > 0f)) return fallback
        val alpha = (sa / total + 0.5f).toInt()
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
 * per pixel. A 1000 px streak costs 20 bilinear reads per pixel instead of 1000. Per-tap weights
 * compose too: the weight of a composed sample is the product of its taps' weights.
 */
internal object Progressive {
    /** Rows between cancellation checks inside a pass (a pass over a large image takes a while). */
    const val CANCEL_ROWS = 16

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
     * Transform parameters of pass [pass] (of [passes]) for a kernel spanning [lo]..[hi], which
     * must contain 0 (the identity): `base + i * stride` for i in 0 until TAPS, with
     * stride = (hi - lo) * TAPS^pass / (M - 1). Summed over all passes this enumerates
     * `lo + m * (hi - lo) / (M - 1)` for every m in 0 until M = TAPS^passes exactly once.
     *
     * Each pass carries a share of [lo] proportional to its span, so the partial sums visited on the
     * way (the last, coarsest pass is applied first from the output pixel) always stay inside a
     * shrunken copy of the kernel. Were the whole offset put into one pass, intermediate samples
     * would wander past the image edge and leave gaps (ghost copies) in the composed streak.
     */
    fun passParams(pass: Int, passes: Int, lo: Double, hi: Double): DoubleArray {
        val m1 = (effectiveSamples(passes) - 1).toDouble()
        var scale = 1.0
        repeat(pass) { scale *= TAPS }
        val stride = (hi - lo) * scale / m1
        val base = lo * (TAPS - 1) * scale / m1
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
