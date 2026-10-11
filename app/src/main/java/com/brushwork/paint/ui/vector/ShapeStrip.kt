package com.brushwork.paint.ui.vector

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Shape tool's strip (v1.8, design §3.9 and §4.6; area F converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun shapeStrip(tool: ShapeTool, c: EditorController): StripSpec = StripSpec.legacy { ShapeToolOptions(tool) }
