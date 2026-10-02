package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lets a tool temporarily change how ONE layer is drawn in the composite (live stroke buffer,
 * filter preview, transform preview, shape preview...). Coordinates are document pixels and the
 * canvas is already clipped to the region being redrawn.
 */
interface LayerRenderOverride {
    /** The layer this override applies to. */
    val layer: Layer

    /**
     * Draw the layer's color content (instead of `layer.bitmap`). The canvas is an isolated
     * offscreen layer, so any xfermode (DST_OUT for erasing, SRC_ATOP for alpha lock) is safe.
     * Return false to fall back to the default drawing.
     */
    fun drawContent(canvas: Canvas): Boolean

    /**
     * Draw the grayscale mask using [maskPaint] (DST_IN + luminance-to-alpha) instead of
     * `layer.mask`. Only called when the layer has an enabled mask. Return false for default.
     */
    fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean = false
}

/**
 * The bitmap a [Compositor.drawDocument] canvas draws into, and how document px map onto it
 * (v1.5): live adjustment layers read the composite below them from it. Every caller owns such a
 * backing bitmap: a display tile (translate), a flattened image (identity), a thumbnail (scale),
 * an eyedropper or fill patch (translate). [display]: the bitmap is a display tile of the canvas
 * (the "Safe compositing" switch only changes what the canvas shows, never exports or merges).
 */
class CompositeTarget(val bitmap: Bitmap, val docToTarget: Matrix, val display: Boolean = false) {
    companion object {
        /** A bitmap whose pixel (0, 0) is document pixel ([left], [top]) at 1:1. */
        fun translate(bitmap: Bitmap, left: Int, top: Int): CompositeTarget =
            CompositeTarget(bitmap, Matrix().apply { setTranslate(-left.toFloat(), -top.toFloat()) })

        /** A display tile of the canvas whose pixel (0, 0) is document pixel ([left], [top]). */
        fun displayTile(bitmap: Bitmap, left: Int, top: Int): CompositeTarget =
            CompositeTarget(bitmap, Matrix().apply { setTranslate(-left.toFloat(), -top.toFloat()) }, display = true)

        /** A document-sized bitmap at 1:1. */
        fun identity(bitmap: Bitmap): CompositeTarget = CompositeTarget(bitmap, Matrix())
    }
}

/**
 * Flattens the document's layers (blend modes, opacity, masks, clipping groups) into a canvas.
 * Clipping groups follow Photoshop semantics: the base layer's opacity and blend mode apply to
 * the whole group; clipped layers are limited to the base's (masked) alpha.
 *
 * Adjustment layers (v1.5) are applied on the fly to what is below them ([AdjustmentStage]); an
 * adjustment layer is never a clipping base and never clipped (a layer marked clipping right
 * above one draws unclipped). Without adjustment layers the drawing is exactly v1.4's (I5).
 */
class Compositor(private val doc: Document, private val overrideProvider: () -> LayerRenderOverride?) {

    private val maskPaint = BitmapUtils.newMaskApplyPaint()
    private val dstInPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val adjustmentScratch = AdjustmentScratch()

    /**
     * Draws all visible layers into [canvas] (document coordinates). [clip] limits work to a
     * region (the canvas should already be clipped to it; it's used for saveLayer bounds).
     * [target] is the bitmap [canvas] draws into (adjustment layers read the composite below
     * them from it); null draws adjustment layers as pass-through.
     */
    fun drawDocument(canvas: Canvas, clip: Rect?, useOverrides: Boolean = true, target: CompositeTarget?) {
        val bounds = RectF(clip ?: doc.bounds)
        val override = if (useOverrides) overrideProvider() else null
        val layers = doc.layers
        var i = 0
        while (i < layers.size) {
            val base = layers[i]
            if (base.isAdjustmentLayer) {
                // Its own group: never a clipping base (layers marked clipping above it draw unclipped).
                if (base.visible && base.opacity > 0f) {
                    adjustmentScratch.colorMode = doc.colorMode
                    AdjustmentStage.draw(canvas, base, bounds, override, target, adjustmentScratch)
                }
                i++
                continue
            }
            var j = i + 1
            while (j < layers.size && layers[j].clipping && !layers[j].isAdjustmentLayer) j++
            if (base.visible && base.opacity > 0f) {
                val clips = if (j > i + 1) layers.subList(i + 1, j).filter { it.visible && it.opacity > 0f } else emptyList()
                drawGroup(canvas, base, clips, bounds, override)
            }
            i = j
        }
    }

    private fun drawGroup(canvas: Canvas, base: Layer, clips: List<Layer>, bounds: RectF, override: LayerRenderOverride?) {
        val groupPaint = BlendModes.paint(base.blendMode, base.opacity)
        if (clips.isEmpty()) {
            drawLayer(canvas, base, groupPaint, bounds, override)
            return
        }
        val save = canvas.saveLayer(bounds, groupPaint)
        drawLayer(canvas, base, null, bounds, override)
        for (c in clips) {
            val cs = canvas.saveLayer(bounds, BlendModes.paint(c.blendMode, c.opacity))
            drawLayer(canvas, c, null, bounds, override)
            // Keep only where the base (with its mask) has alpha.
            val bs = canvas.saveLayer(bounds, dstInPaint)
            drawLayer(canvas, base, null, bounds, override)
            canvas.restoreToCount(bs)
            canvas.restoreToCount(cs)
        }
        canvas.restoreToCount(save)
    }

    /** Draws one layer (content + mask) with [paint] (null = plain source-over). */
    private fun drawLayer(canvas: Canvas, layer: Layer, paint: Paint?, bounds: RectF, override: LayerRenderOverride?) {
        val ov = if (override != null && override.layer === layer) override else null
        val mask = if (layer.maskEnabled) layer.mask else null
        if (ov == null && mask == null) {
            canvas.drawBitmap(layer.bitmap, 0f, 0f, paint ?: plainPaint)
            return
        }
        val save = canvas.saveLayer(bounds, paint)
        if (ov == null || !ov.drawContent(canvas)) canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
        if (mask != null && (ov == null || !ov.drawMask(canvas, maskPaint))) canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        canvas.restoreToCount(save)
    }

    /**
     * Full-resolution flattened image (no tool previews), on [background] when given. Caller owns
     * the bitmap.
     *
     * With a visible adjustment layer the [background] is put BEHIND the composite (a matte, as
     * the canvas shows transparency): an effect never changes it, so a JPG export of an Invert or
     * Tone adjustment over transparent areas stays [background] there, as on the canvas. Without
     * one the drawing is exactly v1.4's (I5).
     */
    fun renderFlattened(background: Int? = null): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        val c = Canvas(out)
        val matte = background != null && hasLiveAdjustment()
        if (background != null && !matte) c.drawColor(background)
        drawDocument(c, null, useOverrides = false, target = CompositeTarget.identity(out))
        if (matte) c.drawColor(background!!, PorterDuff.Mode.DST_OVER)
        return out
    }

    /** True when a visible adjustment layer can change the composite (see [renderFlattened]). */
    private fun hasLiveAdjustment(): Boolean = doc.layers.any { it.isAdjustmentLayer && it.visible && it.opacity > 0f }

    /** Flattened image scaled to fit in [maxSize] x [maxSize]. */
    fun renderThumbnail(maxSize: Int, background: Int? = null): Bitmap {
        val s = minOf(1f, maxSize.toFloat() / max(doc.width, doc.height))
        val w = max(1, (doc.width * s).roundToInt()); val h = max(1, (doc.height * s).roundToInt())
        if (s >= 0.5f) {
            val full = renderFlattened(background)
            if (s >= 1f) return full
            val scaled = Bitmap.createScaledBitmap(full, w, h, true)
            full.recycle()
            return scaled
        }
        // Render at ~2x the target through a scaled canvas, then filter down for smoother results.
        val s2 = minOf(1f, s * 2f)
        val w2 = max(1, (doc.width * s2).roundToInt()); val h2 = max(1, (doc.height * s2).roundToInt())
        val mid = BitmapUtils.createLayerBitmap(w2, h2)
        val c = Canvas(mid)
        val matte = background != null && hasLiveAdjustment()
        if (background != null && !matte) c.drawColor(background)
        val sx = w2.toFloat() / doc.width
        val sy = h2.toFloat() / doc.height
        c.save()
        c.scale(sx, sy)
        drawDocument(c, null, useOverrides = false, target = CompositeTarget(mid, Matrix().apply { setScale(sx, sy) }))
        c.restore()
        if (matte) c.drawColor(background!!, PorterDuff.Mode.DST_OVER)
        val out = Bitmap.createScaledBitmap(mid, w, h, true)
        if (out !== mid) mid.recycle()
        return out
    }
}
