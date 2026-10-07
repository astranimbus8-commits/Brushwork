package com.brushwork.paint.array

import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData

/**
 * v1.7 (item 11, §3.11; area E): the Transform tool's data lift of an arrayed layer. Move,
 * rotate, scale and flip map the array's spec AND its source, so the layer stays a live array.
 *
 * Foundation stub: always null, so the Transform tool refuses arrays until area E implements it.
 */
object ArrayTransforms {
    /**
     * [layer]'s data with its array (spec and source) mapped by [m] (a row-major 3 × 3 affine
     * matrix in document px, in `android.graphics.Matrix` value order), or null when it can't be.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mapped(layer: Layer, m: FloatArray): LayerData? = null
}
