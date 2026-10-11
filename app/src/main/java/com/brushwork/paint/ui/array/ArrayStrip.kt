package com.brushwork.paint.ui.array

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Array tool's strip (v1.8, design §4.6; area B converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun arrayStrip(tool: ArrayTool, c: EditorController): StripSpec = StripSpec.legacy { ArrayToolOptions(tool) }
