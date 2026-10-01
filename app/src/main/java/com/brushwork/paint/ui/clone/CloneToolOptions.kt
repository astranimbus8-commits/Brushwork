package com.brushwork.paint.ui.clone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Options strip of the clone stamp (v1.5 §4.2; owned by A6): Set source, Aligned, Sample, Show
 * source. Foundation stub: a hint only.
 */
@Composable
fun CloneToolOptions(tool: CloneTool) {
    Text("Clone stamp is coming soon", style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, maxLines = 1)
}
