package com.brushwork.paint.ui.vector

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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
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
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Options strip and sheets of the curve and polyline tools (split out of VectorOptions.kt in v1.5; owned by A4).
 */

// ====================================================================== curve / polyline tools

/**
 * Options strip of the curve and polyline tools: undo last point, closed path, actions for the
 * selected anchor (sharp / smooth / automatic tangent / delete), stroke mode, fill, and the
 * "Numbers" / settings sheets.
 */
@Composable
fun CurveToolOptions(tool: CurveTool) {
    val s = tool.settings
    // Only what the row shows about the selected point: dragging anchors doesn't recompose it.
    val selInfo by remember(tool) {
        derivedStateOf {
            val i = tool.selected
            tool.anchors.getOrNull(i)?.let { SelectedAnchor(i, it.sharp, it.hasCustomTangent, it.width) }
        }
    }
    val anyThickness by remember(tool) { derivedStateOf { !CurveGeometry.isUniformWidth(tool.anchors) } }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)

    // The same steps as the app's undo / redo (which take back one point edit at a time too).
    ToolIconButton(Icons.AutoMirrored.Filled.Undo, "Undo last point", onClick = { tool.undoStep() }, enabled = tool.canUndoStep, size = 44.dp)
    ToolIconButton(Icons.AutoMirrored.Filled.Redo, "Redo point", onClick = { tool.redoStep() }, enabled = tool.redoCount > 0, size = 44.dp)
    val a = selInfo
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
    }
    OptionChip("Closed", s.closed, { set { it.copy(closed = !it.closed) } }, icon = Icons.Filled.Loop)
    DropdownChip(
        label = s.stroke.label,
        options = CurveStroke.entries,
        selected = s.stroke,
        optionLabel = { it.label },
        onSelect = { m -> set { it.copy(stroke = m) } },
        leading = { Icon(Icons.Filled.LineWeight, contentDescription = null, modifier = Modifier.size(18.dp)) },
        contentDescription = "Stroke",
    )
    if (s.stroke == CurveStroke.PLAIN) {
        // The line width: the brush size while linked (brush icon), else the line's own.
        val dpi = tool.controller.doc.dpi.toDouble()
        val linked = tool.widthLinked
        ActionChip(
            Units.format(tool.lineWidth.toDouble(), s.unit, dpi),
            if (linked) Icons.Filled.Brush else Icons.Filled.LineWeight,
        ) { showSettings = true }
    }
    OptionChip("Fill", s.fill, { set { it.copy(fill = !it.fill) } }, icon = Icons.Filled.FormatColorFill)
    SnapToObjectsChip(tool.controller)
    ActionChip("Numbers", Icons.Filled.Pin) { showNumbers = true }
    ActionChip("Settings", Icons.Filled.Tune) { showSettings = true }

    if (showSettings) CurveSettingsSheet(tool) { showSettings = false; tool.persistBrushSize() }
    if (showNumbers) CurveNumbersSheet(tool) { showNumbers = false }
}

/** What the curve options row shows about the selected anchor. */
private data class SelectedAnchor(val index: Int, val sharp: Boolean, val customTangent: Boolean, val width: Float)

/** Thickness range of a point in percent (§4.5) and the step of its slider and arrows. */
private const val MAX_THICKNESS_PERCENT = 300f
private const val THICKNESS_STEP = 5f

/** [percent] moved by one step up or down, on the step grid, within 0..300 %. */
internal fun stepThickness(percent: Float, up: Boolean): Float {
    val grid = (percent / THICKNESS_STEP).let { if (up) kotlin.math.floor(it + 1e-3f) + 1f else kotlin.math.ceil(it - 1e-3f) - 1f }
    return (grid * THICKNESS_STEP).coerceIn(0f, MAX_THICKNESS_PERCENT)
}

/**
 * The selected point's thickness in the options strip (§4.5): ‹ › steps of 5 % (hold to
 * repeat), the value (tap to type it) and a 0–300 % slider (double-tap it for 100 %). While the
 * slider is dragged the canvas shows a ring of the real line diameter at the point. One undo
 * step per drag, held arrow or typed value.
 */
@Composable
private fun ThicknessControl(tool: CurveTool, index: Int, width: Float) {
    var typing by remember { mutableStateOf(false) }
    val percent = width * 100f
    fun current(): Float = (tool.anchors.getOrNull(index)?.width ?: 1f) * 100f
    fun setPercent(p: Float) = tool.setWidth(index, p / 100f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        Icon(Icons.Filled.LineWeight, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Thinner point", onRelease = { tool.endNumericEdit() }) {
            setPercent(stepThickness(current(), up = false))
        }
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(8.dp))
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
            setPercent(stepThickness(current(), up = true))
        }
        Slider(
            value = percent.coerceIn(0f, MAX_THICKNESS_PERCENT),
            onValueChange = { v ->
                tool.thicknessRing = true
                setPercent((v / THICKNESS_STEP).roundToInt() * THICKNESS_STEP)
            },
            onValueChangeFinished = {
                tool.thicknessRing = false
                tool.endNumericEdit()
            },
            valueRange = 0f..MAX_THICKNESS_PERCENT,
            steps = (MAX_THICKNESS_PERCENT / THICKNESS_STEP).toInt() - 1,
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
            modifier = Modifier
                .width(128.dp)
                .onDoubleTap {
                    tool.thicknessRing = false
                    setPercent(100f)
                    tool.endNumericEdit()
                }
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
            step = { v, up -> stepThickness(v, up) },
            toFraction = { it / MAX_THICKNESS_PERCENT },
            fromFraction = { f -> ((f * MAX_THICKNESS_PERCENT) / THICKNESS_STEP).roundToInt() * THICKNESS_STEP },
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
 * them, so the element still gets every touch.
 */
private fun Modifier.onDoubleTap(action: () -> Unit): Modifier = pointerInput(Unit) {
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

    BwSheet(title = if (tool.polyline) "Polyline" else "Curve", onDismiss = onDismiss) {
        SectionHeader("Stroke")
        ChoiceChips(CurveStroke.entries.map { it.label }, s.stroke.ordinal, { i -> set { it.copy(stroke = CurveStroke.entries[i]) } })
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
            description = if (s.closed) "Fills the inside of the closed path" else "An open path is filled as if it were closed",
        )
        if (s.fill) FillColorRow(s.fillColor, controller.color) { col -> set { it.copy(fillColor = col) } }

        SectionHeader("Thickness")
        Hint("Select a point to set its thickness (0–300 %). The line blends smoothly from point to point.")
        if (!CurveGeometry.isUniformWidth(tool.anchors)) {
            TextButton(onClick = { tool.resetAllWidths() }) { Text("All points 100 %") }
        }

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
            else "Tap to add points (on the path to insert one), drag any point to move it, long-press a point for corner / smooth / delete. A selected smooth point shows tangent handles you can drag.") +
                " Undo takes back the last point edit.",
            Modifier.padding(top = 8.dp),
        )
    }
}

/** Numeric editing of the curve / polyline anchors (hosted by [CurveToolOptions]). */
@Composable
private fun CurveNumbersSheet(tool: CurveTool, onDismiss: () -> Unit) {
    val s = tool.settings
    val controller = tool.controller
    val anchors = tool.anchors
    val sel = tool.selected
    val dpi = controller.doc.dpi.toDouble()
    val unit = s.unit
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)
    // Where "Add point" puts the next anchor: starts at the last point (or the canvas center).
    val start = anchors.lastOrNull()?.pos ?: Vec2(controller.doc.width / 2f, controller.doc.height / 2f)
    var addX by rememberSaveable { mutableFloatStateOf(start.x) }
    var addY by rememberSaveable { mutableFloatStateOf(start.y) }

    BwSheet(
        title = "Numbers",
        onDismiss = onDismiss,
        actions = { UnitSelector(unit, { u -> set { it.copy(unit = u) } }) },
    ) {
        val a = anchors.getOrNull(sel)
        if (a != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Point ${sel + 1} of ${anchors.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                ToolIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous point", onClick = { tool.select((sel - 1 + anchors.size) % anchors.size) })
                ToolIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next point", onClick = { tool.select((sel + 1) % anchors.size) })
            }
            FieldPair(
                "X", a.x, { x -> tool.moveAnchor(sel, Vec2(x, a.y)) },
                "Y", a.y, { y -> tool.moveAnchor(sel, Vec2(a.x, y)) },
                unit, dpi,
            )
            LabeledSlider(
                label = "Thickness",
                value = a.width * 100f,
                onValueChange = { v -> tool.setWidth(sel, v / 100f) },
                valueRange = 0f..300f,
                steps = 59,
                valueText = "${(a.width * 100f).roundToInt()} %",
                onValueChangeFinished = { tool.endNumericEdit() },
                typing = SliderTyping(scale = 1f, decimals = 0, suffix = "%"),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!tool.polyline) {
                    ChoiceChips(listOf("Smooth", "Sharp corner"), if (a.sharp) 1 else 0, { i -> tool.setSharp(sel, i == 1) }, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = { tool.deleteAnchor(sel) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = BrushworkColors.Danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = BrushworkColors.Danger)
                }
            }
            if (!tool.polyline && a.hasCustomTangent) {
                TextButton(onClick = { tool.resetTangent(sel) }) { Text("Back to the automatic tangent") }
            }
        } else if (anchors.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${anchors.size} point${if (anchors.size == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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

        if (anchors.isNotEmpty()) {
            SectionHeader("Nudge")
            NudgeRow(
                stepPx = s.nudgeStepPx,
                onStep = { v -> set { it.copy(nudgeStepPx = v) } },
                unit = unit, dpi = dpi,
                hint = if (a != null) "Each arrow moves point ${sel + 1} by one step" else "Each arrow moves the whole path by one step",
                onNudge = { dx, dy -> tool.nudge(dx, dy) },
            )
        }
    }
}
