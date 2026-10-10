package com.brushwork.paint.ui.points

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Expressions
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.ui.common.IncrementStepping
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.NumberSliderMath
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * v1.7 (item 1, design §3.1 and §4.5, F4): a number property of N selected points (Path thickness
 * and weight, Curve thickness, Shape "Point roundness"). Shows the shared value with [unit], or
 * "Mixed" when the values differ (state description "Mixed, 20 to 80 %"); nothing to edit (null)
 * shows a dash and does nothing.
 *
 * - A tap opens a [ValueInputDialog]. Its text goes to [onTyped] (the tool applies it with
 *   `MixedEdit.typed`, ONE in-tool step): with a shared value ([relative] non-null) relative text
 *   is resolved against it first ("/2" at 120 gives "120/2", which sets all), so the tool gets an
 *   absolute expression; with "Mixed" the raw text ("*2") goes over and applies to each value.
 * - A horizontal drag scrubs: [onBegin], then [onDrag] with the value the drag started from (the
 *   shared value, or the largest of a spread) and the value it is at now, then [onEnd]; the tool
 *   maps each value with `MixedEdit.scaled` (thickness, weight) or `MixedEdit.shifted`
 *   (roundness) as one in-tool step. With increments on, the scrub moves on the custom step of
 *   [incrementKey] (in [unit]).
 *
 * [range] bounds the dialog and the scrub. [modifier] and [enabled] are additive (F4).
 */
@Composable
fun MixedNumberField(
    label: String,
    value: Mixed<Float>?,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    incrementKey: String,
    onBegin: () -> Unit,
    onDrag: (start: Float, now: Float) -> Unit,
    onEnd: () -> Unit,
    onTyped: (String) -> Unit,
    /** The shared value relative text resolves against; null for "Mixed" (then onTyped gets the raw text and MixedEdit.typed applies it per point). */
    relative: Float? = (value as? Mixed.Same)?.value,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val min = range.start
    val max = range.endInclusive
    val dragStep = MixedFieldMath.dragStep(min, max)
    val decimals = MixedFieldMath.decimals(dragStep)
    fun fmt(v: Float): String = Units.formatNumber(v.toDouble(), decimals)
    val shown = MixedFieldMath.shown(value, unit, decimals)
    val state = MixedFieldMath.stateDescription(value, unit, decimals)
    val active = enabled && value != null
    val incStep = LocalIncrements.current?.customStep(incrementKey)?.toDouble()?.takeIf { IncrementStepping.valid(it) }
    var dialog by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val begin by rememberUpdatedState(onBegin)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val density = LocalDensity.current

    Row(
        modifier
            // As wide as its label, value and arrows: a scrolling strip bounds no width, and a
            // weighted value would get none. A caller's bounded width still shrinks the value first.
            .width(IntrinsicSize.Max)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (scrubbing) BrushworkColors.AccentDim else BrushworkColors.ChromeHigh.copy(alpha = 0.6f))
            .semantics {
                contentDescription = label
                stateDescription = state
            }
            .clickable(enabled = active, onClickLabel = "Type $label") { dialog = true }
            .pointerInput(active, incStep, dragStep, min, max) {
                if (!active) return@pointerInput
                var start = 0f
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = MixedFieldMath.dragStart(latestValue) ?: return@detectHorizontalDragGestures
                        total = 0f
                        scrubbing = true
                        begin()
                    },
                    onDragEnd = { if (scrubbing) { scrubbing = false; end() } },
                    onDragCancel = { if (scrubbing) { scrubbing = false; end() } },
                ) { change, dx ->
                    if (!scrubbing) return@detectHorizontalDragGestures
                    change.consume()
                    total += dx
                    val dp = with(density) { total.toDp().value }
                    val now = if (incStep != null) {
                        IncrementStepping.stepBy(start.toDouble(), NumberSliderMath.scrubSteps(dp), incStep).coerceIn(min.toDouble(), max.toDouble())
                    } else {
                        NumberSliderMath.scrubValue(start.toDouble(), dp, dragStep, min.toDouble(), max.toDouble())
                    }
                    drag(start, now.toFloat())
                }
            }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (active) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim)
        Spacer(Modifier.width(8.dp))
        Text(shown, maxLines = 1, color = if (active) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.size(width = 32.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = if (active) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.5f),
                modifier = Modifier.rotate(90f),
            )
        }
    }

    if (dialog) {
        // The dialog opens at the shared value, or a spread's middle (only its -/+ and slider start there).
        val initial = relative ?: MixedFieldMath.dialogStart(value, min, max)
        ValueInputDialog(
            title = label,
            label = label,
            initial = initial,
            format = { fmt(it) },
            parse = { s -> Units.parse(s)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(min, max) },
            step = { v, up -> (if (up) v + dragStep.toFloat() else v - dragStep.toFloat()).coerceIn(min, max) },
            toFraction = { v -> if (max > min) (v - min) / (max - min) else 0f },
            fromFraction = { f -> MixedFieldMath.roundTo(min + f * (max - min), dragStep).coerceIn(min, max) },
            rangeText = "${fmt(min)} – ${fmt(max)}${if (unit.isEmpty()) "" else " $unit"}",
            suffix = unit,
            onApply = {},
            onDismiss = { dialog = false },
            incrementKey = incrementKey,
            onApplyText = { text ->
                val r = relative
                onTyped(if (r != null) Expressions.resolveRelative(text, r) ?: text else text)
            },
        )
    }
}

/** The pure parts of [MixedNumberField] (tests). */
internal object MixedFieldMath {
    /** A round scrub step for [min]..[max]: a power of ten near a hundredth of the range (1 for 0..500, 0.01 for 0.1..10). */
    fun dragStep(min: Float, max: Float): Double {
        val span = (max - min).toDouble()
        if (!span.isFinite() || span <= 0.0) return 1.0
        return 10.0.pow(floor(log10(span / 100.0)))
    }

    /** Decimals shown for values scrubbed by [step] (one more than the step has, at most 4). */
    fun decimals(step: Double): Int = (-floor(log10(step)).toInt() + 1).coerceIn(0, 4)

    /** The field's text: the value and unit, "Mixed", or a dash for nothing. */
    fun shown(value: Mixed<Float>?, unit: String, decimals: Int): String = when (value) {
        null -> "–"
        is Mixed.Same -> withUnit(Units.formatNumber(value.value.toDouble(), decimals), unit)
        is Mixed.Spread -> PointLabels.MIXED
    }

    /** TalkBack's state: "50 %", "Mixed, 20 to 80 %". */
    fun stateDescription(value: Mixed<Float>?, unit: String, decimals: Int): String = when (value) {
        null -> ""
        is Mixed.Same -> withUnit(Units.formatNumber(value.value.toDouble(), decimals), unit)
        is Mixed.Spread -> "${PointLabels.MIXED}, " + withUnit(
            "${Units.formatNumber(value.min.toDouble(), decimals)} to ${Units.formatNumber(value.max.toDouble(), decimals)}", unit,
        )
    }

    /** The value a scrub starts from: the shared value, or the largest of a spread (null: nothing to scrub). */
    fun dragStart(value: Mixed<Float>?): Float? = when (value) {
        null -> null
        is Mixed.Same -> value.value
        is Mixed.Spread -> value.max
    }

    /** Where the dialog's -/+ and slider start for "Mixed": the spread's middle. */
    fun dialogStart(value: Mixed<Float>?, min: Float, max: Float): Float = when (value) {
        null -> min
        is Mixed.Same -> value.value
        is Mixed.Spread -> (value.min + value.max) / 2f
    }.coerceIn(min, max)

    fun roundTo(v: Float, step: Double): Float = if (step > 0.0) (Math.round(v / step) * step).toFloat() else v

    private fun withUnit(number: String, unit: String) = if (unit.isEmpty()) number else "$number $unit"
}
