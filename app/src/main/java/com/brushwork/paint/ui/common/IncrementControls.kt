package com.brushwork.paint.ui.common

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Expressions
import com.brushwork.paint.core.Units
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.IncrementSettings
import com.brushwork.paint.snap.Increments
import com.brushwork.paint.ui.theme.BrushworkColors

/*
 * The increments UI (v1.6 §3.4; area G): the Step popup (a long-press on any number or slider
 * value), the Increments sheet (More › Increments…, `EditorPanel.INCREMENTS`) and its Settings
 * section. The steps themselves live in `controller.increments` (`snap/Increments`), reached by
 * the shared controls through [LocalIncrements]: outside the editor it is null, and nothing steps
 * or offers a popup there.
 */

/**
 * The step [kind] (or, without a kind, the custom step of control [key]) moves by right now, in
 * that control's shown unit; null while increments are off or the control has no step. Reads
 * Compose state: a composable calling it follows every change of the steps.
 */
internal fun Increments.stepFor(kind: IncrementKind?, key: String?): Float? = when {
    kind != null -> step(kind)
    key != null -> customStep(key)
    else -> null
}

/** What a Step popup edits: the step of [kind], or the custom step [key] of the control [name] shown in [suffix]. */
internal class StepTarget(val kind: IncrementKind?, val key: String?, name: String? = null, suffix: String? = null) {
    /** The control's name in the popup ("Length", "Exposure"). */
    val name: String = name?.takeIf { it.isNotBlank() } ?: kind?.label ?: key?.let(IncrementStepping::nameOfKey).orEmpty()

    /** The step's unit ("px", "%", "°", "EV"; "" when it has none). */
    val suffix: String = kind?.suffix ?: suffix ?: key?.let(IncrementStepping::suffixOfKey).orEmpty()

    val title: String get() = IncrementStepping.popupTitle(kind, key, name)

    /** The long-press action's label (TalkBack, tests). */
    val actionLabel: String get() = title
}

/** The steps as the "#" toast and the sheet summarize them: "10 px · 10 % · 15°". */
internal fun IncrementSettings.summary(): String =
    "${IncrementStepping.format(lengthPx)} px · ${IncrementStepping.format(scalePercent)} % · ${IncrementStepping.format(angleDeg)}°"

/**
 * Long-press on a number or slider value opens the Step popup for [kind] ("Step for angles:
 * [15] ° · Use increments"), or for the custom step of control [key] when [kind] is null (the
 * popup is then titled after the key: "Exposure|EV" → "Step for Exposure", "mask.feather" →
 * "Step for Feather"). The long-press also is a semantics action, for screen readers. The
 * touch that opened the popup goes no further (the control under it doesn't act on release).
 * Nothing outside the editor ([LocalIncrements] null) or without a kind and a key.
 */
fun Modifier.stepOnLongPress(kind: IncrementKind?, key: String? = null): Modifier =
    if (kind == null && key == null) this else stepOnLongPressFor(StepTarget(kind, key))

/**
 * Set while a finger held on a control is opening its Step popup (from the long-press until that
 * finger lifts). A text field reads it to refuse focus meanwhile ([NumberField]): its own
 * long-press (select a word, focus, keyboard) must not happen behind the popup.
 */
internal class StepHold {
    var active: Boolean = false
}

/**
 * [stepOnLongPress] with the control's own name and unit for the popup. [enabled] is read when a
 * touch starts (the touch handler stays attached when it changes, so a change never cuts a
 * gesture short); [hold] is told while a held finger opens the popup.
 */
internal fun Modifier.stepOnLongPressFor(target: StepTarget, enabled: Boolean = true, hold: StepHold? = null): Modifier = composed {
    val inc = LocalIncrements.current
    if (inc == null || (target.kind == null && target.key == null)) return@composed Modifier
    val host = rememberStepPopupHost()
    val latest by rememberUpdatedState(target)
    val on by rememberUpdatedState(enabled)
    Modifier
        .semantics { if (enabled) onLongClick(label = target.actionLabel) { host.open(inc, latest); true } }
        .longPressInitialPass(isEnabled = { on }, onHold = { hold?.active = it }) { host.open(inc, latest) }
}

/**
 * Part of the system long-press time after which a held finger opens the Step popup: a little
 * before the controls under it (a text field's own long-press) act on the same touch, so the
 * popup always wins.
 */
internal const val STEP_LONG_PRESS_FRACTION = 0.85f

/**
 * Calls [onLongPress] when a finger rests here (within the touch slop) for
 * [STEP_LONG_PRESS_FRACTION] of the long-press time, while [isEnabled] (read when the touch
 * starts). It watches the touch in the Initial pass and, once it fired, consumes the rest of that
 * touch, so a clickable, slider or text field under it never acts on the move or release;
 * [onHold] is true from the long-press until that touch ends.
 */
internal fun Modifier.longPressInitialPass(
    isEnabled: () -> Boolean = { true },
    onHold: (Boolean) -> Unit = {},
    onLongPress: () -> Unit,
): Modifier = composed {
    val latest by rememberUpdatedState(onLongPress)
    val enabled by rememberUpdatedState(isEnabled)
    val held by rememberUpdatedState(onHold)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!enabled()) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val timeout = (viewConfiguration.longPressTimeoutMillis * STEP_LONG_PRESS_FRACTION).toLong().coerceAtLeast(1L)
            val fired = try {
                withTimeout(timeout) {
                    var lifted = false
                    while (!lifted) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        lifted = ch == null || !ch.pressed || ch.isConsumed ||
                            (ch.position - down.position).getDistance() > slop || ev.changes.size > 1
                    }
                    false
                }
            } catch (e: PointerEventTimeoutCancellationException) {
                true
            }
            if (!fired) return@awaitEachGesture
            held(true)
            try {
                latest()
                // The rest of this touch belongs to the popup: nothing under it acts on the release.
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
                }
            } finally {
                held(false)
            }
        }
    }
}

/**
 * Opens the Step popup in a window of its own (a modifier can't emit UI), composed as a child of
 * the control's composition (theme and locals carry over). One popup per host; it closes when the
 * host leaves the composition.
 */
internal class StepPopupHost(private val view: View, private val parentContext: CompositionContext) {
    private var dialog: ComponentDialog? = null

    val isOpen: Boolean get() = dialog?.isShowing == true

    fun open(inc: Increments, target: StepTarget) {
        close()
        val d = ComponentDialog(view.context)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = ComposeView(view.context).apply {
            setParentCompositionContext(parentContext)
            setContent { StepPopupPanel(inc, target, onDismiss = { d.dismiss() }) }
        }
        d.setContentView(content)
        d.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        d.setCanceledOnTouchOutside(true)
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    fun close() {
        val d = dialog ?: return
        dialog = null
        runCatching { d.dismiss() }
    }
}

/** A [StepPopupHost] for this place in the composition (closed when it leaves). */
@Composable
internal fun rememberStepPopupHost(): StepPopupHost {
    val view = LocalView.current
    val parent = rememberCompositionContext()
    val host = remember(view, parent) { StepPopupHost(view, parent) }
    DisposableEffect(host) { onDispose { host.close() } }
    return host
}

/**
 * The Step popup's panel: "Step for angles", the step (typed: OK / Done applies it, exactly as
 * typed), the master switch, and for a control's own step "No step" (removes it). A step can't
 * be 0 or negative; a kind's step has an upper bound ([IncrementSettings.MAX_STEPS]).
 */
@Composable
internal fun StepPopupPanel(inc: Increments, target: StepTarget, onDismiss: () -> Unit) {
    val state = inc.state
    val current: Float? = if (target.kind != null) state.step(target.kind) else target.key?.let { state.custom[it] }
    fun selectedAll(s: String) = TextFieldValue(s, selection = TextRange(0, s.length))
    var field by remember { mutableStateOf(selectedAll(current?.let(IncrementStepping::format).orEmpty())) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val max = target.kind?.let { IncrementSettings.MAX_STEPS.getValue(it) } ?: IncrementSettings.MAX_CUSTOM_STEP

    /** The step [text] gives (unchecked against the range), relative text applied to the current step. */
    fun read(text: String): Float? {
        // v1.7 (I13): "*2" / "/2" apply to the current step.
        val typed = current?.let { Expressions.resolveRelative(text, it) } ?: text
        return Units.parse(typed)?.toFloat()?.takeIf { it.isFinite() }
    }
    // v1.7 (item 15's UI): the live readout; its error disables OK and Done.
    val readout = ExpressionReadout.of(field.text, current?.toDouble()) { t -> read(t.trim())?.toDouble() }
    val blocked = ExpressionReadout.blocks(readout)

    fun apply() {
        if (blocked) return
        val text = field.text.trim()
        if (text.isEmpty() && target.kind == null) {
            target.key?.let { k -> inc.update { it.withCustom(k, null) } }
            onDismiss()
            return
        }
        val v = read(text)
        if (v == null || v <= 0f || v > max) {
            error = "Type a step above 0, up to ${IncrementStepping.format(max)}"
            return
        }
        if (target.kind != null) {
            inc.update { it.with(target.kind, v) }
        } else {
            val k = target.key ?: return
            inc.update { it.withCustom(k, v) }
            if (inc.state.custom[k] != v) {
                error = "Too many steps of their own: clear some in Increments"
                return
            }
        }
        onDismiss()
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BrushworkColors.ChromeHigh,
        contentColor = BrushworkColors.OnChrome,
        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            Text(target.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; error = null },
                label = { Text("${target.name} step") },
                suffix = if (target.suffix.isNotEmpty()) ({ Text(target.suffix) }) else null,
                singleLine = true,
                isError = error != null || blocked,
                supportingText = {
                    val e = error
                    when {
                        e != null -> Text(e)
                        readout != null -> ReadoutText(ExpressionReadout.text(readout, { IncrementStepping.format(it.toFloat()) }, target.suffix), blocked)
                        else -> Text(if (target.kind == null) "Leave empty for no step of its own" else "Sliders and drags land on multiples; typed values stay exact")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focus),
            )
            // The keypad has no operators (V17): + − × ÷ ( ) under the field.
            OperatorKeys(
                onKey = { key -> field = OperatorText.insert(field, key); error = null },
                modifier = Modifier.padding(end = 8.dp, bottom = 2.dp),
            )
            ToggleRow(
                "Use increments",
                state.enabled,
                { on -> inc.update { it.copy(enabled = on) } },
                description = if (state.enabled) "On: ${state.summary()}" else "Off: everything moves freely",
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (target.kind == null && current != null) {
                    TextButton(onClick = { target.key?.let { k -> inc.update { it.withCustom(k, null) } }; onDismiss() }) { Text("No step") }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = ::apply, enabled = !blocked) { Text("OK") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * More › Increments…: the master switch, the five steps and "Clear custom steps" (the same
 * content as Settings › Increments). Titled "Increment steps", so its minimized pill never reads
 * "Increments", the X / Y pill's "#" cell (I10). [onDismiss] closes it.
 */
@Composable
fun IncrementsSheet(controller: EditorController, onDismiss: () -> Unit) {
    BwSheet(title = INCREMENTS_SHEET_TITLE, onDismiss = onDismiss) {
        IncrementsSettingsSection(controller)
    }
}

/** The Increments sheet's title (unique: see [IncrementsSheet]). */
const val INCREMENTS_SHEET_TITLE = "Increment steps"

/**
 * Settings › Increments: "Use increments", the step of each kind (typed; what each one applies
 * to below it) and the controls' own steps ("Clear custom steps").
 */
@Composable
fun IncrementsSettingsSection(controller: EditorController) {
    val inc = controller.increments
    val s = inc.state
    ToggleRow(
        "Use increments",
        s.enabled,
        { on -> inc.update { it.copy(enabled = on) } },
        description = "Moves, sizes, scales and angles snap to steps; typed values stay exact",
    )
    for (kind in IncrementKind.entries) {
        StepField(inc, kind)
        Text(
            kindHint(kind),
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    val custom = s.custom.size
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            when (custom) {
                0 -> "No control has a step of its own (long-press a value to give it one)"
                1 -> "1 control has a step of its own"
                else -> "$custom controls have steps of their own"
            },
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { inc.update { it.copy(custom = emptyMap()) } }, enabled = custom > 0) { Text("Clear custom steps") }
    }
}

/** One kind's step, typed (never stepped by itself). */
@Composable
private fun StepField(inc: Increments, kind: IncrementKind) {
    val max = IncrementSettings.MAX_STEPS.getValue(kind).toDouble()
    NumberFieldCore(
        label = "${kind.label} step",
        value = inc.state.step(kind).toDouble(),
        onValueChange = { v -> inc.update { it.with(kind, v.toFloat()) } },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        decimals = 3,
        suffix = kind.suffix,
        min = MIN_TYPED_STEP,
        max = max,
        step = null,
        enabled = true,
        adjust = NumberAdjust.NONE,
        sliderMin = null,
        sliderMax = null,
        logSlider = null,
        dragStep = null,
        onValueChangeFinished = null,
        increments = IncrementBinding.None,
    )
}

/** Smallest step the sheet's fields send while typing ("0" on the way to "0.5" is not sent). */
private const val MIN_TYPED_STEP = 0.001

private fun kindHint(kind: IncrementKind): String = when (kind) {
    IncrementKind.LENGTH -> "Moves, X / Y positions, box and shape sizes, mask widths"
    IncrementKind.SIZE -> "Brush size, font size, stroke width"
    IncrementKind.SCALE -> "Transform scale (100, 110, 120 % …), pinches, handle scale"
    IncrementKind.ANGLE -> "Every rotation and angle"
    IncrementKind.PERCENT -> "Opacity, flow, hardness, amounts and other 0–100 sliders"
}
