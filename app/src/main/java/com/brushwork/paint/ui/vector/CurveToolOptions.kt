package com.brushwork.paint.ui.vector

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.brushwork.paint.AppSettings
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.vector.HandleSide
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.ui.common.stepOnLongPress
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.ui.points.MixedValueDialog
import com.brushwork.paint.ui.points.ThicknessRow
import com.brushwork.paint.ui.points.ThicknessRowDefaults
import com.brushwork.paint.ui.points.groupSliderTouches
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.common.CurveLabels19
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.vector.VSpline
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/*
 * The curve, polyline and path tools' controls and sheets (split out of VectorOptions.kt in v1.5;
 * v1.6: the Path tool's controls and the Curve tool's Handles group, area B). v1.8 (design §3.9,
 * area E): the strip itself is [curveStrip] (CurveStrips.kt); this file keeps the controls its
 * items show, the contents of their popups, the dialogs its overlays show, and the settings and
 * Numbers sheets.
 */

// ====================================================================== curve / polyline / path tools

/** The stroke kinds of the "Stroke kind" menu ("No stroke" is the Fill paint mode). */
internal val STROKE_KINDS = listOf(CurveStroke.BRUSH, CurveStroke.PLAIN)

/** v1.7 (item 5): why a smooth middle point's thickness isn't reached (v1.8: a toast, once per session). */
internal const val PATH_WIDTH_CAPTION = "The line reaches a point's full thickness only where it passes through it (sharp points)"

/**
 * True when [PATH_WIDTH_CAPTION] applies (v1.7, item 5): a Path with exactly one point selected,
 * a smooth middle point (one that can be a corner and is not).
 */
internal fun pathCaptionApplies(tool: CurveTool): Boolean {
    if (!tool.isPath || !tool.pointSelection.isSingle) return false
    val i = tool.selectedPoint
    val p = tool.spline?.points?.getOrNull(i) ?: return false
    return tool.canBeSharp(i) && !p.sharp
}

// ====================================================================== several points (v1.7, item 1)

/** The custom increment key of the group thickness field (a control without a kind, as the weight's). */
internal const val POINT_THICKNESS_KEY = "curve.pointThickness"

/** Thickness factors (0..3) as percentages. */
internal fun percents(factors: FloatArray) = FloatArray(factors.size) { factors[it] * 100f }

/** Percentages as thickness factors. */
internal fun factors(percents: FloatArray) = FloatArray(percents.size) { percents[it] / 100f }

/**
 * v1.9 (R1, R2): "Point thickness" of the selected points, as the one point's control: the same
 * [ThicknessRow] (‹, the value box, ›, the blue slider), 260 dp. The value box reads the shared
 * value or "Mixed", and the factor while the thumb is dragged; the thumb sits at the points'
 * mean. Only a drag that starts on the thumb moves values, relatively (a taper keeps its
 * ratios; points at 0 % rise); ‹ › step each point on the Percent grid; a tap on the value calls
 * [onType] (the strip's overlays show [GroupThicknessDialog]). See [CurveGroupEdit]. Replaces
 * v1.7's `GroupThicknessField` (a scrub field that left 0 % at 0).
 */
@Composable
internal fun GroupThicknessControl(tool: CurveTool, onType: () -> Unit, modifier: Modifier = Modifier) {
    val edit = remember(tool) { CurveGroupEdit(tool, CurveGroupEdit.Kind.THICKNESS) }
    // A drag cut short by the control leaving (the selection changed under the finger) still ends its step.
    DisposableEffect(edit) { onDispose { edit.end() } }
    val text = edit.text()
    ThicknessRow(
        value = edit.thumbValue(),
        text = text,
        textDescription = "$POINT_THICKNESS_LABEL $text",
        onThinner = { edit.step(up = false) },
        onThicker = { edit.step(up = true) },
        onArrowRelease = { tool.endNumericEdit() },
        onType = onType,
        onSlide = { v -> edit.slideTo(v) },
        onSlideEnd = { edit.end() },
        modifier = modifier.testTag(V19Tags.GROUP_THICKNESS),
        range = 0f..MAX_THICKNESS_PERCENT,
        group = edit,
    )
}

/** The dialog of [GroupThicknessControl] (in the strip's overlays): its typed text sets the selected points' thickness, one in-tool step. */
@Composable
internal fun GroupThicknessDialog(tool: CurveTool, onDismiss: () -> Unit) {
    MixedValueDialog(
        label = POINT_THICKNESS_LABEL,
        value = Mixed.of(percents(tool.selectedWidths())),
        unit = "%",
        range = 0f..MAX_THICKNESS_PERCENT,
        incrementKey = POINT_THICKNESS_KEY,
        onTyped = { text -> CurveGroupEdit(tool, CurveGroupEdit.Kind.THICKNESS).type(text) },
        onDismiss = onDismiss,
    )
}

/**
 * v1.9 (R1): PATH, "Point weight" of the selected control points, as the one point's
 * [WeightControl] (the value, tap to type it, and the orange log slider 0.1–10) with the group
 * rules of [GroupThicknessControl]: "Mixed", the thumb at the mean, a relative drag that starts on
 * the thumb, the factor while dragging. Replaces v1.7's `GroupWeightField`.
 */
@Composable
internal fun GroupWeightControl(tool: CurveTool, onType: () -> Unit, modifier: Modifier = Modifier) {
    val edit = remember(tool) { CurveGroupEdit(tool, CurveGroupEdit.Kind.WEIGHT) }
    DisposableEffect(edit) { onDispose { edit.end() } }
    val text = edit.text()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.testTag(V19Tags.GROUP_WEIGHT).padding(horizontal = 2.dp),
    ) {
        Text(WEIGHT_CAPTION, style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim, maxLines = 1)
        WeightValueBox(text, "$POINT_WEIGHT_LABEL $text", onType)
        Box(Modifier.width(WEIGHT_SLIDER)) {
            Slider(
                value = edit.fraction(),
                onValueChange = { f -> edit.slideTo(f) },
                onValueChangeFinished = { edit.end() },
                colors = SliderDefaults.colors(thumbColor = IbisColors.SplineSelected, activeTrackColor = IbisColors.SplineSelected),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = POINT_WEIGHT_SLIDER },
            )
            Box(Modifier.matchParentSize().groupSliderTouches(LocalLayoutDirection.current == LayoutDirection.Rtl) { edit })
        }
    }
}

/** The dialog of [GroupWeightControl] (in the strip's overlays), as [GroupThicknessDialog]. */
@Composable
internal fun GroupWeightDialog(tool: CurveTool, onDismiss: () -> Unit) {
    MixedValueDialog(
        label = POINT_WEIGHT_LABEL,
        value = Mixed.of(tool.selectedWeights()),
        unit = "",
        range = VSpline.MIN_WEIGHT..VSpline.MAX_WEIGHT,
        incrementKey = PATH_WEIGHT_KEY,
        onTyped = { text -> CurveGroupEdit(tool, CurveGroupEdit.Kind.WEIGHT).type(text) },
        onDismiss = onDismiss,
    )
}

/** The labels of the group fields (the v1.6 single-point controls say the same). */
internal const val POINT_THICKNESS_LABEL = "Point thickness"
internal const val POINT_WEIGHT_LABEL = "Point weight"

/** What the three-state sharp chip is known as while the selected points differ. */
internal const val SHARP_MIXED = "Sharp corner: ${PointLabels.MIXED}"

// ====================================================================== the Path tool (v1.6, §3.2)

/**
 * "Order 4" with ‹ ›: 2–6 (2 is the straight control polygon; the effective order is limited by
 * the points). v1.8 (§3.9): the popup of the strip's "Order N" item.
 */
@Composable
internal fun OrderStepper(tool: CurveTool, order: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        ToolIconButton(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Lower order",
            onClick = { tool.setOrder(order - 1) }, enabled = order > VSpline.MIN_ORDER, size = 40.dp,
        )
        Text(
            "Order $order",
            style = MaterialTheme.typography.bodyMedium,
            color = BrushworkColors.OnChrome,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = "Order $order" },
        )
        ToolIconButton(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, "Higher order",
            onClick = { tool.setOrder(order + 1) }, enabled = order < VSpline.MAX_ORDER, size = 40.dp,
        )
    }
}

/** The custom increment key of the Weight control (§3.4: a control without a kind). */
internal const val PATH_WEIGHT_KEY = "path.weight"

/** [w] as the Weight control shows it ("1.00"). */
internal fun formatWeight(w: Float): String = Units.formatNumber(w.toDouble(), 2)

/** A weight moved one step up / down: by the custom step, else by 0.1 (held to 0.1..10). */
internal fun stepWeight(w: Float, up: Boolean, step: Float?): Float {
    val s = step?.takeIf { it.isFinite() && it > 0f } ?: WEIGHT_STEP
    val next = IncrementMath.snap(w + if (up) s else -s, s)
    return next.coerceIn(VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT)
}

private const val WEIGHT_STEP = 0.1f

/**
 * The selected control point's weight (§3.2a): the value (tap to type it, [onType]; long-press
 * for its step) and a log slider 0.1–10 (1 in the middle). Higher pulls the curve towards the
 * point. One in-tool step per drag or typed value. v1.8 (§3.9): the popup of the strip's "Point
 * weight" item; the typing dialog is [WeightDialog], in the strip's overlays.
 */
@Composable
internal fun WeightControl(tool: CurveTool, index: Int, weight: Float, onType: () -> Unit) {
    val sliding = remember(index) { booleanArrayOf(false) }
    val step = tool.controller.increments.customStep(PATH_WEIGHT_KEY)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(WEIGHT_CAPTION, style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim, maxLines = 1)
        WeightValueBox(formatWeight(weight), "$POINT_WEIGHT_LABEL ${formatWeight(weight)}", onType)
        Slider(
            value = SplineEditing.weightToFraction(weight),
            onValueChange = { f ->
                if (!sliding[0]) { sliding[0] = true; tool.beginNumericEdit() }
                var w = SplineEditing.fractionToWeight(f)
                if (step != null) w = IncrementMath.snapInRange(w.toDouble(), step.toDouble(), VSpline.MIN_WEIGHT.toDouble(), VSpline.MAX_WEIGHT.toDouble()).toFloat()
                tool.setWeight(index, w)
            },
            onValueChangeFinished = {
                sliding[0] = false
                tool.endNumericEdit()
            },
            colors = SliderDefaults.colors(thumbColor = IbisColors.SplineSelected, activeTrackColor = IbisColors.SplineSelected),
            modifier = Modifier
                .width(WEIGHT_SLIDER)
                .semantics { contentDescription = POINT_WEIGHT_SLIDER },
        )
    }
}

/** The weight's value box (tap to type it, long-press for its step): one point's and the group's (v1.9). */
@Composable
private fun WeightValueBox(text: String, description: String, onType: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .stepOnLongPress(null, PATH_WEIGHT_KEY)
            .clickable(onClickLabel = TYPE_POINT_WEIGHT, role = Role.Button) { onType() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = BrushworkColors.OnChrome,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BrushworkColors.ChromeHigh)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .semantics { contentDescription = description },
        )
    }
}

/** The Weight control's texts (v1.6's; constants since v1.9, which the group control shares: rule L). */
internal const val WEIGHT_CAPTION = "Weight"
internal const val TYPE_POINT_WEIGHT = "Type the point weight"
internal const val POINT_WEIGHT_SLIDER = "Point weight slider"

/** The Weight control's slider width (v1.6). */
private val WEIGHT_SLIDER = 112.dp

/** The typing dialog of [WeightControl] for point [index] (in the strip's overlays). One in-tool step. */
@Composable
internal fun WeightDialog(tool: CurveTool, index: Int, onDismiss: () -> Unit) {
    val step = tool.controller.increments.customStep(PATH_WEIGHT_KEY)
    ValueInputDialog(
        title = "Point weight",
        label = "Weight",
        initial = tool.weightOf(index),
        format = { formatWeight(it) },
        parse = { t -> Units.parse(t)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT) },
        step = { v, up -> stepWeight(v, up, step) },
        toFraction = { SplineEditing.weightToFraction(it) },
        fromFraction = { SplineEditing.fractionToWeight(it) },
        rangeText = "0.1 – 10",
        suffix = "",
        onApply = { v ->
            tool.setWeight(index, v)
            tool.endNumericEdit()
        },
        onDismiss = onDismiss,
        // The weight's own step: − / + and the slider land on its multiples (as the strip's slider).
        incrementKey = PATH_WEIGHT_KEY,
    )
}

/** The editor's canvas view under [root] (a depth-first walk), or null when none is shown. */
internal fun findCanvasView(root: View): CanvasView? {
    if (root is CanvasView) return root
    if (root is ViewGroup) for (i in 0 until root.childCount) findCanvasView(root.getChildAt(i))?.let { return it }
    return null
}

// ====================================================================== the Handles group (v1.6, §3.3)

/** Handle scale slider range: 10–400 %, logarithmic. */
private const val HANDLE_SLIDER_MIN = 0.1f
private const val HANDLE_SLIDER_MAX = 4f

/** Slider position 0..1 of a handle scale factor (log scale, 100 % at about 62 %). */
internal fun handleScaleToFraction(k: Float): Float {
    val v = if (k.isFinite()) k.coerceIn(HANDLE_SLIDER_MIN, HANDLE_SLIDER_MAX) else 1f
    return (ln(v / HANDLE_SLIDER_MIN) / ln(HANDLE_SLIDER_MAX / HANDLE_SLIDER_MIN)).coerceIn(0f, 1f)
}

/** The handle scale factor at slider position [f] (see [handleScaleToFraction]), on whole percents. */
internal fun fractionToHandleScale(f: Float): Float {
    val x = if (f.isFinite()) f.coerceIn(0f, 1f) else handleScaleToFraction(1f)
    val k = HANDLE_SLIDER_MIN * exp(x * ln(HANDLE_SLIDER_MAX / HANDLE_SLIDER_MIN))
    return ((k * 100f).roundToInt() / 100f).coerceIn(HANDLE_SLIDER_MIN, HANDLE_SLIDER_MAX)
}

/**
 * The Curve tool's Handles group (§3.3): ‹ "Shorter handles", the value (tap: "Type handle
 * scale", [onType]), › "Longer handles", the "Handle scale" mini slider (10–400 %, log), and the
 * chips In and out / In / Out (as the Shape tool's) and All points. The value is relative to the
 * lengths when a change began and goes back to 100 % at rest; ‹ › multiply by 0.9 / 1.1 (or step
 * by the Scale increment) and repeat while held. One in-tool step per slider drag, held arrow or
 * typed value. v1.8 (§3.9): the popup of the strip's "Handles" item, in two lines; the typing
 * dialog is [HandleScaleDialog], in the strip's overlays.
 */
@Composable
internal fun HandlesPanel(tool: CurveTool, onType: () -> Unit) {
    val sliding = remember(tool) { booleanArrayOf(false) }
    val k = tool.handleScale
    val percent = (k * 100f).roundToInt()
    Column(Modifier.padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Shorter handles", onRelease = { tool.endHandleScale() }) {
                tool.stepHandleScale(up = false)
            }
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .widthIn(min = 52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .stepOnLongPress(IncrementKind.SCALE)
                    .clickable(onClickLabel = "Type handle scale", role = Role.Button) { onType() }
                    .semantics { contentDescription = "Type handle scale" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$percent %",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrushworkColors.OnChrome,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BrushworkColors.ChromeHigh)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Longer handles", onRelease = { tool.endHandleScale() }) {
                tool.stepHandleScale(up = true)
            }
            Slider(
                value = handleScaleToFraction(k),
                onValueChange = { f ->
                    // The whole drag is one step, relative to the lengths when it began.
                    if (!sliding[0]) { sliding[0] = true; tool.beginHandleScale() }
                    tool.scaleHandles(tool.steppedHandleScale(fractionToHandleScale(f)))
                },
                onValueChangeFinished = {
                    sliding[0] = false
                    tool.endHandleScale()
                },
                colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
                modifier = Modifier
                    .width(IbisDims.HandleScaleSlider)
                    .semantics { contentDescription = "Handle scale" },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (side in HandleSide.entries) {
                HandlesChip(side.label, tool.handleSide == side) { tool.handleSide = side }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HandlesChip("All points", tool.handleAllPoints) { tool.handleAllPoints = !tool.handleAllPoints }
        }
    }
}

/** The typing dialog of [HandlesPanel] (in the strip's overlays): a scale of the lengths now, one in-tool step. */
@Composable
internal fun HandleScaleDialog(tool: CurveTool, onDismiss: () -> Unit) {
    ValueInputDialog(
        title = "Handle scale",
        label = "Handles",
        initial = 100f,
        format = { Units.formatNumber(it.toDouble(), 0) },
        parse = { t -> Units.parse(t)?.toFloat()?.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 10_000f) },
        step = { v, up -> if (up) v * CurveTool.HANDLE_STEP_UP else v * CurveTool.HANDLE_STEP_DOWN },
        toFraction = { handleScaleToFraction(it / 100f) },
        fromFraction = { fractionToHandleScale(it) * 100f },
        rangeText = "1 – 10000 % of the lengths now",
        suffix = "%",
        onApply = { v -> tool.applyHandleScale(v) },
        onDismiss = onDismiss,
        // − / + and the slider step by the Scale increment while increments are on (as ‹ ›).
        incrementKind = IncrementKind.SCALE,
    )
}

/** A chip of the Handles group (its label reads "Handles: …", unique among the strip's controls). */
@Composable
private fun HandlesChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White),
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .semantics { contentDescription = "Handles: $label" },
    )
}

/** Thickness range of a point in percent (§4.5) and the step of its slider and arrows. */
internal const val MAX_THICKNESS_PERCENT = ThicknessRowDefaults.MAX_PERCENT
internal const val THICKNESS_STEP = 5f

/** [percent] moved by one step up or down, on the step grid, within 0..300 %. */
internal fun stepThickness(percent: Float, up: Boolean): Float = stepThickness(percent, up, THICKNESS_STEP)

/** [percent] moved by one [step] up or down, on the step grid, within 0..300 % (v1.6: the Percent increment). */
internal fun stepThickness(percent: Float, up: Boolean, step: Float): Float {
    val s = step.takeIf { it.isFinite() && it > 0f } ?: THICKNESS_STEP
    val grid = (percent / s).let { if (up) kotlin.math.floor(it + 1e-3f) + 1f else kotlin.math.ceil(it - 1e-3f) - 1f }
    return (grid * s).coerceIn(0f, MAX_THICKNESS_PERCENT)
}

/** The slider's discrete steps for a [step] grid (0 = continuous when the grid doesn't divide 300 % evenly). */
private fun thicknessSliderSteps(step: Float): Int {
    val n = MAX_THICKNESS_PERCENT / step
    val r = n.roundToInt()
    return if (abs(n - r) < 1e-3f && r in 1..600) r - 1 else 0
}

/**
 * The selected point's thickness (§4.5): ‹ › steps of 5 % (hold to repeat), the value (tap to
 * type it, [onType]) and a 0–300 % slider (double-tap it for 100 %). While the slider is dragged
 * the canvas shows a ring of the real line diameter at the point. One undo step per drag, held
 * arrow or typed value. v1.8 (§3.9): an item of the More row, which never scrolls (the v1.7
 * bring-into-view is gone); the typing dialog is [ThicknessDialog], in the strip's overlays.
 * v1.9 (R1): the row itself is [ThicknessRow], which the several points' control shares.
 */
@Composable
internal fun ThicknessControl(tool: CurveTool, index: Int, width: Float, onType: () -> Unit) {
    val percent = width * 100f
    // v1.6 §3.4: with increments on, the slider and ‹ › use the Percent step (5 % by default, as before).
    val step = tool.controller.increments.step(IncrementKind.PERCENT) ?: THICKNESS_STEP
    fun current(): Float = tool.widthOf(index) * 100f
    fun setPercent(p: Float) = tool.setWidth(index, p / 100f)
    // The value when the first tap of a (possible) double tap on the slider went down.
    val beforeTaps = remember(index) { floatArrayOf(Float.NaN) }
    // A slider drag is in progress (begun on its first change, ended when it finishes).
    val sliding = remember(index) { booleanArrayOf(false) }
    val latestFirstDown by rememberUpdatedState { beforeTaps[0] = current() }
    val latestReset by rememberUpdatedState {
        tool.thicknessRing = false
        // The first tap moved the value to the finger (its own in-tool step): the reset
        // replaces that step, so one undo goes back to the value before the double tap.
        if (current() != beforeTaps[0]) tool.undoStep()
        setPercent(100f)
        tool.endNumericEdit()
    }
    ThicknessRow(
        value = percent,
        text = "${percent.roundToInt()} %",
        textDescription = "$POINT_THICKNESS_LABEL ${percent.roundToInt()} %",
        onThinner = { setPercent(stepThickness(current(), up = false, step)) },
        onThicker = { setPercent(stepThickness(current(), up = true, step)) },
        onArrowRelease = { tool.endNumericEdit() },
        onType = onType,
        onSlide = { v ->
            tool.thicknessRing = true
            // The whole drag is one step, however long the finger rests on the way.
            if (!sliding[0]) { sliding[0] = true; tool.beginNumericEdit() }
            setPercent(((v / step).roundToInt() * step).coerceIn(0f, MAX_THICKNESS_PERCENT))
        },
        onSlideEnd = {
            sliding[0] = false
            tool.thicknessRing = false
            tool.endNumericEdit()
        },
        range = 0f..MAX_THICKNESS_PERCENT,
        steps = thicknessSliderSteps(step),
        // (The gesture handler outlives recompositions: it calls the newest callbacks, which
        // act on the point selected now.)
        sliderModifier = Modifier.onDoubleTap(onFirstDown = { latestFirstDown() }) { latestReset() },
    )
}

/** The typing dialog of [ThicknessControl] for point [index] (in the strip's overlays). One in-tool step. */
@Composable
internal fun ThicknessDialog(tool: CurveTool, index: Int, onDismiss: () -> Unit) {
    val step = tool.controller.increments.step(IncrementKind.PERCENT) ?: THICKNESS_STEP
    ValueInputDialog(
        title = "Point thickness",
        label = "Thickness",
        initial = tool.widthOf(index) * 100f,
        format = { Units.formatNumber(it.toDouble(), 0) },
        parse = { t -> Units.parse(t)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, MAX_THICKNESS_PERCENT) },
        step = { v, up -> stepThickness(v, up, step) },
        toFraction = { it / MAX_THICKNESS_PERCENT },
        fromFraction = { f -> (((f * MAX_THICKNESS_PERCENT) / step).roundToInt() * step).coerceIn(0f, MAX_THICKNESS_PERCENT) },
        rangeText = "0 – 300 %",
        suffix = "%",
        onApply = { v ->
            tool.setWidth(index, v / 100f)
            tool.endNumericEdit()
        },
        onDismiss = onDismiss,
    )
}

/**
 * Calls [action] on a double tap, watching the touches before the element (a slider) handles
 * them, so the element still gets every touch. [onFirstDown] is called when a touch goes down
 * that is not the second tap of a double tap, before the element sees it.
 */
private fun Modifier.onDoubleTap(onFirstDown: () -> Unit = {}, action: () -> Unit): Modifier = pointerInput(Unit) {
    val timeout = viewConfiguration.doubleTapTimeoutMillis
    val slop = viewConfiguration.touchSlop
    var lastUp = Long.MIN_VALUE / 2
    var lastPos = Offset.Zero
    awaitPointerEventScope {
        var downPos = Offset.Zero
        var moved = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val ch = e.changes.firstOrNull() ?: continue
            when {
                ch.pressed && !ch.previousPressed -> {
                    downPos = ch.position
                    moved = false
                    if (ch.uptimeMillis - lastUp > timeout) onFirstDown()
                }
                ch.pressed -> if ((ch.position - downPos).getDistance() > slop) moved = true
                !ch.pressed && ch.previousPressed -> {
                    if (moved) {
                        lastUp = Long.MIN_VALUE / 2
                    } else if (ch.uptimeMillis - lastUp <= timeout && (ch.position - lastPos).getDistance() <= slop * 3) {
                        lastUp = Long.MIN_VALUE / 2
                        action()
                    } else {
                        lastUp = ch.uptimeMillis
                        lastPos = ch.position
                    }
                }
            }
        }
    }
}

/** The settings sheet of the curve tools (stroke, fill, thickness, points, path; v1.8: shown in [curveStrip]'s overlays). */
@Composable
internal fun CurveSettingsSheet(tool: CurveTool, onDismiss: () -> Unit) {
    val s = tool.settings
    val controller = tool.controller
    val dpi = controller.doc.dpi
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)

    BwSheet(title = tool.id.label, onDismiss = onDismiss) {
        SectionHeader("Stroke")
        ChoiceChips(CurveStroke.entries.map { it.label }, s.stroke.ordinal, { i ->
            val e = CurveStroke.entries[i]
            set { it.copy(stroke = e, lastStroke = if (e != CurveStroke.NONE) e else it.lastStroke) }
        })
        when (s.stroke) {
            CurveStroke.BRUSH -> {
                val paintTool = controller.lastPaintTool
                val preset = controller.presetFor(paintTool)
                Hint(
                    (if (preset != null) "Painted with ${paintTool.label.lowercase()} \"${preset.name}\"" else "Painted with the ${paintTool.label.lowercase()}") +
                        ". The stroke shows while you edit the points; each point's thickness sets the brush size there.",
                )
                if (tool.brushDrawsPlain) {
                    Hint("This brush needs pixels: on this vector layer the curve is drawn as a plain line")
                }
                ToggleRow("Taper ends", s.taper, { v -> set { it.copy(taper = v) } }, description = "Pressure fades in and out along the path")
                if (s.taper) {
                    LabeledSlider(
                        label = "Taper length",
                        value = s.taperPercent,
                        onValueChange = { v -> set { it.copy(taperPercent = v) } },
                        valueRange = 1f..50f,
                        valueText = "${s.taperPercent.roundToInt()} % of the path",
                    )
                }
            }
            CurveStroke.PLAIN -> {
                MainColorNote(controller.color, "The line uses the main drawing color")
                if (!tool.isReopened) {
                    ToggleRow(
                        "Use brush size", s.useBrushSize, { v -> set { it.copy(useBrushSize = v) } },
                        description = if (s.useBrushSize) "The width follows the brush size slider" else "The width set here is used",
                    )
                }
                LengthEditor(
                    label = "Line width", px = tool.lineWidth, onPx = { w -> tool.setLineWidth(w) },
                    unit = s.unit, onUnit = { u -> set { it.copy(unit = u) } }, dpi = dpi,
                    minPx = ShapeSettings.MIN_STROKE, maxPx = ShapeSettings.MAX_STROKE,
                    // A width is a size (as the Shape tool's stroke width): the Size increment.
                    incrementKind = IncrementKind.SIZE,
                )
                when {
                    tool.isReopened -> Hint("This path keeps its own width")
                    s.useBrushSize -> Hint("Same as the brush size: changing it here resizes the brush too")
                }
            }
            CurveStroke.NONE -> Hint("Only the fill is painted")
        }

        SectionHeader("Fill")
        ToggleRow(
            "Fill the path", s.fill, { v -> set { it.copy(fill = v) } },
            description = if (if (tool.isPath) tool.pathCyclic else s.closed) "Fills the inside of the closed path" else "An open path is filled as if it were closed",
        )
        if (s.fill) {
            // A reopened path with a gradient fill (an imported SVG) keeps it until a color is picked.
            FillColorRow(s.fillColor, controller.color, keptLabel = if (tool.reopenedGradientFill) "Gradient (kept)" else null) { col -> set { it.copy(fillColor = col) } }
        }

        SectionHeader("Thickness")
        Hint("Select a point to set its thickness (0–300 %). The line blends smoothly from point to point.")
        if (!tool.uniformWidth) {
            TextButton(onClick = { tool.resetAllWidths() }) { Text("All points 100 %") }
        }

        HandleSizeSection(tool)

        if (tool.isPath) {
            PathSettingsSection(tool)
        } else {
            SectionHeader("Path")
            ToggleRow("Closed path", s.closed, { v -> tool.setClosed(v) }, description = "Joins the last point back to the first")
            if (!tool.polyline) {
                LabeledSlider(
                    label = "Tension",
                    value = s.tension,
                    onValueChange = { v -> set { it.copy(tension = v) } },
                    valueRange = 0f..1f,
                    valueText = "${(s.tension * 100f).roundToInt()} %",
                )
                Hint("0 % is a smooth curve through every point, 100 % straight lines")
            }
            Hint(
                (if (tool.polyline) "Tap to add points, drag any point to move it, long-press a point to select it."
                else "Tap to add points (on the path to insert one), drag any point to move it, long-press a point for corner / smooth / delete. A selected smooth point shows tangent handles you can drag; the Handles group (or a pinch on the point) scales them.") +
                    " Undo takes back the last point edit." +
                    (if (controller.isVectorMode) " On this vector layer, tap the line of a ${if (tool.polyline) "polyline" else "curve"} to edit its points again." else ""),
                Modifier.padding(top = 8.dp),
            )
        }

        // v1.9 (R14, D22): where new paths on a pixel layer go (off: painted into it, as in v1.8).
        if (!tool.isReopened) {
            ToggleRow(
                CurveLabels19.OWN_LAYER, s.ownLayer, { v -> set { it.copy(ownLayer = v) } },
                Modifier.padding(top = 8.dp), description = CurveLabels19.OWN_LAYER_DESC,
            )
        }
    }
}

/**
 * "Handle size" (§3.3, app-wide, 75–200 %): how big points and handles are drawn and how far
 * from them a finger still grabs them, in the Curve, Polyline and Path tools.
 */
@Composable
private fun HandleSizeSection(tool: CurveTool) {
    var percent by remember(tool) { mutableFloatStateOf(tool.handleSize * 100f) }
    SectionHeader("Points on screen")
    LabeledSlider(
        label = "Handle size",
        value = percent,
        onValueChange = { v ->
            percent = v.roundToInt().toFloat().coerceIn(AppSettings.MIN_CURVE_HANDLE_SCALE * 100f, AppSettings.MAX_CURVE_HANDLE_SCALE * 100f)
            tool.setHandleSize(percent / 100f)
        },
        valueRange = AppSettings.MIN_CURVE_HANDLE_SCALE * 100f..AppSettings.MAX_CURVE_HANDLE_SCALE * 100f,
        valueText = "${percent.roundToInt()} %",
        typing = SliderTyping(scale = 1f, decimals = 0, suffix = "%"),
    )
    Hint("Bigger points and handles are easier to grab with a finger")
}

/** The Path tool's part of its settings sheet: Endpoint, Cyclic and how the gestures work. */
@Composable
private fun PathSettingsSection(tool: CurveTool) {
    val controller = tool.controller
    SectionHeader("Path")
    // (Labels other than the strip's chips: both can be on screen at once.)
    ToggleRow(
        "Cyclic path", tool.pathCyclic, { v -> tool.setCyclic(v) },
        description = "Closes the curve smoothly (3 points or more)",
    )
    if (!tool.pathCyclic) {
        ToggleRow(
            "Touch the end points", tool.pathEndpoint, { v -> tool.setEndpoint(v) },
            description = if (tool.pathEndpoint) "The curve touches its first and last points" else "The curve starts and ends inside the control polygon",
        )
    }
    Hint(
        "Order ${tool.pathOrder}: higher orders make a smoother curve that stays farther from the points (order 2 is the straight control polygon). " +
            "Tap to add a control point (after the selected one), tap near the dashed polygon to insert one, drag a point to move it, tap a point to select it for its weight and thickness. " +
            "A higher weight pulls the curve towards its point. \"To Bézier\" turns the path into a Curve-tool path." +
            (if (controller.isVectorMode) " On this vector layer, tap the line of a path to edit its control points again." else ""),
        Modifier.padding(top = 8.dp),
    )
}

/** Numeric editing of the curve, polyline and path points (v1.8: shown in [curveStrip]'s overlays). */
@Composable
internal fun CurveNumbersSheet(tool: CurveTool, onDismiss: () -> Unit) {
    val s = tool.settings
    val controller = tool.controller
    val count = tool.pointCount
    val sel = tool.selectedIndex
    val dpi = controller.doc.dpi.toDouble()
    val unit = s.unit
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)
    // The points the user edits: the control points (Path) or the anchors.
    fun posOf(i: Int): Vec2? =
        if (tool.isPath) tool.spline?.points?.getOrNull(i)?.let { Vec2(it.x, it.y) } else tool.anchors.getOrNull(i)?.pos
    // Where "Add point" puts the next point: starts at the last point (or the canvas center).
    val start = posOf(count - 1) ?: Vec2(controller.doc.width / 2f, controller.doc.height / 2f)
    var addX by rememberSaveable { mutableFloatStateOf(start.x) }
    var addY by rememberSaveable { mutableFloatStateOf(start.y) }
    val thicknessSliding = remember { booleanArrayOf(false) }
    val weightSliding = remember { booleanArrayOf(false) }

    BwSheet(
        title = "Numbers",
        onDismiss = onDismiss,
        actions = { UnitSelector(unit, { u -> set { it.copy(unit = u) } }) },
    ) {
        val a = posOf(sel)
        if (a != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Point ${sel + 1} of $count", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                ToolIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous point", onClick = { tool.select((sel - 1 + count) % count) })
                ToolIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next point", onClick = { tool.select((sel + 1) % count) })
            }
            FieldPair(
                "X", a.x, { x -> tool.moveAnchor(sel, Vec2(x, a.y)) },
                "Y", a.y, { y -> tool.moveAnchor(sel, Vec2(a.x, y)) },
                unit, dpi,
            )
            if (tool.isPath) {
                NumberField(
                    label = "Weight",
                    value = tool.weightOf(sel).toDouble(),
                    onValueChange = { v ->
                        // One step per drag, however long the finger rests on the way.
                        if (!weightSliding[0]) { weightSliding[0] = true; tool.beginNumericEdit() }
                        tool.setWeight(sel, v.toFloat())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    decimals = 2,
                    min = VSpline.MIN_WEIGHT.toDouble(),
                    max = VSpline.MAX_WEIGHT.toDouble(),
                    step = 0.1,
                    logSlider = true,
                    onValueChangeFinished = { weightSliding[0] = false; tool.endNumericEdit() },
                    incrementKey = PATH_WEIGHT_KEY,
                )
            }
            val width = tool.widthOf(sel)
            LabeledSlider(
                label = "Thickness",
                value = width * 100f,
                onValueChange = { v ->
                    // One step per drag, however long the finger rests on the way.
                    if (!thicknessSliding[0]) { thicknessSliding[0] = true; tool.beginNumericEdit() }
                    tool.setWidth(sel, v / 100f)
                },
                valueRange = 0f..300f,
                // v1.5's 5 % ticks; with increments on, the Percent step's (as the strip's slider),
                // so a finer step than 5 % is reachable (the slider then lands on its multiples).
                steps = thicknessSliderSteps(controller.increments.step(IncrementKind.PERCENT) ?: THICKNESS_STEP),
                valueText = "${(width * 100f).roundToInt()} %",
                onValueChangeFinished = { thicknessSliding[0] = false; tool.endNumericEdit() },
                typing = SliderTyping(scale = 1f, decimals = 0, suffix = "%"),
            )
            val anchor = if (tool.isPath) null else tool.anchors.getOrNull(sel)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!tool.polyline && anchor != null) {
                    ChoiceChips(listOf("Smooth", "Sharp corner"), if (anchor.sharp) 1 else 0, { i -> tool.setSharp(sel, i == 1) }, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = { tool.deleteAnchor(sel) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = BrushworkColors.Danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = BrushworkColors.Danger)
                }
            }
            if (!tool.polyline && anchor != null && anchor.hasCustomTangent) {
                TextButton(onClick = { tool.resetTangent(sel) }) { Text("Back to the automatic tangent") }
            }
        } else if (count > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$count point${if (count == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { tool.select(0) }) { Text("Edit points") }
            }
            Hint("No point is selected: the arrows move the whole path")
        } else {
            Hint("No points yet. Tap the canvas or add points by coordinates below.", Modifier.padding(top = 8.dp))
        }

        SectionHeader("Add point")
        PanelCard {
            FieldPair(
                "X", addX, { if (it.isFinite()) addX = it },
                "Y", addY, { if (it.isFinite()) addY = it },
                unit, dpi,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { tool.addAnchor(Vec2(addX, addY)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add point")
                }
            }
        }

        if (count > 0) {
            // v1.7 (item 1): with several points selected the arrows move all of them ([CurveTool.nudge]).
            val group = tool.pointSelection.count
            SectionHeader("Nudge")
            NudgeRow(
                stepPx = s.nudgeStepPx,
                onStep = { v -> set { it.copy(nudgeStepPx = v) } },
                unit = unit, dpi = dpi,
                hint = when {
                    group >= 2 -> "Each arrow moves the $group selected points by one step"
                    a != null -> "Each arrow moves point ${sel + 1} by one step"
                    else -> "Each arrow moves the whole path by one step"
                },
                onNudge = { dx, dy -> tool.nudge(dx, dy) },
            )
        }
    }
}
