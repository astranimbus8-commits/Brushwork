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
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/*
 * The shared number controls (moved out of Components.kt by the v1.6 foundation, §4.5; same
 * package, same signatures; owned by area G): typeable sliders, numeric and length fields with
 * units, automatic sliders and drag-to-scrub handles. v1.6 adds the increment parameters
 * (`incrementKind` / `incrementKey`), no-ops until area G infers and applies the steps.
 */

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
 * v1.6 increments (§3.4; applied by area G): [incrementKind] overrides the kind inferred from
 * the value's suffix (`%` → PERCENT, `°` → ANGLE); [incrementKey] names the control's custom step
 * when it has no kind (default "$label|$suffix"). No-ops in the foundation.
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
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxWidth()) {
        // A typeable value is a finger-sized target (a near miss would land on the slider below
        // and move it), so its row is as tall as the target.
        Row(
            Modifier.heightIn(min = if (typing != null) MinTouchTarget else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when {
                typing != null && editing && enabled -> SliderValueEditor(
                    label = label,
                    initial = Units.formatNumber((value * typing.scale).toDouble(), typing.decimals),
                    suffix = typing.suffix,
                    onDone = { text ->
                        editing = false
                        val v = NumberSliderMath.parseTyped(text, typing.scale, valueRange.start, valueRange.endInclusive)
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
                        .clickable(onClickLabel = "Type a value for $label", role = Role.Button) { editing = true },
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
                else -> Text(valueText, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim)
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
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
 * Small in-place number editor of a [LabeledSlider]: focused and fully selected when it appears,
 * so typing replaces the number. [onDone] runs once, on Done or when focus leaves.
 */
@Composable
private fun SliderValueEditor(label: String, initial: String, suffix: String, onDone: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val requester = remember { FocusRequester() }
    var wasFocused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val done by rememberUpdatedState(onDone)
    fun finish() {
        if (finished) return
        finished = true
        done(field.text)
    }
    Row(
        Modifier
            .border(1.dp, BrushworkColors.Accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = field,
            onValueChange = { field = it },
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
 * repeat). Shows [value] formatted with [decimals]; invalid text is reverted.
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
 * v1.6 increments (§3.4; applied by area G): [incrementKind] overrides the inferred kind (suffix
 * `%` → PERCENT, `°` → ANGLE, `px` → SIZE); [incrementKey] names the custom step of a control
 * without a kind (default "$label|$suffix"). Typed values are never quantized. No-ops in the
 * foundation.
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
) {
    // "NaN", "Infinity" and "1e999" parse as doubles: they are invalid text here like any other
    // garbage (NaN would pass coerceIn, and an infinite value would reach the model).
    fun parse(s: String): Double? = Units.parse(s)?.takeIf { it.isFinite() }
    fun format(v: Double): String = if (v.isFinite()) Units.formatNumber(v, decimals) else ""
    var text by remember { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, decimals) { if (!focused) text = format(value) }
    // Hold-to-repeat buttons and drags run between recompositions: they read the latest values.
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    // Text already committed (Done), so the focus loss that follows doesn't finish the same edit
    // a second time (one undo step / save per edit). Cleared by any new change.
    var committed by remember { mutableStateOf<String?>(null) }
    fun commit() {
        val v = parse(text)
        if (v != null) {
            latestChange(v.coerceIn(min, max))
            if (committed != text) latestFinished?.invoke()
            committed = text
        } else {
            text = format(latestValue)
        }
    }
    // Buttons, slider and scrub set the number directly and show it at once, even in a focused
    // field, so a later focus-loss commit re-sends this number instead of stale typed text.
    fun set(v: Double) {
        if (!v.isFinite()) return
        val c = v.coerceIn(min, max)
        text = format(c)
        committed = null
        latestChange(c)
    }
    fun finish() {
        committed = text
        latestFinished?.invoke()
    }

    val scale = remember(adjust, min, max, sliderMin, sliderMax, logSlider) {
        NumberSliderMath.scaleFor(adjust, min, max, sliderMin, sliderMax, logSlider)
    }
    val scrub = adjust != NumberAdjust.NONE && scale == null
    val scrubStep = dragStep?.takeIf { it > 0.0 && it.isFinite() } ?: step ?: NumberSliderMath.defaultDragStep(decimals)
    val scrubStart = remember { DoubleArray(1) }

    val field: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step != null) {
                RepeatIconButton(Icons.Filled.Remove, "Decrease $label", enabled = enabled, onRelease = ::finish) { set(latestValue - step) }
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    if (it != text) committed = null
                    text = it
                    // Commit valid in-range values while typing, so buttons (Apply, presets) that
                    // don't take focus always see the number the user typed.
                    val v = parse(it)
                    if (v != null && v >= min && v <= max) latestChange(v)
                },
                // Beside a slider the box is narrow: a long label ends in "…" instead of being cut.
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                // Done commits and, like everywhere on Android, puts the keyboard away (a sheet
                // sits on top of it, so it would otherwise keep covering the canvas).
                keyboardActions = KeyboardActions(onDone = { commit(); defaultKeyboardAction(ImeAction.Done) }),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { f -> if (focused && !f.isFocused) commit(); focused = f.isFocused },
            )
            if (step != null) {
                RepeatIconButton(Icons.Filled.Add, "Increase $label", enabled = enabled, onRelease = ::finish) { set(latestValue + step) }
            }
            if (scrub) {
                ScrubHandle(
                    label = label,
                    enabled = enabled,
                    onStart = { scrubStart[0] = latestValue },
                    onDrag = { dp -> set(NumberSliderMath.scrubValue(scrubStart[0], dp, scrubStep, min, max)) },
                    onEnd = ::finish,
                    onStep = { direction -> set(latestValue + direction * scrubStep); finish() },
                )
            }
        }
    }

    if (scale == null) {
        Box(modifier.boundedWidth()) { field() }
    } else {
        val minText = MinInlineTextWidth + if (step != null) 80.dp else 0.dp
        FieldWithSlider(modifier, minText, field) {
            val focusManager = LocalFocusManager.current
            Slider(
                value = scale.fraction(value),
                onValueChange = { f -> set(NumberSliderMath.sliderValue(f, scale, decimals, min, max)) },
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

/**
 * A length in document pixels, edited in [unit] (converted with [dpi]).
 *
 * v1.6 increments (§3.4; applied by area G): a length field is a LENGTH control unless
 * [incrementKind] says otherwise (e.g. SIZE for a font size); [incrementKey] as in [NumberField].
 * No-ops in the foundation.
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
    NumberField(
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
        incrementKind = incrementKind,
        incrementKey = incrementKey,
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
) {
    val focusManager = LocalFocusManager.current
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val stepBy by rememberUpdatedState(onStep)
    var active by remember { mutableStateOf(false) }
    Box(
        Modifier
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
