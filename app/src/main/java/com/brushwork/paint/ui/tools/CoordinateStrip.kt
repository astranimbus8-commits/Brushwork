package com.brushwork.paint.ui.tools

import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/*
 * The X / Y coordinates' pure math (v1.5 §4.6; v1.6 §3.7.8, area G), shared by the X / Y pill
 * ([CoordinatePill]): how values read, the absolute range screen readers set them over, and the
 * drag of a cell, where the number itself is the slider.
 */

/** Vertical finger travel (dp) away from a cell beyond which its drag is fine (× [FINE_SCALE]). */
internal const val FINE_DISTANCE_DP = 48f

/** Pull of a detent (dp of finger travel). */
internal const val DETENT_DP = 8f

/** Movement of the fine mode relative to the finger. */
internal const val FINE_SCALE = 0.1f

/** [v] rounded to 0.1. */
internal fun round1(v: Float): Float = round(v * 10f) / 10f

/** A length in px shown in [unit]: px with thousands separators, others with the unit's decimals. */
internal fun formatCoordinate(px: Float, unit: LengthUnit, dpi: Double): String {
    if (unit != LengthUnit.PX) return Units.format(px.toDouble(), unit, dpi, withSuffix = false)
    val v = round1(px)
    val whole = abs(v - round(v)) < 1e-3f
    val s = if (whole) String.format(Locale.US, "%,d", round(v).toLong()) else String.format(Locale.US, "%,.1f", v)
    return s.replace('-', '−')
}

/**
 * The absolute range of an axis of [extent] px for a value [v] (what a screen reader's "set
 * progress" moves over): the canvas and a quarter of it on each side, [v] included.
 */
internal fun axisRange(v: Float, extent: Float): ClosedFloatingPointRange<Float> {
    val lo = min(-0.25f * extent, v)
    val hi = max(1.25f * extent, v)
    return lo..hi
}

/**
 * How far a cell drag may take a value of an axis of [extent] px: a whole canvas beyond each edge
 * (and the value it started from), so a drag can't throw an object out of reach.
 */
internal fun dragRange(v: Float, extent: Float): ClosedFloatingPointRange<Float> {
    val lo = min(-extent, v)
    val hi = max(2f * extent, v)
    return lo..hi
}

/**
 * One drag of an X / Y cell (pure; §3.7.8 "the number is the slider"): the value follows the
 * finger's on-screen travel converted to document px ([docPerPx] = 1 / view zoom), so the object
 * moves with the finger at any zoom; a tenth of it while the finger is more than
 * [fineDistancePx] above or below the cell (re-anchored when the mode changes, so the value never
 * jumps); pulled onto [detents] (snapping: 0, the centre, the edge) within [detentPx] of finger
 * travel; else, with a Length [step] (increments on), on the multiples of the step. Detents win
 * over the step (snapping beats increments, §3.4). Clamped to [range].
 */
internal class PillDrag(
    private val start: Float,
    private val docPerPx: Float,
    private val fineDistancePx: Float,
    private val detents: List<Float>,
    private val detentPx: Float,
    private val step: Float?,
    private val range: ClosedFloatingPointRange<Float>,
) {
    private var anchorX = 0f
    private var anchorValue = start
    private var raw = start

    /** True while the finger is far enough above or below the cell for fine mode. */
    var fine = false
        private set

    /** The detent the value sits on (null when none). */
    var detent: Float? = null
        private set

    /** The finger went down at [x] (screen px). */
    fun down(x: Float) {
        anchorX = x
        anchorValue = start
        raw = start
        fine = false
        detent = null
    }

    /** The value for the finger at [x] (screen px), [dy] px above (−) or below (+) the cell's middle. */
    fun move(x: Float, dy: Float): Float {
        val nowFine = abs(dy) > fineDistancePx
        if (nowFine != fine) {
            fine = nowFine
            anchorX = x
            anchorValue = raw
        }
        val scale = if (fine) FINE_SCALE else 1f
        raw = (anchorValue + (x - anchorX) * docPerPx * scale).coerceIn(range.start, range.endInclusive)
        val reach = detentPx * docPerPx * scale
        var best: Float? = null
        for (d in detents) if (abs(d - raw) <= reach && (best == null || abs(d - raw) < abs(best - raw))) best = d
        detent = best
        if (best != null) return best
        if (step != null) return IncrementMath.snap(raw, step).coerceIn(range.start, range.endInclusive)
        return raw
    }
}
