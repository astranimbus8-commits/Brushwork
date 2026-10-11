package com.brushwork.paint.ui.textframes

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Text frames tool's strip (v1.8, design §4.6; area G converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun textFrameStrip(tool: TextFrameTool, c: EditorController): StripSpec = StripSpec.legacy { TextFrameToolOptions(tool) }
