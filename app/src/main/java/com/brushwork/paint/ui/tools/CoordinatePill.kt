package com.brushwork.paint.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.brushwork.paint.ui.common.IncrementStepping
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.StepTarget
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.longPressInitialPass
import com.brushwork.paint.ui.common.rememberStepPopupHost
import com.brushwork.paint.ui.common.summary
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.editor.blockCanvasTouches
import com.brushwork.paint.ui.editor.endCanvasGesture
import kotlinx.serialization.builtins.serializer
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims

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
 */
@Composable
fun CoordinatePill(controller: EditorController, modifier: Modifier = Modifier) {
    val tool = controller.tools[controller.activeToolId] ?: return
    // Read once per tool (CurveTool.splinePointPosition is one stable instance, frozen API note).
    val source = remember(tool) { coordinateSourceOf(tool) } ?: return
    val target = source.target
    // Rounded to 0.1 px: only a visible change recomposes the pill.
    val pos by remember(target) {
        derivedStateOf { target.position?.takeIf { it.x.isFinite() && it.y.isFinite() }?.let { Vec2(round1(it.x), round1(it.y)) } }
    }
    val p = pos ?: return
    controller.docVersion // (the canvas size can change)
    val doc = controller.doc
    val dpi = doc.dpi.toDouble()
    val unit = source.unit()
    val snap = controller.snapping.enabled
    var folded by remember { mutableStateOf(controller.settings.coordinateStripFolded) }
    fun setFolded(v: Boolean) {
        folded = v
        controller.settings.coordinateStripFolded = v
    }
    val label = target.label
    // v1.7: the trash cell's label (null hides it) and the scale (null hides row 2), observed state.
    val deleteLabel = (tool as? DeletingTool)?.objectDeletion?.deleteLabel
    val percent by remember(tool) {
        derivedStateOf {
            (tool as? ScaledTool)?.objectScale?.scalePercent
                ?.takeIf { it.x.isFinite() && it.y.isFinite() }
                ?.let { Vec2(round1(it.x), round1(it.y)) }
        }
    }
    val uniformOnly = (tool as? ScaledTool)?.objectScale?.uniformOnly == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(IbisDims.PillRowGap)) {
        Row(
            Modifier
                .height(IbisDims.PillHeight)
                .background(IbisColors.Pill, RoundedCornerShape(IbisDims.PillRadius))
                // The gaps between the cells don't fall through to the canvas.
                .blockCanvasTouches()
                // What is being placed ("Center", "Point 2", "Source"), for screen readers.
                .semantics { if (label.isNotEmpty()) contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IbisDims.PillGap),
        ) {
            if (folded) {
                FoldCell("Unfold the X / Y strip") { setFolded(false) }
            } else {
                FoldCell("Fold the X / Y strip") { setFolded(true) }
                // At most 110 dp each; on a phone narrower than 392 dp they give way first, so the
                // "#" and trash cells stay whole (no effect at 392 dp: their share is 112 dp there).
                AxisCell(controller, target, "X", p.x, doc.width.toFloat(), unit, dpi, snap, Modifier.weight(1f, fill = false)) { v -> target.setPosition(v, null) }
                AxisCell(controller, target, "Y", p.y, doc.height.toFloat(), unit, dpi, snap, Modifier.weight(1f, fill = false)) { v -> target.setPosition(null, v) }
                StepCell(controller, IncrementKind.LENGTH, INCREMENTS_LABEL)
            }
            // Item 13: delete stays one tap away, folded too.
            if (deleteLabel != null) TrashCell(controller, tool, deleteLabel)
        }
        val pct = percent
        if (!folded && pct != null) ScaleRow(controller, tool, pct, uniformOnly, snap)
    }
}

// ------------------------------------------------------------------ v1.7 (§3.13): the trash cell

/** The trash cell: 40 dp touch, the icon; says what it deletes ([label]) and deletes it at once. */
@Composable
private fun TrashCell(controller: EditorController, tool: Tool, label: String) {
    Box(
        Modifier
            .width(IbisDims.PillIconTouch)
            .requiredHeight(IbisDims.PillCellTouch)
            .testTag(V17Tags.PILL_TRASH)
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
 */
@Composable
private fun ScaleRow(controller: EditorController, tool: Tool, percent: Vec2, uniformOnly: Boolean, snap: Boolean) {
    var keepSetting by remember {
        mutableStateOf(runCatching { controller.settings.getObject(KEEP_PROPORTIONS_KEY, Boolean.serializer()) }.getOrNull() ?: true)
    }
    val keep = keepSetting || uniformOnly
    Row(
        Modifier
            .testTag(V17Tags.PILL_SCALE_ROW)
            .height(IbisDims.PillHeight)
            .background(IbisColors.Pill, RoundedCornerShape(IbisDims.PillRadius))
            .blockCanvasTouches(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IbisDims.PillGap),
    ) {
        KeepCell(keep, enabled = !uniformOnly) { v ->
            keepSetting = v
            controller.settings.putObject(KEEP_PROPORTIONS_KEY, Boolean.serializer(), v)
        }
        ScaleCell(controller, tool, onX = true, percent, keep, snap)
        if (!uniformOnly) ScaleCell(controller, tool, onX = false, percent, keep, snap)
        StepCell(controller, IncrementKind.SCALE, PillLabels.SCALE_INCREMENTS)
    }
}

/** "Keep scale proportions": a chain toggle, 40 dp touch; [enabled] false shows it forced on. */
@Composable
private fun KeepCell(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(IbisDims.PillIconTouch)
            .requiredHeight(IbisDims.PillCellTouch)
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
private fun ScaleCell(controller: EditorController, tool: Tool, onX: Boolean, percent: Vec2, keep: Boolean, snap: Boolean) {
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
        Modifier
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
                                    ch = ev.changes.firstOrNull { it.id == down.id }?.takeIf { it.pressed }
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
        Row(
            Modifier
                .widthIn(min = IbisDims.PillCellMinWidth, max = IbisDims.PillCellMaxWidth)
                .height(IbisDims.PillCellHeight)
                .bevel(IbisColors.PillCell)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(if (onX) "X" else "Y", style = PrefixStyle, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            Text("$shown %", style = ValueStyle, maxLines = 1, overflow = TextOverflow.Clip)
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

/** The ✥ cell: 40 × 40 touch, the glyph on the pill. */
@Composable
private fun FoldCell(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(FOLD_TOUCH)
            .requiredHeight(IbisDims.PillCellTouch)
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
 * or NONE (the pointer went away). Shared by the X / Y and Scale cells.
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
                ch == null -> return@withTimeoutOrNull false
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
                                    ch = ev.changes.firstOrNull { it.id == down.id }?.takeIf { it.pressed }
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
            Text(axis, style = PrefixStyle, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            Text(
                if (unit == LengthUnit.PX) shown else "$shown ${unit.short}",
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
