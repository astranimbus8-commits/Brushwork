package com.brushwork.paint.segmentation

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin raster helpers used by the segmentation pipeline (no android imports, so everything
 * here is unit-tested on the JVM). Masks are row-major [FloatArray]s of confidences 0..1.
 *
 * Segmentation treats transparency as white paper: every function that reads colors flattens
 * NON-premultiplied pixels over white first.
 */
object MaskOps {

    /** [c] (non-premultiplied ARGB) composited over opaque white. */
    fun flattenOverWhite(c: Int): Int {
        val a = c ushr 24
        if (a == 255) return c
        if (a == 0) return -1
        val inv = 255 - a
        val r = (((c shr 16) and 0xFF) * a + 255 * inv + 127) / 255
        val g = (((c shr 8) and 0xFF) * a + 255 * inv + 127) / 255
        val b = ((c and 0xFF) * a + 255 * inv + 127) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Rec.601 luma of [c] flattened over white, 0..1. */
    fun luma01(c: Int): Float {
        val a = c ushr 24
        val l = (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
        if (a == 255) return l
        val k = a / 255f
        return l * k + (1f - k)
    }

    /** Luma plane (0..1) of [src], flattened over white. */
    fun luminance(src: PixelBuffer): FloatArray {
        val out = FloatArray(src.size)
        val p = src.pixels
        Parallel.forRange(out.size, 4096) { s, e -> for (i in s until e) out[i] = luma01(p[i]) }
        return out
    }

    /** Largest size with the aspect of [w]x[h] whose long side is at most [maxSide] (never enlarges). */
    fun fitWithin(w: Int, h: Int, maxSide: Int): IntArray {
        val long = max(w, h)
        if (long <= maxSide) return intArrayOf(w, h)
        val s = maxSide.toDouble() / long
        return intArrayOf(max(1, min(maxSide, (w * s).roundToInt())), max(1, min(maxSide, (h * s).roundToInt())))
    }

    /**
     * Resamples [src] to [dw]x[dh] with an area average (box footprint; degenerates to nearest
     * neighbour when enlarging) and flattens alpha over white. The result is always opaque.
     */
    fun resample(src: PixelBuffer, dw: Int, dh: Int): PixelBuffer {
        require(dw > 0 && dh > 0)
        val sw = src.width; val sh = src.height
        val sp = src.pixels
        val out = PixelBuffer(dw, dh)
        val dp = out.pixels
        if (sw == dw && sh == dh) {
            Parallel.forRange(dp.size, 4096) { s, e -> for (i in s until e) dp[i] = flattenOverWhite(sp[i]) }
            return out
        }
        val x0 = IntArray(dw); val x1 = IntArray(dw)
        for (x in 0 until dw) {
            val a = (x.toLong() * sw / dw).toInt()
            x0[x] = min(a, sw - 1)
            x1[x] = min(sw, max(x0[x] + 1, ((x + 1).toLong() * sw / dw).toInt()))
        }
        Parallel.forRows(dh) { ya, yb ->
            for (y in ya until yb) {
                val sy0 = min((y.toLong() * sh / dh).toInt(), sh - 1)
                val sy1 = min(sh, max(sy0 + 1, ((y + 1).toLong() * sh / dh).toInt()))
                val drow = y * dw
                for (x in 0 until dw) {
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (sy in sy0 until sy1) {
                        val srow = sy * sw
                        for (sx in x0[x] until x1[x]) {
                            val c = flattenOverWhite(sp[srow + sx])
                            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                            n++
                        }
                    }
                    val h = n / 2
                    dp[drow + x] = (0xFF shl 24) or (((r + h) / n) shl 16) or (((g + h) / n) shl 8) or ((b + h) / n)
                }
            }
        }
        return out
    }

    /**
     * Mean over the (2r+1)² window around each pixel, normalized by the number of pixels that are
     * inside the image (no edge replication). O(n) regardless of [r]; double accumulators.
     * [dst] may be the same array as [src]; [tmp] must be distinct from both.
     */
    fun boxMean(
        src: FloatArray, w: Int, h: Int, r: Int,
        dst: FloatArray = FloatArray(w * h), tmp: FloatArray = FloatArray(w * h),
    ): FloatArray {
        require(src.size == w * h && dst.size == w * h && tmp.size == w * h)
        require(tmp !== src && tmp !== dst)
        if (r <= 0) {
            if (dst !== src) System.arraycopy(src, 0, dst, 0, src.size)
            return dst
        }
        Parallel.forRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var acc = 0.0
                for (x in 0..min(r, w - 1)) acc += src[row + x]
                for (x in 0 until w) {
                    val lo = x - r; val hi = x + r
                    tmp[row + x] = (acc / (min(hi, w - 1) - max(lo, 0) + 1)).toFloat()
                    if (hi + 1 < w) acc += src[row + hi + 1]
                    if (lo >= 0) acc -= src[row + lo]
                }
            }
        }
        Parallel.forRange(w, 32) { x0, x1 ->
            val acc = DoubleArray(x1 - x0)
            for (y in 0..min(r, h - 1)) {
                val row = y * w
                for (x in x0 until x1) acc[x - x0] += tmp[row + x]
            }
            for (y in 0 until h) {
                val lo = y - r; val hi = y + r
                val inv = 1.0 / (min(hi, h - 1) - max(lo, 0) + 1)
                val row = y * w
                for (x in x0 until x1) dst[row + x] = (acc[x - x0] * inv).toFloat()
                if (hi + 1 < h) {
                    val add = (hi + 1) * w
                    for (x in x0 until x1) acc[x - x0] += tmp[add + x]
                }
                if (lo >= 0) {
                    val sub = lo * w
                    for (x in x0 until x1) acc[x - x0] -= tmp[sub + x]
                }
            }
        }
        return dst
    }

    /** Center-aligned bilinear resize of a float plane (clamped at the edges). */
    fun resizeBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        require(src.size == sw * sh)
        if (sw == dw && sh == dh) return src.copyOf()
        val out = FloatArray(dw * dh)
        val xi = IntArray(dw); val xt = FloatArray(dw)
        axisTable(sw, dw, xi, xt)
        Parallel.forRows(dh) { y0, y1 ->
            for (y in y0 until y1) {
                val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                val sy0 = min(fy.toInt(), sh - 1)
                val sy1 = min(sy0 + 1, sh - 1)
                val ty = fy - sy0
                val r0 = sy0 * sw; val r1 = sy1 * sw
                val drow = y * dw
                for (x in 0 until dw) {
                    val sx0 = xi[x]; val sx1 = min(sx0 + 1, sw - 1); val tx = xt[x]
                    val top = src[r0 + sx0] + (src[r0 + sx1] - src[r0 + sx0]) * tx
                    val bot = src[r1 + sx0] + (src[r1 + sx1] - src[r1 + sx0]) * tx
                    out[drow + x] = top + (bot - top) * ty
                }
            }
        }
        return out
    }

    /** Source index (floor) and fraction for a center-aligned mapping of [dn] samples onto [sn]. */
    private fun axisTable(sn: Int, dn: Int, idx: IntArray, frac: FloatArray) {
        for (d in 0 until dn) {
            val f = ((d + 0.5f) * sn / dn - 0.5f).coerceIn(0f, (sn - 1).toFloat())
            val i = min(f.toInt(), sn - 1)
            idx[d] = i
            frac[d] = f - i
        }
    }

    /**
     * Like [resample] (area average, alpha flattened over white) when shrinking, but bilinear
     * when enlarging, so an enlarged model input has no nearest-neighbour blocks.
     */
    fun resampleSmooth(src: PixelBuffer, dw: Int, dh: Int): PixelBuffer {
        require(dw > 0 && dh > 0)
        val sw = src.width; val sh = src.height
        if (dw <= sw && dh <= sh) return resample(src, dw, dh)
        // Shrink the axis that shrinks first (area), then enlarge bilinearly.
        val mid = if (dw < sw || dh < sh) resample(src, min(sw, dw), min(sh, dh)) else resample(src, sw, sh)
        val mw = mid.width; val mh = mid.height
        val mp = mid.pixels
        val out = PixelBuffer(dw, dh)
        val xi = IntArray(dw); val xt = FloatArray(dw)
        axisTable(mw, dw, xi, xt)
        Parallel.forRows(dh) { y0, y1 ->
            for (y in y0 until y1) {
                val fy = ((y + 0.5f) * mh / dh - 0.5f).coerceIn(0f, (mh - 1).toFloat())
                val r0 = min(fy.toInt(), mh - 1)
                val r1 = min(r0 + 1, mh - 1)
                val ty = fy - r0
                for (x in 0 until dw) {
                    val x0 = xi[x]; val x1 = min(x0 + 1, mw - 1); val tx = xt[x]
                    out.pixels[y * dw + x] = lerpColor(mp[r0 * mw + x0], mp[r0 * mw + x1], mp[r1 * mw + x0], mp[r1 * mw + x1], tx, ty)
                }
            }
        }
        return out
    }

    private fun lerpColor(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Int {
        fun ch(shift: Int): Int {
            val a = (c00 shr shift) and 0xFF; val b = (c10 shr shift) and 0xFF
            val c = (c01 shr shift) and 0xFF; val d = (c11 shr shift) and 0xFF
            val top = a + (b - a) * tx; val bot = c + (d - c) * tx
            return (top + (bot - top) * ty + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * The [x0, x1) x [y0, y1) part of [src] (which may extend past the image: outside pixels
     * repeat the nearest edge pixel), flattened over white.
     */
    fun crop(src: PixelBuffer, x0: Int, y0: Int, x1: Int, y1: Int): PixelBuffer {
        require(x1 > x0 && y1 > y0)
        val w = x1 - x0; val h = y1 - y0
        val out = PixelBuffer(w, h)
        val sp = src.pixels
        Parallel.forRows(h) { ya, yb ->
            for (y in ya until yb) {
                val sy = (y0 + y).coerceIn(0, src.height - 1) * src.width
                for (x in 0 until w) out.pixels[y * w + x] = flattenOverWhite(sp[sy + (x0 + x).coerceIn(0, src.width - 1)])
            }
        }
        return out
    }

    /** The [x0, x1) x [y0, y1) window of a [w]-wide plane (the window must lie inside it). */
    fun cropPlane(src: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        val cw = x1 - x0
        val out = FloatArray(cw * (y1 - y0))
        for (y in y0 until y1) System.arraycopy(src, y * w + x0, out, (y - y0) * cw, cw)
        return out
    }

    /**
     * Area-average downscale (or bilinear enlargement) of a float plane; center-aligned, like
     * [resizeBilinear] when enlarging.
     */
    fun resizeArea(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        require(src.size == sw * sh)
        if (dw > sw || dh > sh) return resizeBilinear(src, sw, sh, dw, dh)
        if (dw == sw && dh == sh) return src.copyOf()
        val out = FloatArray(dw * dh)
        val x0 = IntArray(dw); val x1 = IntArray(dw)
        for (x in 0 until dw) {
            x0[x] = min((x.toLong() * sw / dw).toInt(), sw - 1)
            x1[x] = min(sw, max(x0[x] + 1, ((x + 1).toLong() * sw / dw).toInt()))
        }
        Parallel.forRows(dh) { ya, yb ->
            for (y in ya until yb) {
                val sy0 = min((y.toLong() * sh / dh).toInt(), sh - 1)
                val sy1 = min(sh, max(sy0 + 1, ((y + 1).toLong() * sh / dh).toInt()))
                for (x in 0 until dw) {
                    var s = 0f; var n = 0
                    for (sy in sy0 until sy1) {
                        val row = sy * sw
                        for (sx in x0[x] until x1[x]) { s += src[row + sx]; n++ }
                    }
                    out[y * dw + x] = s / n
                }
            }
        }
        return out
    }

    /** Pointwise maximum of two planes (new array). */
    fun pointwiseMax(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == b.size)
        return FloatArray(a.size) { if (a[it] >= b[it]) a[it] else b[it] }
    }

    /** Largest value of [m] (0 for an empty array). */
    fun maxValue(m: FloatArray): Float {
        var best = 0f
        for (v in m) if (v > best) best = v
        return best
    }

    /** [v] clamped to 0..1; NaN becomes 0 so a bad model value cannot poison later filtering. */
    fun clamp01(v: Float): Float = if (v >= 0f) (if (v <= 1f) v else 1f) else 0f

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3f - 2f * t)
    }

    /**
     * 64-bit content hash of an image (dimensions + every pixel as segmentation sees it, i.e.
     * flattened over white), used as a cache key. A canvas flattened over transparency (object
     * select) and the same canvas flattened over white (smart select) share one analysis.
     */
    fun contentHash(image: PixelBuffer): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
        hash = (hash xor image.width.toLong()) * 0x100000001b3L
        hash = (hash xor image.height.toLong()) * 0x100000001b3L
        for (c in image.pixels) {
            val f = if (c ushr 24 == 255) c else flattenOverWhite(c)
            hash = (hash xor (f.toLong() and 0xFFFFFFFFL)) * 0x100000001b3L
        }
        return hash
    }
}
