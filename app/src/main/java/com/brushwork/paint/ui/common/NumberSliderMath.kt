package com.brushwork.paint.ui.common

import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.Units
import com.brushwork.paint.model.IncrementKind
import kotlin.math.abs
import kotlin.math.ceil
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

/**
 * The pure rules of the v1.6 increments in the shared number controls (§3.4; area G): which
 * kind a control has, the custom key of one without a kind, and how sliders, -/+ buttons and
 * scrub handles move on the step's multiples. Steps are in the control's SHOWN unit (a 0..1
 * slider shown as a percentage steps by 5 %, i.e. 0.05 of its value). Typed values never come
 * through here: they are never quantized.
 */
object IncrementStepping {

    /**
     * The unit shown after a value: "45 %" → "%", "+0.50 EV" → "EV", "15.0°" → "°", "3.0 ×
     * width" → "× width". Text without a digit ("Off", "None") has no unit: "".
     */
    fun suffixOf(valueText: String): String {
        val i = valueText.indexOfLast { it.isDigit() }
        if (i < 0) return ""
        return valueText.substring(i + 1).trim()
    }

    /**
     * The kind a control's unit implies (design §3.4 (c)): `%` → PERCENT ("% of the path" too),
     * `°` → ANGLE, `px` → SIZE (a length in px is a [LengthField], which is LENGTH by itself);
     * anything else has no kind (it steps by its own custom step, if one is set).
     */
    fun kindForSuffix(suffix: String): IncrementKind? {
        val s = suffix.trim()
        return when {
            s.startsWith("%") -> IncrementKind.PERCENT
            s.startsWith("°") -> IncrementKind.ANGLE
            s == "px" -> IncrementKind.SIZE
            else -> null
        }
    }

    /** The custom-step key of a control without a kind: "$label|$suffix" (e.g. "Exposure|EV"). */
    fun customKey(label: String, suffix: String): String = "$label|$suffix"

    /**
     * The kind and custom key a control steps by (design §3.4 (c): the inference is "overridable
     * through the incrementKind / incrementKey parameters"): an explicit [kind] wins (no key);
     * an explicit [key] alone is a custom step under that key, whatever the unit implies (a
     * feather shown in % with "mask.feather"); otherwise the kind the [suffix] implies
     * ([kindForSuffix]); otherwise a custom step under "$label|$suffix" ([customKey]). Exactly one
     * of the two is non-null.
     */
    fun resolve(kind: IncrementKind?, key: String?, label: String, suffix: String): Pair<IncrementKind?, String?> = when {
        kind != null -> kind to null
        key != null -> null to key
        else -> kindForSuffix(suffix)?.let { it to null } ?: (null to customKey(label, suffix))
    }

    /**
     * Shown units per value unit of a slider whose caller doesn't say ([SliderTyping.scale]): a
     * percentage slider over 0..1 (or 0..2, scatter) shows its value × 100; others show it as is.
     */
    fun impliedScale(kind: IncrementKind?, rangeMax: Float): Float =
        if (kind == IncrementKind.PERCENT && rangeMax <= 2f) 100f else 1f

    /** A usable step: finite and > 0 (anything else means "no step"). */
    fun valid(step: Double?): Boolean = step != null && step.isFinite() && step > 0.0

    /**
     * [v] moved [n] steps of [step] along the step's multiples: the first step goes to the next
     * multiple in that direction (37 +1 → 40, 37 −1 → 30; 40 +1 → 50), each further one a whole
     * step. 0 steps (or no valid step) leave [v] as it is.
     */
    fun stepBy(v: Double, n: Long, step: Double): Double {
        if (!valid(step) || !v.isFinite() || n == 0L) return v
        var k = v / step
        val r = Math.rint(k)
        // A value on a multiple up to binary noise (0.30000000000000004 / 0.1) counts as on it.
        if (abs(k - r) < 1e-6) k = r
        val base = if (n > 0) floor(k) else ceil(k)
        return clean((base + n) * step, step)
    }

    /**
     * A slider value on the nearest multiple of [step] within [min]..[max], the ends staying
     * reachable ([IncrementMath.snapInRange]), without binary noise. No valid step: [v] clamped.
     */
    fun snapSlider(v: Double, step: Double?, min: Double, max: Double): Double {
        if (!valid(step) || !(min <= max)) return if (min <= max && v.isFinite()) v.coerceIn(min, max) else v
        val s = IncrementMath.snapInRange(v, step!!, min, max)
        if (s == min || s == max) return s
        return clean(s, step).coerceIn(min, max)
    }

    /** [v] (a multiple of [step] up to float noise) rounded to well below the step's precision. */
    private fun clean(v: Double, step: Double): Double {
        val exp = (floor(log10(step)).toInt() - 6).coerceIn(-12, 12)
        return NumberSliderMath.roundToPowerOfTen(v, exp)
    }

    /** "lengths", "sizes", "scales", "angles", "percentages": what a kind's steps are for (popup titles). */
    fun plural(kind: IncrementKind): String = when (kind) {
        IncrementKind.LENGTH -> "lengths"
        IncrementKind.SIZE -> "sizes"
        IncrementKind.SCALE -> "scales"
        IncrementKind.ANGLE -> "angles"
        IncrementKind.PERCENT -> "percentages"
    }

    /**
     * The name a custom key stands for: the label of "$label|$suffix" ("Exposure|EV" → "Exposure"),
     * or the last part of a dotted key, capitalized ("mask.feather" → "Feather").
     */
    fun nameOfKey(key: String): String {
        val name = if ('|' in key) key.substringBefore('|') else key.substringAfterLast('.')
        return name.trim().replaceFirstChar { it.uppercase() }.ifEmpty { key }
    }

    /** The unit of a custom key ("Exposure|EV" → "EV"; "" when it has none). */
    fun suffixOfKey(key: String): String = if ('|' in key) key.substringAfter('|').trim() else ""

    /** The Step popup's title: "Step for angles", "Step for Exposure". */
    fun popupTitle(kind: IncrementKind?, key: String?, name: String? = null): String = when {
        kind != null -> "Step for ${plural(kind)}"
        else -> "Step for ${name ?: key?.let(::nameOfKey).orEmpty()}"
    }

    /** A step value as shown in fields and toasts ("10", "0.25", "12.5"). */
    fun format(step: Float): String = Units.formatNumber(step.toDouble(), 3)
}
