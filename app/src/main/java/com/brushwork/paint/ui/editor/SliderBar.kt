package com.brushwork.paint.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.stepOnLongPress
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Which brush slider is being dragged (drives the preview overlay) or typed. */
enum class SliderKind { SIZE, OPACITY }

/**
 * The ibisPaint brush slider rows (v1.6 §3.7.4): size over opacity, each `[value][−][track][+]`,
 * floating over the canvas surround with no background, for the preset of
 * [EditorController.sliderToolId]. Every row takes the touches of its whole width (they never
 * reach the canvas).
 *
 * - The value ("72.0", "100") is tapped to type it ([onEditValue]) and long-pressed for the
 *   Step popup of its increment kind (Size / Percent).
 * - − / + step by the v1.5 [SliderMath] steps, or by the Size / Percent increment while
 *   increments are on; holding repeats.
 * - The track drags RELATIVELY (v1.5): touching it never makes the value jump. While increments
 *   are on the value lands on the step's multiples; the range ends stay reachable.
 *
 * [onDragChange] reports the slider under the finger (for the size preview); [leftHanded]
 * mirrors the rows (values on the right). [oneRow] puts them side by side in one row (wide
 * screens: [com.brushwork.paint.ui.editor.chrome.ChromeLayout.sliderRowCount]).
 */
@Composable
fun BrushSliderRows(
    controller: EditorController,
    leftHanded: Boolean,
    onDragChange: (SliderKind?) -> Unit,
    onEditValue: (SliderKind) -> Unit,
    modifier: Modifier = Modifier,
    oneRow: Boolean = false,
) {
    val toolId = controller.sliderToolId
    val preset = controller.presetFor(toolId) ?: return
    val store = remember(controller) { BrushPresetStore.get(controller.appContext) }
    val increments = controller.increments
    // Everything below reads the tool (and the steps) at event time: they may change between
    // recompositions.
    val persist = { store.persist(controller, controller.sliderToolId) }
    val finishDrag = { kind: SliderKind, dragging: Boolean ->
        onDragChange(if (dragging) kind else null)
        if (!dragging) persist()
    }
    val setSize = { s: Float ->
        val id = controller.sliderToolId
        controller.presetFor(id)?.let { p -> if (s != p.size) controller.updatePreset(id, p.copy(size = s)) }
    }
    val setOpacity = { o: Float ->
        val id = controller.sliderToolId
        controller.presetFor(id)?.let { p -> if (o != p.opacity) controller.updatePreset(id, p.copy(opacity = o)) }
    }
    val name = toolId.label
    val eraser = toolId == ToolId.ERASER
    val color = if (eraser) Color.Black else Color(controller.color).copy(alpha = 1f)
    val sizeRow: @Composable (Modifier) -> Unit = { m ->
        IbisSliderRow(
            modifier = m,
            kind = IncrementKind.SIZE,
            label = "$name size",
            typeLabel = "Type ${name.lowercase()} size",
            // I10: the same labels for every tool the rows serve (the track names the tool).
            minusLabel = "Smaller brush",
            plusLabel = "Bigger brush",
            valueText = SliderMath.formatSizeFixed(preset.size),
            stateText = "${SliderMath.formatSize(preset.size)} px",
            fraction = SliderMath.sizeToFraction(preset.size),
            onFraction = { f ->
                val raw = SliderMath.fractionToSize(f)
                setSize(increments.step(IncrementKind.SIZE)?.let { SliderMath.snapSize(raw, it) } ?: raw)
            },
            onStep = { up ->
                controller.presetFor(controller.sliderToolId)?.let { p ->
                    val step = increments.step(IncrementKind.SIZE)
                    setSize(if (step != null) SliderMath.stepSizeBy(p.size, up, step) else SliderMath.stepSize(p.size, up))
                }
            },
            onStepDone = persist,
            onDragging = { finishDrag(SliderKind.SIZE, it) },
            onEdit = { onEditValue(SliderKind.SIZE) },
            leftHanded = leftHanded,
            drawTrack = { left, right, cy, f -> sizeTrack(left, right, cy, f) },
        )
    }
    val opacityRow: @Composable (Modifier) -> Unit = { m ->
        IbisSliderRow(
            modifier = m,
            kind = IncrementKind.PERCENT,
            label = "$name opacity",
            typeLabel = "Type ${name.lowercase()} opacity",
            minusLabel = "Less opacity",
            plusLabel = "More opacity",
            valueText = (preset.opacity.coerceIn(0f, 1f) * 100f).roundToInt().toString(),
            stateText = SliderMath.formatPercent(preset.opacity),
            fraction = preset.opacity,
            onFraction = { f ->
                setOpacity(if (increments.step(IncrementKind.PERCENT) != null) increments.percent01(f) else (f * 100f).roundToInt() / 100f)
            },
            onStep = { up ->
                controller.presetFor(controller.sliderToolId)?.let { p ->
                    val step = increments.step(IncrementKind.PERCENT)
                    setOpacity(if (step != null) SliderMath.stepPercentBy(p.opacity, up, step) else SliderMath.stepPercent(p.opacity, up))
                }
            },
            onStepDone = persist,
            onDragging = { finishDrag(SliderKind.OPACITY, it) },
            onEdit = { onEditValue(SliderKind.OPACITY) },
            leftHanded = leftHanded,
            drawTrack = { left, right, cy, f -> opacityTrack(left, right, cy, f, color) },
        )
    }
    if (oneRow) {
        // The whole row (the gap between the halves too) keeps its touches from the canvas.
        Row(modifier.fillMaxWidth().blockCanvasTouches()) {
            sizeRow(Modifier.weight(1f))
            Spacer(Modifier.width(IbisDims.SliderOneRowGap))
            opacityRow(Modifier.weight(1f))
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            sizeRow(Modifier.fillMaxWidth())
            opacityRow(Modifier.fillMaxWidth())
        }
    }
}

/**
 * Where the parts of one slider row sit (dp from the row's left edge) on a row [width] wide. On
 * the 392 dp reference phone: value 0–58, − centred at 73, track 92–355, + centred at 375; the
 * left-handed rows mirror that (values on the right).
 */
internal class SliderRowGeometry(private val width: Float, leftHanded: Boolean) {
    private val touch = IbisDims.SliderButtonTouch.value
    private val reference = IbisDims.LayerWindowWidth.value + IbisDims.LayerWindowSideRoom.value // 392
    private val minusInset = IbisDims.SliderMinusCenterX.value                                     // 73
    private val trackInset = IbisDims.SliderTrackStart.value                                       // 92
    private val plusInset = reference - IbisDims.SliderPlusCenterX.value                           // 17
    private val trackEndInset = reference - IbisDims.SliderTrackEnd.value                          // 37

    /** The value box: x and width. */
    val valueX: Float = if (leftHanded) width - IbisDims.SliderValueWidth.value else 0f
    val valueWidth: Float = IbisDims.SliderValueWidth.value

    /** Centres of the − and + buttons. */
    val minusCenter: Float = if (leftHanded) plusInset else minusInset
    val plusCenter: Float = if (leftHanded) width - minusInset else width - plusInset

    /** The track (its touch area spans the same x range, the whole row height). */
    val trackStart: Float = if (leftHanded) trackEndInset else trackInset
    val trackEnd: Float = if (leftHanded) width - trackInset else width - trackEndInset

    /** Left edge of the 40 dp touch box of a button centred at [center], kept on the row. */
    fun buttonX(center: Float): Float = (center - touch / 2f).coerceIn(0f, (width - touch).coerceAtLeast(0f))
}

@Composable
private fun IbisSliderRow(
    modifier: Modifier,
    kind: IncrementKind,
    label: String,
    typeLabel: String,
    minusLabel: String,
    plusLabel: String,
    valueText: String,
    stateText: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
    onStep: (up: Boolean) -> Unit,
    onStepDone: () -> Unit,
    onDragging: (Boolean) -> Unit,
    onEdit: () -> Unit,
    leftHanded: Boolean,
    drawTrack: DrawScope.(left: Float, right: Float, cy: Float, fraction: Float) -> Unit,
) {
    BoxWithConstraints(
        modifier
            .height(IbisDims.SliderRowHeight)
            // The whole row (gaps included) keeps its touches from the canvas behind it.
            .blockCanvasTouches(),
    ) {
        val w = maxWidth.value
        val g = SliderRowGeometry(w, leftHanded)
        val halo = with(LocalDensity.current) { (IbisDims.SliderValueHalo * 3).toPx() }
        // ---- the value: a tap types it, a long-press opens the Step popup of its kind.
        Box(
            Modifier
                .offset(x = g.valueX.dp)
                .width(g.valueWidth.dp)
                .height(IbisDims.SliderRowHeight)
                .stepOnLongPress(kind)
                .clickable(onClickLabel = typeLabel, role = Role.Button, onClick = onEdit),
            contentAlignment = if (leftHanded) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Text(
                valueText,
                // ibisPaint's dark value with a thin white halo: readable over any artwork.
                style = TextStyle(fontSize = IbisDims.SliderValueText, color = IbisColors.SliderValue, shadow = Shadow(Color.White, Offset.Zero, blurRadius = halo)),
                textAlign = if (leftHanded) TextAlign.Start else TextAlign.End,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        // ---- the track
        RelativeTrack(
            fraction = fraction,
            onFraction = onFraction,
            onDragging = onDragging,
            label = label,
            stateText = stateText,
            drawTrack = drawTrack,
            modifier = Modifier
                .offset(x = g.trackStart.dp)
                .width((g.trackEnd - g.trackStart).coerceAtLeast(1f).dp)
                .height(IbisDims.SliderRowHeight),
        )
        // ---- − and +
        StepButton(minusLabel, plus = false, onStep = { onStep(false) }, onDone = onStepDone, glyphCenter = (g.minusCenter - g.buttonX(g.minusCenter)).dp, modifier = Modifier.offset(x = g.buttonX(g.minusCenter).dp))
        StepButton(plusLabel, plus = true, onStep = { onStep(true) }, onDone = onStepDone, glyphCenter = (g.plusCenter - g.buttonX(g.plusCenter)).dp, modifier = Modifier.offset(x = g.buttonX(g.plusCenter).dp))
    }
}

/** The size track: [IbisColors.SizeFill] up to the thumb on [IbisColors.SliderTrack]. */
private fun DrawScope.sizeTrack(left: Float, right: Float, cy: Float, f: Float) {
    val h = IbisDims.SliderTrackThickness.toPx()
    val corner = CornerRadius(IbisDims.SliderTrackRadius.toPx())
    val r = IbisDims.SliderThumb.toPx() / 2f
    val top = cy - h / 2f
    drawRoundRect(IbisColors.SliderTrack, Offset(left, top), Size(right - left, h), corner)
    val x = left + r + (right - left - 2 * r) * f
    drawRoundRect(IbisColors.SizeFill, Offset(left, top), Size((x - left).coerceAtLeast(h), h), corner)
}

/** The opacity track: a 4 dp checker under a gradient from transparent to the current [color]. */
private fun DrawScope.opacityTrack(left: Float, right: Float, cy: Float, @Suppress("UNUSED_PARAMETER") f: Float, color: Color) {
    val h = IbisDims.SliderTrackThickness.toPx()
    val top = cy - h / 2f
    val cell = IbisDims.SliderChecker.toPx().coerceAtLeast(1f)
    val corner = CornerRadius(IbisDims.SliderTrackRadius.toPx())
    drawRoundRect(IbisColors.CheckerLight, Offset(left, top), Size(right - left, h), corner)
    clipRect(left + corner.x / 2f, top, right - corner.x / 2f, top + h) {
        var y = top
        var row = 0
        while (y < top + h) {
            var x = left + if (row % 2 == 0) 0f else cell
            while (x < right) {
                drawRect(IbisColors.CheckerLight2, Offset(x, y), Size(min(cell, right - x), min(cell, top + h - y)))
                x += 2 * cell
            }
            y += cell
            row++
        }
    }
    drawRoundRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color), startX = left, endX = right), Offset(left, top), Size(right - left, h), corner)
}

/**
 * A relative horizontal slider (v1.5): the value moves by the finger's travel over the track,
 * wherever the finger lands, so a touch never makes it jump. Accessibility: [label] with
 * [stateText] and `setProgress`.
 */
@Composable
private fun RelativeTrack(
    fraction: Float,
    onFraction: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    label: String,
    stateText: String,
    drawTrack: DrawScope.(left: Float, right: Float, cy: Float, fraction: Float) -> Unit,
    modifier: Modifier,
) {
    val current by rememberUpdatedState(fraction)
    val onFractionState by rememberUpdatedState(onFraction)
    val onDraggingState by rememberUpdatedState(onDragging)
    Box(
        modifier
            .semantics {
                contentDescription = label
                stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                setProgress { v -> onFractionState(v.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val track = (size.width - IbisDims.SliderThumb.toPx()).coerceAtLeast(1f)
                    var f = current
                    onDraggingState(true)
                    try {
                        drag(down.id) { change ->
                            val dx = change.positionChange().x
                            change.consume()
                            if (dx != 0f) {
                                f = (f + dx / track).coerceIn(0f, 1f)
                                onFractionState(f)
                            }
                        }
                    } finally {
                        onDraggingState(false)
                    }
                }
            }
            .drawBehind {
                val r = IbisDims.SliderThumb.toPx() / 2f
                val f = fraction.coerceIn(0f, 1f)
                drawTrack(0f, size.width, size.height / 2f, f)
                val c = Offset(r + (size.width - 2 * r) * f, size.height / 2f)
                drawCircle(Color.Black.copy(alpha = 0.3f), r + IbisDims.SliderThumbRing.toPx(), c)
                drawCircle(IbisColors.SliderThumb, r, c)
            },
    )
}

/**
 * ibisPaint's round − / + button: a 22 dp [IbisColors.SliderButton] disc with a white glyph,
 * centred [glyphCenter] from the left of its 40 × 40 dp touch box. Holding repeats [onStep]
 * (after a short delay); [onDone] runs when the finger lifts (the preset is saved then).
 */
@Composable
private fun StepButton(label: String, plus: Boolean, onStep: () -> Unit, onDone: () -> Unit, glyphCenter: Dp, modifier: Modifier) {
    val step by rememberUpdatedState(onStep)
    val done by rememberUpdatedState(onDone)
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .size(IbisDims.SliderButtonTouch)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { step(); done(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    step()
                    val repeat = scope.launch {
                        delay(REPEAT_DELAY_MS)
                        while (true) { step(); delay(REPEAT_INTERVAL_MS) }
                    }
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        repeat.cancel()
                        done()
                    }
                }
            }
            .drawBehind {
                val r = IbisDims.SliderButton.toPx() / 2f
                val c = Offset(glyphCenter.toPx(), size.height / 2f)
                drawCircle(IbisColors.SliderButton, r, c)
                val arm = r * 0.5f
                val stroke = 2.dp.toPx()
                drawLine(Color.White, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke, StrokeCap.Round)
                if (plus) drawLine(Color.White, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke, StrokeCap.Round)
            },
    )
}

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L

/**
 * Shown in the middle of the screen while a brush slider is dragged: the brush tip at its real
 * on-screen size ([zoom] = screen px per document px) and the value.
 */
@Composable
fun SliderPreview(controller: EditorController, kind: SliderKind, zoom: Float, modifier: Modifier = Modifier) {
    val toolId = controller.sliderToolId
    val preset = controller.presetFor(toolId) ?: return
    val fill = if (toolId == ToolId.ERASER) Color.White else Color(controller.color)
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(200.dp)) {
            val maxR = size.minDimension / 2f - 2.dp.toPx()
            val r = min(maxR, (preset.size * zoom / 2f).coerceAtLeast(1f))
            val c = Offset(size.width / 2f, size.height / 2f)
            if (kind == SliderKind.OPACITY) drawCircle(fill.copy(alpha = preset.opacity.coerceIn(0f, 1f)), r, c)
            // Double outline: readable on light and dark artwork.
            drawCircle(Color.Black.copy(alpha = 0.6f), r + 1.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White, r, c, style = Stroke(1.dp.toPx()))
        }
        Surface(color = BrushworkColors.ChromeHigh.copy(alpha = 0.95f), shape = RoundedCornerShape(50)) {
            Text(
                when (kind) {
                    SliderKind.SIZE -> "${toolId.label} size ${SliderMath.formatSize(preset.size)} px"
                    SliderKind.OPACITY -> "${toolId.label} opacity ${SliderMath.formatPercent(preset.opacity)}"
                },
                style = MaterialTheme.typography.labelLarge,
                color = BrushworkColors.OnChrome,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}
