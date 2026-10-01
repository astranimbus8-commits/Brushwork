package com.brushwork.paint.masks

import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Rendering and geometry of editable masks (v1.5 §4.3; API frozen, owned by A5). F2 writes the
 * reference renderer for linear and radial components; A5 the real one (brush components,
 * parallel rows). Foundation (F1): every pixel gets the spec's base value (startFull, invert,
 * density); components are not drawn yet.
 */
object MaskSpecs {
    /**
     * Renders [spec] for a [w] x [h] document into [out]: the pixels of [region] row by row,
     * [stride] ints per row, (region.left, region.top) at index 0. Opaque gray ARGB (white =
     * visible), so the compositor's luminance-to-alpha mask paint applies it unchanged.
     */
    fun render(spec: MaskSpec, w: Int, h: Int, region: Rect, out: IntArray, stride: Int) {
        val m0 = if (spec.startFull) 1f else 0f
        val m = (if (spec.invert) 1f - m0 else m0) * spec.density.coerceIn(0f, 1f)
        val g = (m * 255f).roundToInt().coerceIn(0, 255)
        val argb = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        val rw = region.width()
        val rh = region.height()
        if (rw <= 0 || rh <= 0) return
        for (row in 0 until rh) {
            val base = row * stride
            out.fill(argb, base, base + rw)
        }
    }

    /** Document area where the rendered mask can be non-zero (null = nowhere). Conservative. */
    fun coverageBounds(spec: MaskSpec, w: Int, h: Int): Rect? = if (w > 0 && h > 0) Rect(0, 0, w, h) else null

    /** [spec] mapped by the canvas geometry [m] (old -> new document px); null when it can't be (the spec is dropped). */
    fun transformed(spec: MaskSpec, m: Matrix): MaskSpec? = null
}
