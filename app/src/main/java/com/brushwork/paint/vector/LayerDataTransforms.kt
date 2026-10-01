package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Matrix
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.LayerData

/**
 * How a layer's editable data follows a canvas geometry change (resize, crop, trim, rotate,
 * flip, color mode; v1.5 §5.4; API frozen, owned by A1). Called by CanvasOps for every layer
 * whose pixels changed.
 *
 * Foundation (F1): text, shape and vector data are cleared (the layer becomes a raster layer, as
 * v1.4 does for text and shape layers); a mask spec is kept for an identity map, otherwise mapped
 * by [MaskSpecs.transformed] (dropped when that can't); the adjustment is kept.
 */
object LayerDataTransforms {
    /** Data after a canvas geometry change [m] (old -> new document px); vector content mapped (sizeScale), maskSpec via MaskSpecs.transformed. */
    fun transformed(d: LayerData, m: Matrix, newW: Int, newH: Int): LayerData {
        val mask = d.maskSpec?.let { if (m.isIdentity) it else MaskSpecs.transformed(it, m) }
        return d.copy(text = null, shape = null, vector = null, maskSpec = mask)
    }

    /** Background-thread re-render of a vector layer for scaling ops (null = keep CanvasOps' resampled pixels). */
    fun renderScaled(content: VectorContent, newW: Int, newH: Int, cancelled: () -> Boolean): Bitmap? = null
}
