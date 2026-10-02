package com.brushwork.paint.masks

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.MaskTool

/**
 * Editable-mask commands of the layers window's mask page and the Masks tool's Components sheet
 * (v1.5 §4.3; owned by A5).
 */
object MaskLayerOps {
    /**
     * "Add gradient mask…": the Masks tool with target "This layer's mask" and "+ Linear" armed
     * (a painted mask asks to be replaced first). On an adjustment layer: its mask.
     */
    fun addGradientMask(c: EditorController, layer: Layer) {
        if (c.doc.indexOf(layer) < 0) return
        c.selectLayer(layer)
        c.selectTool(ToolId.MASK)
        val tool = c.tools[ToolId.MASK] as? MaskTool ?: return
        if (!layer.isAdjustmentLayer) tool.chooseTarget(MaskTool.Target.ThisLayer)
        tool.arm(MaskTool.Kind.LINEAR)
        c.toast("Drag on the canvas from where the layer shows to where it fades out")
    }

    /** "Convert to pixel mask": forgets the spec, keeps the mask pixels (one step). */
    fun toPixelMask(c: EditorController, layer: Layer) {
        if (layer.maskSpec == null || layer.mask == null) return
        c.setLayerData(layer, layer.dataSnapshot().copy(maskSpec = null), "Convert to pixel mask")
    }

    /** "Use mask as selection": the mask's luminance becomes a soft selection (one step). */
    fun useAsSelection(c: EditorController, layer: Layer) {
        c.selectionFromMask(layer)
    }

    /**
     * "Apply a filter through this mask…": the mask becomes the selection and the layer the
     * filter applies to becomes active ([layer] itself, or for an adjustment layer the nearest
     * visible pixel layer below it). Returns false when there is nothing to filter.
     */
    fun prepareFilterThroughMask(c: EditorController, layer: Layer): Boolean {
        if (layer.mask == null) { c.toast("\"${layer.name}\" has no mask"); return false }
        val target = if (layer.isAdjustmentLayer) {
            val idx = c.doc.indexOf(layer)
            (idx - 1 downTo 0).map { c.doc.layers[it] }.firstOrNull { it.visible && !it.isAdjustmentLayer }
        } else layer
        if (target == null) { c.toast("There is no layer below to apply a filter to"); return false }
        // Said now, not after the filter was picked (the filter would be refused then, leaving
        // the selection and another active layer behind).
        if (target.locked) { c.toast("Layer \"${target.name}\" is locked: unlock it to apply a filter through the mask"); return false }
        c.selectionFromMask(layer)
        if (c.selection == null) return false
        c.selectLayer(target)
        c.toast("The mask is now the selection: pick a filter for \"${target.name}\"")
        return true
    }
}
