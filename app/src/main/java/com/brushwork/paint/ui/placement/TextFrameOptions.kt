package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TextRotateVertical
import androidx.compose.material.icons.filled.TextRotationNone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Options strip of the text tool; also hosts the text editor dialog and the "Numbers" sheet.
 * (An edited text layer whose text is emptied is deleted without a question; undo restores it.)
 */
@Composable
fun TextToolOptions(tool: TextTool) {
    val item = tool.item
    val c = tool.controller
    // The active layer isn't Compose state: follow the counters that change with it.
    val activeText = remember(c.layersVersion, c.editCount) { c.activeLayer.takeIf { it.isTextLayer } }
    val onPath = item?.path?.isActive == true
    // Buttons first, hints last: on a narrow phone the strip scrolls, and only a hint may be cut.
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            item == null && activeText != null -> StripButton(Icons.Filled.Edit, "Edit text") { tool.editLayer(activeText, openEditor = true) }
            item == null -> {
                Icon(Icons.Filled.TextFields, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
            }
            else -> {
                StripButton(Icons.Filled.Edit, "Edit text") { tool.openEditor() }
                StripButton(Icons.Filled.Tune, "Numbers") { tool.numbersOpen = true }
            }
        }
        ToolIconButton(
            icon = if (tool.isVertical) Icons.Filled.TextRotateVertical else Icons.Filled.TextRotationNone,
            contentDescription = if (tool.isVertical) "Vertical text (tap for horizontal)" else "Horizontal text (tap for vertical)",
            onClick = { tool.toggleVertical() },
            selected = tool.isVertical && !onPath,
            enabled = !onPath,
        )
        Spacer(Modifier.width(4.dp))
        Hint(
            when {
                onPath -> "Drag the dots to shape the path, two fingers to scale or turn"
                item != null && item.spec.box.wrapFor(item.spec.vertical) > 0f ->
                    if (item.spec.vertical) "Bottom / side handles: box size" else "Side / bottom handles: box size"
                item != null -> if (item.spec.vertical) "Bottom handle: box height" else "Side handle: box width"
                activeText != null -> "or tap a text to edit it, empty canvas to add"
                else -> "Tap the canvas to add text, or a text to edit it"
            }
        )
    }
    if (tool.editorOpen && item != null) TextEditorDialog(tool)
    if (tool.numbersOpen && item != null && !tool.editorOpen) TextNumbersSheet(tool)
}

/** Options strip of the frame divider; also hosts the frame settings sheet and the grid dialog. */
@Composable
fun FrameDividerOptions(tool: FrameDividerTool) {
    val c = tool.controller
    // The frame status depends on layer contents that aren't Compose state: key it on the
    // counters that change with them (tool edits, any undoable edit / undo, layer changes).
    val status = remember(tool.revision, c.layersVersion, c.editCount) { tool.status() }
    // After an undo/redo or layer change, an "edited" frame may match its model again.
    LaunchedEffect(status, c.layersVersion, c.editCount) {
        if (status == FrameDividerTool.Status.OUT_OF_SYNC) tool.refreshSync()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            FrameDividerTool.Status.NONE -> {
                StripButton(Icons.Filled.Add, "New frame layer") { tool.settingsOpen = true }
                Hint("then drag across a panel to divide it")
            }
            FrameDividerTool.Status.READY -> {
                Icon(
                    if (tool.removeMode) Icons.Filled.Delete else Icons.Filled.ContentCut, contentDescription = null,
                    tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Hint(if (tool.removeMode) "Tap a panel to remove it" else "Drag across panels to divide")
                Spacer(Modifier.width(4.dp))
                ToolIconButton(Icons.Filled.GridView, "Rows × columns", onClick = { tool.gridOpen = true })
                ToolIconButton(Icons.Filled.Delete, "Remove panels mode", onClick = { tool.removeMode = !tool.removeMode }, selected = tool.removeMode)
                ToolIconButton(Icons.Filled.Settings, "Frame settings / new frame layer", onClick = { tool.settingsOpen = true })
            }
            FrameDividerTool.Status.OUT_OF_SYNC -> {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = BrushworkColors.Danger, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Hint("Frame layer was edited")
                StripButton(Icons.Filled.Refresh, "Redraw") { tool.redraw() }
                StripButton(Icons.Filled.Add, "New frame") { tool.settingsOpen = true }
            }
        }
    }
    if (tool.settingsOpen) FrameSettingsSheet(tool, status)
    if (tool.gridOpen && status == FrameDividerTool.Status.READY) FrameGridDialog(tool)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
}

@Composable
private fun StripButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}
