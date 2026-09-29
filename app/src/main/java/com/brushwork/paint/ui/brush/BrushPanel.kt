package com.brushwork.paint.ui.brush

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushTool

// STUB - replaced by the brush module.
/** Brush library + all brush settings for the current paint tool (bottom sheet / dialog). */
@Composable
fun BrushPanel(controller: EditorController, onDismiss: () -> Unit) {}

/** Compact options strip shown under the top bar while a paint tool is active. */
@Composable
fun BrushToolOptions(tool: BrushTool) {}