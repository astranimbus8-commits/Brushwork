package com.brushwork.paint.ui.common

import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * Maps a value range to slider positions 0..1: linearly, or logarithmically so that wide
 * positive ranges (1..10000 px) give the small values room. A logarithmic scale that starts at 0
 * is offset (`ln(1 + v / pivot)`), so 0 stays reachable.
 */
class SliderScale private constructor(val min: Double, val max: Double, val log: Boolean) {

    /** Value at which a logarithmic scale bends: [min] itself, or 1/1000 of the span from 0. */
    private val pivot: Double = if (!log) 1.0 else if (min > 0.0) min else (max - min) / 1000.0
    private val logSpan: Double = if (log) ln1p((max - min) / pivot) else 1.0

    /** Slider position of [v] (clamped to the range; NaN -> 0). */
    fun fraction(v: Double): Float {
        if (v.isNaN()) return 0f
        val c = v.coerceIn(min, max)
        val f = if (log) ln1p((c - min) / pivot) / logSpan else (c - min) / (max - min)
        return f.toFloat().coerceIn(0f, 1f)
    }

    /** Value at slider position [fraction] (clamped to 0..1; NaN -> [min]). */
    fun value(fraction: Float): Double {
        if (fraction.isNaN()) return min
        val f = fraction.coerceIn(0f, 1f).toDouble()
        val v = if (log) min + pivot * expm1(f * logSpan) else min + f * (max - min)
        return v.coerceIn(min, max)
    }

    override fun equals(other: Any?): Boolean = other is SliderScale && other.min == min && other.max == max && other.log == log
    override fun hashCode(): Int = (min.hashCode() * 31 + max.hashCode()) * 31 + log.hashCode()
    override fun toString(): String = "SliderScale(${if (log) "log" else "linear"} $min..$max)"

    companion object {
        fun linear(min: Double, max: Double): SliderScale {
            require(min.isFinite() && max.isFinite() && max > min) { "bad slider range $min..$max" }
            return SliderScale(min, max, log = false)
        }

        fun log(min: Double, max: Double): SliderScale {
            require(min.isFinite() && max.isFinite() && max > min && min >= 0.0) { "bad log slider range $min..$max" }
            return SliderScale(min, max, log = true)
        }
    }
}

/** What a numeric field offers besides typing (see [NumberField]). */
enum class NumberAdjust {
    /** A slider when a sensible range is known, otherwise a scrub handle. */
    AUTO,

    /** Always the scrub handle (drag sideways), never a slider. */
    SCRUB,

    /** Only the text (and -/+ buttons): for fields that sit next to their own slider. */
    NONE,
}

/**
 * Pure helpers behind the compact sliders and scrub handles of numeric fields (JVM-testable):
 * which scale a range gets, how slider values are rounded, and how a horizontal drag turns into
 * a value.
 */
object NumberSliderMath {

    /**
     * Slider scale of a field: none unless [adjust] is AUTO; an explicit [sliderMin]..[sliderMax]
     * (clamped to the field's [min]..[max]) is always honored, otherwise [min]..[max] is used when
     * [autoScale] finds it suitable.
     */
    fun scaleFor(
        adjust: NumberAdjust,
        min: Double,
        max: Double,
        sliderMin: Double?,
        sliderMax: Double?,
        log: Boolean?,
    ): SliderScale? {
        if (adjust != NumberAdjust.AUTO) return null
        return if (sliderMin != null && sliderMax != null) {
            autoScale(maxOf(sliderMin, min), minOf(sliderMax, max), log, explicit = true)
        } else {
            autoScale(min, max, log, explicit = false)
        }
    }

    /** A positive range spanning at least this ratio (max / min) gets a logarithmic slider. */
    const val LOG_RATIO = 1000.0

    /**
     * Widest linear range that gets a slider when it only comes from a field's technical limits
     * (not an explicit slider range): beyond it one slider pixel would be dozens of units, and a
     * scrub handle is the better tool.
     */
    const val MAX_AUTO_LINEAR_SPAN = 5000.0

    /**
     * The slider scale for [min]..[max], or null when there should be none (unbounded or empty
     * range; a very wide linear range unless [explicit]). [log]: true / false forces the kind
     * (log needs [min] >= 0), null picks log for positive ranges spanning [LOG_RATIO] or more.
     */
    fun autoScale(min: Double, max: Double, log: Boolean? = null, explicit: Boolean = false): SliderScale? {
        if (!min.isFinite() || !max.isFinite() || !(max > min)) return null
        val useLog = when (log) {
            true -> min >= 0.0
            false -> false
            null -> min > 0.0 && max / min >= LOG_RATIO
        }
        if (useLog) return SliderScale.log(min, max)
        if (!explicit && max - min > MAX_AUTO_LINEAR_SPAN) return null
        return SliderScale.linear(min, max)
    }

    /**
     * Power of ten (its exponent) that slider values are rounded to: never finer than the
     * field shows ([decimals]); on a log scale three significant digits (12.3, 456, 7890); on a
     * linear one about 1/1000 of the span.
     */
    fun quantumExponent(v: Double, scale: SliderScale, decimals: Int): Int {
        val display = -decimals.coerceIn(0, 9)
        val natural = if (scale.log) {
            val a = abs(v)
            if (a > 0.0 && a.isFinite()) floor(log10(a)).toInt() - 2 else display
        } else {
            floor(log10((scale.max - scale.min) / 1000.0)).toInt()
        }
        return maxOf(display, natural)
    }

    /** [v] rounded to 10^[exponent] without binary noise (0.1 * 3 = 0.3, not 0.30000000000000004). */
    fun roundToPowerOfTen(v: Double, exponent: Int): Double {
        if (!v.isFinite()) return v
        return if (exponent < 0) {
            val f = 10.0.pow(-exponent)
            // Beyond 2^53 a double has no fractional digits left to clean (and a Long would overflow).
            if (abs(v * f) > 9.0e15) v else (v * f).roundToLong() / f
        } else {
            val f = 10.0.pow(exponent)
            if (abs(v / f) > 9.0e15) v else (v / f).roundToLong() * f
        }
    }

    /**
     * The value for slider position [fraction]: mapped through [scale], rounded to a sensible
     * precision, kept within the scale and within the field's [min]..[max]. The ends of the
     * slider give exactly the ends of its range.
     */
    fun sliderValue(fraction: Float, scale: SliderScale, decimals: Int, min: Double, max: Double): Double {
        val raw = scale.value(fraction)
        val rounded = when {
            fraction <= 0f -> scale.min
            fraction >= 1f -> scale.max
            else -> roundToPowerOfTen(raw, quantumExponent(raw, scale, decimals)).coerceIn(scale.min, scale.max)
        }
        return rounded.coerceIn(min, max)
    }

    /**
     * A number typed for a slider shown as value × [scale] ("57", "57%", "12,5 px", "-30°"),
     * back in slider units and clamped to [min]..[max]; null for text without a finite number.
     */
    fun parseTyped(text: String, scale: Float, min: Float, max: Float): Float? {
        val number = text.trim().filter { it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+' }
        val v = number.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        if (scale == 0f || !scale.isFinite()) return null
        val out = (v / scale).toFloat()
        return if (out.isFinite()) out.coerceIn(min, max) else null
    }

    /** Step of a scrub handle when the field has no -/+ step: 1 for whole or 1-decimal numbers, else one digit coarser than shown. */
    fun defaultDragStep(decimals: Int): Double = if (decimals <= 1) 1.0 else 10.0.pow(-(decimals.coerceAtMost(9) - 1))

    /** Drag distance (dp) per step for slow, short drags. */
    const val DP_PER_STEP = 6f

    /** Drag distance (dp) at which the scrub runs twice as fast; it keeps accelerating beyond. */
    const val ACCEL_DP = 120f

    /**
     * Whole steps for a horizontal drag of [dragDp] (signed): about one step per [DP_PER_STEP]
     * near the start, accelerating with the distance so long drags cover big ranges.
     */
    fun scrubSteps(dragDp: Float): Long {
        if (!dragDp.isFinite()) return 0
        val d = abs(dragDp).toDouble()
        val r = d / ACCEL_DP
        val steps = d / DP_PER_STEP * (1.0 + r * r)
        return (sign(dragDp.toDouble()) * floor(steps)).toLong()
    }

    /** The value a scrub that started at [start] shows after a drag of [dragDp], within [min]..[max]. */
    fun scrubValue(start: Double, dragDp: Float, step: Double, min: Double, max: Double): Double {
        if (!start.isFinite() || !(step > 0.0) || !step.isFinite()) return start.coerceIn(min, max)
        val v = start + scrubSteps(dragDp) * step
        // Round away the binary noise of the step without losing precision the start value had.
        val exp = minOf(floor(log10(step)).toInt(), -1) - 6
        return roundToPowerOfTen(v, exp.coerceAtLeast(-12)).coerceIn(min, max)
    }
}
