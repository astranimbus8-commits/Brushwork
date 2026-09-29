package com.brushwork.paint.ui.selection

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeTool

// STUB - replaced by the selection module.
/** Select all / deselect / invert / grow / shrink / feather + smart select (subject, sky, ...). */
@Composable
fun SelectionPanel(controller: EditorController, onDismiss: () -> Unit) {}

@Composable fun MagicWandOptions(tool: MagicWandTool) {}
@Composable fun LassoOptions(tool: LassoTool) {}
@Composable fun MarqueeOptions(tool: MarqueeTool) {}
@Composable fun FillOptions(tool: FillTool) {}
@Composable fun EyedropperOptions(tool: EyedropperTool) {}