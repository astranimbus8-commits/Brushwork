package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Small numeric helpers shared by the distortion and frame filters. */
internal object DistortMath {
    const val PI = Math.PI.toFloat()
    const val TWO_PI = (2.0 * Math.PI).toFloat()
    const val DEG = (Math.PI / 180.0).toFloat()

    /** Sampling outside the image returns transparent pixels. */
    const val EDGE_TRANSPARENT = 0

    /** Sampling outside the image repeats the nearest edge pixel. */
    const val EDGE_CLAMP = 1

    /** Sampling outside the image wraps around to the opposite side. */
    const val EDGE_WRAP = 2

    /** UI labels for an edge-mode [com.brushwork.paint.filters.FilterParam.Choice]; index = mode. */
    val EDGE_OPTIONS = listOf("Transparent", "Stretch edges", "Wrap around")

    /**
     * Radius given in percent of half the shorter image side (100% = a centered circle that
     * touches the nearest edges). Independent of preview scale because it is relative.
     */
    fun percentRadius(percent: Float, width: Int, height: Int): Float =
        max(0.5f, percent / 100f * min(width, height) * 0.5f)

    /** Slider value -100..100 to a vertical squash factor 0.25..4 (0 = circle). */
    fun aspectFactor(value: Float): Float = 2f.pow(value.coerceIn(-100f, 100f) / 50f)

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Error function, Abramowitz & Stegun 7.1.26 (|error| < 1.5e-7). */
    fun erf(x: Float): Float {
        val ax = abs(x)
        val t = 1f / (1f + 0.3275911f * ax)
        val poly = t * (0.254829592f + t * (-0.284496736f + t * (1.421413741f + t * (-1.453152027f + t * 1.061405429f))))
        val y = 1f - poly * exp(-ax * ax)
        return if (x < 0f) -y else y
    }

    /** Standard normal cumulative distribution, clamped to 0..1. */
    fun phi(z: Float): Float = (0.5f * (1f + erf(z * 0.70710678f))).coerceIn(0f, 1f)

    /**
     * Source-over of [color] (its RGB) with coverage [alpha] 0..1 onto the NON-premultiplied
     * pixel [dst]. [alpha] should already include the color's own alpha.
     */
    fun overColor(dst: Int, color: Int, alpha: Float): Int {
        if (alpha <= 0f) return dst
        val sa = min(1f, alpha)
        val da = (dst ushr 24) / 255f
        val ka = da * (1f - sa)
        val oa = sa + ka
        if (oa <= 0f) return 0
        val inv = 1f / oa
        val r = (((color shr 16) and 0xFF) * sa + ((dst shr 16) and 0xFF) * ka) * inv
        val g = (((color shr 8) and 0xFF) * sa + ((dst shr 8) and 0xFF) * ka) * inv
        val b = ((color and 0xFF) * sa + (dst and 0xFF) * ka) * inv
        return (((oa * 255f + 0.5f).toInt()) shl 24) or
            (min(255, (r + 0.5f).toInt()) shl 16) or
            (min(255, (g + 0.5f).toInt()) shl 8) or
            min(255, (b + 0.5f).toInt())
    }

    /** Interpolates two NON-premultiplied colors in premultiplied space (no dark fringes). */
    fun lerpPremul(c1: Int, c2: Int, t: Float): Int {
        if (t <= 0f) return c1
        if (t >= 1f) return c2
        val a1 = (c1 ushr 24) * (1f - t)
        val a2 = (c2 ushr 24) * t
        val a = a1 + a2
        if (a <= 0.001f) return 0
        val inv = 1f / a
        val r = (((c1 shr 16) and 0xFF) * a1 + ((c2 shr 16) and 0xFF) * a2) * inv
        val g = (((c1 shr 8) and 0xFF) * a1 + ((c2 shr 8) and 0xFF) * a2) * inv
        val b = ((c1 and 0xFF) * a1 + (c2 and 0xFF) * a2) * inv
        return ColorUtils.argb((a + 0.5f).toInt(), (r + 0.5f).toInt(), (g + 0.5f).toInt(), (b + 0.5f).toInt())
    }

    // ------------------------------------------------------------------------------ sampling

    /** Bilinear, premultiplied-correct sample at (fx, fy) (pixel centers at +0.5) with an edge mode. */
    fun sample(src: PixelBuffer, fx: Float, fy: Float, edge: Int): Int = when (edge) {
        EDGE_TRANSPARENT -> src.sampleBilinear(fx, fy, transparentOutside = true)
        EDGE_WRAP -> sampleWrap(src, fx, fy)
        else -> src.sampleBilinear(fx, fy, transparentOutside = false)
    }

    /** Bilinear sample that wraps around horizontally and vertically (seamless across the seam). */
    fun sampleWrap(src: PixelBuffer, fx: Float, fy: Float): Int {
        val w = src.width; val h = src.height
        val x = fx - 0.5f; val y = fy - 0.5f
        val xf = floor(x); val yf = floor(y)
        if (xf.isNaN() || yf.isNaN()) return 0
        val tx = x - xf; val ty = y - yf
        val x0 = wrapIndex(xf, w); val y0 = wrapIndex(yf, h)
        val x1 = if (x0 + 1 >= w) 0 else x0 + 1
        val y1 = if (y0 + 1 >= h) 0 else y0 + 1
        val p = src.pixels
        return PixelBuffer.bilerpPremul(p[y0 * w + x0], p[y0 * w + x1], p[y1 * w + x0], p[y1 * w + x1], tx, ty)
    }

    /** Bilinear sample that wraps around horizontally and clamps vertically (for polar seams). */
    fun sampleWrapX(src: PixelBuffer, fx: Float, fy: Float): Int {
        val w = src.width; val h = src.height
        val x = fx - 0.5f; val y = fy - 0.5f
        val xf = floor(x); val yf = floor(y)
        if (xf.isNaN() || yf.isNaN()) return 0
        val tx = x - xf; val ty = y - yf
        val x0 = wrapIndex(xf, w)
        val x1 = if (x0 + 1 >= w) 0 else x0 + 1
        val yi = yf.coerceIn(-1f, h.toFloat()).toInt()
        val y0 = yi.coerceIn(0, h - 1)
        val y1 = (yi + 1).coerceIn(0, h - 1)
        val p = src.pixels
        return PixelBuffer.bilerpPremul(p[y0 * w + x0], p[y0 * w + x1], p[y1 * w + x0], p[y1 * w + x1], tx, ty)
    }

    /** floor-mod of an integral float into 0 until n. */
    fun wrapIndex(v: Float, n: Int): Int {
        val m = v - floor(v / n) * n
        val i = m.toInt()
        return if (i < 0) 0 else if (i >= n) n - 1 else i
    }

    /**
     * Inverse-mapping warp driver. For each pixel inside [x0, x1) x [y0, y1), [map] receives the
     * pixel center and writes the source position into `q`; when it returns true the output pixel
     * is sampled from [src] there, otherwise (and outside the rectangle) the pixel is copied.
     */
    inline fun warp(
        src: PixelBuffer,
        ctx: FilterContext,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        edge: Int,
        crossinline map: (px: Float, py: Float, q: FloatArray) -> Boolean,
    ): PixelBuffer {
        val out = src.copy()
        val xa = max(0, x0); val ya = max(0, y0)
        val xb = min(src.width, x1); val yb = min(src.height, y1)
        if (xa >= xb || ya >= yb) return out
        val w = src.width
        val d = out.pixels
        Parallel.forRange(yb - ya, 4) { r0, r1 ->
            ctx.checkCancelled()
            val q = FloatArray(2)
            for (y in ya + r0 until ya + r1) {
                val py = y + 0.5f
                val row = y * w
                for (x in xa until xb) {
                    if (map(x + 0.5f, py, q)) d[row + x] = sample(src, q[0], q[1], edge)
                }
            }
        }
        return out
    }

    /**
     * Radial warp inside the circle of [radius] around ([cx], [cy]): a pixel at offset v from the
     * center samples the source at center + v * profile.ratioAt(|v| / radius). Outside unchanged.
     */
    fun radialWarp(
        src: PixelBuffer,
        ctx: FilterContext,
        cx: Float,
        cy: Float,
        radius: Float,
        profile: RadialProfile,
        edge: Int = EDGE_CLAMP,
    ): PixelBuffer {
        val r2max = radius * radius
        val invR = 1f / radius
        val x0 = floor(cx - radius).toInt(); val x1 = ceil(cx + radius).toInt() + 1
        val y0 = floor(cy - radius).toInt(); val y1 = ceil(cy + radius).toInt() + 1
        return warp(src, ctx, x0, y0, x1, y1, edge) { px, py, q ->
            val dx = px - cx; val dy = py - cy
            val r2 = dx * dx + dy * dy
            if (r2 >= r2max) {
                false
            } else {
                val k = profile.ratioAt(sqrt(r2) * invR)
                q[0] = cx + dx * k
                q[1] = cy + dy * k
                true
            }
        }
    }

    /**
     * An ellipse frame: rotates by [angleRad] and squashes y by [aspect] so that the ellipse with
     * semi-axes (radius, radius / aspect) becomes a circle of radius `radius`.
     */
    class EllipseFrame(val cx: Float, val cy: Float, angleRad: Float, val aspect: Float) {
        val cos = cos(angleRad)
        val sin = sin(angleRad)

        fun toLocalX(px: Float, py: Float): Float = (px - cx) * cos + (py - cy) * sin
        fun toLocalY(px: Float, py: Float): Float = (-(px - cx) * sin + (py - cy) * cos) * aspect

        fun toImageX(lx: Float, ly: Float): Float = cx + lx * cos - (ly / aspect) * sin
        fun toImageY(lx: Float, ly: Float): Float = cy + lx * sin + (ly / aspect) * cos

        /** Half extents of the axis-aligned bounding box of the frame's circle of [radius]. */
        fun halfWidth(radius: Float): Float = sqrt((radius * cos).pow(2) + (radius / aspect * sin).pow(2))
        fun halfHeight(radius: Float): Float = sqrt((radius * sin).pow(2) + (radius / aspect * cos).pow(2))
    }

    // ------------------------------------------------------------------------------ resampling

    /** Box-filter weights for shrinking a 1D axis of [srcLen] samples to [dstLen] samples. */
    private class BoxWeights(srcLen: Int, dstLen: Int) {
        val stride: Int
        val start = IntArray(dstLen)
        val count = IntArray(dstLen)
        val weight: FloatArray

        init {
            val span = srcLen.toDouble() / dstLen
            stride = ceil(span).toInt() + 1
            weight = FloatArray(dstLen * stride)
            for (i in 0 until dstLen) {
                val a = i * span
                val b = min(srcLen.toDouble(), (i + 1) * span)
                val s = min(srcLen - 1, floor(a).toInt())
                val e = max(s + 1, min(srcLen, ceil(b - 1e-9).toInt()))
                val n = min(stride, e - s)
                start[i] = s
                count[i] = n
                var sum = 0.0
                for (k in 0 until n) {
                    val lo = max(a, (s + k).toDouble())
                    val hi = min(b, (s + k + 1).toDouble())
                    val wgt = max(0.0, hi - lo)
                    weight[i * stride + k] = wgt.toFloat()
                    sum += wgt
                }
                if (sum <= 0.0) { weight[i * stride] = 1f; sum = 1.0 }
                for (k in 0 until n) weight[i * stride + k] = (weight[i * stride + k] / sum).toFloat()
            }
        }
    }

    /**
     * Area-averaging (box) downscale to [nw] x [nh] (each at most the source size), interpolating in
     * premultiplied space. Returns [src] itself when no reduction is needed (treat as read-only).
     * Works one output row at a time, so the only extra memory is the result.
     */
    fun downscale(src: PixelBuffer, nw: Int, nh: Int, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val tw = nw.coerceIn(1, w); val th = nh.coerceIn(1, h)
        if (tw == w && th == h) return src
        val xs = BoxWeights(w, tw)
        val ys = BoxWeights(h, th)
        val out = PixelBuffer(tw, th)
        val p = src.pixels
        val d = out.pixels
        Parallel.forRange(th, 2) { j0, j1 ->
            ctx.checkCancelled()
            val accA = FloatArray(tw); val accR = FloatArray(tw); val accG = FloatArray(tw); val accB = FloatArray(tw)
            for (j in j0 until j1) {
                accA.fill(0f); accR.fill(0f); accG.fill(0f); accB.fill(0f)
                for (ky in 0 until ys.count[j]) {
                    val wy = ys.weight[j * ys.stride + ky]
                    if (wy <= 0f) continue
                    val row = (ys.start[j] + ky) * w
                    for (i in 0 until tw) {
                        var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                        val base = i * xs.stride
                        val st = row + xs.start[i]
                        for (kx in 0 until xs.count[i]) {
                            val c = p[st + kx]
                            val a = (c ushr 24) * xs.weight[base + kx]
                            sa += a
                            sr += ((c shr 16) and 0xFF) * a
                            sg += ((c shr 8) and 0xFF) * a
                            sb += (c and 0xFF) * a
                        }
                        accA[i] += sa * wy; accR[i] += sr * wy; accG[i] += sg * wy; accB[i] += sb * wy
                    }
                }
                val orow = j * tw
                for (i in 0 until tw) {
                    val a = accA[i]
                    if (a < 0.5f) { d[orow + i] = 0; continue }
                    val inv = 1f / a
                    d[orow + i] = ColorUtils.argb((a + 0.5f).toInt(), (accR[i] * inv + 0.5f).toInt(), (accG[i] * inv + 0.5f).toInt(), (accB[i] * inv + 0.5f).toInt())
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------ blur

    /**
     * Gaussian-like blur (3 box passes, same radius semantics as [FilterMath.blur]) of a float
     * plane, IN PLACE. Memory: only per-thread line buffers.
     */
    fun blurPlaneInPlace(plane: FloatArray, w: Int, h: Int, radius: Float, ctx: FilterContext) {
        if (radius < 0.5f) return
        for (box in FilterMath.boxesForGauss(radius / 2f + 0.01f, 3)) {
            val r = (box - 1) / 2
            if (r <= 0) continue
            boxRowsInPlace(plane, w, h, r, ctx)
            boxColumnsInPlace(plane, w, h, r, ctx)
        }
    }

    private fun boxRowsInPlace(plane: FloatArray, w: Int, h: Int, r: Int, ctx: FilterContext) {
        val norm = 1f / (2 * r + 1)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val line = FloatArray(w)
            for (y in y0 until y1) {
                val row = y * w
                System.arraycopy(plane, row, line, 0, w)
                var acc = 0f
                for (k in -r..r) acc += line[min(w - 1, max(0, k))]
                for (x in 0 until w) {
                    plane[row + x] = acc * norm
                    acc += line[min(w - 1, x + r + 1)] - line[max(0, x - r)]
                }
            }
        }
    }

    private const val COLUMN_BLOCK = 16

    private fun boxColumnsInPlace(plane: FloatArray, w: Int, h: Int, r: Int, ctx: FilterContext) {
        val norm = 1f / (2 * r + 1)
        val blocks = (w + COLUMN_BLOCK - 1) / COLUMN_BLOCK
        Parallel.forRange(blocks, 1) { b0, b1 ->
            ctx.checkCancelled()
            val bw = COLUMN_BLOCK
            val tmp = FloatArray(bw * h)
            val acc = FloatArray(bw)
            for (blk in b0 until b1) {
                val x0 = blk * bw
                val n = min(bw, w - x0)
                for (y in 0 until h) System.arraycopy(plane, y * w + x0, tmp, y * bw, n)
                for (j in 0 until n) {
                    var s = 0f
                    for (k in -r..r) s += tmp[min(h - 1, max(0, k)) * bw + j]
                    acc[j] = s
                }
                for (y in 0 until h) {
                    val row = y * w + x0
                    val add = min(h - 1, y + r + 1) * bw
                    val sub = max(0, y - r) * bw
                    for (j in 0 until n) {
                        plane[row + j] = acc[j] * norm
                        acc[j] += tmp[add + j] - tmp[sub + j]
                    }
                }
            }
        }
    }

    /**
     * Alpha-correct gaussian-like blur of a whole image (same radius semantics as
     * [FilterMath.blur]) that needs only two float planes at a time instead of four.
     */
    fun blurImage(src: PixelBuffer, radius: Float, ctx: FilterContext): PixelBuffer {
        if (radius < 0.5f) return src.copy()
        val w = src.width; val h = src.height; val n = src.size
        val p = src.pixels
        val alpha = FloatArray(n)
        Parallel.forRange(n, 4096) { a, b -> for (i in a until b) alpha[i] = (p[i] ushr 24).toFloat() }
        blurPlaneInPlace(alpha, w, h, radius, ctx)
        val out = PixelBuffer(w, h)
        val d = out.pixels
        Parallel.forRange(n, 4096) { a, b ->
            for (i in a until b) {
                val al = alpha[i]
                d[i] = if (al < 0.5f) 0 else ColorUtils.clamp255(al) shl 24
            }
        }
        val plane = FloatArray(n)
        for (shift in intArrayOf(16, 8, 0)) {
            ctx.checkCancelled()
            Parallel.forRange(n, 4096) { a, b ->
                for (i in a until b) {
                    val c = p[i]
                    plane[i] = ((c shr shift) and 0xFF) * (c ushr 24) / 255f
                }
            }
            blurPlaneInPlace(plane, w, h, radius, ctx)
            Parallel.forRange(n, 4096) { a, b ->
                for (i in a until b) {
                    val al = alpha[i]
                    if (al < 0.5f) continue
                    d[i] = d[i] or (ColorUtils.clamp255(plane[i] * 255f / al) shl shift)
                }
            }
        }
        return out
    }
}

/**
 * Radial remapping profile. For a normalized radius t in 0..1 it gives k(t) = f(t) / t, so that a
 * point at offset v from the center samples the source at center + v * k(|v| / radius).
 * [f] must be monotonic with f(0) = 0 and f(1) = 1 (continuous at the circle's edge).
 */
internal class RadialProfile private constructor(private val ratio: FloatArray) {

    fun ratioAt(t: Float): Float {
        val x = (if (t < 0f) 0f else if (t > 1f) 1f else t) * N
        val i = min(N - 1, x.toInt())
        val fr = x - i
        return ratio[i] + (ratio[i + 1] - ratio[i]) * fr
    }

    companion object {
        private const val N = 1024
        private const val EPS = 1e-4f

        /** Profile of the forward mapping [f]. */
        fun of(f: (Float) -> Float): RadialProfile {
            val r = FloatArray(N + 1)
            for (i in 1..N) {
                val t = i.toFloat() / N
                r[i] = f(t) / t
            }
            r[0] = f(EPS) / EPS
            return RadialProfile(sanitize(r))
        }

        /** Profile of the inverse of the monotonic mapping [f] (tabulated and inverted numerically). */
        fun inverseOf(f: (Float) -> Float): RadialProfile {
            val m = N * 8
            val ft = FloatArray(m + 1) { f(it.toFloat() / m) }
            val r = FloatArray(N + 1)
            var j = 0
            for (i in 1..N) {
                val u = i.toFloat() / N
                while (j < m - 1 && ft[j + 1] < u) j++
                val a = ft[j]; val b = ft[j + 1]
                val frac = if (b > a) ((u - a) / (b - a)).coerceIn(0f, 1f) else 0f
                r[i] = ((j + frac) / m) / u
            }
            r[0] = EPS / max(1e-9f, f(EPS))
            return RadialProfile(sanitize(r))
        }

        private fun sanitize(r: FloatArray): FloatArray {
            for (i in r.indices) if (r[i].isNaN() || r[i].isInfinite()) r[i] = 1f
            return r
        }
    }
}

/** A 1-periodic waveform tabulated for fast evaluation: value(phaseTurns) with period 1. */
internal class PeriodicLut(f: (Float) -> Float) {
    private val table = FloatArray(N + 1) { f(it.toFloat() / N) }

    /** Value at [turns] (1 turn = one full period); any finite value is accepted. */
    fun at(turns: Float): Float {
        val fr = turns - floor(turns)
        val x = fr * N
        val i = min(N - 1, max(0, x.toInt()))
        val t = x - i
        return table[i] + (table[i + 1] - table[i]) * t
    }

    companion object {
        private const val N = 2048
        val SINE = PeriodicLut { sin(it * DistortMath.TWO_PI) }
    }
}
