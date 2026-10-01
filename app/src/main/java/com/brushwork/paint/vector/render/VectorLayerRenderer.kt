package com.brushwork.paint.vector.render

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.vector.VectorContent

/**
 * Draws vector content (v1.5 §5.4; API frozen, owned by A1): objects in z-order, clipped to the
 * region. Thread-safe when every thread passes its own [TipCache]. F2 writes the minimal
 * reference (strokes via StrokeRaster, paths via VectorPaintSpec at constant width, shapes via
 * ShapeOutlines.paintSpec); A1 the real one. Foundation (F1): draws nothing.
 */
object VectorLayerRenderer {
    /** Draws [content] (document px) clipped to [region], leaving out the objects [exclude]. */
    fun render(canvas: Canvas, content: VectorContent, region: Rect, exclude: Set<Long> = emptySet(), tips: TipCache) {}

    /** Estimated cost of rendering [region] ([com.brushwork.paint.brush.BrushTool.pathDabCost] units). */
    fun estimateUnits(content: VectorContent, region: Rect): Double = 0.0
}
