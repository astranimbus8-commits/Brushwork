package com.brushwork.paint.ui.mask

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Options strip of the Masks tool (v1.5 §4.3; owned by A5): target, + Linear / Radial / Brush,
 * mode, invert, overlay, Adjust…, Components. Foundation stub: a hint only.
 */
@Composable
fun MaskToolOptions(tool: MaskTool) {
    Text("Masks are coming soon", style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, maxLines = 1)
}
