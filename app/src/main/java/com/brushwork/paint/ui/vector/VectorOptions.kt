package com.brushwork.paint.ui.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.ArrowHeadStyle
import com.brushwork.paint.tools.vector.ArrowHeads
import com.brushwork.paint.tools.vector.CornerStyle
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
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val MAX_LEN = ShapeSettings.MAX_LENGTH.toDouble()

// ====================================================================== shape tool

/**
 * Options strip of the shape tool: shape type, style (or line ends), stroke width, drawing
 * toggles, and the "Numbers" / settings sheets.
 */
@Composable
fun ShapeToolOptions(tool: ShapeTool) {
    val s = tool.settings
    val controller = tool.controller
    // docVersion bumps when the document size / dpi change, so the width label follows the dpi.
    val dpi = remember(controller.docVersion) { controller.doc.dpi }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    fun set(f: (ShapeSettings) -> ShapeSettings) = tool.update(f)

    DropdownChip(
        label = s.type.label,
        options = ShapeType.entries,
        selected = s.type,
        optionLabel = { it.label },
        onSelect = { t -> set { it.copy(type = t) } },
        leading = { Icon(shapeIcon(s.type), contentDescription = null, modifier = Modifier.size(18.dp)) },
        optionLeading = { Icon(shapeIcon(it), contentDescription = null) },
        contentDescription = "Shape type",
    )
    // Points of the pending shape (and the selected point's actions).
    ShapePointsStrip(tool)
    if (s.type.isLineLike) {
        // A brush paints its own ends: the line caps only apply to plain lines.
        if (s.strokeWith == ShapeStroke.PLAIN) DropdownChip(
            label = "${s.lineCap.label} ends",
            options = LineCapStyle.entries,
            selected = s.lineCap,
            optionLabel = { "${it.label} ends" },
            onSelect = { c -> set { it.copy(lineCap = c) } },
            contentDescription = "Line ends",
        )
    } else {
        DropdownChip(
            label = s.style.label,
            options = ShapeStyle.entries,
            selected = s.style,
            optionLabel = { it.label },
            onSelect = { st -> set { it.copy(style = st) } },
            leading = { StyleGlyph(s.style, s.fillColor ?: controller.color, Modifier.size(18.dp)) },
            optionLeading = { StyleGlyph(it, s.fillColor ?: controller.color) },
            contentDescription = "Shape style",
        )
    }
    if (s.strokes) {
        DropdownChip(
            label = s.strokeWith.label,
            options = ShapeStroke.entries,
            selected = s.strokeWith,
            optionLabel = { it.label },
            onSelect = { m -> set { it.copy(strokeWith = m) } },
            leading = { Icon(if (s.strokeWith == ShapeStroke.BRUSH) Icons.Filled.Brush else Icons.Filled.LineWeight, contentDescription = null, modifier = Modifier.size(18.dp)) },
            contentDescription = "Stroke with",
        )
        if (s.strokeWith == ShapeStroke.PLAIN || s.type == ShapeType.ARROW) {
            // The width (the brush size when "Use brush size" is on).
            ActionChip(Units.format(tool.strokeWidth.toDouble(), s.unit, dpi.toDouble()), if (s.useBrushSize) Icons.Filled.Brush else Icons.Filled.LineWeight) { showSettings = true }
        }
    }
    ShapeEditableChip(tool)
    OptionChip("Center", s.fromCenter, { set { it.copy(fromCenter = !it.fromCenter) } }, icon = Icons.Filled.CenterFocusStrong)
    if (!s.type.isLineLike) {
        OptionChip(
            if (s.type == ShapeType.ELLIPSE) "Circle" else "Proportional",
            s.keepProportions,
            { set { it.copy(keepProportions = !it.keepProportions) } },
            icon = Icons.Filled.AspectRatio,
        )
    }
    OptionChip("15°", s.snapAngle, { set { it.copy(snapAngle = !it.snapAngle) } }, icon = Icons.Filled.Straighten)
    SnapToObjectsChip(controller)
    ActionChip("Numbers", Icons.Filled.Pin) { if (tool.ensurePending()) showNumbers = true }
    ActionChip("Settings", Icons.Filled.Tune) { showSettings = true }

    if (showSettings) ShapeSettingsSheet(tool) { showSettings = false }
    if (showNumbers) ShapeNumbersSheet(tool) { showNumbers = false }
}

@Composable
private fun ShapeSettingsSheet(tool: ShapeTool, onDismiss: () -> Unit) {
    val s = tool.settings
    val controller = tool.controller
    val dpi = controller.doc.dpi
    fun set(f: (ShapeSettings) -> ShapeSettings) = tool.update(f)
    val onUnit: (LengthUnit) -> Unit = { u -> set { it.copy(unit = u) } }

    BwSheet(title = "Shape", onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ShapeType.entries.forEach { t ->
                ToolIconButton(shapeIcon(t), t.label, onClick = { set { it.copy(type = t) } }, selected = t == s.type)
            }
        }
        Text(s.type.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))

        if (!s.type.isLineLike) {
            SectionHeader("Style")
            ChoiceChips(ShapeStyle.entries.map { it.label }, s.style.ordinal, { i -> set { it.copy(style = ShapeStyle.entries[i]) } })
        }

        if (s.strokes) {
            SectionHeader("Stroke")
            MainColorNote(controller.color)
            ChoiceChips(ShapeStroke.entries.map { it.label }, s.strokeWith.ordinal, { i -> set { it.copy(strokeWith = ShapeStroke.entries[i]) } })
            if (s.strokeWith == ShapeStroke.BRUSH && tool.editedShapeBrush != null) {
                // An opened shape keeps the brush it was drawn with.
                ShapeOwnBrushNote(tool)
            } else if (s.strokeWith == ShapeStroke.BRUSH) {
                val paintTool = controller.lastPaintTool
                val preset = controller.presetFor(paintTool)
                Hint(
                    (if (preset != null) "Painted with the ${paintTool.label.lowercase()} \"${preset.name}\"" else "Painted with the ${paintTool.label.lowercase()}") +
                        " at full pressure, with its own size, opacity and texture",
                    Modifier.padding(top = 4.dp),
                )
            }
            StrokeWidthControls(tool, unit = s.unit, onUnit = onUnit, dpi = dpi, showSlider = true, showUnit = true)
            if (s.type.isLineLike && s.strokeWith == ShapeStroke.PLAIN) {
                Hint("Line ends")
                ChoiceChips(LineCapStyle.entries.map { it.label }, s.lineCap.ordinal, { i -> set { it.copy(lineCap = LineCapStyle.entries[i]) } })
            }
        }

        if (s.type == ShapeType.ARROW) {
            SectionHeader("Arrowheads")
            ChoiceChips(ArrowHeads.entries.map { it.label }, s.arrowHeads.ordinal, { i -> set { it.copy(arrowHeads = ArrowHeads.entries[i]) } })
            Spacer(Modifier.padding(top = 6.dp))
            ChoiceChips(ArrowHeadStyle.entries.map { it.label }, s.arrowHeadStyle.ordinal, { i -> set { it.copy(arrowHeadStyle = ArrowHeadStyle.entries[i]) } })
            LabeledSlider(
                label = "Head size",
                value = s.arrowHeadScale,
                onValueChange = { v -> set { it.copy(arrowHeadScale = v) } },
                valueRange = 2f..12f,
                valueText = "${Units.formatNumber(s.arrowHeadScale.toDouble(), 1)} × width",
            )
        }

        if (!s.type.isLineLike && s.style.fill) {
            SectionHeader("Fill")
            FillColorRow(s.fillColor, controller.color) { col -> set { it.copy(fillColor = col) } }
        }

        ShapeParamFields(tool)

        SectionHeader("Drawing")
        ToggleRow(
            "Draw from the center", s.fromCenter, { v -> set { it.copy(fromCenter = v) } },
            description = if (s.type.isLineLike) "The first touch is the middle of the line" else "The first touch is the middle of the shape",
        )
        if (!s.type.isLineLike) {
            ToggleRow(
                "Keep proportions", s.keepProportions, { v -> set { it.copy(keepProportions = v) } },
                description = "Squares, circles and regular polygons / stars",
            )
        }
        ToggleRow(
            "Snap angle", s.snapAngle, { v -> set { it.copy(snapAngle = v) } },
            description = if (s.type.isLineLike) "Lines snap to 15° steps" else "Rotation snaps to 15° steps",
        )
        ShapeEditingSettings(tool)
    }
}

/**
 * "Use brush size" and the stroke width (the brush size while that is on: editing it resizes the
 * brush too). When the brush paints the outline only arrows use the width (for their heads).
 */
@Composable
private fun StrokeWidthControls(tool: ShapeTool, unit: LengthUnit, onUnit: (LengthUnit) -> Unit, dpi: Float, showSlider: Boolean, showUnit: Boolean) {
    val s = tool.settings
    val brush = s.strokeWith == ShapeStroke.BRUSH
    if (brush && s.type != ShapeType.ARROW) return
    ToggleRow(
        "Use brush size", s.useBrushSize, { v -> tool.update { it.copy(useBrushSize = v) } },
        description = if (s.useBrushSize) "The width follows the brush size slider" else "The width set here is used",
    )
    LengthEditor(
        label = if (brush) "Stroke width (arrowheads)" else "Stroke width",
        px = tool.strokeWidth, onPx = { tool.setStrokeWidth(it) },
        unit = unit, onUnit = onUnit, dpi = dpi,
        minPx = ShapeSettings.MIN_STROKE, maxPx = ShapeSettings.MAX_STROKE,
        showSlider = showSlider, showUnit = showUnit,
    )
    if (s.useBrushSize) Hint("Same as the brush size: changing it here resizes the brush too")
}

/** Integer field with -/+ buttons, under a slider for quick changes. */
@Composable
private fun IntSliderField(label: String, value: Int, onChange: (Int) -> Unit, range: IntRange) {
    LabeledSlider(
        label = label,
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        valueText = value.toString(),
    )
    IntStepper(label, value, onChange, range)
}

/** Sides / points / inner radius / corners of the current shape type (shared by both sheets). */
@Composable
private fun ShapeParamFields(tool: ShapeTool, compact: Boolean = false) {
    val s = tool.settings
    val dpi = tool.controller.doc.dpi
    fun set(f: (ShapeSettings) -> ShapeSettings) = tool.update(f)
    val range = ShapeGeometry.MIN_SIDES..ShapeGeometry.MAX_SIDES
    // A shape with its own points has no sides / star points any more (Reset shape brings them back).
    val custom by remember(tool) { derivedStateOf { tool.points != null && tool.box != null } }
    if (custom && (s.type == ShapeType.POLYGON || s.type == ShapeType.STAR)) {
        Hint("This shape has its own points: \"Reset shape\" goes back to a regular ${s.type.label.lowercase()}", Modifier.padding(top = 8.dp))
    } else when (s.type) {
        ShapeType.POLYGON -> {
            SectionHeader("Polygon")
            IntSliderField("Number of sides", s.sides, { n -> set { it.copy(sides = n) } }, range)
        }
        ShapeType.STAR -> {
            SectionHeader("Star")
            IntSliderField("Number of points", s.starPoints, { n -> set { it.copy(starPoints = n) } }, range)
            LabeledSlider(
                label = "Inner radius",
                value = s.innerRatio * 100f,
                onValueChange = { v -> set { it.copy(innerRatio = v / 100f) } },
                valueRange = 5f..95f,
                valueText = "${(s.innerRatio * 100f).roundToInt()} %",
            )
            if (compact) {
                NumberField(
                    label = "Inner radius", value = (s.innerRatio * 100f).toDouble(),
                    onValueChange = { v -> set { it.copy(innerRatio = v.toFloat() / 100f) } },
                    modifier = Modifier.fillMaxWidth(), decimals = 0, suffix = "%", min = 5.0, max = 95.0, step = 1.0,
                )
            }
        }
        else -> {}
    }
    if (s.type.hasCorners) {
        SectionHeader("Corners")
        ChoiceChips(CornerStyle.entries.map { it.label }, s.corner.ordinal, { i -> set { it.copy(corner = CornerStyle.entries[i]) } })
        Spacer(Modifier.padding(top = 4.dp))
        LengthEditor(
            label = if (s.corner == CornerStyle.ROUND || s.corner == CornerStyle.INVERTED) "Corner radius" else "Corner size",
            px = s.cornerRadius, onPx = { r -> set { it.copy(cornerRadius = r) } },
            unit = s.unit, onUnit = { u -> set { it.copy(unit = u) } }, dpi = dpi,
            minPx = 0f, maxPx = ShapeSettings.MAX_LENGTH, sliderMin = 1f, sliderMax = 1000f,
            showSlider = true, showUnit = !compact, enabled = s.corner != CornerStyle.SHARP,
        )
        if (s.corner != CornerStyle.SHARP) Hint("Limited to half of the shorter edge at each corner")
    }
}

/** Numeric placement of the pending shape (hosted by [ShapeToolOptions]). */
@Composable
private fun ShapeNumbersSheet(tool: ShapeTool, onDismiss: () -> Unit) {
    val b = tool.box
    // Committed or discarded from elsewhere (✓ / ✕, tool switch): nothing left to edit.
    LaunchedEffect(b == null) { if (b == null) onDismiss() }
    if (b == null) return
    val s = tool.settings
    val dpi = tool.controller.doc.dpi.toDouble()
    val unit = s.unit
    fun set(f: (ShapeSettings) -> ShapeSettings) = tool.update(f)

    BwSheet(
        title = "Numbers",
        onDismiss = onDismiss,
        actions = { UnitSelector(unit, { u -> set { it.copy(unit = u) } }) },
    ) {
        // The selected point (points mode) of a shape with its own points.
        ShapePointFields(tool, unit, dpi)
        // A line with its own points is placed by its box like the other shapes.
        if (s.type.isLineLike && tool.points == null) {
            val st = b.start
            val en = b.end
            SectionHeader("Start point")
            FieldPair(
                "X", st.x, { x -> tool.place(ShapeBox.line(Vec2(x, st.y), en)) },
                "Y", st.y, { y -> tool.place(ShapeBox.line(Vec2(st.x, y), en)) },
                unit, dpi,
            )
            SectionHeader("End point")
            FieldPair(
                "X", en.x, { x -> tool.place(ShapeBox.line(st, Vec2(x, en.y))) },
                "Y", en.y, { y -> tool.place(ShapeBox.line(st, Vec2(en.x, y))) },
                unit, dpi,
            )
            SectionHeader("Length and angle")
            LengthField(
                label = "Length", px = b.w.toDouble(),
                onPxChange = { len -> tool.place(ShapeBox.line(st, st + direction(b.rotationDeg) * len.toFloat())) },
                // >= 1 px so a "0" typed on the way to "0.5 cm" keeps the line's direction.
                unit = unit, dpi = dpi, modifier = Modifier.fillMaxWidth(), minPx = 1.0, maxPx = MAX_LEN,
            )
            NumberField(
                label = "Angle", value = b.rotationDeg.toDouble(),
                onValueChange = { deg -> tool.place(ShapeBox.line(st, st + direction(deg.toFloat()) * b.w)) },
                modifier = Modifier.fillMaxWidth(), decimals = 1, suffix = "°", min = -360.0, max = 360.0, step = 1.0,
            )
            AngleSlider("Angle", b.rotationDeg, s.snapAngle) { deg -> tool.place(ShapeBox.line(st, st + direction(deg) * b.w)) }
            Hint("The start point stays put when the length or angle changes")
        } else {
            SectionHeader("Position")
            FieldPair(
                "X", b.left, { x -> tool.place(b.copy(cx = x + b.w / 2f)) },
                "Y", b.top, { y -> tool.place(b.copy(cy = y + b.h / 2f)) },
                unit, dpi,
            )
            if (b.rotationDeg != 0f) Hint("Top-left corner of the shape before rotation")
            // Fields commit while typing, so intermediate values ("1" on the way to "120") would
            // erode a ratio recomputed from the box: keep the one from when proportions got locked.
            val aspect = remember(s.keepProportions) { if (b.h > 0f) b.w / b.h else 1f }
            SectionHeader("Size")
            FieldPair(
                "Width", b.w, { w ->
                    val h = if (s.keepProportions) w / aspect else b.h
                    tool.place(b.copy(cx = b.left + w / 2f, cy = b.top + h / 2f, w = w, h = h))
                },
                "Height", b.h, { h ->
                    val w = if (s.keepProportions) h * aspect else b.w
                    tool.place(b.copy(cx = b.left + w / 2f, cy = b.top + h / 2f, w = w, h = h))
                },
                unit, dpi, minPx = 1.0,
            )
            if (s.keepProportions) Hint("Keep proportions is on: width and height change together")
            NumberField(
                label = "Rotation", value = b.rotationDeg.toDouble(),
                onValueChange = { deg -> tool.place(b.copy(rotationDeg = deg.toFloat())) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), decimals = 1, suffix = "°", min = -360.0, max = 360.0, step = 1.0,
            )
            AngleSlider("Rotation", b.rotationDeg, s.snapAngle) { deg -> tool.place(b.copy(rotationDeg = deg)) }
        }

        ShapeParamFields(tool, compact = true)

        if (s.strokes && (s.strokeWith == ShapeStroke.PLAIN || s.type == ShapeType.ARROW)) {
            SectionHeader("Stroke")
            StrokeWidthControls(tool, unit = unit, onUnit = { u -> set { it.copy(unit = u) } }, dpi = dpi.toFloat(), showSlider = true, showUnit = false)
        }

        SectionHeader("Nudge")
        NudgeRow(
            stepPx = s.nudgeStepPx,
            onStep = { v -> set { it.copy(nudgeStepPx = v) } },
            unit = unit, dpi = dpi,
            hint = if (tool.pointsMode && tool.selectedPoint >= 0) "Each arrow moves point ${tool.selectedPoint + 1} by one step" else "Each arrow moves the shape by one step",
            onNudge = { dx, dy -> tool.nudge(dx, dy) },
        )
    }
}

/** Slider for an angle in -180..180° (15° steps with [snap]). */
@Composable
private fun AngleSlider(label: String, deg: Float, snap: Boolean, onChange: (Float) -> Unit) {
    LabeledSlider(
        label = label,
        value = deg,
        onValueChange = { v -> onChange(if (snap) ShapeGeometry.snapDegrees(v) else v.roundToInt().toFloat()) },
        valueRange = -180f..180f,
        valueText = "${Units.formatNumber(deg.toDouble(), 1)}°",
    )
}

private fun direction(deg: Float): Vec2 {
    val r = deg * Geometry.DEG
    return Vec2(cos(r), sin(r))
}

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
            tool.anchors.getOrNull(i)?.let { SelectedAnchor(i, it.sharp, it.hasCustomTangent) }
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)

    // The same steps as the app's undo / redo (which take back one point edit at a time too).
    ToolIconButton(Icons.AutoMirrored.Filled.Undo, "Undo last point", onClick = { tool.undoStep() }, enabled = tool.canUndoStep, size = 44.dp)
    ToolIconButton(Icons.AutoMirrored.Filled.Redo, "Redo point", onClick = { tool.redoStep() }, enabled = tool.redoCount > 0, size = 44.dp)
    val a = selInfo
    if (a != null) {
        val sel = a.index
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
    OptionChip("Fill", s.fill, { set { it.copy(fill = !it.fill) } }, icon = Icons.Filled.FormatColorFill)
    SnapToObjectsChip(tool.controller)
    ActionChip("Numbers", Icons.Filled.Pin) { showNumbers = true }
    ActionChip("Settings", Icons.Filled.Tune) { showSettings = true }

    if (showSettings) CurveSettingsSheet(tool) { showSettings = false }
    if (showNumbers) CurveNumbersSheet(tool) { showNumbers = false }
}

/** What the curve options row shows about the selected anchor. */
private data class SelectedAnchor(val index: Int, val sharp: Boolean, val customTangent: Boolean)

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
                    (if (preset != null) "Painted with ${paintTool.label.lowercase()} \"${preset.name}\" at full pressure" else "Painted with the ${paintTool.label.lowercase()}") +
                        ". The stroke shows while you edit the points.",
                )
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
                LengthEditor(
                    label = "Line width", px = s.plainWidth, onPx = { w -> set { it.copy(plainWidth = w) } },
                    unit = s.unit, onUnit = { u -> set { it.copy(unit = u) } }, dpi = dpi,
                    minPx = ShapeSettings.MIN_STROKE, maxPx = ShapeSettings.MAX_STROKE,
                )
            }
            CurveStroke.NONE -> Hint("Only the fill is painted")
        }

        SectionHeader("Fill")
        ToggleRow(
            "Fill the path", s.fill, { v -> set { it.copy(fill = v) } },
            description = if (s.closed) "Fills the inside of the closed path" else "An open path is filled as if it were closed",
        )
        if (s.fill) FillColorRow(s.fillColor, controller.color) { col -> set { it.copy(fillColor = col) } }

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

// ====================================================================== shared pieces

/** Two length fields side by side (no step buttons; the nudge pad covers fine moves). */
@Composable
private fun FieldPair(
    labelA: String, pxA: Float, onA: (Float) -> Unit,
    labelB: String, pxB: Float, onB: (Float) -> Unit,
    unit: LengthUnit, dpi: Double,
    minPx: Double = -MAX_LEN,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LengthField(labelA, pxA.toDouble(), { onA(it.toFloat()) }, unit, dpi, Modifier.weight(1f), step = null, minPx = minPx, maxPx = MAX_LEN)
        Spacer(Modifier.width(8.dp))
        LengthField(labelB, pxB.toDouble(), { onB(it.toFloat()) }, unit, dpi, Modifier.weight(1f), step = null, minPx = minPx, maxPx = MAX_LEN)
    }
}

/** Nudge pad with its step length ("move 3 px left"). */
@Composable
private fun NudgeRow(
    stepPx: Float,
    onStep: (Float) -> Unit,
    unit: LengthUnit,
    dpi: Double,
    hint: String,
    onNudge: (Int, Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NudgePad(onNudge)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            LengthField("Step", stepPx.toDouble(), { onStep(it.toFloat()) }, unit, dpi, Modifier.fillMaxWidth(), step = null, minPx = 0.01, maxPx = MAX_LEN)
            Hint(hint)
        }
    }
}
