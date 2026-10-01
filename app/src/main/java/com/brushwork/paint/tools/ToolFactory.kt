package com.brushwork.paint.tools

import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerTool
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool

object ToolFactory {
    fun create(c: EditorController): Map<ToolId, Tool> = ToolId.entries.associateWith { id ->
        when (id) {
            ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR -> BrushTool(c, id)
            ToolId.FILL -> FillTool(c)
            ToolId.EYEDROPPER -> EyedropperTool(c)
            ToolId.MAGIC_WAND -> MagicWandTool(c)
            ToolId.LASSO -> LassoTool(c)
            ToolId.MARQUEE -> MarqueeTool(c)
            ToolId.TRANSFORM -> TransformTool(c)
            ToolId.TEXT -> TextTool(c)
            ToolId.SHAPE -> ShapeTool(c)
            ToolId.CURVE -> CurveTool(c, polyline = false)
            ToolId.POLYLINE -> CurveTool(c, polyline = true)
            ToolId.FRAME_DIVIDER -> FrameDividerTool(c)
            ToolId.RULER -> RulerTool(c)
            ToolId.OBJECT_SELECT -> com.brushwork.paint.tools.select.ObjectSelectTool(c)
            ToolId.REMOVE -> com.brushwork.paint.tools.remove.RemoveTool(c)
            ToolId.CLONE -> CloneTool(c)
            ToolId.MASK -> MaskTool(c)
        }
    }
}
