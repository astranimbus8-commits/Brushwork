package com.brushwork.paint.ui.editor

import com.brushwork.paint.core.Units
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

/** Value mappings, labels and typed-value parsing for the editor's brush sliders and readouts. */
object SliderMath {
    const val MIN_BRUSH_SIZE = 0.5f
    const val MAX_BRUSH_SIZE = 1000f

    private val logRange = ln(MAX_BRUSH_SIZE / MIN_BRUSH_SIZE)

    /** Brush diameter (px) -> slider position 0..1 (logarithmic, so small sizes get room). */
    fun sizeToFraction(size: Float): Float =
        (ln(size.coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE) / MIN_BRUSH_SIZE) / logRange).coerceIn(0f, 1f)

    /** Slider position 0..1 -> brush diameter (px), rounded to a sensible step. */
    fun fractionToSize(fraction: Float): Float {
        val raw = MIN_BRUSH_SIZE * exp(fraction.coerceIn(0f, 1f) * logRange)
        return roundSize(raw).coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE)
    }

    /** 0.1 px steps below 10 px, whole pixels above. */
    fun roundSize(size: Float): Float = if (size < 10f) (size * 10f).roundToInt() / 10f else size.roundToInt().toFloat()

    /** "2.5", "12", "12.5" (a typed size keeps its tenth), "120". */
    fun formatSize(size: Float): String = Units.formatNumber(size.toDouble(), 1)

    fun formatPercent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"

    /** "150%", or one decimal for small zooms ("2.5%"). */
    fun formatZoom(scale: Float): String {
        val pct = scale * 100f
        return if (pct < 10f) "${Units.formatNumber(pct.toDouble(), 1)}%" else "${pct.roundToInt()}%"
    }

    // ------------------------------------------------------------------ typed values

    /**
     * Parses a number the user typed ("12.5", "12,5", "12.5 px", "85 %"), clamped to
     * [min]..[max]. Null for anything that is not a finite number (the input is then ignored).
     */
    fun parseValue(text: String, min: Double, max: Double): Double? {
        val cleaned = text.trim().removeSuffix("%").removeSuffix("px").removeSuffix("PX").trim()
        if (cleaned.isEmpty()) return null
        val v = Units.parse(cleaned)?.takeIf { it.isFinite() } ?: return null
        return v.coerceIn(min, max)
    }

    /** A typed brush size in px (clamped, to the tenth of a pixel), or null for invalid text. */
    fun parseSize(text: String): Float? =
        parseValue(text, MIN_BRUSH_SIZE.toDouble(), MAX_BRUSH_SIZE.toDouble())
            ?.let { ((it * 10.0).roundToInt() / 10f).coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE) }

    /** A typed opacity in percent ("85" or "85%") as a 0..1 fraction in whole percents, or null. */
    fun parsePercent(text: String): Float? = parseValue(text, 0.0, 100.0)?.let { it.roundToInt() / 100f }

    /**
     * The next brush size for a -/+ button: steps that grow with the size (0.1 px for tiny
     * brushes, 5 px for huge ones), always landing on a multiple of the step.
     */
    fun stepSize(size: Float, up: Boolean): Float {
        val s = size.coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE)
        // Going down, the step of the range just below applies (100 -> 99, not 95).
        val probe = if (up) s else s - 0.0001f
        val step = when {
            probe < 2f -> 0.1f
            probe < 10f -> 0.5f
            probe < 100f -> 1f
            else -> 5f
        }
        val k = s / step
        val next = if (up) (kotlin.math.floor(k + 1e-3f) + 1f) * step else (kotlin.math.ceil(k - 1e-3f) - 1f) * step
        return roundSize(next).coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE)
    }

    /** The next opacity (0..1) for a -/+ button: whole 1 % steps. */
    fun stepPercent(fraction: Float, up: Boolean): Float {
        val pct = (fraction.coerceIn(0f, 1f) * 100f).roundToInt()
        return ((pct + if (up) 1 else -1).coerceIn(0, 100)) / 100f
    }

    // ------------------------------------------------------------------ v1.6: ibis slider rows, increments

    /** The ibisPaint readout of a brush size: always one decimal ("72.0", "2.5", "1000.0"). */
    fun formatSizeFixed(size: Float): String = String.format(java.util.Locale.US, "%.1f", size.toDouble())

    /** A slider size on the Size increment [step] (px): multiples of it, with 0.5 and 1000 still reachable. */
    fun snapSize(size: Float, step: Float): Float =
        com.brushwork.paint.core.IncrementMath.snapInRange(size.toDouble(), step.toDouble(), MIN_BRUSH_SIZE.toDouble(), MAX_BRUSH_SIZE.toDouble()).toFloat()

    /**
     * The next brush size for a -/+ button while the Size increment is [step] px: the next multiple
     * of the step up or down (from an off-step value, the nearest one in that direction), clamped
     * to 0.5..1000.
     */
    fun stepSizeBy(size: Float, up: Boolean, step: Float): Float {
        if (!step.isFinite() || step <= 0f) return stepSize(size, up)
        val s = size.coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE).toDouble()
        val k = s / step
        val next = if (up) (kotlin.math.floor(k + 1e-6) + 1.0) * step else (kotlin.math.ceil(k - 1e-6) - 1.0) * step
        return next.toFloat().coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE)
    }

    /** The next opacity (0..1) for a -/+ button while the Percent increment is [stepPercent] %. */
    fun stepPercentBy(fraction: Float, up: Boolean, stepPercent: Float): Float {
        if (!stepPercent.isFinite() || stepPercent <= 0f) return stepPercent(fraction, up)
        val pct = fraction.coerceIn(0f, 1f) * 100.0
        val k = pct / stepPercent
        val next = if (up) (kotlin.math.floor(k + 1e-6) + 1.0) * stepPercent else (kotlin.math.ceil(k - 1e-6) - 1.0) * stepPercent
        return (next.coerceIn(0.0, 100.0) / 100.0).toFloat()
    }
}
