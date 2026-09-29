package com.brushwork.paint.ui.assist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerGeometry
import com.brushwork.paint.assist.RulerTool
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerSnap
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NudgePad
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------- ruler

/**
 * Numeric ruler editor: type, snap mode, exact position/size in any unit, nudge arrows.
 * Every edit is applied live through controller.updateRuler (not undoable, like on canvas).
 */
@Composable
fun RulerPanel(controller: EditorController, onDismiss: () -> Unit) {
    BwSheet(title = "Ruler", onDismiss = onDismiss) {
        RulerControls(controller, onEditOnCanvas = {
            onDismiss()
            controller.selectTool(ToolId.RULER)
        })
    }
}

@Composable
private fun RulerControls(controller: EditorController, onEditOnCanvas: () -> Unit) {
    val ruler = controller.ruler
    val doc = controller.doc
    val dpi = doc.dpi.toDouble()
    val unit = ruler.unit
    val step = ruler.nudgeStep.toDouble()
    // Always derive from the latest state: hold-to-repeat buttons fire between recompositions.
    fun update(block: (RulerSettings) -> RulerSettings) = controller.updateRuler(block(controller.ruler))

    ToggleRow(
        label = "Use ruler",
        checked = ruler.enabled,
        onCheckedChange = { on ->
            update { r -> (if (r.centerX < 0f || r.centerY < 0f) RulerGeometry.centered(r, doc.width, doc.height) else r).copy(enabled = on) }
        },
        description = "Brush, eraser, smudge and blur strokes follow the ruler",
    )

    SectionHeader("Type")
    ChoiceChips(
        options = RulerType.entries.map { it.label },
        selected = ruler.type.ordinal,
        onSelect = { i -> update { it.copy(type = RulerType.entries[i], enabled = true) } },
    )
    if (ruler.type == RulerType.RADIAL) {
        Hint("Every stroke runs straight toward or away from the center, like lines to a vanishing point.")
    } else {
        SectionHeader("Strokes follow")
        ChoiceChips(
            options = RulerSnap.entries.map { it.label },
            selected = ruler.snap.ordinal,
            onSelect = { i -> update { it.copy(snap = RulerSnap.entries[i]) } },
        )
        Hint(
            if (ruler.snap == RulerSnap.PARALLEL) {
                val shape = if (ruler.type == RulerType.STRAIGHT) "a parallel line" else "a concentric curve"
                "Strokes follow $shape through the point where they start. Starting close to the ruler draws on the ruler itself."
            } else {
                "Every stroke is pulled onto the ruler, wherever it starts."
            }
        )
    }

    SectionHeader("Position and size")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Units", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        UnitSelector(unit, { u -> update { it.copy(unit = u, nudgeStep = u.defaultStep.toFloat()) } })
    }
    NumberField(
        label = "Nudge step",
        value = step,
        onValueChange = { v -> update { it.copy(nudgeStep = v.toFloat()) } },
        modifier = Modifier.fillMaxWidth(),
        decimals = unit.decimals + 1,
        suffix = unit.short,
        min = 0.001,
    )
    LengthField("Center X", ruler.centerX.toDouble(), { v -> update { it.copy(centerX = v.toFloat()) } }, unit, dpi, Modifier.fillMaxWidth(), step = step)
    LengthField("Center Y", ruler.centerY.toDouble(), { v -> update { it.copy(centerY = v.toFloat()) } }, unit, dpi, Modifier.fillMaxWidth(), step = step)
    when (ruler.type) {
        RulerType.CIRCLE ->
            LengthField("Radius", ruler.radius.toDouble(), { v -> update { it.copy(radius = v.toFloat()) } }, unit, dpi, Modifier.fillMaxWidth(), step = step, minPx = MIN_RADIUS_PX)
        RulerType.ELLIPSE -> {
            LengthField("Radius X", ruler.radiusX.toDouble(), { v -> update { it.copy(radiusX = v.toFloat()) } }, unit, dpi, Modifier.fillMaxWidth(), step = step, minPx = MIN_RADIUS_PX)
            LengthField("Radius Y", ruler.radiusY.toDouble(), { v -> update { it.copy(radiusY = v.toFloat()) } }, unit, dpi, Modifier.fillMaxWidth(), step = step, minPx = MIN_RADIUS_PX)
        }
        else -> {}
    }
    if (ruler.type != RulerType.CIRCLE) {
        NumberField(
            label = "Angle",
            value = ruler.angleDeg.toDouble(),
            onValueChange = { v -> update { it.copy(angleDeg = RulerGeometry.normalizeAngle(v.toFloat())) } },
            modifier = Modifier.fillMaxWidth(),
            decimals = 2,
            suffix = "°",
            step = 1.0,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in ANGLE_PRESETS) {
                FilterChip(
                    selected = abs(ruler.angleDeg - a) < 0.005f,
                    onClick = { update { it.copy(angleDeg = a) } },
                    label = { Text("${a.roundToInt()}°") },
                    colors = chipColors(),
                )
            }
        }
    }
    if (ruler.type == RulerType.RADIAL) {
        NumberField(
            label = "Guide lines",
            value = ruler.radialLines.toDouble(),
            onValueChange = { v -> update { it.copy(radialLines = v.roundToInt().coerceIn(MIN_RADIAL_LINES, MAX_RADIAL_LINES)) } },
            modifier = Modifier.fillMaxWidth(),
            decimals = 0,
            min = MIN_RADIAL_LINES.toDouble(),
            max = MAX_RADIAL_LINES.toDouble(),
            step = 1.0,
        )
    }

    SectionHeader("Move by ${formatStep(ruler)}")
    Row(verticalAlignment = Alignment.CenterVertically) {
        NudgePad(onNudge = { dx, dy ->
            update { r ->
                val px = r.unit.toPx(r.nudgeStep.toDouble(), dpi)
                r.copy(centerX = (r.centerX + dx * px).toFloat(), centerY = (r.centerY + dy * px).toFloat())
            }
        })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PanelButton("Center on canvas", Icons.Filled.CenterFocusStrong) { update { RulerGeometry.centered(it, doc.width, doc.height) } }
            PanelButton("Reset", Icons.Filled.RestartAlt) { update { RulerGeometry.reset(it, doc.width, doc.height) } }
            Button(onClick = onEditOnCanvas, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Edit on canvas")
            }
        }
    }
}

private fun formatStep(r: RulerSettings): String =
    "${Units.formatNumber(r.nudgeStep.toDouble(), r.unit.decimals + 1)} ${r.unit.short}"

/**
 * Options strip of the ruler tool: ruler type, a "Numbers" sheet (the full [RulerPanel]) and
 * "Done" to go back to painting.
 */
@Composable
fun RulerToolOptions(tool: RulerTool) {
    val controller = tool.controller
    val ruler = controller.ruler
    var showNumbers by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (type in RulerType.entries) {
            ToolIconButton(
                icon = AssistIcons.of(type),
                contentDescription = "${type.label} ruler",
                onClick = { controller.updateRuler(controller.ruler.copy(type = type, enabled = true)) },
                selected = ruler.type == type,
                size = 40.dp,
            )
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = { showNumbers = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Numbers")
        }
        Button(
            onClick = { controller.selectTool(controller.lastPaintTool) },
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(36.dp),
        ) { Text("Done") }
    }
    if (showNumbers) RulerPanel(controller, onDismiss = { showNumbers = false })
}

// ---------------------------------------------------------------------------------- grid

private data class GridPreset(val label: String, val value: Double, val unit: LengthUnit)

private val GRID_PRESETS = listOf(
    GridPreset("8 px", 8.0, LengthUnit.PX),
    GridPreset("16 px", 16.0, LengthUnit.PX),
    GridPreset("32 px", 32.0, LengthUnit.PX),
    GridPreset("64 px", 64.0, LengthUnit.PX),
    GridPreset("100 px", 100.0, LengthUnit.PX),
    GridPreset("1 cm", 1.0, LengthUnit.CM),
    GridPreset("1 in", 1.0, LengthUnit.IN),
)

/** Grid overlay settings (display only; the grid is never painted into the artwork). */
@Composable
fun GridPanel(controller: EditorController, onDismiss: () -> Unit) {
    val grid = controller.grid
    val dpi = controller.doc.dpi.toDouble()
    var pickColor by remember { mutableStateOf(false) }
    fun update(block: (GridSettings) -> GridSettings) = controller.updateGrid(block(controller.grid))

    BwSheet(title = "Grid", onDismiss = onDismiss) {
        ToggleRow(
            label = "Show grid",
            checked = grid.enabled,
            onCheckedChange = { on -> update { it.copy(enabled = on) } },
            description = "A guide on screen only, never part of the picture",
        )

        SectionHeader("Type")
        ChoiceChips(
            options = GridType.entries.map { it.label },
            selected = grid.type.ordinal,
            onSelect = { i -> update { it.copy(type = GridType.entries[i], enabled = true) } },
        )

        if (grid.type == GridType.SQUARE || grid.type == GridType.ISOMETRIC) {
            SectionHeader(if (grid.type == GridType.ISOMETRIC) "Triangle size" else "Cell size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                LengthField(
                    label = "Spacing",
                    px = grid.spacingPx.toDouble(),
                    onPxChange = { v -> update { it.copy(spacingPx = v.toFloat(), enabled = true) } },
                    unit = grid.unit,
                    dpi = dpi,
                    modifier = Modifier.weight(1f),
                    minPx = 1.0,
                )
                UnitSelector(grid.unit, { u -> update { it.copy(unit = u) } })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in GRID_PRESETS) {
                    val px = p.unit.toPx(p.value, dpi)
                    FilterChip(
                        selected = grid.unit == p.unit && abs(grid.spacingPx - px) < 0.01,
                        onClick = { update { it.copy(spacingPx = px.toFloat(), unit = p.unit, enabled = true) } },
                        label = { Text(p.label) },
                        colors = chipColors(),
                    )
                }
            }
            NumberField(
                label = "Bold line every",
                value = grid.majorEvery.toDouble(),
                onValueChange = { v -> update { it.copy(majorEvery = v.roundToInt().coerceIn(0, MAX_MAJOR_EVERY)) } },
                modifier = Modifier.fillMaxWidth(),
                decimals = 0,
                suffix = "lines",
                min = 0.0,
                max = MAX_MAJOR_EVERY.toDouble(),
                step = 1.0,
            )
            Hint("0 or 1 draws every line the same.")
            SectionHeader("Offset")
            LengthField("Offset X", grid.offsetXPx.toDouble(), { v -> update { it.copy(offsetXPx = v.toFloat()) } }, grid.unit, dpi, Modifier.fillMaxWidth())
            LengthField("Offset Y", grid.offsetYPx.toDouble(), { v -> update { it.copy(offsetYPx = v.toFloat()) } }, grid.unit, dpi, Modifier.fillMaxWidth())
        }

        SectionHeader("Appearance")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Line color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ColorSwatch(color = grid.color or OPAQUE, size = 40.dp, onClick = { pickColor = true })
        }
        LabeledSlider(
            label = "Opacity",
            value = grid.opacity,
            onValueChange = { v -> update { it.copy(opacity = v) } },
            valueRange = 0.05f..1f,
            valueText = "${(grid.opacity * 100).roundToInt()}%",
        )
        ToggleRow(
            label = "Snap to grid",
            checked = grid.snap,
            onCheckedChange = { on -> update { it.copy(snap = on) } },
            description = "Shape and selection tools snap points to grid intersections",
        )

        if (pickColor) {
            ColorPickerDialog(
                initial = grid.color or OPAQUE,
                onPick = { c -> update { it.copy(color = c or OPAQUE) } },
                onDismiss = { pickColor = false },
                title = "Grid color",
                showAlpha = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------------- stabilizer

/** Stabilizer mode + parameters, with a scratch pad to try the current settings. */
@Composable
fun StabilizerPanel(controller: EditorController, onDismiss: () -> Unit) {
    // Sliders edit a draft and save on release: updateStabilizer writes preferences.
    var draft by remember { mutableStateOf(controller.stabilizer) }
    val latest by rememberUpdatedState(draft)
    DisposableEffect(controller) {
        onDispose { if (latest != controller.stabilizer) controller.updateStabilizer(latest) }
    }
    fun commit(s: StabilizerSettings) {
        draft = s
        controller.updateStabilizer(s)
    }

    BwSheet(title = "Stabilizer", onDismiss = onDismiss) {
        ChoiceChips(
            options = listOf("Off", "Smooth", "Rope"),
            selected = draft.mode.ordinal,
            onSelect = { i -> commit(draft.copy(mode = StabilizerMode.entries[i])) },
        )
        Hint(
            when (draft.mode) {
                StabilizerMode.OFF -> "Strokes follow your finger exactly."
                StabilizerMode.SMOOTH -> "Averages out shaky movement. The brush trails a little behind your finger; more strength means smoother lines and more trailing."
                StabilizerMode.ROPE -> "The brush hangs on a string behind your finger and only moves when the string is pulled tight. Small wobbles within the string's length paint nothing, so lines come out clean and deliberate."
            }
        )
        when (draft.mode) {
            StabilizerMode.SMOOTH -> LabeledSlider(
                label = "Strength",
                value = draft.strength,
                onValueChange = { v -> draft = draft.copy(strength = v) },
                valueRange = 0f..1f,
                valueText = "${(draft.strength * 100).roundToInt()}%",
                onValueChangeFinished = { controller.updateStabilizer(draft) },
            )
            StabilizerMode.ROPE -> LabeledSlider(
                label = "Rope length",
                value = draft.ropeLengthDp,
                onValueChange = { v -> draft = draft.copy(ropeLengthDp = v) },
                valueRange = MIN_ROPE_DP..MAX_ROPE_DP,
                valueText = "${draft.ropeLengthDp.roundToInt()} dp",
                onValueChangeFinished = { controller.updateStabilizer(draft) },
            )
            StabilizerMode.OFF -> {}
        }
        if (draft.mode != StabilizerMode.OFF) {
            ToggleRow(
                label = "Catch up when lifting",
                checked = draft.catchUp,
                onCheckedChange = { on -> commit(draft.copy(catchUp = on)) },
                description = "Finish the line at your finger instead of where the brush trails",
            )
        }
        SectionHeader("Try it")
        StabilizerTryPad(draft, Modifier.fillMaxWidth().height(170.dp))
    }
}

// ---------------------------------------------------------------------------------- shared bits

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = BrushworkColors.OnChromeDim,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun PanelButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, maxLines = 1)
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White)

private const val OPAQUE = 0xFF000000.toInt()
private const val MIN_RADIUS_PX = 1.0
private const val MIN_RADIAL_LINES = 2
private const val MAX_RADIAL_LINES = 360
private const val MAX_MAJOR_EVERY = 100
private const val MIN_ROPE_DP = 5f
private const val MAX_ROPE_DP = 200f
private val ANGLE_PRESETS = floatArrayOf(0f, 45f, 90f, -45f)
