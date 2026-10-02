package com.brushwork.paint.ui.textframes

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.brushwork.paint.tools.text.frames.TextFrameTool

/**
 * The Text frames tool's options strip (v1.6, §3.6; area D): Link…, Unlink here, Delete frame,
 * Edit story, Threads, "+ N characters"... Emits one Row, like every tool's options.
 *
 * Foundation stub: an empty row (the strip shows nothing for the tool yet).
 */
@Composable
fun TextFrameToolOptions(tool: TextFrameTool) {
    Row(verticalAlignment = Alignment.CenterVertically) {}
}
