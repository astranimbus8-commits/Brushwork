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
 * space with transparent outside the image; the output alpha is the largest of the three sampled
 * alphas and each channel keeps its own coverage (so fringes appear on transparent layers too).
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
        var ps = 0f; var aS = 0f
        // Four taps, skipping those outside the image (transparent outside).
        if (y0 in 0 until h) {
            val row = y0 * w
            val wy = 1f - ty
            if (x0 in 0 until w) { val c = p[row + x0]; val a = (c ushr 24) * (1f - tx) * wy; aS += a; ps += a * ((c shr shift) and 0xFF) }
            if (x0 + 1 in 0 until w && tx > 0f) { val c = p[row + x0 + 1]; val a = (c ushr 24) * tx * wy; aS += a; ps += a * ((c shr shift) and 0xFF) }
        }
        if (y0 + 1 in 0 until h && ty > 0f) {
            val row = (y0 + 1) * w
            if (x0 in 0 until w) { val c = p[row + x0]; val a = (c ushr 24) * (1f - tx) * ty; aS += a; ps += a * ((c shr shift) and 0xFF) }
            if (x0 + 1 in 0 until w && tx > 0f) { val c = p[row + x0 + 1]; val a = (c ushr 24) * tx * ty; aS += a; ps += a * ((c shr shift) and 0xFF) }
        }
        acc[0] = ps
        acc[1] = aS
    }

    val orderParam = FilterParam.Choice("order", "Color order", ArtMath.colorOrderNames, 0)
}

/** Chromatic Aberration (Moving): shifts the R, G and B channels in opposite directions along a line. */
class ChromaticAberrationMovingFilter : Filter("art.chromatic_aberration_moving", "Chromatic Aberration (Moving)", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("distance", "Distance", 0f, 200f, 5f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("angle", "Angle", 0f, 360f, 0f, step = 1f, suffix = "°"),
        ChannelShift.orderParam,
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val dist = ctx.px(values.float("distance").coerceAtLeast(0f))
        if (dist < 1e-3f) return src.copy()
        val angle = values.float("angle")
        val vx = ArtMath.unitX(angle) * dist; val vy = ArtMath.unitY(angle) * dist
        val k = ArtMath.colorOrderFactors(values.choice("order"))
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
}

/**
 * Chromatic Aberration (Zooming): scales the channels by slightly different amounts about a movable
 * center, like lateral chromatic aberration of a lens. [distance] is the separation at the farthest
 * image corner.
 */
class ChromaticAberrationZoomingFilter : Filter("art.chromatic_aberration_zooming", "Chromatic Aberration (Zooming)", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("distance", "Distance", 0f, 200f, 5f, step = 1f, suffix = "px", pixels = true),
        ChannelShift.orderParam,
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
        val k = ArtMath.colorOrderFactors(values.choice("order"))
        // A channel that zooms outward is sampled closer to the center: q = c + (p - c)(1 - k s).
        val fr = 1f - k[0] * s; val fg = 1f - k[1] * s; val fb = 1f - k[2] * s
        return ChannelShift.recombine(src, ctx) { x, y, pos ->
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            pos[0] = cx + dx * fr; pos[1] = cy + dy * fr
            pos[2] = cx + dx * fg; pos[3] = cy + dy * fg
            pos[4] = cx + dx * fb; pos[5] = cy + dy * fb
        }
    }
}
