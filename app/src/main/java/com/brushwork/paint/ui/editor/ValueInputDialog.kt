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
import com.brushwork.paint.core.Expressions
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.ExpressionReadout
import com.brushwork.paint.ui.common.IncrementStepping
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.OperatorKeys
import com.brushwork.paint.ui.common.OperatorText
import com.brushwork.paint.ui.common.Readout
import com.brushwork.paint.ui.common.ReadoutText
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.stepFor
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * A number the user types: the formatted [initial] value comes pre-selected with the numeric
 * keyboard up, OK / the keyboard's Done applies it (clamped by [parse]), invalid text is refused
 * (the dialog stays open and says so). -/+ step the value ([step]) and the slider ([toFraction] /
 * [fromFraction]) sets it quickly; both only change the text until it is applied.
 *
 * v1.6 increments (§3.4): with [incrementKind] (or a custom step under [incrementKey]) and
 * increments on, -/+ move to the next multiples of that step and the slider lands on them;
 * [incrementScale] is the step's unit per value unit (100 for a 0..1 value typed as %, px per
 * unit for a length typed in mm). The typed value itself is applied exactly as typed.
 *
 * v1.7 (I13, design §3.15): expressions are typed like numbers ([parse] reads them); relative
 * text ("*2", "/2") applies to the value the dialog opened with AS SHOWN (`format(initial)`: an
 * opacity held as 0..1 shows, and [parse] reads, percents). With [onApplyText] the dialog hands
 * the valid text over as typed (not resolved) instead of calling [onApply]: a field showing
 * "Mixed" applies a relative value to each of its values (`MixedEdit.typed`).
 *
 * v1.7 (item 15's UI, area I): the operator keys ([OperatorKeys]) sit under the field, and the
 * live readout under it shows what the text gives ("= 150 px", the value [parse] reads, in
 * [suffix]) or the expression's error ("Can't divide by 0"); OK and Done do nothing while there
 * is an error. [relativeReadout] false shows no value for relative text (only its error): a
 * dialog whose [onApplyText] applies relative text to several values ("Mixed") can't show one.
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
    incrementKind: IncrementKind? = null,
    incrementKey: String? = null,
    incrementScale: Float = 1f,
    onApplyText: ((String) -> Unit)? = null,
    relativeReadout: Boolean = onApplyText == null,
) {
    fun selectedAll(s: String) = TextFieldValue(s, selection = TextRange(0, s.length))
    // The value relative text applies to: the number shown when the dialog opened.
    val relativeBase = remember { relativeBaseOf(format(initial), initial) }
    /** [parse] of [t], relative text resolved against [relativeBase] first. */
    fun parseText(t: String): Float? = parse(Expressions.resolveRelative(t, relativeBase) ?: t)
    var field by remember { mutableStateOf(selectedAll(format(initial))) }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    /** The value shown right now (the last valid one while the text is being edited). */
    var current by remember { mutableStateOf(initial) }
    // The increment in value units (null: the v1.5 steps).
    val incStep = LocalIncrements.current?.stepFor(incrementKind, incrementKey)
        ?.let { it.toDouble() / incrementScale.toDouble() }
        ?.takeIf { IncrementStepping.valid(it) }

    fun setValue(v: Float) {
        current = v
        field = selectedAll(format(v))
        error = false
    }

    /** -/+: the next multiple of the increment (kept within what [parse] accepts), else [step]. */
    fun stepped(v: Float, up: Boolean): Float {
        if (incStep == null) return step(v, up)
        val r = IncrementStepping.stepBy(v.toDouble(), if (up) 1 else -1, incStep).toFloat()
        return parse(format(r)) ?: r
    }

    /** The slider: on the increment's multiples (the ends stay reachable) while increments are on. */
    fun fromSlider(f: Float): Float {
        val v = fromFraction(f)
        if (incStep == null) return v
        val a = fromFraction(0f).toDouble()
        val b = fromFraction(1f).toDouble()
        return IncrementStepping.snapSlider(v.toDouble(), incStep, minOf(a, b), maxOf(a, b)).toFloat()
    }

    // The live readout (null: none, as in v1.6, for empty text and plain numbers).
    val readout = ExpressionReadout.of(field.text, relativeBase.toDouble()) { t -> parseText(t)?.toDouble() }
        ?.takeUnless { it is Readout.Value && !relativeReadout && Expressions.isRelative(field.text) }
    val blocked = ExpressionReadout.blocks(readout)

    val apply = {
        val v = parseText(field.text)
        if (blocked) {
            // The readout already says why: nothing applies, the dialog stays.
        } else if (v == null) {
            error = true
        } else {
            val asText = onApplyText
            if (asText != null) asText(field.text) else onApply(v)
            onDismiss()
        }
    }

    BwDialog(title = title, onDismiss = onDismiss, confirmText = "OK", onConfirm = apply, confirmEnabled = !blocked) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatIconButton(Icons.Filled.Remove, "Decrease $label") { setValue(stepped(parseText(field.text) ?: current, false)) }
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    field = v
                    val parsed = parseText(v.text)
                    error = false
                    if (parsed != null) current = parsed
                },
                label = { Text(label, maxLines = 1) },
                suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
                singleLine = true,
                isError = error || blocked,
                supportingText = {
                    if (readout != null) {
                        ReadoutText(ExpressionReadout.text(readout, { format(it.toFloat()) }, suffix), blocked)
                    } else {
                        Text(if (error) "Type a number ($rangeText)" else rangeText)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            RepeatIconButton(Icons.Filled.Add, "Increase $label") { setValue(stepped(parseText(field.text) ?: current, true)) }
        }
        // The keypad has no operators (V17): + − × ÷ ( ) under the field.
        OperatorKeys(
            onKey = { key ->
                field = OperatorText.insert(field, key)
                error = false
                parseText(field.text)?.let { current = it }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp),
        )
        Slider(
            value = toFraction(current).coerceIn(0f, 1f),
            onValueChange = { f -> setValue(fromSlider(f)) },
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics { contentDescription = "$label slider" },
        )
        Box(Modifier.height(2.dp))
        // Inside the dialog's own composition so the requester is attached when this runs.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/**
 * v1.7: the number [shown] for a value (`format(initial)`, read as an expression would read it:
 * "60", "12,5 px"), or [initial] when it shows no number. Relative text in [ValueInputDialog]
 * applies to it, in the unit the dialog's `parse` reads.
 */
internal fun relativeBaseOf(shown: String, initial: Float): Float =
    (Expressions.evaluate(shown) as? Expressions.Result.Value)?.value?.toFloat()?.takeIf { it.isFinite() } ?: initial

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
            incrementKind = IncrementKind.SIZE,
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
            incrementKind = IncrementKind.PERCENT,
            incrementScale = 100f,
        )
    }
}
