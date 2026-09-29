package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.segmentation.MaskOps.smoothstep
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hand-tuned scene heuristics (pure Kotlin, no models). They are the fallback when the ML models
 * are unavailable, and the sky heuristic also refines the model's sky edges. Every function takes
 * an image (alpha is flattened over white) and returns a 0..1 mask of the same size. Window sizes
 * are relative to the image size, so they behave the same at any analysis resolution (the
 * pipeline runs them at <= 512 px, the sky refinement at <= 1280 px).
 */
object SceneHeuristics {

    /**
     * Sky: bright, low-texture, blue / gray / white pixels that are connected to the top edge.
     * Small enclosed holes (birds, cloud edges) are filled.
     */
    fun sky(img: PixelBuffer): FloatArray {
        val f = Features(img)
        val w = f.w; val h = f.h; val n = f.n
        val tex = f.texture
        val cand = FloatArray(n)
        for (y in 0 until h) {
            val pos = 1f - 0.5f * smoothstep(0.4f, 1f, (y + 0.5f) / h)
            for (x in 0 until w) {
                val i = y * w + x
                val c = f.px[i]
                val r = red(c); val g = green(c); val b = blue(c)
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val sat = if (mx > 0f) (mx - mn) / mx else 0f
                val bright = smoothstep(0.3f, 0.55f, mx)
                val blueish = smoothstep(0.02f, 0.12f, b - r) * (1f - smoothstep(0.02f, 0.12f, g - b))
                val grayish = 1f - smoothstep(0.1f, 0.22f, sat)
                val smooth = 1f - smoothstep(0.02f, 0.06f, tex[i])
                cand[i] = bright * max(blueish, grayish) * smooth * pos
            }
        }
        val passable = BooleanArray(n) { cand[it] > 0.3f }
        val seedRows = max(1, h / 50)
        // Luma texture misses iso-luminant boundaries (blue sky against a gray wall of the same
        // brightness), so the fill also refuses to step across a sharp color change.
        val px = f.px
        val colorEdge = { a: Int, b: Int -> colorStep(px[a], px[b]) < SKY_MAX_STEP }
        val sky = Regions.floodFrom(passable, w, h, colorEdge) { x, y -> y < seedRows && cand[y * w + x] > 0.4f }
        Regions.fillHoles(sky, w, h, maxHoleSize = n / 200)
        return soften(FloatArray(n) { if (sky[it]) 1f else 0f }, w, h)
    }

    /** Vegetation: excess green (2G - R - B, chromatic) in the green hue range, boosted by local texture. */
    fun vegetation(img: PixelBuffer): FloatArray {
        val f = Features(img)
        val tex = f.texture
        val out = FloatArray(f.n)
        for (i in 0 until f.n) {
            val c = f.px[i]
            val r = red(c); val g = green(c); val b = blue(c)
            val sum = r + g + b
            val exg = if (sum < 0.08f) 0f else (2f * g - r - b) / sum
            val mx = max(r, max(g, b))
            val greenness = smoothstep(0.04f, 0.18f, exg) * trapezoid(hue(r, g, b), 45f, 65f, 160f, 180f) *
                smoothstep(0.03f, 0.1f, mx)
            val textured = smoothstep(0.01f, 0.05f, tex[i])
            out[i] = smoothstep(0.3f, 0.6f, greenness * (0.6f + 0.4f * textured))
        }
        return soften(out, f.w, f.h)
    }

    /**
     * Buildings: dense straight horizontal/vertical edges (facades, windows) in low-to-moderate
     * saturation areas that are neither sky nor vegetation.
     */
    fun buildings(img: PixelBuffer, sky: FloatArray? = null, vegetation: FloatArray? = null): FloatArray {
        val f = Features(img)
        val w = f.w; val h = f.h; val n = f.n
        val skyM = sky ?: sky(img)
        val vegM = vegetation ?: vegetation(img)
        val gx = f.gradX; val gy = f.gradY
        val hv = FloatArray(n); val all = FloatArray(n)
        for (i in 0 until n) {
            val ax = kotlin.math.abs(gx[i]); val ay = kotlin.math.abs(gy[i])
            if (ax * ax + ay * ay > 0.06f * 0.06f) {
                all[i] = 1f
                if (ax < 0.3f * ay || ay < 0.3f * ax) hv[i] = 1f
            }
        }
        val radius = max(2, (f.longSide / 48f).roundToInt())
        val tmp = FloatArray(n)
        MaskOps.boxMean(hv, w, h, radius, hv, tmp)
        MaskOps.boxMean(all, w, h, radius, all, tmp)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val straight = hv[i] / max(all[i], 1e-4f)
            val structure = smoothstep(0.03f, 0.12f, hv[i]) * smoothstep(0.45f, 0.75f, straight)
            val c = f.px[i]
            val r = red(c); val g = green(c); val b = blue(c)
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx > 0f) (mx - mn) / mx else 0f
            val satTerm = 1f - 0.8f * smoothstep(0.25f, 0.55f, sat)
            out[i] = structure * satTerm * (1f - skyM[i]) * (1f - vegM[i])
        }
        MaskOps.boxMean(out, w, h, max(1, radius / 2), out, tmp)
        for (i in 0 until n) out[i] = smoothstep(0.15f, 0.4f, out[i]) * (1f - skyM[i])
        return out
    }

    /**
     * Water: blue-ish (or gray) low-texture pixels below the horizon, where the horizon is the
     * lowest row with substantial sky coverage (or 30% down when there is no sky).
     */
    fun water(img: PixelBuffer, sky: FloatArray? = null, vegetation: FloatArray? = null): FloatArray {
        val f = Features(img)
        val w = f.w; val h = f.h
        val skyM = sky ?: sky(img)
        val vegM = vegetation ?: vegetation(img)
        var horizonRow = -1
        for (y in 0 until h) {
            var s = 0f
            for (x in 0 until w) s += skyM[y * w + x]
            if (s / w > 0.2f) horizonRow = y
        }
        val horizon = if (horizonRow >= 0) (horizonRow + 1f) / h else 0.3f
        val tex = f.texture
        val out = FloatArray(f.n)
        for (y in 0 until h) {
            val below = smoothstep(horizon, horizon + 0.04f, (y + 0.5f) / h)
            if (below <= 0f) continue
            for (x in 0 until w) {
                val i = y * w + x
                val c = f.px[i]
                val r = red(c); val g = green(c); val b = blue(c)
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val sat = if (mx > 0f) (mx - mn) / mx else 0f
                val blueish = smoothstep(0.02f, 0.1f, b - r) * (1f - smoothstep(0.05f, 0.2f, g - b))
                val grayish = 0.4f * (1f - smoothstep(0.08f, 0.2f, sat)) * smoothstep(0.15f, 0.35f, mx)
                val smooth = 1f - smoothstep(0.03f, 0.1f, tex[i])
                val raw = below * max(blueish, grayish) * (0.4f + 0.6f * smooth) * (1f - vegM[i]) * (1f - skyM[i])
                out[i] = smoothstep(0.25f, 0.55f, raw)
            }
        }
        return soften(out, w, h)
    }

    /**
     * People: skin tones (YCbCr rule) weighted toward the image center. When a [saliency] mask
     * is given, salient objects that contain skin are included whole (clothes and hair too).
     */
    fun people(img: PixelBuffer, saliency: FloatArray? = null): FloatArray {
        val f = Features(img)
        val w = f.w; val h = f.h; val n = f.n
        val skin = FloatArray(n) { skinLikelihood(f.px[it]) }
        val out = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            out[i] = skin[i] * (0.35f + 0.65f * centerWeight(x, y, w, h, 0.28f, 0.32f, 0.45f))
        }
        MaskOps.boxMean(out, w, h, f.textureRadius, out, FloatArray(n))
        for (i in 0 until n) out[i] = smoothstep(0.2f, 0.45f, out[i])
        if (saliency != null) {
            require(saliency.size == n)
            // A salient object that touches skin (face above a shirt) is a person as a whole.
            val lab = Regions.label(BooleanArray(n) { saliency[it] > 0.5f || skin[it] > 0.5f }, w, h)
            if (lab.count > 0) {
                val skinCount = IntArray(lab.count + 1)
                val salientCount = IntArray(lab.count + 1)
                for (i in 0 until n) {
                    val id = lab.ids[i]
                    if (id == 0) continue
                    if (skin[i] > 0.5f) skinCount[id]++
                    if (saliency[i] > 0.5f) salientCount[id]++
                }
                for (i in 0 until n) {
                    val id = lab.ids[i]
                    if (id != 0 && salientCount[id] > 0 && skinCount[id] >= max(3, (lab.sizes[id] * 0.03f).toInt())) {
                        out[i] = max(out[i], max(saliency[i], smoothstep(0.2f, 0.5f, skin[i])))
                    }
                }
            }
        }
        return out
    }

    /**
     * Salient subject: color contrast against a k-means model of the border colors (the border
     * is assumed to be background), weighted toward the center, thresholded (Otsu) and reduced to
     * the dominant connected objects with their holes filled.
     */
    fun saliency(img: PixelBuffer): FloatArray {
        val f = Features(img)
        val w = f.w; val h = f.h; val n = f.n
        // Opponent color features: lightness 0..100, red-green and yellow-blue ~ +-120.
        val c0 = FloatArray(n); val c1 = FloatArray(n); val c2 = FloatArray(n)
        for (i in 0 until n) {
            val c = f.px[i]
            val r = red(c); val g = green(c); val b = blue(c)
            c0[i] = (0.299f * r + 0.587f * g + 0.114f * b) * 100f
            c1[i] = (r - g) * 120f
            c2[i] = ((r + g) * 0.5f - b) * 120f
        }
        val tmp = FloatArray(n)
        val blurR = max(1, f.longSide / 100)
        MaskOps.boxMean(c0, w, h, blurR, c0, tmp)
        MaskOps.boxMean(c1, w, h, blurR, c1, tmp)
        MaskOps.boxMean(c2, w, h, blurR, c2, tmp)

        val bw = max(1, (min(w, h) * 0.05f).toInt())
        var borderCount = 0
        for (y in 0 until h) for (x in 0 until w) if (isBorder(x, y, w, h, bw)) borderCount++
        val stride = max(1, sqrt(borderCount / 4000f).toInt())
        val samples = FloatArray(((borderCount / (stride * stride)) + w + h + 8) * 3)
        var m = 0
        for (y in 0 until h step stride) for (x in 0 until w step stride) {
            if (!isBorder(x, y, w, h, bw) || (m + 1) * 3 > samples.size) continue
            val i = y * w + x
            samples[m * 3] = c0[i]; samples[m * 3 + 1] = c1[i]; samples[m * 3 + 2] = c2[i]
            m++
        }
        if (m == 0) return FloatArray(n)
        val k = min(5, m)
        val centers = kMeans(samples, m, k, iterations = 10)

        val sal = tmp // reuse
        var maxD = 0f
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            for (j in 0 until k) {
                val d0 = c0[i] - centers[j * 3]; val d1 = c1[i] - centers[j * 3 + 1]; val d2 = c2[i] - centers[j * 3 + 2]
                val d = d0 * d0 + d1 * d1 + d2 * d2
                if (d < best) best = d
            }
            sal[i] = sqrt(best)
            if (sal[i] > maxD) maxD = sal[i]
        }
        val norm = max(percentile(sal, 0.98f, maxD), 20f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            sal[i] = min(1f, sal[i] / norm) * (0.3f + 0.7f * centerWeight(x, y, w, h, 0.3f, 0.3f, 0.5f))
        }
        // Hysteresis: objects are grown through moderately salient pixels (a face above a
        // vivid shirt) but must contain strongly salient ones.
        val t = otsu(sal).coerceIn(0.15f, 0.7f)
        val tLow = max(0.1f, 0.5f * t)
        val lab = Regions.label(BooleanArray(n) { sal[it] > tLow }, w, h)
        if (lab.count == 0) return FloatArray(n)
        val score = FloatArray(lab.count + 1)
        val peak = FloatArray(lab.count + 1)
        for (i in 0 until n) {
            val id = lab.ids[i]
            if (id != 0) { score[id] += sal[i]; if (sal[i] > peak[id]) peak[id] = sal[i] }
        }
        var best = 0f
        for (id in 1..lab.count) if (peak[id] > t && score[id] > best) best = score[id]
        val minSize = max(4, n / 500)
        val keepId = BooleanArray(lab.count + 1) {
            it > 0 && peak[it] > t && lab.sizes[it] >= minSize && score[it] >= 0.3f * best
        }
        val kept = BooleanArray(n) { keepId[lab.ids[it]] }
        if (kept.none { it }) return FloatArray(n)
        Regions.fillHoles(kept, w, h, maxHoleSize = n / 4)
        return soften(FloatArray(n) { if (kept[it]) 1f else 0f }, w, h)
    }

    /** Skin likelihood 0..1 of a color (Chai & Ngan YCbCr ranges with soft borders). */
    fun skinLikelihood(c: Int): Float {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        return trapezoid(cb, 72f, 80f, 122f, 130f) * trapezoid(cr, 128f, 136f, 170f, 178f) * smoothstep(35f, 70f, y)
    }

    // ------------------------------------------------------------------------------ helpers

    /** Largest per-channel change allowed between neighbouring sky pixels (0..1). */
    private const val SKY_MAX_STEP = 0.1f

    /** Largest per-channel difference of two colors, 0..1. */
    internal fun colorStep(a: Int, b: Int): Float {
        val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db)) / 255f
    }

    private fun red(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun green(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun blue(c: Int) = (c and 0xFF) / 255f

    /** Hue in degrees [0, 360); 0 for grays. */
    internal fun hue(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val d = mx - mn
        if (d <= 1e-6f) return 0f
        var hDeg = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (hDeg < 0f) hDeg += 360f
        return hDeg
    }

    /** 0 below [a], ramps up to 1 at [b], stays 1 until [c], ramps down to 0 at [d]. */
    internal fun trapezoid(x: Float, a: Float, b: Float, c: Float, d: Float): Float =
        smoothstep(a, b, x) * (1f - smoothstep(c, d, x))

    /** Gaussian center prior; sigmas and center Y are fractions of the image size. */
    private fun centerWeight(x: Int, y: Int, w: Int, h: Int, sx: Float, sy: Float, cy: Float): Float {
        val dx = (x + 0.5f) / w - 0.5f
        val dy = (y + 0.5f) / h - cy
        return exp(-(dx * dx / (2f * sx * sx) + dy * dy / (2f * sy * sy)))
    }

    private fun isBorder(x: Int, y: Int, w: Int, h: Int, bw: Int) = x < bw || y < bw || x >= w - bw || y >= h - bw

    /** Light anti-aliasing of a binary-ish mask. */
    private fun soften(m: FloatArray, w: Int, h: Int): FloatArray = MaskOps.boxMean(m, w, h, 1, m, FloatArray(m.size))

    /** Deterministic k-means on [count] 3-D samples; returns k*3 center coordinates. */
    internal fun kMeans(samples: FloatArray, count: Int, k: Int, iterations: Int): FloatArray {
        val centers = FloatArray(k * 3)
        for (j in 0 until k) {
            val s = (j.toLong() * count / k).toInt()
            centers[j * 3] = samples[s * 3]; centers[j * 3 + 1] = samples[s * 3 + 1]; centers[j * 3 + 2] = samples[s * 3 + 2]
        }
        val sums = DoubleArray(k * 3)
        val counts = IntArray(k)
        repeat(iterations) {
            sums.fill(0.0); counts.fill(0)
            for (s in 0 until count) {
                var best = 0; var bestD = Float.MAX_VALUE
                for (j in 0 until k) {
                    val d0 = samples[s * 3] - centers[j * 3]
                    val d1 = samples[s * 3 + 1] - centers[j * 3 + 1]
                    val d2 = samples[s * 3 + 2] - centers[j * 3 + 2]
                    val d = d0 * d0 + d1 * d1 + d2 * d2
                    if (d < bestD) { bestD = d; best = j }
                }
                counts[best]++
                sums[best * 3] += samples[s * 3].toDouble()
                sums[best * 3 + 1] += samples[s * 3 + 1].toDouble()
                sums[best * 3 + 2] += samples[s * 3 + 2].toDouble()
            }
            for (j in 0 until k) if (counts[j] > 0) {
                centers[j * 3] = (sums[j * 3] / counts[j]).toFloat()
                centers[j * 3 + 1] = (sums[j * 3 + 1] / counts[j]).toFloat()
                centers[j * 3 + 2] = (sums[j * 3 + 2] / counts[j]).toFloat()
            }
        }
        return centers
    }

    /** Value below which [fraction] of [values] (all within 0..[maxValue]) lie. */
    internal fun percentile(values: FloatArray, fraction: Float, maxValue: Float): Float {
        if (maxValue <= 0f || values.isEmpty()) return 0f
        val bins = 1024
        val hist = IntArray(bins)
        for (v in values) hist[min(bins - 1, max(0, (v / maxValue * (bins - 1)).toInt()))]++
        val target = (values.size * fraction).toLong()
        var acc = 0L
        for (b in 0 until bins) {
            acc += hist[b]
            if (acc >= target) return (b + 1f) / (bins - 1) * maxValue
        }
        return maxValue
    }

    /** Otsu threshold of values in 0..1. */
    internal fun otsu(values: FloatArray, bins: Int = 64): Float {
        val hist = IntArray(bins)
        for (v in values) hist[min(bins - 1, max(0, (v * bins).toInt()))]++
        val total = values.size.toDouble()
        var sumAll = 0.0
        for (b in 0 until bins) sumAll += b * hist[b].toDouble()
        var wB = 0.0; var sumB = 0.0; var bestVar = -1.0; var bestT = 0
        for (b in 0 until bins) {
            wB += hist[b]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += b * hist[b].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > bestVar) { bestVar = between; bestT = b }
        }
        return (bestT + 1f) / bins
    }
}

/** Per-image features shared by the heuristics (lazily computed). */
internal class Features(src: PixelBuffer) {
    val w = src.width
    val h = src.height
    val n = w * h
    val longSide = max(w, h)
    val px: IntArray = IntArray(n).also { dst -> val s = src.pixels; for (i in 0 until n) dst[i] = MaskOps.flattenOverWhite(s[i]) }

    /** Radius of the local-statistics window. */
    val textureRadius = max(1, (longSide / 200f).roundToInt())

    val lum: FloatArray by lazy { FloatArray(n) { MaskOps.luma01(px[it]) } }

    /** Local standard deviation of luma (0..0.5). */
    val texture: FloatArray by lazy {
        val tmp = FloatArray(n)
        val mean = MaskOps.boxMean(lum, w, h, textureRadius, FloatArray(n), tmp)
        val sq = FloatArray(n) { lum[it] * lum[it] }
        MaskOps.boxMean(sq, w, h, textureRadius, sq, tmp)
        for (i in 0 until n) sq[i] = sqrt(max(0f, sq[i] - mean[i] * mean[i]))
        sq
    }

    private val gradients: Pair<FloatArray, FloatArray> by lazy {
        val gx = FloatArray(n); val gy = FloatArray(n)
        val l = lum
        for (y in 0 until h) {
            val ym = max(0, y - 1) * w; val y0 = y * w; val yp = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xm = max(0, x - 1); val xp = min(w - 1, x + 1)
                gx[y0 + x] = ((l[ym + xp] + 2f * l[y0 + xp] + l[yp + xp]) - (l[ym + xm] + 2f * l[y0 + xm] + l[yp + xm])) / 4f
                gy[y0 + x] = ((l[yp + xm] + 2f * l[yp + x] + l[yp + xp]) - (l[ym + xm] + 2f * l[ym + x] + l[ym + xp])) / 4f
            }
        }
        gx to gy
    }

    /** Sobel derivatives of luma, normalized so a unit step gives 1. */
    val gradX: FloatArray get() = gradients.first
    val gradY: FloatArray get() = gradients.second
}
