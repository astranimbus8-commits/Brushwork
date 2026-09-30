package com.brushwork.paint.ui.brush

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.brush.BrushLimits
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import kotlin.math.roundToInt

/** Applies a change to the current preset; `persist` = false while a slider is being dragged. */
typealias PresetEdit = (persist: Boolean, transform: (BrushPreset) -> BrushPreset) -> Unit

/** "12.5 px" / "300 px". */
fun formatSize(size: Float): String = Units.formatNumber(size.toDouble(), if (size < 10f) 1 else 0) + " px"

internal fun percent(v: Float): String = "${(v * 100f).roundToInt()}%"

/**
 * The most used settings, typed or dragged: size (logarithmic slider, -/+ steps) and opacity
 * (strength for smudge and blur). Shown at the top of the brush panel, above the presets.
 */
@Composable
fun BrushCoreSettings(toolId: ToolId, preset: BrushPreset, onEdit: PresetEdit, modifier: Modifier = Modifier) {
    val kind = StrokeKind.of(toolId, preset)
    val done = { onEdit(true) { it } }
    Column(modifier) {
        BrushSizeField(preset.size, { v -> onEdit(false) { it.copy(size = v) } }, done)
        if (kind == StrokeKind.SMUDGE || kind == StrokeKind.BLUR) {
            PercentField("Strength", preset.mixing, { v -> onEdit(false) { it.copy(mixing = v) } }, done)
        } else {
            PercentField(
                if (kind == StrokeKind.WATERCOLOR) "Opacity" else "Opacity (stroke)",
                preset.opacity,
                { v -> onEdit(false) { it.copy(opacity = v) } },
                done,
            )
        }
    }
}

/** Brush diameter field: typed, dragged on its logarithmic slider or stepped; [onDone] once a change is complete. */
@Composable
fun BrushSizeField(size: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, label: String = "Size") {
    NumberField(
        label = label,
        value = size.toDouble(),
        onValueChange = { v -> onChange(v.toFloat()) },
        modifier = modifier.fillMaxWidth(),
        decimals = 1,
        suffix = "px",
        min = BrushLimits.MIN_SIZE.toDouble(),
        max = BrushLimits.MAX_SIZE.toDouble(),
        step = if (size < 10f) 0.5 else 1.0,
        logSlider = true,
        onValueChangeFinished = onDone,
    )
}

/**
 * Every other setting of [preset] as used by [toolId] (see [BrushCoreSettings] for size and
 * opacity); controls irrelevant to the tool are hidden.
 */
@Composable
fun BrushSettings(toolId: ToolId, preset: BrushPreset, onEdit: PresetEdit, modifier: Modifier = Modifier) {
    val kind = StrokeKind.of(toolId, preset)
    val smudgeOrBlur = kind == StrokeKind.SMUDGE || kind == StrokeKind.BLUR
    val coverage = kind == StrokeKind.PAINT || kind == StrokeKind.ERASE
    val done = { onEdit(true) { it } }

    Column(modifier) {
        // Smudge and blur have no flow; their strength is with the size, in BrushCoreSettings.
        if (!smudgeOrBlur) PanelCard {
            LabeledSlider(
                label = "Flow (per dab)",
                value = preset.flow,
                onValueChange = { v -> onEdit(false) { it.copy(flow = v) } },
                valueRange = 0.01f..1f,
                valueText = percent(preset.flow),
                typing = SliderTyping.Percent,
                onValueChangeFinished = done,
            )
            if (kind == StrokeKind.WATERCOLOR) {
                LabeledSlider(
                    label = "Color mixing",
                    value = preset.mixing,
                    onValueChange = { v -> onEdit(false) { it.copy(mixing = v) } },
                    valueRange = 0f..1f,
                    valueText = percent(preset.mixing),
                    typing = SliderTyping.Percent,
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
                typing = SliderTyping.Percent,
                onValueChangeFinished = done,
            )
            LabeledSlider(
                label = "Spacing",
                value = preset.spacing,
                onValueChange = { v -> onEdit(false) { it.copy(spacing = (v * 100f).roundToInt() / 100f) } },
                valueRange = BrushLimits.MIN_SPACING..1.5f,
                valueText = percent(preset.spacing),
                typing = SliderTyping.Percent,
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
                typing = SliderTyping(suffix = "°"),
                onValueChangeFinished = done,
            )
            LabeledSlider(
                label = "Roundness",
                value = preset.roundness,
                onValueChange = { v -> onEdit(false) { it.copy(roundness = v) } },
                valueRange = BrushLimits.MIN_ROUNDNESS..1f,
                valueText = percent(preset.roundness),
                typing = SliderTyping.Percent,
                onValueChangeFinished = done,
            )
            if (!smudgeOrBlur) {
                LabeledSlider(
                    label = "Scatter",
                    value = preset.scatter,
                    onValueChange = { v -> onEdit(false) { it.copy(scatter = v) } },
                    valueRange = 0f..2f,
                    valueText = percent(preset.scatter),
                    typing = SliderTyping.Percent,
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
                    typing = SliderTyping.Percent,
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
                typing = SliderTyping.Percent,
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
                    typing = SliderTyping(suffix = "px"),
                    onValueChangeFinished = done,
                )
                LabeledSlider(
                    label = "Taper end (finger)",
                    value = preset.taperEnd,
                    onValueChange = { v -> onEdit(false) { it.copy(taperEnd = v.roundToInt().toFloat()) } },
                    valueRange = 0f..MAX_TAPER_UI,
                    valueText = if (preset.taperEnd <= 0f) "Off" else formatSize(preset.taperEnd),
                    typing = SliderTyping(suffix = "px"),
                    onValueChangeFinished = done,
                )
            }
        }
    }
}

private const val MAX_TAPER_UI = 600f

/** A 0..1 setting shown and typed as a whole percentage, with a slider beside the number. */
@Composable
internal fun PercentField(label: String, fraction: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    NumberField(
        label = label,
        value = (fraction * 100f).roundToInt().toDouble(),
        onValueChange = { v -> onChange((v / 100.0).toFloat().coerceIn(0f, 1f)) },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        decimals = 0,
        suffix = "%",
        min = 0.0,
        max = 100.0,
        onValueChangeFinished = onDone,
    )
}
