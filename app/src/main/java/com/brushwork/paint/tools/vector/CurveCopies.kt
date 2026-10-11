package com.brushwork.paint.tools.vector

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * v1.8 (item 1a, design §3.1): drawing the symmetry copies of the Curve, Polyline and Path tools'
 * open path. The tool takes the maps once, at the path's first point
 * (`ObjectCopies.mapsAt`), or keeps a reopened path's stored ones; what is drawn here is the
 * path's plain items ([VectorPaintSpec]s, in document px) once as they are and once per copy
 * under `canvas.concat(M_k)`. Android draws homographies, so the Perspective array ruler draws
 * the same way.
 *
 * - On a raster layer the copies are painted in the commit's one step ([commit]), inside ONE
 *   `saveLayerAlpha` when the paint is translucent ([draw]'s alpha; the items are then built
 *   opaque), so copies that overlap never darken; the live preview ([CopiesPreview]) draws
 *   exactly the same.
 * - On a vector layer the preview draws them as the layer renderer draws a path's copies (no
 *   group: alpha 255 and the items as they are).
 *
 * Main thread (the paints are shared).
 */
internal object CurveCopies {
    /**
     * The drawable copies of [maps] (each the 9 values of a row-major 3 × 3): every map but the
     * identity whose values are finite and whose linear part is invertible, in their order.
     */
    fun others(maps: List<FloatArray>): List<FloatArray> {
        if (maps.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(maps.size)
        for (m in maps) {
            if (m.size < 9 || m.any { !it.isFinite() } || isIdentity(m)) continue
            val det = m[0] * m[4] - m[1] * m[3]
            if (det == 0f && m[6] == 0f && m[7] == 0f) continue
            out += m
        }
        return out
    }

    /** True when [m] (row-major 3 × 3) maps every point to itself. */
    fun isIdentity(m: FloatArray): Boolean =
        m.size >= 9 && m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 0f && m[4] == 1f && m[5] == 0f &&
            m[6] == 0f && m[7] == 0f && m[8] == 1f

    /**
     * The integer box of [r] under [m] (a homography divides by w), or null when a corner falls
     * at or behind the horizon (w ≤ 0) or the box is not finite.
     */
    fun mappedBox(r: Rect, m: FloatArray): Rect? {
        val xs = floatArrayOf(r.left.toFloat(), r.right.toFloat(), r.right.toFloat(), r.left.toFloat())
        val ys = floatArrayOf(r.top.toFloat(), r.top.toFloat(), r.bottom.toFloat(), r.bottom.toFloat())
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var rr = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val w = m[6] * xs[i] + m[7] * ys[i] + m[8]
            if (!(w > 0f)) return null
            val x = (m[0] * xs[i] + m[1] * ys[i] + m[2]) / w
            val y = (m[3] * xs[i] + m[4] * ys[i] + m[5]) / w
            l = min(l, x); t = min(t, y); rr = max(rr, x); b = max(b, y)
        }
        if (!(l.isFinite() && t.isFinite() && rr.isFinite() && b.isFinite())) return null
        // One pixel more for anti-aliasing at the mapped edges.
        return Rect(floor(l).toInt() - 1, floor(t).toInt() - 1, ceil(rr).toInt() + 1, ceil(b).toInt() + 1)
    }

    /**
     * The document regions [specs] paint as they are and under each of [copies]: their own
     * regions, then each region's mapped box (a copy whose box is unbounded covers [document]).
     */
    fun regions(specs: List<VectorPaintSpec>, copies: List<FloatArray>, document: Rect): List<Rect> {
        val out = ArrayList<Rect>()
        for (s in specs) out += s.regions
        if (copies.isEmpty()) return out
        val own = out.toList()
        for (m in copies) for (r in own) out += mappedBox(r, m) ?: Rect(document)
        return out
    }

    /**
     * Draws [specs] (document px) as they are, then once per map of [copies] under
     * `canvas.concat`; with [alpha] below 255 all of it inside ONE `saveLayerAlpha` over [area]
     * (the specs then carry opaque colors), so overlapping copies never darken.
     */
    fun draw(canvas: Canvas, renderer: VectorRenderer, specs: List<VectorPaintSpec>, copies: List<FloatArray>, alpha: Int, maskMode: Boolean, colorMode: ColorMode, area: RectF) {
        if (specs.isEmpty()) return
        val save = if (alpha < 255) canvas.saveLayerAlpha(area, alpha.coerceAtLeast(0)) else canvas.save()
        for (s in specs) renderer.draw(canvas, s, maskMode, colorMode)
        if (copies.isNotEmpty()) {
            val mx = Matrix()
            for (m in copies) {
                val c = canvas.save()
                mx.setValues(m)
                canvas.concat(mx)
                for (s in specs) renderer.draw(canvas, s, maskMode, colorMode)
                canvas.restoreToCount(c)
            }
        }
        canvas.restoreToCount(save)
    }

    private val atopPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    // (As VectorRenderer: a shader over the ALPHA_8 mask supplies it as source alpha for DST_IN.)
    private val selectionPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var selectionShaderFor: Bitmap? = null

    /**
     * [draw] limited to [selection] and, with [alphaLocked], drawn only over existing pixels;
     * [clip] is the region being drawn (it bounds the offscreen layers).
     */
    fun drawClipped(
        canvas: Canvas, renderer: VectorRenderer, specs: List<VectorPaintSpec>, copies: List<FloatArray>, alpha: Int,
        selection: Selection?, alphaLocked: Boolean, clip: Rect, maskMode: Boolean, colorMode: ColorMode,
    ) {
        val area = RectF(clip)
        if (selection == null && !alphaLocked) {
            draw(canvas, renderer, specs, copies, alpha, maskMode, colorMode, area)
            return
        }
        val save = canvas.saveLayer(area, if (alphaLocked) atopPaint else null)
        draw(canvas, renderer, specs, copies, alpha, maskMode, colorMode, area)
        if (selection != null) {
            if (selectionShaderFor !== selection.mask) {
                selectionPaint.shader = BitmapShader(selection.mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                selectionShaderFor = selection.mask
            }
            canvas.drawRect(area, selectionPaint)
        }
        canvas.restoreToCount(save)
    }

    /**
     * The raster commit with copies (`VectorCommit.commit` with every copy painted in the same
     * step): [specs] and their [copies] into [layer]'s edit target (its mask when editing the
     * mask), limited to the selection and the alpha lock, as ONE undo step [label]. The caller
     * checks `controller.checkEditable(layer)` first. True when pixels changed.
     */
    fun commit(controller: EditorController, layer: Layer, specs: List<VectorPaintSpec>, copies: List<FloatArray>, alpha: Int, label: String): Boolean {
        val doc = controller.doc
        if (specs.isEmpty() || doc.indexOf(layer) < 0) return false
        val sel = controller.selection
        val document = Rect(0, 0, doc.width, doc.height)
        val regions = ArrayList<Rect>()
        for (q0 in regions(specs, copies, document)) {
            val q = Rect(q0)
            if (!q.intersect(document)) continue
            if (sel != null && !q.intersect(sel.bounds)) continue
            regions += q
        }
        if (regions.isEmpty()) return false
        val r = Rect(regions[0])
        for (q in regions) r.union(q)
        val rec = controller.beginEdit(layer)
        val maskMode = rec.target == EditTarget.MASK
        val target = if (maskMode) layer.mask else layer.bitmap
        if (target == null) { rec.abort(); return false }
        for (q in regions) rec.touch(q)
        val canvas = Canvas(target)
        val renderer = VectorRenderer()
        val locked = layer.alphaLocked && !maskMode
        // Bounded tiles: a canvas-sized path never allocates a canvas-sized offscreen layer (the
        // translucent group, the selection, the alpha lock), and tiles no copy reaches are skipped.
        val tile = Rect()
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                tile.set(x, y, min(x + TILE, r.right), min(y + TILE, r.bottom))
                if (regions.any { Rect.intersects(it, tile) }) {
                    canvas.save()
                    canvas.clipRect(tile)
                    drawClipped(canvas, renderer, specs, copies, alpha, sel, locked, tile, maskMode, doc.colorMode)
                    canvas.restore()
                }
                x += TILE
            }
            y += TILE
        }
        return controller.commitEdit(rec, label)
    }

    private const val TILE = 512
}

/**
 * v1.8 (item 1a): the live preview of an open path with copies on [layer] (a render override, as
 * `VectorPreview`): the layer with the path's items drawn over it and once per copy
 * ([CurveCopies.draw]). [clipped]: a raster layer's pixels (the selection, the alpha lock and the
 * mask apply, as when the commit paints them); otherwise the objects of a vector layer, which
 * neither is clipped nor goes into the mask.
 */
internal class CopiesPreview(private val controller: EditorController, override val layer: Layer) : LayerRenderOverride {
    var specs: List<VectorPaintSpec> = emptyList()
    var copies: List<FloatArray> = emptyList()
    var alpha: Int = 255
    var clipped: Boolean = true
    /** The document regions it drew last (redrawn when it changes). */
    var regions: List<Rect> = emptyList()

    private val renderer = VectorRenderer()
    private val clip = Rect()
    private val clipF = RectF()

    private val maskMode: Boolean get() = clipped && controller.editTargetOf(layer) == EditTarget.MASK

    override fun drawContent(canvas: Canvas): Boolean {
        if (maskMode) return false
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        if (specs.isEmpty()) return true
        canvas.getClipBounds(clip)
        val mode = controller.doc.colorMode
        if (clipped) {
            CurveCopies.drawClipped(canvas, renderer, specs, copies, alpha, controller.selection, layer.alphaLocked, clip, false, mode)
        } else {
            CurveCopies.draw(canvas, renderer, specs, copies, alpha, false, mode, RectF(clip))
        }
        return true
    }

    override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
        if (!maskMode) return false
        val mask = layer.mask ?: return false
        canvas.getClipBounds(clip)
        clipF.set(clip)
        val save = canvas.saveLayer(clipF, maskPaint)
        canvas.drawBitmap(mask, 0f, 0f, null)
        CurveCopies.drawClipped(canvas, renderer, specs, copies, alpha, controller.selection, false, clip, true, controller.doc.colorMode)
        canvas.restoreToCount(save)
        return true
    }
}
