package com.brushwork.paint.ui.pathfinder

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.ui.tools.strip.StripSpec

/** The Pathfinder tool's strip (v1.8, design §4.6; area G converts it). Foundation stub: the v1.7 strip. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun pathfinderStrip(tool: PathfinderTool, c: EditorController): StripSpec = StripSpec.legacy { PathfinderOptions(tool) }
