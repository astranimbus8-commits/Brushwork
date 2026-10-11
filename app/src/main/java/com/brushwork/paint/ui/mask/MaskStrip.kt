package com.brushwork.paint.ui.mask

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Masks tool's strip (v1.8, design §4.6; area G converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun maskStrip(tool: MaskTool, c: EditorController): StripSpec = StripSpec.legacy { MaskToolOptions(tool) }
