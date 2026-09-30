package com.brushwork.paint.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Shared UI building blocks. Every panel in the app should use these so the UI is consistent.
 */

/** Background of editor sheets: see-through so the artwork stays visible behind menus. */
val SheetBackground = BrushworkColors.Chrome.copy(alpha = 0.88f)

/** Height of the compact drag handle at the top of every sheet (the Material one takes 48dp). */
private val SheetHandleHeight = 16.dp

/** Smallest body a sheet keeps, even on a short landscape screen with the keyboard up. */
private val MinSheetBody = 120.dp

/**
 * Standard bottom sheet used for all editor panels. It takes at most [maxHeightFraction] of the
 * screen (half by default), has a translucent background and no dimming scrim, so the canvas
 * stays visible above and behind it. Content scrolls vertically inside the sheet (with
 * [scrollable] = false the content must bring its own scrolling, e.g. a LazyColumn, or a
 * `Modifier.weight(1f, fill = false).verticalScroll(...)` column).
 *
 * [footer] stays pinned under the scrolling body (primary actions such as Apply). With the
 * keyboard up the sheet sits on top of it and keeps its body height, so the field being typed
 * into stays visible. [dismissible] = false keeps the sheet open on outside taps and swipes (the
 * back button still calls [onDismiss]); [showClose] = false hides the ✕ (when [actions] already
 * offer Cancel / OK).
 */
@Composable
fun BwSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    maxHeightFraction: Float = 0.5f,
    dismissible: Boolean = true,
    showClose: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = state,
        sheetGesturesEnabled = dismissible,
        containerColor = SheetBackground,
        contentColor = BrushworkColors.OnChrome,
        scrimColor = Color.Transparent,
        dragHandle = { SheetHandle() },
        // No top inset: a half-height sheet never reaches the status bar, and the padding would
        // only eat its room. The bottom inset includes the keyboard.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true, shouldDismissOnClickOutside = dismissible),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = sheetBodyMaxHeight(screenHeight, maxHeightFraction))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    // The first focus target of the sheet: when its window gains focus, focus
                    // lands here instead of on the first text field (which would open the
                    // keyboard and scroll the body away from its top).
                    .focusable()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                actions()
                if (showClose) IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            val inner = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
            // weight(fill = false): the body takes the height left under the header and scrolls.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .then(inner)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = if (footer == null) 16.dp else 4.dp),
                content = content,
            )
            if (footer != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), content = footer)
            }
        }
    }
}

/**
 * Tallest body (everything under the drag handle) of a sheet: [fraction] of the screen, but
 * never more than the room above the keyboard.
 */
@Composable
private fun sheetBodyMaxHeight(screenHeight: Dp, fraction: Float): Dp {
    val density = LocalDensity.current
    val ime = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val wanted = screenHeight * fraction.coerceIn(0.2f, 1f)
    val room = screenHeight - ime - top
    return (minOf(wanted, room) - SheetHandleHeight).coerceAtLeast(MinSheetBody)
}

@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(BrushworkColors.OnChromeDim.copy(alpha = 0.6f))
    )
}

/** Standard dialog with confirm/dismiss buttons. */
@Composable
fun BwDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String = "OK",
    onConfirm: (() -> Unit)? = null,
    dismissText: String = "Cancel",
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) },
        confirmButton = { if (onConfirm != null) TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (onConfirm == null) "Close" else dismissText) } },
        containerColor = BrushworkColors.ChromeHigh,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = BrushworkColors.OnChromeDim,
        letterSpacing = 1.sp,
        modifier = modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** Slider with a label and value text. [onValueChangeFinished] fires on release (use for undo). */
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
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
        )
    }
}

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
    fun commit() {
        val v = parse(text)
        if (v != null) {
            latestChange(v.coerceIn(min, max))
            latestFinished?.invoke()
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
        latestChange(c)
    }
    fun finish() { latestFinished?.invoke() }

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
                    text = it
                    // Commit valid in-range values while typing, so buttons (Apply, presets) that
                    // don't take focus always see the number the user typed.
                    val v = parse(it)
                    if (v != null && v >= min && v <= max) latestChange(v)
                },
                label = { Text(label, maxLines = 1) },
                suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
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

/** A length in document pixels, edited in [unit] (converted with [dpi]). */
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
private fun Modifier.clearFocusOnTouch(focusManager: FocusManager): Modifier = pointerInput(focusManager) {
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

/** Dropdown to pick a [LengthUnit]. */
@Composable
fun UnitSelector(unit: LengthUnit, onUnitChange: (LengthUnit) -> Unit, modifier: Modifier = Modifier, units: List<LengthUnit> = LengthUnit.entries) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Text(unit.short)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Change unit")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            units.forEach { u ->
                DropdownMenuItem(text = { Text("${u.label} (${u.short})") }, onClick = { onUnitChange(u); open = false })
            }
        }
    }
}

/** Four arrow buttons (hold to repeat). Calls [onNudge] with (dx, dy) in {-1, 0, 1}. */
@Composable
fun NudgePad(onNudge: (dx: Int, dy: Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RepeatIconButton(Icons.Filled.KeyboardArrowUp, "Move up") { onNudge(0, -1) }
        Row {
            RepeatIconButton(Icons.Filled.KeyboardArrowLeft, "Move left") { onNudge(-1, 0) }
            Spacer(Modifier.width(40.dp))
            RepeatIconButton(Icons.Filled.KeyboardArrowRight, "Move right") { onNudge(1, 0) }
        }
        RepeatIconButton(Icons.Filled.KeyboardArrowDown, "Move down") { onNudge(0, 1) }
    }
}

/** Icon button that repeats [onClick] while held (after a short delay); [onRelease] runs when the finger lifts. */
@Composable
fun RepeatIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onRelease: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val current by rememberUpdatedState(onClick)
    val released by rememberUpdatedState(onRelease)
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    current()
                    val job = scope.launch {
                        delay(400)
                        while (true) { current(); delay(60) }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        job.cancel()
                        released?.invoke()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim)
    }
}

/** Square-ish icon button with a highlighted "selected" state (toolbars, tool pickers). */
@Composable
fun ToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) BrushworkColors.AccentDim else Color.Transparent,
            contentColor = if (selected) Color.White else BrushworkColors.OnChrome,
            disabledContentColor = BrushworkColors.OnChromeDim.copy(alpha = 0.5f),
        ),
    ) { Icon(icon, contentDescription = contentDescription) }
}

/** Horizontal row of single-choice chips. */
@Composable
fun ChoiceChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Scroll only when the width is bounded: inside another horizontally scrolling container
    // (e.g. the tool options strip) a nested horizontalScroll would crash at measure time.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val scroll = if (constraints.hasBoundedWidth) Modifier.horizontalScroll(rememberScrollState()) else Modifier
        ChoiceChipsRow(options, selected, onSelect, scroll)
    }
}

@Composable
private fun ChoiceChipsRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White),
            )
        }
    }
}

/**
 * A labelled on/off switch. The whole row is the toggle (tapping the text works too, and a
 * screen reader announces the label with the switch state), not just the small switch.
 */
@Composable
fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Checkerboard background (shows transparency). */
fun Modifier.checkerboard(cell: Dp = 6.dp): Modifier = this.drawBehind {
    drawRect(Color.White)
    val c = Color(0xFFCCCCCC)
    val n = cell.toPx().coerceAtLeast(1f)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else n
        while (x < size.width) {
            drawRect(c, topLeft = Offset(x, y), size = Size(n, n))
            x += 2 * n
        }
        y += n
        row++
    }
}

/** Round color swatch with checkerboard for alpha; supports tap and long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorSwatch(
    color: Int,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .checkerboard(size / 5)
            .background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) BrushworkColors.Accent else BrushworkColors.ChromeBorder, shape)
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier)
    )
}

/** Small rounded container for grouping controls in panels. */
@Composable
fun PanelCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(12.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(color = BrushworkColors.ChromeHigh, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(padding), content = content)
    }
}
