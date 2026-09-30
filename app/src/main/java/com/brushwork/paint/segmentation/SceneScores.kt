package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Scene-parsing backend (the bundled LiteRT model on device, fakes in tests). */
fun interface SceneParser {
    /**
     * Class scores for ONE model input: an opaque [Letterbox.MODEL_SIZE]² RGB image (a letterboxed
     * picture with mid-gray padding, or a crop), or null if the model is unavailable.
     */
    fun run(input: PixelBuffer): SceneScores?

    /**
     * Loads the model now if it is not loaded yet (and whatever one-time checks it needs), so
     * that the next [run] costs one inference. Called before the pipeline times its first pass:
     * a cold start must not make the pass plan think every pass is that slow.
     */
    fun prepare() {}
}

/** What a scene model produced for one input. */
sealed class SceneScores {
    /**
     * Per-class logits on a [gridWidth]x[gridHeight] grid covering the whole input, NHWC
     * (`values[(y * gridWidth + x) * COUNT + c]`). Cell i is centered on input pixel
     * `(i + 0.5) * inputSize / gridWidth` (center-aligned, like the model's own bilinear resize).
     */
    class Logits(val gridWidth: Int, val gridHeight: Int, val values: FloatArray) : SceneScores()

    /** One class id per input pixel (models with a fused argmax). */
    class Labels(val size: Int, val ids: ByteArray) : SceneScores()
}

/** Subject (salient foreground) backend (ML Kit on device, fakes in tests). */
fun interface SubjectBackend {
    /** Foreground confidence 0..1 for each pixel of the opaque [image], or null if unavailable. */
    fun subjectMask(image: PixelBuffer): FloatArray?
}

/** Prompted (tap / scribble) segmentation model: MediaPipe MagicTouch on device, fakes in tests. */
fun interface InteractiveModel {
    /**
     * Object probability 0..1 for each pixel of the square opaque [rgb] input
     * ([InteractiveSegmenter.MODEL_SIZE]²), given a [prior] of the same size (1 = the user's
     * tap / scribble, 0 elsewhere). Null if the model is unavailable.
     */
    fun run(rgb: PixelBuffer, prior: FloatArray): FloatArray?
}

/**
 * Per-class log-probabilities of ONE model pass on its own grid ([gw]x[gh] cells of [stride]
 * input pixels, center-aligned), plus a per-cell validity weight 0..1 (0 = the cell shows
 * letterbox padding, not the picture).
 */
internal class PassGrid(val gw: Int, val gh: Int, val stride: Float, val logp: FloatArray, val weight: FloatArray)

/** Softmax / label helpers turning model outputs into [PassGrid]s (pure Kotlin). */
internal object SceneProbabilities {
    private const val C = SceneClasses.COUNT

    /** Block size (input px) at which argmax labels are turned into class fractions. */
    const val LABEL_BLOCK = 4

    /** Label smoothing for argmax outputs: p = (1 - s) * fraction + s / C. */
    const val LABEL_SMOOTHING = 0.05f

    /**
     * Converts [scores] of an [inputSize]² input into log-probabilities. Only the input pixels in
     * [valid] (x0, y0, x1, y1 in input pixels) show the picture; cells outside get weight 0 and
     * cells that straddle its border get a reduced weight. Null if [scores] is malformed.
     */
    fun fromScores(scores: SceneScores, inputSize: Int, valid: FloatArray): PassGrid? = when (scores) {
        is SceneScores.Logits -> fromLogits(scores, inputSize, valid)
        is SceneScores.Labels -> fromLabels(scores, inputSize, valid)
    }

    private fun fromLogits(s: SceneScores.Logits, inputSize: Int, valid: FloatArray): PassGrid? {
        val gw = s.gridWidth; val gh = s.gridHeight
        if (gw <= 0 || gh <= 0 || gw != gh || s.values.size != gw * gh * C) return null
        val stride = inputSize.toFloat() / gw
        val logp = FloatArray(gw * gh * C)
        val weight = FloatArray(gw * gh)
        for (cell in 0 until gw * gh) {
            val o = cell * C
            if (!logSoftmax(s.values, o, logp, o)) return null
            val cx = cell % gw; val cy = cell / gw
            weight[cell] = coverage(cx * stride, cy * stride, stride, valid)
        }
        return PassGrid(gw, gh, stride, logp, weight)
    }

    private fun fromLabels(s: SceneScores.Labels, inputSize: Int, valid: FloatArray): PassGrid? {
        val size = s.size
        if (size != inputSize || s.ids.size != size * size || size % LABEL_BLOCK != 0) return null
        val g = size / LABEL_BLOCK
        val logp = FloatArray(g * g * C)
        val weight = FloatArray(g * g)
        val counts = IntArray(C)
        val x0 = max(0, valid[0].toInt()); val y0 = max(0, valid[1].toInt())
        val x1 = min(size, kotlin.math.ceil(valid[2]).toInt()); val y1 = min(size, kotlin.math.ceil(valid[3]).toInt())
        val floor = LABEL_SMOOTHING / C
        for (cy in 0 until g) for (cx in 0 until g) {
            counts.fill(0)
            var n = 0
            for (y in max(y0, cy * LABEL_BLOCK) until min(y1, (cy + 1) * LABEL_BLOCK)) {
                for (x in max(x0, cx * LABEL_BLOCK) until min(x1, (cx + 1) * LABEL_BLOCK)) {
                    val id = s.ids[y * size + x].toInt() and 0xFF
                    counts[if (id < C) id else 0]++
                    n++
                }
            }
            val cell = cy * g + cx
            val o = cell * C
            if (n == 0) {
                for (c in 0 until C) logp[o + c] = ln(1f / C)
                continue
            }
            weight[cell] = n.toFloat() / (LABEL_BLOCK * LABEL_BLOCK)
            for (c in 0 until C) logp[o + c] = ln((1f - LABEL_SMOOTHING) * counts[c] / n + floor)
        }
        return PassGrid(g, g, LABEL_BLOCK.toFloat(), logp, weight)
    }

    /**
     * Weight of a cell covering [x, x+size) x [y, y+size) against the [valid] rectangle: 0 when
     * its center is outside, reduced (by the covered fraction) when it straddles the border.
     */
    private fun coverage(x: Float, y: Float, size: Float, valid: FloatArray): Float {
        val cx = x + size / 2; val cy = y + size / 2
        if (cx < valid[0] || cy < valid[1] || cx >= valid[2] || cy >= valid[3]) return 0f
        val ox = (min(x + size, valid[2]) - max(x, valid[0])) / size
        val oy = (min(y + size, valid[3]) - max(y, valid[1])) / size
        val f = ox.coerceIn(0f, 1f) * oy.coerceIn(0f, 1f)
        // A cell partly over padding still mostly sees the picture; keep it, trusted less.
        return if (f >= 0.999f) 1f else 0.5f * f
    }

    /**
     * log-softmax of [n] = COUNT values at [src][so..] into [dst][do..]. False for non-finite
     * input (a broken model output must not poison the fusion).
     */
    fun logSoftmax(src: FloatArray, so: Int, dst: FloatArray, dOff: Int): Boolean {
        var mx = Float.NEGATIVE_INFINITY
        for (c in 0 until C) {
            val v = src[so + c]
            if (!v.isFinite()) return false
            if (v > mx) mx = v
        }
        var sum = 0.0
        for (c in 0 until C) sum += exp((src[so + c] - mx).toDouble())
        val lse = mx + ln(sum).toFloat()
        for (c in 0 until C) dst[dOff + c] = src[so + c] - lse
        return true
    }

    /** Softmax of COUNT log-scores at [src][so..] into [dst][dOff..] (probabilities summing to 1). */
    fun softmax(src: FloatArray, so: Int, dst: FloatArray, dOff: Int) {
        var mx = Float.NEGATIVE_INFINITY
        for (c in 0 until C) if (src[so + c] > mx) mx = src[so + c]
        var sum = 0f
        for (c in 0 until C) {
            val e = exp(src[so + c] - mx)
            dst[dOff + c] = e
            sum += e
        }
        val inv = 1f / sum
        for (c in 0 until C) dst[dOff + c] *= inv
    }
}
