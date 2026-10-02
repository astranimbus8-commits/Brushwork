package com.brushwork.paint.masks

import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.MaskTool

/**
 * Adjustment layer commands (v1.5 §4.3; owned by A5): the layers window's "New adjustment layer
 * (Tone)", "Edit adjustment" and "Edit mask", and the Filters panel's "As adjustment layer".
 */
object AdjustmentLayerOps {
    /** "New adjustment layer (Tone)": a Tone adjustment above the active layer (one step), then its Adjust sheet. */
    fun createDefault(c: EditorController) {
        if (!c.canAddAdjustmentLayer) {
            c.toast("Layer limit reached (${c.maxLayers}) for this canvas size")
            return
        }
        c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(drawingColor = c.color), null) ?: return
        openAdjust(c)
    }

    /** "Edit adjustment": selects [layer] and the Masks tool and opens the Adjust sheet. */
    fun edit(c: EditorController, layer: Layer) {
        if (c.doc.indexOf(layer) < 0 || !layer.isAdjustmentLayer) return
        c.selectLayer(layer)
        openAdjust(c)
    }

    /** "Edit mask": selects [layer] and the Masks tool. */
    fun editMask(c: EditorController, layer: Layer) {
        if (c.doc.indexOf(layer) < 0) return
        c.selectLayer(layer)
        c.selectTool(ToolId.MASK)
    }

    private fun openAdjust(c: EditorController) {
        c.selectTool(ToolId.MASK)
        (c.tools[ToolId.MASK] as? MaskTool)?.openAdjust()
    }

    /**
     * "As adjustment layer" (Filters panel): a new adjustment layer above the active one with
     * [filter] at [values]; an active selection becomes its (painted) mask and is dropped. One
     * step "New adjustment layer". Returns the layer, or null (with a message) when it can't be.
     */
    fun fromFilter(c: EditorController, filter: Filter, values: FilterValues): Layer? {
        if (!filter.isAdjustmentCapable) {
            c.toast("${filter.name} can't be an adjustment layer")
            return null
        }
        if (!c.canAddAdjustmentLayer) {
            c.toast("Layer limit reached (${c.maxLayers}) for this canvas size")
            return null
        }
        val sel = c.selection
        var made: Layer? = null
        c.groupUndo(LABEL) {
            val layer = c.addAdjustmentLayer(AdjustmentEffects.spec(filter, values), null, LABEL) ?: return@groupUndo
            made = layer
            if (sel != null) {
                c.addMask(layer, fromSelection = true)
                c.setSelection(null, label = LABEL)
            }
        }
        made?.let { c.toast("Added \"${it.name}\" — edit it any time with the Masks tool") }
        return made
    }

    const val LABEL = "New adjustment layer"
}
