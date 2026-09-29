package com.brushwork.paint.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Shared UI building blocks. Every panel in the app should use these so the UI is consistent.
 */

/** Standard bottom sheet used for all editor panels. Content scrolls vertically. */
@Composable
fun BwSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = BrushworkColors.Chrome,
        contentColor = BrushworkColors.OnChrome,
        scrimColor = Color.Black.copy(alpha = 0.25f),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                actions()
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            val inner = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(inner.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), content = content)
        }
    }
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

/**
 * Numeric text field that commits on Done / focus loss, with optional -/+ step buttons.
 * Shows [value] formatted with [decimals]; invalid text is reverted.
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
) {
    // "NaN", "Infinity" and "1e999" parse as doubles: they are invalid text here like any other
    // garbage (NaN would pass coerceIn, and an infinite value would reach the model).
    fun parse(s: String): Double? = Units.parse(s)?.takeIf { it.isFinite() }
    fun format(v: Double): String = if (v.isFinite()) Units.formatNumber(v, decimals) else ""
    var text by remember { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, decimals) { if (!focused) text = format(value) }
    fun commit() {
        val v = parse(text)
        if (v != null) onValueChange(v.coerceIn(min, max)) else text = format(value)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (step != null) RepeatIconButton(Icons.Filled.Remove, "Decrease $label", enabled = enabled) { onValueChange((value - step).coerceIn(min, max)) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Commit valid in-range values while typing, so buttons (Apply, presets) that
                // don't take focus always see the number the user typed.
                val v = parse(it)
                if (v != null && v >= min && v <= max) onValueChange(v)
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
        if (step != null) RepeatIconButton(Icons.Filled.Add, "Increase $label", enabled = enabled) { onValueChange((value + step).coerceIn(min, max)) }
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
) {
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
    )
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

/** Icon button that repeats [onClick] while held (after a short delay). */
@Composable
fun RepeatIconButton(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val current by rememberUpdatedState(onClick)
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
                    waitForUpOrCancellation()
                    job.cancel()
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

@Composable
fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
