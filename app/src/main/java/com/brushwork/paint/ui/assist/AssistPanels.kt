package com.brushwork.paint.ui.assist

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerTool

// STUB - replaced by the assist module.
@Composable
fun RulerPanel(controller: EditorController, onDismiss: () -> Unit) {}

@Composable
fun GridPanel(controller: EditorController, onDismiss: () -> Unit) {}

@Composable
fun StabilizerPanel(controller: EditorController, onDismiss: () -> Unit) {}

@Composable
fun RulerToolOptions(tool: RulerTool) {}