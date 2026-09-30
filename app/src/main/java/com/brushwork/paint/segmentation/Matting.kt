package com.brushwork.paint.segmentation

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.filters.FilterMath
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Edge refinement of a soft mask (Photoshop-style "smart radius" + band matting), pure Kotlin:
 *
 * 1. Trimap: definite foreground / background away from the 0.5 contour; an UNKNOWN band around
 *    it whose half-width is [Params.band] (about one model cell), widened where the local color
 *    variance is high (hair, foliage) and where the probability itself is unsure.
 * 2. Data term in the band: every unknown pixel is compared with LOCAL multimodal color models
 *    (k-means of the definite foreground and background colors near its tile, so blue sky with
 *    white clouds or foliage with gaps are two or three colors, not one mean), optionally blended
 *    with an image-wide model (sky), and moved from the model probability toward the color
 *    decision as far as the colors are distinct.
 * 3. Propagation: rounds of the color guided filter with a small eps (local linear color models,
 *    the fast relative of closed-form matting), clamping the definite regions after each round.
 */
internal object Matting {

    class Params(
        /** Half-width (px) of the unknown band around the 0.5 contour in smooth areas. */
        val band: Float,
        /** Extra band width in textured areas: up to band * (1 + widen). */
        val widen: Float = 1f,
        /** Also compare band pixels with an image-wide color model (sky: clouds, haze). */
        val globalColorModel: Boolean = false,
        /**
         * Background pixels within this distance (px) of the mask whose color clearly matches the
         * image-wide foreground model join the band (sky seen through branches). 0 = off.
         */
        val colorReach: Float = 0f,
        /** Guided-filter rounds that propagate the known regions into the band. */
        val rounds: Int = 2,
        /** Guided-filter window radius (px) of the propagation. */
        val radius: Int = 2,
        val eps: Float = 1e-4f,
    )

    const val UNKNOWN: Byte = 0
    const val FOREGROUND: Byte = 1
    const val BACKGROUND: Byte = 2

    /** Refined alpha (0..1, size w*h) of the soft mask [p] over [img]. */
    fun refine(img: ColorPlanes, p: FloatArray, params: Params): FloatArray {
        val w = img.w; val h = img.h; val n = w * h
        require(p.size == n)
        var anyIn = false; var anyOut = false
        for (v in p) { if (v >= 0.5f) anyIn = true else anyOut = true; if (anyIn && anyOut) break }
        if (!anyIn) return FloatArray(n)
        if (!anyOut) return FloatArray(n) { 1f }
        val texture = colorTexture(img, 2)
        val global = if (params.globalColorModel || params.colorReach > 0f) GlobalModel.fit(img, p) else null
        val tri = trimap(p, w, h, texture, params, global?.let { g -> { i: Int -> g.ratio(img, i) } })
        val alpha = FloatArray(n)
        val ratio = FloatArray(n) { -1f }
        val conf = FloatArray(n)
        localColorModels(img, tri, params.band, ratio, conf)
        Parallel.forRange(n, 4096) { s, e ->
            for (i in s until e) {
                when (tri[i]) {
                    FOREGROUND -> alpha[i] = 1f
                    BACKGROUND -> alpha[i] = 0f
                    else -> {
                        var r = ratio[i]; var c = conf[i]
                        if (global != null && params.globalColorModel) {
                            val gr = global.ratio(img, i)
                            val gc = 0.7f * global.confidence(img, i)
                            if (r < 0f) { r = gr; c = gc } else {
                                val sum = c + gc
                                if (sum > 1e-6f) r = (c * r + gc * gr) / sum
                                c = max(c, gc)
                            }
                        }
                        val pi = MaskOps.clamp01(p[i])
                        alpha[i] = if (r < 0f) pi else pi + (MaskOps.smoothstep(0.15f, 0.85f, r) - pi) * c
                    }
                }
            }
        }
        var a = alpha
        repeat(params.rounds) {
            a = ColorGuidedFilter.filter(img, a, params.radius, params.eps)
            Parallel.forRange(n, 4096) { s, e ->
                for (i in s until e) {
                    when (tri[i]) {
                        FOREGROUND -> a[i] = 1f
                        BACKGROUND -> a[i] = 0f
                    }
                }
            }
        }
        return a
    }

    /**
     * Trimap of [p]: [FOREGROUND] / [BACKGROUND] farther than the local band half-width from the
     * 0.5 contour, [UNKNOWN] inside it. [globalRatio] (0..1, 1 = foreground-colored), when
     * given with [Params.colorReach], pulls matching background pixels near the mask into the band.
     */
    fun trimap(p: FloatArray, w: Int, h: Int, texture: FloatArray?, params: Params, globalRatio: ((Int) -> Float)? = null): ByteArray {
        val n = w * h
        val inside = FloatArray(n) { if (p[it] >= 0.5f) 1f else 0f }
        val toInside = FilterMath.distanceToCoverage(inside, w, h, 0.5f)
        for (i in 0 until n) inside[i] = 1f - inside[i]
        val toOutside = FilterMath.distanceToCoverage(inside, w, h, 0.5f)
        val out = ByteArray(n)
        Parallel.forRange(n, 4096) { s, e ->
            for (i in s until e) {
                val fg = p[i] >= 0.5f
                val d = if (fg) toOutside[i] else toInside[i]
                val tex = texture?.let { MaskOps.smoothstep(0.03f, 0.12f, it[i]) } ?: 0f
                val r = params.band * (1f + params.widen * tex)
                val unsure = p[i] > 0.12f && p[i] < 0.88f
                var unknown = d <= r || (unsure && d <= 3f * r)
                if (!unknown && !fg && globalRatio != null && params.colorReach > 0f && d <= params.colorReach) {
                    unknown = globalRatio(i) > 0.8f
                }
                out[i] = if (unknown) UNKNOWN else if (fg) FOREGROUND else BACKGROUND
            }
        }
        return out
    }

    /** Local color standard deviation (RGB, 0..~0.9) in a (2r+1)Ã‚Â² window. */
    fun colorTexture(img: ColorPlanes, r: Int): FloatArray {
        val w = img.w; val h = img.h; val n = w * h
        val tmp = FloatArray(n)
        val sq = FloatArray(n)
        Parallel.forRange(n, 4096) { s, e -> for (i in s until e) sq[i] = img.r[i] * img.r[i] + img.g[i] * img.g[i] + img.b[i] * img.b[i] }
        MaskOps.boxMean(sq, w, h, r, sq, tmp)
        val m = FloatArray(n)
        for (plane in arrayOf(img.r, img.g, img.b)) {
            MaskOps.boxMean(plane, w, h, r, m, tmp)
            Parallel.forRange(n, 4096) { s, e -> for (i in s until e) sq[i] -= m[i] * m[i] }
        }
        Parallel.forRange(n, 4096) { s, e -> for (i in s until e) sq[i] = sqrt(max(0f, sq[i])) }
        return sq
    }

    /**
     * Per-tile color models ([ColorModel]) of the definite foreground and background near each
     * tile of the band; writes, for every UNKNOWN pixel, the foreground ratio 0..1 (1 = a
     * foreground color) into [ratio] and how decisive that is into [conf]. Tiles without enough
     * samples on both sides leave ratio at -1.
     */
    fun localColorModels(img: ColorPlanes, tri: ByteArray, band: Float, ratio: FloatArray, conf: FloatArray) {
        val w = img.w; val h = img.h
        val tile = (band * 4f).roundToInt().coerceIn(16, 96)
        val margin = max(tile / 2, (band * 3f).roundToInt())
        val tilesX = (w + tile - 1) / tile; val tilesY = (h + tile - 1) / tile
        Parallel.forRange(tilesX * tilesY, 1) { ts, te ->
            val fg = FloatArray(MAX_SAMPLES * 3); val bg = FloatArray(MAX_SAMPLES * 3)
            val out = FloatArray(2)
            for (t in ts until te) {
                val x0 = (t % tilesX) * tile; val y0 = (t / tilesX) * tile
                val x1 = min(w, x0 + tile); val y1 = min(h, y0 + tile)
                var hasUnknown = false
                scan@ for (y in y0 until y1) for (x in x0 until x1) if (tri[y * w + x] == UNKNOWN) { hasUnknown = true; break@scan }
                if (!hasUnknown) continue
                val wx0 = max(0, x0 - margin); val wy0 = max(0, y0 - margin)
                val wx1 = min(w, x1 + margin); val wy1 = min(h, y1 + margin)
                val area = (wx1 - wx0) * (wy1 - wy0)
                val step = max(1, sqrt(area / (MAX_SAMPLES * 1.5f)).toInt())
                var nf = 0; var nb = 0
                for (y in wy0 until wy1 step step) for (x in wx0 until wx1 step step) {
                    val i = y * w + x
                    when (tri[i]) {
                        FOREGROUND -> if (nf < MAX_SAMPLES) { fg[nf * 3] = img.r[i]; fg[nf * 3 + 1] = img.g[i]; fg[nf * 3 + 2] = img.b[i]; nf++ }
                        BACKGROUND -> if (nb < MAX_SAMPLES) { bg[nb * 3] = img.r[i]; bg[nb * 3 + 1] = img.g[i]; bg[nb * 3 + 2] = img.b[i]; nb++ }
                    }
                }
                if (nf < 3 || nb < 3) continue
                val mf = ColorModel.fit(fg, nf, 3, iterations = 5)
                val mb = ColorModel.fit(bg, nb, 3, iterations = 5)
                for (y in y0 until y1) for (x in x0 until x1) {
                    val i = y * w + x
                    if (tri[i] != UNKNOWN) continue
                    ColorModel.compare(mf, mb, img.r[i], img.g[i], img.b[i], out)
                    ratio[i] = out[0]
                    conf[i] = out[1]
                }
            }
        }
    }

    private const val MAX_SAMPLES = 384

    /** Image-wide color models of the confident foreground (p > 0.9) and background (p < 0.1). */
    class GlobalModel private constructor(private val fg: ColorModel, private val bg: ColorModel) {
        private val tmp = ThreadLocal.withInitial { FloatArray(2) }

        /** 0..1, 1 = the color of pixel [i] is a foreground color. */
        fun ratio(img: ColorPlanes, i: Int): Float {
            val out = tmp.get()
            ColorModel.compare(fg, bg, img.r[i], img.g[i], img.b[i], out)
            return out[0]
        }

        /** 0..1, how decisive [ratio] is. */
        fun confidence(img: ColorPlanes, i: Int): Float {
            val out = tmp.get()
            ColorModel.compare(fg, bg, img.r[i], img.g[i], img.b[i], out)
            return out[1]
        }

        companion object {
            fun fit(img: ColorPlanes, p: FloatArray): GlobalModel? {
                val n = p.size
                val step = max(1, sqrt(n / 6000f).toInt())
                val fg = FloatArray(MAX_GLOBAL * 3); val bg = FloatArray(MAX_GLOBAL * 3)
                var nf = 0; var nb = 0
                for (y in 0 until img.h step step) for (x in 0 until img.w step step) {
                    val i = y * img.w + x
                    val v = p[i]
                    if (v > 0.9f && nf < MAX_GLOBAL) { fg[nf * 3] = img.r[i]; fg[nf * 3 + 1] = img.g[i]; fg[nf * 3 + 2] = img.b[i]; nf++ }
                    else if (v < 0.1f && nb < MAX_GLOBAL) { bg[nb * 3] = img.r[i]; bg[nb * 3 + 1] = img.g[i]; bg[nb * 3 + 2] = img.b[i]; nb++ }
                }
                if (nf < 8 || nb < 8) return null
                return GlobalModel(ColorModel.fit(fg, nf, 3, 8), ColorModel.fit(bg, nb, 5, 8))
            }

            private const val MAX_GLOBAL = 8192
        }
    }
}

/**
 * A small color mixture (k-means centers weighted by how many samples they explain, one shared
 * isotropic variance): a GMM-lite. The population weights matter: a background cluster made of
 * a few sky-colored holes between branches must not outvote the sky itself.
 */
internal class ColorModel private constructor(
    private val centers: FloatArray,
    private val weights: FloatArray,
    private val k: Int,
    /** Per-channel variance. */
    val variance: Float,
) {
    private val inv2Var = 1f / (2f * variance)
    private val norm = variance.toDouble().pow(-1.5).toFloat()

    /** Density of color (r, g, b), up to a constant shared by all models. */
    fun density(r: Float, g: Float, b: Float): Float {
        var s = 0f
        for (j in 0 until k) {
            val d0 = r - centers[j * 3]; val d1 = g - centers[j * 3 + 1]; val d2 = b - centers[j * 3 + 2]
            s += weights[j] * exp(-(d0 * d0 + d1 * d1 + d2 * d2) * inv2Var)
        }
        return s * norm
    }

    /** Squared distance to the nearest center. */
    fun nearest(r: Float, g: Float, b: Float): Float {
        var best = Float.MAX_VALUE
        for (j in 0 until k) {
            val d0 = r - centers[j * 3]; val d1 = g - centers[j * 3 + 1]; val d2 = b - centers[j * 3 + 2]
            best = min(best, d0 * d0 + d1 * d1 + d2 * d2)
        }
        return best
    }

    companion object {
        /** Smallest per-channel variance (colors of flat synthetic areas are exact). */
        const val MIN_VARIANCE = 5e-4f

        /** Largest per-channel variance: a very textured side must not claim every color. */
        const val MAX_VARIANCE = 1e-2f

        /** Squared distance / variance beyond which a color is explained by neither model. */
        private const val OUTLIER = 16f

        fun fit(samples: FloatArray, n: Int, k: Int, iterations: Int): ColorModel {
            require(n > 0)
            val kk = min(k, n)
            val centers = SceneHeuristics.kMeans(samples, n, kk, iterations)
            val counts = IntArray(kk)
            var sse = 0.0
            for (s in 0 until n) {
                var best = 0; var bestD = Float.MAX_VALUE
                for (j in 0 until kk) {
                    val d0 = samples[s * 3] - centers[j * 3]; val d1 = samples[s * 3 + 1] - centers[j * 3 + 1]; val d2 = samples[s * 3 + 2] - centers[j * 3 + 2]
                    val d = d0 * d0 + d1 * d1 + d2 * d2
                    if (d < bestD) { bestD = d; best = j }
                }
                counts[best]++
                sse += bestD
            }
            val variance = (sse / n / 3).toFloat().coerceIn(MIN_VARIANCE, MAX_VARIANCE)
            val weights = FloatArray(kk) { counts[it].toFloat() / n }
            return ColorModel(centers, weights, kk, variance)
        }

        /**
         * Writes into [out] the foreground ratio of color (r, g, b) under [fg] vs [bg] (0..1,
         * 1 = foreground) and its decisiveness 0..1. Colors far from both models fall back to
         * the nearest-center distances, trusted half as much.
         */
        fun compare(fg: ColorModel, bg: ColorModel, r: Float, g: Float, b: Float, out: FloatArray) {
            val df = fg.nearest(r, g, b); val db = bg.nearest(r, g, b)
            if (df <= OUTLIER * fg.variance || db <= OUTLIER * bg.variance) {
                val lf = fg.density(r, g, b); val lb = bg.density(r, g, b)
                val sum = lf + lb
                if (sum > 1e-20f && sum.isFinite()) {
                    out[0] = lf / sum
                    out[1] = abs(lf - lb) / sum
                    return
                }
            }
            out[0] = db / (df + db + 1e-6f)
            out[1] = 0.5f * abs(db - df) / (db + df + 0.003f)
        }
    }
}
