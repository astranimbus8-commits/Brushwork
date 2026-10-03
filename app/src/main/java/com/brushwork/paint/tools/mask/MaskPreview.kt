package com.brushwork.paint.tools.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.MaskCoverageHint
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.BrushSource
import com.brushwork.paint.masks.MaskBrushCache
import com.brushwork.paint.masks.MaskBrushRaster
import com.brushwork.paint.masks.MaskGeometry
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecRenderer
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.SampleGrid
import com.brushwork.paint.model.Layer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Masks tool's live preview of a mask being edited (v1.5 §4.3c, "drag preview"; owned by
 * A5): the spec is rendered at reduced resolution (½, less on very large canvases) into a
 * preview bitmap, and a render override draws it scaled as the layer's mask (about 2 ms), so a
 * handle drag shows the effect at once. Only the area a change can affect is re-rendered. On
 * release the tool renders the full resolution and records the step. Main thread.
 */
internal class MaskPreview(private val c: EditorController) {
    /** The layer whose mask is previewed (null: a mask that doesn't exist yet, shown only as the overlay). */
    var layer: Layer? = null
        private set
    var bitmap: Bitmap? = null
        private set
    /** Preview pixels per document pixel. */
    var scale: Float = 0.5f
        private set
    /** The spec currently shown (null: no preview). */
    var shown: MaskSpec? = null
        private set
    private var grid: SampleGrid? = null
    private var brushes: PreviewBrushes? = null
    private var override: LayerRenderOverride? = null
    private var buffer = IntArray(0)
    /** Everything the preview made the display show differently (redrawn when it ends). */
    private var touched: Rect? = null

    val isActive: Boolean get() = shown != null

    private inner class Override(override val layer: Layer) : LayerRenderOverride, MaskCoverageHint {
        private var key: Paint? = null
        private var filtered: Paint? = null

        override fun drawContent(canvas: Canvas): Boolean = false

        /** Where the preview can be non-black: the shown spec's coverage, plus the reach of the upscaling. */
        override fun maskCoverage(): Rect? {
            val s = shown ?: return null
            if (s !== coverageOf) {
                coverageOf = s
                coverage = MaskSpecs.coverageBounds(s, c.doc.width, c.doc.height)?.let { padded(it) }?.takeUnless { it.isEmpty }
            }
            return coverage?.let { Rect(it) }
        }

        // The coverage of the spec last asked about (every display tile asks on every frame).
        private var coverageOf: MaskSpec? = null
        private var coverage: Rect? = null

        override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
            val bmp = bitmap ?: return false
            val p = filtered.takeIf { key === maskPaint } ?: Paint(maskPaint).apply { isFilterBitmap = true }.also { filtered = it; key = maskPaint }
            canvas.drawBitmap(bmp, null, RectF(0f, 0f, bmp.width / scale, bmp.height / scale), p)
            return true
        }
    }

    /**
     * Starts previewing edits of [base] (the current spec of [layer], or of a mask about to be
     * created when [layer] is null or has no mask). [cache] provides brush coverage it holds.
     */
    fun begin(layer: Layer?, base: MaskSpec, cache: MaskBrushCache?) {
        end()
        val w = c.doc.width; val h = c.doc.height
        val s = previewScale(w, h)
        val pw = max(1, ceil(w * s).toInt()); val ph = max(1, ceil(h * s).toInt())
        val bmp = bitmap?.takeIf { !it.isRecycled && it.width == pw && it.height == ph && scale == s }
            ?: run {
                bitmap?.recycle()
                bitmap = null
                Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            }
        bitmap = bmp
        scale = s
        val g = SampleGrid(0f, 0f, 1f / s, pw, ph)
        grid = g
        brushes = PreviewBrushes(g, baseRasters(base, g, cache))
        this.layer = layer
        renderRegion(base, 0, 0, pw, ph)
        shown = base
    }

    /**
     * Shows [spec]; [moved] names a brush component moved by an affine map since [begin] (its
     * preview coverage is resampled instead of rasterized again). [region]: the document area
     * where [spec] can render differently from what is shown, when the caller knows it (a brush
     * stroke growing: its new dabs; empty = nowhere); null = computed from the two specs.
     */
    fun update(spec: MaskSpec, moved: Pair<Long, MaskGeometry.Affine>? = null, region: Rect? = null) {
        val prev = shown ?: return
        val g = grid ?: return
        brushes?.moved = moved
        val w = c.doc.width; val h = c.doc.height
        val changed = if (region != null) region.takeUnless { it.isEmpty } else MaskSpecs.changedRegion(prev, spec, w, h)
        shown = spec
        if (changed != null) {
            val s = scale
            val c0 = max(0, floor(changed.left * s).toInt() - 1); val r0 = max(0, floor(changed.top * s).toInt() - 1)
            val c1 = min(g.cols, ceil(changed.right * s).toInt() + 1); val r1 = min(g.rows, ceil(changed.bottom * s).toInt() + 1)
            if (c1 > c0 && r1 > r0) renderRegion(spec, c0, r0, c1 - c0, r1 - r0)
        }
        // The upscaled preview reaches a little beyond the samples that changed.
        val shownRegion = changed?.let { padded(it) }
        val l = layer
        if (l != null && (l.mask != null || l.isAdjustmentLayer) && override == null) {
            val ov = Override(l)
            override = ov
            c.renderOverride = ov
            // The preview replaces the full-resolution mask everywhere it is not black. An
            // adjustment layer without a mask showed its effect everywhere: the preview of the
            // mask it is about to get limits it at once (AdjustmentStage asks the override).
            val before = if (l.mask == null) Rect(0, 0, w, h) else MaskSpecs.coverageBounds(prev, w, h)?.let { padded(it) }
            val dirty = union(before, shownRegion)
            touched = union(touched, dirty ?: Rect())
            redraw(l, dirty ?: Rect())
        } else if (shownRegion != null && override != null) {
            touched = union(touched, shownRegion)
            l?.let { redraw(it, shownRegion) } ?: c.invalidateDoc(shownRegion)
        }
        c.invalidateOverlay()
    }

    /** [r] grown by how far the upscaled preview reaches, within the document. */
    private fun padded(r: Rect): Rect {
        val pad = ceil(2f / scale).toInt() + 2
        return Rect(r).apply {
            inset(-pad, -pad)
            if (!intersect(0, 0, c.doc.width, c.doc.height)) setEmpty()
        }
    }

    /**
     * Shows what changed in [region] of [l]'s mask: an adjustment layer's live session (v1.6
     * §3.1 C2: drawn from proxies while the finger moves), else a plain redraw.
     */
    private fun redraw(l: Layer, region: Rect) {
        if (l.isAdjustmentLayer) c.liveAdjust.touch(l, region) else c.invalidateDoc(region)
    }

    /**
     * Stops the preview (the override goes; the area it showed is redrawn). An adjustment layer's
     * live session refines to exact from here (the tool records the full-resolution mask next).
     */
    fun end() {
        val ov = override
        override = null
        if (ov != null) {
            if (c.renderOverride === ov) c.renderOverride = null
            val l = ov.layer
            touched?.let { r ->
                if (l.isAdjustmentLayer && c.doc.indexOf(l) >= 0) c.liveAdjust.changed(l, r) else c.invalidateDoc(r)
            }
        }
        touched = null
        shown = null
        brushes = null
        layer = null
        c.invalidateOverlay()
    }

    /** Frees the preview bitmap. */
    fun release() {
        end()
        bitmap?.recycle()
        bitmap = null
        buffer = IntArray(0)
    }

    private fun renderRegion(spec: MaskSpec, c0: Int, r0: Int, cols: Int, rows: Int) {
        val g = grid ?: return
        val bmp = bitmap ?: return
        val sub = SampleGrid(g.x0 + c0 * g.step, g.y0 + r0 * g.step, g.step, cols, rows)
        if (buffer.size < cols * rows) buffer = IntArray(cols * rows)
        MaskSpecRenderer.renderGrid(spec, sub, buffer, cols, brushes)
        bmp.setPixels(buffer, 0, cols, c0, r0, cols, rows)
    }

    private fun union(a: Rect?, b: Rect?): Rect? = when {
        a == null -> b
        b == null -> a
        else -> Rect(a).apply { union(b) }
    }

    /** Coverage of every brush component of [base] on the preview grid [g] (from the cache when it holds one). */
    private fun baseRasters(base: MaskSpec, g: SampleGrid, cache: MaskBrushCache?): Map<Long, Pair<BrushMask, ByteArray>> {
        val out = HashMap<Long, Pair<BrushMask, ByteArray>>()
        for (comp in base.components) {
            if (comp !is BrushMask) continue
            val bytes = if (cache != null && cache.holds(comp)) {
                ByteArray(g.size).also { b ->
                    for (r in 0 until g.rows) {
                        val y = floor(g.y(r)).toInt()
                        for (col in 0 until g.cols) {
                            val v = cache.sample(comp, floor(g.x(col)).toInt(), y)
                            b[r * g.cols + col] = if (v < 0) 0 else v.toByte()
                        }
                    }
                }
            } else {
                MaskBrushRaster.rasterize(comp.strokes, g)
            }
            out[comp.id] = comp to bytes
        }
        return out
    }

    /**
     * Brush coverage of the preview: the components as they were at [begin] are copied from
     * their rasters, a component moved by an affine map is resampled from its old raster, and
     * anything else is rasterized by the renderer.
     */
    private class PreviewBrushes(private val grid: SampleGrid, private val base: Map<Long, Pair<BrushMask, ByteArray>>) : BrushSource {
        var moved: Pair<Long, MaskGeometry.Affine>? = null

        override fun coverage(c: BrushMask, grid: SampleGrid): ByteArray? {
            val (orig, bytes) = base[c.id] ?: return null
            val g = this.grid
            val out = ByteArray(grid.size)
            if (orig === c) {
                val ox = ((grid.x0 - g.x0) / g.step).roundToInt()
                val oy = ((grid.y0 - g.y0) / g.step).roundToInt()
                for (r in 0 until grid.rows) {
                    val sy = oy + r
                    if (sy < 0 || sy >= g.rows) continue
                    for (col in 0 until grid.cols) {
                        val sx = ox + col
                        if (sx in 0 until g.cols) out[r * grid.cols + col] = bytes[sy * g.cols + sx]
                    }
                }
                return out
            }
            val mv = moved
            if (mv == null || mv.first != c.id) return null
            val inv = mv.second.inverse() ?: return null
            for (r in 0 until grid.rows) {
                val y = grid.y(r)
                for (col in 0 until grid.cols) {
                    val x = grid.x(col)
                    val qx = inv.mapX(x, y); val qy = inv.mapY(x, y)
                    val sx = floor((qx - g.x0) / g.step).toInt()
                    val sy = floor((qy - g.y0) / g.step).toInt()
                    if (sx in 0 until g.cols && sy in 0 until g.rows) out[r * grid.cols + col] = bytes[sy * g.cols + sx]
                }
            }
            return out
        }
    }

    companion object {
        /** Preview resolution: ½, or less so the preview stays about 1.2 MP on very large canvases. */
        fun previewScale(w: Int, h: Int): Float {
            val px = w.toLong() * h
            return if (px <= 4_800_000L) 0.5f else sqrt(1_200_000f / px).coerceIn(0.05f, 0.5f)
        }

        private val tintPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 48f,
                        0f, 0f, 0f, 0f, 48f,
                        0.1495f, 0.2935f, 0.057f, 0f, 0f,
                    )
                )
            )
        }

        /**
         * Draws the red coverage tint (Lightroom's mask overlay) of a gray mask bitmap [mask]
         * whose pixel is 1 / [scale] document px, in screen space.
         */
        fun drawTint(canvas: Canvas, mask: Bitmap, scale: Float, t: ViewTransform) {
            val m = Matrix(t.matrix)
            if (scale != 1f) m.preScale(1f / scale, 1f / scale)
            canvas.drawBitmap(mask, m, tintPaint)
        }
    }
}
