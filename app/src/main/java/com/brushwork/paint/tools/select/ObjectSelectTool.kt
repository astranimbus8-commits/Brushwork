package com.brushwork.paint.tools.select

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

// STUB — replaced by the segmentation engineer.
/** Tap an object on the canvas to select it (on-device interactive segmentation). */
class ObjectSelectTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.OBJECT_SELECT
}
