package com.brushwork.paint.model

import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.vector.VectorContent

/**
 * ONE snapshot of every editable-data field of a [Layer] (v1.5, V5): what undo actions,
 * duplicate / merge / flip, canvas operations and storage copy together, instead of each field
 * by hand. Immutable (every field is an immutable value).
 *
 * [text] / [shape] / [vector] are CONTENT data ([Layer.bitmap] is rendered from them); [maskSpec]
 * is MASK data ([Layer.mask] is rendered from it); [adjustment] makes the layer an adjustment
 * layer (its effect is applied to the layers below while compositing).
 */
data class LayerData(
    val text: String? = null,
    val shape: String? = null,
    val vector: VectorContent? = null,
    val maskSpec: MaskSpec? = null,
    val adjustment: AdjustmentSpec? = null,
) {
    val isEmpty: Boolean get() = text == null && shape == null && vector == null && maskSpec == null && adjustment == null

    /** What a CONTENT pixel edit keeps (text/shape/vector cleared). */
    fun rasterizedContent(): LayerData = copy(text = null, shape = null, vector = null)

    /** What a MASK pixel edit keeps (maskSpec cleared). */
    fun rasterizedMask(): LayerData = copy(maskSpec = null)

    /** Retained bytes estimate for UndoManager.trim (points dominate). */
    fun approxBytes(): Long {
        var b = 32L
        text?.let { b += it.length * 2L }
        shape?.let { b += it.length * 2L }
        vector?.let { b += it.approxBytes() }
        maskSpec?.let { b += it.approxBytes() }
        adjustment?.let { b += 64L + it.values.size * 48L }
        return b
    }

    companion object {
        val NONE = LayerData()
    }
}
