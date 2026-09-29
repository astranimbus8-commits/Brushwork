package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Blur Frame: a soft vignette border. Toward the edges the image is progressively blurred and a
 * frame color (black by default) fades in. Rectangle frames have gaussian-rounded corners.
 */
class BlurFrameFilter : Filter("frame.blur_frame", "Blur Frame", FilterCategory.FRAME) {
    override val generatesContent: Boolean = true

    override val params: List<FilterParam> = listOf(
        FilterParam.Choice("shape", "Shape", listOf("Rectangle", "Ellipse")),
        FilterParam.Slider("size", "Size", 0f, 100f, 25f, 1f, "%"),
        FilterParam.Slider("softness", "Softness", 0f, 100f, 70f, 1f, "%"),
        FilterParam.Slider("blur", "Blur", 0f, 300f, 24f, 1f, pixels = true),
        FilterParam.Slider("opacity", "Color opacity", 0f, 100f, 80f, 1f, "%"),
        FilterParam.Color("color", "Color", 0xFF000000.toInt()),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val size = values.float("size").coerceIn(0f, 100f) / 100f
        val soft = values.float("softness").coerceIn(0f, 100f) / 100f
        val color = values.color("color")
        val colorAlpha = (color ushr 24) / 255f * values.float("opacity").coerceIn(0f, 100f) / 100f
        val blur = ctx.px(values.float("blur")).coerceAtLeast(0f)
        val half = min(w, h) * 0.5f
        if (size * half < 0.5f || (colorAlpha <= 0f && blur < 0.5f)) return src.copy()

        val mask = if (values.choice("shape") == 1) ellipseMask(w, h, size, soft, half) else rectMask(w, h, size * half, soft)

        // Two blur levels (half and full radius) so the blur grows gradually toward the edge.
        // Large radii are computed on a box-downscaled copy: the result is smooth anyway.
        val levels: Array<PixelBuffer>? = if (blur >= 0.5f) {
            val f = min(1f, BLUR_WORK_RADIUS / blur)
            val small = DistortMath.downscale(src, ceil(w * f).toInt(), ceil(h * f).toInt(), ctx)
            val rs = blur * (small.width.toFloat() / w + small.height.toFloat() / h) * 0.5f
            val b1 = DistortMath.blurImage(small, rs * 0.5f, ctx)
            arrayOf(b1, DistortMath.blurImage(b1, rs * 0.866f, ctx))
        } else {
            null
        }
        val kx = if (levels != null) levels[0].width.toFloat() / w else 1f
        val ky = if (levels != null) levels[0].height.toFloat() / h else 1f
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val m = mask.at(x, y)
            if (m <= 1f / 1024f) {
                c
            } else {
                var out = c
                if (levels != null) {
                    val sx = (x + 0.5f) * kx; val sy = (y + 0.5f) * ky
                    val t = m * 2f
                    val c1 = levels[0].sampleBilinear(sx, sy)
                    out = if (t <= 1f) {
                        DistortMath.lerpPremul(c, c1, t)
                    } else {
                        DistortMath.lerpPremul(c1, levels[1].sampleBilinear(sx, sy), t - 1f)
                    }
                }
                DistortMath.overColor(out, color, colorAlpha * m)
            }
        }
    }

    /** Frame strength 0..1 per pixel (1 at the border). */
    private fun interface FrameMask {
        fun at(x: Int, y: Int): Float
    }

    /** Separable gaussian-blurred rectangle: depth [depth] px, transition width depth * soft. */
    private fun rectMask(w: Int, h: Int, depth: Float, soft: Float): FrameMask {
        val mid = depth * (1f - soft * 0.5f)
        val sigma = max(0.5f, depth * soft * 0.25f)
        fun inside(n: Int) = FloatArray(n) { i ->
            val p = i + 0.5f
            DistortMath.phi((p - mid) / sigma) * DistortMath.phi((n - p - mid) / sigma)
        }
        val ix = inside(w)
        val iy = inside(h)
        return FrameMask { x, y -> 1f - ix[x] * iy[y] }
    }

    /** Elliptical vignette in coordinates normalized to the half width / height. */
    private fun ellipseMask(w: Int, h: Int, size: Float, soft: Float, half: Float): FrameMask {
        val mid = 1f - size * (1f - soft * 0.5f)
        val sigma = max(0.5f / half, size * soft * 0.25f)
        // Tabulated over rho^2 in 0..2 (2 = the corners).
        val lut = FloatArray(LUT_N + 1) { i ->
            val rho = sqrt(i * 2f / LUT_N)
            DistortMath.phi((rho - mid) / sigma)
        }
        val cx = w * 0.5f; val cy = h * 0.5f
        val invX = 1f / cx; val invY = 1f / cy
        return FrameMask { x, y ->
            val nx = (x + 0.5f - cx) * invX
            val ny = (y + 0.5f - cy) * invY
            val q = min(2f, nx * nx + ny * ny) * (LUT_N / 2f)
            val i = min(LUT_N - 1, q.toInt())
            lut[i] + (lut[i + 1] - lut[i]) * (q - i)
        }
    }

    private companion object {
        const val LUT_N = 4096

        /** Blur radius (px) the working copy is downscaled to when the requested blur is larger. */
        const val BLUR_WORK_RADIUS = 12f
    }
}

/**
 * Rain: slanted, tapered streaks drawn over the image (or on an empty layer). Streaks are placed in
 * full-resolution coordinates from the seed, so the preview matches the final result.
 */
class RainFilter : Filter("frame.rain", "Rain", FilterCategory.FRAME) {
    override val generatesContent: Boolean = true

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("amount", "Amount", 0f, 100f, 40f, 1f, "%"),
        FilterParam.Slider("length", "Length", 2f, 1000f, 80f, 1f, pixels = true),
        FilterParam.Slider("thickness", "Thickness", 0.5f, 20f, 2f, 0f, pixels = true),
        FilterParam.Slider("angle", "Angle", -60f, 60f, 12f, 1f, "°"),
        FilterParam.Slider("variation", "Variation", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("opacity", "Opacity", 0f, 100f, 70f, 1f, "%"),
        FilterParam.Slider("blur", "Blur", 0f, 20f, 0f, 0f, pixels = true),
        FilterParam.Color("color", "Color", 0xFFCFDAE6.toInt()),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val amount = values.float("amount").coerceIn(0f, 100f) / 100f
        val color = values.color("color")
        val alpha = (color ushr 24) / 255f * values.float("opacity").coerceIn(0f, 100f) / 100f
        if (amount <= 0f || alpha <= 0f) return src.copy()

        val drops = Drops.generate(w, h, ctx.scale, values, amount) ?: return src.copy()
        ctx.checkCancelled()
        val coverage = FloatArray(w * h)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (k in 0 until drops.count) drops.rasterize(k, coverage, w, y0, y1)
        }
        val blur = ctx.px(values.float("blur"))
        if (blur >= 0.5f) DistortMath.blurPlaneInPlace(coverage, w, h, blur, ctx)

        val out = PixelBuffer(w, h)
        val s = src.pixels; val d = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (i in y0 * w until y1 * w) d[i] = DistortMath.overColor(s[i], color, alpha * coverage[i])
        }
        return out
    }

    /** Streak geometry in buffer pixels: tail (tx, ty) -> head (hx, hy), half thickness, opacity. */
    private class Drops(
        val count: Int,
        val tx: FloatArray,
        val ty: FloatArray,
        val hx: FloatArray,
        val hy: FloatArray,
        val halfT: FloatArray,
        val alpha: FloatArray,
        /** cos of the fall angle (>= 0.5): converts perpendicular distance to a row span. */
        val dirY: Float,
    ) {
        /** Accumulates drop [k]'s antialiased, tapered capsule into rows [y0, y1) of [cov]. */
        fun rasterize(k: Int, cov: FloatArray, w: Int, y0: Int, y1: Int) {
            val ht = halfT[k]
            val pad = ht + 1f
            val ax = tx[k]; val ay = ty[k]
            val ddx = hx[k] - ax; val ddy = hy[k] - ay
            val ya = max(y0, floor(min(ay, hy[k]) - pad).toInt())
            val yb = min(y1, ceil(max(ay, hy[k]) + pad).toInt())
            if (ya >= yb || ddy <= 0f) return
            val invLen2 = 1f / (ddx * ddx + ddy * ddy)
            val span = pad / dirY
            val a0 = alpha[k]
            for (y in ya until yb) {
                val py = y + 0.5f
                val xc = ax + ddx * ((py - ay) / ddy)
                val xa = max(0, floor(xc - span).toInt())
                val xb = min(w, ceil(xc + span).toInt() + 1)
                val row = y * w
                for (x in xa until xb) {
                    val vx = x + 0.5f - ax; val vy = py - ay
                    val t = ((vx * ddx + vy * ddy) * invLen2).coerceIn(0f, 1f)
                    val ex = vx - ddx * t; val ey = vy - ddy * t
                    var c = ht - sqrt(ex * ex + ey * ey) + 0.5f
                    if (c <= 0f) continue
                    if (c > 1f) c = 1f
                    // Tapers from the tail (t = 0) to the head (t = 1).
                    val a = a0 * c * t
                    val i = row + x
                    cov[i] += a * (1f - cov[i])
                }
            }
        }

        companion object {
            /** Full-resolution px^2 per drop at 100% amount. */
            private const val AREA_PER_DROP = 1200f
            private const val MAX_DROPS = 300_000

            fun generate(w: Int, h: Int, scale: Float, values: FilterValues, amount: Float): Drops? {
                val sc = if (scale > 0f) scale else 1f
                val fullW = w / sc; val fullH = h / sc
                val variation = values.float("variation").coerceIn(0f, 100f) / 100f
                val lenF = values.float("length").coerceAtLeast(0.5f)
                val thickF = values.float("thickness").coerceAtLeast(0.05f)
                val angle = values.float("angle").coerceIn(-60f, 60f) * DistortMath.DEG
                val dirX = sin(angle); val dirY = cos(angle)
                val seed = values.seed()
                // Streak centers are spread over the canvas plus a margin so streaks cross the edges.
                val ext = lenF * (1f + 0.6f * variation) * 0.5f + thickF * 2f
                val spanW = fullW + 2f * ext; val spanH = fullH + 2f * ext
                val n = min(MAX_DROPS.toDouble(), amount.toDouble() * spanW * spanH / AREA_PER_DROP).toInt()
                if (n <= 0) return null
                val tx = FloatArray(n); val ty = FloatArray(n); val hx = FloatArray(n); val hy = FloatArray(n)
                val halfT = FloatArray(n); val alpha = FloatArray(n)
                for (k in 0 until n) {
                    val mx = (-ext + FilterMath.hash01(k, 0, seed) * spanW) * sc
                    val my = (-ext + FilterMath.hash01(k, 1, seed) * spanH) * sc
                    val len = max(0.5f, lenF * (1f + variation * 1.2f * (FilterMath.hash01(k, 2, seed) - 0.5f)) * sc)
                    var th = thickF * (1f + variation * 0.8f * (FilterMath.hash01(k, 3, seed) - 0.5f)) * sc
                    var a = 1f - variation * 0.6f * FilterMath.hash01(k, 4, seed)
                    // Hairlines: keep a 1px footprint and fade instead of thinning further.
                    if (th < 1f) { a *= th; th = 1f }
                    hx[k] = mx + dirX * len * 0.5f; hy[k] = my + dirY * len * 0.5f
                    tx[k] = mx - dirX * len * 0.5f; ty[k] = my - dirY * len * 0.5f
                    halfT[k] = th * 0.5f
                    alpha[k] = a
                }
                return Drops(n, tx, ty, hx, hy, halfT, alpha, dirY)
            }
        }
    }
}
