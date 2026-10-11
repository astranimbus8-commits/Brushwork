package com.brushwork.paint.ui.vector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RoundedCorner
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.core.Expressions
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.tools.vector.ShapeRoundness
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.IncrementStepping
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.NumberSliderMath
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.points.MixedFieldMath
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.8 (design §3.9, area F): "Point roundness" in the Shape strip's More row, [width] wide: the
 * shared `MixedNumberField`'s behaviour in a cell that leaves room for the rest of Points mode on
 * three lines. The corner glyph and the value ("20 px", "Mixed", or "–" for nothing to round)
 * show; the label is the cell's description and its long-press label (as every icon cell).
 *
 * - A tap ("Type Point roundness") opens the value dialog; the text goes to the tool as one
 *   in-tool step (a shared value resolves relative text first: "/2" at 40 sets all to 20; with
 *   "Mixed" the raw text, "*2", applies to each corner).
 * - A sideways drag scrubs the selected corners by the same amount (on the custom increment step
 *   while increments are on), one in-tool step.
 * - Nothing to round (no selected corner between straight sides): the value is a dash, the state
 *   and the long-press label say why ([PointLabels.ROUND_REFUSAL]), and a tap says it again.
 */
@Composable
internal fun ShapeRoundnessCell(tool: ShapeTool, value: Mixed<Float>?, width: Dp, onType: () -> Unit) {
    val label = PointLabels.ROUNDNESS
    val unit = "px"
    val min = 0f
    val max = ShapeRoundness.MAX
    val dragStep = MixedFieldMath.dragStep(min, max)
    val decimals = MixedFieldMath.decimals(dragStep)
    val shown = MixedFieldMath.shown(value, unit, decimals)
    val active = value != null
    val state = if (active) MixedFieldMath.stateDescription(value, unit, decimals) else PointLabels.ROUND_REFUSAL
    val incStep = LocalIncrements.current?.customStep(SHAPE_ROUNDNESS_INCREMENT_KEY)?.toDouble()?.takeIf { IncrementStepping.valid(it) }
    var scrubbing by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val density = LocalDensity.current
    val shape = RoundedCornerShape(8.dp)
    val tint = if (active) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.6f)

    Row(
        Modifier
            .width(width)
            .height(SHAPE_CELL_DP)
            .clip(shape)
            .background(if (scrubbing) BrushworkColors.AccentDim else BrushworkColors.ChromeHigh.copy(alpha = 0.6f))
            .longPressLabel(if (active) label else PointLabels.ROUND_REFUSAL)
            .semantics {
                contentDescription = label
                stateDescription = state
            }
            .then(
                if (active) {
                    Modifier.clickable(onClickLabel = "Type $label", onClick = onType)
                } else {
                    Modifier.clickable { tool.controller.toast(PointLabels.ROUND_REFUSAL) }
                },
            )
            .pointerInput(active, incStep, dragStep) {
                if (!active) return@pointerInput
                var start = 0f
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = MixedFieldMath.dragStart(latestValue) ?: return@detectHorizontalDragGestures
                        total = 0f
                        scrubbing = true
                        tool.beginPointRoundness()
                    },
                    onDragEnd = { if (scrubbing) { scrubbing = false; tool.endPointRoundness() } },
                    onDragCancel = { if (scrubbing) { scrubbing = false; tool.endPointRoundness() } },
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
                    tool.dragPointRoundness(start, now.toFloat())
                }
            }
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.RoundedCorner, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(2.dp))
            // Laid out at its own width (IntrinsicSize.Max, within the 46 dp the cell keeps for it),
            // as the shared field's value is: a wider layout reads as an overflow.
            Text(shown, fontSize = 13.sp, maxLines = 1, softWrap = false, color = tint, modifier = Modifier.weight(1f, fill = false).width(IntrinsicSize.Max))
        }
        // The scrub arrows keep 28 dp at the end, as the shared field's do (the value stays left of them).
        Box(Modifier.width(28.dp).height(SHAPE_CELL_DP), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp).rotate(90f))
        }
    }
}

/**
 * The "Point roundness" value dialog of [ShapeRoundnessCell] (an overlay of the strip: it outlives
 * the More row). It opens at the shared value, or a spread's middle (only its -/+ and slider
 * start there); the text goes to the tool as one in-tool step.
 */
@Composable
internal fun ShapeRoundnessDialog(tool: ShapeTool, onDismiss: () -> Unit) {
    val label = PointLabels.ROUNDNESS
    val unit = "px"
    val min = 0f
    val max = ShapeRoundness.MAX
    val dragStep = MixedFieldMath.dragStep(min, max)
    val decimals = MixedFieldMath.decimals(dragStep)
    fun fmt(v: Float): String = Units.formatNumber(v.toDouble(), decimals)
    val value = remember(tool) { tool.pointRoundness }
    val relative = (value as? Mixed.Same)?.value
    ValueInputDialog(
        title = label,
        label = label,
        initial = relative ?: MixedFieldMath.dialogStart(value, min, max),
        format = { fmt(it) },
        parse = { s -> Units.parse(s)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(min, max) },
        step = { v, up -> (if (up) v + dragStep.toFloat() else v - dragStep.toFloat()).coerceIn(min, max) },
        toFraction = { v -> if (max > min) (v - min) / (max - min) else 0f },
        fromFraction = { f -> MixedFieldMath.roundTo(min + f * (max - min), dragStep).coerceIn(min, max) },
        rangeText = "${fmt(min)} – ${fmt(max)} $unit",
        suffix = unit,
        onApply = {},
        onDismiss = onDismiss,
        incrementKey = SHAPE_ROUNDNESS_INCREMENT_KEY,
        onApplyText = { text ->
            tool.typePointRoundness(if (relative != null) Expressions.resolveRelative(text, relative) ?: text else text)
        },
    )
}
