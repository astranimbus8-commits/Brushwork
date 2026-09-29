package com.brushwork.paint.assist

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

// STUB — replaced by the assist module.
/** Lets the user drag/rotate/resize the ruler on the canvas. */
class RulerTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.RULER
}
