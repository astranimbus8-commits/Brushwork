package com.brushwork.paint.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Shared UI building blocks. Every panel in the app should use these so the UI is consistent.
 */

/** Background of editor sheets: see-through so the artwork stays visible behind menus (v1.6: [IbisColors.Sheet]). */
val SheetBackground = IbisColors.Sheet

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
 * into stays visible. [showClose] = false hides the ✕ (when [actions] already offer Cancel / OK).
 *
 * Inside the editor ([LocalSheetHost] provided) the sheet is a NON-MODAL panel drawn by the
 * editor's [SheetHost]: touches outside it minimize it to a pill and reach the canvas; only ✕,
 * Back or the caller dropping this call close it (see SheetHost.kt). Elsewhere it is a modal
 * bottom sheet, where [dismissible] = false keeps it open on outside taps and swipes (the back
 * button still calls [onDismiss]).
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
    val host = LocalSheetHost.current
    // A sheet asked for from another window (a popup or dialog) stays modal: the host draws in
    // the editor's own window only.
    if (host != null && host.view === LocalView.current) {
        HostedBwSheet(host, title, onDismiss, modifier, scrollable, maxHeightFraction, showClose, actions, footer, content)
    } else {
        ModalBwSheet(title, onDismiss, modifier, scrollable, maxHeightFraction, dismissible, showClose, actions, footer, content)
    }
}

@Composable
private fun ModalBwSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    scrollable: Boolean,
    maxHeightFraction: Float,
    dismissible: Boolean,
    showClose: Boolean,
    actions: @Composable RowScope.() -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)?,
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
internal fun SheetHandle() {
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
            // The last cells are cut at the edges: nothing is drawn outside the bounds (an
            // unclipped swatch, e.g. the bottom bar's colour square, would show a ragged rim).
            drawRect(c, topLeft = Offset(x, y), size = Size(minOf(n, size.width - x), minOf(n, size.height - y)))
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
