package com.brushwork.paint.ui.tools.strip

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * tool or vector-mode change and reports the row's height in [moreRowDp], which moves the X / Y
 * pill and the selection and object bars down.
 */
@Stable
class StripState {
    private val open = mutableStateMapOf<ToolId, Boolean>()

    /** The More row's height (dp) as the host draws it now: 0 while it is closed or the strip is not shown. */
    var moreRowDp by mutableFloatStateOf(0f)
        internal set

    fun isOpen(id: ToolId): Boolean = open[id] == true

    /** The More row shows for some tool (a canvas touch then only closes it, D25). */
    val anyOpen: Boolean get() = open.values.any { it }

    fun toggle(id: ToolId) {
        if (isOpen(id)) open.remove(id) else open[id] = true
    }

    fun closeAll() {
        if (open.isNotEmpty()) open.clear()
    }
}

/**
 * The options strip (v1.8, design §3.9 and §4.6): row 1 and the More row of [spec], the active
 * tool's ([toolId]).
 *
 * - A legacy spec ([StripSpec.legacyContent], every tool until its area converts it) draws exactly
 *   as v1.7 did: the "VECTOR" chip and the tool's composable in one row that scrolls sideways,
 *   with no toggle and no More row. [modifier] is that row's, [panelModifier] its container's.
 * - A converted spec draws row 1 as one fixed row that never scrolls (I18): the VECTOR cell while
 *   [vectorMode], the items [StripLayout.split] keeps, and the toggle at the end when anything
 *   goes to the More row. The More row (opaque [IbisColors.Menu], tag [V18Tags.STRIP_MORE_ROW])
 *   drops down under row 1, 4 dp apart, while [state] has it open for [toolId].
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
    val legacy = spec.legacyContent
    if (legacy != null) {
        SideEffect { if (state.moreRowDp != 0f) state.moreRowDp = 0f }
        Box(panelModifier) {
            Row(
                modifier = modifier
                    .heightIn(min = IbisDims.OptionsStripHeight)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = ROW_PADDING.dp)
                    .reportContentWidth(onContentWidth),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Vector mode (v1.5): what is drawn stays editable.
                if (vectorMode) LegacyVectorChip()
                legacy()
            }
        }
    } else {
        ConvertedStrip(spec, toolId, vectorMode, state, modifier, panelModifier, onContentWidth)
    }
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
        val lines = StripLayout.moreLines(split, spec.hint, inner)
        val open = state.isOpen(toolId) && split.toggle && lines.isNotEmpty()
        val moreDp = if (open) StripLayout.moreRowDp(lines.size) else 0f
        SideEffect { if (state.moreRowDp != moreDp) state.moreRowDp = moreDp }
        if (debuggable) {
            val budget = inner - chip - if (split.toggle) StripLayout.TOGGLE_DP + StripLayout.GAP_DP else 0f
            check(StripLayout.rowWidth(split.row1) <= budget + 0.5f) {
                "Row 1 of ${toolId.name} is ${StripLayout.rowWidth(split.row1)} dp wide, over its $budget dp (I18)"
            }
        }
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
                    var first = true
                    if (vectorMode) {
                        VectorCell()
                        first = false
                    }
                    for (item in split.row1) {
                        if (!first) Spacer(Modifier.width(StripLayout.GAP_DP.dp))
                        first = false
                        ItemCell(item)
                    }
                    if (split.toggle) {
                        if (!first) Spacer(Modifier.width(StripLayout.GAP_DP.dp))
                        // The toggle sits at row 1's end, the same place for every tool.
                        Spacer(Modifier.weight(1f))
                        MoreToggle(open) { state.toggle(toolId) }
                    }
                }
            }
            if (open) {
                Spacer(Modifier.height(StripLayout.GAP_DP.dp))
                MoreRow(lines, spec.hint, inner)
            }
        }
    }
}

/** The More row: up to [StripLayout.MAX_MORE_LINES] lines of 44 dp, 4 dp apart, the hint first. */
@Composable
private fun MoreRow(lines: List<List<StripItem>>, hint: String?, innerDp: Float) {
    val shape = RoundedCornerShape(IbisDims.OptionsStripRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IbisColors.Menu, shape)
            .blockCanvasTouches()
            .testTag(V18Tags.STRIP_MORE_ROW)
            .padding(horizontal = ROW_PADDING.dp),
    ) {
        lines.forEachIndexed { i, line ->
            if (i > 0) Spacer(Modifier.height(StripLayout.GAP_DP.dp))
            val hintHere = i == 0 && hint != null
            // Only the last line can be too wide (MAX_MORE_LINES reached): it scrolls sideways.
            val scrolls = hintHere && line.isNotEmpty() || StripLayout.rowWidth(line) > innerDp + 0.5f
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(StripLayout.LINE_DP.dp)
                    .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var first = true
                if (hintHere) {
                    Text(
                        hint.orEmpty(),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = if (line.isEmpty()) TextOverflow.Ellipsis else TextOverflow.Clip,
                    )
                    first = false
                }
                for (item in line) {
                    if (!first) Spacer(Modifier.width(StripLayout.GAP_DP.dp))
                    first = false
                    ItemCell(item)
                }
            }
        }
    }
}

/** One item at its declared width; a hidden item keeps its room (neighbours never jump). */
@Composable
private fun ItemCell(item: StripItem) {
    Box(Modifier.width(item.width), contentAlignment = Alignment.Center) {
        if (item.shown) item.content()
    }
}

/** The toggle ([StripLabels18.MORE_TOGGLE]): 44 dp, the one control every strip with a More row shares. */
@Composable
private fun MoreToggle(open: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(StripLayout.TOGGLE_DP.dp)
            .clip(RoundedCornerShape(8.dp))
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

/** v1.7's accent "VECTOR" chip at the start of a legacy strip, unchanged. */
@Composable
private fun LegacyVectorChip() {
    Text(
        "VECTOR",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BrushworkColors.AccentDim)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = VECTOR_MODE_ON },
    )
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

/** The rows' side padding (dp): row 1's content width is the panel's minus twice this. */
private const val ROW_PADDING = 8f

/** The v1.7 description of the vector-mode chip (V21), unchanged. */
private const val VECTOR_MODE_ON = "Vector mode is on"
