package com.brushwork.paint.tools.select.magnetic

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

/**
 * Magnetic lasso (v1.8 item 3, design §3.4): tap points around an object; each segment between
 * two taps follows the strongest edge.
 *
 * Foundation stub (F3): the tool opens, draws nothing, commits nothing and shows no hint. Area C
 * (SELECTION) owns this file and gives it its body.
 */
class MagneticLassoTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.MAGNETIC_LASSO
}
