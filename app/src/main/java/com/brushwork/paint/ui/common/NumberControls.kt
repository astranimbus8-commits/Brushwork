package com.brushwork.paint.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Expressions
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/*
 * The shared number controls (moved out of Components.kt by the v1.6 foundation, §4.5; same
 * package, same signatures; owned by area G): typeable sliders, numeric and length fields with
 * units, automatic sliders and drag-to-scrub handles.
 *
 * v1.6 increments (§3.4): inside the editor ([LocalIncrements]) every control has a kind,
 * inferred from its unit (`%` → PERCENT, `°` → ANGLE, `px` → SIZE; a [LengthField] is LENGTH) or
 * given by `incrementKind`, or else a custom step of its own under `incrementKey` (an explicit key
 * overrides the inferred kind; default "$label|$suffix"). While increments are on, sliders land
 * on multiples of the step (the range ends stay reachable), and -/+ buttons and scrub handles
 * move to the next multiples; typed values are never quantized. A long-press on the value opens
 * the Step popup. With increments off every control behaves exactly as in v1.5 (I8).
 */

/**
 * How a number control takes part in increments (internal): [Auto] infers or takes its kind or
 * custom key; [None] never steps (the step fields of the Increments sheet themselves).
 */
internal sealed interface IncrementBinding {
    /** [kind] (null: inferred from the suffix, else the custom step [key]); a kind's step × [stepScale] is in the shown unit. */
    class Auto(val kind: IncrementKind?, val key: String?, val stepScale: Double = 1.0) : IncrementBinding

    data object None : IncrementBinding
}

/**
 * Makes the value of a [LabeledSlider] typeable: tap the number, type, Done. The value is shown
 * for typing as value × [scale] with [decimals] decimals (a 0..1 slider shown as a percentage:
 * [Percent]); [suffix] is only a hint next to the number.
 */
@Immutable
class SliderTyping(val scale: Float = 1f, val decimals: Int = 0, val suffix: String = "") {
    companion object {
        /** A 0..1 value shown and typed as a whole percentage. */
        val Percent = SliderTyping(100f, 0, "%")
    }
}

/**
 * Slider with a label and value text. [onValueChangeFinished] fires on release (use for undo).
 * With [typing] the value text is a button: tapping it lets the number be typed in (committed on
 * Done or when the field loses focus, then [onValueChangeFinished] fires).
 *
 * v1.6 increments (§3.4): [incrementKind] overrides the kind inferred from the value's unit
 * ([typing]'s suffix, else the text after the number in [valueText]: `%` → PERCENT, `°` → ANGLE,
 * `px` → SIZE); [incrementKey] without [incrementKind] makes it a custom step under that key
 * whatever its unit says, and a control with neither and no kind of its unit has a custom step
 * under "$label|$suffix". Steps are in the shown unit ([SliderTyping.scale]; a 0..1 percentage slider
 * without typing counts as × 100). While increments are on the slider lands on the step's
 * multiples; a long-press on the value opens the Step popup.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    valueText: String = Units.formatNumber(value.toDouble(), if (valueRange.endInclusive - valueRange.start > 20f) 0 else 2),
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    typing: SliderTyping? = null,
    incrementKind: IncrementKind? = null,
    incrementKey: String? = null,
) {
    var editing by remember { mutableStateOf(false) }
    // v1.7: the value editor's text (here, so the operator keys under the value row type into it).
    var editorText by remember { mutableStateOf(TextFieldValue("")) }
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val focusManager = LocalFocusManager.current
    // Increments: the kind or custom key, and the step in slider units (null: the v1.5 slider).
    val inc = LocalIncrements.current
    // A value shown without a number ("None", "Off" at 0) keeps the unit the slider showed last,
    // so its kind (and its Step popup) doesn't flip while the slider moves to and from 0.
    val lastUnit = remember { arrayOfNulls<String>(1) }
    val shownUnit = typing?.suffix?.takeIf { it.isNotEmpty() } ?: IncrementStepping.unitOf(valueText)
    if (shownUnit != null) lastUnit[0] = shownUnit
    val unitSuffix = shownUnit ?: lastUnit[0].orEmpty()
    val (kind, key) = IncrementStepping.resolve(incrementKind, incrementKey, label, unitSuffix)
    val shownScale = typing?.scale?.takeIf { it.isFinite() && it != 0f } ?: IncrementStepping.impliedScale(kind, valueRange.endInclusive)
    val sliderStep = inc?.stepFor(kind, key)?.let { (it / shownScale).toDouble() }?.takeIf { IncrementStepping.valid(it) }
    val stepTarget = remember(kind, key, label, unitSuffix) { StepTarget(kind, key, if (kind == null) label else null, unitSuffix) }
    val sliderChange: (Float) -> Unit = if (sliderStep == null) onValueChange else { v ->
        onValueChange(IncrementStepping.snapSlider(v.toDouble(), sliderStep, valueRange.start.toDouble(), valueRange.endInclusive.toDouble()).toFloat())
    }
    // v1.7 (I13): "*2" / "/2" apply to the value shown when the editor opened.
    val shown = value * (typing?.scale ?: 1f)
    val readTyped: (String) -> Float? = { text ->
        typing?.let { t -> NumberSliderMath.parseTyped(Expressions.resolveRelative(text, shown) ?: text, t.scale, valueRange.start, valueRange.endInclusive) }
    }
    val typedReadout: (String) -> Readout? = { text ->
        ExpressionReadout.of(text, shown.toDouble()) { t -> readTyped(t)?.let { (it * (typing?.scale ?: 1f)).toDouble() } }
    }
    val editorOpen = typing != null && editing && enabled
    Column(modifier.fillMaxWidth()) {
        // A typeable value is a finger-sized target (a near miss would land on the slider below
        // and move it), so its row is as tall as the target.
        Row(
            Modifier.heightIn(min = if (typing != null) MinTouchTarget else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when {
                typing != null && editorOpen -> SliderValueEditor(
                    label = label,
                    field = editorText,
                    onFieldChange = { editorText = it },
                    suffix = typing.suffix,
                    onDone = { text ->
                        editing = false
                        // v1.7 (area I, gate 2 review): text whose readout is an error applies
                        // nothing, even where parseTyped's lenient v1.6 filter reads a number
                        // ("/0" at 120 is "Can't divide by 0", not 0).
                        val v = if (ExpressionReadout.blocks(typedReadout(text))) null else readTyped(text)
                        if (v != null) {
                            latestChange(v)
                            latestFinished?.invoke()
                        }
                    },
                )
                typing != null && enabled -> Box(
                    Modifier
                        .heightIn(min = MinTouchTarget)
                        .widthIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .stepOnLongPressFor(stepTarget)
                        .clickable(onClickLabel = "Type a value for $label", role = Role.Button) {
                            // Opens fully selected, so typing replaces the number.
                            val initial = Units.formatNumber(shown.toDouble(), typing.decimals)
                            editorText = TextFieldValue(initial, selection = TextRange(0, initial.length))
                            editing = true
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        valueText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrushworkColors.OnChrome,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(BrushworkColors.ChromeHigh)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                else -> Text(
                    valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrushworkColors.OnChromeDim,
                    modifier = Modifier.stepOnLongPressFor(stepTarget, enabled),
                )
            }
        }
        // v1.7 (item 15's UI): while the value is typed, its readout and the operator keys.
        if (typing != null && editorOpen) {
            val r = typedReadout(editorText.text)
            ExpressionKeys(
                r?.let { ExpressionReadout.text(it, { v -> Units.formatNumber(v, typing.decimals) }, typing.suffix) },
                ExpressionReadout.blocks(r),
                onKey = { key -> editorText = OperatorText.insert(editorText, key) },
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = sliderChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
            // A number being typed is committed before the slider moves (not over it later).
            modifier = if (typing != null) Modifier.clearFocusOnTouch(focusManager) else Modifier,
        )
    }
}

/**
 * Small in-place number editor of a [LabeledSlider]: focused when it appears ([LabeledSlider]
 * opens it with the whole number selected, so typing replaces it). [onDone] runs once, on Done
 * or when focus leaves.
 *
 * v1.7 (item 15's UI): the text is [LabeledSlider]'s, which shows the readout and the operator
 * keys under the value row while the editor is open ([ExpressionKeys]).
 */
@Composable
private fun SliderValueEditor(
    label: String,
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    suffix: String,
    onDone: (String) -> Unit,
) {
    val requester = remember { FocusRequester() }
    var wasFocused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val done by rememberUpdatedState(onDone)
    val latestField by rememberUpdatedState(field)
    fun finish() {
        if (finished) return
        finished = true
        done(latestField.text)
    }
    Row(
        Modifier
            .border(1.dp, BrushworkColors.Accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = field,
            onValueChange = onFieldChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = BrushworkColors.OnChrome, textAlign = TextAlign.End),
            cursorBrush = SolidColor(BrushworkColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { defaultKeyboardAction(ImeAction.Done); finish() }),
            modifier = Modifier
                .width(64.dp)
                .focusRequester(requester)
                .semantics { contentDescription = label }
                .onFocusChanged { f ->
                    if (f.isFocused) wasFocused = true else if (wasFocused) finish()
                },
        )
        if (suffix.isNotEmpty()) {
            Text(suffix, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(start = 2.dp))
        }
    }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
}

/** Smallest height of a tap target the app draws itself (Material components bring their own). */
internal val MinTouchTarget = 40.dp

/** Width a numeric field takes when its parent doesn't limit it (e.g. a horizontally scrolling strip). */
private val UnboundedFieldWidth = 240.dp

/** Narrowest slider placed beside its field; narrower rows put the slider under the field. */
private val MinInlineSliderWidth = 120.dp

/** Narrowest text part of a field (with its label and suffix) when a slider sits beside it. */
private val MinInlineTextWidth = 112.dp

/**
 * Numeric text field that commits on Done / focus loss, with optional -/+ step buttons (hold to
 * repeat). Shows [value] formatted with [decimals]; invalid text is reverted. Done also lets the
 * focus go, which closes the operator keys under the field (v1.7), unless it refused an
 * expression whose readout is an error.
 *
 * Besides typing, the number can be dragged ([adjust]):
 * - a compact slider, synced both ways with the text, whenever a range is known: an explicit
 *   [sliderMin]..[sliderMax] (clamped to [min]..[max]), else [min]..[max] when that range is
 *   finite and sensible for a slider (see [NumberSliderMath.autoScale]). Wide positive ranges
 *   (1..10000) are logarithmic; [logSlider] forces the kind. On wide rows the slider sits beside
 *   the text, on narrow ones under it.
 * - otherwise a scrub handle: drag it sideways to change the value continuously, about one
 *   [dragStep] (default [step], else [NumberSliderMath.defaultDragStep]) per 6dp, faster the
 *   further the finger travels.
 *
 * [onValueChange] is called for every change (each keystroke that forms a valid in-range number,
 * each slider / scrub move, each -/+ repeat); [onValueChangeFinished] once a change is complete
 * (text committed, slider or scrub released, -/+ released), e.g. to record undo or save.
 *
 * v1.6 increments (§3.4): [incrementKind] overrides the inferred kind (suffix `%` → PERCENT,
 * `°` → ANGLE, `px` → SIZE); [incrementKey] without [incrementKind] makes it a custom step under
 * that key whatever its unit says (otherwise a control without a kind steps by "$label|$suffix").
 * While increments are on, the slider lands on the step's multiples,
 * -/+ and the scrub handle move to the next multiples; typed values are never quantized. A
 * long-press on the field (while it isn't being typed in) or on the scrub handle opens the Step
 * popup.
 */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 2,
    suffix: String = "",
    min: Double = Double.NEGATIVE_INFINITY,
    max: Double = Double.POSITIVE_INFINITY,
    step: Double? = null,
    enabled: Boolean = true,
    adjust: NumberAdjust = NumberAdjust.AUTO,
    sliderMin: Double? = null,
    sliderMax: Double? = null,
    logSlider: Boolean? = null,
    dragStep: Double? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    incrementKind: IncrementKind? = null,
    incrementKey: String? = null,
) = NumberFieldCore(
    label, value, onValueChange, modifier, decimals, suffix, min, max, step, enabled, adjust,
    sliderMin, sliderMax, logSlider, dragStep, onValueChangeFinished,
    IncrementBinding.Auto(incrementKind, incrementKey),
)

/** [NumberField] with its [increments] binding spelled out (a [LengthField] converts its steps into its unit). */
@Composable
internal fun NumberFieldCore(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier,
    decimals: Int,
    suffix: String,
    min: Double,
    max: Double,
    step: Double?,
    enabled: Boolean,
    adjust: NumberAdjust,
    sliderMin: Double?,
    sliderMax: Double?,
    logSlider: Boolean?,
    dragStep: Double?,
    onValueChangeFinished: (() -> Unit)?,
    increments: IncrementBinding,
) {
    // "NaN", "Infinity" and "1e999" parse as doubles: they are invalid text here like any other
    // garbage (NaN would pass coerceIn, and an infinite value would reach the model).
    // v1.7 (I13): relative text ("*2", "/2") applies to the value the edit started from (taken when
    // the field gains focus, and after each commit or button / slider / scrub change), so live
    // commits while typing never apply it twice.
    val relativeBase = remember { DoubleArray(1) { value } }
    fun parse(s: String): Double? = Units.parse(Expressions.resolveRelative(s, relativeBase[0]) ?: s)?.takeIf { it.isFinite() }
    fun format(v: Double): String = if (v.isFinite()) Units.formatNumber(v, decimals) else ""
    // v1.7: a TextFieldValue, so the operator keys go in at the cursor.
    var textValue by remember { mutableStateOf(TextFieldValue(format(value))) }
    /** Shows [s] with the cursor after it (the same text keeps the cursor and the selection). */
    fun setText(s: String) { if (s != textValue.text) textValue = TextFieldValue(s, TextRange(s.length)) }
    var focused by remember { mutableStateOf(false) }
    // Done lets the focus go (the keys row closes with it). The focus-loss commit that follows
    // re-sends the value Done committed and finishes nothing again (`committed`).
    val fieldFocus = LocalFocusManager.current
    LaunchedEffect(value, decimals) { if (!focused) setText(format(value)) }
    // Hold-to-repeat buttons and drags run between recompositions: they read the latest values.
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    // Text already committed (Done), so the focus loss that follows doesn't finish the same edit
    // a second time (one undo step / save per edit). Cleared by any new change.
    var committed by remember { mutableStateOf<String?>(null) }
    /** v1.7 (item 15's UI): what [s] gives ("= 150 px") or its error, which refuses the commit. */
    fun readout(s: String): Readout? = ExpressionReadout.of(s, relativeBase[0]) { t -> parse(t)?.coerceIn(min, max) }
    fun commit() {
        val text = textValue.text
        val v = if (ExpressionReadout.blocks(readout(text))) null else parse(text)
        if (v != null) {
            val c = v.coerceIn(min, max)
            latestChange(c)
            // v1.7: committed relative text shows its result (and is the base of the next one).
            if (Expressions.isRelative(text)) setText(format(c))
            relativeBase[0] = c
            if (committed != textValue.text) latestFinished?.invoke()
            committed = textValue.text
        } else {
            setText(format(latestValue))
        }
    }
    // Buttons, slider and scrub set the number directly and show it at once, even in a focused
    // field, so a later focus-loss commit re-sends this number instead of stale typed text.
    fun set(v: Double) {
        if (!v.isFinite()) return
        val c = v.coerceIn(min, max)
        setText(format(c))
        committed = null
        relativeBase[0] = c
        latestChange(c)
    }
    fun finish() {
        committed = textValue.text
        latestFinished?.invoke()
    }
    /** The text changed to [t] (typed, or an operator key). */
    fun typed(t: String) {
        committed = null
        // Commit valid in-range values while typing, so buttons (Apply, presets) that
        // don't take focus always see the number the user typed.
        val v = parse(t)
        if (v != null && v >= min && v <= max) {
            // (A typed absolute number is the base of relative text typed next.)
            if (!Expressions.isRelative(t)) relativeBase[0] = v
            latestChange(v)
        }
    }

    val scale = remember(adjust, min, max, sliderMin, sliderMax, logSlider) {
        NumberSliderMath.scaleFor(adjust, min, max, sliderMin, sliderMax, logSlider)
    }
    val scrub = adjust != NumberAdjust.NONE && scale == null
    val scrubStep = dragStep?.takeIf { it > 0.0 && it.isFinite() } ?: step ?: NumberSliderMath.defaultDragStep(decimals)
    val scrubStart = remember { DoubleArray(1) }

    // Increments: the kind or custom key, and the step in the shown unit (null: the v1.5 field).
    val auto = increments as? IncrementBinding.Auto
    val (kind, key) = if (auto == null) null to null else IncrementStepping.resolve(auto.kind, auto.key, label, suffix)
    val inc = LocalIncrements.current
    val incStep: Double? = if (auto == null) null else inc?.stepFor(kind, key)?.toDouble()
        ?.let { if (kind != null) it * auto.stepScale else it }
        ?.takeIf { IncrementStepping.valid(it) }
    val stepTarget = remember(kind, key, label, suffix) { if (auto == null) null else StepTarget(kind, key, if (kind == null) label else null, if (kind == null) suffix else null) }
    /** [v] moved [n] steps: to the next multiples of the increment, else by [by]. */
    fun stepped(v: Double, n: Int, by: Double): Double = if (incStep != null) IncrementStepping.stepBy(v, n.toLong(), incStep) else v + n * by
    // A finger held on the field opens the Step popup; meanwhile the field refuses focus, so its
    // own long-press (select a word, keyboard) doesn't happen behind the popup. While it is being
    // typed in, a long-press is the text field's (select, paste).
    val stepHold = remember { StepHold() }
    val longPress: Modifier = if (stepTarget == null) Modifier else Modifier.stepOnLongPressFor(stepTarget, enabled && !focused, stepHold)

    val field: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step != null) {
                RepeatIconButton(Icons.Filled.Remove, "Decrease $label", enabled = enabled, onRelease = ::finish) { set(stepped(latestValue, -1, step)) }
            }
            OutlinedTextField(
                value = textValue,
                onValueChange = {
                    val changed = it.text != textValue.text
                    textValue = it
                    // (A cursor move or a selection alone is no edit.)
                    if (changed) typed(it.text)
                },
                // Beside a slider the box is narrow: a long label ends in "…" instead of being cut.
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                // Done commits and, like everywhere on Android, puts the keyboard away (a sheet
                // sits on top of it, so it would otherwise keep covering the canvas).
                // v1.7 final QA: a value Done commits also lets the focus go, so the operator keys
                // under the field close at once, as a slider's value editor closes on Done. Kept,
                // the row stayed open until the next touch elsewhere took the focus (a scrub's
                // touch-down), and everything under it moved up 42 dp under that finger. Text
                // whose readout is an error is refused (Done applies nothing): the field keeps
                // the focus and its keys, as before.
                keyboardActions = KeyboardActions(onDone = {
                    val refused = ExpressionReadout.blocks(readout(textValue.text))
                    commit()
                    defaultKeyboardAction(ImeAction.Done)
                    if (!refused) fieldFocus.clearFocus()
                }),
                modifier = Modifier
                    .weight(1f)
                    .then(longPress)
                    .focusProperties { canFocus = !stepHold.active }
                    .onFocusChanged { f ->
                        if (focused && !f.isFocused) commit()
                        if (!focused && f.isFocused) relativeBase[0] = latestValue
                        focused = f.isFocused
                    },
            )
            if (step != null) {
                RepeatIconButton(Icons.Filled.Add, "Increase $label", enabled = enabled, onRelease = ::finish) { set(stepped(latestValue, 1, step)) }
            }
            if (scrub) {
                ScrubHandle(
                    label = label,
                    enabled = enabled,
                    onStart = { scrubStart[0] = latestValue },
                    onDrag = { dp ->
                        set(
                            if (incStep != null) IncrementStepping.stepBy(scrubStart[0], NumberSliderMath.scrubSteps(dp), incStep).coerceIn(min, max)
                            else NumberSliderMath.scrubValue(scrubStart[0], dp, scrubStep, min, max),
                        )
                    },
                    onEnd = ::finish,
                    onStep = { direction -> set(stepped(latestValue, direction, scrubStep)); finish() },
                    modifier = longPress,
                )
            }
        }
    }

    // v1.7 (item 15's UI): the readout and the operator keys under the field while it is typed in.
    // Inline (no popup), and the same layout node with or without them, so the field keeps focus.
    val keys: @Composable () -> Unit = {
        if (focused && enabled) {
            val r = readout(textValue.text)
            ExpressionKeys(r?.let { ExpressionReadout.text(it, ::format, suffix) }, ExpressionReadout.blocks(r), onKey = { key ->
                textValue = OperatorText.insert(textValue, key)
                typed(textValue.text)
            })
        }
    }
    val control: @Composable () -> Unit = {
        if (scale == null) {
            Box(Modifier.boundedWidth()) { field() }
        } else {
            val minText = MinInlineTextWidth + if (step != null) 80.dp else 0.dp
            FieldWithSlider(Modifier, minText, field) {
                val focusManager = LocalFocusManager.current
                Slider(
                    value = scale.fraction(value),
                    onValueChange = { f ->
                        val v = NumberSliderMath.sliderValue(f, scale, decimals, min, max)
                        set(if (incStep == null) v else IncrementStepping.snapSlider(v, incStep, maxOf(scale.min, min), minOf(scale.max, max)))
                    },
                    onValueChangeFinished = ::finish,
                    enabled = enabled,
                    colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        // A field being typed in commits first, so it can't overwrite the drag later.
                        .clearFocusOnTouch(focusManager)
                        .semantics {
                            contentDescription = label
                            stateDescription = if (suffix.isEmpty()) format(value) else "${format(value)} $suffix"
                        },
                )
            }
        }
    }
    ControlOverKeys(modifier, control, keys)
}

/**
 * [control] with [keys] (the readout and operator keys of a field being typed in; may emit
 * nothing) under it, no wider than [control]: the keys never widen a field's place in a row,
 * and in a narrow one their row scrolls ([OperatorKeys]). A bounded height that [control] fills
 * leaves the keys none.
 */
@Composable
private fun ControlOverKeys(modifier: Modifier, control: @Composable () -> Unit, keys: @Composable () -> Unit) {
    Layout(content = { control(); keys() }, modifier = modifier) { measurables, constraints ->
        val c = measurables[0].measure(constraints)
        val left = if (constraints.hasBoundedHeight) (constraints.maxHeight - c.height).coerceAtLeast(0) else Constraints.Infinity
        val k = measurables.getOrNull(1)?.measure(Constraints(maxWidth = c.width, maxHeight = left))
        val h = (c.height + (k?.height ?: 0)).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(c.width, h) {
            c.placeRelative(0, 0)
            k?.placeRelative(0, c.height)
        }
    }
}

/**
 * A length in document pixels, edited in [unit] (converted with [dpi]).
 *
 * v1.6 increments (§3.4): a length field is a LENGTH control unless [incrementKind] says
 * otherwise (e.g. SIZE for a font size); [incrementKey] alone makes it a custom step under that
 * key, in the field's shown unit (as in [NumberField]). Length and size steps
 * are document px, shown in [unit] (10 px is 0.847 mm at 300 dpi: an mm field lands on its
 * multiples).
 */
@Composable
fun LengthField(
    label: String,
    px: Double,
    onPxChange: (Double) -> Unit,
    unit: LengthUnit,
    dpi: Double,
    modifier: Modifier = Modifier,
    step: Double? = unit.defaultStep,
    minPx: Double = Double.NEGATIVE_INFINITY,
    maxPx: Double = Double.POSITIVE_INFINITY,
    enabled: Boolean = true,
    adjust: NumberAdjust = NumberAdjust.AUTO,
    sliderMinPx: Double? = null,
    sliderMaxPx: Double? = null,
    logSlider: Boolean? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    incrementKind: IncrementKind? = null,
    incrementKey: String? = null,
) {
    // Decided in pixels: the unit changes how wide a range looks, not whether a slider suits it.
    val pxScale = remember(adjust, minPx, maxPx, sliderMinPx, sliderMaxPx, logSlider) {
        NumberSliderMath.scaleFor(adjust, minPx, maxPx, sliderMinPx, sliderMaxPx, logSlider)
    }
    // A length field is LENGTH unless told otherwise; a key alone is a custom step (in the shown unit).
    val kind = incrementKind ?: if (incrementKey != null) null else IncrementKind.LENGTH
    // Length and size steps are px: shown in this field's unit.
    val stepScale = if (kind == IncrementKind.LENGTH || kind == IncrementKind.SIZE) unit.fromPx(1.0, dpi) else 1.0
    NumberFieldCore(
        label = label,
        value = unit.fromPx(px, dpi),
        onValueChange = { onPxChange(unit.toPx(it, dpi).coerceIn(minPx, maxPx)) },
        modifier = modifier,
        decimals = unit.decimals,
        suffix = unit.short,
        min = unit.fromPx(minPx, dpi),
        max = unit.fromPx(maxPx, dpi),
        step = step,
        enabled = enabled,
        adjust = if (adjust == NumberAdjust.AUTO && pxScale == null) NumberAdjust.SCRUB else adjust,
        sliderMin = pxScale?.let { unit.fromPx(it.min, dpi) },
        sliderMax = pxScale?.let { unit.fromPx(it.max, dpi) },
        logSlider = pxScale?.log,
        dragStep = step ?: unit.defaultStep,
        onValueChangeFinished = onValueChangeFinished,
        increments = IncrementBinding.Auto(kind, incrementKey, stepScale.takeIf { it.isFinite() && it > 0.0 } ?: 1.0),
    )
}

/**
 * Lays out a field and its slider: side by side when the row is wide enough, else the slider
 * under the field. A custom layout (not BoxWithConstraints) so it also works where intrinsic
 * sizes are measured (dropdown menus). An unbounded width (a horizontally scrolling strip) is
 * treated as [UnboundedFieldWidth].
 */
@Composable
private fun FieldWithSlider(modifier: Modifier, minTextWidth: Dp, field: @Composable () -> Unit, slider: @Composable () -> Unit) {
    Layout(content = { field(); slider() }, modifier = modifier) { measurables, constraints ->
        val fieldM = measurables[0]
        val sliderM = measurables[1]
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(constraints.minWidth, UnboundedFieldWidth.roundToPx())
        val gap = 8.dp.roundToPx()
        val minText = minTextWidth.roundToPx()
        val minSlider = MinInlineSliderWidth.roundToPx()
        val maxH = constraints.maxHeight
        if (width >= minText + gap + minSlider) {
            val fw = (width * 0.45f).roundToInt().coerceIn(minText, width - gap - minSlider)
            val sw = width - fw - gap
            val f = fieldM.measure(Constraints(minWidth = fw, maxWidth = fw, maxHeight = maxH))
            val s = sliderM.measure(Constraints(minWidth = sw, maxWidth = sw, maxHeight = maxH))
            val h = maxOf(f.height, s.height).coerceIn(constraints.minHeight, maxH)
            layout(width, h) {
                f.placeRelative(0, (h - f.height) / 2)
                s.placeRelative(fw + gap, (h - s.height) / 2)
            }
        } else {
            val f = fieldM.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = maxH))
            val left = if (constraints.hasBoundedHeight) (maxH - f.height).coerceAtLeast(0) else Constraints.Infinity
            val s = sliderM.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = left))
            val h = (f.height + s.height).coerceIn(constraints.minHeight, maxH)
            layout(width, h) {
                f.placeRelative(0, 0)
                s.placeRelative(0, f.height)
            }
        }
    }
}

/** Gives an unbounded width (horizontally scrolling parent) a finite one, so `weight` works inside. */
private fun Modifier.boundedWidth(): Modifier = layout { measurable, constraints ->
    val c = if (constraints.hasBoundedWidth) constraints else constraints.copy(maxWidth = maxOf(constraints.minWidth, UnboundedFieldWidth.roundToPx()))
    val p = measurable.measure(c)
    layout(p.width, p.height) { p.placeRelative(0, 0) }
}

/** Clears text-field focus as soon as a touch starts here (before the touched control acts). */
internal fun Modifier.clearFocusOnTouch(focusManager: FocusManager): Modifier = pointerInput(focusManager) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        focusManager.clearFocus()
    }
}

/**
 * Handle beside an unbounded numeric field: drag it sideways to change the value continuously.
 * [onDrag] gets the horizontal distance in dp since the drag started (after the touch slop);
 * vertical drags are left to an enclosing scroll. Screen readers get increase / decrease actions.
 */
@Composable
private fun ScrubHandle(
    label: String,
    enabled: Boolean,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val stepBy by rememberUpdatedState(onStep)
    var active by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(width = 40.dp, height = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) BrushworkColors.AccentDim else BrushworkColors.ChromeHigh.copy(alpha = 0.6f))
            .semantics {
                contentDescription = "Drag sideways to change $label"
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction("Increase $label") { stepBy(1); true },
                        CustomAccessibilityAction("Decrease $label") { stepBy(-1); true },
                    )
                }
            }
            .pointerInput(enabled, focusManager) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A field being typed in commits before the drag changes the value.
                    focusManager.clearFocus()
                    var total = 0f
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        total = over
                    } ?: return@awaitEachGesture
                    active = true
                    start()
                    drag(total.toDp().value)
                    try {
                        horizontalDrag(slop.id) { change ->
                            total += change.positionChange().x
                            change.consume()
                            drag(total.toDp().value)
                        }
                    } finally {
                        active = false
                        end()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.UnfoldMore,
            contentDescription = null,
            tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.5f),
            modifier = Modifier.rotate(90f),
        )
    }
}
