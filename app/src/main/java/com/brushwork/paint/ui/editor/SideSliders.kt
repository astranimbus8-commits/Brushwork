package com.brushwork.paint.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.min
import kotlin.math.roundToInt

/** Which side slider is being dragged (drives the preview overlay). */
enum class SliderKind { SIZE, OPACITY }

/**
 * ibisPaint-style vertical brush size (logarithmic 0.5-1000 px) and opacity sliders for the
 * preset of [EditorController.sliderToolId], plus an eyedropper shortcut. [sideBySide] puts the
 * two sliders in a row (short landscape screens).
 */
@Composable
fun SideSliders(
    controller: EditorController,
    sliderLength: Dp,
    sideBySide: Boolean,
    onDragChange: (SliderKind?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preset = controller.presetFor(controller.sliderToolId) ?: return
    val size: @Composable () -> Unit = {
        VerticalSlider(
            fraction = SliderMath.sizeToFraction(preset.size),
            onFraction = { f ->
                // Read the tool at event time: the active tool may change between recompositions.
                val id = controller.sliderToolId
                controller.presetFor(id)?.let { controller.updatePreset(id, it.copy(size = SliderMath.fractionToSize(f))) }
            },
            valueText = SliderMath.formatSize(preset.size),
            label = "Brush size",
            length = sliderLength,
            onDragging = { onDragChange(if (it) SliderKind.SIZE else null) },
        )
    }
    val opacity: @Composable () -> Unit = {
        VerticalSlider(
            fraction = preset.opacity,
            onFraction = { f ->
                val id = controller.sliderToolId
                controller.presetFor(id)?.let { controller.updatePreset(id, it.copy(opacity = (f * 100f).roundToInt() / 100f)) }
            },
            valueText = SliderMath.formatPercent(preset.opacity),
            label = "Brush opacity",
            length = sliderLength,
            onDragging = { onDragChange(if (it) SliderKind.OPACITY else null) },
        )
    }
    val eyedropperActive = controller.activeToolId == ToolId.EYEDROPPER
    val eyedropper: @Composable () -> Unit = {
        Surface(color = BrushworkColors.Chrome.copy(alpha = 0.88f), shape = CircleShape) {
            ToolIconButton(
                icon = Icons.Filled.Colorize,
                contentDescription = if (eyedropperActive) "Back to ${controller.lastPaintTool.label}" else "Eyedropper",
                onClick = { controller.selectTool(if (eyedropperActive) controller.lastPaintTool else ToolId.EYEDROPPER) },
                selected = eyedropperActive,
                size = 40.dp,
            )
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { size(); opacity() }
        } else {
            size()
            opacity()
        }
        eyedropper()
    }
}

/**
 * A vertical slider (top = max) that changes its value RELATIVELY while dragging, so touching
 * it never makes the value jump.
 */
@Composable
private fun VerticalSlider(
    fraction: Float,
    onFraction: (Float) -> Unit,
    valueText: String,
    label: String,
    length: Dp,
    onDragging: (Boolean) -> Unit,
) {
    val current by rememberUpdatedState(fraction)
    val onFractionState by rememberUpdatedState(onFraction)
    val onDraggingState by rememberUpdatedState(onDragging)
    val shape = RoundedCornerShape(16.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(34.dp)
                .height(length)
                .clip(shape)
                .background(BrushworkColors.Chrome.copy(alpha = 0.85f))
                .border(1.dp, BrushworkColors.ChromeBorder, shape)
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
                        val track = (size.height - 8.dp.toPx()).coerceAtLeast(1f)
                        var f = current
                        onDraggingState(true)
                        try {
                            drag(down.id) { change ->
                                val dy = change.positionChange().y
                                change.consume()
                                if (dy != 0f) {
                                    f = (f - dy / track).coerceIn(0f, 1f)
                                    onFractionState(f)
                                }
                            }
                        } finally {
                            onDraggingState(false)
                        }
                    }
                }
                .drawBehind {
                    val pad = 4.dp.toPx()
                    val trackH = size.height - 2 * pad
                    val y = pad + trackH * (1f - fraction.coerceIn(0f, 1f))
                    drawRoundRect(
                        color = BrushworkColors.Accent.copy(alpha = 0.45f),
                        topLeft = Offset(pad, y),
                        size = Size(size.width - 2 * pad, size.height - pad - y),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                    val thumbH = 3.dp.toPx()
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(pad + 2.dp.toPx(), (y - thumbH / 2f).coerceIn(pad, size.height - pad - thumbH)),
                        size = Size(size.width - 2 * pad - 4.dp.toPx(), thumbH),
                        cornerRadius = CornerRadius(thumbH / 2f),
                    )
                },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            valueText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = BrushworkColors.OnChrome,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(BrushworkColors.Chrome.copy(alpha = 0.85f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * Shown in the middle of the screen while a side slider is dragged: the brush tip at its real
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
