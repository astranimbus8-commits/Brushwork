package com.brushwork.paint.tools.symmetry

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

/**
 * The Symmetry tool (v1.7 item 18, §3.18; area H): ibisPaint's five symmetry rulers (mirror,
 * kaleidoscope, rotation, array, perspective array) with their canvas handles. The settings are
 * `Document.symmetry`, changed through `EditorController.updateSymmetry`; they stay on after the
 * tool is put away.
 *
 * Foundation stub: registered (factory, icon, menu cell, options branch) and safe to select on
 * every layer kind; it draws nothing, takes no touches and never has pending work until area H
 * implements it.
 */
class SymmetryTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.SYMMETRY
}
