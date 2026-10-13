package com.brushwork.paint.ui.vector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripState
import com.brushwork.paint.ui.tools.strip.passCanvasGesture
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits
import kotlin.math.floor
import kotlin.math.max

/**
 * The floating bar of selected vector objects (Delete, Duplicate, arrange, Recolor, Transform,
 * Deselect; v1.5 §4.9, owned by A2). The editor shows it instead of the selection bar while
 * `controller.vectors.selectedIds` is not empty. Every edit is one undo step
 * ([ObjectActions]); Recolor paints lines and fills with the main color, a long press only the
 * lines. v1.7 adds "Array" (an array from the selected objects) after Deselect; v1.8 adds
 * detaching live mirror copies after it ([ObjectActions.detachCopies]), shown only while a
 * selected object has copies.
 *
 * v1.8 (design §3.9): one row that never scrolls. When the buttons don't all fit it at 40 dp
 * ([ObjectBarLayout]), the rarer ones leave it first, by rank (Array, the detach button, Forward
 * and Backward, Front and Back, ...); row 1 keeps the others in their v1.6 order and ends with
 * the toggle [StripLabels18.MORE_OBJECT]. The ones that left wait,
 * in their order, on the bar's More row, opaque ([IbisColors.Menu]), under the bar. On the user's
 * 392 dp phone (and at 360 dp) row 1 holds Delete, Duplicate, Front, Back, Recolor, Transform and
 * Deselect; Forward, Backward, Array and the detach button are one tap away. The More row's open
 * state is [state]'s ([StripState.objectRowOpen], the options strip's state). v1.9 (R3): the same
 * rules as the strip's row apply: only its toggle opens and closes it, remembered in
 * `UiPrefs.objectBarMoreOpen`; a canvas touch never closes it, the chrome covers only hide it.
 * While it shows, the strip's row hides (remembered) and shows again when the bar leaves or this
 * row closes; the strip's toggle opening hides this row in turn (one More row at a time). A long
 * press on a button shows what it does in a bubble (Recolor keeps its own long press).
 *
 * Selecting another layer drops the object selection (the objects of a layer that isn't being
 * worked on are not acted on).
 */
@Composable
fun VectorObjectBar(controller: EditorController, modifier: Modifier, state: StripState? = null) {
    val strip = state ?: remember { StripState() }
    val v = controller.vectors
    val count by remember(controller) { derivedStateOf { v.selectedIds.size } }
    val selectedLayer by remember(controller) { derivedStateOf { v.selectedLayer } }
    val activeLayer by remember(controller) { derivedStateOf { controller.layersVersion; controller.activeLayer } }
    // v1.8: whether a selected object has live mirror copies (the detach button shows).
    val hasCopies by remember(controller) { derivedStateOf { controller.layersVersion; ObjectActions.hasCopiesSelected(controller) } }
    LaunchedEffect(selectedLayer, activeLayer) {
        if (selectedLayer != null && selectedLayer !== activeLayer) v.setSelection(null, emptySet())
    }
    // v1.9 (R3): the bar leaving (the objects deselected, a filter, the interface hidden) hides its
    // More row, remembered; the strip's row shows again.
    DisposableEffect(strip) { onDispose { strip.objectBarGone() } }
    // The swatch shows the color as this document will paint it (grayscale / 1-bit documents).
    val color = Color(ColorModeOps.displayColor(controller.color, controller.doc.colorMode))
    fun run(action: () -> Unit) {
        controller.endCanvasGesture()
        action()
    }
    val summary = if (count == 1) "1 object selected" else "$count objects selected"
    val buttons = buildList {
        add(BarButton(1, RANK_DELETE) { w -> ObjectBarItem(Icons.Outlined.Delete, "Delete", "Delete the selected objects", w) { run { ObjectActions.delete(controller) } } })
        add(BarButton(1, RANK_DUPLICATE) { w ->
            ObjectBarItem(Icons.Outlined.ControlPointDuplicate, "Duplicate", "Duplicate the selected objects", w) { run { ObjectActions.duplicate(controller) } }
        })
        add(BarButton(1, RANK_STEP) { w ->
            ObjectBarItem(Icons.Outlined.FlipToFront, "Forward", "Bring forward", w) { run { ObjectActions.arrange(controller, ObjectEdits.Arrange.FORWARD) } }
        })
        add(BarButton(1, RANK_STEP) { w ->
            ObjectBarItem(Icons.Outlined.FlipToBack, "Backward", "Send backward", w) { run { ObjectActions.arrange(controller, ObjectEdits.Arrange.BACKWARD) } }
        })
        add(BarButton(1, RANK_ENDS) { w ->
            ObjectBarItem(Icons.Outlined.VerticalAlignTop, "Front", "Bring to front", w) { run { ObjectActions.arrange(controller, ObjectEdits.Arrange.FRONT) } }
        })
        add(BarButton(1, RANK_ENDS) { w ->
            ObjectBarItem(Icons.Outlined.VerticalAlignBottom, "Back", "Send to back", w) { run { ObjectActions.arrange(controller, ObjectEdits.Arrange.BACK) } }
        })
        add(BarButton(1, RANK_RECOLOR) { w ->
            ObjectBarItem(
                Icons.Outlined.FormatColorFill, "Recolor", "Recolor with the main color", w,
                swatch = color,
                longClickLabel = "Recolor the lines only",
                onLongClick = { run { ObjectActions.recolor(controller, linesOnly = true) } },
            ) {
                run { ObjectActions.recolor(controller, linesOnly = false) }
            }
        })
        add(BarButton(1, RANK_TRANSFORM) { w ->
            ObjectBarItem(EditorIcons.tool(ToolId.TRANSFORM), "Transform", "Transform the selected objects", w) { run { ObjectActions.transform(controller) } }
        })
        add(BarButton(1, RANK_DESELECT) { w -> ObjectBarItem(Icons.Outlined.Deselect, "Deselect", "Deselect the objects", w) { run { ObjectActions.deselect(controller) } } })
        // v1.7 (item 3): after Deselect, so the nine v1.6 buttons keep their places and sizes.
        add(BarButton(1, RANK_ARRAY) { w ->
            ObjectBarItem(EditorIcons.tool(ToolId.ARRAY), ArrayLabels.BUTTON, ArrayLabels.FROM_OBJECTS, w) {
                run { controller.arrayFromObjects(v.selectedIds.toSet()) }
            }
        })
        // v1.8 (I15): only while a selected object has live mirror copies; twice as wide (its
        // words stay readable), after Array.
        if (hasCopies) {
            add(BarButton(2, RANK_DETACH) { w -> ObjectBarItem(Icons.Outlined.LinkOff, MirrorLabels18.DETACH, MirrorLabels18.DETACH, w) { run { ObjectActions.detachCopies(controller) } } })
        }
    }
    BoxWithConstraints(modifier) {
        val inner = if (maxWidth == Dp.Infinity) Float.POSITIVE_INFINITY else (maxWidth - ROW_PADDING * 2).value
        val split = ObjectBarLayout.split(buttons.map { it.units }, buttons.map { it.rank }, inner)
        // Whole pixels: a button a fraction of a pixel wide rounds up and squeezes the toggle at
        // the row's end, so the button width is floored to a pixel and the row adds up in pixels.
        val density = LocalDensity.current
        val itemPx = with(density) { split.itemDp.dp.toPx().toInt() }
        val togglePx = with(density) { ObjectBarLayout.TOGGLE_DP.dp.roundToPx() }
        val padPx = with(density) { ROW_PADDING.roundToPx() } * 2
        val item = with(density) { itemPx.toDp() }
        val used = split.row1.sumOf { buttons[it].units }
        val barWidth = with(density) { (used * itemPx + (if (split.toggle) togglePx else 0) + padPx).toDp() }
        val widest = split.more.maxOfOrNull { line -> line.sumOf { buttons[it].units } } ?: 0
        val moreWidth = maxOf(barWidth, with(density) { (widest * itemPx + padPx).toDp() })
        val open = strip.objectRowShows() && split.toggle
        // v1.9 (R3): only while the bar shows its toggle does its row take the strip's row's place.
        SideEffect { strip.objectBarShown(split.toggle) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = SheetBackground,
                contentColor = BrushworkColors.OnChrome,
                shape = BAR_SHAPE,
                shadowElevation = 4.dp,
                modifier = Modifier.semantics { contentDescription = summary },
            ) {
                Row(
                    Modifier.width(barWidth).padding(horizontal = ROW_PADDING, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in split.row1) buttons[i].content(item * buttons[i].units)
                    if (split.toggle) ObjectMoreToggle(open) { strip.toggleObjectRow() }
                }
            }
            if (open) {
                Spacer(Modifier.height(StripLayout.GAP_DP.dp))
                // v1.9 (R3): no Surface (it would take every touch on the row): a touch on the
                // row's background reaches the canvas, a canvas gesture's finger landing on a
                // button only joins that gesture (passCanvasGesture), and the row fades to 25 %
                // once that gesture passes the touch slop, as the strip's row does.
                CompositionLocalProvider(LocalContentColor provides BrushworkColors.OnChrome) {
                    Column(
                        Modifier
                            .width(moreWidth)
                            .graphicsLayer { alpha = if (strip.canvasTouchMoved) StripState.ROW_FADE_ALPHA else 1f }
                            .shadow(4.dp, BAR_SHAPE)
                            .background(IbisColors.Menu, BAR_SHAPE)
                            .border(BorderStroke(1.dp, BrushworkColors.ChromeBorder), BAR_SHAPE)
                            .testTag(V18Tags.STRIP_MORE_ROW)
                            .padding(horizontal = ROW_PADDING, vertical = 2.dp),
                    ) {
                        val guard = Modifier.passCanvasGesture(strip)
                        for (line in split.more) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                for (i in line) Box(guard) { buttons[i].content(item * buttons[i].units) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One button of the bar: [units] button widths wide (the detach button is 2), drawn by [content]
 * at a given width. [rank] says how much it is used: when row 1 is short of room, the buttons of
 * the lowest rank leave it first (equal ranks leave together).
 */
private class BarButton(val units: Int, val rank: Int, val content: @Composable (Dp) -> Unit)

// The buttons' ranks (design §3.9: the most used stay on row 1). Forward and Backward leave
// together, as do Front and Back, so a pair is never split between the rows.
private const val RANK_ARRAY = 1
private const val RANK_DETACH = 2
private const val RANK_STEP = 3
private const val RANK_ENDS = 4
private const val RANK_DUPLICATE = 5
private const val RANK_RECOLOR = 6
private const val RANK_TRANSFORM = 7
private const val RANK_DESELECT = 8
private const val RANK_DELETE = 9

/**
 * v1.8 (design §3.9): how the object bar's buttons share its width (pure; dp).
 *
 * Each button is `units` button widths wide. When they all fit [innerDp] at [MIN_ITEM_DP] they
 * share it, up to [MAX_ITEM_DP] each (no toggle). Otherwise the buttons of the lowest rank leave
 * row 1, a whole rank at a time, until the rest fit beside the [TOGGLE_DP] toggle at
 * [MIN_ITEM_DP]; they are widened to fill the row (at most [MAX_ITEM_DP]). The ones that left go
 * to the More row in their order, in lines no wider than [innerDp] at the same button width.
 */
internal object ObjectBarLayout {
    /** The smallest button width (finger-sized). */
    const val MIN_ITEM_DP = 40f

    /** The selection bar's button width, used when there is room. */
    const val MAX_ITEM_DP = 46f

    /** The toggle at row 1's end, as wide as the options strip's. */
    const val TOGGLE_DP = StripLayout.TOGGLE_DP

    /**
     * [row1] the buttons on row 1 (indices, in order), each unit [itemDp] wide; [toggle] whether
     * the toggle follows them; [more] the More row's lines (indices, in order); [rowDp] row 1's
     * width.
     */
    data class Split(val row1: List<Int>, val itemDp: Float, val toggle: Boolean, val more: List<List<Int>>, val rowDp: Float)

    fun split(units: List<Int>, ranks: List<Int>, innerDp: Float): Split {
        require(units.size == ranks.size) { "one rank per button" }
        val total = units.sum()
        if (innerDp.isInfinite() || total * MIN_ITEM_DP <= innerDp + EPS) {
            val item = if (innerDp.isInfinite() || total == 0) MAX_ITEM_DP else (innerDp / total).coerceIn(MIN_ITEM_DP, MAX_ITEM_DP)
            return Split(units.indices.toList(), item, toggle = false, more = emptyList(), rowDp = total * item)
        }
        val room = innerDp - TOGGLE_DP
        val kept = units.indices.toMutableSet()
        var used = total
        for (rank in ranks.distinct().sorted()) {
            if (used * MIN_ITEM_DP <= room + EPS) break
            for (i in units.indices) if (ranks[i] == rank && kept.remove(i)) used -= units[i]
        }
        val row1 = units.indices.filter { it in kept }
        val item = if (used == 0) MIN_ITEM_DP else (room / used).coerceIn(MIN_ITEM_DP, MAX_ITEM_DP)
        val perLine = max(1, floor((innerDp + EPS) / item).toInt())
        val more = mutableListOf<MutableList<Int>>()
        var lineUnits = 0
        for (i in units.indices) {
            if (i in kept) continue
            if (more.isEmpty() || lineUnits + units[i] > perLine) {
                more += mutableListOf<Int>()
                lineUnits = 0
            }
            more.last() += i
            lineUnits += units[i]
        }
        return Split(row1, item, toggle = true, more = more, rowDp = used * item + TOGGLE_DP)
    }

    private const val EPS = 0.5f
}

/**
 * The toggle [StripLabels18.MORE_OBJECT] at row 1's end: 44 dp, the chevron,
 * filled with the accent while the More row is open. It is tagged like the options strip's toggle
 * ([V18Tags.STRIP_TOGGLE]) and says Expanded / Collapsed the same way, so whatever reaches a
 * strip's More row reaches this one too. A long press shows its label.
 */
@Composable
private fun ObjectMoreToggle(open: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .width(ObjectBarLayout.TOGGLE_DP.dp)
            .heightIn(min = MAX_ITEM_WIDTH)
            .clip(shape)
            .background(if (open) IbisColors.Accent else Color.Transparent, shape)
            .longPressLabel(StripLabels18.MORE_OBJECT)
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = StripLabels18.MORE_OBJECT
                stateDescription = if (open) StripLabels18.EXPANDED else StripLabels18.COLLAPSED
                role = Role.Button
            }
            .testTag(V18Tags.STRIP_TOGGLE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Color.White)
    }
}

/**
 * Icon over a short label (the selection bar's look); [clickLabel] tells a screen reader what it
 * does, and a long press shows it in a bubble (v1.8). [swatch] shows a color dot on the icon;
 * [onLongClick] adds a long-press action instead of the bubble.
 */
@Composable
private fun ObjectBarItem(
    icon: ImageVector,
    label: String,
    clickLabel: String,
    width: Dp,
    swatch: Color? = null,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tint = BrushworkColors.OnChrome
    Column(
        Modifier
            .width(width)
            .heightIn(min = MAX_ITEM_WIDTH)
            .clip(RoundedCornerShape(10.dp))
            .then(if (onLongClick == null) Modifier.longPressLabel(clickLabel) else Modifier)
            .combinedClickable(
                onClickLabel = clickLabel,
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            if (swatch != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, BrushworkColors.Chrome, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        // The whole word always shows inside the button: a long one ("Transform" is about 49 dp
        // at 10 sp) is set a little smaller rather than clipped by the button's rounded shape.
        BasicText(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = tint,
                fontSize = LABEL_SP,
                lineHeight = 12.sp,
                letterSpacing = 0.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = MIN_LABEL_SP, maxFontSize = LABEL_SP, stepSize = 0.25.sp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
        )
    }
}

/** The label size of the selection bar; long words shrink down to [MIN_LABEL_SP] to fit their button. */
private val LABEL_SP = 10.sp
private val MIN_LABEL_SP = 7.5.sp

/** The selection bar's button size, used as the buttons' (and the toggle's) height. */
private val MAX_ITEM_WIDTH = ObjectBarLayout.MAX_ITEM_DP.dp

private val ROW_PADDING = 4.dp

/** The bar's and its More row's shape (the selection bar's). */
private val BAR_SHAPE = RoundedCornerShape(14.dp)
