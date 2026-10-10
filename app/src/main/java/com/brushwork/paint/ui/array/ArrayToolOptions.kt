package com.brushwork.paint.ui.array

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.vector.ActionChip

/**
 * The Array tool's options strip (v1.7 item 3, §3.3; area E): one chip that shows the array
 * ("Line × 3") and opens the Array sheet ([ArraySheet], known as "Array settings"),
 * "Finish source edit" while a raster source is being edited, and "Rendering array…" while a
 * vector array's copies are on their way or a large cache has rendered for over 300 ms
 * ([ArrayTool.rendering]). Without an array on the active layer: how to make one.
 * Also hosts the sheet and the "Apply turns the text into pixels" confirmation.
 */
@Composable
fun ArrayToolOptions(tool: ArrayTool) {
    val c = tool.controller
    // Every edit, undo and layer change (the array lives on the layer, which is not observable).
    c.editCount
    c.layersVersion
    val layer = tool.target
    val spec = tool.shownSpec()
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            layer == null || spec == null -> Text(
                NO_ARRAY_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            spec.editingSource -> ActionChip(ArrayLabels.FINISH_SOURCE, EditorIcons.tool(ToolId.ARRAY)) { tool.finishSource() }
            else -> ActionChip(
                "${modeWord(spec.mode)} × ${spec.count}",
                EditorIcons.tool(ToolId.ARRAY),
                contentDescription = SETTINGS,
            ) { tool.sheetOpen = true }
        }
        if (tool.rendering) {
            Text(
                ArrayLabels.RENDERING,
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
    if (tool.sheetOpen && layer != null && spec != null) ArraySheet(tool, layer, spec) { tool.closeSheet() }
    if (tool.pendingTextApply != null) {
        BwDialog(
            title = ArrayLabels.APPLY_TEXT_ASK,
            onDismiss = { tool.answerTextApply(false) },
            confirmText = APPLY_CONFIRM,
            onConfirm = { tool.answerTextApply(true) },
        ) {
            Text(APPLY_TEXT_DETAIL, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The visible word of [mode] (the segments' text; their spoken names are [ArrayLabels]). */
internal fun modeWord(mode: ArrayMode): String = when (mode) {
    ArrayMode.LINE -> "Line"
    ArrayMode.CIRCLE -> "Circle"
    ArrayMode.CURVE -> "Curve"
    ArrayMode.TRANSFORM -> "Transform"
}

/** The strip chip that opens the Array sheet (I10: the visible text changes, the name does not). */
internal const val SETTINGS = "Array settings"

/** What the strip says on a layer without an array. */
internal const val NO_ARRAY_HINT = "Select pixels or objects and tap Array, or use Array… in the layer menu"

private const val APPLY_CONFIRM = "Apply"
private const val APPLY_TEXT_DETAIL = "The copies stay as they look, but the text can no longer be edited. Undo brings the live array back."
