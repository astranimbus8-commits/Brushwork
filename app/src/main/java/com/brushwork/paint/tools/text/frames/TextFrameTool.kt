package com.brushwork.paint.tools.text.frames

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.PositionedTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

/**
 * The Text frames tool (v1.6, §3.6; area D): linked text boxes, InDesign style. Drag to draw a
 * frame, type its story, tap the red "+" out-port of an overset frame and draw (or tap) the next
 * frame: the text that doesn't fit flows on. Every frame is a text layer whose `textData` is an
 * ordinary [com.brushwork.paint.tools.text.TextItem] holding its slice of the story plus the
 * story itself ([com.brushwork.paint.tools.text.TextThreadSpec]).
 *
 * Foundation stub: registered (factory, icon, grid, options) and safe to select; it draws
 * nothing, takes no touches and never has pending work until area D implements it.
 */
class TextFrameTool(controller: EditorController) : Tool(controller), PositionedTool {
    override val id = ToolId.TEXT_FRAMES

    /** The X / Y pill's target: the selected frame's centre (null = nothing selected; stub: null). */
    override val objectPosition: ObjectPosition? get() = null

    /**
     * Selects frame [layer] (switching to this tool is the caller's job) and opens its story
     * editor when [openEditor]; false if [layer] is not a frame (stub: always false).
     */
    fun openFrame(layer: Layer, openEditor: Boolean): Boolean = false
}
