package com.brushwork.paint.ui.filters

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.ResetGap
import com.brushwork.paint.ui.common.ResetValueButton
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.V19Tags
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
    /**
     * A control finished its change of a key (a slider released, a chip chosen, an editor drag
     * ended...). v1.9 (R8): a session records it as one step; an adjustment, as one step
     * "Change <label>".
     */
    val onChangeFinished: (key: String) -> Unit = {},
    /** A − / + press ended (v1.9: a session keeps a run of them as one step, ended by another control). */
    val onNudgeFinished: (key: String) -> Unit = onChangeFinished,
    /** v1.9 (R8): a parameter's default here (a session: the drawing colour for a drawing-colour parameter). */
    private val defaultOf: (FilterParam) -> Any = { it.defaultValue() },
) {
    /** The current values (read when used: repeated button presses always see the latest). */
    val values: FilterValues get() = valuesOf()
    val histogram: IntArray? get() = histogramOf()
    val draggingPoint: String? get() = draggingPointOf()

    /**
     * v1.9 (R8): whether [p] holds its default ([FilterSessionMath.sameValue]); a missing value
     * counts as the default. The reset icon shows only when it does not.
     */
    fun isDefault(p: FilterParam): Boolean {
        // Fringes as the filter draws them (a v1.7 "order" shows as its colours).
        if (p is FilterParam.Fringes) return FringeColors.resolve(values, p).contentEquals(p.default)
        val d = defaultOf(p)
        return FilterSessionMath.sameValue(values.raw(p.key) ?: d, d)
    }
}

/** The host of a filter session's controls. */
internal fun FilterSession.paramHost(): ParamHost = ParamHost(
    { values }, ::update, ::resetParam, { histogram }, { draggingPoint }, pointsOnCanvas = true,
    onChangeFinished = ::commitStep,
    // A run of − / + taps stays one step: the next change of another control (or Undo) records it.
    onNudgeFinished = {},
    defaultOf = ::sessionDefault,
)

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
            is FilterParam.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
                ToggleRow(
                    param.label,
                    host.values.bool(param.key),
                    { if (enabled) { host.update(param.key, it); host.onChangeFinished(param.key) } },
                    Modifier.weight(1f),
                )
                ResetSlot(host, param, enabled)
            }
            is FilterParam.Choice -> {
                LabelRow(host, param, enabled)
                ChoiceChips(param.options, host.values.choice(param.key), { if (enabled) { host.update(param.key, it); host.onChangeFinished(param.key) } })
            }
            is FilterParam.Color -> ColorControl(host, param, enabled)
            is FilterParam.Point -> PointControl(host, param, enabled)
            is FilterParam.Curve -> {
                // v1.9: the label row's reset icon replaces the editor's own "Reset" button.
                LabelRow(host, param, enabled)
                CurveEditor(
                    points = host.values.curve(param.key),
                    onChange = { host.update(param.key, it) },
                    histogram = host.histogram,
                    enabled = enabled,
                    onChangeFinished = { host.onChangeFinished(param.key) },
                )
            }
            is FilterParam.Gradient -> {
                LabelRow(host, param, enabled)
                GradientEditor(
                    stops = host.values.gradient(param.key),
                    onChange = { host.update(param.key, it) },
                    defaultStops = param.default,
                    enabled = enabled,
                    onChangeFinished = { host.onChangeFinished(param.key) },
                )
            }
            is FilterParam.Text -> TextControl(host, param, enabled)
            is FilterParam.Seed -> SeedControl(host, param, enabled)
            is FilterParam.Fringes -> FringeControl(host, param, enabled)
        }
    }
}

@Composable
private fun ParamLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = modifier.padding(bottom = 4.dp))
}

/** A label over its control (chips, editors), with [p]'s reset icon at the row's far end ([ResetSlot]). */
@Composable
internal fun LabelRow(host: ParamHost, p: FilterParam, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ParamLabel(p.label, Modifier.weight(1f))
        ResetSlot(host, p, enabled)
    }
}

/**
 * v1.9 (R8): [p]'s reset icon ([ResetValueButton], tagged [V19Tags.FILTER_RESET_PREFIX] + its
 * key), [ResetGap] after the value; shown only while [p] differs from its default
 * ([ParamHost.isDefault]). A reset is one step.
 */
@Composable
internal fun ResetSlot(host: ParamHost, p: FilterParam, enabled: Boolean) {
    if (host.isDefault(p)) return
    Spacer(Modifier.width(ResetGap))
    ResetValueButton(p.label, { host.resetParam(p.key) }, Modifier.testTag(V19Tags.FILTER_RESET_PREFIX + p.key), enabled)
}

@Composable
private fun SliderControl(host: ParamHost, p: FilterParam.Slider, enabled: Boolean) {
    val v = host.values.float(p.key)
    // v1.6 §3.4: the slider and its −/+ use one increment (off: the parameter's own steps).
    val inc = LocalIncrements.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(Icons.Filled.Remove, "Decrease ${p.label}", enabled = enabled, onRelease = { host.onNudgeFinished(p.key) }) {
            host.update(p.key, ParamIncrements.nudge(p, host.values.float(p.key), -1, inc))
        }
        LabeledSlider(
            label = p.label,
            value = v,
            onValueChange = { host.update(p.key, SliderFormat.snap(p, it)) },
            onValueChangeFinished = { host.onChangeFinished(p.key) },
            valueRange = p.min..p.max,
            valueText = SliderFormat.format(p, v),
            enabled = enabled,
            // Tap the number to type it (snapped to the parameter's step like the slider).
            typing = SliderTyping(decimals = SliderFormat.decimals(p), suffix = ParamIncrements.suffixOf(p)),
            modifier = Modifier.weight(1f),
            incrementKind = ParamIncrements.kindOf(p),
            incrementKey = ParamIncrements.keyOf(p),
            onReset = if (host.isDefault(p)) null else ({ host.resetParam(p.key) }),
            resetTag = V19Tags.FILTER_RESET_PREFIX + p.key,
        )
        RepeatIconButton(Icons.Filled.Add, "Increase ${p.label}", enabled = enabled, onRelease = { host.onNudgeFinished(p.key) }) {
            host.update(p.key, ParamIncrements.nudge(p, host.values.float(p.key), 1, inc))
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
        // TalkBack names the swatch after its label ("Choose color of the static"), its color as
        // its state. Lower case, so neither the label's text nor the name is inside the other.
        ColorSwatch(
            color,
            modifier = Modifier.semantics {
                contentDescription = "Choose ${p.label.lowercase()}"
                stateDescription = ColorUtils.toHex(color)
            },
            size = 40.dp,
            onClick = { if (enabled) picking = true },
        )
        ResetSlot(host, p, enabled)
    }
    if (picking) {
        ColorPickerDialog(
            initial = color,
            onPick = { host.update(p.key, it); host.onChangeFinished(p.key) },
            onDismiss = { picking = false },
            title = p.label,
            showAlpha = true,
        )
    }
}

/** A point, dragged on the canvas (a session) or only shown; v1.9: its reset shows off the default, as every control's. */
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
        ResetSlot(host, p, enabled)
    }
}

/**
 * A text parameter. Each key changes it live; v1.9 (R8): leaving the field (a single line's Done
 * leaves it too) finishes the change, so a typed text is one step, never one per key.
 */
@Composable
private fun TextControl(host: ParamHost, p: FilterParam.Text, enabled: Boolean) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = host.values.text(p.key),
            onValueChange = { host.update(p.key, it) },
            label = { Text(p.label) },
            singleLine = !p.multiline,
            minLines = if (p.multiline) 3 else 1,
            maxLines = if (p.multiline) 6 else 1,
            enabled = enabled,
            keyboardOptions = if (p.multiline) KeyboardOptions.Default else KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f).onFocusChanged { state ->
                if (focused && !state.isFocused) host.onChangeFinished(p.key)
                focused = state.isFocused
            },
        )
        ResetSlot(host, p, enabled)
    }
}

@Composable
private fun SeedControl(host: ParamHost, p: FilterParam.Seed, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${host.values.seed(p.key)}", style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { host.update(p.key, Random.nextInt(1, 1_000_000)); host.onChangeFinished(p.key) }, enabled = enabled) {
            Icon(Icons.Filled.Shuffle, contentDescription = newSeedDescription(p.label))
        }
        ResetSlot(host, p, enabled)
    }
}

/**
 * What a screen reader calls a seed's shuffle button: "New" and the seed's [label] in lowercase,
 * with "random" added only when the label does not already start with it (v1.7's "Random seed"
 * reads "New random seed", never "New random random seed").
 */
internal fun newSeedDescription(label: String): String =
    if (label.startsWith("random", ignoreCase = true)) "New ${label.lowercase()}" else "New random ${label.lowercase()}"
