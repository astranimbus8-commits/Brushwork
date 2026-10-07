package com.brushwork.paint.tools.array

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

/**
 * The Array tool (v1.7 item 3, §3.3; area E): the canvas handles of the active layer's live array
 * (the Line arrow, the Circle centre, the Curve guide's points, the Transform pivot and step
 * arrow) and its options sheet. Also opened by the layer ⋮ "Edit array".
 *
 * Foundation stub: registered (factory, icon, menu cell, options branch) and safe to select on
 * every layer kind; it draws nothing, takes no touches and never has pending work until area E
 * implements it.
 */
class ArrayTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.ARRAY
}
