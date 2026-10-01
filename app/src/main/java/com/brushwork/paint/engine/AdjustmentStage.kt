package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.brushwork.paint.model.Layer

/**
 * Reusable buffers of the adjustment stage (one per [Compositor]; main thread, or the
 * compositor's own thread). Owned by A5.
 */
class AdjustmentScratch {
    /** Scratch bitmap the mapped composite is drawn from (reused between calls; may be null). */
    var bitmap: Bitmap? = null

    /** Scratch pixel buffer (reused between calls). */
    var pixels: IntArray = IntArray(0)

    /** Frees the buffers. */
    fun release() {
        bitmap?.recycle()
        bitmap = null
        pixels = IntArray(0)
    }
}

/**
 * Live adjustment layers (v1.5 §4.3c; owned by A5): when the compositor's top-level loop reaches
 * a visible adjustment layer, the composite below it — read from the caller's [CompositeTarget]
 * — is mapped by the effect's pixel mapper and drawn back as the layer's content through the
 * normal layer path (blend mode, opacity, mask or the override's mask).
 *
 * Foundation (F1): pass-through (draws nothing, so the layers below show unchanged).
 */
object AdjustmentStage {
    /**
     * Applies adjustment [layer] onto [canvas] (document px, clipped to [bounds]); [override] is
     * the active render override (if it concerns [layer], its mask preview applies); [target] is
     * the bitmap [canvas] draws into (null = pass-through).
     */
    fun draw(canvas: Canvas, layer: Layer, bounds: RectF, override: LayerRenderOverride?, target: CompositeTarget?, scratch: AdjustmentScratch) {}
}
