package com.brushwork.paint.ui.brush

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brushwork.paint.brush.BrushLimits
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Applies a change to the current preset; `persist` = false while a slider is being dragged. */
typealias PresetEdit = (persist: Boolean, transform: (BrushPreset) -> BrushPreset) -> Unit

private val LOG_SIZE_RANGE = ln(BrushLimits.MAX_SIZE / BrushLimits.MIN_SIZE)

/** Brush size (0.5..1000 px) -> slider position 0..1 (logarithmic). */
fun sizeToSlider(size: Float): Float = (ln(size.coerceIn(BrushLimits.MIN_SIZE, BrushLimits.MAX_SIZE) / BrushLimits.MIN_SIZE) / LOG_SIZE_RANGE).coerceIn(0f, 1f)

/** Slider position 0..1 -> brush size, rounded to a sensible step for its magnitude. */
fun sliderToSize(v: Float): Float {
    val s = BrushLimits.MIN_SIZE * exp(v.coerceIn(0f, 1f) * LOG_SIZE_RANGE)
    val r = when {
        s < 10f -> (s * 10f).roundToInt() / 10f
        s < 100f -> (s * 2f).roundToInt() / 2f
        else -> s.roundToInt().toFloat()
    }
    return r.coerceIn(BrushLimits.MIN_SIZE, BrushLimits.MAX_SIZE)
}

/** "12.5 px" / "300 px". */
fun formatSize(size: Float): String = Units.formatNumber(size.toDouble(), if (size < 10f) 1 else 0) + " px"

internal fun percent(v: Float): String = "${(v * 100f).roundToInt()}%"

/** Every setting of [preset] as used by [toolId]; controls irrelevant to the tool are hidden. */
@Composable
fun BrushSettings(toolId: ToolId, preset: BrushPreset, onEdit: PresetEdit, modifier: Modifier = Modifier) {
    val kind = StrokeKind.of(toolId, preset)
    val smudgeOrBlur = kind == StrokeKind.SMUDGE || kind == StrokeKind.BLUR
    val coverage = kind == StrokeKind.PAINT || kind == StrokeKind.ERASE
    val done = { onEdit(true) { it } }

    Column(modifier) {
        PanelCard {
            LabeledSlider(
                label = "Size",
                value = sizeToSlider(preset.size),
                onValueChange = { v -> onEdit(false) { it.copy(size = sliderToSize(v)) } },
                valueRange = 0f..1f,
                valueText = formatSize(preset.size),
                onValueChangeFinished = done,
            )
            NumberField(
                label = "Size",
                value = preset.size.toDouble(),
                onValueChange = { v -> onEdit(true) { it.copy(size = v.toFloat()) } },
                decimals = 1,
                suffix = "px",
                min = BrushLimits.MIN_SIZE.toDouble(),
                max = BrushLimits.MAX_SIZE.toDouble(),
                step = if (preset.size < 10f) 0.5 else 1.0,
            )
            if (smudgeOrBlur) {
                LabeledSlider(
                    label = "Strength",
                    value = preset.mixing,
                    onValueChange = { v -> onEdit(false) { it.copy(mixing = v) } },
                    valueRange = 0f..1f,
                    valueText = percent(preset.mixing),
                    onValueChangeFinished = done,
                )
            } else {
                LabeledSlider(
                    label = if (kind == StrokeKind.WATERCOLOR) "Opacity" else "Opacity (stroke)",
                    value = preset.opacity,
                    onValueChange = { v -> onEdit(false) { it.copy(opacity = v) } },
                    valueRange = 0f..1f,
                    valueText = percent(preset.opacity),
                    onValueChangeFinished = done,
                )
                LabeledSlider(
                    label = "Flow (per dab)",
                    value = preset.flow,
                    onValueChange = { v -> onEdit(false) { it.copy(flow = v) } },
                    valueRange = 0.01f..1f,
                    valueText = percent(preset.flow),
                    onValueChangeFinished = done,
                )
            }
            if (kind == StrokeKind.WATERCOLOR) {
                LabeledSlider(
                    label = "Color mixing",
                    value = preset.mixing,
                    onValueChange = { v -> onEdit(false) { it.copy(mixing = v) } },
                    valueRange = 0f..1f,
                    valueText = percent(preset.mixing),
                    onValueChangeFinished = done,
                )
            }
        }

        SectionHeader("Tip")
        PanelCard {
            LabeledSlider(
                label = "Hardness",
                value = preset.hardness,
                onValueChange = { v -> onEdit(false) { it.copy(hardness = v) } },
                valueRange = 0f..1f,
                valueText = percent(preset.hardness),
                onValueChangeFinished = done,
            )
            LabeledSlider(
                label = "Spacing",
                value = preset.spacing,
                onValueChange = { v -> onEdit(false) { it.copy(spacing = (v * 100f).roundToInt() / 100f) } },
                valueRange = BrushLimits.MIN_SPACING..1.5f,
                valueText = percent(preset.spacing),
                onValueChangeFinished = done,
            )
            // Tips are symmetric, so 0..180 degrees covers every orientation.
            val angle = if (preset.angle > 180f) preset.angle - 180f else preset.angle
            LabeledSlider(
                label = "Angle",
                value = angle,
                onValueChange = { v -> onEdit(false) { it.copy(angle = v.roundToInt().toFloat()) } },
                valueRange = 0f..180f,
                valueText = "${angle.roundToInt()}°",
                onValueChangeFinished = done,
            )
            LabeledSlider(
                label = "Roundness",
                value = preset.roundness,
                onValueChange = { v -> onEdit(false) { it.copy(roundness = v) } },
                valueRange = BrushLimits.MIN_ROUNDNESS..1f,
                valueText = percent(preset.roundness),
                onValueChangeFinished = done,
            )
            if (!smudgeOrBlur) {
                LabeledSlider(
                    label = "Scatter",
                    value = preset.scatter,
                    onValueChange = { v -> onEdit(false) { it.copy(scatter = v) } },
                    valueRange = 0f..2f,
                    valueText = percent(preset.scatter),
                    onValueChangeFinished = done,
                )
            }
            if (coverage) {
                LabeledSlider(
                    label = "Grain (paper texture)",
                    value = preset.grain,
                    onValueChange = { v -> onEdit(false) { it.copy(grain = v) } },
                    valueRange = 0f..1f,
                    valueText = percent(preset.grain),
                    onValueChangeFinished = done,
                )
                ToggleRow(
                    label = "Anti-aliasing",
                    checked = preset.antiAlias,
                    onCheckedChange = { on -> onEdit(true) { it.copy(antiAlias = on) } },
                    description = if (preset.antiAlias) "Smooth edges" else "Hard pixel edges (pixel art)",
                )
            }
        }

        SectionHeader("Pressure & taper")
        PanelCard {
            ToggleRow(
                label = "Pressure changes size",
                checked = preset.pressureSize,
                onCheckedChange = { on -> onEdit(true) { it.copy(pressureSize = on) } },
            )
            LabeledSlider(
                label = "Minimum size",
                value = preset.minSizeRatio,
                onValueChange = { v -> onEdit(false) { it.copy(minSizeRatio = v) } },
                valueRange = 0f..1f,
                valueText = percent(preset.minSizeRatio),
                onValueChangeFinished = done,
                enabled = preset.pressureSize,
            )
            ToggleRow(
                label = if (smudgeOrBlur) "Pressure changes strength" else "Pressure changes opacity",
                checked = preset.pressureOpacity,
                onCheckedChange = { on -> onEdit(true) { it.copy(pressureOpacity = on) } },
            )
            if (!smudgeOrBlur) {
                LabeledSlider(
                    label = "Taper start (finger)",
                    value = preset.taperStart,
                    onValueChange = { v -> onEdit(false) { it.copy(taperStart = v.roundToInt().toFloat()) } },
                    valueRange = 0f..MAX_TAPER_UI,
                    valueText = if (preset.taperStart <= 0f) "Off" else formatSize(preset.taperStart),
                    onValueChangeFinished = done,
                )
                LabeledSlider(
                    label = "Taper end (finger)",
                    value = preset.taperEnd,
                    onValueChange = { v -> onEdit(false) { it.copy(taperEnd = v.roundToInt().toFloat()) } },
                    valueRange = 0f..MAX_TAPER_UI,
                    valueText = if (preset.taperEnd <= 0f) "Off" else formatSize(preset.taperEnd),
                    onValueChangeFinished = done,
                )
            }
        }
    }
}

private const val MAX_TAPER_UI = 600f
