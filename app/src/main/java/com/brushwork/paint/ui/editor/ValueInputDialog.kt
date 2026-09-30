package com.brushwork.paint.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.EditorController
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * A number the user types: the formatted [initial] value comes pre-selected with the numeric
 * keyboard up, OK / the keyboard's Done applies it (clamped by [parse]), invalid text is refused
 * (the dialog stays open and says so). -/+ step the value ([step]) and the slider ([toFraction] /
 * [fromFraction]) sets it quickly; both only change the text until it is applied.
 */
@Composable
fun ValueInputDialog(
    title: String,
    label: String,
    initial: Float,
    format: (Float) -> String,
    parse: (String) -> Float?,
    step: (value: Float, up: Boolean) -> Float,
    toFraction: (Float) -> Float,
    fromFraction: (Float) -> Float,
    rangeText: String,
    suffix: String,
    onApply: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    fun selectedAll(s: String) = TextFieldValue(s, selection = TextRange(0, s.length))
    var field by remember { mutableStateOf(selectedAll(format(initial))) }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    /** The value shown right now (the last valid one while the text is being edited). */
    var current by remember { mutableStateOf(initial) }

    fun setValue(v: Float) {
        current = v
        field = selectedAll(format(v))
        error = false
    }

    val apply = {
        val v = parse(field.text)
        if (v == null) {
            error = true
        } else {
            onApply(v)
            onDismiss()
        }
    }

    BwDialog(title = title, onDismiss = onDismiss, confirmText = "OK", onConfirm = apply) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatIconButton(Icons.Filled.Remove, "Decrease $label") { setValue(step(parse(field.text) ?: current, false)) }
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    field = v
                    val parsed = parse(v.text)
                    error = false
                    if (parsed != null) current = parsed
                },
                label = { Text(label, maxLines = 1) },
                suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
                singleLine = true,
                isError = error,
                supportingText = { Text(if (error) "Type a number ($rangeText)" else rangeText) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            RepeatIconButton(Icons.Filled.Add, "Increase $label") { setValue(step(parse(field.text) ?: current, true)) }
        }
        Slider(
            value = toFraction(current).coerceIn(0f, 1f),
            onValueChange = { f -> setValue(fromFraction(f)) },
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics { contentDescription = "$label slider" },
        )
        Box(Modifier.height(2.dp))
        // Inside the dialog's own composition so the requester is attached when this runs.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/**
 * Typed brush size or opacity for the preset shown by the slider bar
 * ([EditorController.sliderToolId]); the result is saved with the preset.
 */
@Composable
fun BrushValueDialog(controller: EditorController, kind: SliderKind, onDismiss: () -> Unit) {
    // Fixed for the dialog's lifetime: the value is applied to the tool it was opened for.
    val toolId = remember { controller.sliderToolId }
    val preset = controller.presetFor(toolId) ?: return
    val apply: (Float) -> Unit = { v ->
        val store = BrushPresetStore.get(controller.appContext)
        store.edit(controller, toolId, persist = true) {
            if (kind == SliderKind.SIZE) it.copy(size = v) else it.copy(opacity = v)
        }
    }
    when (kind) {
        SliderKind.SIZE -> ValueInputDialog(
            title = "${toolId.label} size",
            label = "${toolId.label} size",
            initial = preset.size,
            format = { SliderMath.formatSize(it) },
            parse = SliderMath::parseSize,
            step = SliderMath::stepSize,
            toFraction = SliderMath::sizeToFraction,
            fromFraction = SliderMath::fractionToSize,
            rangeText = "0.5 – 1000 px",
            suffix = "px",
            onApply = apply,
            onDismiss = onDismiss,
        )
        SliderKind.OPACITY -> ValueInputDialog(
            title = "${toolId.label} opacity",
            label = "${toolId.label} opacity",
            initial = preset.opacity,
            format = { SliderMath.formatPercent(it).removeSuffix("%") },
            parse = SliderMath::parsePercent,
            step = SliderMath::stepPercent,
            toFraction = { it },
            fromFraction = { kotlin.math.round(it * 100f) / 100f },
            rangeText = "0 – 100 %",
            suffix = "%",
            onApply = apply,
            onDismiss = onDismiss,
        )
    }
}
