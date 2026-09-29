package com.brushwork.paint.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.ln
import kotlin.math.roundToInt

/** A top-bar action; actions that don't fit the width move into the overflow menu. */
data class BarAction(val label: String, val icon: ImageVector, val selected: Boolean = false, val onClick: () -> Unit)

/** An overflow menu entry; [checked] non-null shows a check mark when true (toggles). */
data class MenuEntry(
    val label: String,
    val icon: ImageVector,
    val checked: Boolean? = null,
    val dividerBefore: Boolean = false,
    val onClick: () -> Unit,
)

private val BarButtonSize = 40.dp
private val TitleMinWidth = 104.dp

/**
 * Compact editor top bar: back, document name + size, as many [actions] as fit the width, and
 * an overflow menu holding the remaining actions followed by [menu].
 */
@Composable
fun EditorTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actions: List<BarAction>,
    menu: List<MenuEntry>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val reserved = BarButtonSize * 2 + TitleMinWidth + 8.dp
        val fitting = ((maxWidth - reserved) / BarButtonSize).toInt().coerceIn(0, actions.size)
        val inBar = actions.take(fitting)
        val overflow = actions.drop(fitting).map { MenuEntry(it.label, it.icon, checked = if (it.selected) true else null, onClick = it.onClick) }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to gallery", onBack, size = BarButtonSize)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = BrushworkColors.OnChrome)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = BrushworkColors.OnChromeDim)
            }
            inBar.forEach { ToolIconButton(it.icon, it.label, it.onClick, selected = it.selected, size = BarButtonSize) }
            OverflowMenu(overflow, menu)
        }
    }
}

@Composable
private fun OverflowMenu(first: List<MenuEntry>, rest: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolIconButton(Icons.Filled.MoreVert, "More options", { open = true }, size = BarButtonSize)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val entries = if (first.isEmpty()) rest else first + rest.mapIndexed { i, e -> if (i == 0) e.copy(dividerBefore = true) else e }
            entries.forEach { e ->
                if (e.dividerBefore) HorizontalDivider(color = BrushworkColors.ChromeBorder)
                DropdownMenuItem(
                    text = { Text(e.label) },
                    leadingIcon = { Icon(e.icon, contentDescription = null) },
                    trailingIcon = if (e.checked == true) ({ Icon(Icons.Filled.Check, contentDescription = "On", tint = BrushworkColors.Accent) }) else null,
                    onClick = { open = false; e.onClick() },
                )
            }
        }
    }
}

/**
 * Bottom tool bar (ibisPaint-like): tool picker, brush/eraser toggle, brush size, color,
 * layers, undo, redo. Seven 44dp targets fit a 360dp-wide phone.
 */
@Composable
fun Hotbar(
    controller: EditorController,
    onToolPicker: () -> Unit,
    onBrushPanel: () -> Unit,
    onColorPanel: () -> Unit,
    onLayersPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = controller.activeToolId
    val paintTool = controller.lastPaintTool
    val erasing = active == ToolId.ERASER
    val preset = controller.presetFor(controller.sliderToolId)
    controller.layersVersion // observe layer changes for the active layer number
    val layerNumber = controller.doc.activeLayerIndex.coerceIn(0, (controller.doc.layers.size - 1).coerceAtLeast(0)) + 1
    val pending = controller.currentTool.hasPendingWork
    val session = controller.filterSession
    Box(
        modifier
            .fillMaxWidth()
            .background(BrushworkColors.Chrome)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.Center,
    ) {
        // Capped width so the buttons stay together on tablets and in landscape.
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIconButton(EditorIcons.tool(active), "Tools (current: ${active.label})", onToolPicker)
            ToolIconButton(
                icon = if (erasing) EditorIcons.Eraser else EditorIcons.tool(paintTool),
                contentDescription = if (erasing) "Eraser on: switch to ${paintTool.label.lowercase()}" else "Switch to eraser",
                onClick = { controller.toggleEraser() },
                selected = erasing || active == paintTool,
            )
            BrushSizeButton(preset?.size ?: 0f, onBrushPanel)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Open color picker", role = Role.Button, onClick = onColorPanel),
                contentAlignment = Alignment.Center,
            ) { ColorSwatch(controller.color, size = 30.dp) }
            LayersButton(layerNumber, onLayersPanel)
            ToolIconButton(Icons.AutoMirrored.Filled.Undo, "Undo", { controller.undo() }, enabled = controller.canUndo || pending || session != null)
            ToolIconButton(Icons.AutoMirrored.Filled.Redo, "Redo", { controller.redo() }, enabled = controller.canRedo && !pending && session == null)
        }
    }
}

/** Shows the current brush diameter as a dot (log-scaled) and a number; opens the brush panel. */
@Composable
private fun BrushSizeButton(size: Float, onClick: () -> Unit) {
    val dot = (4f + 14f * (ln(size.coerceIn(0.5f, 1000f) / 0.5f) / ln(2000f))).dp
    Column(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Open brush settings", role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(dot).clip(CircleShape).background(BrushworkColors.OnChrome))
        }
        Text(SliderMath.formatSize(size), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BrushworkColors.OnChrome, maxLines = 1)
    }
}

@Composable
private fun LayersButton(number: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Open layers (active layer $number)", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Layers, contentDescription = null, tint = BrushworkColors.OnChrome)
        Text(
            number.toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 2.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BrushworkColors.AccentDim)
                .border(1.dp, BrushworkColors.Chrome, RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp),
        )
    }
}

/** Grid of every tool; the current one is highlighted. */
@Composable
fun ToolPickerSheet(controller: EditorController, onDismiss: () -> Unit) {
    BwSheet(title = "Tools", onDismiss = onDismiss) {
        val active = controller.activeToolId
        ToolId.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { id ->
                    ToolTile(id, selected = id == active, modifier = Modifier.weight(1f)) {
                        controller.selectTool(id)
                        onDismiss()
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToolTile(id: ToolId, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) BrushworkColors.AccentDim else BrushworkColors.ChromeHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(EditorIcons.tool(id), contentDescription = null, tint = if (selected) Color.White else BrushworkColors.OnChrome)
        Spacer(Modifier.height(4.dp))
        Text(
            id.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else BrushworkColors.OnChrome,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Floating apply / discard buttons for a tool with uncommitted editable work. */
@Composable
fun PendingWorkBar(controller: EditorController, tool: Tool, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { tool.discard(); controller.invalidateOverlay() },
            shape = CircleShape,
            color = BrushworkColors.ChromeHigh,
            shadowElevation = 4.dp,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, contentDescription = "Discard ${tool.id.label.lowercase()} edit", tint = BrushworkColors.Danger) }
        }
        Surface(
            onClick = { tool.commit(); controller.invalidateOverlay() },
            shape = CircleShape,
            color = BrushworkColors.Accent,
            shadowElevation = 4.dp,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, contentDescription = "Apply ${tool.id.label.lowercase()} edit", tint = Color(0xFF002B55)) }
        }
    }
}

/**
 * Full-screen scrim that blocks all input while a long operation runs. [onCancel] non-null
 * offers a Stop button (cancellable operations).
 */
@Composable
fun BusyOverlay(message: String, progress: Float, onCancel: (() -> Unit)? = null) {
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
                if (progress < 0f) {
                    LinearProgressIndicator(color = BrushworkColors.Accent, trackColor = BrushworkColors.ChromeBorder, modifier = Modifier.width(200.dp))
                } else {
                    val p = progress.coerceIn(0f, 1f)
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

/** Small toast-like pill (undo/redo feedback, zoom readout). */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier) {
    Surface(color = BrushworkColors.ChromeHigh.copy(alpha = 0.94f), shape = RoundedCornerShape(50), shadowElevation = 2.dp, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = BrushworkColors.OnChrome, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
