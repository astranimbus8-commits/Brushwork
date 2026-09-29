package com.brushwork.paint.filters.style

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared machinery of the Style filters: content cropping, a sub-pixel signed distance field of the
 * layer shape, O(n) plane blurs, height-field domes, lighting vectors and non-premultiplied
 * compositing of solid effect colors. Pure Kotlin, thread-safe.
 *
 * Plane convention: float planes are row-major `w * h` with pixel centres at integer coordinates.
 */
internal object StyleMath {

    /** Distance reported when the image contains no shape at all (or no background at all). */
    const val FAR = 1.0e7f

    private const val INF = Int.MAX_VALUE
    private const val NO_OFFSET = Float.MAX_VALUE

    // ------------------------------------------------------------------ bounds & cropping

    /** Tight bounds `[left, top, right, bottom)` of pixels with alpha > 0, or null if none. */
    fun contentBounds(src: PixelBuffer): IntArray? {
        val w = src.width; val h = src.height; val p = src.pixels
        val left = IntArray(h) { w }
        val right = IntArray(h) { -1 }
        Parallel.forRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var l = 0
                while (l < w && p[row + l] ushr 24 == 0) l++
                if (l == w) continue
                var r = w - 1
                while (p[row + r] ushr 24 == 0) r--
                left[y] = l; right[y] = r
            }
        }
        var l = w; var t = -1; var r = -1; var b = -1
        for (y in 0 until h) {
            if (right[y] < 0) continue
            if (t < 0) t = y
            b = y
            l = min(l, left[y]); r = max(r, right[y])
        }
        return if (t < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /**
     * Runs [block] on the sub-image `[l, t, r, b)` (clamped to the image) and pastes its result into
     * a copy of [src]; pixels outside the rectangle are copied unchanged. The block receives the
     * sub-image and its offset (left, top) inside [src]. [block] must not mutate its argument (it
     * may be [src] itself when the rectangle covers the whole image).
     */
    inline fun cropped(
        src: PixelBuffer, l0: Int, t0: Int, r0: Int, b0: Int,
        block: (img: PixelBuffer, offX: Int, offY: Int) -> PixelBuffer,
    ): PixelBuffer {
        val l = max(0, l0); val t = max(0, t0)
        val r = min(src.width, r0); val b = min(src.height, b0)
        if (r <= l || b <= t) return src.copy()
        if (l == 0 && t == 0 && r == src.width && b == src.height) return block(src, 0, 0)
        val cw = r - l; val ch = b - t; val sw = src.width
        val sub = PixelBuffer(cw, ch)
        for (y in 0 until ch) System.arraycopy(src.pixels, (t + y) * sw + l, sub.pixels, y * cw, cw)
        val res = block(sub, l, t)
        val out = src.copy()
        for (y in 0 until ch) System.arraycopy(res.pixels, y * cw, out.pixels, (t + y) * sw + l, cw)
        return out
    }

    /** [cropped] to the content bounds grown by [margin] px. Fully transparent images are just copied. */
    inline fun aroundContent(
        src: PixelBuffer, margin: Int,
        block: (img: PixelBuffer, offX: Int, offY: Int) -> PixelBuffer,
    ): PixelBuffer {
        val bb = contentBounds(src) ?: return src.copy()
        val m = margin.coerceIn(0, 1 shl 20)
        return cropped(src, bb[0] - m, bb[1] - m, bb[2] + m, bb[3] + m, block)
    }

    /** Integer pixel margin for an effect reaching [reach] px (already scaled) beyond the content. */
    fun margin(reach: Float): Int = if (reach.isNaN()) 2 else (min(reach, 1.0e6f) + 3f).toInt()

    // ------------------------------------------------------------------ planes

    /** Largest alpha (0..255) in the image. */
    fun maxAlpha(src: PixelBuffer): Int {
        var m = 0
        for (c in src.pixels) { val a = c ushr 24; if (a > m) { m = a; if (m == 255) break } }
        return m
    }

    /**
     * Alpha as 0..1 floats; with [normalized] it is divided by the image's maximum alpha so that a
     * uniformly translucent layer still reads as a solid shape.
     */
    fun alphaPlane(src: PixelBuffer, ctx: FilterContext, normalized: Boolean = false): FloatArray {
        val p = src.pixels
        val out = FloatArray(p.size)
        val m = if (normalized) maxAlpha(src) else 255
        if (m == 0) return out
        val scale = 1f / m
        Parallel.forRows(src.height) { y0, y1 ->
            ctx.checkCancelled()
            for (i in y0 * src.width until y1 * src.width) out[i] = min(1f, (p[i] ushr 24) * scale)
        }
        return out
    }

    /** Coverage 0..1 of "within [radius] px of the shape" with a soft edge of [soft] px (0 = hard). */
    fun coverageWithin(sd: Float, radius: Float, soft: Float): Float = ramp(radius - sd, soft)

    /** Maps a signed margin [v] (positive = inside the covered side) to coverage with a [soft] px ramp. */
    fun ramp(v: Float, soft: Float): Float =
        if (soft <= 0f) (if (v >= 0f) 1f else 0f) else (v / soft + 0.5f).coerceIn(0f, 1f)

    /**
     * Anti-aliasing ramp width in buffer pixels for a user value [aaFull] (full-resolution px):
     * 0 means hard edges; otherwise never sharper than half a pixel.
     */
    fun softness(aaFull: Float, ctx: FilterContext): Float =
        if (aaFull <= 0f) 0f else max(ctx.px(aaFull), min(aaFull, 1f) * 0.75f).coerceAtLeast(0.5f)

    // ------------------------------------------------------------------ signed distance field

    /**
     * Signed distance in pixels from each pixel centre to the shape edge, negative inside and
     * positive outside. The edge is the 50 % contour of the alpha channel normalized by the image's
     * maximum alpha (so a layer painted at 40 % opacity still has a solid shape).
     *
     * Anti-aliased distance transform in the spirit of Gustavson & Strand (2011): every pixel next to
     * the 50 % crossing estimates the sub-pixel point where the contour passes (from its coverage and
     * the local alpha gradient), an exact Euclidean nearest-feature transform (Felzenszwalb-Huttenlocher,
     * O(n)) finds the closest such edge pixel for every pixel, and the distance is measured to that
     * pixel's edge point. The result is accurate to a small fraction of a pixel and its gradient is a
     * smooth normal, so iso-lines and lit height fields built from it have no streaks. Returns [FAR]
     * everywhere for an empty image and `-FAR` everywhere when nothing is outside the shape.
     */
    fun signedDistance(src: PixelBuffer, ctx: FilterContext): FloatArray {
        val w = src.width; val h = src.height; val px = src.pixels
        val out = FloatArray(w * h)
        val aMax = maxAlpha(src)
        if (aMax == 0) { out.fill(FAR); return out }
        val an = FloatArray(256) { min(1f, it.toFloat() / aMax) }
        val alphaIn = BooleanArray(256) { an[it] >= 0.5f }
        val partial = BooleanArray(256) { an[it] > 0f && an[it] < 1f }

        // Edge pixels: a 4-neighbour lies on the other side of the 50 % contour. Partially covered
        // pixels locate the contour precisely. Saturated coverage only bounds the distance, so a
        // fully covered pixel counts only across a hard (aliased) step, and an empty one never.
        // Each edge pixel stores its packed sub-pixel edge point (0 = not an edge pixel).
        val edge = ShortArray(w * h)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val a = px[i] ushr 24
                    if ((x > 0 && crosses(a, px[i - 1] ushr 24, alphaIn, partial)) ||
                        (x < w - 1 && crosses(a, px[i + 1] ushr 24, alphaIn, partial)) ||
                        (y > 0 && crosses(a, px[i - w] ushr 24, alphaIn, partial)) ||
                        (y < h - 1 && crosses(a, px[i + w] ushr 24, alphaIn, partial))
                    ) edge[i] = packedEdgePoint(px, an, w, h, x, y)
                }
            }
        }

        // Column pass: signed row offset to the nearest edge pixel in the same column.
        Parallel.forRange(w, 16) { x0, x1 ->
            ctx.checkCancelled()
            val n = x1 - x0
            val last = IntArray(n) { -1 }
            for (y in 0 until h) {
                val row = y * w + x0
                for (i in 0 until n) {
                    if (edge[row + i].toInt() != 0) last[i] = y
                    out[row + i] = if (last[i] >= 0) (last[i] - y).toFloat() else NO_OFFSET
                }
            }
            last.fill(-1)
            for (y in h - 1 downTo 0) {
                val row = y * w + x0
                for (i in 0 until n) {
                    if (edge[row + i].toInt() != 0) last[i] = y
                    if (last[i] >= 0) {
                        val cand = (last[i] - y).toFloat()
                        val cur = out[row + i]
                        if (cur == NO_OFFSET || cand < -cur) out[row + i] = cand
                    }
                }
            }
        }

        // Row pass: exact 2-D nearest edge pixel (by centre) via lower envelopes. Along a stair-stepped
        // contour that pixel can sit a pixel or two away from the true closest point, so the edge
        // points of all edge pixels in its 3x3 block are candidates and the closest one wins.
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val off = IntArray(w)
            val f = IntArray(w); val arg = IntArray(w)
            val v = IntArray(w); val z = DoubleArray(w + 1)
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val o = out[row + x]
                    if (o == NO_OFFSET) { f[x] = INF; off[x] = 0 } else { val k = o.toInt(); off[x] = k; f[x] = k * k }
                }
                if (!lowerEnvelope(f, w, v, z, arg)) {
                    // No edge anywhere: everything is on one side of the contour.
                    for (x in 0 until w) out[row + x] = if (alphaIn[px[row + x] ushr 24]) -FAR else FAR
                    continue
                }
                for (x in 0 until w) {
                    val qx = arg[x]
                    val qy = y + off[qx]
                    val ins = alphaIn[px[row + x] ushr 24]
                    var best = Float.MAX_VALUE
                    for (j in max(0, qy - 1)..min(h - 1, qy + 1)) {
                        for (i in max(0, qx - 1)..min(w - 1, qx + 1)) {
                            val e = edge[j * w + i].toInt() and 0xFFFF
                            if (e == 0) continue
                            val hi = e ushr 8; val lo = e and 0xFF
                            val d2: Float
                            if (hi == NO_GRADIENT) {
                                // Edge |0.5 - coverage| away along the line between the pixels.
                                val s = lo / 510f
                                val dx = (x - i).toFloat(); val dy = (y - j).toFloat()
                                val dist = sqrt(dx * dx + dy * dy)
                                val d = if (alphaIn[px[j * w + i] ushr 24] == ins) dist + s else max(0f, dist - s)
                                d2 = d * d
                            } else {
                                val dx = x - (i + (hi - 128) * INV_OFFSET_SCALE)
                                val dy = y - (j + (lo - 128) * INV_OFFSET_SCALE)
                                d2 = dx * dx + dy * dy
                            }
                            if (d2 < best) best = d2
                        }
                    }
                    val d = sqrt(best)
                    out[row + x] = if (ins) -d else d
                }
            }
        }
        return out
    }

    /** True when a pixel of alpha [a] is an edge pixel with respect to its neighbour of alpha [b]. */
    private fun crosses(a: Int, b: Int, alphaIn: BooleanArray, partial: BooleanArray): Boolean =
        alphaIn[a] != alphaIn[b] && (partial[a] || (alphaIn[a] && !partial[b]))

    /** Packed-offset units per pixel (offsets stay within +-0.71 px, so +-121 around 128). */
    private const val OFFSET_SCALE = 170f
    private const val INV_OFFSET_SCALE = 1f / OFFSET_SCALE

    /** High byte marking an edge pixel without a usable alpha gradient. */
    private const val NO_GRADIENT = 1

    /**
     * The point where the 50 % contour passes closest to the centre of edge pixel ([x], [y]), packed
     * as `(dx * 170 + 128) shl 8 | (dy * 170 + 128)` (never 0). It lies along the alpha gradient at
     * the distance a straight edge of that orientation needs to produce the pixel's coverage
     * (box-filtered anti-aliasing). Pixels without a usable gradient (1-px lines, isolated dots)
     * store [NO_GRADIENT] and `|0.5 - coverage| * 510` instead.
     */
    private fun packedEdgePoint(px: IntArray, an: FloatArray, w: Int, h: Int, x: Int, y: Int): Short {
        val xl = if (x > 0) x - 1 else x; val xr = if (x < w - 1) x + 1 else x
        val yu = if (y > 0) y - 1 else y; val yd = if (y < h - 1) y + 1 else y
        val ru = yu * w; val rc = y * w; val rd = yd * w
        val a = an[px[rc + x] ushr 24]
        // Sobel gradient of the normalized alpha (points into the shape).
        val gx = (an[px[ru + xr] ushr 24] + 2f * an[px[rc + xr] ushr 24] + an[px[rd + xr] ushr 24]) -
            (an[px[ru + xl] ushr 24] + 2f * an[px[rc + xl] ushr 24] + an[px[rd + xl] ushr 24])
        val gy = (an[px[rd + xl] ushr 24] + 2f * an[px[rd + x] ushr 24] + an[px[rd + xr] ushr 24]) -
            (an[px[ru + xl] ushr 24] + 2f * an[px[ru + x] ushr 24] + an[px[ru + xr] ushr 24])
        val gl = sqrt(gx * gx + gy * gy)
        if (gl < 1e-3f) {
            val s = ColorUtils.clamp255(kotlin.math.abs(0.5f - a) * 510f)
            return ((NO_GRADIENT shl 8) or s).toShort()
        }
        val nx = gx / gl; val ny = gy / gl
        // Signed distance of the centre from the edge (positive = outside); the edge point is that
        // far along the inward normal.
        val s = coverageToDistance(nx, ny, a)
        val hi = (kotlin.math.round(nx * s * OFFSET_SCALE).toInt() + 128).coerceIn(NO_GRADIENT + 1, 255)
        val lo = (kotlin.math.round(ny * s * OFFSET_SCALE).toInt() + 128).coerceIn(0, 255)
        return ((hi shl 8) or lo).toShort()
    }

    /**
     * Signed distance (px, positive = outside) from a pixel centre to a straight edge with unit
     * normal ([nx], [ny]) that covers fraction [a] of the pixel (Gustavson's `edgedf`).
     */
    fun coverageToDistance(nx: Float, ny: Float, a: Float): Float {
        var gx = kotlin.math.abs(nx); var gy = kotlin.math.abs(ny)
        if (gx < gy) { val t = gx; gx = gy; gy = t }
        if (gy < 1e-4f) return 0.5f - a
        val a1 = 0.5f * gy / gx
        return when {
            a < a1 -> 0.5f * (gx + gy) - sqrt(2f * gx * gy * a)
            a < 1f - a1 -> (0.5f - a) * gx
            else -> -0.5f * (gx + gy) + sqrt(2f * gx * gy * (1f - a))
        }
    }

    /**
     * Plain Euclidean distance (px) from every pixel to the nearest pixel with `mask != 0`
     * ([FAR] when the mask is empty). O(n), one output plane.
     */
    fun distanceToMask(mask: ByteArray, w: Int, h: Int, ctx: FilterContext): FloatArray {
        val out = FloatArray(w * h)
        Parallel.forRange(w, 16) { x0, x1 ->
            ctx.checkCancelled()
            val n = x1 - x0
            val last = IntArray(n) { -1 }
            for (y in 0 until h) {
                val row = y * w + x0
                for (i in 0 until n) {
                    if (mask[row + i].toInt() != 0) last[i] = y
                    out[row + i] = if (last[i] >= 0) (y - last[i]).toFloat() else NO_OFFSET
                }
            }
            last.fill(-1)
            for (y in h - 1 downTo 0) {
                val row = y * w + x0
                for (i in 0 until n) {
                    if (mask[row + i].toInt() != 0) last[i] = y
                    if (last[i] >= 0) {
                        val cand = (last[i] - y).toFloat()
                        if (cand < out[row + i]) out[row + i] = cand
                    }
                }
            }
        }
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val f = IntArray(w); val arg = IntArray(w)
            val v = IntArray(w); val z = DoubleArray(w + 1)
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val o = out[row + x]
                    f[x] = if (o == NO_OFFSET) INF else { val k = o.toInt(); k * k }
                }
                if (!lowerEnvelope(f, w, v, z, arg)) {
                    for (x in 0 until w) out[row + x] = FAR
                    continue
                }
                for (x in 0 until w) {
                    val q = arg[x]; val dx = x - q
                    out[row + x] = sqrt((dx * dx).toFloat() + f[q].toFloat())
                }
            }
        }
        return out
    }

    /**
     * Lower envelope of the parabolas `(x - q)^2 + f[q]` over the finite entries of [f]; writes the
     * minimizing q for every x into [arg]. Returns false when every entry is [INF].
     */
    private fun lowerEnvelope(f: IntArray, n: Int, v: IntArray, z: DoubleArray, arg: IntArray): Boolean {
        var k = -1
        for (q in 0 until n) {
            val fq = f[q]
            if (fq == INF) continue
            if (k < 0) {
                k = 0; v[0] = q; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY
                continue
            }
            val gq = fq.toDouble() + q.toDouble() * q
            var s: Double
            while (true) {
                val p = v[k]
                s = (gq - (f[p].toDouble() + p.toDouble() * p)) / (2.0 * (q - p))
                if (s > z[k]) break
                k--
            }
            k++
            v[k] = q; z[k] = s; z[k + 1] = Double.POSITIVE_INFINITY
        }
        if (k < 0) return false
        var j = 0
        for (x in 0 until n) {
            while (z[j + 1] < x) j++
            arg[x] = v[j]
        }
        return true
    }

    // ------------------------------------------------------------------ blur

    /**
     * Gaussian blur of a plane IN PLACE (edges clamped). Small sigmas use an exact kernel, larger
     * ones three box passes (O(n) regardless of sigma). Allocates one temporary plane.
     */
    fun gaussianInPlace(plane: FloatArray, w: Int, h: Int, sigma: Float, ctx: FilterContext) {
        if (!(sigma >= 0.35f)) return
        val tmp = FloatArray(plane.size)
        if (sigma < 2.5f) {
            val k = FilterMath.gaussianKernel(sigma)
            convH(plane, tmp, w, h, k, ctx)
            convV(tmp, plane, w, h, k, ctx)
            return
        }
        val s = min(sigma, 1.0e5f)
        for (b in FilterMath.boxesForGauss(s, 3)) {
            val r = (b - 1) / 2
            if (r <= 0) continue
            boxH(plane, tmp, w, h, r, ctx)
            boxV(tmp, plane, w, h, r, ctx)
        }
    }

    private fun convH(src: FloatArray, dst: FloatArray, w: Int, h: Int, k: FloatArray, ctx: FilterContext) {
        val r = k.size / 2
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    var acc = 0f
                    for (j in -r..r) acc += src[row + (x + j).coerceIn(0, w - 1)] * k[j + r]
                    dst[row + x] = acc
                }
            }
        }
    }

    private fun convV(src: FloatArray, dst: FloatArray, w: Int, h: Int, k: FloatArray, ctx: FilterContext) {
        val r = k.size / 2
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val row = y * w
                for (j in -r..r) {
                    val sRow = (y + j).coerceIn(0, h - 1) * w
                    val kv = k[j + r]
                    if (j == -r) for (x in 0 until w) dst[row + x] = src[sRow + x] * kv
                    else for (x in 0 until w) dst[row + x] += src[sRow + x] * kv
                }
            }
        }
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, ctx: FilterContext) {
        val norm = 1.0 / (2 * r + 1)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val row = y * w
                var acc = 0.0
                for (j in -r..r) acc += src[row + j.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    dst[row + x] = (acc * norm).toFloat()
                    acc += src[row + min(w - 1, x + r + 1)] - src[row + max(0, x - r)]
                }
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, ctx: FilterContext) {
        val norm = 1.0 / (2 * r + 1)
        Parallel.forRange(w, 64) { x0, x1 ->
            ctx.checkCancelled()
            val n = x1 - x0
            val acc = DoubleArray(n)
            for (j in -r..r) {
                val base = j.coerceIn(0, h - 1) * w + x0
                for (i in 0 until n) acc[i] += src[base + i]
            }
            for (y in 0 until h) {
                val row = y * w + x0
                val add = min(h - 1, y + r + 1) * w + x0
                val sub = max(0, y - r) * w + x0
                for (i in 0 until n) {
                    dst[row + i] = (acc[i] * norm).toFloat()
                    acc[i] += src[add + i] - src[sub + i]
                }
            }
        }
    }

    // ------------------------------------------------------------------ sampling

    /** Bilinear sample of a plane at (x, y) (pixel centres at integers), edges clamped. */
    fun sample(plane: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xf = if (x > 0f) min(x, (w - 1).toFloat()) else 0f
        val yf = if (y > 0f) min(y, (h - 1).toFloat()) else 0f
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val tx = xf - x0; val ty = yf - y0
        val r0 = y0 * w; val r1 = y1 * w
        val top = plane[r0 + x0] + (plane[r0 + x1] - plane[r0 + x0]) * tx
        val bot = plane[r1 + x0] + (plane[r1 + x1] - plane[r1 + x0]) * tx
        return top + (bot - top) * ty
    }

    /** Bilinear sample that treats everything outside the plane as 0. */
    fun sampleZero(plane: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        if (!(x > -1f && y > -1f && x < w.toFloat() && y < h.toFloat())) return 0f
        val xf = kotlin.math.floor(x); val yf = kotlin.math.floor(y)
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val x1 = x0 + 1; val y1 = y0 + 1
        val tx = x - xf; val ty = y - yf
        val inX0 = x0 >= 0; val inX1 = x1 < w; val inY0 = y0 >= 0; val inY1 = y1 < h
        val c00 = if (inX0 && inY0) plane[y0 * w + x0] else 0f
        val c10 = if (inX1 && inY0) plane[y0 * w + x1] else 0f
        val c01 = if (inX0 && inY1) plane[y1 * w + x0] else 0f
        val c11 = if (inX1 && inY1) plane[y1 * w + x1] else 0f
        val top = c00 + (c10 - c00) * tx
        val bot = c01 + (c11 - c01) * tx
        return top + (bot - top) * ty
    }

    // ------------------------------------------------------------------ height fields & light

    /**
     * Turns a signed distance field (in place) into dome heights in pixels: every 8-connected inside
     * region becomes a rounded dome whose radius is the region's largest inside distance, so thin
     * strokes become thin tubes and big blobs big pillows. [flatness] 0..0.95 flattens the top into a
     * plateau; [heightScale] 1 gives a hemisphere. Outside pixels become 0.
     */
    fun domeHeights(sd: FloatArray, w: Int, h: Int, flatness: Float, heightScale: Float, ctx: FilterContext) {
        // The dome profile is steep near the rim and amplifies pixel-scale wobble of the distance
        // gradient along stair-stepped edges; a 1 px blur of the (locally linear) distance removes
        // it without moving the contour.
        gaussianInPlace(sd, w, h, 1f, ctx)
        val visited = 1.0e5f
        val half = visited * 0.5f
        // Inside distances are bounded by the image size (-FAR means "no background at all").
        val limit = min(half - 1f, (w + h).toFloat())
        for (i in sd.indices) { val v = sd[i]; sd[i] = if (!(v < 0f)) 0f else max(v, -limit) }
        val f = flatness.coerceIn(0f, 0.95f)
        val flat = 1f / (1f - f)
        val knee = min(0.5f, f)
        val stack = IntStack()
        for (i in sd.indices) {
            val v = sd[i]
            if (!(v < 0f && v > -half)) continue
            if (i % w == 0) ctx.checkCancelled()
            // Pass 1: mark the region and find its deepest point.
            var rm = 0f
            floodFill(i, w, h, stack, { sd[it] < 0f && sd[it] > -half }) { j ->
                val d = -sd[j]
                if (d > rm) rm = d
                sd[j] = -visited - d
            }
            val radius = max(rm, 1e-3f)
            // Pass 2: write heights.
            floodFill(i, w, h, stack, { sd[it] <= -half }) { j ->
                val d = -sd[j] - visited
                val t = (d / radius).coerceIn(0f, 1f)
                val u = 1f - t
                sd[j] = softMin(sqrt(max(0f, 1f - u * u)) * flat, 1f, knee) * radius * heightScale
            }
        }
    }

    /** Polynomial smooth minimum: like min(a, b) but rounded over a band of width [k] (0 = hard). */
    fun softMin(a: Float, b: Float, k: Float): Float {
        if (k <= 0f) return min(a, b)
        val hh = max(k - kotlin.math.abs(a - b), 0f) / k
        return min(a, b) - hh * hh * k * 0.25f
    }

    /** Growable int stack for flood fills. */
    class IntStack {
        var data = IntArray(256)
        var size = 0
        fun push(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun pop(): Int = data[--size]
        fun isEmpty() = size == 0
    }

    /** 8-connected scanline flood fill from [seed]; [visit] must make visited pixels fail [fillable]. */
    inline fun floodFill(seed: Int, w: Int, h: Int, stack: IntStack, fillable: (Int) -> Boolean, visit: (Int) -> Unit) {
        stack.size = 0
        stack.push(seed)
        while (!stack.isEmpty()) {
            val s = stack.pop()
            if (!fillable(s)) continue
            val y = s / w
            val row = y * w
            var l = s - row
            var r = l
            while (l > 0 && fillable(row + l - 1)) l--
            while (r < w - 1 && fillable(row + r + 1)) r++
            for (x in l..r) visit(row + x)
            val xs = max(0, l - 1); val xe = min(w - 1, r + 1)
            if (y > 0) {
                val nr = row - w
                var inRun = false
                for (x in xs..xe) {
                    if (fillable(nr + x)) { if (!inRun) { stack.push(nr + x); inRun = true } } else inRun = false
                }
            }
            if (y < h - 1) {
                val nr = row + w
                var inRun = false
                for (x in xs..xe) {
                    if (fillable(nr + x)) { if (!inRun) { stack.push(nr + x); inRun = true } } else inRun = false
                }
            }
        }
    }

    /**
     * Unit surface normal (nx, ny, nz; image coordinates, z toward the viewer) of height field [z]
     * at (x, y) from central differences, passed to [block] without allocating.
     */
    inline fun <R> withNormal(z: FloatArray, w: Int, h: Int, x: Int, y: Int, block: (nx: Float, ny: Float, nz: Float) -> R): R {
        val row = y * w
        val xl = if (x > 0) x - 1 else x; val xr = if (x < w - 1) x + 1 else x
        val yu = if (y > 0) y - 1 else y; val yd = if (y < h - 1) y + 1 else y
        val dx = (z[row + xr] - z[row + xl]) / max(1, xr - xl)
        val dy = (z[yd * w + x] - z[yu * w + x]) / max(1, yd - yu)
        val inv = 1f / sqrt(dx * dx + dy * dy + 1f)
        return block(-dx * inv, -dy * inv, inv)
    }

    /**
     * Self-shadowing of height field [z] (px) under a directional light with horizontal direction
     * ([lx], [ly]) (towards the light, image coordinates) and elevation slope [tanE]. Returns the
     * shadow amount 0..1 per pixel, with a penumbra of [soft] height px. O(n): a horizon sweep
     * from the side facing the light, one pixel line at a time.
     */
    fun shadowSweep(z: FloatArray, w: Int, h: Int, lx: Float, ly: Float, tanE: Float, soft: Float, ctx: FilterContext): FloatArray {
        val out = FloatArray(w * h)
        val len = sqrt(lx * lx + ly * ly)
        if (!(len > 1e-4f) || !(tanE < 1e3f)) return out
        val dx = lx / len; val dy = ly / len
        val majorX = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
        val nu = if (majorX) w else h
        val nv = if (majorX) h else w
        val du = if (majorX) dx else dy
        val dvPerStep = (if (majorX) dy else dx) / kotlin.math.abs(du)
        val drop = sqrt(1f + dvPerStep * dvPerStep) * tanE
        val towardLight = if (du > 0f) 1 else -1
        val low = -1.0e9f
        var prev = FloatArray(nv) { low }
        var cur = FloatArray(nv)
        val invSoft = 1f / max(soft, 1e-3f)
        // Start at the line nearest to the light; the previous line is one step towards it.
        for (step in 0 until nu) {
            if (step and 255 == 0) ctx.checkCancelled()
            val u = if (towardLight > 0) nu - 1 - step else step
            for (v in 0 until nv) {
                val pv = v + dvPerStep
                val v0 = kotlin.math.floor(pv).toInt()
                val f = pv - v0
                val a = if (v0 in 0 until nv) prev[v0] else low
                val b = if (v0 + 1 in 0 until nv) prev[v0 + 1] else low
                val horizon = a + (b - a) * f - drop
                val i = if (majorX) v * w + u else u * w + v
                val zz = z[i]
                out[i] = ((horizon - zz) * invSoft).coerceIn(0f, 1f)
                cur[v] = max(zz, horizon)
            }
            val t = prev; prev = cur; cur = t
        }
        return out
    }

    /**
     * Unit vector pointing TO a light at [angleDeg] (0 = right, 90 = top of the screen,
     * counter-clockwise) and [elevationDeg] above the canvas, in image coordinates (y down).
     */
    fun lightFromAngle(angleDeg: Float, elevationDeg: Float): FloatArray {
        val a = angleDeg * (PI.toFloat() / 180f)
        val e = elevationDeg.coerceIn(1f, 90f) * (PI.toFloat() / 180f)
        return floatArrayOf(cos(a) * cos(e), -sin(a) * cos(e), sin(e))
    }

    /** Unit 2-D direction (image coordinates, y down) for [angleDeg] (0 = right, 90 = up). */
    fun direction(angleDeg: Float): FloatArray {
        val a = angleDeg * (PI.toFloat() / 180f)
        return floatArrayOf(cos(a), -sin(a))
    }

    fun normalize3(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (!(l > 1e-6f)) { v[0] = 0f; v[1] = 0f; v[2] = 1f } else { v[0] /= l; v[1] /= l; v[2] /= l }
        return v
    }

    /** Positive power that tolerates non-positive bases. */
    fun ppow(b: Float, e: Float): Float = if (b <= 0f) 0f else b.pow(e)

    // ------------------------------------------------------------------ color space

    /** sRGB byte -> linear 0..1. */
    val srgbToLinear: FloatArray = FloatArray(256) {
        val c = it / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private const val ENC_STEPS = 4096
    private val linearToSrgbLut: IntArray = IntArray(ENC_STEPS + 1) {
        val l = it.toFloat() / ENC_STEPS
        val s = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
        ColorUtils.clamp255(s * 255f)
    }

    /** Linear 0..1 (clamped) -> sRGB byte. */
    fun linearToSrgb(l: Float): Int =
        if (!(l > 0f)) 0 else if (l >= 1f) 255 else linearToSrgbLut[(l * ENC_STEPS + 0.5f).toInt()]

    // ------------------------------------------------------------------ compositing

    /** ARGB from a 0..1 alpha and 0..255 float channels (clamped, rounded). */
    fun pack(a: Float, r: Float, g: Float, b: Float): Int {
        val ai = ColorUtils.clamp255(a * 255f)
        if (ai == 0) return 0
        return ColorUtils.argbUnchecked(ai, ColorUtils.clamp255(r), ColorUtils.clamp255(g), ColorUtils.clamp255(b))
    }

    /** The solid [color] at coverage [cov] (its own alpha included). */
    fun solid(color: Int, cov: Float): Int {
        val a = ColorUtils.clamp255(cov.coerceIn(0f, 1f) * (color ushr 24))
        return if (a == 0) 0 else (color and 0xFFFFFF) or (a shl 24)
    }

    /** Solid [color] at coverage [cov] composited BEHIND pixel [c] (source-over with [c] on top). */
    fun behind(c: Int, color: Int, cov: Float): Int {
        val ea = cov.coerceIn(0f, 1f) * (color ushr 24) / 255f
        if (ea <= 0f) return c
        val sa = (c ushr 24) / 255f
        if (sa >= 1f) return c
        val k = ea * (1f - sa)
        val oa = sa + k
        val inv = 1f / oa
        return pack(
            oa,
            (((c shr 16) and 0xFF) * sa + ((color shr 16) and 0xFF) * k) * inv,
            (((c shr 8) and 0xFF) * sa + ((color shr 8) and 0xFF) * k) * inv,
            ((c and 0xFF) * sa + (color and 0xFF) * k) * inv,
        )
    }

    /** Solid [color] at coverage [cov] composited OVER pixel [c]. */
    fun over(c: Int, color: Int, cov: Float): Int {
        val ea = cov.coerceIn(0f, 1f) * (color ushr 24) / 255f
        if (ea <= 0f) return c
        val sa = (c ushr 24) / 255f
        val k = sa * (1f - ea)
        val oa = ea + k
        if (oa <= 0f) return 0
        val inv = 1f / oa
        return pack(
            oa,
            (((color shr 16) and 0xFF) * ea + ((c shr 16) and 0xFF) * k) * inv,
            (((color shr 8) and 0xFF) * ea + ((c shr 8) and 0xFF) * k) * inv,
            ((color and 0xFF) * ea + (c and 0xFF) * k) * inv,
        )
    }

    /** Solid [color] at coverage [cov] painted onto [c] keeping [c]'s alpha (source-atop). */
    fun atop(c: Int, color: Int, cov: Float): Int {
        val ea = cov.coerceIn(0f, 1f) * (color ushr 24) / 255f
        if (ea <= 0f || c ushr 24 == 0) return c
        val k = 1f - ea
        return (c and 0xFF000000.toInt()) or
            (ColorUtils.clamp255(((c shr 16) and 0xFF) * k + ((color shr 16) and 0xFF) * ea) shl 16) or
            (ColorUtils.clamp255(((c shr 8) and 0xFF) * k + ((color shr 8) and 0xFF) * ea) shl 8) or
            ColorUtils.clamp255((c and 0xFF) * k + (color and 0xFF) * ea)
    }

    /**
     * Solid [color] at coverage [cov] composited over [c] with the Screen blend mode (W3C
     * compositing: where [c] is transparent the color is simply added on top).
     */
    fun screen(c: Int, color: Int, cov: Float): Int {
        val ea = cov.coerceIn(0f, 1f) * (color ushr 24) / 255f
        if (ea <= 0f) return c
        val sa = (c ushr 24) / 255f
        val oa = ea + sa - ea * sa
        val inv = 1f / oa
        fun ch(cs: Int, ce: Int): Float {
            val s = cs / 255f; val e = ce / 255f
            val blended = s + e - s * e
            return ((1f - ea) * sa * s + (1f - sa) * ea * e + sa * ea * blended) * inv * 255f
        }
        return pack(oa, ch((c shr 16) and 0xFF, (color shr 16) and 0xFF), ch((c shr 8) and 0xFF, (color shr 8) and 0xFF), ch(c and 0xFF, color and 0xFF))
    }

    // ------------------------------------------------------------------ shared params

    /** "Output" choice: index 0 = combine with the layer, 1 = effect only. */
    fun outputParam(combined: String, effectOnly: String) = FilterParam.Choice("output", "Output", listOf(combined, effectOnly), 0)

    fun opacityParam(default: Float = 100f) = FilterParam.Slider("opacity", "Opacity", 0f, 100f, default, 1f, "%")

    fun antialiasParam(default: Float = 1f) = FilterParam.Slider("antialias", "Anti-aliasing", 0f, 20f, default, 0f, pixels = true)

    /** 0..1 fraction from a percent slider. */
    fun percent(v: Float): Float = (v / 100f).let { if (it.isNaN()) 0f else it }

    /** Gaussian sigma for a user "blur radius" (same convention as FilterMath.blur). */
    fun sigmaForRadius(radius: Float): Float = radius / 2f
}
