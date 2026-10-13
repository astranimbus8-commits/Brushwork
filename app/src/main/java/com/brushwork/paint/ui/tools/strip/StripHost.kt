package com.brushwork.paint.ui.tools.strip

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.IconLongPressScope
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims

/**
 * The More rows' state (v1.9, R3 "sticky rows"; v1.8 design §3.9 (b)): which rows the user
 * opened, remembered per tool and across relaunches, and what hides them for now.
 *
 * Memory (fact V27): only the explicit taps write it. [toggle] (the strip's More toggle) writes
 * [UiPrefs.stripOpenRows], [toggleObjectRow] (the vector object bar's toggle) writes
 * [UiPrefs.objectBarMoreOpen]; both load at construction. Without [prefs] (a test's rig, a bar
 * on its own) the memory lasts as long as this state. History taps never touch it (I26).
 *
 * Hiding never forgets:
 * - [closeAll] hides a remembered row until its tool is entered again ([enter]) or its toggle
 *   opens it; one row at a time (the strip's toggle opening while the object bar's row shows)
 *   hides the object bar's row likewise.
 * - [drop] hides a tool's row while its spec has no More toggle; it shows again as soon as the
 *   spec has one ([undrop]: Transform's next transformation, a narrower window).
 * - [hiddenByChrome] (`EditorScreen`: the layers panel, a panel or sheet, the tool menu, "hide
 *   interface") hides every row while it is true; a toggle tap meanwhile shows its row anyway.
 * - While objects are selected and the object bar's row is open ([objectRowOpen]), the tool's
 *   row is hidden: when the objects are deselected or that row closes, it shows again.
 *
 * `EditorScreen` hoists one. The host applies the memory on each tool entry, hides a tool's row
 * while its spec shows no More toggle ([drop], [undrop]) and reports the row's height in [moreRowDp], which
 * moves the X / Y pill and the selection and object bars down. A canvas touch never closes a row
 * ([canvasTouchDown], [canvasTouchMoved]: the rows let it through and fade).
 */
@Stable
class StripState(private val prefs: UiPrefs? = null) {
    /** The tools whose More row the user opened (the memory). */
    private val remembered = mutableStateMapOf<ToolId, Boolean>()

    /** Remembered names that are no [ToolId] of this version: written back as they were. */
    private val unknown: Set<String>

    init {
        val names = prefs?.stripOpenRows.orEmpty()
        val known = names.mapNotNull { name -> ToolId.entries.firstOrNull { it.name == name } }
        known.forEach { remembered[it] = true }
        unknown = names - known.map { it.name }.toSet()
    }

    /** Remembered rows hidden for now ([closeAll]) until their tool's next [enter] or toggle. */
    private val hidden = mutableStateMapOf<ToolId, Boolean>()

    /** Tools whose spec shows no More toggle now ([drop]): hidden until it has one again ([undrop]). */
    private val dropped = mutableStateMapOf<ToolId, Boolean>()

    /** The object bar's row is remembered open. */
    private var objectRemembered by mutableStateOf(prefs?.objectBarMoreOpen == true)

    /** The object bar's row is hidden for now ([closeAll], one row at a time) until the next [enter], the bar's next showing or its toggle. */
    private var objectHidden by mutableStateOf(false)

    /** The tool [enter] last applied the memory for (null before, and after the host left). */
    private var entered by mutableStateOf<ToolId?>(null)

    /**
     * The vector object bar shows its More toggle (`VectorObjectBar` reports it while it shows):
     * only then can its row take precedence over the tool's.
     */
    var objectBarToggle by mutableStateOf(false)
        private set

    /**
     * v1.9 (R3): a chrome surface covers the rows (`EditorScreen`: the layers panel, a panel or
     * sheet, the tool menu, "hide interface"). They hide, remembered, and show again when it
     * turns false. A toggle tap meanwhile clears it: the user asked for that row.
     */
    var hiddenByChrome by mutableStateOf(false)

    /**
     * v1.9 (R3): a canvas touch is down, from its first pointer down until the last lift
     * (`CanvasView.onTouchDown` / `onTouchEnd`). The rows' controls let every pointer through
     * meanwhile, so a finger that lands on a row joins the canvas gesture.
     */
    var canvasTouchDown by mutableStateOf(false)
        private set

    /** v1.9 (R3): that canvas touch moved past the touch slop (`CanvasView.onTouchSlop`): the rows fade to [ROW_FADE_ALPHA]. */
    var canvasTouchMoved by mutableStateOf(false)
        private set

    /** The More row's height (dp) as the host draws it now: 0 while it is closed or the strip is not shown. */
    var moreRowDp by mutableFloatStateOf(0f)
        internal set

    /**
     * The vector object bar's More row shows: remembered open, not hidden, the bar shows its
     * toggle and nothing covers it. The tool's row is hidden meanwhile.
     */
    val objectRowOpen: Boolean get() = objectBarToggle && objectRowShows()

    /** The object bar's row shows whenever the bar shows its toggle (`VectorObjectBar` reads it for its own row). */
    fun objectRowShows(): Boolean = objectRemembered && !objectHidden && !hiddenByChrome

    /** [id]'s row shows: remembered open, not hidden, nothing covers it and the object bar's row does not show. */
    fun isOpen(id: ToolId): Boolean = shows(id, hides = true)

    /**
     * What the host shows for [id], the active tool: [isOpen], except that before [enter] ran for
     * [id] its hides do not count yet. So the memory shows from the tool's first frame, and the
     * entry's [closeAll] then memory in one effect changes nothing on screen (no flicker).
     */
    internal fun showsFor(id: ToolId): Boolean = shows(id, hides = entered == id)

    private fun shows(id: ToolId, hides: Boolean): Boolean =
        remembered[id] == true && !(hides && hidden[id] == true) && dropped[id] != true && !hiddenByChrome && !objectRowOpen

    /** [id]'s row is remembered open (shown or not). */
    fun remembers(id: ToolId): Boolean = remembered[id] == true

    /** The object bar's row is remembered open (shown or not). */
    val objectRowRemembered: Boolean get() = objectRemembered

    /** A More row shows: some tool's, or the object bar's. */
    val anyOpen: Boolean
        get() = objectRowOpen || (!hiddenByChrome && remembered.keys.any { hidden[it] != true && dropped[it] != true })

    /**
     * The strip's More toggle for [id]: closes its row (forgets it) or opens it (remembers it,
     * shows it whatever hid it, and hides the object bar's row: one row at a time). Writes
     * [UiPrefs.stripOpenRows].
     */
    fun toggle(id: ToolId) {
        if (isOpen(id)) {
            remembered.remove(id)
        } else {
            remembered[id] = true
            if (hiddenByChrome) hiddenByChrome = false
            if (objectRowOpen) objectHidden = true
        }
        if (hidden.containsKey(id)) hidden.remove(id)
        prefs?.stripOpenRows = remembered.keys.mapTo(unknown.toHashSet()) { it.name }
    }

    /**
     * The object bar's toggle: closes its row (forgets it) or opens it (remembers it and shows
     * it; the tool's row is hidden meanwhile, never forgotten). Writes [UiPrefs.objectBarMoreOpen].
     */
    fun toggleObjectRow() {
        if (objectRowShows()) {
            objectRemembered = false
        } else {
            objectRemembered = true
            objectHidden = false
            if (hiddenByChrome) hiddenByChrome = false
        }
        prefs?.objectBarMoreOpen = objectRemembered
    }

    /**
     * Hides [id]'s row, remembered: the host calls it while [id]'s spec shows no More toggle
     * (nothing goes to the More row, or the strip got wide enough), and [undrop] once the spec
     * shows one again, when the remembered row shows again.
     */
    fun drop(id: ToolId) {
        if (dropped[id] != true) dropped[id] = true
    }

    /** [id]'s spec shows its More toggle again: the hide of [drop] ends. */
    internal fun undrop(id: ToolId) {
        if (dropped.containsKey(id)) dropped.remove(id)
    }

    /** Hides every row, remembered (v1.9: nothing is forgotten), until the next [enter] or a toggle. */
    fun closeAll() {
        for (id in remembered.keys) if (hidden[id] != true) hidden[id] = true
        if (objectRemembered && !objectHidden) objectHidden = true
    }

    /**
     * A tool entry (the host's effect on a tool or vector-mode change): [closeAll], then the
     * memory of [id] and of the object bar's row shows again, in one snapshot.
     */
    fun enter(id: ToolId) {
        closeAll()
        if (hidden.containsKey(id)) hidden.remove(id)
        if (objectHidden) objectHidden = false
        entered = id
    }

    /** The host left the screen (a filter, the interface hidden): it hides, and its next entry shows the memory from its first frame. */
    internal fun left() {
        closeAll()
        entered = null
        moreRowDp = 0f
    }

    /** `VectorObjectBar` shows, with its More toggle when [toggle]. */
    fun objectBarShown(toggle: Boolean) {
        if (objectBarToggle != toggle) objectBarToggle = toggle
    }

    /** `VectorObjectBar` left (the objects deselected): the tool's row shows again, and the bar's next showing starts from the memory. */
    fun objectBarGone() {
        if (objectBarToggle) objectBarToggle = false
        if (objectHidden) objectHidden = false
    }

    /** A canvas touch started (its first pointer down). */
    fun canvasTouchStarted() {
        if (!canvasTouchDown) canvasTouchDown = true
        if (canvasTouchMoved) canvasTouchMoved = false
    }

    /** The canvas touch moved past the touch slop. */
    fun canvasTouchSlop() {
        if (canvasTouchDown && !canvasTouchMoved) canvasTouchMoved = true
    }

    /** The canvas touch ended (its last pointer lifted, or it was cancelled). */
    fun canvasTouchEnded() {
        if (canvasTouchDown) canvasTouchDown = false
        if (canvasTouchMoved) canvasTouchMoved = false
    }

    companion object {
        /** The rows' alpha while a canvas touch that moved past the touch slop is down (R3 (a)). */
        const val ROW_FADE_ALPHA = 0.25f
    }
}

/**
 * The options strip (v1.8, design §3.9 and §4.6): row 1 and the More row of [spec], the active
 * tool's ([toolId]).
 *
 * Row 1 is one row: the VECTOR cell while [vectorMode], the items [StripLayout.split] keeps, and
 * the toggle at the end when anything goes to the More row. While the window is
 * [FIXED_ROW1_MIN_DP] wide or wider it is fixed and never scrolls (I18; a debug build checks that
 * it fits its budget). In a narrower window (a larger Display size) the budgets do not hold: the
 * VECTOR cell and the items scroll sideways in the room the toggle leaves, and the toggle stays
 * pinned at the end, in view.
 * [modifier] is row 1's, [panelModifier] its container's. The More row (opaque [IbisColors.Menu],
 * tag [V18Tags.STRIP_MORE_ROW]) drops down under row 1, 4 dp apart, while [state] has it open for
 * [toolId].
 *
 * [StripSpec.overlays] are composed whenever the strip shows, after the rows, open or closed, so
 * a sheet or dialog opened from the More row outlives it. v1.9 (R3): the row opens and closes
 * only through its toggle; a tool or vector-mode change shows the new tool's remembered row
 * ([StripState.enter]), and `EditorScreen` hides it under the chrome ([StripState.hiddenByChrome]).
 *
 * [onContentWidth] receives the width (px) of what row 1 shows, at layout time (0 = nothing: the
 * panel hides itself then).
 */
@Composable
fun StripHost(
    spec: StripSpec,
    toolId: ToolId,
    vectorMode: Boolean,
    state: StripState,
    modifier: Modifier = Modifier,
    panelModifier: Modifier = Modifier,
    onContentWidth: ((Int) -> Unit)? = null,
) {
    // v1.9 (R3): closeAll, then the new tool's memory, in one effect (nothing flickers).
    LaunchedEffect(state, toolId, vectorMode) { state.enter(toolId) }
    // A strip that leaves the screen (a filter session, the interface hidden) hides its row,
    // remembered: it shows again when the strip comes back.
    DisposableEffect(state) {
        onDispose { state.left() }
    }
    ConvertedStrip(spec, toolId, vectorMode, state, modifier, panelModifier, onContentWidth)
    spec.overlays()
}

@Composable
private fun ConvertedStrip(
    spec: StripSpec,
    toolId: ToolId,
    vectorMode: Boolean,
    state: StripState,
    modifier: Modifier,
    panelModifier: Modifier,
    onContentWidth: ((Int) -> Unit)?,
) {
    val screenDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val debuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    BoxWithConstraints {
        val widthDp = if (constraints.hasBoundedWidth) maxWidth.value else screenDp
        val inner = widthDp - 2 * ROW_PADDING
        val chip = if (vectorMode) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
        val split = StripLayout.split(spec, inner - chip)
        // The hint's lines as the More row lays it out (its text style, the row's content width).
        val hintPx = if (constraints.hasBoundedWidth) {
            constraints.maxWidth - 2 * with(LocalDensity.current) { ROW_PADDING.dp.roundToPx() }
        } else {
            with(LocalDensity.current) { inner.dp.roundToPx() }
        }
        val measuredHint = rememberHintLines(spec.hint, hintPx)
        val lines = StripLayout.moreLines(split, spec.hint, inner, measuredHint)
        val canOpen = split.toggle && lines.isNotEmpty()
        val open = state.showsFor(toolId) && canOpen
        val moreDp = if (open) StripLayout.moreRowDp(lines.size) else 0f
        SideEffect {
            if (state.moreRowDp != moreDp) state.moreRowDp = moreDp
            // A row that can no longer show (the strip got wide enough, nothing left for it) hides,
            // remembered (v1.9, R3): it shows again once the spec has a More toggle again.
            if (!canOpen) state.drop(toolId) else state.undrop(toolId)
        }
        // Below FIXED_ROW1_MIN_DP (display-size scaling) the design's budgets do not hold: row 1 scrolls.
        val narrow = screenDp < FIXED_ROW1_MIN_DP
        val toggleDp = if (split.toggle) StripLayout.TOGGLE_DP + StripLayout.GAP_DP else 0f
        if (debuggable && !narrow) {
            val budget = inner - chip - toggleDp
            check(StripLayout.rowWidth(split.row1) <= budget + 0.5f) {
                "Row 1 of ${toolId.name} is ${StripLayout.rowWidth(split.row1)} dp wide, over its $budget dp (I18)"
            }
        }
        // The scroll starts over for each tool and vector mode.
        val row1Scroll = key(toolId, vectorMode) { rememberScrollState() }
        Column {
            Box(panelModifier) {
                Row(
                    modifier = modifier
                        .heightIn(min = IbisDims.OptionsStripHeight)
                        .testTag(V18Tags.STRIP_ROW1)
                        .padding(horizontal = ROW_PADDING.dp)
                        .reportContentWidth(onContentWidth),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!narrow) {
                        Row1Cells(vectorMode, split.row1)
                    } else {
                        // Too narrow for the design's budgets: the cells scroll sideways, the
                        // toggle stays pinned at the end, in view.
                        Row(
                            Modifier
                                .widthIn(max = (inner - toggleDp).coerceAtLeast(0f).dp)
                                .horizontalScroll(row1Scroll),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Row1Cells(vectorMode, split.row1) }
                    }
                    if (split.toggle) {
                        if (vectorMode || split.row1.isNotEmpty()) Spacer(Modifier.width(StripLayout.GAP_DP.dp))
                        // The toggle sits at row 1's end, the same place for every tool.
                        Spacer(Modifier.weight(1f))
                        MoreToggle(open) { state.toggle(toolId) }
                    }
                }
            }
            if (open) {
                Spacer(Modifier.height(StripLayout.GAP_DP.dp))
                MoreRow(state, lines, spec.hint, StripLayout.hintLineCount(split, measuredHint), inner)
            }
        }
    }
}

/**
 * The More row: up to [StripLayout.MAX_MORE_LINES] lines of 44 dp, 4 dp apart, the hint first.
 * The hint alone on its [hintLines] lines (see [StripLayout.hintLineCount]) is one block that
 * tall, its text in up to that many lines, then an ellipsis.
 *
 * v1.9 (R3): the row stays open over the canvas, so it covers as little of it as it can. Each
 * line (and the hint's block) is opaque only behind its own items, with a 1 dp edge, end-aligned
 * under the toggle; only a line too wide for the strip (it scrolls sideways) spans the strip. The
 * band itself (tag [V18Tags.STRIP_MORE_ROW], the strip's width, the same height as before) has
 * no pointer input: a touch beside a line, in the 4 dp gaps or on a line's background between
 * its controls reaches the canvas. From a canvas pointer down until the last lift the controls
 * take no touch ([passCanvasGesture]), and once that gesture passes the touch slop the row fades
 * to [StripState.ROW_FADE_ALPHA] (drawn only, never recomposed), back when the last finger lifts.
 */
@Composable
private fun MoreRow(state: StripState, lines: List<List<StripItem>>, hint: String?, hintLines: Int, innerDp: Float) {
    val shape = RoundedCornerShape(IbisDims.OptionsStripRadius)
    // A 1 dp edge sets each line apart from row 1 and from the canvas under it.
    val lineSurface = Modifier
        .clip(shape)
        .background(IbisColors.Menu, shape)
        .border(1.dp, BrushworkColors.ChromeBorder, shape)
        .padding(horizontal = ROW_PADDING.dp)
    val guard = Modifier.passCanvasGesture(state)
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (state.canvasTouchMoved) StripState.ROW_FADE_ALPHA else 1f }
            .testTag(V18Tags.STRIP_MORE_ROW),
        horizontalAlignment = Alignment.End,
    ) {
        lines.forEachIndexed { i, line ->
            // The hint's further lines are part of its block, drawn with its first.
            if (hint != null && i in 1 until hintLines) return@forEachIndexed
            if (i > 0) Spacer(Modifier.height(StripLayout.GAP_DP.dp))
            if (i == 0 && hint != null && line.isEmpty()) {
                Box(lineSurface.height(StripLayout.moreRowDp(hintLines).dp), contentAlignment = Alignment.CenterStart) {
                    HintText(hint, hintLines, TextOverflow.Ellipsis)
                }
                return@forEachIndexed
            }
            // The hint shares its line only when MAX_MORE_LINES leaves no other.
            val hintHere = i == 0 && hint != null
            // Only the last line can be too wide (MAX_MORE_LINES reached): it scrolls sideways.
            val scrolls = hintHere || StripLayout.rowWidth(line) > innerDp + 0.5f
            Row(
                (if (scrolls) Modifier.fillMaxWidth() else Modifier)
                    .then(lineSurface)
                    .height(StripLayout.LINE_DP.dp)
                    .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var first = true
                if (hintHere) {
                    HintText(hint.orEmpty(), 1, TextOverflow.Clip)
                    first = false
                }
                for (item in line) {
                    if (!first) Spacer(Modifier.width(StripLayout.GAP_DP.dp))
                    first = false
                    key(item.key) { ItemCell(item, guard) }
                }
            }
        }
    }
}

/**
 * v1.9 (R3): while a canvas gesture is down ([StripState.canvasTouchDown]) a pointer that lands on
 * a More-row control is consumed before the control sees it (a clickable, a slider or a dropdown
 * ignores consumed events), to its lift: it only joins the canvas' gesture (the canvas view gets
 * every pointer of the screen, so a second finger there pinches or pans). Pointers that landed
 * before the canvas gesture keep working. Put on a control's own bounds only, never on a line, so
 * a line's background between controls passes touches to the canvas.
 */
internal fun Modifier.passCanvasGesture(state: StripState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        val joined = HashSet<PointerId>()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) {
                if (change.changedToDownIgnoreConsumed() && state.canvasTouchDown) joined += change.id
                if (change.id in joined) {
                    change.consume()
                    if (change.changedToUpIgnoreConsumed()) joined -= change.id
                }
            }
        }
    }
}

/** The hint's text, [HINT_SP] in the theme's style. */
@Composable
private fun HintText(hint: String, maxLines: Int, overflow: TextOverflow) {
    Text(hint, color = Color.White.copy(alpha = 0.8f), fontSize = HINT_SP, maxLines = maxLines, overflow = overflow)
}

/**
 * The lines [hint] takes on the More row: laid out as [HintText] lays it out ([HINT_SP] in the
 * theme's text style) in [widthPx], at most [StripLayout.MAX_HINT_LINES]; 1 without a hint. The
 * host passes it to [StripLayout.moreLines]; tests measure a hint at another width the same way.
 */
@Composable
internal fun rememberHintLines(hint: String?, widthPx: Int): Int {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = HINT_SP))
    return remember(hint, widthPx, style, measurer) {
        if (hint == null || widthPx <= 0) {
            1
        } else {
            measurer.measure(
                hint,
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = StripLayout.MAX_HINT_LINES,
                constraints = Constraints(maxWidth = widthPx),
            ).lineCount
        }
    }
}

/**
 * One item at its declared width; a hidden item keeps its room (neighbours never jump). Each cell
 * is composed under key(item.key), so an item inserted or removed before it in the same line
 * keeps its state (an open popup or dropdown stays open). Its icon-only controls show their name
 * on a long press ([IconLongPressScope], v1.8 polish). [guard] wraps the shown control itself,
 * not its cell (the More row's [passCanvasGesture]).
 */
@Composable
private fun ItemCell(item: StripItem, guard: Modifier = Modifier) {
    Box(Modifier.width(item.width), contentAlignment = Alignment.Center) {
        if (item.shown) {
            if (guard === Modifier) {
                IconLongPressScope { item.content() }
            } else {
                Box(guard, contentAlignment = Alignment.Center) { IconLongPressScope { item.content() } }
            }
        }
    }
}

/**
 * The toggle ([StripLabels18.MORE_TOGGLE]): 44 dp, the one control every strip with a More row shares.
 * While the row is open the toggle is filled with the accent (the chevron points up); a long press
 * shows its label in a bubble and never toggles (v1.8, design §3.9).
 */
@Composable
private fun MoreToggle(open: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(StripLayout.TOGGLE_DP.dp)
            .clip(shape)
            .background(if (open) IbisColors.Accent else Color.Transparent, shape)
            .longPressLabel(StripLabels18.MORE_TOGGLE)
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = StripLabels18.MORE_TOGGLE
                stateDescription = if (open) StripLabels18.EXPANDED else StripLabels18.COLLAPSED
                role = Role.Button
            }
            .testTag(V18Tags.STRIP_TOGGLE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Color.White)
    }
}

/** Row 1's cells: the VECTOR cell while [vectorMode], then [items], [StripLayout.GAP_DP] apart. */
@Composable
private fun Row1Cells(vectorMode: Boolean, items: List<StripItem>) {
    var first = true
    if (vectorMode) {
        VectorCell()
        first = false
    }
    for (item in items) {
        if (!first) Spacer(Modifier.width(StripLayout.GAP_DP.dp))
        first = false
        // Keyed: an item that comes or goes before an open popup never resets it.
        key(item.key) { ItemCell(item) }
    }
}

/** The VECTOR cell of a converted row 1 (D30): a 40 dp accent icon cell, not clickable. */
@Composable
private fun VectorCell() {
    Box(
        Modifier
            .size(StripLayout.VECTOR_CHIP_DP.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BrushworkColors.AccentDim)
            .longPressLabel(VECTOR_MODE_ON)
            .semantics { contentDescription = VECTOR_MODE_ON },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Polyline, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Reports the content's own width (no minimum, before padding) to [onContentWidth] while it is measured. */
private fun Modifier.reportContentWidth(onContentWidth: ((Int) -> Unit)?): Modifier =
    if (onContentWidth == null) {
        this
    } else {
        layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0))
            onContentWidth(p.width)
            layout(maxOf(p.width, constraints.minWidth), p.height) { p.place(0, 0) }
        }
    }

/**
 * The narrowest window (dp, `screenWidthDp`) the design's row-1 budgets are for (§3.9's table). At
 * this width and wider row 1 is fixed and a debug build checks that it fits (I18); below it (a
 * larger Display size) row 1's cells scroll sideways.
 */
private const val FIXED_ROW1_MIN_DP = 360f

/** The hint's text size on the More row. */
private val HINT_SP = 13.sp

/** The rows' side padding (dp): row 1's content width is the panel's minus twice this. */
private const val ROW_PADDING = 8f

/** The v1.7 description of the vector-mode chip (V21), the VECTOR cell's, unchanged. */
private const val VECTOR_MODE_ON = "Vector mode is on"
