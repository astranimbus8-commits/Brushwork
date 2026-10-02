package com.brushwork.paint.tools.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.model.Layer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The reduced copy of a layer's mask the Masks tool's red coverage overlay is drawn from (v1.5
 * §4.3a; owned by A5). Drawing the full-resolution mask in the overlay would upload it to the GPU
 * on every frame it changes (10 MB at 1080 x 2408 while a brush stroke paints, 80 MB at
 * 4000 x 5000); this copy is at most about [MAX_PIXELS] pixels. It is rebuilt when the mask
 * bitmap or the layer's content version changes, and patched where a live brush stroke paints
 * (whose pixels change before the version does). Main thread.
 */
internal class MaskTint {
    /** The reduced mask (gray, like the mask), or null when there is none yet. */
    private var bitmap: Bitmap? = null
    private var source: Bitmap? = null
    private var version = Long.MIN_VALUE

    /** Copy pixels per mask pixel. */
    var scale = 1f
        private set

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val canvas = Canvas()
    private val dst = RectF()

    /**
     * The reduced copy of [mask] (the mask of [layer]), up to date — or [mask] itself when it is
     * small enough ([scale] 1); null when memory is short.
     */
    fun of(layer: Layer, mask: Bitmap): Bitmap? {
        if (mask.isRecycled) return null
        val s = scaleFor(mask.width, mask.height)
        if (s >= 1f) {
            if (bitmap != null) release()
            scale = 1f
            return mask
        }
        val cur = bitmap
        if (cur != null && !cur.isRecycled && mask === source && layer.contentVersion == version) return cur
        val w = max(1, ceil(mask.width * s).toInt()); val h = max(1, ceil(mask.height * s).toInt())
        val b = cur?.takeIf { !it.isRecycled && it.width == w && it.height == h } ?: try {
            cur?.recycle()
            bitmap = null
            source = null
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        bitmap = b
        scale = s
        source = mask
        version = layer.contentVersion
        draw(mask, null)
        return b
    }

    /**
     * The pixels of [mask] changed within [region] (document px) while the layer's version stays
     * the same (a live brush stroke, or one cancelled): the copy follows there.
     */
    fun refresh(mask: Bitmap, region: Rect) {
        if (mask !== source || bitmap == null || region.isEmpty) return
        draw(mask, region)
    }

    /**
     * The layer's version moved on from [before], and the copy already shows its mask (a live
     * stroke patched it and was committed). Nothing when the copy wasn't current at [before].
     */
    fun adopt(layer: Layer, mask: Bitmap, before: Long) {
        if (mask === source && bitmap != null && version == before) version = layer.contentVersion
    }

    /** Frees the copy. */
    fun release() {
        bitmap?.recycle()
        bitmap = null
        source = null
        version = Long.MIN_VALUE
    }

    /**
     * Draws [mask] reduced into the copy, within [region] (document px; null = everywhere). The
     * whole mask is drawn through a clip, so a patched area gets exactly what a rebuild gives.
     */
    private fun draw(mask: Bitmap, region: Rect?) {
        val b = bitmap ?: return
        canvas.setBitmap(b)
        try {
            canvas.save()
            if (region != null) {
                // One copy pixel of margin: bilinear sampling reaches one mask pixel around.
                val l = max(0, floor(region.left * scale).toInt() - 1)
                val t = max(0, floor(region.top * scale).toInt() - 1)
                val r = min(b.width, ceil(region.right * scale).toInt() + 1)
                val bt = min(b.height, ceil(region.bottom * scale).toInt() + 1)
                if (r <= l || bt <= t) return
                canvas.clipRect(l, t, r, bt)
            }
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            dst.set(0f, 0f, mask.width * scale, mask.height * scale)
            canvas.drawBitmap(mask, null, dst, paint)
        } finally {
            canvas.restoreToCount(1)
            canvas.setBitmap(null)
        }
    }

    companion object {
        /** Largest copy (pixels). */
        const val MAX_PIXELS = 1_200_000L

        /** Copy pixels per mask pixel for a [w] x [h] mask: 1, or less so the copy stays near [MAX_PIXELS]. */
        fun scaleFor(w: Int, h: Int): Float {
            val px = w.toLong() * h
            return if (px <= MAX_PIXELS) 1f else sqrt(MAX_PIXELS.toFloat() / px).coerceIn(0.05f, 1f)
        }
    }
}
