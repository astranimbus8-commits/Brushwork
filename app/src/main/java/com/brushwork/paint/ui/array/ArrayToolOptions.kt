package com.brushwork.paint.ui.array

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.brushwork.paint.tools.array.ArrayTool

/**
 * The Array tool's options strip (v1.7 item 3, §3.3; area E): the "Line | Circle | Curve |
 * Transform" segments, "Count", and the button that opens the Array sheet. Emits one Row, like
 * every tool's options.
 *
 * Foundation stub: an empty row (the options panel hides for the tool until area E fills it).
 */
@Composable
fun ArrayToolOptions(tool: ArrayTool) {
    Row(verticalAlignment = Alignment.CenterVertically) {}
}
