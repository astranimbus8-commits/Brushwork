package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** sRGB (D65) <-> CIELAB with lookup tables. L is 0..100, a/b roughly -128..127. */
internal object Lab {
    private val toLinear = FloatArray(256) {
        val c = it / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
    private const val ENC = 4096
    private val encode = IntArray(ENC + 1) {
        val l = it.toDouble() / ENC
        val s = if (l <= 0.0031308) 12.92 * l else 1.055 * l.pow(1 / 2.4) - 0.055
        (s * 255.0).roundToInt().coerceIn(0, 255)
    }
    private const val XN = 0.95047f
    private const val ZN = 1.08883f
    private const val EPS = 216f / 24389f
    private const val KAPPA = 24389f / 27f

    /** Linear 0..1 to an 8-bit sRGB value (clamped). */
    fun encode(linear: Float): Int {
        if (!(linear > 0f)) return 0
        if (linear >= 1f) return 255
        return encode[(linear * ENC + 0.5f).toInt()]
    }

    private fun f(t: Float): Float = if (t > EPS) Math.cbrt(t.toDouble()).toFloat() else (KAPPA * t + 16f) / 116f

    /** Writes L, a, b of the 8-bit sRGB color (r8, g8, b8) into [out] at [o]. */
    fun fromRgb(r8: Int, g8: Int, b8: Int, out: FloatArray, o: Int = 0) {
        val r = toLinear[r8]; val g = toLinear[g8]; val b = toLinear[b8]
        val x = (0.4124564f * r + 0.3575761f * g + 0.1804375f * b) / XN
        val y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b
        val z = (0.0193339f * r + 0.1191920f * g + 0.9503041f * b) / ZN
        val fx = f(x); val fy = f(y); val fz = f(z)
        out[o] = 116f * fy - 16f
        out[o + 1] = 500f * (fx - fy)
        out[o + 2] = 200f * (fy - fz)
    }

    private fun finv(t: Float): Float = if (t > 6f / 29f) t * t * t else (116f * t - 16f) / KAPPA

    /** Lab to RGB bits (alpha byte 0) with clamping; allocation free. */
    fun toRgb(l: Float, a: Float, b: Float): Int {
        val fy = (l + 16f) / 116f
        val x = XN * finv(fy + a / 500f)
        val y = finv(fy)
        val z = ZN * finv(fy - b / 200f)
        val r = encode(3.2404542f * x - 1.5371385f * y - 0.4985314f * z)
        val g = encode(-0.9692660f * x + 1.8760108f * y + 0.0415560f * z)
        val bl = encode(0.0556434f * x - 0.2040259f * y + 1.0572252f * z)
        return (r shl 16) or (g shl 8) or bl
    }
}

/**
 * Planar float image used by the photo stylization filters: they do their heavy, spatially
 * wide work on a reduced "working" copy and then render at full resolution by sampling it.
 */
internal class Planes(val w: Int, val h: Int, count: Int) {
    val p: Array<FloatArray> = Array(count) { FloatArray(w * h) }
    operator fun get(i: Int): FloatArray = p[i]

    /** Bilinear sample of plane [i] at working-pixel coordinates (centers at +0.5), clamped. */
    fun sample(i: Int, fx: Float, fy: Float): Float = sample(p[i], w, h, fx, fy)

    companion object {
        fun sample(plane: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
            var x = fx - 0.5f; var y = fy - 0.5f
            if (x < 0f) x = 0f; if (y < 0f) y = 0f
            if (x > w - 1f) x = w - 1f; if (y > h - 1f) y = h - 1f
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = if (x0 + 1 < w) x0 + 1 else x0
            val y1 = if (y0 + 1 < h) y0 + 1 else y0
            val tx = x - x0; val ty = y - y0
            val r0 = y0 * w; val r1 = y1 * w
            val top = plane[r0 + x0] + (plane[r0 + x1] - plane[r0 + x0]) * tx
            val bot = plane[r1 + x0] + (plane[r1 + x1] - plane[r1 + x0]) * tx
            return top + (bot - top) * ty
        }
    }
}

internal object Stylize {
    /** Working size: the long side limited to [maxLong] (never enlarged). */
    fun workSize(w: Int, h: Int, maxLong: Int): IntArray {
        val long = max(w, h)
        if (long <= maxLong) return intArrayOf(w, h)
        val s = maxLong.toDouble() / long
        return intArrayOf(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
    }

    /**
     * Area-averages [src] down to ww x wh, weighting colors by alpha. Returns planes
     * [L, a, b, alpha(0..1)], or [L, alpha] when [chroma] is false. Transparent areas get
     * L = [emptyL] and a = b = 0.
     */
    fun downsampleLab(
        src: PixelBuffer, ww: Int, wh: Int, ctx: FilterContext, emptyL: Float = 100f, chroma: Boolean = true,
    ): Planes {
        val out = Planes(ww, wh, if (chroma) 4 else 2)
        val sw = src.width; val sh = src.height
        val px = src.pixels
        val fx = sw.toDouble() / ww; val fy = sh.toDouble() / wh
        val lp = out[0]
        val ap = if (chroma) out[1] else null
        val bp = if (chroma) out[2] else null
        val alp = out[out.p.size - 1]
        Parallel.forRows(wh) { j0, j1 ->
            ctx.checkCancelled()
            val lab = FloatArray(3)
            for (j in j0 until j1) {
                val sy0 = (j * fy).toInt(); val sy1 = max(sy0 + 1, min(sh, ((j + 1) * fy).toInt()))
                for (i in 0 until ww) {
                    val sx0 = (i * fx).toInt(); val sx1 = max(sx0 + 1, min(sw, ((i + 1) * fx).toInt()))
                    var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0
                    for (y in sy0 until sy1) {
                        val row = y * sw
                        for (x in sx0 until sx1) {
                            val c = px[row + x]
                            val a = c ushr 24
                            sa += a
                            sr += ((c shr 16) and 0xFF) * a
                            sg += ((c shr 8) and 0xFF) * a
                            sb += (c and 0xFF) * a
                            cnt++
                        }
                    }
                    val o = j * ww + i
                    if (sa <= 0L) {
                        lp[o] = emptyL; alp[o] = 0f
                        continue
                    }
                    Lab.fromRgb((sr / sa).toInt(), (sg / sa).toInt(), (sb / sa).toInt(), lab)
                    lp[o] = lab[0]
                    if (ap != null && bp != null) { ap[o] = lab[1]; bp[o] = lab[2] }
                    alp[o] = sa / (255f * cnt)
                }
            }
        }
        return out
    }

    /**
     * Gives fully transparent working pixels the colour of nearby visible content (normalized
     * gaussian convolution), so layer transparency does not create fake edges or bleed a
     * placeholder colour into the result. No-op for fully opaque images.
     */
    fun fillTransparent(img: Planes, channels: Int, alphaIndex: Int, sigma: Float, ctx: FilterContext) {
        val alpha = img[alphaIndex]
        if (alpha.none { it <= 0f }) return
        val w = img.w; val h = img.h
        val s = max(2f, sigma) // must reach a few working pixels into the transparent area
        val den = fastBlur(alpha, w, h, s, ctx)
        for (c in 0 until channels) {
            val plane = img[c]
            val weighted = FloatArray(plane.size) { plane[it] * alpha[it] }
            val num = fastBlur(weighted, w, h, s, ctx)
            for (i in plane.indices) if (alpha[i] <= 0f && den[i] > 1e-4f) plane[i] = num[i] / den[i]
        }
    }

    /** Gaussian-like blur of a plane in O(n) regardless of [sigma] (three box passes). */
    fun fastBlur(plane: FloatArray, w: Int, h: Int, sigma: Float, ctx: FilterContext): FloatArray {
        var p = plane
        for (box in FilterMath.boxesForGauss(max(0.5f, sigma), 3)) p = FilterMath.boxBlurPlane(p, w, h, (box - 1) / 2, ctx)
        return if (p === plane) plane.copyOf() else p
    }

    /**
     * Separable (horizontal then vertical) joint bilateral filter over the first [channels]
     * planes of [img], in place, weighting neighbours by the alpha plane [alphaIndex] (or not,
     * when it is -1). The range distance is the Euclidean distance over all filtered channels.
     */
    fun bilateral(
        img: Planes, channels: Int, alphaIndex: Int, sigmaS: Float, sigmaR: Float,
        iterations: Int, ctx: FilterContext,
    ) {
        if (iterations <= 0 || sigmaS < 0.3f) return
        val w = img.w; val h = img.h
        val radius = max(1, ceil(sigmaS * 2f).toInt())
        val spatial = FloatArray(2 * radius + 1) { val d = it - radius; exp(-(d * d) / (2f * sigmaS * sigmaS)) }
        // Range kernel as a LUT over squared distance up to (3 sigma)^2.
        val lutSize = 1024
        val maxD2 = 9f * sigmaR * sigmaR
        val lutScale = (lutSize - 1) / maxD2
        val range = FloatArray(lutSize) { exp(-(it / lutScale) / (2f * sigmaR * sigmaR)) }
        range[lutSize - 1] = 0f
        val tmp = Array(channels) { FloatArray(w * h) }
        val alpha = if (alphaIndex >= 0) img[alphaIndex] else null
        repeat(iterations) {
            pass(img.p, tmp, channels, alpha, w, h, radius, spatial, range, lutScale, horizontal = true, ctx)
            pass(tmp, img.p, channels, alpha, w, h, radius, spatial, range, lutScale, horizontal = false, ctx)
        }
    }

    private fun pass(
        src: Array<FloatArray>, dst: Array<FloatArray>, channels: Int, alpha: FloatArray?,
        w: Int, h: Int, radius: Int, spatial: FloatArray, range: FloatArray, lutScale: Float,
        horizontal: Boolean, ctx: FilterContext,
    ) {
        val lutMax = range.size - 1
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val center = FloatArray(channels)
            val acc = FloatArray(channels)
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val o = y * w + x
                    for (c in 0 until channels) { center[c] = src[c][o]; acc[c] = 0f }
                    var wsum = 0f
                    val lo = if (horizontal) max(-radius, -x) else max(-radius, -y)
                    val hi = if (horizontal) min(radius, w - 1 - x) else min(radius, h - 1 - y)
                    for (k in lo..hi) {
                        val q = if (horizontal) o + k else o + k * w
                        var d2 = 0f
                        for (c in 0 until channels) { val d = src[c][q] - center[c]; d2 += d * d }
                        val li = (d2 * lutScale).toInt()
                        if (li >= lutMax) continue
                        var wt = spatial[k + radius] * range[li]
                        if (alpha != null) wt *= alpha[q] + 1e-3f
                        wsum += wt
                        for (c in 0 until channels) acc[c] += src[c][q] * wt
                    }
                    if (wsum > 1e-12f) {
                        val inv = 1f / wsum
                        for (c in 0 until channels) dst[c][o] = acc[c] * inv
                    } else {
                        for (c in 0 until channels) dst[c][o] = center[c]
                    }
                }
            }
        }
    }

    /**
     * Deterministic k-means in Lab over the working planes [L, a, b, alpha]. Samples sit on a
     * fixed grid of relative positions, so a preview-sized working copy finds (almost) the same
     * centers as the full-resolution one. Returns up to k*3 center coordinates.
     */
    fun kMeans(work: Planes, k: Int, seed: Int, ctx: FilterContext): FloatArray {
        val ww = work.w; val wh = work.h
        val lp = work[0]; val ap = work[1]; val bp = work[2]; val alpha = work[3]
        val sm = FloatArray(KMEANS_GRID * KMEANS_GRID * 3)
        var count = 0
        for (j in 0 until KMEANS_GRID) {
            val y = min(wh - 1, ((j + 0.5f) * wh / KMEANS_GRID).toInt())
            for (i in 0 until KMEANS_GRID) {
                val o = y * ww + min(ww - 1, ((i + 0.5f) * ww / KMEANS_GRID).toInt())
                if (alpha[o] <= 0.1f) continue
                sm[count * 3] = lp[o]; sm[count * 3 + 1] = ap[o]; sm[count * 3 + 2] = bp[o]
                count++
            }
        }
        if (count == 0) return floatArrayOf(100f, 0f, 0f)
        val kk = max(1, min(k, count))
        val centers = FloatArray(kk * 3)
        // Farthest-point initialisation from a seeded first pick.
        val first = kotlin.random.Random(seed).nextInt(count)
        for (c in 0..2) centers[c] = sm[first * 3 + c]
        val best = FloatArray(count) { Float.MAX_VALUE }
        for (ci in 1 until kk) {
            var far = 0; var farD = -1f
            for (i in 0 until count) {
                val d = dist2(sm, i, centers, ci - 1)
                if (d < best[i]) best[i] = d
                if (best[i] > farD) { farD = best[i]; far = i }
            }
            for (c in 0..2) centers[ci * 3 + c] = sm[far * 3 + c]
        }
        val sums = FloatArray(kk * 3); val counts = IntArray(kk)
        repeat(8) {
            ctx.checkCancelled()
            sums.fill(0f); counts.fill(0)
            for (i in 0 until count) {
                val ci = nearest(sm[i * 3], sm[i * 3 + 1], sm[i * 3 + 2], centers, kk)
                counts[ci]++
                for (c in 0..2) sums[ci * 3 + c] += sm[i * 3 + c]
            }
            for (ci in 0 until kk) if (counts[ci] > 0) for (c in 0..2) centers[ci * 3 + c] = sums[ci * 3 + c] / counts[ci]
        }
        return centers
    }

    /**
     * Pulls every working pixel of [L, a, b] towards its k-means center by [amount] (lightness
     * by [amount] * [lightWeight]). With [softness] > 0 (Delta E) the target blends the nearby
     * centers with gaussian weights, so a smooth gradient between two clusters does not break
     * into a hard seam.
     */
    fun quantizeTowards(
        work: Planes, centers: FloatArray, amount: Float, ctx: FilterContext,
        lightWeight: Float = 1f, softness: Float = 0f,
    ) {
        val k = centers.size / 3
        val lp = work[0]; val ap = work[1]; val bp = work[2]
        val w = work.w
        val inv2s2 = if (softness > 0f) 1f / (2f * softness * softness) else 0f
        val amountL = amount * lightWeight
        Parallel.forRows(work.h) { y0, y1 ->
            ctx.checkCancelled()
            val d2 = FloatArray(k)
            for (i in y0 * w until y1 * w) {
                val l = lp[i]; val a = ap[i]; val b = bp[i]
                var bestD = Float.MAX_VALUE; var best = 0
                for (ci in 0 until k) {
                    val dl = l - centers[ci * 3]; val da = a - centers[ci * 3 + 1]; val db = b - centers[ci * 3 + 2]
                    val d = dl * dl + da * da + db * db
                    d2[ci] = d
                    if (d < bestD) { bestD = d; best = ci }
                }
                var tl = centers[best * 3]; var ta = centers[best * 3 + 1]; var tb = centers[best * 3 + 2]
                if (inv2s2 > 0f && k > 1) {
                    var sw = 0f; var sl = 0f; var sa = 0f; var sb = 0f
                    for (ci in 0 until k) {
                        val e = (d2[ci] - bestD) * inv2s2
                        if (e > 9f) continue
                        val wt = exp(-e)
                        sw += wt; sl += wt * centers[ci * 3]; sa += wt * centers[ci * 3 + 1]; sb += wt * centers[ci * 3 + 2]
                    }
                    tl = sl / sw; ta = sa / sw; tb = sb / sw
                }
                lp[i] = l + (tl - l) * amountL
                ap[i] = a + (ta - a) * amount
                bp[i] = b + (tb - b) * amount
            }
        }
    }

    private fun nearest(l: Float, a: Float, b: Float, centers: FloatArray, k: Int): Int {
        var best = 0; var bestD = Float.MAX_VALUE
        for (ci in 0 until k) {
            val dl = l - centers[ci * 3]; val da = a - centers[ci * 3 + 1]; val db = b - centers[ci * 3 + 2]
            val d = dl * dl + da * da + db * db
            if (d < bestD) { bestD = d; best = ci }
        }
        return best
    }

    private fun dist2(sm: FloatArray, i: Int, centers: FloatArray, ci: Int): Float {
        val dl = sm[i * 3] - centers[ci * 3]; val da = sm[i * 3 + 1] - centers[ci * 3 + 1]; val db = sm[i * 3 + 2] - centers[ci * 3 + 2]
        return dl * dl + da * da + db * db
    }

    /** k-means samples per axis (110 x 110 = 12100 samples at most). */
    private const val KMEANS_GRID = 110

    /** Difference of gaussians G(sigma) - G(1.6 sigma) of a plane (negative on the dark side of edges). */
    fun dog(plane: FloatArray, w: Int, h: Int, sigma: Float, ctx: FilterContext): FloatArray {
        val s = max(0.35f, sigma)
        val g1 = FilterMath.gaussianBlurPlane(plane, w, h, s, ctx)
        val g2 = FilterMath.gaussianBlurPlane(plane, w, h, s * 1.6f, ctx)
        for (i in g1.indices) g1[i] -= g2[i]
        return g1
    }

    /** Sobel gradient magnitude of a plane (edges clamped). */
    fun gradient(plane: FloatArray, w: Int, h: Int, ctx: FilterContext): FloatArray {
        val out = FloatArray(w * h)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val ym = max(0, y - 1) * w; val yc = y * w; val yp = min(h - 1, y + 1) * w
                for (x in 0 until w) {
                    val xm = max(0, x - 1); val xp = min(w - 1, x + 1)
                    val gx = (plane[ym + xp] + 2 * plane[yc + xp] + plane[yp + xp]) -
                        (plane[ym + xm] + 2 * plane[yc + xm] + plane[yp + xm])
                    val gy = (plane[yp + xm] + 2 * plane[yp + x] + plane[yp + xp]) -
                        (plane[ym + xm] + 2 * plane[ym + x] + plane[ym + xp])
                    out[yc + x] = sqrt(gx * gx + gy * gy) * 0.125f
                }
            }
        }
        return out
    }

    /**
     * Renders a full-size result row by row: [f] receives the output pixel coordinates, the
     * matching working-image coordinates and the source pixel.
     */
    inline fun render(
        src: PixelBuffer, work: Planes, ctx: FilterContext,
        crossinline f: (x: Int, y: Int, wx: Float, wy: Float, c: Int) -> Int,
    ): PixelBuffer {
        val sx = work.w.toFloat() / src.width
        val sy = work.h.toFloat() / src.height
        return FilterMath.mapXY(src, ctx) { x, y, c -> f(x, y, (x + 0.5f) * sx, (y + 0.5f) * sy, c) }
    }
}

/** Fast odd-symmetric soft step used for cel-style tone quantization. */
internal class SoftQuantizer(levels: Int, sharpness: Float) {
    private val step = 100f / max(1, levels)
    private val half = step / 2f
    private val lut = FloatArray(1025)

    init {
        val k = max(0.5f, sharpness)
        val norm = kotlin.math.tanh(k.toDouble()).toFloat()
        for (i in lut.indices) {
            val u = i / 512f - 1f
            lut[i] = kotlin.math.tanh((k * u).toDouble()).toFloat() / norm
        }
    }

    /**
     * Quantizes a lightness value 0..100 into soft bands (Winnemöller): values are pulled to the
     * band centers (multiples of the step) with a smooth tanh transition at each band boundary.
     */
    fun apply(l: Float): Float {
        val boundary = (kotlin.math.floor(l / step) + 0.5f) * step
        val u = ((l - boundary) / half).coerceIn(-1f, 1f)
        return boundary + half * lut[((u + 1f) * 512f + 0.5f).toInt()]
    }
}
