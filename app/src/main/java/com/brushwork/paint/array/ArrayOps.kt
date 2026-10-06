package com.brushwork.paint.array

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer

/**
 * v1.7 (item 3): the array operations: create an array from a selection, from vector objects or
 * from a whole layer; edit, apply and remove it; "Edit source pixels" and "Finish source edit".
 *
 * Area E owns this file and writes the bodies. The foundation (F2) declares the signatures the
 * controller's entry points (`EditorController.arrayFromSelection` and friends) and the layer
 * window call. Every stub returns false and changes nothing, so `main` behaves as v1.6.
 */
object ArrayOps {
    /** "Array from selection": the selected pixels of the active layer become a new "Array N" layer. */
    fun fromSelection(c: EditorController): Boolean = false

    /** "Array from objects": the vector objects [objectIds] of the active layer become a new array layer. */
    fun fromObjects(c: EditorController, objectIds: Set<Long>): Boolean = false

    /** Layer ⋮ "Array…": [layer] as a whole (raster, text, shape or vector) becomes the array's source. */
    fun fromLayer(c: EditorController, layer: Layer): Boolean = false

    /** "Edit array": replaces the array spec of [layer] (one undo step). */
    fun edit(c: EditorController, layer: Layer, spec: ArraySpec): Boolean = false

    /** "Apply array": the copies stay as pixels and the array is dropped. */
    fun apply(c: EditorController, layer: Layer): Boolean = false

    /** "Remove array": the copies go and the source stays. */
    fun remove(c: EditorController, layer: Layer): Boolean = false

    /** "Edit source pixels": shows a raster array's source for painting. */
    fun editSource(c: EditorController, layer: Layer): Boolean = false

    /** "Finish source edit": the painted source goes back into the array and the copies re-render. */
    fun finishSource(c: EditorController, layer: Layer): Boolean = false
}
