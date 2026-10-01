package com.brushwork.paint.masks

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Rendering and geometry of editable masks (v1.5 §4.3; API frozen, owned by A5): the pure-Kotlin
 * [MaskSpecRenderer] (linear, radial and brush components with the §4.3b combination) wrapped
 * for Android rects, bitmaps and matrices.
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

    /**
     * [spec] mapped by the canvas geometry [m] (old -> new document px); null when it can't be
     * (a perspective or degenerate map: the spec is dropped). Linear and radial components map
     * exactly for every affine map (flips, 90° turns, scaling, crops); brush strokes map their
     * points and scale their size by √|det|.
     */
    fun transformed(spec: MaskSpec, m: Matrix): MaskSpec? {
        val a = affineOf(m) ?: return null
        return MaskGeometry.transformed(spec, a)
    }

    /** The affine part of [m], or null for a perspective or non-finite matrix. */
    fun affineOf(m: Matrix): MaskGeometry.Affine? {
        val v = FloatArray(9)
        m.getValues(v)
        if (v.any { !it.isFinite() }) return null
        if (v[Matrix.MPERSP_0] != 0f || v[Matrix.MPERSP_1] != 0f) return null
        val p = v[Matrix.MPERSP_2]
        if (!(p > 0f)) return null
        val a = MaskGeometry.Affine(
            v[Matrix.MSCALE_X] / p, v[Matrix.MSKEW_X] / p, v[Matrix.MTRANS_X] / p,
            v[Matrix.MSKEW_Y] / p, v[Matrix.MSCALE_Y] / p, v[Matrix.MTRANS_Y] / p,
        )
        return a.takeIf { it.isFinite }
    }

    /** Document area (in the [w] x [h] document) that may render differently between two specs; null = nothing. */
    fun changedRegion(before: MaskSpec?, after: MaskSpec?, w: Int, h: Int): Rect? =
        MaskSpecRenderer.changedRegion(before, after, w, h)?.let { Rect(it[0], it[1], it[2], it[3]) }

    /** Estimated render time (ms, on the reference phone) of [spec] over [region]. */
    fun estimateMillis(spec: MaskSpec, region: Rect): Double =
        MaskSpecRenderer.estimateMillis(spec, region.left, region.top, region.right, region.bottom)

    /**
     * Renders [spec] into the mask bitmap [mask] (document-sized) within [region], in bands of
     * bounded memory. Brush components come from [brushes] when it has them.
     */
    fun renderInto(mask: Bitmap, spec: MaskSpec, region: Rect, brushes: BrushSource? = null) {
        val r = Rect(region)
        if (!r.intersect(0, 0, mask.width, mask.height)) return
        val w = r.width()
        val band = max(1, min(r.height(), BAND_PIXELS / max(1, w)))
        val buf = IntArray(w * band)
        var y = r.top
        while (y < r.bottom) {
            val rows = min(band, r.bottom - y)
            MaskSpecRenderer.render(spec, mask.width, mask.height, r.left, y, w, rows, buf, w, brushes)
            mask.setPixels(buf, 0, w, r.left, y, w, rows)
            y += rows
        }
    }

    /** A new document-sized mask bitmap rendered from [spec]. */
    fun newMask(spec: MaskSpec, w: Int, h: Int, brushes: BrushSource? = null): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        renderInto(bmp, spec, Rect(0, 0, w, h), brushes)
        return bmp
    }

    /** Pixels rendered per band by [renderInto]. */
    private const val BAND_PIXELS = 1 shl 18
}
