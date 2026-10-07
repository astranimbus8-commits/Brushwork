package com.brushwork.paint.ui.pathfinder

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.brushwork.paint.tools.pathfinder.PathfinderTool

/**
 * The Pathfinder tool's options strip (v1.7 item 20, §3.20; area G): the ten operations, "Select
 * all objects" and the hint. Emits one Row, like every tool's options.
 *
 * Foundation stub: an empty row (the options panel hides for the tool until area G fills it).
 */
@Composable
fun PathfinderOptions(tool: PathfinderTool) {
    Row(verticalAlignment = Alignment.CenterVertically) {}
}
