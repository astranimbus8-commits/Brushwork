package com.brushwork.paint.masks

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer

/**
 * Editable-mask commands of the layers window's mask page (v1.5 §4.3, owned by A5).
 * Foundation (F1): [toPixelMask] is real (a data-only step: the mask pixels already are the
 * rendered spec); [addGradientMask] only says it is coming.
 */
object MaskLayerOps {
    /** "Add gradient mask…": the Masks tool with target "This layer's mask". */
    fun addGradientMask(c: EditorController, layer: Layer) {
        c.toast("Coming soon")
    }

    /** "Convert to pixel mask": forgets the spec, keeps the mask pixels (one step). */
    fun toPixelMask(c: EditorController, layer: Layer) {
        if (layer.maskSpec == null || layer.mask == null) return
        c.setLayerData(layer, layer.dataSnapshot().copy(maskSpec = null), "Convert to pixel mask")
    }
}
