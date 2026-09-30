package com.brushwork.paint.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.IndeterminateCheckBox
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillSettings
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeShape
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

// ---------------------------------------------------------------------- tool option strips

/** Magic wand: mode, tolerance, contiguous, reference, anti-alias. */
@Composable
fun MagicWandOptions(tool: MagicWandTool) {
    val s = tool.settings
    OptionsRow {
        SelectionModeButtons(tool.mode) { tool.mode = it }
        StripDivider()
        SliderChip("Tolerance", s.tolerance, 0..255) { tool.settings = tool.settings.copy(tolerance = it) }
        ToggleChip("Contiguous", s.contiguous) { tool.settings = tool.settings.copy(contiguous = it) }
        ChoiceChip("Refer", SampleSource.entries, s.source, { it.label }) { tool.settings = tool.settings.copy(source = it) }
        ToggleChip("Anti-alias", s.antiAlias) { tool.settings = tool.settings.copy(antiAlias = it) }
        StripDivider()
        SelectionShortcuts(tool.controller)
        BusyIndicator(tool.busy)
    }
}

/**
 * Lasso: freehand / polygon, mode, anti-alias; for the polygon: undo / redo of the last corner,
 * ✓ / ✕ for an unfinished polygon.
 */
@Composable
fun LassoOptions(tool: LassoTool) {
    val s = tool.settings
    OptionsRow {
        ToolIconButton(Icons.Filled.Gesture, "Freehand lasso", onClick = { tool.setPolygonMode(false) }, selected = !s.polygon, size = 40.dp)
        ToolIconButton(Icons.Outlined.Polyline, "Polygon lasso", onClick = { tool.setPolygonMode(true) }, selected = s.polygon, size = 40.dp)
        StripDivider()
        if (s.polygon) {
            ToolIconButton(Icons.AutoMirrored.Filled.Undo, "Undo last corner", onClick = { tool.undoLastCorner() }, enabled = tool.canUndoStep, size = 40.dp)
            ToolIconButton(Icons.AutoMirrored.Filled.Redo, "Redo corner", onClick = { tool.redoStep() }, enabled = tool.redoCount > 0, size = 40.dp)
        }
        if (tool.hasPendingWork) {
            Text("${tool.vertexCount} pt", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim)
            ToolIconButton(Icons.Filled.Close, "Discard polygon", onClick = { tool.discard() }, size = 40.dp)
            ToolIconButton(Icons.Filled.Check, "Close polygon", onClick = { tool.commit() }, enabled = tool.vertexCount >= 3, size = 40.dp)
            StripDivider()
        } else if (s.polygon) {
            Text("Tap corners", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim)
            StripDivider()
        }
        SelectionModeButtons(tool.mode) { tool.mode = it }
        StripDivider()
        ToggleChip("Anti-alias", s.antiAlias) { tool.settings = tool.settings.copy(antiAlias = it) }
        StripDivider()
        SelectionShortcuts(tool.controller)
        BusyIndicator(tool.busy)
    }
}

/** Rectangle / ellipse selection: shape, 1:1, from center, mode. */
@Composable
fun MarqueeOptions(tool: MarqueeTool) {
    val s = tool.settings
    OptionsRow {
        ToolIconButton(Icons.Outlined.Rectangle, "Rectangle selection", onClick = { tool.settings = tool.settings.copy(shape = MarqueeShape.RECTANGLE) }, selected = s.shape == MarqueeShape.RECTANGLE, size = 40.dp)
        ToolIconButton(Icons.Outlined.Circle, "Ellipse selection", onClick = { tool.settings = tool.settings.copy(shape = MarqueeShape.ELLIPSE) }, selected = s.shape == MarqueeShape.ELLIPSE, size = 40.dp)
        StripDivider()
        ToggleChip("1:1", s.square) { tool.settings = tool.settings.copy(square = it) }
        ToggleChip("From center", s.fromCenter) { tool.settings = tool.settings.copy(fromCenter = it) }
        StripDivider()
        SelectionModeButtons(tool.mode) { tool.mode = it }
        StripDivider()
        SelectionShortcuts(tool.controller)
        BusyIndicator(tool.busy)
    }
}

/** Bucket fill: tolerance, reference, gap closing, expand, anti-alias. */
@Composable
fun FillOptions(tool: FillTool) {
    val s = tool.settings
    val c = tool.controller
    OptionsRow {
        ColorSwatch(ColorModeOps.displayColor(c.color, c.doc.colorMode), size = 28.dp)
        StripDivider()
        SliderChip("Tolerance", s.tolerance, 0..255) { tool.settings = tool.settings.copy(tolerance = it) }
        ChoiceChip("Refer", SampleSource.entries, s.source, { it.label }) { tool.settings = tool.settings.copy(source = it) }
        SliderChip("Close gaps", s.gapClose, 0..FillSettings.MAX_GAP_CLOSE, suffix = " px") { tool.settings = tool.settings.copy(gapClose = it) }
        SliderChip("Expand", s.expand, 0..FillSettings.MAX_EXPAND, suffix = " px") { tool.settings = tool.settings.copy(expand = it) }
        ToggleChip("Anti-alias", s.antiAlias) { tool.settings = tool.settings.copy(antiAlias = it) }
        BusyIndicator(tool.busy)
    }
}

/** Eyedropper: source, sample size, return to brush. */
@Composable
fun EyedropperOptions(tool: EyedropperTool) {
    val s = tool.settings
    val c = tool.controller
    OptionsRow {
        ColorSwatch(c.color, size = 28.dp)
        StripDivider()
        ChoiceChip("Sample", listOf(SampleSource.CANVAS, SampleSource.LAYER), s.source, { it.label }) { tool.settings = tool.settings.copy(source = it) }
        ChoiceChip("Size", SAMPLE_SIZES, s.sampleSize, { "$it×$it" }) { tool.settings = tool.settings.copy(sampleSize = it) }
        ToggleChip("Back to brush", s.returnToBrush) { tool.settings = tool.settings.copy(returnToBrush = it) }
    }
}

private val SAMPLE_SIZES = listOf(1, 3, EyedropperTool.MAX_SAMPLE)

// ---------------------------------------------------------------------- shared strip controls

@Composable
private fun OptionsRow(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun StripDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(BrushworkColors.ChromeBorder))
}

@Composable
private fun BusyIndicator(busy: Boolean) {
    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BrushworkColors.Accent)
}

internal fun modeIcon(mode: SelectionMode): ImageVector = when (mode) {
    SelectionMode.REPLACE -> Icons.Outlined.CheckBoxOutlineBlank
    SelectionMode.ADD -> Icons.Outlined.AddBox
    SelectionMode.SUBTRACT -> Icons.Outlined.IndeterminateCheckBox
    SelectionMode.INTERSECT -> Icons.Outlined.JoinInner
}

internal fun modeDescription(mode: SelectionMode): String = when (mode) {
    SelectionMode.REPLACE -> "New selection"
    SelectionMode.ADD -> "Add to selection"
    SelectionMode.SUBTRACT -> "Subtract from selection"
    SelectionMode.INTERSECT -> "Intersect with selection"
}

/** New / Add / Subtract / Intersect as four icon toggles. */
@Composable
internal fun SelectionModeButtons(mode: SelectionMode, onChange: (SelectionMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionMode.entries.forEach { m ->
            ToolIconButton(modeIcon(m), modeDescription(m), onClick = { onChange(m) }, selected = m == mode, size = 40.dp)
        }
    }
}

/** Deselect + open the selection menu. */
@Composable
private fun SelectionShortcuts(controller: EditorController) {
    var menu by remember { mutableStateOf(false) }
    ToolIconButton(Icons.Outlined.Deselect, "Deselect", onClick = { controller.deselect() }, enabled = controller.selection != null, size = 40.dp)
    ToolIconButton(Icons.Outlined.HighlightAlt, "Selection menu", onClick = { menu = true }, size = 40.dp)
    if (menu) SelectionPanel(controller) { menu = false }
}

private val chipColors
    @Composable get() = FilterChipDefaults.filterChipColors(
        selectedContainerColor = BrushworkColors.AccentDim,
        selectedLabelColor = Color.White,
        selectedLeadingIconColor = Color.White,
    )

/** On/off chip with a check mark when on. */
@Composable
internal fun ToggleChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label) },
        leadingIcon = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        colors = chipColors,
    )
}

/** "Label: value ▾" chip that opens a menu of [options]. */
@Composable
internal fun <T> ChoiceChip(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("$label: ${text(selected)}") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(text(o)) },
                    onClick = { onSelect(o); open = false },
                    leadingIcon = { if (o == selected) Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
        }
    }
}

/** "Label value ▾" chip that opens a slider + numeric field for an integer in [range]. */
@Composable
internal fun SliderChip(label: String, value: Int, range: IntRange, suffix: String = "", onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("$label $value$suffix") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
                val span = range.last - range.first
                LabeledSlider(
                    label = label,
                    value = value.toFloat(),
                    onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = if (span in 2..20) span - 1 else 0,
                    valueText = "$value$suffix",
                )
                NumberField(
                    label = label,
                    value = value.toDouble(),
                    onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
                    decimals = 0,
                    suffix = suffix.trim(),
                    min = range.first.toDouble(),
                    max = range.last.toDouble(),
                    step = 1.0,
                )
            }
        }
    }
}
