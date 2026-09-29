package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

// STUB — replaced by the placement module.
/** Move / scale / rotate / flip the active layer or the selected pixels. */
class TransformTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.TRANSFORM

    /** Places [image] (e.g. an imported picture) onto the empty [layer] with transform handles. */
    fun startPlacement(layer: Layer, image: Bitmap) {}
}
