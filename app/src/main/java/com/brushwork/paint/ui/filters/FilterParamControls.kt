package com.brushwork.paint.ui.filters

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * What a parameter control reads and changes: a filter session's values, or (v1.5) an adjustment
 * layer's effect values edited in the Masks tool's Adjust sheet. [pointsOnCanvas]: point
 * parameters are dragged on the canvas (a session) or only shown and reset.
 */
internal class ParamHost(
    private val valuesOf: () -> FilterValues,
    val update: (key: String, value: Any) -> Unit,
    val resetParam: (key: String) -> Unit,
    private val histogramOf: () -> IntArray? = { null },
    private val draggingPointOf: () -> String? = { null },
    val pointsOnCanvas: Boolean = false,
    /** A slider or editor drag ended (an adjustment records its step later; a session needs nothing). */
    val onChangeFinished: () -> Unit = {},
) {
    /** The current values (read when used: repeated button presses always see the latest). */
    val values: FilterValues get() = valuesOf()
    val histogram: IntArray? get() = histogramOf()
    val draggingPoint: String? get() = draggingPointOf()
}

/** The host of a filter session's controls. */
internal fun FilterSession.paramHost(): ParamHost =
    ParamHost({ values }, ::update, ::resetParam, { histogram }, { draggingPoint }, pointsOnCanvas = true)

/** The control generated for one filter parameter. */
@Composable
internal fun FilterParamControl(session: FilterSession, param: FilterParam, enabled: Boolean, modifier: Modifier = Modifier) =
    FilterParamControl(remember(session) { session.paramHost() }, param, enabled, modifier)

/** The control generated for one filter parameter, on any [host] (v1.5: without a session too). */
@Composable
internal fun FilterParamControl(host: ParamHost, param: FilterParam, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (param) {
            is FilterParam.Slider -> SliderControl(host, param, enabled)
            // A switch or a chip is a whole change at once (v1.6: an adjustment refines at once).
            is FilterParam.Toggle -> ToggleRow(param.label, host.values.bool(param.key), { if (enabled) { host.update(param.key, it); host.onChangeFinished() } })
            is FilterParam.Choice -> {
                ParamLabel(param.label)
                ChoiceChips(param.options, host.values.choice(param.key), { if (enabled) { host.update(param.key, it); host.onChangeFinished() } })
            }
            is FilterParam.Color -> ColorControl(host, param, enabled)
            is FilterParam.Point -> PointControl(host, param, enabled)
            is FilterParam.Curve -> {
                ParamLabel(param.label)
                CurveEditor(
                    points = host.values.curve(param.key),
                    onChange = { host.update(param.key, it) },
                    histogram = host.histogram,
                    enabled = enabled,
                    onReset = { host.resetParam(param.key) },
                    onChangeFinished = host.onChangeFinished,
                )
            }
            is FilterParam.Gradient -> {
                ParamLabel(param.label)
                GradientEditor(
                    stops = host.values.gradient(param.key),
                    onChange = { host.update(param.key, it) },
                    defaultStops = param.default,
                    enabled = enabled,
                    onChangeFinished = host.onChangeFinished,
                )
            }
            is FilterParam.Text -> OutlinedTextField(
                value = host.values.text(param.key),
                onValueChange = { host.update(param.key, it) },
                label = { Text(param.label) },
                singleLine = !param.multiline,
                minLines = if (param.multiline) 3 else 1,
                maxLines = if (param.multiline) 6 else 1,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            is FilterParam.Seed -> SeedControl(host, param, enabled)
        }
    }
}

@Composable
private fun ParamLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun SliderControl(host: ParamHost, p: FilterParam.Slider, enabled: Boolean) {
    val v = host.values.float(p.key)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(Icons.Filled.Remove, "Decrease ${p.label}", enabled = enabled, onRelease = host.onChangeFinished) {
            host.update(p.key, SliderFormat.nudge(p, host.values.float(p.key), -1))
        }
        LabeledSlider(
            label = p.label,
            value = v,
            onValueChange = { host.update(p.key, SliderFormat.snap(p, it)) },
            onValueChangeFinished = host.onChangeFinished,
            valueRange = p.min..p.max,
            valueText = SliderFormat.format(p, v),
            enabled = enabled,
            // Tap the number to type it (snapped to the parameter's step like the slider).
            typing = SliderTyping(decimals = SliderFormat.decimals(p), suffix = if (p.pixels) "px" else p.suffix),
            modifier = Modifier.weight(1f),
        )
        RepeatIconButton(Icons.Filled.Add, "Increase ${p.label}", enabled = enabled, onRelease = host.onChangeFinished) {
            host.update(p.key, SliderFormat.nudge(p, host.values.float(p.key), 1))
        }
    }
}

@Composable
private fun ColorControl(host: ParamHost, p: FilterParam.Color, enabled: Boolean) {
    var picking by remember { mutableStateOf(false) }
    val color = host.values.color(p.key)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.bodyMedium)
            Text(ColorUtils.toHex(color), style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        }
        ColorSwatch(color, size = 40.dp, onClick = { if (enabled) picking = true })
    }
    if (picking) {
        ColorPickerDialog(
            initial = color,
            onPick = { host.update(p.key, it); host.onChangeFinished() },
            onDismiss = { picking = false },
            title = p.label,
            showAlpha = true,
        )
    }
}

@Composable
private fun PointControl(host: ParamHost, p: FilterParam.Point, enabled: Boolean) {
    val v = host.values.point(p.key)
    val dragging = host.draggingPoint == p.key
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.bodyMedium, color = if (dragging) BrushworkColors.Accent else BrushworkColors.OnChrome)
            Text(
                (if (host.pointsOnCanvas) "Drag on the canvas to move ${p.label.lowercase()}  ·  " else "") +
                    "X ${(v[0] * 100).roundToInt()}%  Y ${(v[1] * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
            )
        }
        IconButton(onClick = { host.resetParam(p.key) }, enabled = enabled) {
            Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Reset ${p.label}")
        }
    }
}

@Composable
private fun SeedControl(host: ParamHost, p: FilterParam.Seed, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${host.values.seed(p.key)}", style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { host.update(p.key, Random.nextInt(1, 1_000_000)); host.onChangeFinished() }, enabled = enabled) {
            Icon(Icons.Filled.Shuffle, contentDescription = "New random ${p.label.lowercase()}")
        }
    }
}
