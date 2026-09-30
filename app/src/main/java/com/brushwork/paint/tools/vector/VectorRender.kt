package com.brushwork.paint.tools.vector

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Miter limit for sharp corners (the default 4 would bevel acute star tips). */
internal const val MITER_LIMIT = 10f

/** Stroke bands are split into boxes of about this size (document px). */
private const val REGION_CHUNK = 128f

/** Beyond this many band boxes a spec just uses its bounds. */
private const val MAX_REGIONS = 256

/** Flattening tolerance used for the stroke bands (added to their outset). */
private const val REGION_TOLERANCE = 0.5f

/** Converts to an android [Path] (reusing [out]). */
fun VectorPath.toAndroidPath(out: Path = Path()): Path {
    out.rewind()
    for (op in ops) {
        when (op) {
            is PathOp.MoveTo -> out.moveTo(op.p.x, op.p.y)
            is PathOp.LineTo -> out.lineTo(op.p.x, op.p.y)
            is PathOp.CubicTo -> out.cubicTo(op.c1.x, op.c1.y, op.c2.x, op.c2.y, op.p.x, op.p.y)
            PathOp.Close -> out.close()
        }
    }
    return out
}

/**
 * Everything needed to draw one vector item into a layer: an optional filled path, an optional
 * stroked path and optional extra paths filled with the stroke color (arrowheads). Document
 * pixels. [bounds] covers everything that can be painted (stroke width, miters, anti-aliasing);
 * [regions] is a tighter conservative cover (a band of boxes along a stroke-only outline), so a
 * large hollow shape neither redraws nor snapshots its untouched interior.
 */
class VectorPaintSpec private constructor(
    val fill: Path?,
    val fillColor: Int,
    val stroke: Path?,
    val strokeColor: Int,
    val strokeWidth: Float,
    val cap: Paint.Cap,
    val join: JoinStyle,
    val strokeFill: Path?,
    val bounds: RectF,
    val regions: List<Rect>,
) {
    companion object {
        /** Builds a spec; returns null when there is nothing to draw. */
        fun build(
            fill: VectorPath?,
            fillColor: Int,
            stroke: VectorPath?,
            strokeColor: Int,
            strokeWidth: Float,
            cap: LineCapStyle = LineCapStyle.ROUND,
            join: JoinStyle = JoinStyle.ROUND,
            strokeFill: VectorPath? = null,
        ): VectorPaintSpec? {
            val f = fill?.takeUnless { it.isEmpty }
            val s = stroke?.takeUnless { it.isEmpty || strokeWidth <= 0f }
            val sf = strokeFill?.takeUnless { it.isEmpty }
            if (f == null && s == null && sf == null) return null
            var b: Bounds? = null
            val regions = ArrayList<Rect>()
            f?.controlBounds()?.let { b = it.outset(2f) }
            f?.subpathControlBounds()?.forEach { regions += it.outset(2f).toRect() }
            s?.controlBounds()?.let {
                val joinFactor = if (join == JoinStyle.MITER) MITER_LIMIT else 1f
                val capFactor = if (cap == LineCapStyle.SQUARE) sqrt(2f) else 1f
                // How far paint can reach from the path: miter tips / square caps, plus anti-aliasing.
                val reach = strokeWidth / 2f * max(joinFactor, capFactor) + 2f
                val o = it.outset(reach)
                b = b?.union(o) ?: o
                strokeRegions(s, reach + REGION_TOLERANCE, regions)
            }
            sf?.controlBounds()?.let { val o = it.outset(2f); b = b?.union(o) ?: o }
            sf?.subpathControlBounds()?.forEach { regions += it.outset(2f).toRect() }
            val bb = b ?: return null
            // Too many boxes, or boxes so thick (huge miters) that they cover more than the bounds.
            val boundsRect = bb.toRect()
            val boundsArea = boundsRect.width().toLong() * boundsRect.height()
            if (regions.size > MAX_REGIONS || regions.sumOf { it.width().toLong() * it.height() } >= boundsArea) {
                regions.clear()
                regions += boundsRect
            }
            return VectorPaintSpec(
                fill = f?.toAndroidPath(),
                fillColor = fillColor,
                stroke = s?.toAndroidPath(),
                strokeColor = strokeColor,
                strokeWidth = strokeWidth,
                cap = when (cap) { LineCapStyle.BUTT -> Paint.Cap.BUTT; LineCapStyle.ROUND -> Paint.Cap.ROUND; LineCapStyle.SQUARE -> Paint.Cap.SQUARE },
                join = join,
                strokeFill = sf?.toAndroidPath(),
                bounds = RectF(bb.left, bb.top, bb.right, bb.bottom),
                regions = regions,
            )
        }

        private fun Bounds.toRect() = Rect(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())

        /**
         * Boxes covering everything within [reach] of the stroked [path]: the flattened outline is
         * cut into pieces of at most [REGION_CHUNK] and each piece's box is grown by [reach].
         */
        private fun strokeRegions(path: VectorPath, reach: Float, out: MutableList<Rect>) {
            for (poly in path.flatten(REGION_TOLERANCE)) {
                val pts = poly.points
                if (pts.isEmpty()) continue
                var l = pts[0].x; var t = pts[0].y; var r = l; var b = t
                fun emit() = Bounds(l, t, r, b).outset(reach).toRect().let { out += it }
                val n = if (poly.closed && pts.size > 1) pts.size + 1 else pts.size
                var prev = pts[0]
                for (i in 1 until n) {
                    val q = pts[i % pts.size]
                    val pieces = max(1, ceil(prev.distanceTo(q) / REGION_CHUNK).toInt()).coerceAtMost(MAX_REGIONS * 4)
                    for (k in 1..pieces) {
                        val p = prev.lerp(q, k.toFloat() / pieces)
                        l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
                        if (r - l >= REGION_CHUNK || b - t >= REGION_CHUNK) {
                            emit()
                            l = p.x; t = p.y; r = p.x; b = p.y
                        }
                    }
                    prev = q
                    if (out.size > MAX_REGIONS) return
                }
                emit()
            }
        }
    }

    /** Integer document rect covering [bounds]. */
    fun boundsRect(out: Rect = Rect()): Rect { bounds.roundOut(out); return out }
}

/**
 * Draws [VectorPaintSpec]s with the layer rules: clipped to the selection, SRC_ATOP when the
 * layer's alpha is locked, luminance gray when painting into a mask, color-mode constrained.
 * Holds reusable paints; not thread-safe.
 */
class VectorRenderer {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val atopPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    // Skia draws an ALPHA_8 bitmap as a coverage mask, which turns DST_IN into a no-op; a shader
    // over the mask supplies it as source alpha instead.
    private val selectionPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var selectionShaderFor: Bitmap? = null
    private val layerRect = RectF()

    /** The color actually painted for [c] (mask gray / grayscale / monochrome). */
    fun paintColor(c: Int, maskMode: Boolean, colorMode: ColorMode): Int = when {
        maskMode -> ColorUtils.gray(ColorUtils.luminance(c), ColorUtils.alpha(c))
        colorMode != ColorMode.RGB -> ColorModeOps.constrainPixel(c, colorMode)
        else -> c
    }

    /** Draws [spec] straight into [canvas] (no selection / alpha-lock handling). */
    fun draw(canvas: Canvas, spec: VectorPaintSpec, maskMode: Boolean, colorMode: ColorMode) {
        val aa = maskMode || colorMode != ColorMode.MONOCHROME
        spec.fill?.let {
            fillPaint.isAntiAlias = aa
            fillPaint.color = paintColor(spec.fillColor, maskMode, colorMode)
            canvas.drawPath(it, fillPaint)
        }
        val strokeColor = paintColor(spec.strokeColor, maskMode, colorMode)
        spec.stroke?.let {
            strokePaint.isAntiAlias = aa
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = spec.strokeWidth
            strokePaint.strokeCap = spec.cap
            strokePaint.strokeJoin = when (spec.join) {
                JoinStyle.MITER -> Paint.Join.MITER
                JoinStyle.ROUND -> Paint.Join.ROUND
                JoinStyle.BEVEL -> Paint.Join.BEVEL
            }
            strokePaint.strokeMiter = MITER_LIMIT
            canvas.drawPath(it, strokePaint)
        }
        spec.strokeFill?.let {
            fillPaint.isAntiAlias = aa
            fillPaint.color = strokeColor
            canvas.drawPath(it, fillPaint)
        }
    }

    /**
     * Draws [spec] limited to [selection] and, with [alphaLocked], only over existing pixels.
     * [clip] is the region being drawn (bounds the offscreen layer).
     */
    fun drawClipped(canvas: Canvas, spec: VectorPaintSpec, selection: Selection?, alphaLocked: Boolean, clip: Rect, maskMode: Boolean, colorMode: ColorMode) {
        if (selection == null && !alphaLocked) {
            draw(canvas, spec, maskMode, colorMode)
            return
        }
        layerRect.set(clip)
        if (!layerRect.intersect(spec.bounds)) return
        val save = canvas.saveLayer(layerRect, if (alphaLocked) atopPaint else null)
        draw(canvas, spec, maskMode, colorMode)
        if (selection != null) {
            if (selectionShaderFor !== selection.mask) {
                selectionPaint.shader = BitmapShader(selection.mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                selectionShaderFor = selection.mask
            }
            canvas.drawRect(layerRect, selectionPaint)
        }
        canvas.restoreToCount(save)
    }
}

/** Bakes vector items into layers with undo. */
object VectorCommit {
    private const val TILE = 512

    /**
     * Renders [specs] into [layer]'s edit target (its mask when editing the mask) as one undo
     * step called [label]. The caller checks `controller.checkEditable(layer)` first. Returns
     * true if pixels changed (false when everything lies outside the canvas or the selection).
     */
    fun commit(controller: EditorController, layer: Layer, specs: List<VectorPaintSpec>, label: String): Boolean {
        val doc = controller.doc
        if (specs.isEmpty() || doc.indexOf(layer) < 0) return false
        val sel = controller.selection
        // Only the regions the specs can paint (inside the canvas and the selection) are
        // snapshotted for undo; a hollow shape leaves its interior tiles alone.
        val regions = ArrayList<Rect>()
        for (s in specs) for (sr in s.regions) {
            val q = Rect(sr)
            if (!q.intersect(0, 0, doc.width, doc.height)) continue
            if (sel != null && !q.intersect(sel.bounds)) continue
            regions += q
        }
        if (regions.isEmpty()) return false
        val r = Rect(regions[0])
        for (q in regions) r.union(q)
        val rec = controller.beginEdit(layer)
        val maskMode = rec.target == EditTarget.MASK
        val target = if (maskMode) layer.mask ?: return false else layer.bitmap
        for (q in regions) rec.touch(q)
        val canvas = Canvas(target)
        val renderer = VectorRenderer()
        val locked = layer.alphaLocked && !maskMode
        if (sel == null && !locked) {
            canvas.save()
            canvas.clipRect(r)
            for (s in specs) renderer.draw(canvas, s, maskMode, doc.colorMode)
            canvas.restore()
        } else {
            // Offscreen layers are needed for the selection / alpha lock: work in bounded tiles so
            // a canvas-sized shape never allocates a canvas-sized layer, and skip tiles no spec
            // can reach.
            val tile = Rect()
            var y = r.top
            while (y < r.bottom) {
                var x = r.left
                while (x < r.right) {
                    tile.set(x, y, min(x + TILE, r.right), min(y + TILE, r.bottom))
                    if (regions.any { Rect.intersects(it, tile) }) {
                        canvas.save()
                        canvas.clipRect(tile)
                        for (s in specs) {
                            if (s.regions.any { Rect.intersects(it, tile) }) {
                                renderer.drawClipped(canvas, s, sel, locked, tile, maskMode, doc.colorMode)
                            }
                        }
                        canvas.restore()
                    }
                    x += TILE
                }
                y += TILE
            }
        }
        return controller.commitEdit(rec, label)
    }
}

/**
 * Live preview of pending vector items on [layer] through the compositor, so blend mode,
 * opacity, clipping and masks look exactly like the committed result.
 */
class VectorPreview(private val controller: EditorController, override val layer: Layer) : LayerRenderOverride {
    /** Items to preview (replaced by the tool whenever geometry or style changes). */
    var specs: List<VectorPaintSpec> = emptyList()

    private val renderer = VectorRenderer()
    private val clip = Rect()
    private val clipF = RectF()

    private val maskMode: Boolean get() = controller.editTargetOf(layer) == EditTarget.MASK

    override fun drawContent(canvas: Canvas): Boolean {
        if (maskMode) return false
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        if (specs.isEmpty()) return true
        canvas.getClipBounds(clip)
        val sel = controller.selection
        for (s in specs) renderer.drawClipped(canvas, s, sel, layer.alphaLocked, clip, false, controller.doc.colorMode)
        return true
    }

    override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
        if (!maskMode) return false
        val mask = layer.mask ?: return false
        canvas.getClipBounds(clip)
        clipF.set(clip)
        val save = canvas.saveLayer(clipF, maskPaint)
        canvas.drawBitmap(mask, 0f, 0f, null)
        val sel = controller.selection
        for (s in specs) renderer.drawClipped(canvas, s, sel, false, clip, true, controller.doc.colorMode)
        canvas.restoreToCount(save)
        return true
    }

    /** Document rect covered by the current items (empty if none). */
    fun dirtyRect(): Rect {
        val r = Rect()
        val tmp = Rect()
        for (s in specs) { s.boundsRect(tmp); r.union(tmp) }
        return r
    }
}

/**
 * Keeps one tool's [VectorPreview] installed as the controller's render override and redraws
 * only the regions that the old and the new items cover (not their whole bounding boxes).
 *
 * While a finger drags the items ([interacting]) they are drawn straight into the screen
 * overlay instead ([drawOverlay]) whenever that looks exactly the same (see [drawsInOverlay]):
 * the canvas tiles then stay as they are during the whole drag instead of being recomposited
 * and re-uploaded on every frame. When the finger lifts, the items go back into the layer.
 */
internal class PreviewHost(private val controller: EditorController) {
    private var preview: VectorPreview? = null
    private var shown: List<Rect> = emptyList()

    /** Items drawn in the overlay while dragging (empty: none), on [overlayLayer]. */
    private var overlaySpecs: List<VectorPaintSpec> = emptyList()
    private var overlayLayer: Layer? = null
    private val overlay = SpecOverlay()

    /** True while the overlay shows the items (tests). */
    val isInOverlay: Boolean get() = overlaySpecs.isNotEmpty()

    /**
     * True while a finger drags the items: they are then shown in the overlay when possible.
     * Setting it back to false puts them back into the layer.
     */
    var interacting = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                val layer = overlayLayer
                val specs = overlaySpecs
                if (layer != null && specs.isNotEmpty()) show(layer, specs)
            }
        }

    /** Previews [specs] on [layer]; an empty list removes the preview. */
    fun show(layer: Layer, specs: List<VectorPaintSpec>) {
        if (specs.isEmpty()) { release(); return }
        if (interacting && drawsInOverlay(layer)) {
            // Out of the layer (its tiles redraw once without the items) and into the overlay.
            removeOverride()
            overlayLayer = layer
            overlaySpecs = specs
            controller.invalidateOverlay()
            return
        }
        clearOverlay()
        val p = preview?.takeIf { it.layer === layer } ?: run {
            removeOverride()
            VectorPreview(controller, layer).also { preview = it }
        }
        p.specs = specs
        if (controller.renderOverride !== p) controller.renderOverride = p
        val regions = ArrayList<Rect>()
        for (s in specs) regions += s.regions
        invalidate(shown)
        invalidate(regions)
        shown = regions
    }

    /** Removes the preview (if it is still installed) and redraws what it covered. */
    fun release() {
        clearOverlay()
        removeOverride()
    }

    /** Draws the items shown in the overlay (call first in the tool's drawOverlay). */
    fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val layer = overlayLayer ?: return
        val specs = overlaySpecs
        if (specs.isEmpty()) return
        overlay.draw(canvas, t, controller, layer, specs)
        // The layers changed under the drag (props, order): back into the layer from the next
        // frame on.
        if (!drawsInOverlay(layer)) {
            clearOverlay()
            show(layer, specs)
        }
    }

    /**
     * True when drawing items over the finished composite looks exactly like drawing them into
     * [layer]: a normal, fully opaque, unmasked, unclipped layer that paints its content (not its
     * mask), without alpha lock, with no visible layer above it, in a color document (1-bit art
     * is thresholded at document resolution).
     */
    private fun drawsInOverlay(layer: Layer): Boolean {
        val doc = controller.doc
        val index = doc.indexOf(layer)
        if (index < 0 || !layer.visible || layer.opacity < 1f) return false
        if (layer.blendMode != LayerBlendMode.NORMAL || layer.clipping || layer.alphaLocked) return false
        if (layer.mask != null && layer.maskEnabled) return false
        if (controller.editTargetOf(layer) != EditTarget.CONTENT || doc.colorMode == ColorMode.MONOCHROME) return false
        val layers = doc.layers
        for (i in index + 1 until layers.size) {
            val above = layers[i]
            if (above.visible && above.opacity > 0f) return false
        }
        return true
    }

    private fun clearOverlay() {
        if (overlaySpecs.isEmpty() && overlayLayer == null) return
        overlaySpecs = emptyList()
        overlayLayer = null
        controller.invalidateOverlay()
    }

    private fun removeOverride() {
        val p = preview
        if (p != null && controller.renderOverride === p) controller.renderOverride = null
        preview = null
        invalidate(shown)
        shown = emptyList()
    }

    private fun invalidate(rects: List<Rect>) {
        if (rects.isEmpty()) return
        val tiles = controller.tiles
        for (r in rects) tiles.invalidate(r)
        controller.invalidateOverlay()
    }
}
