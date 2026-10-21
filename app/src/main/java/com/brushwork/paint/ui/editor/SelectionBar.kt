package com.brushwork.paint.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.remove.ContentAwareFillJob
import com.brushwork.paint.tools.select.SelectionDuplicate
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.common.icons.arrayBehind
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.remove.ContentAwareFillSheet
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import kotlin.math.ceil

/**
 * Floating actions for the current selection (whatever made it: marquee, lasso, magic wand,
 * smart select...): copy, cut, paste, deselect, delete the selected pixels, content-aware fill
 * (opens its options), invert and the full selection menu ([onMore]); v1.7 adds "Array" (an array
 * from the selection) and "Save" (a saved selection) after it; v1.9 adds [LayerLabels19.DUPLICATE]
 * ([SelectionDuplicate]) after Save where it keeps the layer's kind. With no selection but
 * something copied, it offers Paste only, and
 * [onHide] (non-null then) hides it until something new is copied.
 *
 * v1.10 (F0, design 3.2; A6 owns the bar): [onEdit] adds [ObjectBarLabels110.EDIT] as the first
 * item (R10) and [onArrayBehind] adds [ArrayLabels110.BEHIND] right after Array (R4); each is
 * hidden while its callback is null, so without them the bar is v1.9's.
 */
@Composable
internal fun SelectionActionBar(
    controller: EditorController,
    onMore: () -> Unit,
    onHide: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onArrayBehind: (() -> Unit)? = null,
) {
    val hasSelection = controller.selection != null
    val canPaste = controller.clipboard != null
    // v1.9 (R14): reading layersVersion, the bar follows a change of the active layer or its kind.
    val canDuplicate = controller.layersVersion.let { SelectionDuplicate.kindOf(controller) != null }
    // Content-aware fill options; Fill closes them (the busy overlay shows the progress).
    var fillOptionsOpen by remember { mutableStateOf(false) }
    if (fillOptionsOpen) ContentAwareFillSheet(controller, onDismiss = { fillOptionsOpen = false })
    val scroll = rememberScrollState()
    // v1.9: slid to its end, the bar starts clear of its start cue ([selectionBarEndPadding]).
    val density = LocalDensity.current
    var viewportPx by remember { mutableIntStateOf(0) }
    var leadPx by remember { mutableIntStateOf(0) }
    val endPad = with(density) { selectionBarEndPadding(leadPx.toDp(), viewportPx.toDp()) }
    val endPadPx = with(density) { endPad.roundToPx() }
    Surface(
        color = SheetBackground,
        contentColor = BrushworkColors.OnChrome,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Box(Modifier.onSizeChanged { viewportPx = it.width }) {
            Row(
                Modifier
                    .horizontalScroll(scroll)
                    .onSizeChanged { leadPx = it.width - endPadPx }
                    .padding(start = BarEdge, end = endPad, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // v1.10 (R10, F0): the first item whenever it shows (A6 decides when).
                if (onEdit != null) {
                    BarItem(Icons.Outlined.Edit, ObjectBarLabels110.EDIT, ObjectBarLabels110.EDIT_DESC, modifier = Modifier.testTag(V110Tags.SEL_EDIT)) {
                        controller.endCanvasGesture(); onEdit()
                    }
                }
                if (hasSelection) {
                    BarItem(Icons.Outlined.ContentCopy, "Copy", "Copy selection") {
                        controller.endCanvasGesture(); controller.copySelection()
                    }
                    BarItem(Icons.Outlined.ContentCut, "Cut", "Cut selection") {
                        controller.endCanvasGesture(); controller.cutSelection()
                    }
                }
                BarItem(Icons.Outlined.ContentPaste, "Paste", "Paste as a new layer", enabled = canPaste) {
                    controller.endCanvasGesture(); controller.paste()
                }
                if (hasSelection) {
                    BarItem(Icons.Outlined.Deselect, "Deselect", "Clear the selection") { controller.endCanvasGesture(); controller.deselect() }
                    // "Clear" (its step's name): the Transform strip beside it has its own "Delete" (I10).
                    BarItem(Icons.Outlined.DeleteSweep, "Clear", "Delete the selected pixels") {
                        controller.endCanvasGesture(); controller.clearLayer()
                    }
                    // Two short lines keep it as narrow as the others: eight items just fit a 392 dp phone.
                    BarItem(Icons.Outlined.AutoFixHigh, "Content\naware", ContentAwareFillJob.FILL_LABEL, lines = 2) {
                        controller.endCanvasGesture(); fillOptionsOpen = true
                    }
                    BarItem(Icons.Outlined.InvertColors, "Invert", "Invert the selection") { controller.endCanvasGesture(); controller.invertSelection() }
                }
                BarItem(Icons.Outlined.MoreHoriz, "More", "Selection menu", onClick = onMore)
                if (hasSelection) {
                    // v1.7 (items 3 and 14), after More so the eight v1.6 items keep their places.
                    BarItem(EditorIcons.tool(ToolId.ARRAY), ArrayLabels.BUTTON, ArrayLabels.FROM_SELECTION) {
                        controller.endCanvasGesture(); controller.arrayFromSelection()
                    }
                    // v1.10 (R4, F0): right after Array, only while it has a callback.
                    if (onArrayBehind != null) {
                        BarItem(
                            EditorIcons.arrayBehind,
                            ArrayLabels110.BEHIND,
                            ArrayLabels110.BEHIND_DESC,
                            modifier = Modifier.testTag(V110Tags.SEL_ARRAY_BEHIND),
                        ) {
                            controller.endCanvasGesture(); onArrayBehind()
                        }
                    }
                    BarItem(Icons.Outlined.BookmarkAdd, SavedSelectionLabels.SAVE_BUTTON, SavedSelectionLabels.SAVE) {
                        controller.endCanvasGesture(); controller.saveSelection()
                    }
                    // v1.9 (R14): after Save, so the earlier items keep their places; only where it
                    // keeps the layer's type (SelectionDuplicate.kindOf).
                    if (canDuplicate) {
                        BarItem(
                            Icons.Outlined.ControlPointDuplicate,
                            LayerLabels19.DUPLICATE,
                            LayerLabels19.DUPLICATE_DESC,
                            fit = true,
                            modifier = Modifier.testTag(V19Tags.DUPLICATE),
                        ) {
                            controller.endCanvasGesture(); SelectionDuplicate.duplicate(controller)
                        }
                    }
                }
                if (onHide != null) BarItem(Icons.Outlined.Close, "Hide", "Hide the paste bar", onClick = onHide)
            }
            // v1.8 polish: the bar scrolls sideways (ten items, eleven in v1.9, are wider than a phone); a cue at each
            // edge with items past it says so.
            Box(Modifier.matchParentSize()) {
                if (scroll.canScrollBackward) ScrollCue(forward = false, Modifier.align(Alignment.CenterStart))
                if (scroll.canScrollForward) ScrollCue(forward = true, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/**
 * A chevron over a fade to the menu color, [ScrollCueWidth] wide at the bar's edge: in the bar's
 * edge padding and the items' own side margin, clear of a whole item's icon and label on the
 * phones the bar is laid out for (it may lie over an item cut by the edge). The chevron is a
 * standard 24 dp icon centred on the cue; its glyph (7 dp wide) stays inside it. It takes no
 * touches: a finger there reaches the item under it. On the menu color it reads the same on a
 * dark or a white canvas.
 */
@Composable
private fun ScrollCue(forward: Boolean, modifier: Modifier) {
    val fade = if (forward) listOf(Color.Transparent, IbisColors.Menu) else listOf(IbisColors.Menu, Color.Transparent)
    Box(
        modifier
            .width(ScrollCueWidth)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(fade))
            .testTag(if (forward) ChromeTags.SELECTION_BAR_CUE_END else ChromeTags.SELECTION_BAR_CUE_START),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (forward) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
            contentDescription = null,
            tint = BrushworkColors.OnChrome,
            // Wider than the cue: centred over it, only the glyph's empty sides overhang.
            modifier = Modifier.requiredSize(24.dp),
        )
    }
}

/**
 * The scroll cue's width: the bar's 4 dp edge padding and 8 dp of an item's side margin. Item
 * labels are centred in 46 dp; slid to its end, the bar starts with a whole item at least this
 * far from its edge ([selectionBarEndPadding]).
 */
private val ScrollCueWidth = 12.dp

/** A bar item's width (v1.6), and the row's padding at each end (v1.8). */
private val BarItemWidth = 46.dp
private val BarEdge = 4.dp

/**
 * v1.9 (eleven items, [LayerLabels19.DUPLICATE] last): the row's end padding. It is v1.8's
 * [BarEdge] unless the bar slid to its end would start with a whole item nearer than
 * [ScrollCueWidth] to its edge, its label under the start cue ("Deselect" on a 392 dp phone):
 * then it is wider by what leaves [ScrollCueWidth] of the item before it in view, under the cue
 * alone, and puts that item [ScrollCueWidth] in. [lead] is the row's width without its end
 * padding (its start padding and items, every one [BarItemWidth] wide but the last), [viewport]
 * the bar's.
 */
internal fun selectionBarEndPadding(lead: Dp, viewport: Dp): Dp {
    val pad = BarEdge.value
    val item = BarItemWidth.value
    val cue = ScrollCueWidth.value
    val scrolled = lead.value + pad - viewport.value
    if (lead.value <= 0f || viewport.value <= 0f || scrolled <= 0f) return BarEdge
    // The first item wholly in view, the k-th: its left edge at pad + k * item along the row.
    val k = ceil((scrolled - pad) / item - 0.001f)
    val d = pad + k * item - scrolled
    return if (d >= cue - 0.01f) BarEdge else (pad + d + item - cue).dp
}

/** Icon over a short label; [clickLabel] tells a screen reader what it does, and a long press shows it (v1.8). */
@Composable
private fun BarItem(
    icon: ImageVector,
    label: String,
    clickLabel: String,
    enabled: Boolean = true,
    lines: Int = 1,
    fit: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.45f)
    Column(
        modifier
            // v1.9: [fit] grows the item to a one-line label wider than the others' (whole at any font size).
            .then(if (fit) Modifier.widthIn(min = BarItemWidth) else Modifier.width(BarItemWidth))
            // Grows (rather than clipping) the two-line label under a large system font.
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(10.dp))
            // v1.8 (design §3.9): a long press shows what the item does ([clickLabel]) and never acts.
            .longPressLabel(clickLabel)
            .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = if (lines > 1) 11.sp else 12.sp,
            color = tint,
            maxLines = lines,
            softWrap = !fit,
            textAlign = TextAlign.Center,
            modifier = if (fit) Modifier.padding(horizontal = 4.dp) else Modifier,
        )
    }
}
