package com.brushwork.paint.ui.common

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/*
 * Non-modal editor sheets.
 *
 * Inside the editor every [BwSheet] is rendered by a [SheetHost] in the editor's own window
 * instead of a modal bottom sheet window: the canvas around the panel keeps working. A touch
 * outside the panel doesn't close it; the host MINIMIZES it to a small pill (title + restore +
 * close) and the touch reaches the canvas, so the view can be zoomed or an object moved while the
 * menu waits. Tapping the pill brings the panel back exactly as it was: its content stays
 * composed (with its scroll position and state) while minimized, it is only not placed.
 *
 * Sheets opened from a sheet (a color picker from a panel) stack: the newest is shown, closing it
 * reveals the one below. ✕, Back ([SheetHostState.dismissTop]) and the calling UI dropping its
 * BwSheet close a sheet; nothing else does. Without a host (the gallery, tests composing a panel
 * directly) BwSheet falls back to the modal bottom sheet.
 */

/** The editor's sheet host, or null where sheets are modal. */
val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

/**
 * The group of the sheets composed under it: any key, e.g. the editor panel whose composable
 * opens them (a color picker opened from a panel belongs to the panel's group). The host can
 * bring a group back on top ([SheetHostState.bringToFront]), e.g. when the panel's button is
 * pressed while another sheet covers it.
 */
val LocalSheetGroup = staticCompositionLocalOf<Any?> { null }

/** Semantics of a hosted sheet panel (its title), for tests and tools that look for open menus. */
val BwSheetTitleKey = SemanticsPropertyKey<String>("BwSheetTitle")
private var SemanticsPropertyReceiver.bwSheetTitle by BwSheetTitleKey

/** Semantics of the pill of a minimized sheet (the sheet's title). */
val BwSheetPillKey = SemanticsPropertyKey<String>("BwSheetPill")
private var SemanticsPropertyReceiver.bwSheetPill by BwSheetPillKey

/**
 * One open [BwSheet] registered with a [SheetHostState]. Every field follows the latest
 * arguments of its BwSheet call (they are the call's `rememberUpdatedState` holders).
 */
@Stable
class SheetEntry internal constructor(
    internal val title: State<String>,
    internal val onDismiss: State<() -> Unit>,
    internal val modifier: State<Modifier>,
    internal val scrollable: State<Boolean>,
    internal val maxHeightFraction: State<Float>,
    internal val showClose: State<Boolean>,
    internal val actions: State<@Composable RowScope.() -> Unit>,
    internal val footer: State<(@Composable ColumnScope.() -> Unit)?>,
    internal val content: State<@Composable ColumnScope.() -> Unit>,
    /** CompositionLocals of the BwSheet call site, provided around the content in the host. */
    internal val locals: State<CompositionLocalContext>,
    /** [LocalSheetGroup] at the call site. */
    internal val group: State<Any?>,
)

/**
 * The stack of open sheets of one screen and whether the top one is minimized. Create it with
 * [rememberSheetHostState], provide it with [LocalSheetHost] and render it with [SheetHost]
 * (panel) and [SheetPill] (minimized).
 */
@Stable
class SheetHostState(internal val view: View?) {
    private val stack = mutableStateListOf<SheetEntry>()

    /** Open sheets, oldest first (the last one is shown). */
    val entries: List<SheetEntry> get() = stack

    /** True while the top sheet is folded into its pill. */
    var minimized by mutableStateOf(false)
        private set

    val isEmpty: Boolean get() = stack.isEmpty()

    /** A sheet panel is on screen (open and not minimized). */
    val hasExpanded: Boolean get() = stack.isNotEmpty() && !minimized

    /** Title of the sheet on top (the one shown, or the one in the pill). */
    val topTitle: String? get() = stack.lastOrNull()?.title?.value

    internal fun register(entry: SheetEntry) {
        stack.add(entry)
        // A sheet the user just opened is shown, even when others were minimized.
        minimized = false
    }

    internal fun unregister(entry: SheetEntry) {
        stack.remove(entry)
        if (stack.isEmpty()) minimized = false
    }

    /** Folds the top sheet into its pill (no-op without sheets). */
    fun minimize() {
        if (stack.isNotEmpty()) minimized = true
    }

    /** Shows the top sheet again. */
    fun restore() {
        minimized = false
    }

    /**
     * Shows the sheets of [group] (see [LocalSheetGroup]): they move on top of the others,
     * keeping their order and state, and the top one is shown. Without such sheets it is
     * [restore].
     */
    fun bringToFront(group: Any) {
        val mine = stack.filter { it.group.value == group }
        if (mine.isNotEmpty() && stack.subList(stack.size - mine.size, stack.size) != mine) {
            stack.removeAll(mine)
            stack.addAll(mine)
        }
        minimized = false
    }

    /** Closes the top sheet the way its ✕ / Back would (asks its caller to close it). */
    fun dismissTop() {
        stack.lastOrNull()?.onDismiss?.value?.invoke()
    }
}

@Composable
fun rememberSheetHostState(): SheetHostState {
    val view = LocalView.current
    return remember(view) { SheetHostState(view) }
}

/**
 * Registers a hosted sheet with [host] for as long as the calling BwSheet is composed. The
 * content is composed by the host, not here.
 */
@Composable
internal fun HostedBwSheet(
    host: SheetHostState,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    scrollable: Boolean,
    maxHeightFraction: Float,
    showClose: Boolean,
    actions: @Composable RowScope.() -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleState = rememberUpdatedState(title)
    val dismissState = rememberUpdatedState(onDismiss)
    val modifierState = rememberUpdatedState(modifier)
    val scrollableState = rememberUpdatedState(scrollable)
    val fractionState = rememberUpdatedState(maxHeightFraction)
    val closeState = rememberUpdatedState(showClose)
    val actionsState = rememberUpdatedState(actions)
    val footerState = rememberUpdatedState(footer)
    val contentState = rememberUpdatedState(content)
    val localsState = rememberUpdatedState(currentCompositionLocalContext)
    val groupState = rememberUpdatedState(LocalSheetGroup.current)
    val entry = remember {
        SheetEntry(titleState, dismissState, modifierState, scrollableState, fractionState, closeState, actionsState, footerState, contentState, localsState, groupState)
    }
    DisposableEffect(host, entry) {
        host.register(entry)
        onDispose { host.unregister(entry) }
    }
}

/** Widest a sheet panel gets (Material's bottom sheet default), centered on wide screens. */
private val HostedSheetMaxWidth = 640.dp

/** Smallest height a hosted panel keeps (title row + some body), room permitting. */
private val MinHostedPanel = 184.dp

/**
 * Renders the sheets of [state]: the top one as a translucent, rounded, half-height panel at the
 * bottom of this box, [bottomInset] above its bottom edge (the chrome it must not cover) or above
 * the keyboard, whichever is higher. Place it over the canvas, filling the area a panel may
 * cover; outside the panel it takes no touches. Every other sheet (and the top one while
 * minimized) stays composed but hidden, so it comes back unchanged. [onMinimize] runs for the
 * panel's own minimize button and its drag-down (by default it just minimizes).
 */
@Composable
fun SheetHost(state: SheetHostState, bottomInset: Dp, modifier: Modifier = Modifier, onMinimize: () -> Unit = state::minimize) {
    val entries = state.entries
    val focusManager = LocalFocusManager.current
    val minimized = state.minimized
    // A field being typed in commits (and the keyboard goes away) when its panel folds away or
    // another sheet covers it: keys must never go to a field nobody sees. (Launched before the
    // content's own effects, so a sheet that focuses its field when it opens still gets it.)
    val shownEntry = if (minimized) null else entries.lastOrNull()
    LaunchedEffect(shownEntry) { focusManager.clearFocus() }
    if (entries.isEmpty()) return
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(bottom = maxOf(bottomInset, imeBottom)),
    ) {
        val top = entries.last()
        for (entry in entries) {
            key(entry) {
                CompositionLocalProvider(entry.locals.value) {
                    HostedPanel(
                        entry = entry,
                        shown = entry === top && !minimized,
                        screenHeight = screenHeight,
                        onMinimize = onMinimize,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.HostedPanel(entry: SheetEntry, shown: Boolean, screenHeight: Dp, onMinimize: () -> Unit) {
    val title = entry.title.value
    val onDismiss = entry.onDismiss.value
    val footer = entry.footer.value
    val content = entry.content.value
    val actions = entry.actions.value
    val scrollable = entry.scrollable.value
    val wanted = screenHeight * entry.maxHeightFraction.value.coerceIn(0.2f, 1f)
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .widthIn(max = HostedSheetMaxWidth)
            .fillMaxWidth()
            // Hidden panels stay composed (their state is kept) but are neither measured nor
            // placed: not drawn, no touches, no semantics, and no layout work while the canvas
            // is used (a sheet that follows a dragged object recomposes on every move).
            .then(
                if (shown) {
                    Modifier.semantics { paneTitle = title; bwSheetTitle = title }
                } else {
                    Modifier.clearAndSetSemantics {}
                }
            )
            .layout { measurable, constraints ->
                if (!shown) return@layout layout(0, 0) {}
                // Half the screen (by default), but never more than the room this box has.
                val cap = minOf(constraints.maxHeight, wanted.roundToPx())
                    .coerceAtLeast(minOf(MinHostedPanel.roundToPx(), constraints.maxHeight))
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = cap))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .then(entry.modifier.value),
        shape = SheetShape,
        color = SheetBackground,
        contentColor = BrushworkColors.OnChrome,
    ) {
        Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, BrushworkColors.ChromeBorder.copy(alpha = 0.6f)), SheetShape)) {
            // Drag the handle (or the title row) down to minimize, like pulling a sheet away.
            val minimizeByDrag = Modifier.pointerInputMinimize(onMinimize)
            Box(Modifier.fillMaxWidth().then(minimizeByDrag), contentAlignment = Alignment.Center) { SheetHandle() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(minimizeByDrag)
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
                IconButton(onClick = onMinimize, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize")
                }
                if (entry.showClose.value) IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
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

private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

/** A downward drag of a few dp minimizes the panel. */
private fun Modifier.pointerInputMinimize(onMinimize: () -> Unit): Modifier = pointerInput(onMinimize) {
    val threshold = MinimizeDragDistance.toPx()
    var total = 0f
    var fired = false
    detectVerticalDragGestures(
        onDragStart = { total = 0f; fired = false },
        onVerticalDrag = { change, dy ->
            change.consume()
            total += dy
            if (!fired && total >= threshold) {
                fired = true
                onMinimize()
            }
        },
    )
}

private val MinimizeDragDistance = 24.dp

/**
 * The pill of a minimized sheet: "▴ Title" restores it, ✕ closes it (only for sheets that show a
 * ✕ themselves; the others, e.g. with Cancel / OK, are finished from the restored panel).
 * Nothing is shown unless the top sheet is minimized.
 */
@Composable
fun SheetPill(state: SheetHostState, modifier: Modifier = Modifier) {
    if (!state.minimized) return
    val top = state.entries.lastOrNull() ?: return
    val title = top.title.value
    val stacked = state.entries.size
    Surface(
        shape = RoundedCornerShape(50),
        color = BrushworkColors.ChromeHigh.copy(alpha = 0.96f),
        contentColor = BrushworkColors.OnChrome,
        border = BorderStroke(1.dp, BrushworkColors.ChromeBorder),
        shadowElevation = 4.dp,
        modifier = modifier.semantics { bwSheetPill = title },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clickable(onClickLabel = "Show $title", role = Role.Button, onClick = state::restore)
                    .heightIn(min = 44.dp)
                    .padding(start = 12.dp, end = if (top.showClose.value) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = BrushworkColors.Accent)
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                if (stacked > 1) {
                    // More menus wait under this one.
                    Text(
                        "+${stacked - 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrushworkColors.OnChromeDim,
                        modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = "${stacked - 1} more below" },
                    )
                }
            }
            if (top.showClose.value) {
                IconButton(onClick = { top.onDismiss.value.invoke() }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close $title")
                }
            }
        }
    }
}
