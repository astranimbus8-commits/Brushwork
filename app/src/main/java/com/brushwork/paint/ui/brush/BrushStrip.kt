package com.brushwork.paint.ui.brush

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.ui.vector.VectorEraserOptions

/**
 * The strip of the Brush, Eraser, Smudge and Blur (v1.8, design §3.9 and §4.6; area D converts
 * it). Foundation stub: the v1.7 strip, the vector eraser's modes first on a vector layer (they
 * decide what a stroke removes, and the start of the strip is what a phone shows).
 */
@Composable
internal fun brushStrip(tool: BrushTool, c: EditorController): StripSpec {
    val vectorMode = LocalStripContext.current.vectorMode
    return StripSpec.legacy {
        if (tool.id == ToolId.ERASER && vectorMode) VectorEraserOptions(c)
        BrushToolOptions(tool)
    }
}
