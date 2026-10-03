package com.brushwork.paint.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.ui.common.IncrementStepping
import com.brushwork.paint.ui.common.StepTarget
import com.brushwork.paint.ui.common.rememberStepPopupHost
import com.brushwork.paint.ui.common.summary
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.editor.blockCanvasTouches
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
    Row(
        modifier
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
            AxisCell(controller, target, "X", p.x, doc.width.toFloat(), unit, dpi, snap) { v -> target.setPosition(v, null) }
            AxisCell(controller, target, "Y", p.y, doc.height.toFloat(), unit, dpi, snap) { v -> target.setPosition(null, v) }
            HashCell(controller)
        }
    }
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

/** Touch width of the ✥ and # cells (their glyphs sit on 32 dp of the pill). */
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
        Modifier
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
                    when (kind) {
                        CellGesture.TAP -> typing = true
                        CellGesture.LONG_PRESS -> {
                            openStep()
                            // The rest of this touch belongs to the popup.
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
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
        if (fine) {
            Text(
                "Fine",
                style = TextStyle(color = Color.White, fontSize = 10.sp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .floatBelow(2.dp)
                    .background(IbisColors.Pill, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
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

/** The "#" cell: increments on / off ("Increments"), filled with the accent while on. */
@Composable
private fun HashCell(controller: EditorController) {
    val inc = controller.increments
    val on = inc.state.enabled
    Box(
        Modifier
            .width(FOLD_TOUCH)
            .requiredHeight(IbisDims.PillCellTouch)
            .toggleable(value = on, role = Role.Switch) { v ->
                inc.update { it.copy(enabled = v) }
                controller.toast(if (v) "Increments on: ${inc.state.summary()}" else "Increments off")
            }
            .semantics { contentDescription = INCREMENTS_LABEL },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = IbisDims.PillHashWidth, height = IbisDims.PillHashHeight)
                .bevel(if (on) IbisColors.Accent else IbisColors.PillCell),
            contentAlignment = Alignment.Center,
        ) {
            Text("#", style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
    }
}

/** The "#" cell's label (I10). */
const val INCREMENTS_LABEL = "Increments"
