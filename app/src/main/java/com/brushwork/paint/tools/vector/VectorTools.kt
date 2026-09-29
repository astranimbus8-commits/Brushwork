package com.brushwork.paint.tools.vector

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

// STUB — replaced by the vector module.
class ShapeTool(controller: EditorController) : Tool(controller) { override val id = ToolId.SHAPE }

/** Bezier curve tool, or polyline tool when [polyline] (all corners sharp). */
class CurveTool(controller: EditorController, val polyline: Boolean) : Tool(controller) {
    override val id = if (polyline) ToolId.POLYLINE else ToolId.CURVE
}
