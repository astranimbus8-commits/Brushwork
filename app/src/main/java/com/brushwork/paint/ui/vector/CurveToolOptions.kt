package com.brushwork.paint.ui.vector

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.AppSettings
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.vector.HandleSide
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.ui.common.stepOnLongPress
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.tools.points.MixedEdit
import com.brushwork.paint.ui.points.MixedNumberField
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.vector.VSpline
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import com.brushwork.paint.tools.vector.ArrowHeadStyle
import com.brushwork.paint.tools.vector.ArrowHeads
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeBox
import com.brushwork.paint.tools.vector.ShapeGeometry
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.SnapToObjectsChip
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NudgePad
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Options strip and sheets of the curve, polyline and path tools (split out of VectorOptions.kt
 * in v1.5; v1.6: the Path tool's strip and the Curve tool's Handles group, area B).
 */

// ====================================================================== curve / polyline / path tools

/**
 * Options strip of the curve tools: the Curve and Polyline strip, or the Path tool's
 * ([PathToolOptions]) for [CurveTool.isPath].
 */
@Composable
fun CurveToolOptions(tool: CurveTool) {
    // v1.7: a one-line hint under the bar ("Select several", or the Path thickness caption).
    val hint by remember(tool) { derivedStateOf { stripHint(tool) } }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tool.isPath) PathToolOptions(tool) else BezierToolOptions(tool)
        }
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = BrushworkColors.OnChromeDim,
                maxLines = 2,
                modifier = Modifier.widthIn(max = HINT_MAX_WIDTH).padding(start = 6.dp, bottom = 2.dp),
            )
        }
    }
}

/** The hint line under the strip: "Select several"'s, or (v1.7, item 5) the caption of a smooth middle Path point's thickness. */
private fun stripHint(tool: CurveTool): String? {
    if (tool.selectSeveral) return PointLabels.SEVERAL_HINT
    if (!tool.isPath || !tool.pointSelection.isSingle) return null
    val i = tool.selectedPoint
    val p = tool.spline?.points?.getOrNull(i) ?: return null
    return if (tool.canBeSharp(i) && !p.sharp) PATH_WIDTH_CAPTION else null
}

/** v1.7 (item 5): why a smooth middle point's thickness isn't reached (shown under the Path strip). */
internal const val PATH_WIDTH_CAPTION = "The line reaches a point's full thickness only where it passes through it (sharp points)"

/** The hint line wraps to a second line rather than run past a phone's width. */
private val HINT_MAX_WIDTH = 360.dp

/**
 * Options strip of the curve and polyline tools: undo last point, closed path, actions for the
 * selected anchor (sharp / smooth / automatic tangent / delete), the Handles group (Curve),
 * stroke mode, fill, and the "Numbers" / settings sheets.
 */
@Composable
private fun BezierToolOptions(tool: CurveTool) {
    val s = tool.settings
    // Only what the row shows about the selected point: dragging anchors doesn't recompose it.
    val selInfo by remember(tool) {
        derivedStateOf {
            // (One point: the v1.6 controls. Two or more: the group's, [GroupPointControls].)
            val i = if (tool.pointSelection.isSingle) tool.selected else -1
            tool.anchors.getOrNull(i)?.let { SelectedAnchor(i, it.sharp, it.hasCustomTangent, it.width) }
        }
    }
    val anyThickness by remember(tool) { derivedStateOf { !CurveGeometry.isUniformWidth(tool.anchors) } }
    // (Derived: dragging points doesn't recompose the strip.)
    val handlesShown by remember(tool) { derivedStateOf { tool.canScaleHandles } }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)

    // The same steps as the app's undo / redo (which take back one point edit at a time too).
    ToolIconButton(Icons.AutoMirrored.Filled.Undo, "Undo last point", onClick = { tool.undoStep() }, enabled = tool.canUndoStep, size = 44.dp)
    ToolIconButton(Icons.AutoMirrored.Filled.Redo, "Redo point", onClick = { tool.redoStep() }, enabled = tool.redoCount > 0, size = 44.dp)
    val a = selInfo
    // v1.7 (item 1): "Select several" and "Select all points" start the points bar, then the
    // group's controls. With exactly one point selected they follow that point's controls, which
    // keep their v1.6 places (I12).
    if (a == null) {
        PointSelectionControls(tool)
        GroupPointControls(tool, anyThickness)
    }
    if (a != null) {
        val sel = a.index
        // First, so it shows without scrolling on a phone: the selected point's thickness.
        ThicknessControl(tool, sel, a.width)
        if (anyThickness) ActionChip("All points 100 %", Icons.Filled.Restore) { tool.resetAllWidths() }
        if (!tool.polyline) {
            if (a.sharp) ActionChip("Smooth", Icons.Filled.Gesture) { tool.setSharp(sel, false) }
            else ActionChip("Sharp corner", Icons.Filled.ChangeHistory) { tool.setSharp(sel, true) }
            if (a.customTangent) ActionChip("Auto tangent", Icons.Filled.Restore) { tool.resetTangent(sel) }
        }
        ActionChip("Delete point", Icons.Outlined.Delete, tint = BrushworkColors.Danger) { tool.deleteAnchor(sel) }
        ToolIconButton(Icons.Filled.Deselect, "Deselect point", onClick = { tool.deselect() }, size = 44.dp)
        PointSelectionControls(tool)
    }
    // v1.6 §3.3: the selected point's handles (all points' with none selected).
    if (handlesShown) HandlesGroup(tool)
    OptionChip("Closed", s.closed, { set { it.copy(closed = !it.closed) } }, icon = Icons.Filled.Loop)
    StrokeFillAndSheets(tool, onSettings = { showSettings = true }, onNumbers = { showNumbers = true })

    if (showSettings) CurveSettingsSheet(tool) { showSettings = false; tool.persistBrushSize() }
    if (showNumbers) CurveNumbersSheet(tool) { showNumbers = false }
}

/** Stroke mode, the plain line's width, Fill, Snap to objects, Numbers and Settings (every curve tool). */
@Composable
private fun StrokeFillAndSheets(tool: CurveTool, onSettings: () -> Unit, onNumbers: () -> Unit) {
    val s = tool.settings
    // v1.7 (item 7): Stroke / Fill / Both, then the stroke kind for a line (Stroke, Both).
    PaintSegments(tool)
    if (s.stroke != CurveStroke.NONE) {
        DropdownChip(
            label = s.stroke.label,
            options = STROKE_KINDS,
            selected = s.stroke,
            optionLabel = { it.label },
            onSelect = { m -> tool.setStrokeKind(m) },
            leading = { Icon(Icons.Filled.LineWeight, contentDescription = null, modifier = Modifier.size(18.dp)) },
            contentDescription = CurveLabels17.STROKE_KIND,
        )
    }
    if (s.stroke == CurveStroke.PLAIN) {
        // The line width: the brush size while linked (brush icon), else the line's own.
        val dpi = tool.controller.doc.dpi.toDouble()
        val linked = tool.widthLinked
        ActionChip(
            Units.format(tool.lineWidth.toDouble(), s.unit, dpi),
            if (linked) Icons.Filled.Brush else Icons.Filled.LineWeight,
        ) { onSettings() }
    }
    SnapToObjectsChip(tool.controller)
    ActionChip("Numbers", Icons.Filled.Pin) { onNumbers() }
    // I10: shows "Settings", known as "Curve settings" / "Polyline settings" / "Path settings".
    ActionChip("Settings", Icons.Filled.Tune, contentDescription = "${tool.id.label} settings") { onSettings() }
}

/** The stroke kinds of the strip's dropdown ("No stroke" is the Fill segment now). */
private val STROKE_KINDS = listOf(CurveStroke.BRUSH, CurveStroke.PLAIN)

/**
 * v1.7 (item 7, §3.7a): the three segments "Stroke", "Fill", "Both" (known as "Stroke only",
 * "Fill only", "Stroke and fill"; 40 dp tall, about 64 dp wide). With 1 or 2 points Fill and Both
 * are greyed (disabled for TalkBack); a tap on one says "Fill needs 3 points".
 */
@Composable
private fun PaintSegments(tool: CurveTool) {
    val mode = tool.paintMode
    val fillOk by remember(tool) { derivedStateOf { tool.fillPossible } }
    fun pick(m: CurvePaint) {
        if (m != CurvePaint.STROKE && !fillOk) tool.controller.toast(CurveLabels17.FILL_NEEDS_3) else tool.setPaintMode(m)
    }
    Row(
        Modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BrushworkColors.ChromeHigh.copy(alpha = 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaintSegment("Stroke", CurveLabels17.STROKE_ONLY, mode == CurvePaint.STROKE, available = true) { pick(CurvePaint.STROKE) }
        PaintSegment("Fill", CurveLabels17.FILL_ONLY, mode == CurvePaint.FILL, available = fillOk) { pick(CurvePaint.FILL) }
        PaintSegment("Both", CurveLabels17.BOTH, mode == CurvePaint.BOTH, available = fillOk) { pick(CurvePaint.BOTH) }
    }
}

/** One segment of [PaintSegments]: [text] shown, known by [description] alone (I10, as [ActionChip]). */
@Composable
private fun PaintSegment(text: String, description: String, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 64.dp)
            .background(if (selected) BrushworkColors.AccentDim else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
                if (!available) {
                    stateDescription = CurveLabels17.FILL_NEEDS_3
                    disabled()
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = when {
                !available -> BrushworkColors.OnChromeDim.copy(alpha = 0.5f)
                selected -> Color.White
                else -> BrushworkColors.OnChrome
            },
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

// ====================================================================== several points (v1.7, item 1)

/** What the selection controls show (derived: dragging points doesn't recompose them). */
private data class SelectionInfo(val points: Int, val selected: Int, val several: Boolean)

/**
 * The start of the points bar (§3.1a), 44 dp each: "Select several" (accent while on) and
 * "Select all points" ("Deselect all points" when every point is selected). Nothing while no
 * point exists. With exactly one point selected the strips show them after that point's
 * controls instead, so those keep their v1.6 places (I12).
 */
@Composable
private fun PointSelectionControls(tool: CurveTool) {
    val info by remember(tool) { derivedStateOf { SelectionInfo(tool.pointCount, tool.pointSelection.count, tool.selectSeveral) } }
    val i = info
    if (i.points == 0) return
    // (As [OptionChip], 44 dp tall: a finger's size, as the button beside it.)
    FilterChip(
        selected = i.several,
        onClick = { tool.selectSeveral = !i.several },
        label = { Text(PointLabels.SELECT_SEVERAL, maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.HighlightAlt, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
        modifier = Modifier.padding(horizontal = 3.dp).heightIn(min = SELECTION_CONTROL_SIZE),
    )
    val all = i.selected == i.points
    ToolIconButton(
        if (all) Icons.Filled.Deselect else Icons.Filled.SelectAll,
        if (all) PointLabels.DESELECT_ALL else PointLabels.SELECT_ALL,
        onClick = { tool.toggleSelectAll() },
        size = SELECTION_CONTROL_SIZE,
    )
}

/** "Select several" and "Select all points" are 44 dp each (§3.1a). */
private val SELECTION_CONTROL_SIZE = 44.dp

/**
 * Two or more points selected (§3.1a): their thickness (and a Path's weights) as one
 * [MixedNumberField] each, the sharp chip in three states (not for a Polyline, whose points are
 * all corners), "All points 100 %", "Delete point" (the selected ones, one in-tool step) and
 * "Deselect point" (clears the selection). Nothing with fewer than two selected.
 */
@Composable
private fun GroupPointControls(tool: CurveTool, anyThickness: Boolean) {
    val selected by remember(tool) { derivedStateOf { tool.pointSelection.count } }
    if (selected < 2) return
    if (tool.isPath) GroupWeightField(tool)
    GroupThicknessField(tool)
    if (!tool.polyline) GroupSharpChip(tool)
    if (anyThickness) ActionChip("All points 100 %", Icons.Filled.Restore) { tool.resetAllWidths() }
    ActionChip("Delete point", Icons.Outlined.Delete, tint = BrushworkColors.Danger) { tool.deleteSelectedPoints() }
    ToolIconButton(Icons.Filled.Deselect, "Deselect point", onClick = { tool.deselect() }, size = 44.dp)
}

/** The custom increment key of the group thickness field (a control without a kind, as the weight's). */
internal const val POINT_THICKNESS_KEY = "curve.pointThickness"

/** Thickness factors (0..3) as percentages. */
private fun percents(factors: FloatArray) = FloatArray(factors.size) { factors[it] * 100f }

/** Percentages as thickness factors. */
private fun factors(percents: FloatArray) = FloatArray(percents.size) { percents[it] / 100f }

/**
 * "Point thickness" of the selected points: the shared value or "Mixed"; a scrub multiplies each
 * point's thickness (0 stays 0), a typed value sets all of them (`*2` / `/2`: each). One in-tool
 * step per scrub or typed value.
 */
@Composable
private fun GroupThicknessField(tool: CurveTool) {
    val value by remember(tool) { derivedStateOf { Mixed.of(percents(tool.selectedWidths())) } }
    // The selected points and their thickness when the scrub began (every event scales those).
    val base = remember(tool) { arrayOfNulls<FloatArray>(1) }
    val indices = remember(tool) { arrayOf(emptyList<Int>()) }
    MixedNumberField(
        label = POINT_THICKNESS_LABEL,
        value = value,
        unit = "%",
        range = 0f..MAX_THICKNESS_PERCENT,
        incrementKey = POINT_THICKNESS_KEY,
        onBegin = {
            indices[0] = tool.pointSelection.indices
            base[0] = percents(tool.selectedWidths())
            tool.beginNumericEdit()
        },
        onDrag = drag@{ start, now ->
            val b = base[0] ?: return@drag
            tool.setWidths(indices[0], factors(MixedEdit.scaled(b, start, now, 0f, MAX_THICKNESS_PERCENT)))
        },
        onEnd = {
            base[0] = null
            tool.endNumericEdit()
        },
        onTyped = typed@{ text ->
            val v = MixedEdit.typed(percents(tool.selectedWidths()), text, 0f, MAX_THICKNESS_PERCENT) ?: return@typed
            tool.setWidths(tool.pointSelection.indices, factors(v))
            tool.endNumericEdit()
        },
    )
}

/** PATH: "Point weight" of the selected control points (0.1–10), as [GroupThicknessField]. */
@Composable
private fun GroupWeightField(tool: CurveTool) {
    val value by remember(tool) { derivedStateOf { Mixed.of(tool.selectedWeights()) } }
    val base = remember(tool) { arrayOfNulls<FloatArray>(1) }
    val indices = remember(tool) { arrayOf(emptyList<Int>()) }
    MixedNumberField(
        label = POINT_WEIGHT_LABEL,
        value = value,
        unit = "",
        range = VSpline.MIN_WEIGHT..VSpline.MAX_WEIGHT,
        incrementKey = PATH_WEIGHT_KEY,
        onBegin = {
            indices[0] = tool.pointSelection.indices
            base[0] = tool.selectedWeights()
            tool.beginNumericEdit()
        },
        onDrag = drag@{ start, now ->
            val b = base[0] ?: return@drag
            tool.setWeights(indices[0], MixedEdit.scaled(b, start, now, VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT))
        },
        onEnd = {
            base[0] = null
            tool.endNumericEdit()
        },
        onTyped = typed@{ text ->
            val v = MixedEdit.typed(tool.selectedWeights(), text, VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT) ?: return@typed
            tool.setWeights(tool.pointSelection.indices, v)
            tool.endNumericEdit()
        },
    )
}

/** The labels of the group fields (the v1.6 single-point controls say the same). */
internal const val POINT_THICKNESS_LABEL = "Point thickness"
internal const val POINT_WEIGHT_LABEL = "Point weight"

/**
 * The sharp chip for several points (§3.1a, §3.4a): "Sharp corner" when all are smooth,
 * "Smooth" when all are corners, "Mixed" (known as "Sharp corner: Mixed") when they differ; a
 * tap on "Mixed" makes all of them corners. One in-tool step. Only the points that can be
 * corners count: on a Path selection of open ends only it is disabled ("Ends are always sharp").
 */
@Composable
private fun GroupSharpChip(tool: CurveTool) {
    val state by remember(tool) { derivedStateOf { Mixed.of(tool.selectedSharp()) } }
    when (val st = state) {
        null -> ActionChip(PointLabels.ENDS_SHARP, Icons.Filled.ChangeHistory, enabled = false) {}
        is Mixed.Same -> if (st.value) {
            ActionChip("Smooth", Icons.Filled.Gesture) { tool.setSharpPoints(tool.pointSelection.indices, false) }
        } else {
            ActionChip("Sharp corner", Icons.Filled.ChangeHistory) { tool.setSharpPoints(tool.pointSelection.indices, true) }
        }
        is Mixed.Spread -> ActionChip(PointLabels.MIXED, Icons.Filled.ChangeHistory, contentDescription = SHARP_MIXED) {
            tool.setSharpPoints(tool.pointSelection.indices, true)
        }
    }
}

/** What the three-state sharp chip is known as while the selected points differ. */
internal const val SHARP_MIXED = "Sharp corner: ${PointLabels.MIXED}"

// ====================================================================== the Path tool (v1.6, §3.2)

/** What the path options row shows about the selected control point. */
private data class SelectedPathPoint(val index: Int, val weight: Float, val width: Float, val sharp: Boolean, val canSharp: Boolean)

/**
 * Options strip of the Path tool (§3.2a, in order): Undo / Redo point, the Order stepper,
 * Endpoint, Cyclic, the selected point's Weight and Thickness, Delete point / Deselect, stroke,
 * Fill, Snap, Numbers, Settings, "To Bézier", and while no point exists the "Shapes" quick starts.
 */
@Composable
private fun PathToolOptions(tool: CurveTool) {
    val selInfo by remember(tool) {
        derivedStateOf {
            // (One point: the v1.6 controls. Two or more: the group's, [GroupPointControls].)
            val i = if (tool.pointSelection.isSingle) tool.selectedPoint else -1
            tool.spline?.points?.getOrNull(i)?.let { SelectedPathPoint(i, it.weight, it.width, it.sharp, tool.canBeSharp(i)) }
        }
    }
    val anyThickness by remember(tool) { derivedStateOf { !tool.uniformWidth } }
    val count by remember(tool) { derivedStateOf { tool.pointCount } }
    // (Derived: dragging control points doesn't recompose the strip.)
    val flags by remember(tool) { derivedStateOf { PathFlags(tool.pathOrder, tool.pathEndpoint, tool.pathCyclic) } }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNumbers by rememberSaveable { mutableStateOf(false) }

    ToolIconButton(Icons.AutoMirrored.Filled.Undo, "Undo last point", onClick = { tool.undoStep() }, enabled = tool.canUndoStep, size = 44.dp)
    ToolIconButton(Icons.AutoMirrored.Filled.Redo, "Redo point", onClick = { tool.redoStep() }, enabled = tool.redoCount > 0, size = 44.dp)
    val f = flags
    val a = selInfo
    // v1.7 (item 1): "Select several" and "Select all points" start the points bar (with exactly one
    // point selected they follow that point's controls, as in the Curve strip).
    if (a == null) PointSelectionControls(tool)
    OrderStepper(tool, f.order)
    // (Endpoint only matters for an open curve: greyed while Cyclic is on.)
    OptionChip("Endpoint", f.endpoint, { tool.setEndpoint(!f.endpoint) }, enabled = !f.cyclic)
    OptionChip("Cyclic", f.cyclic, { tool.setCyclic(!f.cyclic) }, icon = Icons.Filled.Loop)
    if (a != null) {
        // A point just got selected: the strip scrolls so its controls start near the left edge
        // (the whole Weight control, then the Thickness arrows and value). The two together are
        // wider than a phone: the strip is asked to show their first screen width (a part that
        // fits, so it lands there exactly), not all of them (it would show the Thickness slider
        // and push Weight off).
        val bring = remember { BringIntoViewRequester() }
        val groupSize = remember { IntArray(2) }
        val shownWidth = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - POINT_GROUP_MARGIN).toPx() }
        LaunchedEffect(a.index) {
            withFrameNanos { }
            val w = min(groupSize[0].toFloat(), shownWidth)
            bring.bringIntoView(if (w > 0f) Rect(0f, 0f, w, groupSize[1].toFloat()) else null)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .bringIntoViewRequester(bring)
                .onSizeChanged { groupSize[0] = it.width; groupSize[1] = it.height },
        ) {
            WeightControl(tool, a.index, a.weight)
            ThicknessControl(tool, a.index, a.width, bringIntoView = false)
        }
        // v1.7 (item 4): a middle point can be a corner (the ends of an open path always are).
        when {
            !a.canSharp -> ActionChip(PointLabels.ENDS_SHARP, Icons.Filled.ChangeHistory, enabled = false) {}
            a.sharp -> ActionChip("Smooth", Icons.Filled.Gesture) { tool.setSharp(a.index, false) }
            else -> ActionChip("Sharp corner", Icons.Filled.ChangeHistory) { tool.setSharp(a.index, true) }
        }
        if (anyThickness) ActionChip("All points 100 %", Icons.Filled.Restore) { tool.resetAllWidths() }
        ActionChip("Delete point", Icons.Outlined.Delete, tint = BrushworkColors.Danger) { tool.deleteAnchor(a.index) }
        ToolIconButton(Icons.Filled.Deselect, "Deselect point", onClick = { tool.deselect() }, size = 44.dp)
        PointSelectionControls(tool)
    } else {
        GroupPointControls(tool, anyThickness)
    }
    StrokeFillAndSheets(tool, onSettings = { showSettings = true }, onNumbers = { showNumbers = true })
    if (count >= 2) ActionChip("To Bézier", Icons.Filled.Gesture) { tool.toBezier() }
    if (count == 0) ShapesChip(tool)

    if (showSettings) CurveSettingsSheet(tool) { showSettings = false; tool.persistBrushSize() }
    if (showNumbers) CurveNumbersSheet(tool) { showNumbers = false }
}

/** The Path strip's order, Endpoint and Cyclic (pending path's, else the next path's). */
private data class PathFlags(val order: Int, val endpoint: Boolean, val cyclic: Boolean)

/** The screen width the strip does not show (its panel's margins, with room to spare). */
private val POINT_GROUP_MARGIN = 24.dp

/** "Order 4" with ‹ ›: 2–6 (2 is the straight control polygon; the effective order is limited by the points). */
@Composable
private fun OrderStepper(tool: CurveTool, order: Int) {
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
 * The selected control point's weight (§3.2a): the value (tap to type it; long-press for its
 * step) and a log slider 0.1–10 (1 in the middle). Higher pulls the curve towards the point. One
 * in-tool step per drag or typed value.
 */
@Composable
private fun WeightControl(tool: CurveTool, index: Int, weight: Float) {
    var typing by remember { mutableStateOf(false) }
    val sliding = remember(index) { booleanArrayOf(false) }
    val step = tool.controller.increments.customStep(PATH_WEIGHT_KEY)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text("Weight", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim, maxLines = 1)
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .stepOnLongPress(null, PATH_WEIGHT_KEY)
                .clickable(onClickLabel = "Type the point weight", role = Role.Button) { typing = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                formatWeight(weight),
                style = MaterialTheme.typography.bodyMedium,
                color = BrushworkColors.OnChrome,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrushworkColors.ChromeHigh)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .semantics { contentDescription = "Point weight ${formatWeight(weight)}" },
            )
        }
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
                .width(112.dp)
                .semantics { contentDescription = "Point weight slider" },
        )
    }
    if (typing) {
        ValueInputDialog(
            title = "Point weight",
            label = "Weight",
            initial = weight,
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
            onDismiss = { typing = false },
            // The weight's own step: − / + and the slider land on its multiples (as the strip's slider).
            incrementKey = PATH_WEIGHT_KEY,
        )
    }
}

/** "Shapes ▾" while the path has no point: the Circle and Capsule quick starts, fitted to the visible canvas. */
@Composable
private fun ShapesChip(tool: CurveTool) {
    var open by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box(Modifier.padding(horizontal = 3.dp)) {
        AssistChip(
            onClick = { open = true },
            label = { Text("Shapes", maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.Category, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(labelColor = BrushworkColors.OnChrome, leadingIconContentColor = BrushworkColors.OnChrome, trailingIconContentColor = BrushworkColors.OnChromeDim),
            modifier = Modifier.semantics { contentDescription = "Path shapes" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (shape in CurveTool.PathShape.entries) {
                DropdownMenuItem(
                    text = { Text(shape.label) },
                    onClick = {
                        open = false
                        // The part of the canvas that shows: the canvas view less the chrome over it.
                        tool.startShape(shape, tool.shapeArea(findCanvasView(view.rootView)?.freeArea()))
                    },
                )
            }
        }
    }
}

/** The editor's canvas view under [root] (a depth-first walk), or null when none is shown. */
private fun findCanvasView(root: View): CanvasView? {
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
 * The Curve tool's Handles group (§3.3): ⟷, ‹ "Shorter handles", the value (tap: "Type handle
 * scale"), › "Longer handles", the "Handle scale" mini slider (10–400 %, log), and the chips
 * In and out / In / Out (as the Shape tool's) and All points. The value is relative to the lengths when a change began and
 * goes back to 100 % at rest; ‹ › multiply by 0.9 / 1.1 (or step by the Scale increment) and
 * repeat while held. One in-tool step per slider drag, held arrow or typed value.
 */
@Composable
private fun HandlesGroup(tool: CurveTool) {
    var typing by remember { mutableStateOf(false) }
    val sliding = remember(tool) { booleanArrayOf(false) }
    val k = tool.handleScale
    val percent = (k * 100f).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            "⟷",
            style = MaterialTheme.typography.titleMedium,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(horizontal = 2.dp).semantics { contentDescription = "Handles" },
        )
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Shorter handles", onRelease = { tool.endHandleScale() }) {
            tool.stepHandleScale(up = false)
        }
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(8.dp))
                .stepOnLongPress(IncrementKind.SCALE)
                .clickable(onClickLabel = "Type handle scale", role = Role.Button) { typing = true }
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
        for (side in HandleSide.entries) {
            HandlesChip(side.label, tool.handleSide == side) { tool.handleSide = side }
        }
        HandlesChip("All points", tool.handleAllPoints) { tool.handleAllPoints = !tool.handleAllPoints }
    }
    if (typing) {
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
            onDismiss = { typing = false },
            // − / + and the slider step by the Scale increment while increments are on (as ‹ ›).
            incrementKind = IncrementKind.SCALE,
        )
    }
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

/** What the curve options row shows about the selected anchor. */
private data class SelectedAnchor(val index: Int, val sharp: Boolean, val customTangent: Boolean, val width: Float)

/** Thickness range of a point in percent (§4.5) and the step of its slider and arrows. */
private const val MAX_THICKNESS_PERCENT = 300f
private const val THICKNESS_STEP = 5f

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
 * The selected point's thickness in the options strip (§4.5): ‹ › steps of 5 % (hold to
 * repeat), the value (tap to type it) and a 0–300 % slider (double-tap it for 100 %). While the
 * slider is dragged the canvas shows a ring of the real line diameter at the point. One undo
 * step per drag, held arrow or typed value. With [bringIntoView] a newly selected point scrolls
 * the strip so the whole control shows (the Path strip scrolls its point group instead).
 */
@Composable
private fun ThicknessControl(tool: CurveTool, index: Int, width: Float, bringIntoView: Boolean = true) {
    var typing by remember { mutableStateOf(false) }
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
    // A point just got selected: the scrolling strip shows the whole control (with the VECTOR
    // chip in front, or on a narrow phone, its slider would start off the screen).
    val bring = remember { BringIntoViewRequester() }
    if (bringIntoView) {
        LaunchedEffect(index) {
            withFrameNanos { }
            bring.bringIntoView()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bringIntoViewRequester(bring).padding(horizontal = 2.dp)) {
        Icon(Icons.Filled.LineWeight, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Thinner point", onRelease = { tool.endNumericEdit() }) {
            setPercent(stepThickness(current(), up = false, step))
        }
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(8.dp))
                .stepOnLongPress(IncrementKind.PERCENT)
                .clickable(onClickLabel = "Type the point thickness", role = Role.Button) { typing = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${percent.roundToInt()} %",
                style = MaterialTheme.typography.bodyMedium,
                color = BrushworkColors.OnChrome,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrushworkColors.ChromeHigh)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .semantics { contentDescription = "Point thickness ${percent.roundToInt()} %" },
            )
        }
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Thicker point", onRelease = { tool.endNumericEdit() }) {
            setPercent(stepThickness(current(), up = true, step))
        }
        Slider(
            value = percent.coerceIn(0f, MAX_THICKNESS_PERCENT),
            onValueChange = { v ->
                tool.thicknessRing = true
                // The whole drag is one step, however long the finger rests on the way.
                if (!sliding[0]) { sliding[0] = true; tool.beginNumericEdit() }
                setPercent(((v / step).roundToInt() * step).coerceIn(0f, MAX_THICKNESS_PERCENT))
            },
            onValueChangeFinished = {
                sliding[0] = false
                tool.thicknessRing = false
                tool.endNumericEdit()
            },
            valueRange = 0f..MAX_THICKNESS_PERCENT,
            steps = thicknessSliderSteps(step),
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
            modifier = Modifier
                .width(128.dp)
                // (The gesture handler outlives recompositions: it calls the newest callbacks,
                // which act on the point selected now.)
                .onDoubleTap(onFirstDown = { latestFirstDown() }) { latestReset() }
                .semantics { contentDescription = "Point thickness slider" },
        )
    }
    if (typing) {
        ValueInputDialog(
            title = "Point thickness",
            label = "Thickness",
            initial = percent,
            format = { Units.formatNumber(it.toDouble(), 0) },
            parse = { t -> Units.parse(t)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, MAX_THICKNESS_PERCENT) },
            step = { v, up -> stepThickness(v, up, step) },
            toFraction = { it / MAX_THICKNESS_PERCENT },
            fromFraction = { f -> (((f * MAX_THICKNESS_PERCENT) / step).roundToInt() * step).coerceIn(0f, MAX_THICKNESS_PERCENT) },
            rangeText = "0 – 300 %",
            suffix = "%",
            onApply = { v ->
                setPercent(v)
                tool.endNumericEdit()
            },
            onDismiss = { typing = false },
        )
    }
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

@Composable
private fun CurveSettingsSheet(tool: CurveTool, onDismiss: () -> Unit) {
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
            ToggleRow("Closed path", s.closed, { v -> set { it.copy(closed = v) } }, description = "Joins the last point back to the first")
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

/** Numeric editing of the curve / polyline anchors (hosted by [CurveToolOptions]). */
@Composable
private fun CurveNumbersSheet(tool: CurveTool, onDismiss: () -> Unit) {
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
