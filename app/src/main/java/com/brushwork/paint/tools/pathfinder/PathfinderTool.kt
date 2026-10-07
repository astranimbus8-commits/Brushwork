package com.brushwork.paint.tools.pathfinder

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

/**
 * The Pathfinder tool (v1.7 item 20, §3.20; area G): tap shapes and paths on any layer to pick
 * the operands, then combine them (Unite, Minus front, Minus back, Intersect, Exclude, Divide,
 * Trim, Merge, Crop, Outline) into a new "Pathfinder N" layer. It picks its operands itself, so
 * `LayerToolRules` never refuses it.
 *
 * Foundation stub: registered (factory, icon, menu cell, options branch) and safe to select on
 * every layer kind; it draws nothing, takes no touches and never has pending work until area G
 * implements it.
 */
class PathfinderTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.PATHFINDER
}
