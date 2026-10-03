package com.brushwork.paint.ui.filters

import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.snap.Increments
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * v1.6 §3.4 increments for a filter parameter slider (a filter session's sheet and the Masks
 * tool's Adjust sheet). Its kind or custom key is the design's inference made explicit — `%` →
 * Percent, `°` → Angle, anything else the custom step `"label|suffix"` (Exposure: `"Exposure|EV"`)
 * — and handed to the slider, so the slider (whose Step popup and landing are the shared
 * controls') and its −/+ buttons always use the same step. Every value is in the units the slider
 * shows (all `%` sliders run 0..100, `°` ones are degrees). With increments off, or no step for
 * the parameter, the −/+ keep the parameter's own nudge (I8: exactly v1.5).
 */
internal object ParamIncrements {
    /** The unit the slider shows after its number. */
    fun suffixOf(p: FilterParam.Slider): String = if (p.pixels) "px" else p.suffix

    /** The increment kind of [p]'s slider, or null when it takes a custom step ([keyOf]). */
    fun kindOf(p: FilterParam.Slider): IncrementKind? = when (suffixOf(p)) {
        "%" -> IncrementKind.PERCENT
        "°" -> IncrementKind.ANGLE
        else -> null
    }

    /** The custom step key of [p]'s slider (`"label|suffix"`), or null when it has a kind. */
    fun keyOf(p: FilterParam.Slider): String? = if (kindOf(p) == null) "${p.label}|${suffixOf(p)}" else null

    /** The step [p]'s slider moves by now (slider units), or null (increments off, or no step for it). */
    fun stepOf(p: FilterParam.Slider, inc: Increments?): Float? {
        if (inc == null) return null
        val kind = kindOf(p)
        val s = if (kind != null) inc.step(kind) else keyOf(p)?.let { inc.customStep(it) }
        return s?.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * One press of the −/+ buttons ([direction] −1 / +1) from [v]: with a step, the next multiple of
     * the step that way (from a value between two multiples, the nearer one in that direction; the
     * range ends stay reachable), held to the parameter's range and its own resolution; without
     * one, the parameter's own nudge.
     */
    fun nudge(p: FilterParam.Slider, v: Float, direction: Int, inc: Increments?): Float {
        val step = stepOf(p, inc) ?: return SliderFormat.nudge(p, v, direction)
        val up = direction > 0
        val q = v / step
        var n = if (up) floor(q + EPS) + 1f else ceil(q - EPS) - 1f
        var target = SliderFormat.snap(p, n * step)
        // A step that isn't a multiple of the parameter's own resolution (Hue in 7.5° steps, whole
        // degrees) can round back onto [v]: the press then goes one step further, so it always moves.
        val end = if (up) p.max else p.min
        for (i in 0 until MAX_EXTRA_STEPS) {
            val moved = if (up) target > v + TOL * max(1f, abs(v)) else target < v - TOL * max(1f, abs(v))
            if (moved || abs(target - end) <= TOL * max(1f, abs(end))) break
            n += if (up) 1f else -1f
            target = SliderFormat.snap(p, n * step)
        }
        return target
    }

    /** Tolerance (in steps) for a value that is already a multiple of the step. */
    private const val EPS = 1e-3f

    /** Relative tolerance of "the value moved". */
    private const val TOL = 1e-6f

    /** At most this many further steps when rounding to the parameter's resolution undoes one. */
    private const val MAX_EXTRA_STEPS = 4
}
