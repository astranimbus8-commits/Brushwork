package com.brushwork.paint.tools.select.colorrange

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

/**
 * Color select (v1.8 item 4, design §3.5): Color Range (tap one or more colors, with a
 * Fuzziness) plus hue and tone presets.
 *
 * Foundation stub (F3): the tool opens, draws nothing, commits nothing and shows no hint. Area C
 * (SELECTION) owns this file and gives it its body.
 */
class ColorSelectTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.COLOR_SELECT
}
