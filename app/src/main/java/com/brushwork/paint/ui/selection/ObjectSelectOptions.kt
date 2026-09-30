package com.brushwork.paint.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.select.ObjectSelectTool
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Object select: how to use it (or, while a selection is being computed, a spinner and a cancel
 * button), selection mode, what to analyse (canvas / layer), edge refinement, then deselect and
 * the selection menu, like the other selection tools. The hint / busy state comes FIRST, like
 * the lasso's "Tap corners", so it is visible on a narrow phone without scrolling the strip.
 */
@Composable
fun ObjectSelectOptions(tool: ObjectSelectTool) {
    val s = tool.settings
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tool.busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BrushworkColors.Accent)
            Text("Selecting…", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChrome)
            ToolIconButton(Icons.Filled.Close, "Cancel object select", onClick = { tool.cancel() }, size = 40.dp)
        } else {
            Text("Tap or scribble on an object", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim)
        }
        Divider()
        SelectionModeButtons(tool.mode) { tool.mode = it }
        Divider()
        ChoiceChip("Refer", SampleSource.entries, s.source, { it.label }) { tool.settings = tool.settings.copy(source = it) }
        ToggleChip("Refine edges", s.refineEdges) { tool.settings = tool.settings.copy(refineEdges = it) }
        Divider()
        var menu by remember { mutableStateOf(false) }
        ToolIconButton(Icons.Outlined.Deselect, "Deselect", onClick = { tool.controller.deselect() }, enabled = tool.controller.selection != null, size = 40.dp)
        ToolIconButton(Icons.Outlined.HighlightAlt, "Selection menu", onClick = { menu = true }, size = 40.dp)
        if (menu) SelectionPanel(tool.controller) { menu = false }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(BrushworkColors.ChromeBorder))
}
