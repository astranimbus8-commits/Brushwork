package com.brushwork.paint.brush

import kotlin.math.max

/**
 * How far apart dabs are stretched so a fast stroke with a very large brush never stalls a
 * frame (moved verbatim out of BrushTool's `Stroke.limitCost`, v1.5): the live stroke and the
 * offline replay of vector strokes (StrokeRaster) must space their dabs identically.
 */
internal object StrokeCost {
    /** Pixel operations a coverage-buffer (paint / erase) stroke may spend per input event. */
    const val BUFFER_BUDGET = 6_000_000f

    /** Pixel operations a direct (smudge / blur / watercolor) stroke may spend per input event. */
    const val DIRECT_BUDGET = 1_500_000f

    /**
     * Spacing multiplier for an input segment of [len] px drawn with a brush of current diameter
     * [size] and relative [spacing]: 1 while the dabs it needs cost at most [budget] ([dabCost]
     * of one dab of a given diameter), else the factor that brings them down to the budget.
     */
    inline fun spacingScale(len: Float, size: Float, spacing: Float, dabCost: (Float) -> Float, budget: Float): Float {
        val d = max(1f, size)
        val nominal = max(StrokeDynamics.MIN_SPACING_PX, spacing * d)
        val cost = len / nominal * dabCost(d)
        return if (cost > budget) cost / budget else 1f
    }
}
