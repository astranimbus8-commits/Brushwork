package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import kotlin.math.min

/**
 * The load-time check of the rewired scene model ([LiteRtSceneParser]), in pure Kotlin so it is
 * unit-tested: a synthetic scene, and how the logits model's answer is compared with what the
 * original fused-argmax model says for the same input.
 */
internal object SceneSelfTest {
    private const val C = SceneClasses.COUNT

    /** Share of grid cells that must agree with the argmax model (boundary cells may differ). */
    const val MIN_AGREEMENT = 0.85f

    /** A [size]² scene: a blue sky gradient over textured green ground. */
    fun image(size: Int): PixelBuffer {
        val img = PixelBuffer(size, size)
        val half = size / 2
        for (y in 0 until size) for (x in 0 until size) {
            img[x, y] = if (y < half) {
                val t = y.toFloat() / half
                rgb((70 + 80 * t).toInt(), (130 + 60 * t).toInt(), (220 + 25 * t).toInt())
            } else {
                val n = ((x * 7919 + y * 104729) % 41) - 20
                rgb(60 + n, 120 + n, 45 + n / 2)
            }
        }
        return img
    }

    /**
     * Fraction (0..1) of the cells of [logits] whose most likely class also appears in the
     * reference [labels] ([size]² class ids for the same input) at one of the cell's four inner
     * quarter points. The fused-argmax finalizer resizes these same logits before its argmax, so
     * a correctly rewired output agrees everywhere except on some class-boundary cells (the
     * resize may shift a boundary by up to a cell); a misrouted tensor, a wrong channel order or
     * a wrong dequantization does not.
     */
    fun argmaxAgreement(logits: SceneScores.Logits, labels: ByteArray, size: Int): Float {
        val gw = logits.gridWidth; val gh = logits.gridHeight
        if (gw <= 0 || gh <= 0 || logits.values.size != gw * gh * C || labels.size != size * size) return 0f
        val arg = SceneTargets.argmax(logits.values, gw * gh)
        val sx = size.toFloat() / gw; val sy = size.toFloat() / gh
        var agree = 0
        for (cy in 0 until gh) for (cx in 0 until gw) {
            val k = arg[cy * gw + cx]
            var hit = false
            for (qy in 0..1) for (qx in 0..1) {
                val x = min(size - 1, ((cx + 0.25f + 0.5f * qx) * sx).toInt())
                val y = min(size - 1, ((cy + 0.25f + 0.5f * qy) * sy).toInt())
                if ((labels[y * size + x].toInt() and 0xFF) == k) hit = true
            }
            if (hit) agree++
        }
        return agree.toFloat() / (gw * gh)
    }

    /**
     * The weaker check used when the reference model cannot run: the dominant class of the
     * sky half ([image]) differs from the dominant class of the ground half.
     */
    fun halvesDiffer(logits: SceneScores.Logits): Boolean {
        val gw = logits.gridWidth; val gh = logits.gridHeight
        if (gw <= 0 || gh <= 0 || logits.values.size != gw * gh * C) return false
        val arg = SceneTargets.argmax(logits.values, gw * gh)
        fun dominant(y0: Int, y1: Int): Int {
            val counts = IntArray(C)
            for (y in y0 until y1) for (x in 0 until gw) counts[arg[y * gw + x]]++
            return counts.indices.maxByOrNull { counts[it] } ?: 0
        }
        return dominant(gh / 8, gh * 3 / 8) != dominant(gh * 5 / 8, gh * 7 / 8)
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
