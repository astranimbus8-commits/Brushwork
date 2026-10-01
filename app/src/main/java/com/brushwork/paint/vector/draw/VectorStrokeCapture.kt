package com.brushwork.paint.vector.draw

import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeInfo

/**
 * What a brush / eraser stroke does on a vector layer (v1.5 §4.9, owned by A3): a brush stroke is
 * recorded as a `VStroke`, the eraser removes objects, direct tips are refused. Reached through
 * `VectorLayers.strokeHook` (the BrushTool seam). Foundation (F1): every stroke is a normal
 * raster stroke ([StrokeHook.None]).
 */
object VectorStrokeCapture {
    fun hookFor(c: EditorController, info: StrokeInfo): StrokeHook = StrokeHook.None
}
