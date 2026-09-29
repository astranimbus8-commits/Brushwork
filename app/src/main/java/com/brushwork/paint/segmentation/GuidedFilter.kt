package com.brushwork.paint.segmentation

import com.brushwork.paint.core.Parallel

/**
 * Gray-guide guided filter (He, Sun & Tang 2010): an edge-preserving smoother that makes the
 * output locally a linear function of the guide, so mask edges snap to image edges.
 * Pure Kotlin; O(n) in the number of pixels regardless of the radius.
 */
object GuidedFilter {

    /**
     * Averaged linear coefficients so that `q = meanA·I + meanB` is the filtered [p]. Returning the
     * coefficients (instead of q) enables the fast guided filter: compute them at a working
     * resolution and apply them to a full-resolution guide ([MaskOps.guidedUpsample]).
     *
     * @param guide guide image I (0..1), size w*h
     * @param p input to filter (e.g. a coarse mask 0..1), size w*h
     * @param r window radius in pixels
     * @param eps regularization; edges whose guide variance is well above eps are preserved
     * @return (meanA, meanB), freshly allocated
     */
    fun coefficients(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): Pair<FloatArray, FloatArray> {
        val n = w * h
        require(guide.size == n && p.size == n) { "size mismatch" }
        require(eps > 0f)
        val tmp = FloatArray(n)
        val meanI = MaskOps.boxMean(guide, w, h, r, FloatArray(n), tmp)
        val meanP = MaskOps.boxMean(p, w, h, r, FloatArray(n), tmp)
        val prod = FloatArray(n)
        Parallel.forRange(n, 4096) { s, e -> for (i in s until e) prod[i] = guide[i] * guide[i] }
        val corrII = MaskOps.boxMean(prod, w, h, r, FloatArray(n), tmp)
        Parallel.forRange(n, 4096) { s, e -> for (i in s until e) prod[i] = guide[i] * p[i] }
        val corrIP = MaskOps.boxMean(prod, w, h, r, prod, tmp)
        // a -> corrIP storage, b -> meanP storage
        Parallel.forRange(n, 4096) { s, e ->
            for (i in s until e) {
                val mi = meanI[i]
                val varI = corrII[i] - mi * mi
                val covIP = corrIP[i] - mi * meanP[i]
                val a = covIP / ((if (varI > 0f) varI else 0f) + eps)
                corrIP[i] = a
                meanP[i] = meanP[i] - a * mi
            }
        }
        val meanA = MaskOps.boxMean(corrIP, w, h, r, corrII, tmp)
        val meanB = MaskOps.boxMean(meanP, w, h, r, meanI, tmp)
        return meanA to meanB
    }

    /** Guided filter of [p] with [guide] at the same resolution; optionally clamped to 0..1. */
    fun filter(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float, clamp: Boolean = true): FloatArray {
        val (a, b) = coefficients(guide, p, w, h, r, eps)
        val out = a // reuse
        for (i in out.indices) {
            val q = a[i] * guide[i] + b[i]
            out[i] = if (clamp) MaskOps.clamp01(q) else q
        }
        return out
    }
}
