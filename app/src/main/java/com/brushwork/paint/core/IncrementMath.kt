package com.brushwork.paint.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * Snapping math of the v1.6 increments (§3.4; pure, frozen). A step that is NaN, infinite or
 * ≤ 0 means "no step": every function then returns its input unchanged (except [snapInRange],
 * which still clamps), as do non-finite inputs. Rounding is to the nearest multiple with halves
 * going up (`floor(x + 0.5)`), the same in every function, so a value exactly between two
 * multiples always goes the same way.
 */
object IncrementMath {
    private fun valid(step: Float) = step.isFinite() && step > 0f
    private fun valid(step: Double) = step.isFinite() && step > 0.0

    /** Nearest integer of [x] (halves up), as a double (no Int overflow for big quotients). */
    private fun roundHalfUp(x: Double): Double = floor(x + 0.5)

    /** [v] on the nearest multiple of [step] counted from [origin] (absolute values: coordinates, sizes). */
    fun snap(v: Float, step: Float, origin: Float = 0f): Float {
        if (!valid(step) || !v.isFinite() || !origin.isFinite()) return v
        val k = roundHalfUp((v.toDouble() - origin) / step)
        val r = (origin + k * step).toFloat()
        return if (r.isFinite()) r else v
    }

    /** A delta (a move from the gesture start) on the nearest multiple of [step]; 0 stays 0. */
    fun snapDelta(d: Float, step: Float): Float = snap(d, step, 0f)

    /**
     * A scale factor relative to the gesture start: `1 + round((k − 1) / s)·s` with `s =
     * stepPercent / 100` (10 % gives 0.9, 1.0, 1.1, 1.2 …), never below one step (`s`), so a
     * pinch can't collapse an object to nothing. The result is always positive: a caller with a
     * mirrored scale (negative [k]) snaps `|k|` and puts the sign back.
     */
    fun snapFactor(k: Float, stepPercent: Float): Float {
        if (!valid(stepPercent) || !k.isFinite()) return k
        val s = stepPercent / 100.0
        val r = 1.0 + roundHalfUp((k - 1.0) / s) * s
        return maxOf(r, s).toFloat()
    }

    /**
     * A percentage of the ORIGINAL size (Transform scale: 100, 110, 120 …, 90, 80 …) on the
     * nearest multiple of [stepPercent], never below one step.
     */
    fun snapPercentOfOriginal(percent: Float, stepPercent: Float): Float {
        if (!valid(stepPercent) || !percent.isFinite()) return percent
        return maxOf(snap(percent, stepPercent), stepPercent)
    }

    /** An absolute angle (degrees) on the nearest multiple of [step], normalized to (-180, 180]. */
    fun snapAngle(deg: Float, step: Float): Float {
        if (!valid(step) || !deg.isFinite()) return deg
        return normalizeDegrees(snap(deg, step))
    }

    /**
     * A slider value on the nearest multiple of [step] within [min]..[max] (absolute multiples,
     * from 0), but the range ends stay reachable: a value nearer to an end than to the nearest
     * multiple inside the range is that end. Values outside the range are clamped. No step: [v]
     * clamped.
     */
    fun snapInRange(v: Double, step: Double, min: Double, max: Double): Double {
        if (!(min <= max)) return v
        if (!v.isFinite()) return v
        val c = v.coerceIn(min, max)
        if (!valid(step)) return c
        if (c <= min) return min
        if (c >= max) return max
        var s = roundHalfUp(c / step) * step
        if (s > max) s -= step
        if (s < min) s += step
        if (s < min || s > max) {
            // No multiple inside the range: the nearer end.
            return if (c - min <= max - c) min else max
        }
        val ds = abs(c - s)
        if (c - min < ds) return min
        if (max - c < ds) return max
        return s
    }

    /** [deg] normalized to (-180, 180]. */
    fun normalizeDegrees(deg: Float): Float {
        if (!deg.isFinite()) return deg
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }
}
