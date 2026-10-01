package com.brushwork.paint.vector

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection

/**
 * Layer-level operations on vector layers (v1.5 §5.4; API frozen, owned by A1). The controller
 * and the layers window delegate to these; false / null means "not handled": the caller falls
 * back to today's raster behaviour (which turns the layer into a raster layer, undoably).
 */
object VectorLayerOps {
    /** Turns the vector layer into a plain raster layer (one step; toast). */
    fun rasterize(c: EditorController, layer: Layer): Boolean = false

    /** Merges the vector layer [upper] into the vector layer [lower] by concatenating their objects. */
    fun mergeVector(c: EditorController, upper: Layer, lower: Layer): Boolean = false

    /** A new vector layer with only the objects [sel] touches (Duplicate with a selection). */
    fun duplicateTouched(c: EditorController, layer: Layer, sel: Selection): Layer? = null

    /** [content] mirrored inside a [w] x [h] canvas (Flip layer); null = rasterize as today. */
    fun flipped(content: VectorContent, w: Int, h: Int, horizontal: Boolean): VectorContent? = null

    /** Clear on a vector layer: removes the objects [sel] touches (or all). */
    fun clear(c: EditorController, layer: Layer, sel: Selection?): Boolean = false

    /** Fill on a vector layer: adds a filled path from the selection outline (or the canvas). */
    fun fill(c: EditorController, layer: Layer, sel: Selection?, color: Int): Boolean = false
}
