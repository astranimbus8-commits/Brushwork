package com.brushwork.paint.ui.vector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Hexagon
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.ShapeGeometry
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.IconLongPressScope
import com.brushwork.paint.ui.common.IncrementStepping
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.NudgePad
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.PaintModeGlyph
import com.brushwork.paint.ui.common.PaintModeSegments
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.common.iconLongPressLabel
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.common.paintModeName
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
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
    ShapeType.COG -> Icons.Outlined.Settings
}

/** Logarithmic slider mapping for widths / radii (fine control for small values). */
internal class LogScale(private val min: Float, private val max: Float) {
    fun toPos(v: Float): Float = (ln(v.coerceIn(min, max) / min) / ln(max / min)).coerceIn(0f, 1f)
    fun fromPos(p: Float): Float = min * (max / min).pow(p.coerceIn(0f, 1f))
}

/**
 * A length in document pixels: numeric field in [unit] + unit picker (unless [showUnit] is
 * false because the sheet has a shared one), and optionally a logarithmic slider from
 * [sliderMin] to [sliderMax] px. With an [incrementKind] (v1.6 §3.4) the field's − / + and the
 * slider land on the multiples of that step while increments are on (the slider's ends stay
 * reachable); without one the field steps by the Length increment and the slider is free.
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
    incrementKind: IncrementKind? = null,
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
            incrementKind = incrementKind,
        )
        if (showUnit) UnitSelector(unit, onUnit)
    }
    if (showSlider) {
        val scale = LogScale(sliderMin, sliderMax)
        // The step in document px (the slider's own unit).
        val pxStep = incrementKind?.let { LocalIncrements.current?.step(it) }?.toDouble()
        Slider(
            value = scale.toPos(px),
            onValueChange = { pos ->
                val v = scale.fromPos(pos)
                onPx(if (pxStep == null) v else IncrementStepping.snapSlider(v.toDouble(), pxStep, sliderMin.toDouble(), sliderMax.toDouble()).toFloat())
            },
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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }) {
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

// ---------------------------------------------------------------------- paint mode (v1.10, R3)
// One control for one function: every place that picks Stroke, Fill or Both shows the same three
// glyphs (PaintModeGlyph, the v1.9 Shape StyleGlyph drawing), icons only, known by CurveLabels17's
// three names. The enums keep their names (the codecs read them); these map them.

/** v1.10 (R3): the paint mode of a shape style. */
internal fun ShapeStyle.paintMode(): PaintMode = when (this) {
    ShapeStyle.STROKE -> PaintMode.STROKE
    ShapeStyle.FILL -> PaintMode.FILL
    ShapeStyle.STROKE_FILL -> PaintMode.BOTH
}

/** v1.10 (R3): the shape style of a paint mode. */
internal fun PaintMode.shapeStyle(): ShapeStyle = when (this) {
    PaintMode.STROKE -> ShapeStyle.STROKE
    PaintMode.FILL -> ShapeStyle.FILL
    PaintMode.BOTH -> ShapeStyle.STROKE_FILL
}

/** v1.10 (R3): the paint mode of a curve tool's paint (Curve, Path and Polyline). */
internal fun CurvePaint.paintMode(): PaintMode = when (this) {
    CurvePaint.STROKE -> PaintMode.STROKE
    CurvePaint.FILL -> PaintMode.FILL
    CurvePaint.BOTH -> PaintMode.BOTH
}

/** v1.10 (R3): the curve tools' paint of a paint mode. */
internal fun PaintMode.curvePaint(): CurvePaint = when (this) {
    PaintMode.STROKE -> CurvePaint.STROKE
    PaintMode.FILL -> CurvePaint.FILL
    PaintMode.BOTH -> CurvePaint.BOTH
}

/** v1.10 (R3): the tag prefixes of the paint-mode controls ([V110Tags.PAINT_MODE], one per place). */
internal object PaintModeTags {
    /** The Shape settings sheet. */
    const val SHAPE = "${V110Tags.PAINT_MODE}.shape"
    /** The Curve, Path and Polyline settings sheet. */
    const val CURVE = "${V110Tags.PAINT_MODE}.curve"
    /** The Shape options strip's style cell (its menu). */
    const val SHAPE_STRIP = "${V110Tags.PAINT_MODE}.shapeStrip"
    /** The curve strips' Stroke kind cell (its menu). */
    const val CURVE_STRIP = "${V110Tags.PAINT_MODE}.curveStrip"
}

/**
 * v1.10 (R3): the paint mode in a settings sheet: the three [PaintModeSegments] (icons only,
 * tagged "$tag.<MODE>") with their long-press names (the sheet host resets the scope, so the row
 * provides its own).
 */
@Composable
internal fun PaintModeSheetRow(selected: PaintMode?, tag: String, onSelect: (PaintMode) -> Unit) {
    IconLongPressScope {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PaintModeSegments(selected = selected, tagPrefix = tag, onSelect = onSelect)
        }
    }
}

/**
 * v1.10 (R3; the strip's v1.7 style dropdown): a cell [width] wide showing [mode]'s
 * [PaintModeGlyph] (filled with [fill]) and ▾, known by [label] (its description and long-press
 * label); a tap opens a menu holding the three [PaintModeSegments], icons only (tagged
 * "$tag.<MODE>", each known by its name). A pick closes the menu. (A menu is a popup: its
 * segments show no long-press bubble there, see [IconLongPressScope]; their descriptions stay.)
 */
@Composable
internal fun PaintModeMenuCell(label: String, mode: PaintMode, fill: Int, width: Dp, tag: String, onPick: (PaintMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.width(width)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SHAPE_CELL_DP)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .longPressLabel(label)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = label }
                .padding(start = 6.dp, end = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { PaintModeGlyph(mode, Color(fill)) }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }, containerColor = IbisColors.Menu) {
            Box(Modifier.padding(horizontal = 8.dp)) {
                PaintModeSegments(selected = mode, tagPrefix = tag, onSelect = { m ->
                    open = false
                    onPick(m)
                })
            }
        }
    }
}

/**
 * v1.10 (R3; the curve strips' Stroke kind menu): the three paint-mode segments as
 * [PaintModeSegments] draws them (48 x 40 dp, [PaintModeGlyph], tagged "$tag.<MODE>", known by
 * [paintModeName]), for a place where a mode can be refused: a mode with a [refusal] is greyed and
 * marked disabled, reads the refusal as its state, and a tap hands it to [onRefused] (v1.7: "Fill
 * and Both greyed with 1 or 2 points, a tap says why") instead of [onSelect].
 * (PaintModeSegments ignores a tap on a segment that is not enabled and says nothing; LeadRequest.)
 */
@Composable
internal fun PaintModeChoices(
    selected: PaintMode?,
    refusal: (PaintMode) -> String?,
    tag: String,
    onSelect: (PaintMode) -> Unit,
    onRefused: (String) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        for (mode in PaintMode.entries) {
            val on = mode == selected
            val refused = refusal(mode)
            val name = paintModeName(mode)
            Box(
                Modifier
                    .size(48.dp, 40.dp)
                    .background(if (on) BrushworkColors.AccentDim else Color.Transparent, shape)
                    .testTag("$tag.${mode.name}")
                    .iconLongPressLabel(name)
                    .clickable(role = Role.RadioButton) { if (refused != null) onRefused(refused) else onSelect(mode) }
                    .semantics {
                        contentDescription = name
                        this.selected = on
                        if (refused != null) {
                            stateDescription = refused
                            disabled()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PaintModeGlyph(mode, if (on) Color.White else BrushworkColors.OnChrome, Modifier.alpha(if (refused == null) 1f else 0.38f))
            }
        }
    }
}

/**
 * What [FillColorRow] (the Curve settings sheet) says of a reopened path's gradient fill it keeps
 * until a color is picked; v1.10: also the Object colors sheet's Fill tab state (R2).
 */
internal const val GRADIENT_KEPT = "Gradient (kept)"

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
