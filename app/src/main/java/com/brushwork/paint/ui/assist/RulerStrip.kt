package com.brushwork.paint.ui.assist

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Ruler tool's strip (v1.8, design §4.6; area D converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun rulerStrip(tool: RulerTool, c: EditorController): StripSpec = StripSpec.legacy { RulerToolOptions(tool) }
