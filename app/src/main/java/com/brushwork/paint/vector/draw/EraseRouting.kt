package com.brushwork.paint.vector.draw

import com.brushwork.paint.model.Layer

/** v1.9 (R11, design 2.4.12): how an erase on a layer is carried out. */
enum class EraseRoute { RASTER, OBJECTS, CONVERT_THEN_OBJECTS, RASTERIZE_DATA, REFUSE }

/** v1.9 (R11): what erases: the Eraser brush, or one of the four tools in erase mode ([com.brushwork.paint.tools.ErasableTool]). */
enum class EraseSource { ERASER_BRUSH, CURVE, POLYLINE, PATH, SHAPE }

/** v1.9 (R11, design 2.4.12; owner A5): the route of an erase. */
object EraseRouting {
    /**
     * F1b reproduces v1.8: raster layer or mask target -> RASTER; vector layer with the Eraser
     * (BrushTool.start's `layer.isVectorLayer && !maskTarget && !isPath`) -> OBJECTS; text or shape
     * layer with the Eraser -> RASTERIZE_DATA; folders and adjustments -> REFUSE; the four tools -> REFUSE.
     * A5: the table of R11 (b).
     *
     * In detail (F1b, in this order): a source other than the Eraser brush, or a folder, is
     * REFUSE; a mask target is RASTER (folders have no mask; an adjustment layer's mask is
     * painted); an adjustment layer's content is REFUSE (`checkEditable` refuses it, as in v1.8);
     * a vector layer is OBJECTS, or RASTERIZE_DATA for an erase along a path ([isPath]: v1.8
     * erased its pixels, which drops the objects' editability); a text or shape layer is
     * RASTERIZE_DATA; everything else is RASTER. Never CONVERT_THEN_OBJECTS in F1b.
     */
    fun route(layer: Layer, source: EraseSource, maskTarget: Boolean, isPath: Boolean): EraseRoute = when {
        source != EraseSource.ERASER_BRUSH -> EraseRoute.REFUSE
        layer.isFolder -> EraseRoute.REFUSE
        maskTarget -> EraseRoute.RASTER
        layer.isAdjustmentLayer -> EraseRoute.REFUSE
        layer.isVectorLayer -> if (isPath) EraseRoute.RASTERIZE_DATA else EraseRoute.OBJECTS
        layer.isTextLayer || layer.isShapeLayer -> EraseRoute.RASTERIZE_DATA
        else -> EraseRoute.RASTER
    }
}
