package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Shared machinery of the two chromatic-aberration filters: every output pixel takes its red, green
 * and blue channel from three different source positions. Samples are bilinear in premultiplied
 * space with the edge pixels extended; the output alpha is the largest of the three sampled
 * alphas and each channel keeps its own coverage (so fringes appear on transparent layers too).
 *
 * v1.8 (item 5): the three positions are the first, middle and last fringe, each with a color
 * ([FringeColors]). [recombine] is v1.7's code, the fast path (each channel from one fringe);
 * [recombineMix] is the general path (each channel a swatch-weighted mean of the fringes).
 */
internal object ChannelShift {

    /**
     * Runs the per-pixel channel recombination. [positions] maps (x, y) to the three sample
     * positions (buffer pixels, pixel centers at +0.5) written into `pos[0..5]` as
     * rx, ry, gx, gy, bx, by.
     */
    inline fun recombine(
        src: PixelBuffer,
        ctx: FilterContext,
        crossinline positions: (x: Int, y: Int, pos: FloatArray) -> Unit,
    ): PixelBuffer {
        val w = src.width; val h = src.height
        val out = PixelBuffer(w, h)
        val s = src.pixels; val d = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val pos = FloatArray(6)
            val acc = FloatArray(2)
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    positions(x, y, pos)
                    sample(s, w, h, pos[0], pos[1], 16, acc); val pr = acc[0]; val ar = acc[1]
                    sample(s, w, h, pos[2], pos[3], 8, acc); val pg = acc[0]; val ag = acc[1]
                    sample(s, w, h, pos[4], pos[5], 0, acc); val pb = acc[0]; val ab = acc[1]
                    val a = max(ar, max(ag, ab))
                    d[row + x] = if (a < 0.5f) 0 else {
                        val inv = 1f / a
                        (clamp(a) shl 24) or (clamp(pr * inv) shl 16) or (clamp(pg * inv) shl 8) or clamp(pb * inv)
                    }
                }
            }
        }
        return out
    }

    /**
     * v1.8 general path: [positions] writes the first, middle and last fringe's sample positions
     * into `pos[0..5]`. Each sample [Mix.needed] is read once for its alpha and its three
     * coverage-weighted channels; channel c is `Σ_i w[3i + c] · P_i,c` over the output alpha, the
     * largest alpha among the [Mix.colored] samples. With [mix] one-hot this equals [recombine]
     * byte for byte (the weights are 1 and 0, and the same samples give the alpha).
     */
    inline fun recombineMix(
        src: PixelBuffer,
        ctx: FilterContext,
        mix: FringeColors.Mix,
        crossinline positions: (x: Int, y: Int, pos: FloatArray) -> Unit,
    ): PixelBuffer {
        val w = src.width; val h = src.height
        val out = PixelBuffer(w, h)
        val s = src.pixels; val d = out.pixels
        val m = mix.w
        val w0r = m[0]; val w0g = m[1]; val w0b = m[2]
        val w1r = m[3]; val w1g = m[4]; val w1b = m[5]
        val w2r = m[6]; val w2g = m[7]; val w2b = m[8]
        val n0 = mix.needed[0]; val n1 = mix.needed[1]; val n2 = mix.needed[2]
        val c0 = mix.colored[0]; val c1 = mix.colored[1]; val c2 = mix.colored[2]
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val pos = FloatArray(6)
            // Samples never read keep 0 (their weights are 0 too).
            val acc = FloatArray(12)
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    positions(x, y, pos)
                    var a = 0f
                    if (n0) { sampleAll(s, w, h, pos[0], pos[1], acc, 0); if (c0) a = acc[3] }
                    if (n1) { sampleAll(s, w, h, pos[2], pos[3], acc, 4); if (c1 && acc[7] > a) a = acc[7] }
                    if (n2) { sampleAll(s, w, h, pos[4], pos[5], acc, 8); if (c2 && acc[11] > a) a = acc[11] }
                    d[row + x] = if (a < 0.5f) 0 else {
                        val inv = 1f / a
                        val pr = w0r * acc[0] + w1r * acc[4] + w2r * acc[8]
                        val pg = w0g * acc[1] + w1g * acc[5] + w2g * acc[9]
                        val pb = w0b * acc[2] + w1b * acc[6] + w2b * acc[10]
                        (clamp(a) shl 24) or (clamp(pr * inv) shl 16) or (clamp(pg * inv) shl 8) or clamp(pb * inv)
                    }
                }
            }
        }
        return out
    }

    fun clamp(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

    /**
     * Bilinear sample of one channel ([shift] 16/8/0 for R/G/B) at buffer position (fx, fy).
     * Writes the alpha-weighted channel sum into acc[0] and the alpha (0..255) into acc[1], so
     * acc[0] / alpha is the straight channel value.
     */
    fun sample(p: IntArray, w: Int, h: Int, fx: Float, fy: Float, shift: Int, acc: FloatArray) {
        val x = fx - 0.5f; val y = fy - 0.5f
        val xf = floor(x); val yf = floor(y)
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val tx = x - xf; val ty = y - yf
        // Edge pixels extend outward: no colored frame on opaque images, and transparent edges
        // of a layer stay transparent.
        val xa = ArtMath.clampInt(x0, 0, w - 1); val xb = ArtMath.clampInt(x0 + 1, 0, w - 1)
        val r0 = ArtMath.clampInt(y0, 0, h - 1) * w; val r1 = ArtMath.clampInt(y0 + 1, 0, h - 1) * w
        val c00 = p[r0 + xa]; val c10 = p[r0 + xb]; val c01 = p[r1 + xa]; val c11 = p[r1 + xb]
        val a00 = (c00 ushr 24) * (1f - tx) * (1f - ty); val a10 = (c10 ushr 24) * tx * (1f - ty)
        val a01 = (c01 ushr 24) * (1f - tx) * ty; val a11 = (c11 ushr 24) * tx * ty
        acc[0] = a00 * ((c00 shr shift) and 0xFF) + a10 * ((c10 shr shift) and 0xFF) +
            a01 * ((c01 shr shift) and 0xFF) + a11 * ((c11 shr shift) and 0xFF)
        acc[1] = a00 + a10 + a01 + a11
    }

    /**
     * [sample] of all three channels at once: the alpha-weighted R, G and B sums into
     * `acc[o..o+2]` and the alpha into `acc[o+3]`, each with [sample]'s arithmetic (the same bytes).
     */
    fun sampleAll(p: IntArray, w: Int, h: Int, fx: Float, fy: Float, acc: FloatArray, o: Int) {
        val x = fx - 0.5f; val y = fy - 0.5f
        val xf = floor(x); val yf = floor(y)
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val tx = x - xf; val ty = y - yf
        val xa = ArtMath.clampInt(x0, 0, w - 1); val xb = ArtMath.clampInt(x0 + 1, 0, w - 1)
        val r0 = ArtMath.clampInt(y0, 0, h - 1) * w; val r1 = ArtMath.clampInt(y0 + 1, 0, h - 1) * w
        val c00 = p[r0 + xa]; val c10 = p[r0 + xb]; val c01 = p[r1 + xa]; val c11 = p[r1 + xb]
        val a00 = (c00 ushr 24) * (1f - tx) * (1f - ty); val a10 = (c10 ushr 24) * tx * (1f - ty)
        val a01 = (c01 ushr 24) * (1f - tx) * ty; val a11 = (c11 ushr 24) * tx * ty
        acc[o] = a00 * ((c00 shr 16) and 0xFF) + a10 * ((c10 shr 16) and 0xFF) +
            a01 * ((c01 shr 16) and 0xFF) + a11 * ((c11 shr 16) and 0xFF)
        acc[o + 1] = a00 * ((c00 shr 8) and 0xFF) + a10 * ((c10 shr 8) and 0xFF) +
            a01 * ((c01 shr 8) and 0xFF) + a11 * ((c11 shr 8) and 0xFF)
        acc[o + 2] = a00 * (c00 and 0xFF) + a10 * (c10 and 0xFF) +
            a01 * (c01 and 0xFF) + a11 * (c11 and 0xFF)
        acc[o + 3] = a00 + a10 + a01 + a11
    }

    /**
     * Picks the path for [colors]: v1.7's [recombine] with the one-hot direction factors
     * (+1 first, 0 middle, -1 last per channel) through [fast], else [recombineMix] through
     * [general]; the source itself when no swatch has a color.
     */
    inline fun byPath(
        src: PixelBuffer,
        colors: IntArray,
        fast: (k: IntArray) -> PixelBuffer,
        general: (mix: FringeColors.Mix) -> PixelBuffer,
    ): PixelBuffer {
        if (FringeColors.allNone(colors)) return src.copy()
        val k = FringeColors.oneHotFactors(colors)
        return if (k != null) fast(k) else general(FringeColors.Mix(colors))
    }
}

/** Chromatic Aberration (Moving): shifts the R, G and B channels in opposite directions along a line. */
class ChromaticAberrationMovingFilter : Filter("art.chromatic_aberration_moving", "Chromatic Aberration (Moving)", FilterCategory.ART) {
    private val fringes = FringeColors.param(FringeColors.LEGACY_ORDER_KEY)

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("distance", "Distance", 0f, 200f, 5f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("angle", "Angle", 0f, 360f, 0f, step = 1f, suffix = "°"),
        fringes,
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val dist = ctx.px(values.float("distance").coerceAtLeast(0f))
        if (dist < 1e-3f) return src.copy()
        val angle = values.float("angle")
        val vx = ArtMath.unitX(angle) * dist; val vy = ArtMath.unitY(angle) * dist
        return ChannelShift.byPath(
            src, FringeColors.resolve(values, fringes),
            fast = { k -> fast(src, ctx, k, vx, vy) },
            general = { mix -> general(src, ctx, mix, vx, vy) },
        )
    }

    /** v1.7's code: channel c moves by k[c]·v. */
    internal fun fast(src: PixelBuffer, ctx: FilterContext, k: IntArray, vx: Float, vy: Float): PixelBuffer {
        // A channel that moves forward by v is sampled at p - v.
        val rx = -k[0] * vx; val ry = -k[0] * vy
        val gx = -k[1] * vx; val gy = -k[1] * vy
        val bx = -k[2] * vx; val by = -k[2] * vy
        return ChannelShift.recombine(src, ctx) { x, y, pos ->
            val cx = x + 0.5f; val cy = y + 0.5f
            pos[0] = cx + rx; pos[1] = cy + ry
            pos[2] = cx + gx; pos[3] = cy + gy
            pos[4] = cx + bx; pos[5] = cy + by
        }
    }

    /** The general path: fringe i moves by (1 - i)·v (first forward, middle still, last back). */
    internal fun general(src: PixelBuffer, ctx: FilterContext, mix: FringeColors.Mix, vx: Float, vy: Float): PixelBuffer {
        return ChannelShift.recombineMix(src, ctx, mix) { x, y, pos ->
            val cx = x + 0.5f; val cy = y + 0.5f
            pos[0] = cx - vx; pos[1] = cy - vy
            pos[2] = cx; pos[3] = cy
            pos[4] = cx + vx; pos[5] = cy + vy
        }
    }
}

/**
 * Chromatic Aberration (Zooming): scales the channels by slightly different amounts about a movable
 * center, like lateral chromatic aberration of a lens. [distance] is the separation at the farthest
 * image corner.
 */
class ChromaticAberrationZoomingFilter : Filter("art.chromatic_aberration_zooming", "Chromatic Aberration (Zooming)", FilterCategory.ART) {
    private val fringes = FringeColors.param(FringeColors.LEGACY_ORDER_KEY)

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("distance", "Distance", 0f, 200f, 5f, step = 1f, suffix = "px", pixels = true),
        fringes,
        FilterParam.Point("center", "Center"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val dist = ctx.px(values.float("distance").coerceAtLeast(0f))
        if (dist < 1e-3f) return src.copy()
        val w = src.width; val h = src.height
        val pt = values.point("center")
        val cx = pt[0].coerceIn(0f, 1f) * w; val cy = pt[1].coerceIn(0f, 1f) * h
        val rRef = max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy)))
        if (rRef < 1e-3f) return src.copy()
        val s = (dist / rRef).coerceAtMost(0.9f)
        return ChannelShift.byPath(
            src, FringeColors.resolve(values, fringes),
            fast = { k -> fast(src, ctx, k, cx, cy, s) },
            general = { mix -> general(src, ctx, mix, cx, cy, s) },
        )
    }

    /** v1.7's code: channel c zooms by k[c]·s about the center. */
    internal fun fast(src: PixelBuffer, ctx: FilterContext, k: IntArray, cx: Float, cy: Float, s: Float): PixelBuffer {
        // A channel that zooms outward is sampled closer to the center: q = c + (p - c)(1 - k s).
        val fr = 1f - k[0] * s; val fg = 1f - k[1] * s; val fb = 1f - k[2] * s
        return ChannelShift.recombine(src, ctx) { x, y, pos ->
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            pos[0] = cx + dx * fr; pos[1] = cy + dy * fr
            pos[2] = cx + dx * fg; pos[3] = cy + dy * fg
            pos[4] = cx + dx * fb; pos[5] = cy + dy * fb
        }
    }

    /** The general path: fringe i zooms by (1 - i)·s (first outward, middle still, last inward). */
    internal fun general(src: PixelBuffer, ctx: FilterContext, mix: FringeColors.Mix, cx: Float, cy: Float, s: Float): PixelBuffer {
        val ff = 1f - s; val fl = 1f + s
        return ChannelShift.recombineMix(src, ctx, mix) { x, y, pos ->
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            pos[0] = cx + dx * ff; pos[1] = cy + dy * ff
            pos[2] = cx + dx; pos[3] = cy + dy
            pos[4] = cx + dx * fl; pos[5] = cy + dy * fl
        }
    }
}
