package com.brushwork.paint.tools.remove

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

// STUB — replaced by the content-aware fill engineer.
/** Brush over something; on release the painted area is filled from its surroundings. */
class RemoveTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.REMOVE
}
