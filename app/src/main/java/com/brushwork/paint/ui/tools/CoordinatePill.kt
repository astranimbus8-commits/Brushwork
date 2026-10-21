package com.brushwork.paint.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.ObjectScale
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.ui.common.IncrementStepping
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.StepTarget
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.longPressInitialPass
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.common.rememberStepPopupHost
import com.brushwork.paint.ui.common.summary
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.editor.blockCanvasTouches
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.common.FitText
import kotlinx.serialization.builtins.serializer

/**
 * The X / Y pill (v1.6 §3.7.8; area G): "small, the number is the slider, X and Y in beveled
 * squares". `[✥][X 1,329][Y 2,356][#]`, 32 dp tall (each cell's touch target is 40 dp: it
 * overflows the pill, which never clips). Shows whenever `coordinateSourceOf(tool)` is non-null,
 * for whatever the current tool is placing (see CoordinateSources.kt); the editor screen places it
 * ([modifier]) under the options strip and leaves it out of the canvas fit inset, so it never
 * moves the canvas.
 *
 * - **✥** folds the pill to a lone ✥ ("Fold the X / Y strip" / "Unfold the X / Y strip";
 *   remembered in `AppSettings.coordinateStripFolded`).
 * - **X / Y cells:** a horizontal drag changes the value by the finger's travel in document px
 *   (`dx / zoom`: the object follows the finger at any zoom); more than 48 dp above or below the
 *   cell it is fine (× 0.1, "Fine"); with "Snap to objects" there are detents at 0, the centre and
 *   the edge (a haptic tick); with increments on the value lands on multiples of the Length step.
 *   A tap types the value ("Type X"), a long-press opens the Step popup (Length). One drag or
 *   one typed value is one edit (`beginPositionEdit` … `endPositionEdit`). Screen readers get
 *   "X slider" with set-progress over the canvas and a quarter on each side, the value as state,
 *   and "Increase X" / "Decrease X" (1 px, or one step with increments on).
 * - **#** switches increments on and off ("Increments"), with a toast of the steps.
 *
 * v1.7 (items 9 and 13, design §3.9 / §3.13; area I):
 * - Row 1 is `[✥][X][Y][# 10][🗑]`: the "#" cell shows the Length step ("# 10", the step in
 *   11 sp, no unit; still "Increments"; a long-press opens the Length Step popup), and the trash
 *   cell deletes what the tool's [DeletingTool.objectDeletion] names ("Delete selected points",
 *   "Delete path"…; one tap, no confirmation, undo restores). The folded pill is `[✥][🗑]`.
 * - Row 2, 4 dp under it while the tool's [ScaledTool.objectScale] has a scale, is
 *   `[⛓][Scale X][Scale Y][# 10]`: "Keep scale proportions" (remembered as
 *   `pill.keepProportions`; forced on, and Scale Y hidden, for a uniform-only object such as
 *   text), the scale in % of the box the selection was taken with (typed, expressions too, or
 *   dragged like X / Y: 1 % per dp; one typed value or one drag is one edit), and "Scale
 *   increments" (the Scale step; the tap switches increments like row 1's "#").
 * The pill reads both interfaces off the tool generically (never a concrete tool class), so a
 * tool that implements them gets the row and the cell without touching this file.
 *
 * v1.8 (F4, design §3.2; D5): row 1 ends with "Select several" ([PointLabels.SELECT_SEVERAL],
 * tag [V18Tags.PILL_SEVERAL]) while the tool's [PointEditor.selectSeveralAvailable]:
 * `[✥][X][Y][# 10][🗑][sel]`, folded `[✥][🗑][sel]`; with points but no position yet the pill
 * shows reduced to `[sel]` ([pillRow1]). The strips' "Select several" chips are gone.
 *
 * v1.10 (J20, J28; F0): two more cells, each shown only while the caller passes it. [colorCell]
 * (Object colors, `PillCell.COLOR`; A2's `PillColorCell`) is the first cell of row 2, under the
 * move icon, at every width, so row 1 stays v1.9's and X and Y keep their widths. [sharpCell]
 * (Sharp / Smooth, `PillCell.SHARP`; A5's `PillSharpCell`) ends row 2 under "Select several"
 * (both rows then span the pill's width), or follows COLOR when row 1 does not end with "Select
 * several", so it is never under the trash ([pillRow2]). Folded, both join row 1:
 * `[✥][color][🗑][sel][sharp]`. Row 2's Scale cells give way first (each at most
 * [scaleRowMaxWidth]'s share), so "Keep scale proportions", "Scale increments" and the new cells
 * stay whole at 360 dp and font scale 2.0. With neither cell the pill is v1.9's.
 */
@Composable
fun CoordinatePill(
    controller: EditorController,
    modifier: Modifier = Modifier,
    colorCell: (@Composable () -> Unit)? = null,
    sharpCell: (@Composable () -> Unit)? = null,
) {
    val tool = controller.tools[controller.activeToolId] ?: return
    // Read once per tool (CurveTool.splinePointPosition is one stable instance, frozen API note).
    val source = remember(tool) { coordinateSourceOf(tool) } ?: return
    val target = source.target
    // Rounded to 0.1 px: only a visible change recomposes the pill.
    val pos by remember(target) {
        derivedStateOf { target.position?.takeIf { it.x.isFinite() && it.y.isFinite() }?.let { Vec2(round1(it.x), round1(it.y)) } }
    }
    // v1.8 (F4): whether the "Select several" cell shows (derived: dragging points doesn't recompose the pill).
    val editor = tool as? PointEditor
    val severalShown by remember(tool) { derivedStateOf { editor?.selectSeveralAvailable == true } }
    val p = pos
    var folded by remember { mutableStateOf(controller.settings.coordinateStripFolded) }
    fun setFolded(v: Boolean) {
        folded = v
        controller.settings.coordinateStripFolded = v
    }
    // v1.7: the trash cell's label (null hides it) and the scale (null hides row 2), observed state.
    val deleteLabel = if (p != null) (tool as? DeletingTool)?.objectDeletion?.deleteLabel else null
    val cells = pillRow1(
        hasPosition = p != null, folded = folded, trash = deleteLabel != null, several = editor != null && severalShown,
        color = colorCell != null, sharp = sharpCell != null,
    )
    if (cells.isEmpty()) return
    controller.docVersion // (the canvas size can change)
    val doc = controller.doc
    val dpi = doc.dpi.toDouble()
    val unit = source.unit()
    val snap = controller.snapping.enabled
    val label = if (p != null) target.label else ""
    val percent by remember(tool) {
        derivedStateOf {
            (tool as? ScaledTool)?.objectScale?.scalePercent
                ?.takeIf { it.x.isFinite() && it.y.isFinite() }
                ?.let { Vec2(round1(it.x), round1(it.y)) }
        }
    }
    val uniformOnly = (tool as? ScaledTool)?.objectScale?.uniformOnly == true
    val pct = percent
    // v1.10 (J20, J28): row 2's cells; [PillCell.SCALE] alone is v1.9's Scale row.
    val row2 = pillRow2(
        hasPosition = p != null, folded = folded, scaleRow = pct != null, color = colorCell != null, sharp = sharpCell != null,
        row1LastIsSeveral = cells.last() == PillCell.SEVERAL,
    )
    // SHARP ends row 2 under row 1's last cell ("Select several"): both rows span the pill (the
    // widest row's width). Otherwise SHARP follows COLOR, and the rows keep their own widths.
    val span = row2 != null && PillCell.SHARP in row2 && cells.last() == PillCell.SEVERAL
    Column(
        if (span) modifier.width(IntrinsicSize.Max) else modifier,
        verticalArrangement = Arrangement.spacedBy(IbisDims.PillRowGap),
    ) {
        Row(
            (if (span) Modifier.fillMaxWidth() else Modifier)
                .height(IbisDims.PillHeight)
                .background(IbisColors.Pill, RoundedCornerShape(IbisDims.PillRadius))
                // The gaps between the cells don't fall through to the canvas.
                .blockCanvasTouches()
                // What is being placed ("Center", "Point 2", "Source"), for screen readers.
                .semantics { if (label.isNotEmpty()) contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (span) PillLastAtEnd else Arrangement.spacedBy(IbisDims.PillGap),
        ) {
            for (cell in cells) {
                when (cell) {
                    PillCell.FOLD ->
                        if (folded) FoldCell("Unfold the X / Y strip") { setFolded(false) } else FoldCell("Fold the X / Y strip") { setFolded(true) }
                    // At most 110 dp each; on a phone narrower than 392 dp they give way first, so the
                    // "#", trash and "Select several" cells stay whole (no effect at 392 dp).
                    PillCell.X -> if (p != null) {
                        AxisCell(controller, target, "X", p.x, doc.width.toFloat(), unit, dpi, snap, Modifier.weight(1f, fill = false)) { v -> target.setPosition(v, null) }
                    }
                    PillCell.Y -> if (p != null) {
                        AxisCell(controller, target, "Y", p.y, doc.height.toFloat(), unit, dpi, snap, Modifier.weight(1f, fill = false)) { v -> target.setPosition(null, v) }
                    }
                    PillCell.STEP -> StepCell(controller, IncrementKind.LENGTH, INCREMENTS_LABEL)
                    // Item 13: delete stays one tap away, folded too.
                    PillCell.TRASH -> if (deleteLabel != null) TrashCell(controller, tool, deleteLabel)
                    // v1.8 (F4): so does "Select several".
                    PillCell.SEVERAL -> if (editor != null) SeveralCell(controller, editor)
                    // v1.10 (J20, J28): folded only (unfolded they are row 2's).
                    PillCell.COLOR -> colorCell?.invoke()
                    PillCell.SHARP -> sharpCell?.invoke()
                    PillCell.SCALE -> {}
                }
            }
        }
        if (row2 != null && pct != null && row2 == listOf(PillCell.SCALE)) {
            // v1.9's row 2, exactly.
            ScaleRow(controller, tool, pct, uniformOnly, snap)
        } else if (row2 != null) {
            val usable = LocalConfiguration.current.screenWidthDp - 2 * IbisDims.PillStart.value
            PillRow2(row2, span, scaleRowMaxWidth(usable, sharp = sharpCell != null, color = colorCell != null), colorCell, sharpCell) { m ->
                if (pct != null) ScaleRow(controller, tool, pct, uniformOnly, snap, m, chrome = false)
            }
        }
    }
}

/**
 * v1.8 (F4, design §3.2): a cell of the pill's rows, in [pillRow1]'s and [pillRow2]'s order.
 * v1.10 (J20, J28): [COLOR] and [SHARP]; [SCALE] stands for v1.9's whole Scale row in [pillRow2].
 */
internal enum class PillCell { FOLD, COLOR, X, Y, STEP, TRASH, SEVERAL, SHARP, SCALE }

/**
 * v1.8 (F4, design §3.2 and D5): the cells of the pill's first row. With a position:
 * `[✥][X][Y][#][🗑][sel]`, folded `[✥][🗑][sel]` (the trash cell while the tool deletes
 * something, [trash]; "Select several" while it is available, [several]). Without one: only
 * `[sel]` (the tool has points but no position yet), else nothing (no pill, as in v1.7).
 *
 * v1.10 (J20, J28): unfolded, exactly v1.9's row 1 at every width ([color] and [sharp] are row
 * 2's, [pillRow2]). Folded: `[✥][color][🗑][sel][sharp]`. Without a position, v1.9's too (no
 * COLOR or SHARP: an object or a selected point always has a position).
 */
internal fun pillRow1(
    hasPosition: Boolean,
    folded: Boolean,
    trash: Boolean,
    several: Boolean,
    color: Boolean = false,
    sharp: Boolean = false,
): List<PillCell> {
    if (!hasPosition) return if (several) listOf(PillCell.SEVERAL) else emptyList()
    val cells = mutableListOf(PillCell.FOLD)
    if (!folded) cells += listOf(PillCell.X, PillCell.Y, PillCell.STEP)
    if (folded && color) cells += PillCell.COLOR
    if (trash) cells += PillCell.TRASH
    if (several) cells += PillCell.SEVERAL
    if (folded && sharp) cells += PillCell.SHARP
    return cells
}

/**
 * v1.10 (J20, J28; F0): the pill's unfolded row 2, null for none. COLOR first, under the move icon,
 * at every width; then the Scale row ([PillCell.SCALE], while [scaleRow]: the tool has a scale);
 * SHARP last, so it ends under row 1's last cell, "Select several" (both rows then span the pill's
 * width, a flexible space before SHARP). When row 1's last cell is not "Select several"
 * ([row1LastIsSeveral] false), SHARP follows COLOR instead (`[color][sharp][Scale row]`), so it is
 * never directly under the trash (a delete with no confirmation). Today SHARP always comes with
 * "Select several": it needs a selected point, so `PointEditor.selectSeveralAvailable` holds.
 * Folded or without a position there is no row 2 ([pillRow1] holds COLOR and SHARP then).
 * Without [color] and [sharp] it is v1.9's: `[SCALE]` while [scaleRow], else null.
 */
internal fun pillRow2(
    hasPosition: Boolean,
    folded: Boolean,
    scaleRow: Boolean,
    color: Boolean,
    sharp: Boolean,
    row1LastIsSeveral: Boolean,
): List<PillCell>? {
    if (!hasPosition || folded) return null
    val cells = ArrayList<PillCell>(3)
    if (color) cells += PillCell.COLOR
    if (sharp && !row1LastIsSeveral) cells += PillCell.SHARP
    if (scaleRow) cells += PillCell.SCALE
    if (sharp && row1LastIsSeveral) cells += PillCell.SHARP
    return cells.takeIf { it.isNotEmpty() }
}

/**
 * v1.10 (J28, finding 32; F0): the widest the Scale row may be in row 2, in dp, on a pill
 * [usableDp] wide (the window less the pill's 8 dp margins: 376 at 392 dp, 344 at 360 dp): v1.9's
 * 328 ([IbisDims.PillScaleRowMaxWidth]) less 44 (a 40 dp cell and a 4 dp gap) for each of SHARP
 * and COLOR beside it. 256 at 360 dp with both: Keep 40, "Scale increments" 40-56 and three gaps
 * leave 74-82 dp per Scale cell (they give way first; at least 72).
 */
internal fun scaleRowMaxWidth(usableDp: Float, sharp: Boolean, color: Boolean): Float =
    minOf(IbisDims.PillScaleRowMaxWidth.value, usableDp - (if (sharp) 44f else 0f) - (if (color) 44f else 0f))

/**
 * v1.10 (J28; F0): row 1's arrangement while SHARP ends row 2: the cells [IbisDims.PillGap] apart
 * from the start, as `Arrangement.spacedBy`, but the last ("Select several") at the row's end, so
 * it stays over SHARP when row 1's cells leave room (X and Y give way to their content, a larger
 * font, a wider row 2).
 */
private object PillLastAtEnd : Arrangement.Horizontal {
    override val spacing: Dp get() = IbisDims.PillGap

    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        if (sizes.isEmpty()) return
        val gap = spacing.roundToPx()
        var x = 0
        for (i in sizes.indices) {
            outPositions[i] = x
            x += sizes[i] + gap
        }
        val last = sizes.lastIndex
        outPositions[last] = maxOf(outPositions[last], totalSize - sizes[last])
        if (layoutDirection == LayoutDirection.Rtl) for (i in sizes.indices) outPositions[i] = totalSize - outPositions[i] - sizes[i]
    }
}

/**
 * v1.10 (J20, J28): row 2 with COLOR or SHARP: one pill background (the Scale row inside draws
 * none, so the translucent pill is never drawn twice), the [cells] of [pillRow2] in order. The
 * Scale row ([scaleRow], given its modifier) sits in a flexible box at most [scaleMaxDp] wide;
 * with [span] the box (or a flexible space when there is no Scale row) fills the rest of the row,
 * so SHARP ends at the pill's right edge, under "Select several". COLOR and SHARP are measured
 * before the Scale row, so they never give way.
 */
@Composable
private fun PillRow2(
    cells: List<PillCell>,
    span: Boolean,
    scaleMaxDp: Float,
    colorCell: (@Composable () -> Unit)?,
    sharpCell: (@Composable () -> Unit)?,
    scaleRow: @Composable (Modifier) -> Unit,
) {
    Row(
        (if (span) Modifier.fillMaxWidth() else Modifier)
            .height(IbisDims.PillHeight)
            .background(IbisColors.Pill, RoundedCornerShape(IbisDims.PillRadius))
            .blockCanvasTouches(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IbisDims.PillGap),
    ) {
        for (cell in cells) {
            when (cell) {
                PillCell.COLOR -> colorCell?.invoke()
                PillCell.SCALE -> Box(Modifier.weight(1f, fill = span), contentAlignment = Alignment.CenterStart) {
                    scaleRow(Modifier.widthIn(max = scaleMaxDp.coerceAtLeast(0f).dp))
                }
                PillCell.SHARP -> {
                    if (span && PillCell.SCALE !in cells) Spacer(Modifier.weight(1f))
                    sharpCell?.invoke()
                }
                else -> {}
            }
        }
    }
}

// ------------------------------------------------------------------ v1.8 (F4, §3.2): "Select several"

/**
 * "Select several" ([PointLabels.SELECT_SEVERAL]): a 40 dp icon cell, tag [V18Tags.PILL_SEVERAL],
 * that switches the tool's [PointEditor.selectSeveral] (as v1.7's strip chip did; its state is
 * "On" / "Off", the square filled with the accent while on). A long press shows its name.
 */
@Composable
private fun SeveralCell(controller: EditorController, editor: PointEditor) {
    val on = editor.selectSeveral
    Box(
        Modifier
            .width(IbisDims.PillIconTouch)
            .requiredHeight(IbisDims.PillCellTouch)
            .testTag(V18Tags.PILL_SEVERAL)
            // (Outside the toggle: after a long press the touch is the bubble's, never a switch.)
            .longPressLabel(PointLabels.SELECT_SEVERAL)
            .toggleable(value = on, role = Role.Switch) { v ->
                controller.endCanvasGesture()
                editor.selectSeveral = v
            }
            .semantics {
                contentDescription = PointLabels.SELECT_SEVERAL
                stateDescription = if (on) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(IbisDims.PillCellHeight)
                .bevel(if (on) IbisColors.Accent else IbisColors.PillCell),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.HighlightAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

// ------------------------------------------------------------------ v1.7 (§3.13): the trash cell

/** The trash cell: 40 dp touch, the icon; says what it deletes ([label], also its long-press bubble, v1.8) and deletes it at once. */
@Composable
private fun TrashCell(controller: EditorController, tool: Tool, label: String) {
    Box(
        Modifier
            .width(IbisDims.PillIconTouch)
            .requiredHeight(IbisDims.PillCellTouch)
            .testTag(V17Tags.PILL_TRASH)
            .longPressLabel(label)
            .clickable(onClickLabel = label, role = Role.Button) {
                // Read at the tap: what the tool deletes right now (one controller or in-tool step).
                val deletion = (tool as? DeletingTool)?.objectDeletion ?: return@clickable
                controller.endCanvasGesture()
                deletion.delete()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------ v1.7 (§3.9): the Scale row

/** `pill.keepProportions` ("Keep scale proportions"), on until the user turns it off. */
private const val KEEP_PROPORTIONS_KEY = "pill.keepProportions"

/**
 * Row 2: "Keep scale proportions", Scale X, Scale Y (hidden when [uniformOnly]) and "Scale
 * increments", for the [percent] (rounded to 0.1) of the tool's [ScaledTool.objectScale].
 *
 * v1.10 (J28, finding 32): the Scale cells give way first (`weight(1f, fill = false)`: Keep and
 * "Scale increments" are measured before them), so on a narrow row the step cell never vanishes;
 * where v1.9's row fits (360 dp and wider) the cells are as wide as in v1.9. Inside v1.10's row 2
 * ([chrome] false) the row draws no pill background and blocks no touches of its own (the row
 * around it does both), and [modifier] caps its width ([scaleRowMaxWidth]).
 */
@Composable
private fun ScaleRow(
    controller: EditorController,
    tool: Tool,
    percent: Vec2,
    uniformOnly: Boolean,
    snap: Boolean,
    modifier: Modifier = Modifier,
    chrome: Boolean = true,
) {
    var keepSetting by remember {
        mutableStateOf(runCatching { controller.settings.getObject(KEEP_PROPORTIONS_KEY, Boolean.serializer()) }.getOrNull() ?: true)
    }
    val keep = keepSetting || uniformOnly
    Row(
        modifier
            .testTag(V17Tags.PILL_SCALE_ROW)
            .height(IbisDims.PillHeight)
            .then(
                if (chrome) Modifier.background(IbisColors.Pill, RoundedCornerShape(IbisDims.PillRadius)).blockCanvasTouches() else Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IbisDims.PillGap),
    ) {
        KeepCell(keep, enabled = !uniformOnly) { v ->
            keepSetting = v
            controller.settings.putObject(KEEP_PROPORTIONS_KEY, Boolean.serializer(), v)
        }
        ScaleCell(controller, tool, onX = true, percent, keep, snap, Modifier.weight(1f, fill = false))
        if (!uniformOnly) ScaleCell(controller, tool, onX = false, percent, keep, snap, Modifier.weight(1f, fill = false))
        StepCell(controller, IncrementKind.SCALE, PillLabels.SCALE_INCREMENTS)
    }
}

/** "Keep scale proportions": a chain toggle, 40 dp touch; [enabled] false shows it forced on. A long press shows its name (v1.8). */
@Composable
private fun KeepCell(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(IbisDims.PillIconTouch)
            .requiredHeight(IbisDims.PillCellTouch)
            .longPressLabel(PillLabels.KEEP_PROPORTIONS)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = PillLabels.KEEP_PROPORTIONS },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Filled.Link else Icons.Filled.LinkOff,
            contentDescription = null,
            tint = if (on) IbisColors.Accent else Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One Scale cell ("Scale X 150 %"): a tap types the value (expressions too; relative text applies
 * to the value shown), a drag changes it by 1 % per dp (fine, detent at 100 % with snapping and
 * the Scale step with increments as an X / Y cell), a long-press opens the Scale Step popup.
 * Each typed value and each drag is one edit (`beginScaleEdit` … `endScaleEdit`); with [keep]
 * the other axis follows ([scaleTarget]).
 */
@Composable
private fun ScaleCell(
    controller: EditorController,
    tool: Tool,
    onX: Boolean,
    percent: Vec2,
    keep: Boolean,
    snap: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val inc = controller.increments
    val name = if (onX) PillLabels.SCALE_X else PillLabels.SCALE_Y
    val value = if (onX) percent.x else percent.y
    fun scale(): ObjectScale? = (tool as? ScaledTool)?.objectScale
    val latestKeep by rememberUpdatedState(keep)
    val latestSnap by rememberUpdatedState(snap)
    var typing by remember { mutableStateOf(false) }
    var fine by remember { mutableStateOf(false) }
    val stepHost = rememberStepPopupHost()
    val stepTarget = remember { StepTarget(IncrementKind.SCALE, null) }
    val openStep = { stepHost.open(inc, stepTarget) }

    /** One edit: [v] % on this axis (the other following with keep), from the scale [from]. */
    fun setOnce(v: Float, from: Vec2) {
        val s = scale() ?: return
        val (x, y) = scaleTarget(onX, v, from, latestKeep)
        s.beginScaleEdit()
        try { s.setScale(x, y) } finally { s.endScaleEdit() }
    }

    fun nudge(direction: Int) {
        val now = scale()?.scalePercent ?: return
        val v = if (onX) now.x else now.y
        val step = inc.step(IncrementKind.SCALE)
        val next = if (step != null) IncrementStepping.stepBy(v.toDouble(), direction.toLong(), step.toDouble()).toFloat() else v + direction
        setOnce(next.coerceAtLeast(MIN_SCALE_PERCENT), now)
    }

    val shown = formatScale(value)
    Box(
        modifier
            .requiredHeight(IbisDims.PillCellTouch)
            .semantics {
                contentDescription = name
                stateDescription = "$shown %"
                onClick(label = "Type $name") { typing = true; true }
                onLongClick(label = stepTarget.actionLabel) { openStep(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Increase $name") { nudge(1); true },
                    CustomAccessibilityAction("Decrease $name") { nudge(-1); true },
                )
            }
            .pointerInput(tool, onX) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val (kind, dragFrom) = awaitCellGesture(down)
                    when (kind) {
                        CellGesture.TAP -> typing = true
                        CellGesture.LONG_PRESS -> {
                            openStep()
                            consumeRestOfTouch()
                        }
                        CellGesture.DRAG -> {
                            val s = scale() ?: return@awaitEachGesture
                            val from = s.scalePercent ?: return@awaitEachGesture
                            val start = if (onX) from.x else from.y
                            val drag = PillDrag(
                                start, SCALE_PERCENT_PER_DP / density, FINE_DISTANCE_DP.dp.toPx(),
                                if (latestSnap) listOf(100f) else emptyList(), DETENT_DP.dp.toPx(),
                                inc.step(IncrementKind.SCALE), scaleDragRange(start),
                            )
                            drag.down(down.position.x)
                            var last = start
                            var lastDetent: Float? = null
                            // The whole drag is one edit.
                            s.beginScaleEdit()
                            try {
                                var ch = dragFrom
                                while (ch != null) {
                                    val v = drag.move(ch.position.x, ch.position.y - size.height / 2f).coerceAtLeast(MIN_SCALE_PERCENT)
                                    fine = drag.fine
                                    if (drag.detent != null && drag.detent != lastDetent) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lastDetent = drag.detent
                                    if (v != last) {
                                        last = v
                                        val (x, y) = scaleTarget(onX, v, from, latestKeep)
                                        s.setScale(x, y)
                                    }
                                    ch.consume()
                                    val ev = awaitPointerEvent()
                                    // Lifted, or claimed by a history tap (consumed): the edit ends.
                                    ch = ev.changes.firstOrNull { it.id == down.id }?.takeIf { it.pressed && !it.isConsumed }
                                    if (ch == null) ev.changes.forEach { it.consume() }
                                }
                            } finally {
                                fine = false
                                s.endScaleEdit()
                            }
                        }
                        CellGesture.NONE -> {}
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The cell is read as "Scale X" (its description) and "… %" (its state): the drawn "X" and
        // value stay out of the semantics, or row 2 would repeat row 1's "X" / "Y" labels.
        Row(
            Modifier
                .widthIn(min = IbisDims.PillCellMinWidth, max = IbisDims.PillCellMaxWidth)
                .height(IbisDims.PillCellHeight)
                .bevel(IbisColors.PillCell)
                .padding(horizontal = 8.dp)
                .clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // v1.9 Gate 2 (I27): the texts shrink at a large font scale to stay in the cell, never below
            // their size at font scale 1 ([FitText]): a far-off value is cut at the cap, as in v1.7.
            FitText(if (onX) "X" else "Y", fontSize = IbisDims.PillPrefixText, style = PrefixStyle, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            FitText(
                "$shown %",
                fontSize = IbisDims.PillValueText,
                style = ValueStyle,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        if (fine) FineTag(Modifier.align(Alignment.BottomCenter))
    }
    if (typing) {
        ValueInputDialog(
            title = name,
            label = name,
            initial = value,
            format = { formatScale(it) },
            parse = { t -> Units.parse(t)?.toFloat()?.takeIf { it.isFinite() && it >= MIN_SCALE_PERCENT } },
            step = { v, up -> (v + if (up) 1f else -1f).coerceAtLeast(MIN_SCALE_PERCENT) },
            toFraction = { v -> (v - SCALE_DRAG_MIN) / (SCALE_SLIDER_MAX - SCALE_DRAG_MIN) },
            fromFraction = { f -> round1(SCALE_DRAG_MIN + f * (SCALE_SLIDER_MAX - SCALE_DRAG_MIN)) },
            rangeText = "% of the size it was selected at",
            suffix = "%",
            onApply = { v ->
                val from = scale()?.scalePercent ?: return@ValueInputDialog
                setOnce(v, from)
            },
            onDismiss = { typing = false },
            incrementKind = IncrementKind.SCALE,
        )
    }
}

/** The far end of the typed Scale dialog's slider (the field itself takes any value). */
private const val SCALE_SLIDER_MAX = 400f

// ------------------------------------------------------------------ v1.7 (§3.9): the "# step" cells

/** The step a "#" cell shows after the sign: "# 10" (the step in [IbisDims.PillStepText], no unit). */
internal fun stepCellText(step: Float): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)) { append("#") }
    append(" ")
    withStyle(SpanStyle(fontSize = IbisDims.PillStepText, fontFeatureSettings = "tnum")) { append(IncrementStepping.format(step)) }
}

/** The ✥ cell: 40 × 40 touch, the glyph on the pill; a long press shows its [label] (v1.8). */
@Composable
private fun FoldCell(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(FOLD_TOUCH)
            .requiredHeight(IbisDims.PillCellTouch)
            .longPressLabel(label)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.OpenWith, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Touch width of the ✥ cell (its glyph sits on 32 dp of the pill; the "# step" cells are 40–56 dp). */
private val FOLD_TOUCH = 40.dp

/** The beveled rounded square of a cell: [fill], a light line along the top inner edge, a dark one along the bottom. */
private fun Modifier.bevel(fill: Color): Modifier = drawBehind {
    val r = IbisDims.PillCellRadius.toPx()
    val b = IbisDims.PillBevel.toPx()
    drawRoundRect(fill, cornerRadius = CornerRadius(r, r))
    drawLine(IbisColors.BevelLight, Offset(r * 0.6f, b / 2f), Offset(size.width - r * 0.6f, b / 2f), strokeWidth = b)
    drawLine(IbisColors.BevelDark, Offset(r * 0.6f, size.height - b / 2f), Offset(size.width - r * 0.6f, size.height - b / 2f), strokeWidth = b)
}

private val ValueStyle = TextStyle(color = Color.White, fontSize = IbisDims.PillValueText, fontFeatureSettings = "tnum")
private val PrefixStyle = TextStyle(color = IbisColors.PillPrefix, fontSize = IbisDims.PillPrefixText, fontWeight = FontWeight.Bold)

/** What a cell's touch turned out to be. */
private enum class CellGesture { TAP, DRAG, LONG_PRESS, NONE }

/**
 * What the touch that went [down] on a cell is: a TAP (lifted within the slop), a DRAG (moved
 * past it; the change that did is returned with it), a LONG_PRESS (held for the long-press time)
 * or NONE (the pointer went away, or a history tap over the UI claimed the touch). Shared by the
 * X / Y and Scale cells.
 */
private suspend fun AwaitPointerEventScope.awaitCellGesture(down: PointerInputChange): Pair<CellGesture, PointerInputChange?> {
    val slop = viewConfiguration.touchSlop
    var kind = CellGesture.NONE
    var dragFrom: PointerInputChange? = null
    val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (kind == CellGesture.NONE) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id }
            when {
                // Consumed: a two- / three-finger history tap over the UI claimed the touch
                // (`Modifier.historyTaps`): no dialog, drag or Step popup comes of it.
                ch == null || ch.isConsumed -> return@withTimeoutOrNull false
                !ch.pressed -> { ch.consume(); kind = CellGesture.TAP }
                (ch.position - down.position).getDistance() > slop -> { dragFrom = ch; kind = CellGesture.DRAG }
            }
        }
        true
    }
    if (decided == null) kind = CellGesture.LONG_PRESS
    return kind to dragFrom
}

/** The rest of this touch belongs to the Step popup a long-press opened: consumed until every finger lifts. */
private suspend fun AwaitPointerEventScope.consumeRestOfTouch() {
    while (true) {
        val ev = awaitPointerEvent()
        ev.changes.forEach { it.consume() }
        if (ev.changes.none { it.pressed }) break
    }
}

/** The "Fine" tag under a cell while its drag is fine. */
@Composable
private fun FineTag(modifier: Modifier) {
    Text(
        "Fine",
        style = TextStyle(color = Color.White, fontSize = 10.sp),
        modifier = modifier
            .floatBelow(2.dp)
            .background(IbisColors.Pill, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * One axis cell ("X 1,329"): the number is the slider (see [CoordinatePill] and [PillDrag]).
 * [onSet] places the value (document px).
 */
@Composable
private fun AxisCell(
    controller: EditorController,
    target: ObjectPosition,
    axis: String,
    value: Float,
    extent: Float,
    unit: LengthUnit,
    dpi: Double,
    snap: Boolean,
    modifier: Modifier = Modifier,
    onSet: (Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val inc = controller.increments
    val current: () -> Float? = { target.position?.let { if (axis == "X") it.x else it.y } }
    val latestSet by rememberUpdatedState(onSet)
    val latestCurrent by rememberUpdatedState(current)
    // Read by the running gesture: a recomposition (snapping switched, the canvas resized) must
    // never restart the touch handler and so cut a drag short.
    val latestExtent by rememberUpdatedState(extent)
    val latestSnap by rememberUpdatedState(snap)
    var typing by remember { mutableStateOf(false) }
    var fine by remember { mutableStateOf(false) }
    val stepHost = rememberStepPopupHost()
    val stepTarget = remember { StepTarget(IncrementKind.LENGTH, null) }
    val openStep = { stepHost.open(inc, stepTarget) }
    fun begin() = target.beginPositionEdit()
    fun end() = target.endPositionEdit()

    /** One step of the screen reader's increase / decrease: 1 px, or to the next multiple of the Length step. */
    fun nudge(direction: Int) {
        val v = latestCurrent() ?: return
        val step = inc.step(IncrementKind.LENGTH)
        val next = if (step != null) IncrementStepping.stepBy(v.toDouble(), direction.toLong(), step.toDouble()).toFloat() else v + direction
        begin()
        latestSet(next)
        end()
    }

    val range = axisRange(value, extent)
    val shown = formatCoordinate(value, unit, dpi)
    Box(
        modifier
            .requiredHeight(IbisDims.PillCellTouch)
            .semantics {
                contentDescription = "$axis slider"
                stateDescription = "$shown ${unit.short}"
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range.start, range.endInclusive), range)
                setProgress { v ->
                    begin()
                    latestSet(v)
                    end()
                    true
                }
                onClick(label = "Type $axis") { typing = true; true }
                onLongClick(label = stepTarget.actionLabel) { openStep(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Increase $axis") { nudge(1); true },
                    CustomAccessibilityAction("Decrease $axis") { nudge(-1); true },
                )
            }
            // Restarted for another target (a different tool's pill in the same place), so a drag
            // always begins and ends the edit of what it moves.
            .pointerInput(target) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val (kind, dragFrom) = awaitCellGesture(down)
                    when (kind) {
                        CellGesture.TAP -> typing = true
                        CellGesture.LONG_PRESS -> {
                            openStep()
                            consumeRestOfTouch()
                        }
                        CellGesture.DRAG -> {
                            val start = latestCurrent() ?: return@awaitEachGesture
                            val zoom = controller.viewTransform.zoom.coerceAtLeast(1e-6f)
                            val ext = latestExtent
                            val detents = if (latestSnap) listOf(0f, ext / 2f, ext) else emptyList()
                            val drag = PillDrag(
                                start, 1f / zoom, FINE_DISTANCE_DP.dp.toPx(), detents, DETENT_DP.dp.toPx(),
                                inc.step(IncrementKind.LENGTH), dragRange(start, ext),
                            )
                            drag.down(down.position.x)
                            var last = start
                            var lastDetent: Float? = null
                            // The whole drag is one edit, however long the finger rests on the way.
                            begin()
                            try {
                                var ch = dragFrom
                                while (ch != null) {
                                    val v = drag.move(ch.position.x, ch.position.y - size.height / 2f)
                                    fine = drag.fine
                                    if (drag.detent != null && drag.detent != lastDetent) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lastDetent = drag.detent
                                    if (v != last) {
                                        last = v
                                        latestSet(v)
                                    }
                                    ch.consume()
                                    val ev = awaitPointerEvent()
                                    // Lifted, or claimed by a history tap (consumed): the edit ends.
                                    ch = ev.changes.firstOrNull { it.id == down.id }?.takeIf { it.pressed && !it.isConsumed }
                                    if (ch == null) ev.changes.forEach { it.consume() }
                                }
                            } finally {
                                fine = false
                                end()
                            }
                        }
                        CellGesture.NONE -> {}
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(min = IbisDims.PillCellMinWidth, max = IbisDims.PillCellMaxWidth)
                .height(IbisDims.PillCellHeight)
                .bevel(IbisColors.PillCell)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // v1.9 Gate 2 (I27): the texts shrink at a large font scale to stay in the cell, never below
            // their size at font scale 1 ([FitText]): a far-off value is cut at the cap, as in v1.7.
            FitText(axis, fontSize = IbisDims.PillPrefixText, style = PrefixStyle, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            FitText(
                if (unit == LengthUnit.PX) shown else "$shown ${unit.short}",
                fontSize = IbisDims.PillValueText,
                style = ValueStyle,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        if (fine) FineTag(Modifier.align(Alignment.BottomCenter))
    }
    if (typing) {
        val r = axisRange(value, extent)
        val span = (r.endInclusive - r.start).coerceAtLeast(1f)
        ValueInputDialog(
            title = "$axis position",
            label = axis,
            initial = unit.fromPx(value.toDouble(), dpi).toFloat(),
            format = { Units.formatNumber(it.toDouble(), unit.decimals) },
            parse = { t -> Units.parse(t)?.toFloat()?.takeIf { it.isFinite() } },
            step = { v, up -> (v + if (up) unit.defaultStep.toFloat() else -unit.defaultStep.toFloat()) },
            toFraction = { v -> ((unit.toPx(v.toDouble(), dpi).toFloat() - r.start) / span) },
            fromFraction = { f -> unit.fromPx((r.start + f * span).toDouble(), dpi).toFloat() },
            rangeText = "Canvas: 0 – ${Units.format(extent.toDouble(), unit, dpi)}",
            suffix = unit.short,
            onApply = { v ->
                begin()
                latestSet(unit.toPx(v.toDouble(), dpi).toFloat())
                end()
            },
            onDismiss = { typing = false },
            incrementKind = IncrementKind.LENGTH,
            incrementScale = unit.toPx(1.0, dpi).toFloat(),
        )
    }
}

/** Places this element [gap] below its alignment point without taking room (the "Fine" tag under a cell). */
private fun Modifier.floatBelow(gap: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout(0, 0) { p.place(-p.width / 2, gap.roundToPx()) }
}

/**
 * A "# step" cell (v1.7 item 9): increments on / off ([label]: "Increments" in row 1, "Scale
 * increments" in row 2; filled with the accent while on, with a toast of the steps), showing the
 * [kind]'s step after the sign ("# 10", 40–56 dp wide); a long-press opens that kind's Step
 * popup. The step is the cell's state for screen readers ("On, step 10 px"), not its text, so the
 * two cells' "# 10" never read as one more label.
 */
@Composable
private fun StepCell(controller: EditorController, kind: IncrementKind, label: String) {
    val inc = controller.increments
    val on = inc.state.enabled
    val step = inc.state.step(kind)
    val stepHost = rememberStepPopupHost()
    val stepTarget = remember(kind) { StepTarget(kind, null) }
    val stepText = "${IncrementStepping.format(step)} ${kind.suffix}"
    Box(
        Modifier
            .widthIn(min = IbisDims.PillStepMinWidth, max = IbisDims.PillStepMaxWidth)
            .requiredHeight(IbisDims.PillCellTouch)
            .longPressInitialPass { stepHost.open(inc, stepTarget) }
            .toggleable(value = on, role = Role.Switch) { v ->
                inc.update { it.copy(enabled = v) }
                controller.toast(if (v) "Increments on: ${inc.state.summary()}" else "Increments off")
            }
            .semantics {
                contentDescription = label
                stateDescription = "${if (on) "On" else "Off"}, step $stepText"
                onLongClick(label = stepTarget.actionLabel) { stepHost.open(inc, stepTarget); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .widthIn(min = IbisDims.PillHashWidth)
                .height(IbisDims.PillHashHeight)
                .bevel(if (on) IbisColors.Accent else IbisColors.PillCell)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            // (Drawn only: the step is the state above, not one more label.)
            Text(stepCellText(step), Modifier.clearAndSetSemantics {}, style = TextStyle(color = Color.White), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
}

/** The "#" cell's label (I10). */
const val INCREMENTS_LABEL = "Increments"
