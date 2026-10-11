package com.brushwork.paint.ui.clone

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Clone stamp's strip (v1.8, design §4.6; area D converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun cloneStrip(tool: CloneTool, c: EditorController): StripSpec = StripSpec.legacy { CloneToolOptions(tool) }
