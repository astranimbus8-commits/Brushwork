package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Light effects shared by Bloom and Cross Filter: the bright parts of the image are extracted into
 * a low-resolution premultiplied "light" image, spread out (blurred or streaked) and added back.
 * Light is allowed to spill onto transparent pixels: the output alpha grows to fit the added light.
 */
internal object Glow {

    /** Soft-knee bright pass weight of a straight color with luma [y] (0..1). */
    fun brightWeight(y: Float, threshold: Float): Float {
        val m = ((y - threshold) / max(1e-3f, 1f - threshold)).coerceIn(0f, 1f)
        return m * m
    }

    /** Downsampling factor so a light image of `w/f × h/f` stays below [maxPixels]. */
    fun minFactor(w: Int, h: Int, maxPixels: Int): Int =
        max(1, ceil(sqrt(w.toDouble() * h / maxPixels)).toInt())

    /**
     * Bright pass of [src] pooled into f×f cells: premultiplied light planes (0..255 units) of
     * size dw × dh. [maxPool] keeps the brightest value of each cell (so point lights survive the
     * reduction), otherwise the cell average is used.
     */
    fun brightPass(src: PixelBuffer, f: Int, threshold: Float, maxPool: Boolean, ctx: FilterContext): Array<FloatArray> {
        val w = src.width; val h = src.height
        val dw = (w + f - 1) / f; val dh = (h + f - 1) / f
        val planes = Array(3) { FloatArray(dw * dh) }
        val pr = planes[0]; val pg = planes[1]; val pb = planes[2]
        val p = src.pixels
        Parallel.forRows(dh) { j0, j1 ->
            ctx.checkCancelled()
            for (j in j0 until j1) {
                val y0 = j * f; val y1 = min(h, y0 + f)
                for (i in 0 until dw) {
                    val x0 = i * f; val x1 = min(w, x0 + f)
                    var r = 0f; var g = 0f; var b = 0f
                    for (y in y0 until y1) {
                        val row = y * w
                        for (x in x0 until x1) {
                            val c = p[row + x]
                            val a = c ushr 24
                            if (a == 0) continue
                            val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                            val m = brightWeight(ArtMath.luma(cr, cg, cb) / 255f, threshold)
                            if (m <= 0f) continue
                            val k = m * a / 255f
                            if (maxPool) {
                                r = max(r, cr * k); g = max(g, cg * k); b = max(b, cb * k)
                            } else {
                                r += cr * k; g += cg * k; b += cb * k
                            }
                        }
                    }
                    val o = j * dw + i
                    if (maxPool) {
                        pr[o] = r; pg[o] = g; pb[o] = b
                    } else {
                        val inv = 1f / ((x1 - x0) * (y1 - y0))
                        pr[o] = r * inv; pg[o] = g * inv; pb[o] = b * inv
                    }
                }
            }
        }
        return planes
    }

    /**
     * Adds the light planes (dw × dh, cell size [f], bilinearly upsampled) times [gain] to [src].
     * [screen] blends with Screen (never blows out beyond white) instead of plain addition.
     */
    fun composite(src: PixelBuffer, light: Array<FloatArray>, f: Int, gain: Float, screen: Boolean, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val dw = (w + f - 1) / f; val dh = (h + f - 1) / f
        val lr = light[0]; val lg = light[1]; val lb = light[2]
        // Per-column bilinear taps (shared by all rows).
        val cx0 = IntArray(w); val cx1 = IntArray(w); val ctx0 = FloatArray(w)
        for (x in 0 until w) {
            val gx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, (dw - 1).toFloat())
            val i0 = floor(gx).toInt()
            cx0[x] = i0; cx1[x] = min(dw - 1, i0 + 1); ctx0[x] = gx - i0
        }
        val out = PixelBuffer(w, h)
        val sp = src.pixels; val dp = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val gy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, (dh - 1).toFloat())
                val j0 = floor(gy).toInt(); val j1 = min(dh - 1, j0 + 1); val ty = gy - j0
                val o0 = j0 * dw; val o1 = j1 * dw
                val row = y * w
                for (x in 0 until w) {
                    val a0 = o0 + cx0[x]; val a1 = o0 + cx1[x]; val b0 = o1 + cx0[x]; val b1 = o1 + cx1[x]
                    val tx = ctx0[x]
                    val wa0 = (1f - tx) * (1f - ty); val wa1 = tx * (1f - ty); val wb0 = (1f - tx) * ty; val wb1 = tx * ty
                    val gr = (lr[a0] * wa0 + lr[a1] * wa1 + lr[b0] * wb0 + lr[b1] * wb1) * gain
                    val gg = (lg[a0] * wa0 + lg[a1] * wa1 + lg[b0] * wb0 + lg[b1] * wb1) * gain
                    val gb = (lb[a0] * wa0 + lb[a1] * wa1 + lb[b0] * wb0 + lb[b1] * wb1) * gain
                    val c = sp[row + x]
                    if (gr <= 0.02f && gg <= 0.02f && gb <= 0.02f) { dp[row + x] = c; continue }
                    dp[row + x] = addLight(c, gr, gg, gb, screen)
                }
            }
        }
        return out
    }

    /** Adds premultiplied light (0..255 units) to a straight ARGB color. */
    fun addLight(c: Int, lr: Float, lg: Float, lb: Float, screen: Boolean): Int {
        val a = (c ushr 24).toFloat()
        val k = a / 255f
        val sr = ((c shr 16) and 0xFF) * k; val sg = ((c shr 8) and 0xFF) * k; val sb = (c and 0xFF) * k
        val oR: Float; val oG: Float; val oB: Float
        if (screen) {
            val r = min(lr, 255f); val g = min(lg, 255f); val b = min(lb, 255f)
            oR = sr + r - sr * r / 255f; oG = sg + g - sg * g / 255f; oB = sb + b - sb * b / 255f
        } else {
            oR = sr + lr; oG = sg + lg; oB = sb + lb
        }
        val outA = min(255f, max(a, max(oR, max(oG, oB))))
        if (outA < 0.5f) return 0
        val inv = 255f / outA
        return (ChannelShift.clamp(outA) shl 24) or (ChannelShift.clamp(oR * inv) shl 16) or
            (ChannelShift.clamp(oG * inv) shl 8) or ChannelShift.clamp(oB * inv)
    }
}

/**
 * Bloom: bright parts of the image bleed a soft halo of light into their surroundings. The bright
 * pass (soft knee above 1 − Area) is blurred at five scales up to [radius] and added back, either
 * additively or, when Balanced, with Screen so the image does not blow out.
 */
class BloomFilter : Filter("art.bloom", "Bloom", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("area", "Area", 0f, 100f, 40f, step = 1f, suffix = "%"),
        FilterParam.Slider("radius", "Radius", 1f, 200f, 30f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("brightness", "Brightness", 0f, 200f, 100f, step = 1f, suffix = "%"),
        FilterParam.Toggle("balanced", "Balanced", true),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val area = (values.float("area") / 100f).coerceIn(0f, 1f)
        val gain = (values.float("brightness") / 100f).coerceIn(0f, 2f)
        if (area <= 0f || gain <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val radius = max(0.5f, ctx.px(values.float("radius").coerceIn(1f, 200f)))
        val f = max(Glow.minFactor(w, h, 2_000_000), floor(radius / 4f).toInt())
        val light = Glow.brightPass(src, f, 1f - area, maxPool = false, ctx)
        val dw = (w + f - 1) / f; val dh = (h + f - 1) / f
        // Five scales radius/16 .. radius, equally weighted, blurred incrementally in place. The
        // weights sum to 1.75 so small light sources get a visible halo at 100 % brightness.
        val acc = Array(3) { FloatArray(dw * dh) }
        var prevSigma = 0f
        for (level in 0 until 5) {
            val sigma = radius / f * (1 shl level) / 16f
            val step = sqrt(max(0f, sigma * sigma - prevSigma * prevSigma))
            prevSigma = sigma
            for (c in 0 until 3) {
                ArtMath.gaussInPlace(light[c], dw, dh, step, ctx)
                val l = light[c]; val a = acc[c]
                for (i in a.indices) a[i] += l[i] * 0.35f
            }
        }
        return Glow.composite(src, acc, f, gain, values.bool("balanced"), ctx)
    }
}

/**
 * Cross Filter: a camera star filter. Bright light sources emit thin rays (Count of them, rotated
 * by Direction) that fade exponentially over Length. Rays are streaked with a recursive filter
 * along each direction on a reduced-resolution light image, then added back.
 */
class CrossFilter : Filter("art.cross_filter", "Cross Filter", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("count", "Rays", 2f, 16f, 4f, step = 1f),
        FilterParam.Slider("direction", "Direction", 0f, 360f, 45f, step = 1f, suffix = "°"),
        FilterParam.Slider("length", "Length", 5f, 1000f, 120f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("thickness", "Thickness", 1f, 10f, 2f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("area", "Area", 0f, 100f, 20f, step = 1f, suffix = "%"),
        FilterParam.Slider("brightness", "Brightness", 0f, 200f, 100f, step = 1f, suffix = "%"),
        FilterParam.Toggle("chromatic", "Chromatic rays", false),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val area = (values.float("area") / 100f).coerceIn(0f, 1f)
        val gain = (values.float("brightness") / 100f).coerceIn(0f, 2f)
        if (area <= 0f || gain <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val count = values.int("count").coerceIn(2, 16)
        val direction = values.float("direction")
        val f = max(Glow.minFactor(w, h, 1_500_000), ctx.px(values.float("thickness").coerceIn(1f, 10f)).roundToInt())
        val length = max(0.5f, ctx.px(values.float("length").coerceIn(5f, 1000f)) / f)
        val chromatic = values.bool("chromatic")
        val light = Glow.brightPass(src, f, 1f - area, maxPool = true, ctx)
        val dw = (w + f - 1) / f; val dh = (h + f - 1) / f
        val rays = Array(3) { FloatArray(dw * dh) }
        val lengthScale = if (chromatic) floatArrayOf(1f, 0.82f, 0.64f) else floatArrayOf(1f, 1f, 1f)
        Parallel.forRange(3, 1) { c0, c1 ->
            for (c in c0 until c1) {
                val prev = FloatArray(max(dw, dh)); val cur = FloatArray(max(dw, dh))
                for (k in 0 until count) {
                    ctx.checkCancelled()
                    val deg = direction + k * 360f / count
                    streak(light[c], rays[c], dw, dh, ArtMath.unitX(deg), ArtMath.unitY(deg), length * lengthScale[c], prev, cur)
                }
            }
        }
        return Glow.composite(src, rays, f, gain, screen = false, ctx)
    }

    /**
     * Adds a one-sided exponential streak of [e] along (ux, uy) into [acc]:
     * acc(p) += Σ_{s ≥ 1} d^s e(p − s·v) with a unit step along the major axis. Implemented as a
     * first-order recursion over columns (or rows), interpolating linearly along the minor axis.
     */
    private fun streak(e: FloatArray, acc: FloatArray, dw: Int, dh: Int, ux: Float, uy: Float, length: Float, prev: FloatArray, cur: FloatArray) {
        val xMajor = abs(ux) >= abs(uy)
        val major = if (xMajor) abs(ux) else abs(uy)
        val stepLen = 1f / major
        val decay = exp(-3f * stepLen / length)
        val slope = (if (xMajor) uy else ux) / major // minor offset per major step
        val dir = if ((if (xMajor) ux else uy) > 0f) 1 else -1
        val nMajor = if (xMajor) dw else dh
        val nMinor = if (xMajor) dh else dw
        // Index of (major, minor) = major * majorStride + minor * minorStride.
        val majorStride = if (xMajor) 1 else dw
        val minorStride = if (xMajor) dw else 1
        prev.fill(0f, 0, nMinor)
        var ma = if (dir > 0) 0 else nMajor - 1
        repeat(nMajor) {
            val base = ma * majorStride
            for (mi in 0 until nMinor) {
                // Streak value S(p) = e(p) + d * S(p - v); the added ray excludes the source itself.
                val q = mi - slope
                val q0 = floor(q).toInt(); val t = q - q0
                val s0 = if (q0 in 0 until nMinor) prev[q0] else 0f
                val s1 = if (q0 + 1 in 0 until nMinor) prev[q0 + 1] else 0f
                val tail = decay * (s0 + (s1 - s0) * t)
                val i = base + mi * minorStride
                cur[mi] = e[i] + tail
                acc[i] += tail
            }
            System.arraycopy(cur, 0, prev, 0, nMinor)
            ma += dir
        }
    }
}
