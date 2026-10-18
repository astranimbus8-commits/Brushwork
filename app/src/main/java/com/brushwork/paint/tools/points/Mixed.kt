package com.brushwork.paint.tools.points

import com.brushwork.paint.core.Expressions
import com.brushwork.paint.core.Units

/**
 * v1.7 (item 1, design §3.1 and §4.5, I12): the value of a property over the selected points:
 * one value ([Same]) or differing ones ([Spread], shown as "Mixed").
 */
sealed interface Mixed<out T> {
    data class Same<T>(val value: T) : Mixed<T>
    data class Spread<T>(val min: T, val max: T) : Mixed<T>

    companion object {
        /** Null for no values; [Same] when every value is equal (exactly), else the [Spread] of the finite ones. */
        fun of(values: FloatArray): Mixed<Float>? {
            if (values.isEmpty()) return null
            val first = values[0]
            if (values.all { it == first || (it.isNaN() && first.isNaN()) }) return Same(first)
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (v in values) {
                if (v.isNaN()) continue
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return if (lo <= hi) Spread(lo, hi) else Same(first)
        }

        /** Null for no values; [Same] when all are equal, else `Spread(false, true)` (the three-state chip's "Mixed"). */
        fun of(values: BooleanArray): Mixed<Boolean>? {
            if (values.isEmpty()) return null
            val first = values[0]
            return if (values.all { it == first }) Same(first) else Spread(false, true)
        }
    }
}

/**
 * v1.7 (item 1, design §3.1): edits applied to each selected point's value at once. Every result
 * is a new array of the same size, each value clamped to `min..max`.
 */
object MixedEdit {
    /** A drag on a multiplicative field (thickness, weight): each value × (now / start); 0 stays 0; clamped. */
    fun scaled(values: FloatArray, start: Float, now: Float, min: Float, max: Float): FloatArray {
        val k = now / start
        if (!k.isFinite()) return values.copyOf()
        return FloatArray(values.size) { i ->
            val v = values[i]
            if (v == 0f) 0f else clamp(v * k, min, max)
        }
    }

    /** A drag on an additive field (roundness px): each value + delta; clamped. */
    fun shifted(values: FloatArray, delta: Float, min: Float, max: Float): FloatArray {
        if (!delta.isFinite()) return values.copyOf()
        return FloatArray(values.size) { i -> clamp(values[i] + delta, min, max) }
    }

    /**
     * Typed text: an absolute expression sets all; a relative one (leading × ÷ * / x) applies to
     * each. Null = invalid. Absolute text reads as `Units.parse` reads it (plain numbers the v1.6
     * way, I13).
     */
    fun typed(values: FloatArray, text: String, min: Float, max: Float): FloatArray? {
        if (Expressions.isRelative(text)) {
            val out = FloatArray(values.size)
            for (i in values.indices) {
                val r = Expressions.evaluate(text, values[i].toDouble()) as? Expressions.Result.Value ?: return null
                val v = r.value.toFloat()
                if (!v.isFinite()) return null
                out[i] = clamp(v, min, max)
            }
            return out
        }
        val v = Units.parse(text)?.toFloat()?.takeIf { it.isFinite() } ?: return null
        val c = clamp(v, min, max)
        return FloatArray(values.size) { c }
    }

    /**
     * v1.9 (R1, R2): a thumb drag on a group slider. [values] are the points' values when the
     * drag began, [startMean] their mean (where the thumb sat), [thumb] where it is now. Each
     * value scales by `thumb / startMean`, so a taper keeps its ratios; a value at 0 rises by
     * `thumb - startMean` when that is positive (else stays 0); when [startMean] is 0 (every
     * value at 0) every value takes [thumb]. Clamped; NaN values stay as they are.
     */
    fun dragged(values: FloatArray, startMean: Float, thumb: Float, min: Float, max: Float): FloatArray {
        if (!thumb.isFinite() || !startMean.isFinite()) return values.copyOf()
        if (startMean <= 0f) return FloatArray(values.size) { i -> if (values[i].isNaN()) values[i] else clamp(thumb, min, max) }
        val k = thumb / startMean
        val rise = (thumb - startMean).coerceAtLeast(0f)
        return FloatArray(values.size) { i ->
            val v = values[i]
            when {
                v.isNaN() -> v
                v == 0f -> clamp(rise, min, max)
                else -> clamp(v * k, min, max)
            }
        }
    }

    /**
     * v1.9 (R1, R2): ‹ › on a group: each value moves to its own next ([up]) or previous value on
     * the [step] grid (a value on the grid moves one whole step; 0 goes up to one step). Clamped.
     */
    fun stepped(values: FloatArray, up: Boolean, step: Float, min: Float, max: Float): FloatArray {
        if (!step.isFinite() || step <= 0f) return values.copyOf()
        return FloatArray(values.size) { i ->
            val v = values[i]
            if (v.isNaN()) {
                v
            } else {
                val g = v / step
                val grid = if (up) kotlin.math.floor(g + 1e-3f) + 1f else kotlin.math.ceil(g - 1e-3f) - 1f
                clamp(grid * step, min, max)
            }
        }
    }

    /**
     * v1.9 (R1): typed text that starts with `+` or `-` ("+10", "-10"): the number after the sign
     * is added to (or taken from) each value; clamped. Null when [text] is not such an offset
     * (then [typed] reads it: `Expressions` takes a leading sign as the number's own).
     */
    fun offset(values: FloatArray, text: String, min: Float, max: Float): FloatArray? {
        val t = text.trim()
        if (t.length < 2 || (t[0] != '+' && t[0] != '-')) return null
        val d = Units.parse(t.substring(1).trim())?.toFloat()?.takeIf { it.isFinite() } ?: return null
        val delta = if (t[0] == '-') -d else d
        return FloatArray(values.size) { i -> clamp(values[i] + delta, min, max) }
    }

    /** The mean of the finite [values] (0 for none): where a group slider's thumb sits. */
    fun mean(values: FloatArray): Float {
        var sum = 0.0
        var n = 0
        for (v in values) if (v.isFinite()) { sum += v; n++ }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    private fun clamp(v: Float, min: Float, max: Float): Float {
        if (v.isNaN()) return v
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return v.coerceIn(lo, hi)
    }
}
