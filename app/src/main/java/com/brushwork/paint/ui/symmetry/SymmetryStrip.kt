package com.brushwork.paint.ui.symmetry

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.symmetry.SymmetryTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Symmetry tool's strip (v1.8, design §4.6; area D converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun symmetryStrip(tool: SymmetryTool, c: EditorController): StripSpec = StripSpec.legacy { SymmetryOptions(tool) }
