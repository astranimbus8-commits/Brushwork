package com.brushwork.paint.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import kotlin.math.roundToInt

/** A More-menu entry; [checked] non-null shows a check mark when true (toggles). */
data class MenuEntry(
    val label: String,
    val icon: ImageVector,
    val checked: Boolean? = null,
    val dividerBefore: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Drops a canvas stroke that is still in progress before a chrome button acts. A button click
 * fires on release, possibly while another finger is still drawing; undo, tool switches or
 * committing tool work must never run in the middle of a tool gesture. The canvas' remaining
 * events for that pointer are then ignored by the controller.
 */
internal fun EditorController.endCanvasGesture() {
    if (isInteracting) pointerCancel()
}

/**
 * Keeps touches on a chrome container (including the gaps between its controls) from falling
 * through to the full-screen canvas behind it, like a Material Surface does.
 */
internal fun Modifier.blockCanvasTouches(): Modifier = pointerInput(Unit) {}

/** Whether the Redo button does something right now (see [EditorController.redo]). */
internal fun canRedoNow(controller: EditorController): Boolean {
    if (controller.filterSession != null) return false
    val tool = controller.currentTool
    // A tool that took back a step of its pending work (a curve point) redoes that first; an
    // untouched automatic lift (transform tool) doesn't block redo (see Tool.hasUserChanges).
    if (tool.hasPendingWork && tool.canRedoStep) return true
    return controller.canRedo && !tool.hasUserChanges
}

/** Size of the floating ✓ / ✕ buttons (v1.6: 44 dp, y 209–253 above the layer window on the reference phone). */
internal val PendingButtonSize = 44.dp

/**
 * Floating apply / discard buttons for a tool with uncommitted editable work; [vertical] stacks
 * them (apply on top). The editor places them (v1.6 §3.7.9): centred above the slider rows,
 * right-aligned while the tool menu is open, above the layer window's top-right corner while it
 * is open.
 */
@Composable
fun PendingWorkBar(controller: EditorController, tool: Tool, modifier: Modifier = Modifier, vertical: Boolean = false) {
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PendingButtons(controller, tool, applyFirst = true)
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            PendingButtons(controller, tool, applyFirst = false)
        }
    }
}

@Composable
private fun PendingButtons(controller: EditorController, tool: Tool, applyFirst: Boolean) {
    val apply: @Composable () -> Unit = {
        Surface(
            onClick = { controller.endCanvasGesture(); tool.commit(); controller.invalidateOverlay() },
            shape = CircleShape,
            color = IbisColors.Accent,
            shadowElevation = 4.dp,
            modifier = Modifier.size(PendingButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, contentDescription = "Apply ${tool.id.label.lowercase()} edit", tint = Color.White) }
        }
    }
    if (applyFirst) apply()
    Surface(
        onClick = { controller.endCanvasGesture(); tool.discard(); controller.invalidateOverlay() },
        shape = CircleShape,
        color = IbisColors.SliderButton,
        shadowElevation = 4.dp,
        modifier = Modifier.size(PendingButtonSize),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, contentDescription = "Discard ${tool.id.label.lowercase()} edit", tint = BrushworkColors.Danger) }
    }
    if (!applyFirst) apply()
}

/**
 * Full-screen scrim that blocks all input while a long operation runs. [progress] (0..1, < 0 =
 * indeterminate) is read here so frequent progress updates only recompose the overlay.
 * [onCancel] non-null offers a Stop button (cancellable operations).
 */
@Composable
fun BusyOverlay(message: String, progress: () -> Float, onCancel: (() -> Unit)? = null) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = BrushworkColors.ChromeHigh, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.widthIn(min = 220.dp, max = 300.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, style = MaterialTheme.typography.titleSmall, color = BrushworkColors.OnChrome, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                val raw = progress()
                if (raw < 0f || raw.isNaN()) {
                    LinearProgressIndicator(color = BrushworkColors.Accent, trackColor = BrushworkColors.ChromeBorder, modifier = Modifier.width(200.dp))
                } else {
                    val p = raw.coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { p }, color = BrushworkColors.Accent, trackColor = BrushworkColors.ChromeBorder, modifier = Modifier.width(200.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("${(p * 100f).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim)
                }
                if (onCancel != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        border = BorderStroke(1.dp, BrushworkColors.ChromeBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrushworkColors.Accent),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                }
            }
        }
    }
}

/** Small toast-like pill (undo/redo feedback, zoom readout, the increments readout). */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier) {
    Surface(color = IbisColors.Sheet, shape = RoundedCornerShape(50), shadowElevation = 2.dp, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = BrushworkColors.OnChrome, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
