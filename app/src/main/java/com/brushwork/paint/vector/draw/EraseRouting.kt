package com.brushwork.paint.vector.draw

import com.brushwork.paint.model.Layer

/** v1.9 (R11, design 2.4.12): how an erase on a layer is carried out. */
enum class EraseRoute { RASTER, OBJECTS, CONVERT_THEN_OBJECTS, RASTERIZE_DATA, REFUSE }

/** v1.9 (R11): what erases: the Eraser brush, or one of the four tools in erase mode ([com.brushwork.paint.tools.ErasableTool]). */
enum class EraseSource { ERASER_BRUSH, CURVE, POLYLINE, PATH, SHAPE }

/**
 * v1.9 (R11 b, design 2.4.12; owner A5): the route of an erase, the table every erase asks first
 * (I20: an erase never turns a vector or shape layer into pixels on its own).
 */
object EraseRouting {
    /**
     * The table of R11 (b), the same for the Eraser brush and the four tools ([source] does not
     * change the route: whether a tool may erase at all is [ToolErase.begin]'s), in this order:
     * - a folder: REFUSE (it has no pixels; `checkUsable` says so, as in v1.8);
     * - a mask target: RASTER (an adjustment layer's mask included: v1.8 paints it);
     * - an adjustment layer's content: REFUSE (`checkEditable` refuses it, as in v1.8);
     * - a vector layer: OBJECTS;
     * - a legacy shape layer: CONVERT_THEN_OBJECTS (it becomes a vector layer through
     *   `LayerConvert` inside the erase's own step; a cancelled erase leaves no step);
     * - a text layer: RASTERIZE_DATA (v1.8's undoable rasterize with its toast);
     * - every other layer: RASTER.
     *
     * [isPath]: the Eraser brush along a vector path (`BrushTool`'s path stroke, which is never
     * hooked: it erases pixels). On a vector or shape layer that stays v1.8's RASTERIZE_DATA, with
     * v1.8's toast, since only a hooked stroke can erase objects.
     */
    fun route(layer: Layer, source: EraseSource, maskTarget: Boolean, isPath: Boolean): EraseRoute = when {
        layer.isFolder -> EraseRoute.REFUSE
        maskTarget -> EraseRoute.RASTER
        layer.isAdjustmentLayer -> EraseRoute.REFUSE
        layer.isVectorLayer -> if (isPath && source == EraseSource.ERASER_BRUSH) EraseRoute.RASTERIZE_DATA else EraseRoute.OBJECTS
        layer.isShapeLayer -> if (isPath && source == EraseSource.ERASER_BRUSH) EraseRoute.RASTERIZE_DATA else EraseRoute.CONVERT_THEN_OBJECTS
        layer.isTextLayer -> EraseRoute.RASTERIZE_DATA
        else -> EraseRoute.RASTER
    }
}
