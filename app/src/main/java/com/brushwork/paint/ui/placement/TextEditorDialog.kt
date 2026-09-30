package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBoxPreset
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.VerticalStyle
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NudgePad
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.max

private val SIZE_UNITS = listOf(LengthUnit.PT, LengthUnit.PX, LengthUnit.MM)

private enum class ColorTarget { FILL, OUTLINE, BOX_FILL, BORDER }

/**
 * Text editor: content plus every style option (font, color, vertical style, alignment,
 * spacing, outline, box, shape / path), in a half-height see-through sheet so the text on the
 * canvas stays in view. Changes apply live to the text on the canvas; Cancel (or the back
 * button) reverts them (and removes a text that was just created), OK keeps them. Taps outside
 * and swipes don't close it, so typed text is never lost by accident.
 */
@Composable
fun TextEditorDialog(tool: TextTool) {
    val item = tool.item ?: return
    val spec = item.spec
    val doc = tool.controller.doc
    val dpi = doc.dpi.toDouble()
    val onPath = item.path.isActive
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    val focus = remember { FocusRequester() }
    fun style(transform: (TextSpec) -> TextSpec) = tool.updateSpec(transform)

    BwSheet(
        title = if (tool.editingNew) "Add text" else "Edit text",
        onDismiss = { tool.cancelEditor() },
        dismissible = false,
        showClose = false,
        actions = {
            TextButton(onClick = { tool.cancelEditor() }) { Text("Cancel") }
            TextButton(onClick = { tool.confirmEditor() }) { Text("OK", fontWeight = FontWeight.SemiBold, color = BrushworkColors.Accent) }
        },
    ) {
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
            ToolIconButton(
                Icons.Filled.TextRotateVertical, "Vertical text",
                onClick = { style { it.copy(vertical = !it.vertical) } },
                selected = spec.vertical && !onPath,
                enabled = !onPath,
            )
            Text(
                when {
                    onPath -> "Follows a shape"
                    spec.vertical -> "Vertical"
                    else -> "Horizontal"
                },
                style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim,
            )
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
            ColorSwatch(spec.color, size = 40.dp, onClick = { colorTarget = ColorTarget.FILL })
            Spacer(Modifier.width(12.dp))
            Text("Text color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { style { it.copy(color = tool.controller.color) } }) { Text("Use drawing color") }
        }

        if (spec.vertical && !onPath) {
            SectionHeader("Vertical text")
            ChoiceChips(VerticalStyle.entries.map { it.label }, spec.verticalStyle.ordinal, { i -> style { it.copy(verticalStyle = VerticalStyle.entries[i]) } })
            Note(
                if (spec.verticalStyle == VerticalStyle.UPRIGHT) "Every letter stands upright, stacked from top to bottom."
                else "Japanese style: Latin words turn sideways, short numbers sit across the column."
            )
            ChoiceChips(listOf("Columns right to left", "Left to right"), if (spec.columnsLeftToRight) 1 else 0, { i -> style { it.copy(columnsLeftToRight = i == 1) } })
        }

        if (!onPath) {
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
        }

        SectionHeader("Spacing")
        LabeledSlider(
            label = "Letter spacing",
            value = spec.letterSpacing,
            onValueChange = { v -> style { it.copy(letterSpacing = v) } },
            valueRange = TextSpec.MIN_LETTER_SPACING..TextSpec.MAX_LETTER_SPACING,
            valueText = Units.formatNumber(spec.letterSpacing.toDouble(), 2) + " em",
            typing = SliderTyping(decimals = 2, suffix = "em"),
        )
        if (!onPath) {
            LabeledSlider(
                label = if (spec.vertical) "Column spacing" else "Line spacing",
                value = spec.lineSpacing,
                onValueChange = { v -> style { it.copy(lineSpacing = v) } },
                valueRange = TextSpec.MIN_LINE_SPACING..TextSpec.MAX_LINE_SPACING,
                valueText = "× " + Units.formatNumber(spec.lineSpacing.toDouble(), 2),
                typing = SliderTyping(decimals = 2, suffix = "×"),
            )
        }

        SectionHeader("Outline")
        val maxOutline = max(1f, spec.sizePx * 0.3f)
        LabeledSlider(
            label = "Outline width",
            value = spec.strokeWidthPx,
            onValueChange = { v -> style { it.copy(strokeWidthPx = v) } },
            valueRange = 0f..maxOutline,
            valueText = if (spec.strokeWidthPx <= 0f) "None" else Units.format(spec.strokeWidthPx.toDouble(), LengthUnit.PX, dpi),
            typing = SliderTyping(decimals = 1, suffix = "px"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            ColorSwatch(spec.strokeColor, size = 40.dp, onClick = { colorTarget = ColorTarget.OUTLINE })
            Spacer(Modifier.width(12.dp))
            Text("Outline color (drawn behind the text)", style = MaterialTheme.typography.bodyMedium)
        }

        TextBoxSection(tool, onPath, onPickFill = { colorTarget = ColorTarget.BOX_FILL }, onPickBorder = { colorTarget = ColorTarget.BORDER })

        SectionHeader("Shape / path")
        Note("Make the text follow a line, a circle, a square or a curve. Drag the dots on the canvas to shape it.")
        TextPathControls(item.path, doc.dpi) { tool.setPath(it) }

        SectionHeader("Edges")
        ToggleRow("Anti-aliasing", spec.antiAlias, { on -> style { it.copy(antiAlias = on) } }, description = "Smooth edges (turn off for crisp 1-bit lettering)")
    }

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
        ColorTarget.BOX_FILL -> ColorPickerDialog(
            initial = spec.box.fillColor,
            onPick = { c -> tool.updateBox { it.copy(fillColor = c, fill = true) } },
            onDismiss = { colorTarget = null },
            title = "Box background",
            showAlpha = true,
        )
        ColorTarget.BORDER -> ColorPickerDialog(
            initial = spec.box.borderColor,
            onPick = { c -> tool.updateBox { it.copy(borderColor = c) } },
            onDismiss = { colorTarget = null },
            title = "Box border color",
            showAlpha = true,
        )
        null -> {}
    }
}

/**
 * The text box: quick presets, fixed width (lines wrap) or height (vertical columns wrap),
 * padding, background, border and corner rounding. Not used while the text follows a shape.
 */
@Composable
private fun TextBoxSection(tool: TextTool, onPath: Boolean, onPickFill: () -> Unit, onPickBorder: () -> Unit) {
    val item = tool.item ?: return
    val spec = item.spec
    val box = spec.box
    val doc = tool.controller.doc
    val dpi = doc.dpi.toDouble()
    SectionHeader("Box")
    if (onPath) {
        Note("Boxes aren't used while the text follows a shape; your box settings are kept for straight text.")
        return
    }
    ChoiceChips(
        TextBoxPreset.entries.map { it.label },
        TextBoxPreset.entries.indexOfFirst { it.matches(box, spec.sizePx) },
        { i -> tool.applyBoxPreset(TextBoxPreset.entries[i]) },
    )
    val fixed = if (spec.vertical) box.height else box.width
    ToggleRow(
        if (spec.vertical) "Fixed height (columns wrap)" else "Fixed width (lines wrap)",
        fixed > 0f,
        { on -> tool.setFixedBox(on) },
        description = "Or drag the handle on the ${if (spec.vertical) "bottom" else "right"} edge of the text",
    )
    if (fixed > 0f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LengthField(
                label = if (spec.vertical) "Box height" else "Box width",
                px = fixed.toDouble(),
                onPxChange = { tool.setBoxLength(it.toFloat()) },
                unit = tool.positionUnit,
                dpi = dpi,
                minPx = spec.sizePx.toDouble().coerceAtMost(tool.maxBoxPx.toDouble()),
                maxPx = tool.maxBoxPx.toDouble(),
                sliderMaxPx = max(doc.width, doc.height).toDouble().coerceAtLeast(spec.sizePx * 2.0),
                modifier = Modifier.weight(1f),
            )
            UnitSelector(tool.positionUnit, { tool.positionUnit = it })
        }
    }
    val maxPadding = max(1f, spec.sizePx * 2f)
    LabeledSlider(
        label = "Padding",
        value = box.padding,
        onValueChange = { v -> tool.updateBox { it.copy(padding = v) } },
        valueRange = 0f..max(maxPadding, box.padding),
        valueText = if (box.padding <= 0f) "None" else Units.format(box.padding.toDouble(), LengthUnit.PX, dpi),
        typing = SliderTyping(decimals = 1, suffix = "px"),
    )
    ToggleRow("Background", box.fill, { on -> tool.updateBox { it.copy(fill = on) } }, description = "Fill the box behind the text")
    if (box.fill) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            ColorSwatch(box.fillColor, size = 40.dp, onClick = onPickFill)
            Spacer(Modifier.width(12.dp))
            Text("Background color (can be see-through)", style = MaterialTheme.typography.bodyMedium)
        }
    }
    val maxBorder = max(1f, spec.sizePx * 0.5f)
    LabeledSlider(
        label = "Border width",
        value = box.borderWidth,
        onValueChange = { v -> tool.updateBox { it.copy(borderWidth = v) } },
        valueRange = 0f..max(maxBorder, box.borderWidth),
        valueText = if (box.borderWidth <= 0f) "None" else Units.format(box.borderWidth.toDouble(), LengthUnit.PX, dpi),
        typing = SliderTyping(decimals = 1, suffix = "px"),
    )
    if (box.borderWidth > 0f) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            ColorSwatch(box.borderColor, size = 40.dp, onClick = onPickBorder)
            Spacer(Modifier.width(12.dp))
            Text("Border color", style = MaterialTheme.typography.bodyMedium)
        }
    }
    LabeledSlider(
        label = "Corner rounding",
        value = box.roundness,
        onValueChange = { v -> tool.updateBox { it.copy(roundness = v) } },
        valueRange = 0f..1f,
        valueText = when {
            box.roundness <= 0f -> "Square"
            box.roundness >= 1f -> "Round"
            else -> Units.formatNumber(box.roundness * 100.0, 0) + " %"
        },
        typing = SliderTyping.Percent,
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(vertical = 2.dp))
}

/** Numeric position / rotation / size of the text, plus a nudge pad. */
@Composable
fun TextNumbersSheet(tool: TextTool) {
    val item = tool.item ?: return
    val doc = tool.controller.doc
    val dpi = doc.dpi.toDouble()
    val unit = tool.positionUnit
    val w = doc.width.toDouble()
    val h = doc.height.toDouble()
    BwSheet(
        title = "Position & size",
        onDismiss = { tool.numbersOpen = false },
        actions = { UnitSelector(unit, { tool.positionUnit = it }) },
    ) {
        Text(
            if (item.path.isActive) "Moving or turning the text moves or turns its shape, in document ${unit.label.lowercase()}."
            else "The position is the center of the text box, in document ${unit.label.lowercase()}.",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
        )
        // Sliders span one canvas size before the canvas to two after it; any number can be typed.
        LengthField(
            "Center X", item.cx.toDouble(), { tool.setCenterX(it.toFloat()) }, unit, dpi, Modifier.padding(top = 8.dp),
            sliderMinPx = -w, sliderMaxPx = 2.0 * w,
        )
        LengthField(
            "Center Y", item.cy.toDouble(), { tool.setCenterY(it.toFloat()) }, unit, dpi, Modifier.padding(top = 8.dp),
            sliderMinPx = -h, sliderMaxPx = 2.0 * h,
        )
        NumberField(
            label = "Rotation",
            value = item.rotationDeg.toDouble(),
            onValueChange = { tool.setRotation(it.toFloat()) },
            modifier = Modifier.padding(top = 8.dp),
            decimals = 1,
            suffix = "°",
            step = 1.0,
            sliderMin = -180.0,
            sliderMax = 180.0,
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
