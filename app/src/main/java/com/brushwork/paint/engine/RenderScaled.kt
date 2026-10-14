package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PorterDuff
import com.brushwork.paint.model.Document
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v1.9 (R9, design 3.2 A7a step 6; owner A7a): the pixels a selection tool reads (the Magnetic
 * lasso's edge map, Color select's proxy), at most [MAX_SIDE] px on the long side, rendered OFF
 * the main thread, never through a full-size flatten.
 *
 * - [Source.canvas] (main thread) takes the canvas as it is now: a private document that holds
 *   the same layers (their bitmaps are not copied), its paper and colour mode. [Source.layer]
 *   takes one layer's pixels. Taking a source is cheap: no pixel is read.
 * - [render] / [renderAt] (any thread) draw it at [size] (or at a given size): 1:1 as
 *   `Compositor.renderFlattened`; from 1/2 up, straight through a scaled canvas (no full-size
 *   bitmap); below 1/2, at twice the size, then filtered down (as `Compositor.renderThumbnail`).
 *   `withPaper`: on the document's paper ([PaperBackdrop]), as the canvas shows it (both tools
 *   pass true, R15); a visible adjustment layer puts it behind, as a matte
 *   (`Compositor.renderFlattened`'s rule). No tool preview and no live override is drawn.
 *
 * The layers' pixels are read while the main thread may change them (a layer recycled meanwhile
 * makes a render throw a `RuntimeException`; the tools take a new source and try once more).
 * PRINT (3000 x 4000, 3 layers): at most 60 ms on a phone (design 3.2 (c)).
 */
object RenderScaled {
    /** The longest side of a [render] (px), the edge map's (`EdgeMap.MAX_SIDE`). */
    const val MAX_SIDE = 2048

    /** What a render draws, taken on the main thread. */
    class Source private constructor(
        internal val doc: Document?,
        internal val layer: Bitmap?,
        /** The source's size (document px). */
        val width: Int,
        val height: Int,
    ) {
        companion object {
            /** The canvas of [doc] as it is now (main thread; the layers are shared, not copied). */
            fun canvas(doc: Document): Source {
                val tmp = Document("render-scaled", "", doc.width, doc.height, doc.dpi)
                tmp.colorMode = doc.colorMode
                tmp.paper = doc.paper
                tmp.layers.addAll(doc.layers)
                return Source(tmp, null, doc.width, doc.height)
            }

            /** One layer's pixels (main thread; read, never changed or recycled by a render). */
            fun layer(bitmap: Bitmap): Source = Source(null, bitmap, bitmap.width, bitmap.height)
        }
    }

    /**
     * The size of a render of [w] x [h] whose long side is at most [maxSide] (the same rounding
     * as `EdgeMap.proxySize`, so the edge map takes it as it is).
     */
    fun size(w: Int, h: Int, maxSide: Int = MAX_SIDE): IntArray {
        val long = max(w, h)
        if (long <= maxSide) return intArrayOf(max(1, w), max(1, h))
        val s = maxSide.toDouble() / long
        return intArrayOf(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
    }

    /**
     * [source] at [size] (long side at most [maxSide]); [withPaper]: on its paper. Any thread;
     * the caller recycles the result. Throws `OutOfMemoryError` and, when a layer went away
     * meanwhile, a `RuntimeException`.
     */
    fun render(source: Source, maxSide: Int = MAX_SIDE, withPaper: Boolean): Bitmap {
        val (w, h) = size(source.width, source.height, maxSide)
        return renderAt(source, w, h, withPaper)
    }

    /** [source] at [w] x [h] (at most its own size; Color select's proxy size); as [render]. */
    fun renderAt(source: Source, w: Int, h: Int, withPaper: Boolean): Bitmap {
        require(w in 1..source.width && h in 1..source.height) { "render size ${w}x$h of ${source.width}x${source.height}" }
        source.layer?.let { bmp ->
            if (w == bmp.width && h == bmp.height) {
                return bmp.copy(Bitmap.Config.ARGB_8888, false) ?: throw OutOfMemoryError("layer copy ${bmp.width}x${bmp.height}")
            }
            return Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        val doc = requireNotNull(source.doc)
        val compositor = Compositor(doc) { null }
        if (w == doc.width && h == doc.height) return compositor.renderFlattened(withPaper = withPaper)
        val bg = if (withPaper) PaperBackdrop.sampleColor(doc) else null
        val s = w.toFloat() / doc.width
        if (s >= 0.5f) return draw(compositor, doc, w, h, bg)
        // Below 1/2: at twice the size, then filtered down (smoother than one bilinear step).
        val s2 = minOf(1f, s * 2f)
        val w2 = max(1, (doc.width * s2).roundToInt())
        val h2 = max(1, (doc.height * s2).roundToInt())
        val mid = draw(compositor, doc, w2, h2, bg)
        var out: Bitmap? = null
        try {
            out = Bitmap.createScaledBitmap(mid, w, h, true)
            return out
        } finally {
            if (out !== mid) mid.recycle()
        }
    }

    /** [doc] drawn through a canvas scaled to [w] x [h], on [bg] (behind, as a matte, under a visible adjustment). */
    private fun draw(compositor: Compositor, doc: Document, w: Int, h: Int, bg: Int?): Bitmap {
        val out = BitmapUtils.createLayerBitmap(w, h)
        try {
            val c = Canvas(out)
            val matte = bg != null && doc.layers.any { it.isAdjustmentLayer && doc.effectiveVisible(it) && it.opacity > 0f }
            if (bg != null && !matte) c.drawColor(bg)
            val sx = w.toFloat() / doc.width
            val sy = h.toFloat() / doc.height
            c.save()
            c.scale(sx, sy)
            compositor.drawDocument(c, null, useOverrides = false, target = CompositeTarget(out, Matrix().apply { setScale(sx, sy) }))
            c.restore()
            if (matte) c.drawColor(bg!!, PorterDuff.Mode.DST_OVER)
            return out
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
    }
}
