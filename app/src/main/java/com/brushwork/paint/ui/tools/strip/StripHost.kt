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
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.editor.blockCanvasTouches
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims

/**
 * The More row's open state (v1.8, design §3.9 (b)): per tool, for the session, never persisted.
 * `EditorScreen` hoists one and closes it on the chrome's events (D25); the host closes it on a
 * tool or vector-mode change, drops a tool's entry while its spec shows no More toggle ([drop]),
 * and reports the row's height in [moreRowDp], which moves the X / Y pill and the selection and
 * object bars down.
 *
 * The vector object bar's own More row ([StripLabels18.MORE_OBJECT], [objectRowOpen]) follows the
 * same rules: [anyOpen] counts it, [closeAll] closes it. One More row shows at a time: opening the
 * strip's closes the object bar's ([toggle]), and the other way round ([toggleObjectRow]).
 */
@Stable
class StripState {
    private val open = mutableStateMapOf<ToolId, Boolean>()

    /** The vector object bar's More row is open (`VectorObjectBar`; closed whenever the bar leaves). */
    var objectRowOpen by mutableStateOf(false)

    /** The object bar's toggle: opens its More row (closing the strip's) or closes it. */
    fun toggleObjectRow() {
        if (objectRowOpen) {
            objectRowOpen = false
        } else {
            if (open.isNotEmpty()) open.clear()
            objectRowOpen = true
        }
    }

    /** The More row's height (dp) as the host draws it now: 0 while it is closed or the strip is not shown. */
    var moreRowDp by mutableFloatStateOf(0f)
        internal set

    fun isOpen(id: ToolId): Boolean = open[id] == true

    /**
     * A More row shows: some tool's, or the object bar's (a canvas touch then only closes it, D25;
     * a two- or three-finger tap closes it and undoes or redoes, `CanvasView.onConsumeTouch`).
     */
    val anyOpen: Boolean get() = objectRowOpen || open.values.any { it }

    fun toggle(id: ToolId) {
        if (isOpen(id)) {
            open.remove(id)
        } else {
            open[id] = true
            if (objectRowOpen) objectRowOpen = false
        }
    }

    /**
     * Drops [id]'s open entry. The host calls it while [id]'s spec shows no More toggle (nothing
     * goes to the More row, or the strip got wide enough): a row that cannot show
     * must not stay open unseen, or [anyOpen] stays true and the next canvas touch only closes it (D25).
     */
    fun drop(id: ToolId) {
        if (open.containsKey(id)) open.remove(id)
    }

    fun closeAll() {
        if (open.isNotEmpty()) open.clear()
        if (objectRowOpen) objectRowOpen = false
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
 * a sheet or dialog opened from the More row outlives it. The row closes on a tool change and on
 * a vector-mode change; `EditorScreen` closes it on the rest of D25.
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
    LaunchedEffect(state, toolId, vectorMode) { state.closeAll() }
    // A strip that leaves the screen (a filter session, the interface hidden) takes its row along:
    // nothing open is left to swallow a canvas touch.
    DisposableEffect(state) {
        onDispose {
            state.closeAll()
            state.moreRowDp = 0f
        }
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
        val open = state.isOpen(toolId) && canOpen
        val moreDp = if (open) StripLayout.moreRowDp(lines.size) else 0f
        SideEffect {
            if (state.moreRowDp != moreDp) state.moreRowDp = moreDp
            // A row that can no longer show (the strip got wide enough) must not stay open unseen:
            // it would swallow the next canvas touch (D25).
            if (!canOpen) state.drop(toolId)
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
                MoreRow(lines, spec.hint, StripLayout.hintLineCount(split, measuredHint), inner)
            }
        }
    }
}

/**
 * The More row: up to [StripLayout.MAX_MORE_LINES] lines of 44 dp, 4 dp apart, the hint first;
 * opaque, with a 1 dp edge. The hint alone on its [hintLines] lines (see
 * [StripLayout.hintLineCount]) is one block that tall, its text in up to that many lines, then an
 * ellipsis.
 */
@Composable
private fun MoreRow(lines: List<List<StripItem>>, hint: String?, hintLines: Int, innerDp: Float) {
    val shape = RoundedCornerShape(IbisDims.OptionsStripRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IbisColors.Menu, shape)
            // A 1 dp edge sets the row apart from row 1 and from the canvas under it.
            .border(1.dp, BrushworkColors.ChromeBorder, shape)
            .blockCanvasTouches()
            .testTag(V18Tags.STRIP_MORE_ROW)
            .padding(horizontal = ROW_PADDING.dp),
    ) {
        lines.forEachIndexed { i, line ->
            // The hint's further lines are part of its block, drawn with its first.
            if (hint != null && i in 1 until hintLines) return@forEachIndexed
            if (i > 0) Spacer(Modifier.height(StripLayout.GAP_DP.dp))
            if (i == 0 && hint != null && line.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(StripLayout.moreRowDp(hintLines).dp), contentAlignment = Alignment.CenterStart) {
                    HintText(hint, hintLines, TextOverflow.Ellipsis)
                }
                return@forEachIndexed
            }
            // The hint shares its line only when MAX_MORE_LINES leaves no other.
            val hintHere = i == 0 && hint != null
            // Only the last line can be too wide (MAX_MORE_LINES reached): it scrolls sideways.
            val scrolls = hintHere || StripLayout.rowWidth(line) > innerDp + 0.5f
            Row(
                Modifier
                    .fillMaxWidth()
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
                    key(item.key) { ItemCell(item) }
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
 * keeps its state (an open popup or dropdown stays open).
 */
@Composable
private fun ItemCell(item: StripItem) {
    Box(Modifier.width(item.width), contentAlignment = Alignment.Center) {
        if (item.shown) item.content()
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
