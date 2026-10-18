package com.brushwork.paint.ui.vector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.Units
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.tools.points.MixedEdit
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.points.GroupSlider
import com.brushwork.paint.vector.VSpline
import kotlin.math.roundToInt

/**
 * v1.9 (R1, R2): what the several points' controls do ([GroupThicknessControl], [GroupWeightControl]
 * and their typing dialogs), on the selected points of [tool]: their thickness in percent
 * ([Kind.THICKNESS], 0–300 %) or, in PATH mode, their weight ([Kind.WEIGHT], 0.1–10). Not a
 * composable, so the rules are tested on the tool alone.
 *
 * - A thumb drag ([begin], [drag] or [slideTo], [end]) is relative: [MixedEdit.dragged] from the
 *   values captured at its start (a taper keeps its ratios, a point at 0 % rises by the offset,
 *   points all at 0 % take the thumb). One in-tool step per drag.
 * - ‹ › ([step]): each point to its own next or previous value on the Percent grid.
 * - Typed text ([type]): "+10" / "-10" add to each, "*2" / "/2" scale each, a number sets all.
 *
 * Every result is clamped to [min]..[max].
 */
internal class CurveGroupEdit(private val tool: CurveTool, val kind: Kind) : GroupSlider {

    enum class Kind { THICKNESS, WEIGHT }

    /** The thumb's value while a drag runs (percent or weight; Compose state), else null. */
    var thumb: Float? by mutableStateOf(null)
        private set

    /** The mean of the values when the drag began: where the thumb sat. */
    var startMean: Float = 0f
        private set

    private var base: FloatArray? = null
    private var indices: List<Int> = emptyList()
    private var startFraction = 0f

    val min: Float get() = if (kind == Kind.THICKNESS) 0f else VSpline.MIN_WEIGHT
    val max: Float get() = if (kind == Kind.THICKNESS) MAX_THICKNESS_PERCENT else VSpline.MAX_WEIGHT

    /** A drag is running. */
    val dragging: Boolean get() = base != null

    /** The selected points' values (percent or weight), in selection order. */
    fun values(): FloatArray = if (kind == Kind.THICKNESS) percents(tool.selectedWidths()) else tool.selectedWeights()

    /** Where the thumb sits: the drag's value, else the selected points' mean. */
    fun thumbValue(): Float = thumb ?: MixedEdit.mean(values())

    /** The slider's position for value [v]: percent on a linear 0–300 track; a weight on v1.6's log track. */
    fun toFraction(v: Float): Float =
        if (kind == Kind.THICKNESS) v / MAX_THICKNESS_PERCENT else SplineEditing.weightToFraction(v)

    /** The value at slider position [f] (see [toFraction]). */
    fun fromFraction(f: Float): Float =
        if (kind == Kind.THICKNESS) f * MAX_THICKNESS_PERCENT else SplineEditing.fractionToWeight(f)

    /** The Percent step of ‹ › and of a thickness drag (5 % by default, as for one point). */
    fun thicknessStep(): Float = tool.controller.increments.step(IncrementKind.PERCENT) ?: THICKNESS_STEP

    override fun fraction(): Float = toFraction(thumbValue()).coerceIn(0f, 1f)

    /** A drag starts: the selected points and their values are captured; one in-tool step begins. */
    override fun begin() {
        if (base != null) return
        val b = values()
        if (b.isEmpty()) return
        indices = tool.pointSelection.indices
        base = b
        startMean = MixedEdit.mean(b)
        startFraction = toFraction(startMean)
        tool.beginNumericEdit()
        thumb = startMean
    }

    /**
     * The finger travelled [deltaFraction] of the track from where it went down. Thickness: the
     * thumb moves from the start mean by whole Percent steps; weight: along the log track (on the
     * Weight item's custom step when one is set).
     */
    override fun drag(deltaFraction: Float) {
        if (base == null || !deltaFraction.isFinite()) return
        val t = when (kind) {
            Kind.THICKNESS -> {
                val s = thicknessStep().takeIf { it.isFinite() && it > 0f } ?: THICKNESS_STEP
                startMean + (deltaFraction * MAX_THICKNESS_PERCENT / s).roundToInt() * s
            }
            Kind.WEIGHT -> {
                val w = fromFraction((startFraction + deltaFraction).coerceIn(0f, 1f))
                val s = tool.controller.increments.customStep(PATH_WEIGHT_KEY)
                if (s == null) w else IncrementMath.snapInRange(w.toDouble(), s.toDouble(), VSpline.MIN_WEIGHT.toDouble(), VSpline.MAX_WEIGHT.toDouble()).toFloat()
            }
        }
        dragTo(t)
    }

    /** The thumb is at [value] (percent or weight): every captured point follows ([MixedEdit.dragged]). */
    fun dragTo(value: Float) {
        val b = base ?: return
        if (!value.isFinite()) return
        val t = value.coerceIn(min, max)
        thumb = t
        apply(indices, MixedEdit.dragged(b, startMean, t, min, max))
    }

    /** The slider itself moved to [sliderValue] (TalkBack's set-progress, a key): a drag to there. */
    fun slideTo(sliderValue: Float) {
        begin()
        dragTo(if (kind == Kind.THICKNESS) sliderValue else fromFraction(sliderValue))
    }

    /** The drag is over: its in-tool step is complete. */
    override fun end() {
        if (base == null) return
        base = null
        indices = emptyList()
        thumb = null
        tool.endNumericEdit()
    }

    /** ‹ ([up] false) or › : each point to its own previous or next value on the Percent grid. */
    fun step(up: Boolean) {
        val v = values()
        if (v.isEmpty()) return
        apply(tool.pointSelection.indices, MixedEdit.stepped(v, up, thicknessStep(), min, max))
    }

    /** Typed [text] (see the class docs); one in-tool step. False when it does not read as a value. */
    fun type(text: String): Boolean {
        val v = values()
        if (v.isEmpty()) return false
        val out = MixedEdit.offset(v, text, min, max) ?: MixedEdit.typed(v, text, min, max) ?: return false
        apply(tool.pointSelection.indices, out)
        tool.endNumericEdit()
        return true
    }

    /**
     * The value box: while dragging the factor ("×1.25"; the value itself when the points all
     * started at 0); else the shared value ("40 %", "1.00") or "Mixed".
     */
    fun text(): String {
        val t = thumb
        if (t != null) {
            return if (startMean > 0f) PointLabels19.FACTOR.format(Units.formatNumber((t / startMean).toDouble(), 2)) else shown(t)
        }
        return when (val m = Mixed.of(values())) {
            is Mixed.Same -> shown(m.value)
            is Mixed.Spread -> PointLabels.MIXED
            null -> ""
        }
    }

    private fun shown(v: Float): String = if (kind == Kind.THICKNESS) "${v.roundToInt()} %" else formatWeight(v)

    private fun apply(at: List<Int>, out: FloatArray) {
        if (kind == Kind.THICKNESS) tool.setWidths(at, factors(out)) else tool.setWeights(at, out)
    }
}
