package com.brushwork.paint.tools.clone

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.PositionedTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint

/**
 * Clone stamp (v1.5, §4.2; owned by A6). Foundation stub: registered so the tools grid, the
 * options strip and the pointer-down rules know it; touching the canvas only says it is coming.
 * The real tool wraps a private `BrushTool(c, ToolId.CLONE)` through the brush hooks
 * (`coverageSource`, `undoLabelOverride`) and reports its source as [objectPosition].
 */
class CloneTool(controller: EditorController) : Tool(controller), PositionedTool {
    override val id = ToolId.CLONE

    /** The clone source (null while none is set). */
    override val objectPosition: ObjectPosition? get() = null

    override fun onDown(p: ToolPoint) {
        controller.toast("Coming soon")
    }
}
