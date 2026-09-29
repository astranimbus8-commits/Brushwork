package com.brushwork.paint.segmentation

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import kotlin.math.abs
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
     * Fast guided filter output at full resolution: bilinearly upsamples the coefficient planes
     * [meanA]/[meanB] (computed at [w]x[h], see [GuidedFilter.coefficients]) to the size of
     * [image] and evaluates `q = a·I + b` with the full-resolution luma of [image] as guide.
     * Only the returned array is allocated at full size. Values are clamped to 0..1.
     */
    fun guidedUpsample(image: PixelBuffer, meanA: FloatArray, meanB: FloatArray, w: Int, h: Int): FloatArray {
        require(meanA.size == w * h && meanB.size == w * h)
        val fw = image.width; val fh = image.height
        val px = image.pixels
        val out = FloatArray(fw * fh)
        if (fw == w && fh == h) {
            Parallel.forRange(out.size, 4096) { s, e ->
                for (i in s until e) out[i] = clamp01(meanA[i] * luma01(px[i]) + meanB[i])
            }
            return out
        }
        val xi = IntArray(fw); val xt = FloatArray(fw)
        axisTable(w, fw, xi, xt)
        Parallel.forRows(fh) { y0, y1 ->
            for (y in y0 until y1) {
                val fy = ((y + 0.5f) * h / fh - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val sy0 = min(fy.toInt(), h - 1)
                val sy1 = min(sy0 + 1, h - 1)
                val ty = fy - sy0
                val r0 = sy0 * w; val r1 = sy1 * w
                val row = y * fw
                for (x in 0 until fw) {
                    val sx0 = xi[x]; val sx1 = min(sx0 + 1, w - 1); val tx = xt[x]
                    val aTop = meanA[r0 + sx0] + (meanA[r0 + sx1] - meanA[r0 + sx0]) * tx
                    val aBot = meanA[r1 + sx0] + (meanA[r1 + sx1] - meanA[r1 + sx0]) * tx
                    val bTop = meanB[r0 + sx0] + (meanB[r0 + sx1] - meanB[r0 + sx0]) * tx
                    val bBot = meanB[r1 + sx0] + (meanB[r1 + sx1] - meanB[r1 + sx0]) * tx
                    val a = aTop + (aBot - aTop) * ty
                    val b = bTop + (bBot - bTop) * ty
                    out[row + x] = clamp01(a * luma01(px[row + x]) + b)
                }
            }
        }
        return out
    }

    /**
     * Refines a coarse (upsampled) mask [m] of [img] inside a band of about [radius] px around its
     * boundary. Each band pixel is classified by its color distance to the LOCAL mean color of the
     * confidently-inside and confidently-outside pixels nearby (within 2·radius), which recovers
     * detail the coarse mask cannot represent (sky between branches, shorelines). [extra] is an
     * optional independent 0..1 estimate that can only ADD confidence inside the band (heuristic
     * fusion). Pixels outside the band keep [m]. Returns a new array.
     */
    fun refineBand(img: PixelBuffer, m: FloatArray, radius: Int, extra: FloatArray? = null): FloatArray {
        val w = img.width; val h = img.height; val n = w * h
        require(m.size == n && (extra == null || extra.size == n))
        val out = m.copyOf()
        if (radius <= 0) return out
        val tmp = FloatArray(n)
        val mb = boxMean(m, w, h, radius, FloatArray(n), tmp)
        // 1 = confidently inside, 2 = confidently outside (no opposite pixel within radius), 0 = band.
        val state = ByteArray(n)
        var nb = 0
        for (i in 0 until n) {
            val v = mb[i]
            when {
                v >= BAND_HI -> state[i] = 1
                v <= BAND_LO -> state[i] = 2
                else -> nb++
            }
        }
        if (nb == 0) return out
        val bandIdx = IntArray(nb)
        val bandBeta = FloatArray(nb)
        run {
            var k = 0
            for (i in 0 until n) {
                if (state[i].toInt() == 0) { bandIdx[k] = i; bandBeta[k] = 1f - abs(2f * mb[i] - 1f); k++ }
            }
        }
        val px = img.pixels
        val r2 = radius * 2
        // Local mean colors of confident-inside (pass 0) and confident-outside (pass 1) pixels.
        val means = Array(2) { FloatArray(nb * 4) } // weight, r, g, b per band pixel
        val wPlane = mb // reused: the band information has been extracted
        val rPlane = FloatArray(n); val gPlane = FloatArray(n); val bPlane = FloatArray(n)
        for (pass in 0..1) {
            val want: Byte = if (pass == 0) 1 else 2
            for (i in 0 until n) {
                if (state[i] == want) {
                    val c = px[i]
                    wPlane[i] = 1f
                    rPlane[i] = ((c shr 16) and 0xFF) / 255f
                    gPlane[i] = ((c shr 8) and 0xFF) / 255f
                    bPlane[i] = (c and 0xFF) / 255f
                } else {
                    wPlane[i] = 0f; rPlane[i] = 0f; gPlane[i] = 0f; bPlane[i] = 0f
                }
            }
            boxMean(wPlane, w, h, r2, wPlane, tmp)
            boxMean(rPlane, w, h, r2, rPlane, tmp)
            boxMean(gPlane, w, h, r2, gPlane, tmp)
            boxMean(bPlane, w, h, r2, bPlane, tmp)
            val dstMeans = means[pass]
            for (k in 0 until nb) {
                val i = bandIdx[k]
                val wt = wPlane[i]
                dstMeans[k * 4] = wt
                if (wt > 1e-6f) {
                    dstMeans[k * 4 + 1] = rPlane[i] / wt
                    dstMeans[k * 4 + 2] = gPlane[i] / wt
                    dstMeans[k * 4 + 3] = bPlane[i] / wt
                }
            }
        }
        val ins = means[0]; val outs = means[1]
        for (k in 0 until nb) {
            val i = bandIdx[k]
            var a = m[i]
            // Trust the color decision by position in the band (the coarse boundary is most likely
            // wrong at its center) and by how clearly the color matches one side.
            var weight = bandBeta[k]
            if (ins[k * 4] > 1e-4f && outs[k * 4] > 1e-4f) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                val di = sq(r - ins[k * 4 + 1]) + sq(g - ins[k * 4 + 2]) + sq(b - ins[k * 4 + 3])
                val dout = sq(r - outs[k * 4 + 1]) + sq(g - outs[k * 4 + 2]) + sq(b - outs[k * 4 + 3])
                val ratio = dout / (di + dout + 1e-6f)
                // Only distinct local colors are informative: when both sides look alike the
                // ratio is noise and the coarse mask is kept.
                val contrast = smoothstep(0.004f, 0.03f, sq(ins[k * 4 + 1] - outs[k * 4 + 1]) +
                    sq(ins[k * 4 + 2] - outs[k * 4 + 2]) + sq(ins[k * 4 + 3] - outs[k * 4 + 3]))
                a = m[i] + (smoothstep(0.2f, 0.8f, ratio) - m[i]) * contrast
                weight = max(weight, abs(2f * ratio - 1f) * contrast)
            }
            if (extra != null && extra[i] > a) {
                weight = max(weight, extra[i])
                a = extra[i]
            }
            out[i] = clamp01((1f - weight) * m[i] + weight * a)
        }
        return out
    }

    private const val BAND_LO = 0.02f
    private const val BAND_HI = 0.98f

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

    private fun sq(v: Float) = v * v

    /** 64-bit content hash of an image (dimensions + every pixel), used as a cache key. */
    fun contentHash(image: PixelBuffer): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
        hash = (hash xor image.width.toLong()) * 0x100000001b3L
        hash = (hash xor image.height.toLong()) * 0x100000001b3L
        for (c in image.pixels) hash = (hash xor (c.toLong() and 0xFFFFFFFFL)) * 0x100000001b3L
        return hash
    }
}
