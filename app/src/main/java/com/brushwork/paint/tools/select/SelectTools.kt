package com.brushwork.paint.tools.select

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

// STUB — replaced by the selection module.

object SelectionOutline {
    /** Computes [sel].outline (doc-space Path of the selection edge) off the main thread, then invalidates. */
    fun computeAsync(controller: EditorController, sel: Selection) {}

    /** Draws marching ants in screen space. [phase] animates the dash offset. */
    fun draw(canvas: Canvas, t: ViewTransform, sel: Selection, phase: Float) {}
}

class MagicWandTool(controller: EditorController) : Tool(controller) { override val id = ToolId.MAGIC_WAND }
class LassoTool(controller: EditorController) : Tool(controller) { override val id = ToolId.LASSO }
class MarqueeTool(controller: EditorController) : Tool(controller) { override val id = ToolId.MARQUEE }
class FillTool(controller: EditorController) : Tool(controller) { override val id = ToolId.FILL }
class EyedropperTool(controller: EditorController) : Tool(controller) { override val id = ToolId.EYEDROPPER }
