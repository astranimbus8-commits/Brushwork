package com.brushwork.paint.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.min
import kotlin.math.roundToInt

/** Which brush slider is being dragged (drives the preview overlay) or typed. */
enum class SliderKind { SIZE, OPACITY }

private val ROW_HEIGHT = 36.dp
private val VALUE_WIDTH = 62.dp
private val TRACK_HEIGHT = 18.dp
private val THUMB_RADIUS = 8.dp

/** Screens at least this wide show both sliders side by side in one row. */
private val ONE_ROW_WIDTH = 560.dp

/**
 * The brush size (logarithmic 0.5-1000 px) and opacity sliders for the preset of
 * [EditorController.sliderToolId], in a translucent bar directly above the hotbar, plus an
 * eyedropper shortcut. Phones get two compact rows (long tracks for precise sizes), wide
 * screens one row. Tapping a value ([onEditValue]) lets the user type it; [onDragChange]
 * reports the slider under the finger (for the size preview). [leftHanded] puts the values and
 * the eyedropper on the left.
 */
@Composable
fun BrushSliderBar(
    controller: EditorController,
    leftHanded: Boolean,
    onDragChange: (SliderKind?) -> Unit,
    onEditValue: (SliderKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toolId = controller.sliderToolId
    val preset = controller.presetFor(toolId) ?: return
    val store = remember(controller) { BrushPresetStore.get(controller.appContext) }
    // Read the tool at event time: the active tool may change between recompositions.
    val finishDrag = { kind: SliderKind, dragging: Boolean ->
        onDragChange(if (dragging) kind else null)
        if (!dragging) store.persist(controller, controller.sliderToolId)
    }
    val sizeRow: @Composable (Modifier) -> Unit = { m ->
        SliderRow(
            icon = Icons.Filled.LineWeight,
            label = "${toolId.label} size",
            fraction = SliderMath.sizeToFraction(preset.size),
            valueText = "${SliderMath.formatSize(preset.size)} px",
            wedge = true,
            onFraction = { f ->
                val id = controller.sliderToolId
                controller.presetFor(id)?.let { p ->
                    val s = SliderMath.fractionToSize(f)
                    if (s != p.size) controller.updatePreset(id, p.copy(size = s))
                }
            },
            onDragging = { finishDrag(SliderKind.SIZE, it) },
            onEdit = { onEditValue(SliderKind.SIZE) },
            leftHanded = leftHanded,
            modifier = m,
        )
    }
    val opacityRow: @Composable (Modifier) -> Unit = { m ->
        SliderRow(
            icon = Icons.Filled.Opacity,
            label = "${toolId.label} opacity",
            fraction = preset.opacity,
            valueText = SliderMath.formatPercent(preset.opacity),
            wedge = false,
            onFraction = { f ->
                val id = controller.sliderToolId
                controller.presetFor(id)?.let { p ->
                    val o = (f * 100f).roundToInt() / 100f
                    if (o != p.opacity) controller.updatePreset(id, p.copy(opacity = o))
                }
            },
            onDragging = { finishDrag(SliderKind.OPACITY, it) },
            onEdit = { onEditValue(SliderKind.OPACITY) },
            leftHanded = leftHanded,
            modifier = m,
        )
    }
    val eyedropperActive = controller.activeToolId == ToolId.EYEDROPPER
    val eyedropper: @Composable () -> Unit = {
        ToolIconButton(
            icon = Icons.Filled.Colorize,
            contentDescription = if (eyedropperActive) "Back to ${controller.lastPaintTool.label}" else "Eyedropper",
            onClick = {
                controller.endCanvasGesture()
                controller.selectTool(if (controller.activeToolId == ToolId.EYEDROPPER) controller.lastPaintTool else ToolId.EYEDROPPER)
            },
            selected = eyedropperActive,
            size = 40.dp,
        )
    }
    // A Surface: touches on the bar (also between its controls) never reach the canvas below.
    Surface(color = BrushworkColors.Chrome.copy(alpha = 0.82f), contentColor = BrushworkColors.OnChrome, modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            val oneRow = maxWidth >= ONE_ROW_WIDTH
            Row(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (leftHanded) eyedropper()
                if (oneRow) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        sizeRow(Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        opacityRow(Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        sizeRow(Modifier.fillMaxWidth())
                        opacityRow(Modifier.fillMaxWidth())
                    }
                }
                if (!leftHanded) eyedropper()
            }
        }
    }
}

/** Icon, slider and the tappable value of one brush setting. */
@Composable
private fun SliderRow(
    icon: ImageVector,
    label: String,
    fraction: Float,
    valueText: String,
    wedge: Boolean,
    onFraction: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    onEdit: () -> Unit,
    leftHanded: Boolean,
    modifier: Modifier,
) {
    Row(modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        val value: @Composable () -> Unit = { ValueChip(valueText, "Type ${label.lowercase()}", onEdit) }
        if (leftHanded) value()
        Icon(icon, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.padding(horizontal = 4.dp).size(18.dp))
        HorizontalSlider(fraction, onFraction, onDragging, label, valueText, wedge, Modifier.weight(1f).fillMaxHeight())
        if (!leftHanded) value()
    }
}

/** The current value; tapping it opens the numeric input. The whole row height is the target. */
@Composable
private fun ValueChip(text: String, clickLabel: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(VALUE_WIDTH)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = BrushworkColors.OnChrome,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BrushworkColors.ChromeHigh)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/**
 * A horizontal slider that changes its value RELATIVELY while dragging (like dragging the
 * thumb from anywhere on the track), so touching it never makes the value jump.
 */
@Composable
private fun HorizontalSlider(
    fraction: Float,
    onFraction: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    label: String,
    valueText: String,
    wedge: Boolean,
    modifier: Modifier,
) {
    val current by rememberUpdatedState(fraction)
    val onFractionState by rememberUpdatedState(onFraction)
    val onDraggingState by rememberUpdatedState(onDragging)
    Box(
        modifier
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                setProgress { v -> onFractionState(v.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val track = (size.width - 2 * THUMB_RADIUS.toPx()).coerceAtLeast(1f)
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
                val r = THUMB_RADIUS.toPx()
                val h = TRACK_HEIGHT.toPx()
                val top = (size.height - h) / 2f
                val left = r
                val right = size.width - r
                val cy = size.height / 2f
                val corner = CornerRadius(h / 2f)
                drawRoundRect(BrushworkColors.ChromeHigh, Offset(left - r / 2f, top), Size(right - left + r, h), corner)
                if (wedge) {
                    // Thin-to-thick wedge: reads as "size" at a glance.
                    val p = Path().apply {
                        moveTo(left, cy)
                        lineTo(right, top + h * 0.2f)
                        lineTo(right, top + h * 0.8f)
                        close()
                    }
                    drawPath(p, BrushworkColors.OnChromeDim.copy(alpha = 0.3f))
                } else {
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, BrushworkColors.OnChrome.copy(alpha = 0.3f)), startX = left, endX = right),
                        Offset(left - r / 2f, top), Size(right - left + r, h), corner,
                    )
                }
                val x = left + (right - left) * fraction.coerceIn(0f, 1f)
                drawRoundRect(BrushworkColors.Accent.copy(alpha = 0.5f), Offset(left - r / 2f, top), Size(x - left + r / 2f, h), corner)
                drawCircle(Color.Black.copy(alpha = 0.5f), r + 1.dp.toPx(), Offset(x, cy))
                drawCircle(Color.White, r, Offset(x, cy))
            },
    )
}

/**
 * Shown in the middle of the screen while a brush slider is dragged: the brush tip at its real
 * on-screen size ([zoom] = screen px per document px) and the value.
 */
@Composable
fun SliderPreview(controller: EditorController, kind: SliderKind, zoom: Float, modifier: Modifier = Modifier) {
    val toolId = controller.sliderToolId
    val preset = controller.presetFor(toolId) ?: return
    val fill = if (toolId == ToolId.ERASER) Color.White else Color(controller.color)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
