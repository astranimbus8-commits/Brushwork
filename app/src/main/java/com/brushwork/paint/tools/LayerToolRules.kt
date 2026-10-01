package com.brushwork.paint.tools

import com.brushwork.paint.model.Layer

/**
 * Which tools can start a gesture on which kind of layer (v1.5, frozen). The controller asks
 * [refusal] at pointer-down, before the tool sees the touch: a refused gesture shows the
 * message and is ignored until the finger lifts (no stroke, no undo step).
 */
object LayerToolRules {
    /** Tools that need pixels: refused on vector layers by the pointer-down gate. */
    val PIXEL_ONLY: Set<ToolId> = setOf(ToolId.SMUDGE, ToolId.BLUR, ToolId.CLONE, ToolId.REMOVE, ToolId.FRAME_DIVIDER)

    /** Tools that paint into the active layer (or its mask) with the brush engine. */
    private val PAINTING: Set<ToolId> = setOf(ToolId.BRUSH, ToolId.ERASER)

    /** Message for tools refused on adjustment layers. */
    const val ADJUSTMENT_MESSAGE = "Adjustment layers have no pixels — use the Masks tool"

    /** The message for [id] refused on a vector layer. */
    fun pixelOnlyMessage(id: ToolId): String = "${id.label} works on pixels — tap Vector to switch, or Rasterize this layer"

    /**
     * Null when [id] may start a gesture on [layer]; otherwise the message to show.
     *  - Vector layer + [PIXEL_ONLY] -> "<Tool> works on pixels — tap Vector to switch, or Rasterize this layer".
     *  - Adjustment layer + a painting tool (brush / eraser) without a mask -> [ADJUSTMENT_MESSAGE]
     *    (with a mask they paint the mask).
     *  - Adjustment layer + smudge / blur / clone / remove / frame divider, or the bucket without a
     *    mask -> [ADJUSTMENT_MESSAGE].
     */
    fun refusal(id: ToolId, layer: Layer): String? {
        if (layer.isVectorLayer && id in PIXEL_ONLY) return pixelOnlyMessage(id)
        if (layer.isAdjustmentLayer) {
            val hasMask = layer.mask != null
            if (id in PIXEL_ONLY) return ADJUSTMENT_MESSAGE
            if ((id in PAINTING || id == ToolId.FILL) && !hasMask) return ADJUSTMENT_MESSAGE
        }
        return null
    }
}
