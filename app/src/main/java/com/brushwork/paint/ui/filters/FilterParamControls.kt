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

/** The control generated for one filter parameter. */
@Composable
internal fun FilterParamControl(session: FilterSession, param: FilterParam, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (param) {
            is FilterParam.Slider -> SliderControl(session, param, enabled)
            is FilterParam.Toggle -> ToggleRow(param.label, session.values.bool(param.key), { if (enabled) session.update(param.key, it) })
            is FilterParam.Choice -> {
                ParamLabel(param.label)
                ChoiceChips(param.options, session.values.choice(param.key), { if (enabled) session.update(param.key, it) })
            }
            is FilterParam.Color -> ColorControl(session, param, enabled)
            is FilterParam.Point -> PointControl(session, param, enabled)
            is FilterParam.Curve -> {
                ParamLabel(param.label)
                CurveEditor(
                    points = session.values.curve(param.key),
                    onChange = { session.update(param.key, it) },
                    histogram = session.histogram,
                    enabled = enabled,
                    onReset = { session.resetParam(param.key) },
                )
            }
            is FilterParam.Gradient -> {
                ParamLabel(param.label)
                GradientEditor(
                    stops = session.values.gradient(param.key),
                    onChange = { session.update(param.key, it) },
                    defaultStops = param.default,
                    enabled = enabled,
                )
            }
            is FilterParam.Text -> OutlinedTextField(
                value = session.values.text(param.key),
                onValueChange = { session.update(param.key, it) },
                label = { Text(param.label) },
                singleLine = !param.multiline,
                minLines = if (param.multiline) 3 else 1,
                maxLines = if (param.multiline) 6 else 1,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            is FilterParam.Seed -> SeedControl(session, param, enabled)
        }
    }
}

@Composable
private fun ParamLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun SliderControl(session: FilterSession, p: FilterParam.Slider, enabled: Boolean) {
    val v = session.values.float(p.key)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(Icons.Filled.Remove, "Decrease ${p.label}", enabled = enabled) {
            session.update(p.key, SliderFormat.nudge(p, session.values.float(p.key), -1))
        }
        LabeledSlider(
            label = p.label,
            value = v,
            onValueChange = { session.update(p.key, SliderFormat.snap(p, it)) },
            valueRange = p.min..p.max,
            valueText = SliderFormat.format(p, v),
            enabled = enabled,
            // Tap the number to type it (snapped to the parameter's step like the slider).
            typing = SliderTyping(decimals = SliderFormat.decimals(p), suffix = if (p.pixels) "px" else p.suffix),
            modifier = Modifier.weight(1f),
        )
        RepeatIconButton(Icons.Filled.Add, "Increase ${p.label}", enabled = enabled) {
            session.update(p.key, SliderFormat.nudge(p, session.values.float(p.key), 1))
        }
    }
}

@Composable
private fun ColorControl(session: FilterSession, p: FilterParam.Color, enabled: Boolean) {
    var picking by remember { mutableStateOf(false) }
    val color = session.values.color(p.key)
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
            onPick = { session.update(p.key, it) },
            onDismiss = { picking = false },
            title = p.label,
            showAlpha = true,
        )
    }
}

@Composable
private fun PointControl(session: FilterSession, p: FilterParam.Point, enabled: Boolean) {
    val v = session.values.point(p.key)
    val dragging = session.draggingPoint == p.key
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.bodyMedium, color = if (dragging) BrushworkColors.Accent else BrushworkColors.OnChrome)
            Text(
                "Drag on the canvas to move ${p.label.lowercase()}  ·  X ${(v[0] * 100).roundToInt()}%  Y ${(v[1] * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
            )
        }
        IconButton(onClick = { session.resetParam(p.key) }, enabled = enabled) {
            Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Reset ${p.label}")
        }
    }
}

@Composable
private fun SeedControl(session: FilterSession, p: FilterParam.Seed, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${session.values.seed(p.key)}", style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { session.update(p.key, Random.nextInt(1, 1_000_000)) }, enabled = enabled) {
            Icon(Icons.Filled.Shuffle, contentDescription = "New random ${p.label.lowercase()}")
        }
    }
}
