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
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Palette
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
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.ui.common.LocalHistoryTaps
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.icons.arrayBehind
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
 *
 * v1.10 (A6, design R10, R11, R3, R4; J26): Recolor shows [recolorIcon], the palette
 * ([ObjectBarRecolorIcon]). [ObjectBarLabels110.EDIT] follows Transform while exactly one object
 * with an editor is selected ([ObjectActions.editShown]: not a brush stroke), dimmed while
 * [ObjectActions.editRefusal] says why it can't open (a tap shows that reason);
 * [ObjectBarLabels110.TO_SHAPE] (2 units, as Detach) follows Detach while exactly one path is
 * selected ([ObjectActions.toShapeShown]); [ArrayLabels110.BEHIND] follows Array while
 * [onArrayBehind] is set (the editor sets it while A3's array behind can run). [onEdit] and
 * [onToShape] replace what those two do ([ObjectActions.openInEditor],
 * [ObjectActions.turnIntoShape]). The order and the ranks are [ObjectBarSlot]'s: at 392 dp row 1
 * holds Delete, Duplicate, Front, Back, Recolor, Transform, Edit and Deselect at 40.5 dp; at 360 dp
 * Front and Back join the More row while Edit shows (6 buttons at 46 dp), and stay without it.
 */
@Composable
fun VectorObjectBar(
    controller: EditorController,
    modifier: Modifier,
    state: StripState? = null,
    recolorIcon: ImageVector = ObjectBarRecolorIcon,
    onEdit: (() -> Unit)? = null,
    onToShape: (() -> Unit)? = null,
    onArrayBehind: (() -> Unit)? = null,
) {
    val strip = state ?: remember { StripState() }
    val v = controller.vectors
    val count by remember(controller) { derivedStateOf { v.selectedIds.size } }
    val selectedLayer by remember(controller) { derivedStateOf { v.selectedLayer } }
    val activeLayer by remember(controller) { derivedStateOf { controller.layersVersion; controller.activeLayer } }
    // v1.8: whether a selected object has live mirror copies (the detach button shows).
    val hasCopies by remember(controller) { derivedStateOf { controller.layersVersion; ObjectActions.hasCopiesSelected(controller) } }
    // v1.10 (R10, R11): Edit for one object with an editor (dimmed while it can't open), "Turn
    // into shape" for one path.
    val editShown by remember(controller) { derivedStateOf { controller.layersVersion; ObjectActions.editShown(controller) } }
    val editRefused by remember(controller) { derivedStateOf { controller.layersVersion; editShown && ObjectActions.editRefusal(controller) != null } }
    val toShapeShown by remember(controller) { derivedStateOf { controller.layersVersion; ObjectActions.toShapeShown(controller) } }
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
    // v1.7 (item 3): Array after Deselect, so the nine v1.6 buttons keep their places; v1.8 (I15):
    // Detach only while a selected object has live mirror copies, twice as wide (its words stay
    // readable). v1.10: Behind after Array, TO_SHAPE (as wide as Detach) after Detach.
    val slots = ObjectBarSlot.shown(edit = editShown, behind = onArrayBehind != null, detach = hasCopies, toShape = toShapeShown)
    val button: @Composable (ObjectBarSlot, Dp) -> Unit = { slot, w ->
        when (slot) {
            ObjectBarSlot.DELETE -> ObjectBarItem(Icons.Outlined.Delete, "Delete", "Delete the selected objects", w) { run { ObjectActions.delete(controller) } }
            ObjectBarSlot.DUPLICATE ->
                ObjectBarItem(Icons.Outlined.ControlPointDuplicate, "Duplicate", "Duplicate the selected objects", w) { run { ObjectActions.duplicate(controller) } }
            ObjectBarSlot.FORWARD ->
                ObjectBarItem(Icons.Outlined.FlipToFront, "Forward", "Bring forward", w) { run { ObjectActions.arrange(controller, ObjectEdits.Arrange.FORWARD) } }
            ObjectBarSlot.BACKWARD ->
                ObjectBarItem(Icons.Outlined.FlipToBack, "Backward", "Send backward", w) { run { ObjectActions.arrange(controller, ObjectEdits.Arrange.BACKWARD) } }
            ObjectBarSlot.FRONT ->
                ObjectBarItem(Icons.Outlined.VerticalAlignTop, "Front", "Bring to front", w) { run { ObjectActions.arrange(controller, ObjectEdits.Arrange.FRONT) } }
            ObjectBarSlot.BACK ->
                ObjectBarItem(Icons.Outlined.VerticalAlignBottom, "Back", "Send to back", w) { run { ObjectActions.arrange(controller, ObjectEdits.Arrange.BACK) } }
            ObjectBarSlot.RECOLOR -> ObjectBarItem(
                recolorIcon, "Recolor", "Recolor with the main color", w,
                swatch = color,
                longClickLabel = "Recolor the lines only",
                onLongClick = { run { ObjectActions.recolor(controller, linesOnly = true) } },
            ) {
                run { ObjectActions.recolor(controller, linesOnly = false) }
            }
            ObjectBarSlot.TRANSFORM ->
                ObjectBarItem(EditorIcons.tool(ToolId.TRANSFORM), "Transform", "Transform the selected objects", w) { run { ObjectActions.transform(controller) } }
            // (A refused Edit still answers a tap: openInEditor shows the reason.)
            ObjectBarSlot.EDIT -> ObjectBarItem(
                Icons.Outlined.Edit, ObjectBarLabels110.EDIT, ObjectBarLabels110.EDIT_DESC, w, tag = V110Tags.OBJECTS_EDIT, dimmed = editRefused,
            ) {
                run { if (onEdit != null) onEdit() else ObjectActions.openInEditor(controller) }
            }
            ObjectBarSlot.DESELECT -> ObjectBarItem(Icons.Outlined.Deselect, "Deselect", "Deselect the objects", w) { run { ObjectActions.deselect(controller) } }
            ObjectBarSlot.ARRAY -> ObjectBarItem(EditorIcons.tool(ToolId.ARRAY), ArrayLabels.BUTTON, ArrayLabels.FROM_OBJECTS, w) {
                run { controller.arrayFromObjects(v.selectedIds.toSet()) }
            }
            ObjectBarSlot.BEHIND -> ObjectBarItem(EditorIcons.arrayBehind, ArrayLabels110.BEHIND, ArrayLabels110.BEHIND_DESC, w) { run { onArrayBehind?.invoke() } }
            ObjectBarSlot.DETACH -> ObjectBarItem(Icons.Outlined.LinkOff, MirrorLabels18.DETACH, MirrorLabels18.DETACH, w) { run { ObjectActions.detachCopies(controller) } }
            ObjectBarSlot.TO_SHAPE -> ObjectBarItem(Icons.Outlined.Category, ObjectBarLabels110.TO_SHAPE, ObjectBarLabels110.TO_SHAPE, w, tag = V110Tags.OBJECTS_TO_SHAPE) {
                run { if (onToShape != null) onToShape() else ObjectActions.turnIntoShape(controller) }
            }
        }
    }
    BoxWithConstraints(modifier) {
        val inner = if (maxWidth == Dp.Infinity) Float.POSITIVE_INFINITY else (maxWidth - ROW_PADDING * 2).value
        val split = ObjectBarSlot.split(slots, inner)
        // Whole pixels: a button a fraction of a pixel wide rounds up and squeezes the toggle at
        // the row's end, so the button width is floored to a pixel and the row adds up in pixels.
        val density = LocalDensity.current
        val itemPx = with(density) { split.itemDp.dp.toPx().toInt() }
        val togglePx = with(density) { ObjectBarLayout.TOGGLE_DP.dp.roundToPx() }
        val padPx = with(density) { ROW_PADDING.roundToPx() } * 2
        val item = with(density) { itemPx.toDp() }
        val used = split.row1.sumOf { slots[it].units }
        val barWidth = with(density) { (used * itemPx + (if (split.toggle) togglePx else 0) + padPx).toDp() }
        val widest = split.more.maxOfOrNull { line -> line.sumOf { slots[it].units } } ?: 0
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
                    for (i in split.row1) button(slots[i], item * slots[i].units)
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
                        val guard = Modifier.passCanvasGesture(strip, LocalHistoryTaps.current)
                        for (line in split.more) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                for (i in line) Box(guard) { button(slots[i], item * slots[i].units) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** v1.10 (R3, A6): Recolor's icon, the palette (it paints the main color; v1.9 showed a fill bucket). */
internal val ObjectBarRecolorIcon: ImageVector get() = Icons.Outlined.Palette

/**
 * The bar's buttons in row order (v1.10, J26), each [units] button widths wide (Detach and "Turn
 * into shape" are 2, so their words stay readable). [rank] says how much it is used (design §3.9:
 * the most used stay on row 1): when row 1 is short of room, the buttons of the lowest rank leave
 * it first, a whole rank at a time, so Forward and Backward, like Front and Back, are never split
 * between the rows. Delete stays longest, then Deselect and Edit.
 */
internal enum class ObjectBarSlot(val units: Int, val rank: Int) {
    DELETE(1, 10),
    DUPLICATE(1, 5),
    FORWARD(1, 3),
    BACKWARD(1, 3),
    FRONT(1, 4),
    BACK(1, 4),
    RECOLOR(1, 6),
    TRANSFORM(1, 7),
    EDIT(1, 8),
    DESELECT(1, 9),
    ARRAY(1, 1),
    BEHIND(1, 1),
    DETACH(2, 2),
    TO_SHAPE(2, 2),
    ;

    companion object {
        /** The buttons that show, in order; [edit], [behind], [detach] and [toShape] say whether those do. */
        fun shown(edit: Boolean, behind: Boolean, detach: Boolean, toShape: Boolean): List<ObjectBarSlot> = entries.filter {
            when (it) {
                EDIT -> edit
                BEHIND -> behind
                DETACH -> detach
                TO_SHAPE -> toShape
                else -> true
            }
        }

        /** How [slots] share a row [innerDp] wide ([ObjectBarLayout.split]; indices into [slots]). */
        fun split(slots: List<ObjectBarSlot>, innerDp: Float): ObjectBarLayout.Split =
            ObjectBarLayout.split(slots.map { it.units }, slots.map { it.rank }, innerDp)
    }
}

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
 * [onLongClick] adds a long-press action instead of the bubble. v1.10: [tag] is its test tag;
 * [dimmed] greys it out (it still answers a tap, which says why it can't act).
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
    tag: String? = null,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (dimmed) BrushworkColors.OnChrome.copy(alpha = DIMMED_ALPHA) else BrushworkColors.OnChrome
    Column(
        Modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
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
        // at 10 sp) is set a little smaller rather than clipped by the button's rounded shape. v1.9
        // Gate 2 (I27): the smallest size is [MIN_LABEL_SP] as at font scale 1 (in dp), so a large font
        // scale never cuts a label.
        val minLabel = with(LocalDensity.current) { MIN_LABEL_SP.value.dp.toSp() }.let { if (it.value < LABEL_SP.value) it else LABEL_SP }
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
            autoSize = TextAutoSize.StepBased(minFontSize = minLabel, maxFontSize = LABEL_SP, stepSize = 0.25.sp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
        )
    }
}

/** The label size of the selection bar; long words shrink down to [MIN_LABEL_SP] to fit their button. */
private val LABEL_SP = 10.sp
private val MIN_LABEL_SP = 7.5.sp

/** A dimmed button's icon and label (v1.10: Edit while it can't open). */
private const val DIMMED_ALPHA = 0.4f

/** The selection bar's button size, used as the buttons' (and the toggle's) height. */
private val MAX_ITEM_WIDTH = ObjectBarLayout.MAX_ITEM_DP.dp

private val ROW_PADDING = 4.dp

/** The bar's and its More row's shape (the selection bar's). */
private val BAR_SHAPE = RoundedCornerShape(14.dp)
