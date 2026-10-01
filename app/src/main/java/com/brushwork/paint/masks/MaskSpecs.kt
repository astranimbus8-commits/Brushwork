package com.brushwork.paint.masks

import android.graphics.Matrix
import android.graphics.Rect

/**
 * Rendering and geometry of editable masks (v1.5 §4.3; API frozen, owned by A5). F2 writes the
 * reference renderer for linear and radial components ([MaskSpecRenderer], with the combination
 * rules of §4.3b); A5 the real one (brush components, the drag preview). Brush components are
 * not drawn yet (raw 0).
 */
object MaskSpecs {
    /**
     * Renders [spec] for a [w] x [h] document into [out]: the pixels of [region] row by row,
     * [stride] ints per row, (region.left, region.top) at index 0. Opaque gray ARGB (white =
     * visible), so the compositor's luminance-to-alpha mask paint applies it unchanged.
     */
    fun render(spec: MaskSpec, w: Int, h: Int, region: Rect, out: IntArray, stride: Int) =
        MaskSpecRenderer.render(spec, w, h, region.left, region.top, region.width(), region.height(), out, stride)

    /** Document area where the rendered mask can be non-zero (null = nowhere). Conservative. */
    fun coverageBounds(spec: MaskSpec, w: Int, h: Int): Rect? =
        MaskSpecRenderer.coverageBounds(spec, w, h)?.let { Rect(it[0], it[1], it[2], it[3]) }

    /** [spec] mapped by the canvas geometry [m] (old -> new document px); null when it can't be (the spec is dropped). */
    fun transformed(spec: MaskSpec, m: Matrix): MaskSpec? = null
}
