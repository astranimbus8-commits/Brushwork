package com.brushwork.paint.ui.vector

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Curve, Polyline and Path strips, by `CurveTool.kind` (v1.8, design §3.9 and §4.6; area E
 * converts them). Foundation stub: the v1.7 strip, which branches on the kind itself.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun curveStrip(tool: CurveTool, c: EditorController): StripSpec = StripSpec.legacy { CurveToolOptions(tool) }
