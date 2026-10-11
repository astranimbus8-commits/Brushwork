package com.brushwork.paint.ui.remove

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.remove.RemoveTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Remove tool's strip (v1.8, design §4.6; area G converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun removeStrip(tool: RemoveTool, c: EditorController): StripSpec = StripSpec.legacy { RemoveToolOptions(tool) }
