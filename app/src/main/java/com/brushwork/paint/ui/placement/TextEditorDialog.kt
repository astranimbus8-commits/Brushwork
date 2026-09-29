package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.TextRotateVertical
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
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
import kotlin.math.max

private val SIZE_UNITS = listOf(LengthUnit.PT, LengthUnit.PX, LengthUnit.MM)

private enum class ColorTarget { FILL, OUTLINE }

/**
 * Text editor: content plus every style option. Changes apply live to the text on the canvas;
 * Cancel reverts them (and removes a text that was just created).
 */
@Composable
fun TextEditorDialog(tool: TextTool) {
    val item = tool.item ?: return
    val spec = item.spec
    val dpi = tool.controller.doc.dpi.toDouble()
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    val focus = remember { FocusRequester() }
    fun style(transform: (TextSpec) -> TextSpec) = tool.updateSpec(transform)

    AlertDialog(
        onDismissRequest = { tool.cancelEditor() },
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = BrushworkColors.ChromeHigh,
        title = { Text(if (tool.editingNew) "Add text" else "Edit text") },
        confirmButton = { TextButton(onClick = { tool.confirmEditor() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { tool.cancelEditor() }) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = item.text,
                    onValueChange = { tool.setText(it) },
                    label = { Text("Text") },
                    placeholder = { Text(if (spec.vertical) "縦書き / vertical text" else "Type here") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                // Same composition as the field, so the requester is attached when this runs.
                LaunchedEffect(Unit) {
                    if (tool.editingNew) runCatching { focus.requestFocus() }
                }

                SectionHeader("Font")
                ChoiceChips(TextFont.entries.map { it.label }, spec.font.ordinal, { i -> style { it.copy(font = TextFont.entries[i]) } })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolIconButton(Icons.Filled.FormatBold, "Bold", onClick = { style { it.copy(bold = !it.bold) } }, selected = spec.bold)
                    ToolIconButton(Icons.Filled.FormatItalic, "Italic", onClick = { style { it.copy(italic = !it.italic) } }, selected = spec.italic)
                    ToolIconButton(Icons.Filled.TextRotateVertical, "Vertical text", onClick = { style { it.copy(vertical = !it.vertical) } }, selected = spec.vertical)
                    Text(if (spec.vertical) "Vertical" else "Horizontal", style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LengthField(
                        label = "Size",
                        px = spec.sizePx.toDouble(),
                        onPxChange = { tool.setSizePx(it.toFloat()) },
                        unit = tool.sizeUnit,
                        dpi = dpi,
                        minPx = TextSpec.MIN_SIZE_PX.toDouble(),
                        maxPx = tool.maxSizePx.toDouble(),
                        modifier = Modifier.weight(1f),
                    )
                    UnitSelector(tool.sizeUnit, { tool.sizeUnit = it }, units = SIZE_UNITS)
                }

                SectionHeader("Color")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorSwatch(spec.color, size = 36.dp, onClick = { colorTarget = ColorTarget.FILL })
                    Spacer(Modifier.width(12.dp))
                    Text("Text color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { style { it.copy(color = tool.controller.color) } }) { Text("Use drawing color") }
                }

                SectionHeader(if (spec.vertical) "Column alignment" else "Alignment")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextAlign.entries.forEach { a ->
                        val icon = when (a) {
                            TextAlign.START -> if (spec.vertical) Icons.Filled.VerticalAlignTop else Icons.AutoMirrored.Filled.FormatAlignLeft
                            TextAlign.CENTER -> if (spec.vertical) Icons.Filled.VerticalAlignCenter else Icons.Filled.FormatAlignCenter
                            TextAlign.END -> if (spec.vertical) Icons.Filled.VerticalAlignBottom else Icons.AutoMirrored.Filled.FormatAlignRight
                        }
                        ToolIconButton(icon, if (spec.vertical) a.verticalLabel else a.horizontalLabel, onClick = { style { it.copy(align = a) } }, selected = spec.align == a)
                    }
                }

                SectionHeader("Spacing")
                LabeledSlider(
                    label = "Letter spacing",
                    value = spec.letterSpacing,
                    onValueChange = { v -> style { it.copy(letterSpacing = v) } },
                    valueRange = TextSpec.MIN_LETTER_SPACING..1f,
                    valueText = Units.formatNumber(spec.letterSpacing.toDouble(), 2) + " em",
                )
                LabeledSlider(
                    label = if (spec.vertical) "Column spacing" else "Line spacing",
                    value = spec.lineSpacing,
                    onValueChange = { v -> style { it.copy(lineSpacing = v) } },
                    valueRange = TextSpec.MIN_LINE_SPACING..3f,
                    valueText = "× " + Units.formatNumber(spec.lineSpacing.toDouble(), 2),
                )

                SectionHeader("Outline")
                val maxOutline = max(1f, spec.sizePx * 0.3f)
                LabeledSlider(
                    label = "Outline width",
                    value = spec.strokeWidthPx,
                    onValueChange = { v -> style { it.copy(strokeWidthPx = v) } },
                    valueRange = 0f..maxOutline,
                    valueText = if (spec.strokeWidthPx <= 0f) "None" else Units.format(spec.strokeWidthPx.toDouble(), LengthUnit.PX, dpi),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    ColorSwatch(spec.strokeColor, size = 36.dp, onClick = { colorTarget = ColorTarget.OUTLINE })
                    Spacer(Modifier.width(12.dp))
                    Text("Outline color (drawn behind the text)", style = MaterialTheme.typography.bodyMedium)
                }
                ToggleRow("Anti-aliasing", spec.antiAlias, { on -> style { it.copy(antiAlias = on) } }, description = "Smooth edges (turn off for crisp 1-bit lettering)")
            }
        },
    )

    when (colorTarget) {
        ColorTarget.FILL -> ColorPickerDialog(
            initial = spec.color,
            onPick = { c -> style { it.copy(color = c) } },
            onDismiss = { colorTarget = null },
            title = "Text color",
            showAlpha = true,
        )
        ColorTarget.OUTLINE -> ColorPickerDialog(
            initial = spec.strokeColor,
            onPick = { c -> style { it.copy(strokeColor = c) } },
            onDismiss = { colorTarget = null },
            title = "Outline color",
            showAlpha = true,
        )
        null -> {}
    }
}

/** Numeric position / rotation / size of the text, plus a nudge pad. */
@Composable
fun TextNumbersSheet(tool: TextTool) {
    val item = tool.item ?: return
    val dpi = tool.controller.doc.dpi.toDouble()
    val unit = tool.positionUnit
    BwSheet(
        title = "Position & size",
        onDismiss = { tool.numbersOpen = false },
        actions = { UnitSelector(unit, { tool.positionUnit = it }) },
    ) {
        Text(
            "The position is the center of the text box, in document ${unit.label.lowercase()}.",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
        )
        LengthField("Center X", item.cx.toDouble(), { tool.setCenterX(it.toFloat()) }, unit, dpi, Modifier.padding(top = 8.dp))
        LengthField("Center Y", item.cy.toDouble(), { tool.setCenterY(it.toFloat()) }, unit, dpi, Modifier.padding(top = 8.dp))
        NumberField(
            label = "Rotation",
            value = item.rotationDeg.toDouble(),
            onValueChange = { tool.setRotation(it.toFloat()) },
            modifier = Modifier.padding(top = 8.dp),
            decimals = 1,
            suffix = "°",
            step = 1.0,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            LengthField(
                label = "Size",
                px = item.spec.sizePx.toDouble(),
                onPxChange = { tool.setSizePx(it.toFloat()) },
                unit = tool.sizeUnit,
                dpi = dpi,
                minPx = TextSpec.MIN_SIZE_PX.toDouble(),
                maxPx = tool.maxSizePx.toDouble(),
                modifier = Modifier.weight(1f),
            )
            UnitSelector(tool.sizeUnit, { tool.sizeUnit = it }, units = SIZE_UNITS)
        }
        SectionHeader("Nudge (${Units.formatNumber(unit.defaultStep, unit.decimals)} ${unit.short})")
        NudgePad(
            onNudge = { dx, dy ->
                val step = unit.toPx(unit.defaultStep, dpi).toFloat()
                tool.nudge(dx * step, dy * step)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
