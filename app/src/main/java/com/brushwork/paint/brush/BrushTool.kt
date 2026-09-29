package com.brushwork.paint.brush

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

// STUB — replaced by the brush module.
/** Painting tool for BRUSH, ERASER, SMUDGE and BLUR (preset = controller.presetFor(id)). */
class BrushTool(controller: EditorController, override val id: ToolId) : Tool(controller) {
    override val usesStrokeAssist: Boolean = true
}
