package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.filters.PixelMapper
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Reusable buffers of the adjustment stage (one per [Compositor]; main thread, or the
 * compositor's own thread). Owned by A5.
 */
class AdjustmentScratch {
    /** Scratch bitmap the mapped composite is drawn from (reused between calls; may be null). */
    var bitmap: Bitmap? = null

    /** Scratch pixel buffer (reused between calls). */
    var pixels: IntArray = IntArray(0)

    internal val maskPaint = BitmapUtils.newMaskApplyPaint()
    internal val plainPaint = Paint()
    internal val whitePaint = Paint().apply { color = -1 }
    internal val dstOutPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    internal val plusPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    internal val inverse = Matrix()

    /**
     * Color mode of the document being composited (set by the compositor): in a grayscale or
     * 1-bit document the effect's colors are constrained like every committed pixel edit, so an
     * effect never shows colors the document can't have.
     */
    internal var colorMode: ColorMode = ColorMode.RGB

    /** Resolved effects of the last specs seen (by identity: specs are immutable). */
    private val effectSpecs = arrayOfNulls<AdjustmentSpec>(EFFECT_CACHE)
    private val effectMappers = arrayOfNulls<PixelMapper>(EFFECT_CACHE)
    private var effectNext = 0

    /** The mapper of [spec] (null = pass-through), cached. */
    internal fun mapperFor(spec: AdjustmentSpec): PixelMapper? {
        for (i in 0 until EFFECT_CACHE) if (effectSpecs[i] === spec) return effectMappers[i]
        val m = AdjustmentEffects.mapperOf(spec)
        effectSpecs[effectNext] = spec
        effectMappers[effectNext] = m
        effectNext = (effectNext + 1) % EFFECT_CACHE
        return m
    }

    // Painted-mask bounds, per mask bitmap and layer content version (a few adjustment layers).
    // Weak: a deleted layer's mask must not be kept alive by the cache.
    private val boundsMasks = arrayOfNulls<java.lang.ref.WeakReference<Bitmap>>(BOUNDS_CACHE)
    private val boundsVersions = LongArray(BOUNDS_CACHE)
    private val boundsValues = arrayOfNulls<Rect>(BOUNDS_CACHE)
    private var boundsNext = 0

    /**
     * Document area where [layer]'s [mask] lets the effect through (null = nowhere): the spec's
     * coverage bounds for an editable mask (its pixels are the spec's rendering, I1), else the
     * bounds of the non-black pixels of a painted mask (cached per content version).
     */
    internal fun maskCoverage(layer: Layer, mask: Bitmap): Rect? {
        layer.maskSpec?.let { return specCoverage(it, mask.width, mask.height) }
        for (i in 0 until BOUNDS_CACHE) {
            if (boundsMasks[i]?.get() === mask) {
                if (boundsVersions[i] == layer.contentVersion) return boundsValues[i]?.let { Rect(it) }
                val r = nonBlackBounds(mask)
                boundsVersions[i] = layer.contentVersion
                boundsValues[i] = r
                return r?.let { Rect(it) }
            }
        }
        val r = nonBlackBounds(mask)
        boundsMasks[boundsNext] = java.lang.ref.WeakReference(mask)
        boundsVersions[boundsNext] = layer.contentVersion
        boundsValues[boundsNext] = r
        boundsNext = (boundsNext + 1) % BOUNDS_CACHE
        return r?.let { Rect(it) }
    }

    // Coverage bounds of the last specs seen (by identity: specs are immutable). Every display
    // tile asks for them on every frame of a slider drag. Weak: old specs (brush points can be
    // large) must not be kept alive by the cache.
    private val specKeys = arrayOfNulls<java.lang.ref.WeakReference<MaskSpec>>(SPEC_CACHE)
    private val specSizes = LongArray(SPEC_CACHE)
    private val specValues = arrayOfNulls<Rect>(SPEC_CACHE)
    private var specNext = 0

    /** [MaskSpecs.coverageBounds] of [spec] in a [w] x [h] document (null = nowhere), cached. */
    internal fun specCoverage(spec: MaskSpec, w: Int, h: Int): Rect? {
        val size = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)
        for (i in 0 until SPEC_CACHE) {
            if (specKeys[i]?.get() === spec && specSizes[i] == size) return specValues[i]?.let { Rect(it) }
        }
        val r = MaskSpecs.coverageBounds(spec, w, h)
        specKeys[specNext] = java.lang.ref.WeakReference(spec)
        specSizes[specNext] = size
        specValues[specNext] = r
        specNext = (specNext + 1) % SPEC_CACHE
        return r?.let { Rect(it) }
    }

    /** The scratch bitmap, at least [w] x [h]. */
    internal fun scratchBitmap(w: Int, h: Int): Bitmap {
        val cur = bitmap?.takeUnless { it.isRecycled }
        if (cur != null && cur.width >= w && cur.height >= h) return cur
        val nw = max(w, cur?.width ?: 0)
        val nh = max(h, cur?.height ?: 0)
        cur?.recycle()
        bitmap = null
        val b = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        bitmap = b
        return b
    }

    /** The scratch pixel buffer, at least [n] ints. */
    internal fun scratchPixels(n: Int): IntArray {
        if (pixels.size < n) pixels = IntArray(n)
        return pixels
    }

    /** Frees the buffers. */
    fun release() {
        bitmap?.recycle()
        bitmap = null
        pixels = IntArray(0)
        boundsMasks.fill(null)
        boundsValues.fill(null)
        effectSpecs.fill(null)
        effectMappers.fill(null)
        specKeys.fill(null)
        specValues.fill(null)
    }

    private companion object {
        const val EFFECT_CACHE = 8
        const val BOUNDS_CACHE = 8
        const val SPEC_CACHE = 8

        /** Bounds of the pixels of [mask] whose color isn't black, or null when all are black. */
        fun nonBlackBounds(mask: Bitmap): Rect? {
            val w = mask.width; val h = mask.height
            if (w <= 0 || h <= 0) return null
            val row = IntArray(w)
            fun rowHas(y: Int): Boolean {
                mask.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) if (row[x] and 0xFFFFFF != 0) return true
                return false
            }
            var top = 0
            while (top < h && !rowHas(top)) top++
            if (top == h) return null
            var bottom = h - 1
            while (bottom > top && !rowHas(bottom)) bottom--
            var left = w; var right = -1
            for (y in top..bottom) {
                mask.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < left && row[x] and 0xFFFFFF == 0) x++
                if (x < left) left = x
                x = w - 1
                while (x > right && row[x] and 0xFFFFFF == 0) x--
                if (x > right) right = x
                if (left == 0 && right == w - 1) break
            }
            return if (right < left) null else Rect(left, top, right + 1, bottom + 1)
        }
    }
}

/**
 * Implemented by a [LayerRenderOverride] that draws an adjustment layer's mask differently while
 * it is edited (the Masks tool's previews and live brush strokes): where the mask it draws can be
 * non-black, so the effect is only computed there. On an adjustment layer that has no mask yet,
 * such an override's [LayerRenderOverride.drawMask] supplies the mask being made (the preview of
 * its first part), so the effect shows only where the mask will let it through.
 */
interface MaskCoverageHint {
    /** Document area outside which the drawn mask is black; null = nowhere. */
    fun maskCoverage(): Rect?
}

/**
 * Live adjustment layers (v1.5 §4.3c; owned by A5): when the compositor's top-level loop reaches
 * a visible adjustment layer, the composite below it — read from the caller's [CompositeTarget] —
 * is mapped by the effect's pixel mapper and drawn back as the layer's content, with the layer's
 * blend mode, opacity and mask (or the override's mask).
 *
 * Only the area the mask lets through is processed (the spec's coverage bounds, or the painted
 * mask's non-black bounds), in chunks of at most [CHUNK]² target pixels, mapped on [Parallel]
 * rows. With the NORMAL blend mode the result is exactly `lerp(below, F(below), mask·opacity)`:
 * where the composite below is opaque the mapped pixels are drawn over it like any layer; where
 * it is not, two passes (DST_OUT by mask·opacity, then PLUS of the masked effect) keep its alpha,
 * so an adjustment never makes transparent areas more opaque. Other blend modes blend the mapped
 * composite onto it like a layer of that mode.
 *
 * An unknown effect, an effect without a mapper or one that is exactly the identity draws
 * nothing (pass-through), as does every adjustment layer on the canvas while [safeCompositing]
 * is on.
 */
object AdjustmentStage {
    /** Largest side of one processed chunk (target px). */
    const val CHUNK = 512

    /**
     * "Safe compositing" kill switch (`AppSettings.safeCompositing`, I5): adjustment layers draw
     * as pass-through ON THE CANVAS (display tiles); flattened images, exports, thumbnails, merges
     * and tool patches keep the effect, so the switch never changes what the artwork is.
     * Process-wide; the Masks tool loads it from the settings when the editor starts and its sheet
     * toggles it.
     */
    @Volatile
    var safeCompositing: Boolean = false

    /** Below this many pixels a chunk is mapped on the calling thread. */
    private const val PARALLEL_MIN = 16_384

    /**
     * Applies adjustment [layer] onto [canvas] (document px, clipped to [bounds]); [override] is
     * the active render override (if it concerns [layer], its mask preview applies); [target] is
     * the bitmap [canvas] draws into, with the canvas matrix equal to `target.docToTarget`
     * (null = pass-through).
     */
    fun draw(canvas: Canvas, layer: Layer, bounds: RectF, override: LayerRenderOverride?, target: CompositeTarget?, scratch: AdjustmentScratch) {
        if (target == null || (safeCompositing && target.display)) return
        val spec = layer.adjustment ?: return
        val mapper = scratch.mapperFor(spec) ?: return
        val bmp = target.bitmap
        if (bmp.isRecycled || bmp.config != Bitmap.Config.ARGB_8888) return
        val ov = if (override != null && override.layer === layer) override else null
        val mask = if (layer.maskEnabled) layer.mask else null
        // The mask the effect goes through: the layer's (enabled) mask, or the preview of the
        // mask a layer without one is getting (see MaskCoverageHint).
        val masked = mask != null || (layer.mask == null && ov is MaskCoverageHint)
        val region = RectF(bounds)
        if (masked) {
            // Where the mask lets the effect through: the layer's own mask, or what an override
            // drawing it (a mask being edited) says; an override that doesn't say: everywhere.
            val cov: Rect? = when {
                ov == null -> scratch.maskCoverage(layer, mask!!) ?: return
                ov is MaskCoverageHint -> ov.maskCoverage() ?: return
                else -> null
            }
            if (cov != null && !region.intersect(cov.left.toFloat(), cov.top.toFloat(), cov.right.toFloat(), cov.bottom.toFloat())) return
        }
        if (!target.docToTarget.invert(scratch.inverse)) return
        val inverse = scratch.inverse
        val mapped = RectF(region)
        target.docToTarget.mapRect(mapped)
        val t = Rect(floorInt(mapped.left), floorInt(mapped.top), ceilInt(mapped.right), ceilInt(mapped.bottom))
        if (!t.intersect(0, 0, bmp.width, bmp.height) || t.isEmpty) return
        val o = layer.opacity.coerceIn(0f, 1f)
        val alpha = (o * 255f + 0.5f).toInt()
        if (alpha <= 0) return
        val normal = layer.blendMode == LayerBlendMode.NORMAL
        val mode = scratch.colorMode
        val blendPaint = BlendModes.paint(layer.blendMode, o).apply { isFilterBitmap = false }
        val cw0 = min(CHUNK, t.width()); val ch0 = min(CHUNK, t.height())
        val s = scratch.scratchBitmap(cw0, ch0)
        val px = scratch.scratchPixels(cw0 * ch0)
        val src = Rect()
        val dst = Rect()
        var y = t.top
        while (y < t.bottom) {
            val ch = min(CHUNK, t.bottom - y)
            var x = t.left
            while (x < t.right) {
                val cw = min(CHUNK, t.right - x)
                bmp.getPixels(px, 0, cw, x, y, cw, ch)
                val n = cw * ch
                var opaque = true
                for (i in 0 until n) if (px[i] ushr 24 != 0xFF) { opaque = false; break }
                if (n < PARALLEL_MIN) mapRange(mapper, mode, px, 0, n)
                else Parallel.forRange(ch, 8) { r0, r1 -> mapRange(mapper, mode, px, r0 * cw, r1 * cw) }
                s.setPixels(px, 0, cw, 0, 0, cw, ch)
                src.set(0, 0, cw, ch)
                dst.set(x, y, x + cw, y + ch)
                val save = canvas.save()
                // The chunk, in target pixels; then back to document px for the mask.
                canvas.concat(inverse)
                canvas.clipRect(dst)
                canvas.concat(target.docToTarget)
                if (normal && !opaque) {
                    // dst · (1 − m·o) …
                    scratch.dstOutPaint.alpha = alpha
                    val s1 = canvas.saveLayer(bounds, scratch.dstOutPaint)
                    canvas.drawPaint(scratch.whitePaint)
                    if (masked) drawMask(canvas, mask, ov, scratch)
                    canvas.restoreToCount(s1)
                    // … + F(below) · m·o.
                    scratch.plusPaint.alpha = alpha
                    val s2 = canvas.saveLayer(bounds, scratch.plusPaint)
                    drawScratch(canvas, s, src, dst, inverse, scratch.plainPaint)
                    if (masked) drawMask(canvas, mask, ov, scratch)
                    canvas.restoreToCount(s2)
                } else if (!masked) {
                    drawScratch(canvas, s, src, dst, inverse, blendPaint)
                } else {
                    val sl = canvas.saveLayer(bounds, blendPaint)
                    drawScratch(canvas, s, src, dst, inverse, scratch.plainPaint)
                    drawMask(canvas, mask, ov, scratch)
                    canvas.restoreToCount(sl)
                }
                canvas.restoreToCount(save)
                x += cw
            }
            y += ch
        }
    }

    /**
     * Maps [px] from [from] until [until] with [mapper]; in a grayscale or 1-bit document the
     * colors are then constrained to it (alpha kept: the composite's coverage never changes).
     */
    private fun mapRange(mapper: PixelMapper, mode: ColorMode, px: IntArray, from: Int, until: Int) {
        mapper.map(px, from, until)
        if (mode == ColorMode.RGB) return
        for (i in from until until) {
            val c = px[i]
            val a = c and 0xFF000000.toInt()
            if (a == 0) continue
            px[i] = a or (ColorModeOps.constrainPixel(c or 0xFF000000.toInt(), mode) and 0xFFFFFF)
        }
    }

    /** Draws the mapped chunk 1:1 onto the target pixels it was read from. */
    private fun drawScratch(canvas: Canvas, s: Bitmap, src: Rect, dst: Rect, inverse: Matrix, paint: Paint) {
        val save = canvas.save()
        canvas.concat(inverse)
        canvas.drawBitmap(s, src, dst, paint)
        canvas.restoreToCount(save)
    }

    /** The layer's [mask] (or the override's preview of it, or of the mask it is getting) as DST_IN, in document px. */
    private fun drawMask(canvas: Canvas, mask: Bitmap?, ov: LayerRenderOverride?, scratch: AdjustmentScratch) {
        if (ov != null && ov.drawMask(canvas, scratch.maskPaint)) return
        if (mask != null) canvas.drawBitmap(mask, 0f, 0f, scratch.maskPaint)
    }

    private fun floorInt(v: Float): Int = if (v.isNaN()) 0 else floor(v.toDouble()).coerceIn(-1e9, 1e9).toInt()
    private fun ceilInt(v: Float): Int = if (v.isNaN()) 0 else ceil(v.toDouble()).coerceIn(-1e9, 1e9).toInt()
}
