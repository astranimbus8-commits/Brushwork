package com.brushwork.paint.segmentation

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import kotlin.math.min

/** An image as three float planes (0..1), flattened over white. */
internal class ColorPlanes(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
    init {
        require(r.size == w * h && g.size == w * h && b.size == w * h)
    }

    companion object {
        fun of(img: PixelBuffer): ColorPlanes {
            val n = img.size
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
            val px = img.pixels
            Parallel.forRange(n, 4096) { s, e ->
                for (i in s until e) {
                    val c = MaskOps.flattenOverWhite(px[i])
                    r[i] = ((c shr 16) and 0xFF) / 255f
                    g[i] = ((c shr 8) and 0xFF) / 255f
                    b[i] = (c and 0xFF) / 255f
                }
            }
            return ColorPlanes(img.width, img.height, r, g, b)
        }
    }
}

/** Averaged linear coefficients of a color guided filter: `q = aR·R + aG·G + aB·B + b`. */
internal class GuidedCoefficients(val w: Int, val h: Int, val aR: FloatArray, val aG: FloatArray, val aB: FloatArray, val b: FloatArray)

/**
 * Color guided filter (He, Sun & Tang, "Guided Image Filtering", TPAMI 2013, eq. 19-21): the
 * output is locally a linear function of the RGB guide, with the 3x3 color covariance of each
 * window, so mask edges follow color edges even where both sides have the same brightness (blue
 * sky against orange brick). O(n) in the number of pixels for any radius (17 box filters).
 *
 * Returning the coefficients enables the fast guided filter (He & Sun 2015): compute them at a
 * reduced resolution and evaluate them with the full-resolution guide ([upsample]).
 */
internal object ColorGuidedFilter {

    /**
     * Coefficients for filtering [p] (size w*h) with guide [guide], window radius [r] and
     * regularization [eps] (color edges whose variance is well above eps are preserved).
     */
    fun coefficients(guide: ColorPlanes, p: FloatArray, r: Int, eps: Float): GuidedCoefficients {
        val w = guide.w; val h = guide.h; val n = w * h
        require(p.size == n) { "size mismatch" }
        require(eps > 0f)
        val R = guide.r; val G = guide.g; val B = guide.b
        val tmp = FloatArray(n)
        fun box(src: FloatArray, dst: FloatArray = FloatArray(n)) = MaskOps.boxMean(src, w, h, r, dst, tmp)
        fun boxOfProduct(x: FloatArray, y: FloatArray): FloatArray {
            val d = FloatArray(n)
            Parallel.forRange(n, 4096) { s, e -> for (i in s until e) d[i] = x[i] * y[i] }
            return box(d, d)
        }
        val mR = box(R); val mG = box(G); val mB = box(B); val mP = box(p)
        val vRR = boxOfProduct(R, R); val vRG = boxOfProduct(R, G); val vRB = boxOfProduct(R, B)
        val vGG = boxOfProduct(G, G); val vGB = boxOfProduct(G, B); val vBB = boxOfProduct(B, B)
        val cR = boxOfProduct(R, p); val cG = boxOfProduct(G, p); val cB = boxOfProduct(B, p)
        Parallel.forRange(n, 4096) { s, e ->
            for (i in s until e) {
                val mr = mR[i]; val mg = mG[i]; val mb = mB[i]; val mp = mP[i]
                val rr = vRR[i] - mr * mr + eps; val rg = vRG[i] - mr * mg; val rb = vRB[i] - mr * mb
                val gg = vGG[i] - mg * mg + eps; val gb = vGB[i] - mg * mb; val bb = vBB[i] - mb * mb + eps
                val xr = cR[i] - mr * mp; val xg = cG[i] - mg * mp; val xb = cB[i] - mb * mp
                // Inverse of the symmetric 3x3 covariance by cofactors.
                val i00 = gg * bb - gb * gb
                val i01 = rb * gb - rg * bb
                val i02 = rg * gb - rb * gg
                val i11 = rr * bb - rb * rb
                val i12 = rb * rg - rr * gb
                val i22 = rr * gg - rg * rg
                val det = rr * i00 + rg * i01 + rb * i02
                if (det <= 1e-30f || !det.isFinite()) {
                    cR[i] = 0f; cG[i] = 0f; cB[i] = 0f; mP[i] = mp
                    continue
                }
                val inv = 1f / det
                val ar = (i00 * xr + i01 * xg + i02 * xb) * inv
                val ag = (i01 * xr + i11 * xg + i12 * xb) * inv
                val ab = (i02 * xr + i12 * xg + i22 * xb) * inv
                cR[i] = ar; cG[i] = ag; cB[i] = ab
                mP[i] = mp - ar * mr - ag * mg - ab * mb
            }
        }
        // Average the per-window coefficients (reusing the variance planes as outputs).
        val aR = box(cR, vRR); val aG = box(cG, vRG); val aB = box(cB, vRB); val bOut = box(mP, vGG)
        return GuidedCoefficients(w, h, aR, aG, aB, bOut)
    }

    /** Evaluates [k] with [guide] at the same resolution. */
    fun apply(guide: ColorPlanes, k: GuidedCoefficients, clamp: Boolean = true): FloatArray {
        require(guide.w == k.w && guide.h == k.h)
        val n = guide.w * guide.h
        val out = FloatArray(n)
        Parallel.forRange(n, 4096) { s, e ->
            for (i in s until e) {
                val q = k.aR[i] * guide.r[i] + k.aG[i] * guide.g[i] + k.aB[i] * guide.b[i] + k.b[i]
                out[i] = if (clamp) MaskOps.clamp01(q) else q
            }
        }
        return out
    }

    /** Color guided filter of [p] at the guide's resolution. */
    fun filter(guide: ColorPlanes, p: FloatArray, r: Int, eps: Float, clamp: Boolean = true): FloatArray =
        apply(guide, coefficients(guide, p, r, eps), clamp)

    /**
     * Fast guided filter output at the FULL resolution of [image]: bilinearly upsamples the four
     * coefficient planes and evaluates them with the full-resolution colors (flattened over
     * white). Only the returned array is allocated at full size. Values are clamped to 0..1.
     */
    fun upsample(image: PixelBuffer, k: GuidedCoefficients): FloatArray {
        val fw = image.width; val fh = image.height
        val w = k.w; val h = k.h
        val px = image.pixels
        val out = FloatArray(fw * fh)
        if (fw == w && fh == h) {
            Parallel.forRange(out.size, 4096) { s, e -> for (i in s until e) out[i] = eval(k, i, i, 0, 0, 0f, 0f, px[i], sameSize = true) }
            return out
        }
        val xi = IntArray(fw); val xt = FloatArray(fw)
        for (x in 0 until fw) {
            val f = ((x + 0.5f) * w / fw - 0.5f).coerceIn(0f, (w - 1).toFloat())
            xi[x] = min(f.toInt(), w - 1)
            xt[x] = f - xi[x]
        }
        Parallel.forRows(fh) { y0, y1 ->
            for (y in y0 until y1) {
                val fy = ((y + 0.5f) * h / fh - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val sy0 = min(fy.toInt(), h - 1)
                val sy1 = min(sy0 + 1, h - 1)
                val ty = fy - sy0
                val r0 = sy0 * w; val r1 = sy1 * w
                val row = y * fw
                for (x in 0 until fw) {
                    val sx0 = xi[x]; val sx1 = min(sx0 + 1, w - 1)
                    out[row + x] = eval(k, r0 + sx0, r0 + sx1, r1 + sx0, r1 + sx1, xt[x], ty, px[row + x], sameSize = false)
                }
            }
        }
        return out
    }

    private fun lerp2(p: FloatArray, i00: Int, i10: Int, i01: Int, i11: Int, tx: Float, ty: Float): Float {
        val top = p[i00] + (p[i10] - p[i00]) * tx
        val bot = p[i01] + (p[i11] - p[i01]) * tx
        return top + (bot - top) * ty
    }

    private fun eval(k: GuidedCoefficients, i00: Int, i10: Int, i01: Int, i11: Int, tx: Float, ty: Float, pixel: Int, sameSize: Boolean): Float {
        val c = MaskOps.flattenOverWhite(pixel)
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        if (sameSize) return MaskOps.clamp01(k.aR[i00] * r + k.aG[i00] * g + k.aB[i00] * b + k.b[i00])
        val ar = lerp2(k.aR, i00, i10, i01, i11, tx, ty)
        val ag = lerp2(k.aG, i00, i10, i01, i11, tx, ty)
        val ab = lerp2(k.aB, i00, i10, i01, i11, tx, ty)
        val bb = lerp2(k.b, i00, i10, i01, i11, tx, ty)
        return MaskOps.clamp01(ar * r + ag * g + ab * b + bb)
    }
}
