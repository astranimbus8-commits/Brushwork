package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
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
 * v1.6: an override that replaces how SEVERAL layers draw (linked text frames being moved or
 * resized: every affected frame previews its pending item). Install it as
 * `controller.renderOverride` like any override; the compositor asks it for every layer in
 * [layers] (and for [layer], which must be one of them) through [drawContentFor] /
 * [drawMaskFor], never through the single-layer [drawContent] / [drawMask]. A
 * [MaskCoverageHint] is not consulted through it (the compositor sees a per-layer view): a masked
 * adjustment layer in [layers] is mapped over its whole region, its mask still applying.
 */
interface MultiLayerRenderOverride : LayerRenderOverride {
    /** Every layer it draws (includes [layer]). */
    val layers: Set<Layer>

    /** Draws [target]'s color content (see [LayerRenderOverride.drawContent]); false = default drawing. */
    fun drawContentFor(target: Layer, canvas: Canvas): Boolean

    /** Draws [target]'s mask (see [LayerRenderOverride.drawMask]); false = default drawing. */
    fun drawMaskFor(target: Layer, canvas: Canvas, maskPaint: Paint): Boolean = false
}

/**
 * The bitmap a [Compositor.drawDocument] canvas draws into, and how document px map onto it
 * (v1.5): live adjustment layers read the composite below them from it. Every caller owns such a
 * backing bitmap: a display tile (translate), a flattened image (identity), a thumbnail (scale),
 * an eyedropper or fill patch (translate). [display]: the bitmap is a display tile of the canvas
 * (the "Safe compositing" switch only changes what the canvas shows, never exports or merges).
 *
 * v1.6 [directWrite]: the canvas draws straight into [bitmap], clipped by a RECT, with no pending
 * saveLayer (true for all 7 current callers, V12), so the adjustment stage may write its result
 * into [bitmap] directly (the fused NORMAL path, §3.1 C1); false forces the v1.5 Skia path.
 */
class CompositeTarget(
    val bitmap: Bitmap,
    val docToTarget: Matrix,
    val display: Boolean = false,
    /** v1.6: see the class docs; false forces the v1.5 Skia path. */
    val directWrite: Boolean = true,
    /**
     * v1.6 (§3.1 C2, additive): keeps the resampled factors of an adjustment layer's own mask on
     * this target between draws (a live session's proxy tile, whose owner clears it when the mask
     * may have changed); null (every other caller) resamples each time.
     */
    val maskCache: MaskFactorCache? = null,
) {
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
    internal val dstInPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    internal val adjustmentScratch = AdjustmentScratch()

    /**
     * v1.7: the tile-sized scratch bitmaps [FolderComposite] composites isolated folders in
     * (pooled, used on the drawing thread like [adjustmentScratch]).
     */
    internal val folderScratch = FolderScratchPool()

    /**
     * Draws all visible layers into [canvas] (document coordinates). [clip] limits work to a
     * region (the canvas should already be clipped to it; it's used for saveLayer bounds).
     * [target] is the bitmap [canvas] draws into (adjustment layers read the composite below
     * them from it); null draws adjustment layers as pass-through.
     *
     * v1.6 [layerRange]: only these layers (indices into `doc.layers`, bottom = 0), e.g. the
     * composite below an adjustment layer, then that layer and everything above it, onto the same
     * target (a live session's below-cache, §3.1). A range must not split a clipping group: its
     * first index is 0 or an adjustment layer's index, its last + 1 is the layer count or an
     * adjustment layer's index (empty ranges at such a boundary are fine). `[0, k)` then `[k, n)`
     * onto the same target equals the full draw. Anything else throws [IllegalArgumentException].
     * v1.7: with folders, a range may cut only through folders that draw their children straight
     * onto the canvas (pass-through at 100 %, not a clip base, not clipped; hidden ones draw
     * nothing); cutting through an isolated folder throws [IllegalArgumentException] too.
     */
    fun drawDocument(canvas: Canvas, clip: Rect?, useOverrides: Boolean = true, target: CompositeTarget?, layerRange: IntRange? = null) {
        val layers = doc.layers
        val from: Int
        val until: Int
        if (layerRange == null) {
            from = 0
            until = layers.size
        } else {
            from = layerRange.first
            until = layerRange.last + 1
            require(isGroupBoundary(from) && isGroupBoundary(until) && from <= until) {
                "Layer range $layerRange splits a clipping group or is out of 0..${layers.size}"
            }
        }
        val bounds = RectF(clip ?: doc.bounds)
        val override = if (useOverrides) overrideProvider() else null
        // v1.7 (I11): a document with a folder is drawn level by level (area A's FolderComposite);
        // without one, the v1.6 loop below runs unchanged (I5).
        if (doc.hasFolders) {
            FolderComposite.draw(this, doc, canvas, bounds, override, target, layerRange)
            return
        }
        drawFlat(canvas, layers, from, until, bounds, override, target)
    }

    /**
     * The v1.6 loop over [layers] (none of them a folder) from [from] until [until]: clipping
     * groups and adjustment layers as in [drawDocument]. v1.7: also used by [FolderComposite].
     */
    internal fun drawFlat(canvas: Canvas, layers: List<Layer>, from: Int, until: Int, bounds: RectF, override: LayerRenderOverride?, target: CompositeTarget?) {
        var i = from
        while (i < until) {
            val base = layers[i]
            if (base.isAdjustmentLayer) {
                // Its own group: never a clipping base (layers marked clipping above it draw unclipped).
                if (base.visible && base.opacity > 0f) {
                    adjustmentScratch.colorMode = doc.colorMode
                    AdjustmentStage.draw(canvas, base, bounds, overrideFor(base, override), target, adjustmentScratch)
                }
                i++
                continue
            }
            var j = i + 1
            while (j < until && layers[j].clipping && !layers[j].isAdjustmentLayer) j++
            if (base.visible && base.opacity > 0f) {
                val clips = if (j > i + 1) layers.subList(i + 1, j).filter { it.visible && it.opacity > 0f } else emptyList()
                drawGroup(canvas, base, clips, bounds, override)
            }
            i = j
        }
    }

    /** True when a [drawDocument] layer range may start or end at index [k] (see there). */
    private fun isGroupBoundary(k: Int): Boolean {
        val n = doc.layers.size
        return k == 0 || k == n || (k in 1 until n && doc.layers[k].isAdjustmentLayer)
    }

    /**
     * v1.9 A6 (P3, design 3.2 A6 step 9): [drawDocument] of layers [from, until) with the overrides,
     * where the range may also start or end at any clipping group's first layer
     * ([isSplitBoundary]), not only at an adjustment layer: a stroke's below-cache
     * (`engine/live/StrokeBelowCache`) is `[0, k)` with k the stroke layer's group, and the
     * frame draws `[k, n)` over it. At scale 1 onto the same target, `[0, k)` then `[k, n)` is
     * the full draw bit for bit: [drawFlat] draws one group after another onto one canvas, so the
     * canvas after `[0, k)` is exactly the cache. With folders, [FolderComposite] still refuses a
     * cut through a folder that is not drawn as is ([IllegalArgumentException]); callers split
     * only where `FolderComposite.drawnAsIs` holds. [drawDocument]'s own contract is unchanged.
     */
    internal fun drawSplit(canvas: Canvas, clip: Rect, target: CompositeTarget?, from: Int, until: Int) {
        require(isSplitBoundary(from) && isSplitBoundary(until) && from <= until) {
            "Layer range [$from, $until) splits a clipping group or is out of 0..${doc.layers.size}"
        }
        if (from == until) return
        val bounds = RectF(clip)
        val override = overrideProvider()
        if (doc.hasFolders) {
            FolderComposite.draw(this, doc, canvas, bounds, override, target, from until until)
            return
        }
        drawFlat(canvas, doc.layers, from, until, bounds, override, target)
    }

    /**
     * v1.9 A6 (P3): true when a [drawSplit] range may start or end at index [k]: the ends, an
     * adjustment layer, or a layer that starts a clipping group (it is not clipped to the unit
     * below it, `LayerTree.isClipped`: as [drawFlat] and [FolderComposite] group a level).
     */
    internal fun isSplitBoundary(k: Int): Boolean {
        val layers = doc.layers
        val n = layers.size
        return k == 0 || k == n || (k in 1 until n && (layers[k].isAdjustmentLayer || !FolderComposite.isClipped(layers, k)))
    }

    /** Reused view of a [MultiLayerRenderOverride] as the override of one of its layers (main thread). */
    private val multiView = MultiLayerView()

    private class MultiLayerView : LayerRenderOverride {
        var multi: MultiLayerRenderOverride? = null
        var target: Layer? = null
        override val layer: Layer get() = target!!
        override fun drawContent(canvas: Canvas): Boolean = multi!!.drawContentFor(target!!, canvas)
        override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean = multi!!.drawMaskFor(target!!, canvas, maskPaint)
    }

    /**
     * The override that applies to [layer] (v1.6): [override] itself when it is a single-layer
     * override of [layer] (exactly the v1.5 rule: I5 goldens), a view of a
     * [MultiLayerRenderOverride] drawing [layer] when [layer] is one of its layers, else null.
     */
    internal fun overrideFor(layer: Layer, override: LayerRenderOverride?): LayerRenderOverride? {
        if (override == null) return null
        if (override is MultiLayerRenderOverride) {
            if (layer !== override.layer && layer !in override.layers) return null
            return multiView.also { it.multi = override; it.target = layer }
        }
        return if (override.layer === layer) override else null
    }

    /**
     * v1.9 A6 (P2): the group paints [BlendModes.paint] makes, one per blend mode and alpha,
     * made once on the main thread (where every frame draws) instead of per layer and tile;
     * never changed after they are made. Other threads get a new paint, as before.
     */
    private var blendPaints: Array<Paint?>? = null

    /** [BlendModes.paint] for [mode] and [opacity]: the shared one on the main thread. */
    private fun blendPaint(mode: LayerBlendMode, opacity: Float): Paint {
        if (Looper.myLooper() !== Looper.getMainLooper()) return BlendModes.paint(mode, opacity)
        // The alpha BlendModes.paint gives the paint: equal alphas make equal paints.
        val alpha = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val cache = blendPaints ?: arrayOfNulls<Paint>(LayerBlendMode.values().size * 256).also { blendPaints = it }
        val key = mode.ordinal * 256 + alpha
        return cache[key] ?: BlendModes.paint(mode, opacity).also { cache[key] = it }
    }

    internal fun drawGroup(canvas: Canvas, base: Layer, clips: List<Layer>, bounds: RectF, override: LayerRenderOverride?) {
        val groupPaint = blendPaint(base.blendMode, base.opacity)
        if (clips.isEmpty()) {
            drawLayer(canvas, base, groupPaint, bounds, override)
            return
        }
        val save = canvas.saveLayer(bounds, groupPaint)
        drawLayer(canvas, base, null, bounds, override)
        for (c in clips) {
            val cs = canvas.saveLayer(bounds, blendPaint(c.blendMode, c.opacity))
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
    internal fun drawLayer(canvas: Canvas, layer: Layer, paint: Paint?, bounds: RectF, override: LayerRenderOverride?) {
        val ov = overrideFor(layer, override)
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
     *
     * v1.9 (design 2.4.9, C16): [withPaper] (every flattened raster export and share, and canvas
     * sampling) puts the document's paper ([PaperBackdrop]) under the artwork in place of
     * [background], as the canvas shows it; the same as v1.8 while `doc.paper == null`.
     */
    fun renderFlattened(background: Int? = null, withPaper: Boolean = false): Bitmap {
        val bg = if (withPaper) PaperBackdrop.sampleColor(doc) ?: background else background
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        val c = Canvas(out)
        val matte = bg != null && hasLiveAdjustment()
        if (bg != null && !matte) c.drawColor(bg)
        drawDocument(c, null, useOverrides = false, target = CompositeTarget.identity(out))
        if (matte) c.drawColor(bg, PorterDuff.Mode.DST_OVER)
        return out
    }

    /**
     * True when a visible adjustment layer can change the composite (see [renderFlattened]).
     * v1.7: one inside a hidden folder cannot (`effectiveVisible` is the own eye at the top level).
     */
    private fun hasLiveAdjustment(): Boolean = doc.layers.any { it.isAdjustmentLayer && doc.effectiveVisible(it) && it.opacity > 0f }

    /**
     * Flattened image scaled to fit in [maxSize] x [maxSize]. v1.9 (design 2.4.9, C16): on the
     * document's paper ([PaperBackdrop]) when it has one (gallery and save thumbnails, the Color
     * select proxy), else on [background] as in v1.8.
     */
    fun renderThumbnail(maxSize: Int, background: Int? = null): Bitmap {
        val bg = PaperBackdrop.sampleColor(doc) ?: background
        val s = minOf(1f, maxSize.toFloat() / max(doc.width, doc.height))
        val w = max(1, (doc.width * s).roundToInt()); val h = max(1, (doc.height * s).roundToInt())
        if (s >= 0.5f) {
            val full = renderFlattened(bg)
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
        val matte = bg != null && hasLiveAdjustment()
        if (bg != null && !matte) c.drawColor(bg)
        val sx = w2.toFloat() / doc.width
        val sy = h2.toFloat() / doc.height
        c.save()
        c.scale(sx, sy)
        drawDocument(c, null, useOverrides = false, target = CompositeTarget(mid, Matrix().apply { setScale(sx, sy) }))
        c.restore()
        if (matte) c.drawColor(bg, PorterDuff.Mode.DST_OVER)
        val out = Bitmap.createScaledBitmap(mid, w, h, true)
        if (out !== mid) mid.recycle()
        return out
    }
}
