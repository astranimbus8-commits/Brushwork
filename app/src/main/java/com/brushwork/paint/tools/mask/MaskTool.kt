package com.brushwork.paint.tools.mask

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.PositionedTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint

/**
 * Masks (v1.5, §4.3; owned by A5): linear / radial / brush mask components and adjustment
 * layers. Foundation stub: registered so the tools grid, the options strip and the pointer-down
 * rules know it; touching the canvas only says it is coming. The real tool reports the selected
 * component's pin as [objectPosition].
 */
class MaskTool(controller: EditorController) : Tool(controller), PositionedTool {
    override val id = ToolId.MASK

    /** The selected component's pin (null while none is selected). */
    override val objectPosition: ObjectPosition? get() = null

    override fun onDown(p: ToolPoint) {
        controller.toast("Coming soon")
    }
}
