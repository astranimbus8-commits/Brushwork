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

    private fun clamp(v: Float, min: Float, max: Float): Float {
        if (v.isNaN()) return v
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return v.coerceIn(lo, hi)
    }
}
