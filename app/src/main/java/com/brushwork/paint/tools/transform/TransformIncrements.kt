package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.Units
import kotlin.math.abs

/**
 * The v1.6 increments (§3.4; area G) of the Transform and Shape gestures, pure and JVM-tested:
 * how a scale lands on the Scale step, and what the gesture readout says.
 *
 * Transform scales are percentages of the ORIGINAL size (the source the transform started from):
 * a box at 100 % that is scaled with a 10 % step goes to 110, 120 … or 90, 80 … % whatever its
 * size was when the gesture began, so the factor a gesture applies to its start state is the
 * snapped percentage divided by the start percentage. A mirrored axis (negative factor) is snapped
 * by its size and keeps its sign ([IncrementMath.snapFactor] and the percentage snaps are always
 * positive). A step that is NaN, infinite or ≤ 0 leaves the factor unchanged.
 */
object TransformIncrements {

    /**
     * The factor to apply to an axis whose scale was [startScale] when the gesture began (1 = the
     * original size, negative = mirrored) so that the axis ends on a multiple of [stepPercent]
     * percent of the original, near where the raw factor [k] would put it.
     */
    fun axisFactor(startScale: Float, k: Float, stepPercent: Float): Float =
        factorFor(abs(startScale) * 100f, k, stepPercent)

    /**
     * The uniform factor to apply to a box at [startPercent] of its original size (Transform's
     * `scalePercent`) so that it ends on a multiple of [stepPercent], near where [k] would put it.
     */
    fun uniformFactor(startPercent: Float, k: Float, stepPercent: Float): Float =
        factorFor(abs(startPercent), k, stepPercent)

    private fun factorFor(startPercent: Float, k: Float, stepPercent: Float): Float {
        if (!(startPercent > 0f) || !startPercent.isFinite() || !k.isFinite()) return k
        if (!(stepPercent > 0f) || !stepPercent.isFinite()) return k
        val p = IncrementMath.snapPercentOfOriginal(startPercent * abs(k), stepPercent)
        val kk = p / startPercent
        if (!kk.isFinite()) return k
        return if (k < 0f) -kk else kk
    }

    /**
     * A factor relative to the gesture start (a pinch of a shape: 1 = unchanged) on the Scale step's
     * multiples ([IncrementMath.snapFactor]); a negative factor keeps its sign.
     */
    fun relativeFactor(k: Float, stepPercent: Float): Float {
        if (!k.isFinite() || !(stepPercent > 0f) || !stepPercent.isFinite()) return k
        val r = IncrementMath.snapFactor(abs(k), stepPercent)
        return if (k < 0f) -r else r
    }
}

/**
 * What `controller.increments.readout` shows while a gesture is quantized (§3.4; the InfoChip
 * slot): "+30 px, −10 px", "120 %", "110 × 90 %", "45°", "40 px".
 */
object IncrementReadout {
    /** A move from the gesture start. */
    fun move(dx: Float, dy: Float): String = "${signed(dx)} px, ${signed(dy)} px"

    /** An absolute length (a width, a line's length). */
    fun length(px: Float): String = "${num(px)} px"

    /** A size of [w] × [h] px. */
    fun size(w: Float, h: Float): String = "${num(w)} × ${num(h)} px"

    /** A scale in percent. */
    fun percent(p: Float): String = "${num(p)} %"

    /** Two axis scales in percent (one when they are equal). */
    fun scale(px: Float, py: Float): String = if (abs(px - py) < 0.05f) percent(px) else "${num(px)} × ${num(py)} %"

    /** An absolute angle. */
    fun angle(deg: Float): String = "${num(deg)}°"

    private fun num(v: Float): String = Units.formatNumber(v.toDouble(), 1).replace('-', '−')

    private fun signed(v: Float): String = when {
        v > 0f -> "+" + num(v)
        v < 0f -> "−" + num(abs(v))
        else -> "0"
    }
}
