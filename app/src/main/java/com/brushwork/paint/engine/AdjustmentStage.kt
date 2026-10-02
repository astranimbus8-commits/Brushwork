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

    /** The unmapped chunk, kept while a non-opaque composite is mapped (see [AdjustmentStage]). */
    private var source: IntArray = IntArray(0)

    internal val maskPaint = BitmapUtils.newMaskApplyPaint()

    /** The mask paint's sampling without its color filter, drawing as SRC (the fused path's scaled masks). */
    internal val maskSamplePaint = Paint(maskPaint).apply {
        colorFilter = null
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
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

    /** A copy of the first [n] ints of [px] (the chunk before it is mapped), in a reused buffer. */
    internal fun sourceCopy(px: IntArray, n: Int): IntArray {
        if (source.size < n) source = IntArray(n)
        System.arraycopy(px, 0, source, 0, n)
        return source
    }

    // ------------------------------------------------------------------ v1.6: the fused NORMAL path (§3.1 C1)

    /** The mapped and blended chunk of the fused path (reused). */
    private var fused: IntArray = IntArray(0)

    /** The mask factors of the fused path's chunk (alpha = m; reused). */
    private var factors: IntArray = IntArray(0)

    /** Chunk the fused path draws the mask into: opaque white, then the mask as DST_IN (reused). */
    private var maskChunk: Bitmap? = null
    private var maskCanvas: Canvas? = null

    /** Matrix of the mask draw: `translate(-x, -y) · docToTarget` (reused). */
    internal val chunkMatrix = Matrix()

    /** The fused path's result buffer, at least [n] ints. */
    internal fun fusedPixels(n: Int): IntArray {
        if (fused.size < n) fused = IntArray(n)
        return fused
    }

    /** The fused path's mask-factor buffer, at least [n] ints. */
    internal fun factorPixels(n: Int): IntArray {
        if (factors.size < n) factors = IntArray(n)
        return factors
    }

    /** A canvas on a reused bitmap of at least [w] x [h] for the fused path's mask chunk. */
    internal fun maskChunkCanvas(w: Int, h: Int): Pair<Bitmap, Canvas> {
        val cur = maskChunk?.takeUnless { it.isRecycled }
        val cc = maskCanvas
        if (cur != null && cc != null && cur.width >= w && cur.height >= h) return cur to cc
        val nw = max(w, cur?.width ?: 0)
        val nh = max(h, cur?.height ?: 0)
        cur?.recycle()
        maskChunk = null
        maskCanvas = null
        val b = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        maskChunk = b
        maskCanvas = c
        return b to c
    }

    /** Frees the buffers. */
    fun release() {
        bitmap?.recycle()
        bitmap = null
        maskChunk?.recycle()
        maskChunk = null
        maskCanvas = null
        fused = IntArray(0)
        factors = IntArray(0)
        pixels = IntArray(0)
        source = IntArray(0)
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
 * composite onto it like a layer of that mode. A mapper that lowers alpha (Gradation Map with
 * semi-transparent stops, see [PixelMapper]) draws its colors that much weaker in every mode, so
 * a transparent stop shows the image below unchanged ([revealed] for the two passes).
 *
 * An unknown effect, an effect without a mapper or one that is exactly the identity draws
 * nothing (pass-through), as does every adjustment layer on the canvas while [safeCompositing]
 * is on.
 *
 * v1.6 fused NORMAL path (§3.1 C1): with the NORMAL blend mode and a [CompositeTarget.directWrite]
 * target (every caller: the canvas draws straight into the target, clipped by a rect, with no
 * pending saveLayer) the result is computed per pixel and written straight back into the target
 * with `setPixels`, with no scratch bitmap, saveLayer or DST_IN pass: per chunk the composite
 * below is read, the mask factor `m` is the alpha of an opaque white chunk with the mask drawn
 * over it by the unchanged v1.5 DST_IN luminance paint (or the override's mask), the mapper runs,
 * and `rgb = lerp(below.rgb, F.rgb, k)` with `k = m·opacity` (non-premultiplied, exactly rounded)
 * while the composite's alpha is kept. That is the documented `lerp(below, F(below), mask·opacity)`
 * for opaque and non-opaque composites alike (the v1.5 two-pass rule), within one level of the v1.5
 * Skia path. Every caller (canvas tiles, live proxies, exports, thumbnails, tool patches) takes it,
 * so what is exported equals what is shown. Other blend modes keep the v1.5 path.
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
        if (normal && target.directWrite) {
            // The canvas clip is the caller's rect [bounds] (CompositeTarget contract); Skia rounds
            // a non-anti-aliased clip to the nearest pixel edge, and so do we: the fused path writes
            // exactly the pixels the v1.5 draw could reach.
            val clip = RectF(bounds)
            target.docToTarget.mapRect(clip)
            if (!t.intersect(Math.round(clip.left), Math.round(clip.top), Math.round(clip.right), Math.round(clip.bottom)) || t.isEmpty) return
            drawFused(mapper, mode, if (masked) mask else null, ov, masked, target, t, alpha, scratch)
            return
        }
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
                // The two-pass composite needs the mapped pixels at the composite's own alpha.
                val below = if (normal && !opaque) scratch.sourceCopy(px, n) else null
                if (n < PARALLEL_MIN) mapRange(mapper, mode, px, below, 0, n)
                else Parallel.forRange(ch, 8) { r0, r1 -> mapRange(mapper, mode, px, below, r0 * cw, r1 * cw) }
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
     * The fused NORMAL path (§3.1 C1): target pixels [t] (already clipped to the canvas clip and
     * the bitmap) become `lerp(below, F(below), m·alpha)` with the composite's alpha kept, written
     * straight into `target.bitmap`. [mask] is the layer's enabled mask (null: none, or the
     * override supplies it); [masked] says whether a mask applies at all.
     */
    private fun drawFused(
        mapper: PixelMapper,
        mode: ColorMode,
        mask: Bitmap?,
        ov: LayerRenderOverride?,
        masked: Boolean,
        target: CompositeTarget,
        t: Rect,
        alpha: Int,
        scratch: AdjustmentScratch,
    ) {
        val bmp = target.bitmap
        val cw0 = min(CHUNK, t.width()); val ch0 = min(CHUNK, t.height())
        val n0 = cw0 * ch0
        val below = scratch.scratchPixels(n0)
        val out = scratch.fusedPixels(n0)
        val factors = if (masked) scratch.factorPixels(n0) else null
        val m = scratch.chunkMatrix
        // The layer's own mask on a target that is document pixels shifted by whole pixels (canvas
        // tiles, flattened images, tool patches) is read as it is: its luminance is exactly what
        // the DST_IN luminance paint gives (no resampling), without a color-filtered draw.
        val tx = integerTranslation(target.docToTarget, X)
        val ty = integerTranslation(target.docToTarget, Y)
        var y = t.top
        while (y < t.bottom) {
            val ch = min(CHUNK, t.bottom - y)
            var x = t.left
            while (x < t.right) {
                val cw = min(CHUNK, t.right - x)
                val n = cw * ch
                bmp.getPixels(below, 0, cw, x, y, cw, ch)
                // How the chunk's mask factors are stored: none (m = 255), as alpha (an override
                // drew its mask through the DST_IN luminance paint) or as the luminance of the
                // mask's own grey.
                var kind = FACTORS_NONE
                if (factors != null) {
                    m.set(target.docToTarget)
                    m.postTranslate(-x.toFloat(), -y.toFloat())
                    if (ov != null && drawOverrideMask(ov, m, cw, ch, factors, scratch)) {
                        kind = FACTORS_ALPHA
                    } else if (mask != null) {
                        if (tx != NOT_INTEGER && ty != NOT_INTEGER) readMask(mask, x - tx, y - ty, cw, ch, factors)
                        else sampleMask(mask, m, cw, ch, factors, scratch)
                        kind = FACTORS_LUMINANCE
                    }
                }
                if (n < PARALLEL_MIN) fuseRange(mapper, mode, below, out, factors, kind, alpha, 0, n)
                else Parallel.forRange(ch, 8) { r0, r1 -> fuseRange(mapper, mode, below, out, factors, kind, alpha, r0 * cw, r1 * cw) }
                bmp.setPixels(out, 0, cw, x, y, cw, ch)
                x += cw
            }
            y += ch
        }
    }

    private const val FACTORS_NONE = 0
    private const val FACTORS_ALPHA = 1
    private const val FACTORS_LUMINANCE = 2
    private const val X = 0
    private const val Y = 1
    private const val NOT_INTEGER = Int.MIN_VALUE

    /** The whole-pixel translation of [m] along [axis] when [m] is exactly such a translation, else [NOT_INTEGER]. */
    private fun integerTranslation(m: Matrix, axis: Int): Int {
        val v = FloatArray(9)
        m.getValues(v)
        if (v[Matrix.MSCALE_X] != 1f || v[Matrix.MSCALE_Y] != 1f || v[Matrix.MSKEW_X] != 0f || v[Matrix.MSKEW_Y] != 0f ||
            v[Matrix.MPERSP_0] != 0f || v[Matrix.MPERSP_1] != 0f || v[Matrix.MPERSP_2] != 1f
        ) return NOT_INTEGER
        val tr = if (axis == X) v[Matrix.MTRANS_X] else v[Matrix.MTRANS_Y]
        val r = Math.round(tr)
        return if (tr == r.toFloat() && kotlin.math.abs(r) < 1_000_000_000) r else NOT_INTEGER
    }

    /**
     * The override's mask over an opaque white chunk of [cw] x [ch] through the v1.5 DST_IN
     * luminance paint (the [LayerRenderOverride.drawMask] contract; e.g. the Masks tool's
     * half-resolution preview), drawn with [m] (`translate(-x, -y) · docToTarget`); [factors]
     * gets the chunk (m = alpha). False when the override leaves the mask to the default.
     */
    private fun drawOverrideMask(ov: LayerRenderOverride, m: Matrix, cw: Int, ch: Int, factors: IntArray, scratch: AdjustmentScratch): Boolean {
        val (mb, mc) = scratch.maskChunkCanvas(cw, ch)
        val save = mc.save()
        mc.clipRect(0, 0, cw, ch)
        mc.drawColor(-1, PorterDuff.Mode.SRC)
        mc.concat(m)
        val drawn = ov.drawMask(mc, scratch.maskPaint)
        mc.restoreToCount(save)
        if (drawn) mb.getPixels(factors, 0, cw, 0, 0, cw, ch)
        return drawn
    }

    /** Mask pixels ([mx], [my]) + [cw] x [ch] into [factors]; outside the mask white (the DST_IN draw leaves those). */
    private fun readMask(mask: Bitmap, mx: Int, my: Int, cw: Int, ch: Int, factors: IntArray) {
        val r = Rect(mx, my, mx + cw, my + ch)
        if (r.left < 0 || r.top < 0 || r.right > mask.width || r.bottom > mask.height) {
            factors.fill(-1, 0, cw * ch)
            if (!r.intersect(0, 0, mask.width, mask.height)) return
        }
        mask.getPixels(factors, (r.top - my) * cw + (r.left - mx), cw, r.left, r.top, r.width(), r.height())
    }

    /**
     * [mask] resampled onto the chunk with [m] (a scaled target: thumbnails, live proxies), with
     * the sampling of the v1.5 mask paint but no color filter; outside the mask white.
     */
    private fun sampleMask(mask: Bitmap, m: Matrix, cw: Int, ch: Int, factors: IntArray, scratch: AdjustmentScratch) {
        val (mb, mc) = scratch.maskChunkCanvas(cw, ch)
        val save = mc.save()
        mc.clipRect(0, 0, cw, ch)
        mc.drawColor(-1, PorterDuff.Mode.SRC)
        mc.concat(m)
        mc.drawBitmap(mask, 0f, 0f, scratch.maskSamplePaint)
        mc.restoreToCount(save)
        mb.getPixels(factors, 0, cw, 0, 0, cw, ch)
    }

    /**
     * Pixels [from] until [until] of the fused path: [out] = F([below]) (mapped, held to the color
     * mode, [revealed] where the mapper changed the alpha), then blended with [below] by
     * `k = m·alpha`, alpha kept. m comes from [factors] as [kind] says (255 without a mask).
     */
    private fun fuseRange(mapper: PixelMapper, mode: ColorMode, below: IntArray, out: IntArray, factors: IntArray?, kind: Int, alpha: Int, from: Int, until: Int) {
        System.arraycopy(below, from, out, from, until - from)
        mapRange(mapper, mode, out, below, from, until)
        for (i in from until until) {
            val m = when (kind) {
                FACTORS_ALPHA -> factors!![i] ushr 24
                FACTORS_LUMINANCE -> luminance(factors!![i])
                else -> 255
            }
            out[i] = blend(below[i], out[i], (m * alpha + 127) / 255)
        }
    }

    /**
     * What the v1.5 DST_IN luminance paint makes of mask pixel [c] (unpremultiplied): the alpha
     * `0.299 r + 0.587 g + 0.114 b`, rounded; a grey's own level.
     */
    internal fun luminance(c: Int): Int = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114 + 500) / 1000

    /**
     * [below] with its colour moved towards [mapped]'s by [k] / 255 (non-premultiplied, exactly
     * rounded per channel) and its own alpha: `lerp(below, F, k)` at the composite's alpha.
     */
    internal fun blend(below: Int, mapped: Int, k: Int): Int {
        if (k <= 0) return below
        val a = below and 0xFF000000.toInt()
        if (k >= 255) return a or (mapped and 0xFFFFFF)
        val inv = 255 - k
        val r = ((mapped shr 16 and 0xFF) * k + (below shr 16 and 0xFF) * inv + 127) / 255
        val g = ((mapped shr 8 and 0xFF) * k + (below shr 8 and 0xFF) * inv + 127) / 255
        val bl = ((mapped and 0xFF) * k + (below and 0xFF) * inv + 127) / 255
        return a or (r shl 16) or (g shl 8) or bl
    }

    /**
     * Maps [px] from [from] until [until] with [mapper]; in a grayscale or 1-bit document the
     * colors are then constrained to it (the mapped alpha kept). With [below] (the unmapped
     * pixels, for the two-pass composite over a non-opaque composite) see [revealed].
     */
    private fun mapRange(mapper: PixelMapper, mode: ColorMode, px: IntArray, below: IntArray?, from: Int, until: Int) {
        mapper.map(px, from, until)
        if (mode != ColorMode.RGB) {
            for (i in from until until) {
                val c = px[i]
                val a = c and 0xFF000000.toInt()
                if (a == 0) continue
                px[i] = a or (ColorModeOps.constrainPixel(c or 0xFF000000.toInt(), mode) and 0xFFFFFF)
            }
        }
        if (below != null) for (i in from until until) if (px[i] ushr 24 != below[i] ushr 24) px[i] = revealed(below[i], px[i])
    }

    /**
     * A mapped pixel [mapped] whose alpha differs from the composite's pixel [below] (a mapper
     * that lowers alpha on purpose: Gradation Map with semi-transparent stops, see [PixelMapper]),
     * as the same result at the composite's own alpha: the mapped color mixed with the original by
     * mappedAlpha / alpha, so a transparent stop shows the image below. This is exactly what the
     * one-pass SRC_OVER draws over an opaque composite; the two passes (which keep the alpha)
     * need it spelled out, or a lowered alpha would fade the image instead. A raised alpha is
     * held to the composite's (an adjustment never adds coverage). Non-premultiplied ARGB.
     */
    internal fun revealed(below: Int, mapped: Int): Int {
        val sa = below ushr 24
        val ma = mapped ushr 24
        if (sa == 0) return below
        if (ma >= sa) return (sa shl 24) or (mapped and 0xFFFFFF)
        val keep = sa - ma
        val half = sa / 2
        val r = ((below shr 16 and 0xFF) * keep + (mapped shr 16 and 0xFF) * ma + half) / sa
        val g = ((below shr 8 and 0xFF) * keep + (mapped shr 8 and 0xFF) * ma + half) / sa
        val b = ((below and 0xFF) * keep + (mapped and 0xFF) * ma + half) / sa
        return (sa shl 24) or (r shl 16) or (g shl 8) or b
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
