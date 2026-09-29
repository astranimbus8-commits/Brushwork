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
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Miter limit for sharp corners (the default 4 would bevel acute star tips). */
internal const val MITER_LIMIT = 10f

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
 * pixels. [bounds] covers everything that can be painted (stroke width, miters, anti-aliasing).
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
            f?.controlBounds()?.let { b = it.outset(2f) }
            s?.controlBounds()?.let {
                val joinFactor = if (join == JoinStyle.MITER) MITER_LIMIT else 1f
                val capFactor = if (cap == LineCapStyle.SQUARE) sqrt(2f) else 1f
                val o = it.outset(strokeWidth / 2f * max(joinFactor, capFactor) + 2f)
                b = b?.union(o) ?: o
            }
            sf?.controlBounds()?.let { val o = it.outset(2f); b = b?.union(o) ?: o }
            val bb = b ?: return null
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
            )
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
        val r = Rect()
        val tmp = Rect()
        for (s in specs) { s.boundsRect(tmp); r.union(tmp) }
        if (!r.intersect(0, 0, doc.width, doc.height)) return false
        val sel = controller.selection
        if (sel != null && !r.intersect(sel.bounds)) return false
        val rec = controller.beginEdit(layer)
        val maskMode = rec.target == EditTarget.MASK
        val target = if (maskMode) layer.mask ?: return false else layer.bitmap
        rec.touch(r)
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
            // a canvas-sized shape never allocates a canvas-sized layer.
            val tile = Rect()
            var y = r.top
            while (y < r.bottom) {
                var x = r.left
                while (x < r.right) {
                    tile.set(x, y, min(x + TILE, r.right), min(y + TILE, r.bottom))
                    canvas.save()
                    canvas.clipRect(tile)
                    for (s in specs) renderer.drawClipped(canvas, s, sel, locked, tile, maskMode, doc.colorMode)
                    canvas.restore()
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
