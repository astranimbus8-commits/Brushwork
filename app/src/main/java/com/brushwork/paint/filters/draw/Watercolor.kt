package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Photo -> watercolor painting: edge-preserving smoothing and color simplification form flat
 * washes, pigment pools darker along wash edges, color bleeds with a turbulent wobble, pigment
 * granulates, and the result sits on textured white paper. Sizes are relative to the image.
 */
class WatercolorFilter : Filter("draw.watercolor", "Watercolor", FilterCategory.DRAW) {

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("wash", "Wash smoothing", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("simplify", "Color simplification", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("edges", "Edge darkening", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("bleed", "Bleeding", 0f, 100f, 40f, 1f, "%"),
        FilterParam.Slider("granulation", "Granulation", 0f, 100f, 35f, 1f, "%"),
        FilterParam.Slider("paper", "Paper texture", 0f, 100f, 45f, 1f, "%"),
        FilterParam.Slider("lightness", "Paper whiteness", 0f, 100f, 25f, 1f, "%"),
        FilterParam.Slider("saturation", "Saturation", -100f, 100f, 0f, 1f),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val size = Stylize.workSize(src.width, src.height, WORK_LONG)
        val work = Stylize.downsampleLab(src, size[0], size[1], ctx)
        val ww = work.w; val wh = work.h
        val u = max(ww, wh) / 800f
        Stylize.fillTransparent(work, 3, 3, 4f * u, ctx)
        val seed = values.seed()
        val wash = values.float("wash").coerceIn(0f, 100f) / 100f
        val iterations = if (wash <= 0f) 0 else 1 + (wash * 2.99f).toInt()
        Stylize.bilateral(work, 3, 3, u * (1.5f + 3f * wash), 8f + 12f * wash, iterations, ctx)
        ctx.progress(0.4f)

        val simplify = values.float("simplify").coerceIn(0f, 100f) / 100f
        if (simplify > 0f) {
            val k = (24 - 18 * simplify).toInt().coerceIn(2, 24)
            val centers = kMeans(work, k, seed, ctx)
            quantizeTowards(work, centers, simplify * 0.75f, ctx)
        }
        ctx.progress(0.55f)
        // Wet-edge pigment pooling: strength from the lightness gradient of the washes.
        val edges = values.float("edges").coerceIn(0f, 100f) / 100f
        val edgeMap = if (edges > 0f) {
            val g = Stylize.gradient(work[0], ww, wh, ctx)
            val blurred = FilterMath.gaussianBlurPlane(g, ww, wh, 0.8f * u, ctx)
            for (i in blurred.indices) blurred[i] = (blurred[i] / 12f).coerceIn(0f, 1f)
            blurred
        } else null
        ctx.progress(0.7f)

        val bufLong = max(src.width, src.height).toFloat()
        val ub = bufLong / 800f // relative unit in output pixels
        val bleedAmp = values.float("bleed").coerceIn(0f, 100f) / 100f * 4f * ub
        val bleedPeriod = 28f * ub
        val gran = values.float("granulation").coerceIn(0f, 100f) / 100f
        val grain = max(0.75f, 1.3f * ub)
        val paper = values.float("paper").coerceIn(0f, 100f) / 100f
        val lift = 1f - 0.6f * values.float("lightness").coerceIn(0f, 100f) / 100f
        val sat = max(0f, 1f + values.float("saturation").coerceIn(-100f, 100f) / 100f)
        val sx = ww.toFloat() / src.width; val sy = wh.toFloat() / src.height
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val alpha = c ushr 24
            if (alpha == 0) return@mapXY 0
            val fx = x + 0.5f; val fy = y + 0.5f
            var px = fx; var py = fy
            if (bleedAmp > 0f) {
                px += bleedAmp * Noise.perlin(fx / bleedPeriod, fy / bleedPeriod, seed)
                py += bleedAmp * Noise.perlin(fx / bleedPeriod + 17.3f, fy / bleedPeriod - 9.1f, seed + 1)
            }
            val wx = px * sx; val wy = py * sy
            val l = work.sample(0, wx, wy)
            val a = work.sample(1, wx, wy) * sat
            val b = work.sample(2, wx, wy) * sat
            val rgb = Lab.toRgb(l, a, b)
            // Pigment density per channel (0 = white paper).
            var density = 1f
            if (edgeMap != null) density += 0.9f * edges * Planes.sample(edgeMap, ww, wh, wx, wy)
            if (gran > 0f) {
                val g = FilterMath.valueNoise(fx / grain, fy / grain, seed + 11) * 0.6f +
                    FilterMath.valueNoise(fx / (grain * 3.1f), fy / (grain * 3.1f), seed + 12) * 0.4f
                density *= 1f + 1.1f * gran * (g - 0.5f)
            }
            var shade = 1f
            if (paper > 0f) {
                // Signed paper height: pigment settles in the valleys, the peaks catch light.
                val h = Noise.fbm(fx, fy, 14f * ub, 1.2f * ub, 0.55f, seed + 23, Noise.PLAIN, skipBelow = 0.75f)
                density *= 1f - 0.5f * paper * h
                shade = 1f + 0.12f * paper * h
            }
            density *= lift
            val r = pigment((rgb shr 16) and 0xFF, density, shade)
            val gg = pigment((rgb shr 8) and 0xFF, density, shade)
            val bb = pigment(rgb and 0xFF, density, shade)
            (alpha shl 24) or (r shl 16) or (gg shl 8) or bb
        }
    }

    private fun pigment(v: Int, density: Float, shade: Float): Int {
        val absorb = (255 - v) * density
        return ((255f - absorb) * shade).toInt().coerceIn(0, 255)
    }

    /** Deterministic k-means in Lab on a subsample of opaque working pixels; returns k*3 centers. */
    private fun kMeans(work: Planes, k: Int, seed: Int, ctx: FilterContext): FloatArray {
        val n = work.w * work.h
        val stride = max(1, n / 12000)
        val lp = work[0]; val ap = work[1]; val bp = work[2]; val alpha = work[3]
        var count = 0
        for (i in 0 until n step stride) if (alpha[i] > 0.1f) count++
        if (count == 0) return FloatArray(3) { if (it == 0) 100f else 0f }
        val sm = FloatArray(count * 3)
        var s = 0
        for (i in 0 until n step stride) if (alpha[i] > 0.1f) {
            sm[s * 3] = lp[i]; sm[s * 3 + 1] = ap[i]; sm[s * 3 + 2] = bp[i]; s++
        }
        val kk = min(k, count)
        val centers = FloatArray(kk * 3)
        // Farthest-point initialisation from a seeded first pick.
        val first = Random(seed).nextInt(count)
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

    private fun quantizeTowards(work: Planes, centers: FloatArray, amount: Float, ctx: FilterContext) {
        val k = centers.size / 3
        val lp = work[0]; val ap = work[1]; val bp = work[2]
        val w = work.w
        Parallel.forRows(work.h) { y0, y1 ->
            ctx.checkCancelled()
            for (i in y0 * w until y1 * w) {
                val ci = nearest(lp[i], ap[i], bp[i], centers, k) * 3
                lp[i] += (centers[ci] - lp[i]) * amount
                ap[i] += (centers[ci + 1] - ap[i]) * amount
                bp[i] += (centers[ci + 2] - bp[i]) * amount
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

    private companion object { const val WORK_LONG = 1280 }
}
