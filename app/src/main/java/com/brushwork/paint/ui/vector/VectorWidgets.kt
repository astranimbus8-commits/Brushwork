package com.brushwork.paint.ui.vector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Hexagon
import androidx.compose.material.icons.outlined.StarOutline
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.ShapeGeometry
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NudgePad
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Icon of a shape type. */
internal fun shapeIcon(type: ShapeType): ImageVector = when (type) {
    ShapeType.LINE -> Icons.Filled.HorizontalRule
    ShapeType.RECTANGLE -> Icons.Outlined.CropSquare
    ShapeType.ELLIPSE -> Icons.Outlined.Circle
    ShapeType.POLYGON -> Icons.Outlined.Hexagon
    ShapeType.STAR -> Icons.Outlined.StarOutline
    ShapeType.ARROW -> Icons.AutoMirrored.Filled.ArrowRightAlt
}

/** Logarithmic slider mapping for widths / radii (fine control for small values). */
internal class LogScale(private val min: Float, private val max: Float) {
    fun toPos(v: Float): Float = (ln(v.coerceIn(min, max) / min) / ln(max / min)).coerceIn(0f, 1f)
    fun fromPos(p: Float): Float = min * (max / min).pow(p.coerceIn(0f, 1f))
}

/**
 * A length in document pixels: numeric field in [unit] + unit picker (unless [showUnit] is
 * false because the sheet has a shared one), and optionally a logarithmic slider from
 * [sliderMin] to [sliderMax] px.
 */
@Composable
internal fun LengthEditor(
    label: String,
    px: Float,
    onPx: (Float) -> Unit,
    unit: LengthUnit,
    onUnit: (LengthUnit) -> Unit,
    dpi: Float,
    minPx: Float,
    maxPx: Float,
    sliderMin: Float = 0.5f,
    sliderMax: Float = 500f,
    showSlider: Boolean = true,
    showUnit: Boolean = true,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LengthField(
            label = label,
            px = px.toDouble(),
            onPxChange = { onPx(it.toFloat()) },
            unit = unit,
            dpi = dpi.toDouble(),
            modifier = Modifier.weight(1f),
            minPx = minPx.toDouble(),
            maxPx = maxPx.toDouble(),
            enabled = enabled,
            // Its own log slider follows below: no second one in the field.
            adjust = if (showSlider) com.brushwork.paint.ui.common.NumberAdjust.NONE else com.brushwork.paint.ui.common.NumberAdjust.AUTO,
        )
        if (showUnit) UnitSelector(unit, onUnit)
    }
    if (showSlider) {
        val scale = LogScale(sliderMin, sliderMax)
        Slider(
            value = scale.toPos(px),
            onValueChange = { onPx(scale.fromPos(it)) },
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
        )
    }
}

/** Integer field with -/+ buttons. */
@Composable
internal fun IntStepper(label: String, value: Int, onChange: (Int) -> Unit, range: IntRange, modifier: Modifier = Modifier) {
    NumberField(
        label = label,
        value = value.toDouble(),
        onValueChange = { if (it.isFinite()) onChange(it.roundToInt().coerceIn(range)) },
        modifier = modifier.fillMaxWidth(),
        decimals = 0,
        min = range.first.toDouble(),
        max = range.last.toDouble(),
        step = 1.0,
    )
}

/** Toggle chip used in the tool option strips. */
@Composable
internal fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
        modifier = Modifier.padding(horizontal = 3.dp),
    )
}

/**
 * One-shot action chip used in the tool option strips. With [contentDescription] the chip still
 * shows [label] but is known by the description alone (I10): the strips' "Settings" chips are
 * "Curve settings", "Shape settings"…, told apart from the tool menu's "Settings" cell (as the
 * Text tool's Letters chips are).
 */
@Composable
internal fun ActionChip(
    label: String,
    icon: ImageVector,
    tint: Color = BrushworkColors.OnChrome,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1, modifier = if (contentDescription != null) Modifier.clearAndSetSemantics {} else Modifier) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint) },
        colors = AssistChipDefaults.assistChipColors(labelColor = BrushworkColors.OnChrome),
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    )
}

/**
 * Chip that shows the current choice and opens a menu of [options] (the selected one is
 * checked). [leading] decorates the chip, [optionLeading] each menu item.
 */
@Composable
internal fun <T> DropdownChip(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    optionLeading: (@Composable (T) -> Unit)? = null,
    contentDescription: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 3.dp)) {
        AssistChip(
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = leading,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = contentDescription, modifier = Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(labelColor = BrushworkColors.OnChrome, leadingIconContentColor = BrushworkColors.OnChrome, trailingIconContentColor = BrushworkColors.OnChromeDim),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(optionLabel(o)) },
                    onClick = { onSelect(o); open = false },
                    leadingIcon = optionLeading?.let { f -> { f(o) } },
                    trailingIcon = if (o == selected) ({ Icon(Icons.Filled.Check, contentDescription = "Selected", tint = BrushworkColors.Accent) }) else null,
                )
            }
        }
    }
}

/** Read-only note that strokes use the main drawing color. */
@Composable
internal fun MainColorNote(color: Int, text: String = "Stroke uses the main drawing color") {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(color, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
    }
}

/** Small explanatory text under a control. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = modifier.padding(vertical = 2.dp))
}

/** Small glyph that shows a shape style (outline / filled / both) with the fill color. */
@Composable
internal fun StyleGlyph(style: ShapeStyle, fillColor: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val inset = 3.dp.toPx()
        val r = CornerRadius(4.dp.toPx())
        val topLeft = Offset(inset, inset)
        val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
        if (style.fill) {
            drawRoundRect(Color(fillColor), topLeft, sz, r)
            if (!style.stroke) drawRoundRect(BrushworkColors.OnChromeDim, topLeft, sz, r, style = Stroke(1.dp.toPx()))
        }
        if (style.stroke) drawRoundRect(BrushworkColors.OnChrome, topLeft, sz, r, style = Stroke(2.5.dp.toPx()))
    }
}

/**
 * Fill color row: swatch (opens the color picker with alpha) and a way back to following the
 * main drawing color. [custom] is null when the fill follows the main color.
 */
@Composable
internal fun FillColorRow(custom: Int?, mainColor: Int, keptLabel: String? = null, onPick: (Int?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val shown = custom ?: mainColor
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(shown, size = 36.dp, onClick = { picking = true })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Fill color", style = MaterialTheme.typography.bodyMedium)
            // [keptLabel]: without a color of its own the object keeps a fill it already has (a
            // gradient of an imported path), not the main color.
            Text(
                when {
                    custom != null -> "Custom color"
                    keptLabel != null -> keptLabel
                    else -> "Same as the main color"
                },
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
            )
        }
        if (custom != null) TextButton(onClick = { onPick(null) }) { Text("Use main") }
        TextButton(onClick = { picking = true }) { Text("Pick") }
    }
    if (picking) {
        ColorPickerDialog(
            initial = shown,
            onPick = { onPick(it) },
            onDismiss = { picking = false },
            title = "Fill color",
            showAlpha = true,
        )
    }
}

// ====================================================================== shared by the shape and curve options (moved from VectorOptions.kt, v1.5)

private val VECTOR_MAX_LEN = ShapeSettings.MAX_LENGTH.toDouble()

/** Integer field with -/+ buttons, under a slider for quick changes. */
@Composable
internal fun IntSliderField(label: String, value: Int, onChange: (Int) -> Unit, range: IntRange) {
    LabeledSlider(
        label = label,
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        valueText = value.toString(),
    )
    IntStepper(label, value, onChange, range)
}

/** Slider for an angle in -180..180° (15° steps with [snap]). */
@Composable
internal fun AngleSlider(label: String, deg: Float, snap: Boolean, onChange: (Float) -> Unit) {
    LabeledSlider(
        label = label,
        value = deg,
        onValueChange = { v -> onChange(if (snap) ShapeGeometry.snapDegrees(v) else v.roundToInt().toFloat()) },
        valueRange = -180f..180f,
        valueText = "${Units.formatNumber(deg.toDouble(), 1)}°",
    )
}

internal fun direction(deg: Float): Vec2 {
    val r = deg * Geometry.DEG
    return Vec2(cos(r), sin(r))
}

/** Two length fields side by side (no step buttons; the nudge pad covers fine moves). */
@Composable
internal fun FieldPair(
    labelA: String, pxA: Float, onA: (Float) -> Unit,
    labelB: String, pxB: Float, onB: (Float) -> Unit,
    unit: LengthUnit, dpi: Double,
    minPx: Double = -VECTOR_MAX_LEN,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LengthField(labelA, pxA.toDouble(), { onA(it.toFloat()) }, unit, dpi, Modifier.weight(1f), step = null, minPx = minPx, maxPx = VECTOR_MAX_LEN)
        Spacer(Modifier.width(8.dp))
        LengthField(labelB, pxB.toDouble(), { onB(it.toFloat()) }, unit, dpi, Modifier.weight(1f), step = null, minPx = minPx, maxPx = VECTOR_MAX_LEN)
    }
}

/** Nudge pad with its step length ("move 3 px left"). */
@Composable
internal fun NudgeRow(
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
            LengthField("Step", stepPx.toDouble(), { onStep(it.toFloat()) }, unit, dpi, Modifier.fillMaxWidth(), step = null, minPx = 0.01, maxPx = VECTOR_MAX_LEN)
            Hint(hint)
        }
    }
}
