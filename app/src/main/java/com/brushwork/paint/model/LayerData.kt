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
 *
 * v1.7: [folder] makes the layer a folder (I11), and [array] is the layer's live array (I14; the
 * bitmap is its cache). Both are trailing defaulted parameters, so every named-argument call site
 * compiles unchanged.
 */
data class LayerData(
    val text: String? = null,
    val shape: String? = null,
    val vector: VectorContent? = null,
    val maskSpec: MaskSpec? = null,
    val adjustment: AdjustmentSpec? = null,
    /** v1.7 (item 8): non-null for a folder. */
    val folder: FolderSpec? = null,
    /** v1.7 (item 3): the live array. */
    val array: LayerArray? = null,
) {
    /** v1.7: a folder-only or array-only snapshot is NOT empty. */
    val isEmpty: Boolean get() = text == null && shape == null && vector == null && maskSpec == null &&
        adjustment == null && folder == null && array == null

    /**
     * What a CONTENT pixel edit keeps: text, shape, vector AND the array go (I1, I14); the folder
     * is kept. Exception: a raster array in "Edit source pixels" mode ([ArraySpec.editingSource],
     * §3.3) keeps its array.
     */
    fun rasterizedContent(): LayerData =
        if (array?.spec?.editingSource == true) copy(text = null, shape = null, vector = null)
        else copy(text = null, shape = null, vector = null, array = null)

    /** What a MASK pixel edit keeps (maskSpec cleared). */
    fun rasterizedMask(): LayerData = copy(maskSpec = null)

    /** Retained bytes estimate for UndoManager.trim (points dominate; an array's source pixels count). */
    fun approxBytes(): Long {
        var b = 32L
        text?.let { b += it.length * 2L }
        shape?.let { b += it.length * 2L }
        vector?.let { b += it.approxBytes() }
        maskSpec?.let { b += it.approxBytes() }
        adjustment?.let { b += 64L + it.values.size * 48L }
        b += array?.approxBytes() ?: 0L
        return b
    }

    companion object {
        val NONE = LayerData()
    }
}
