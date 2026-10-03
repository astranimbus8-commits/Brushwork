package com.brushwork.paint.engine.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.MaskFactorCache
import com.brushwork.paint.engine.MultiLayerRenderOverride
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer

/**
 * What the composite below a live adjustment layer depends on (§3.1 C2), checked every frame
 * against a proxy tile's below-cache: for every layer below its identity, bitmap, mask, content
 * version, visibility, opacity, blend mode, clipping, mask switch and data (an adjustment below
 * changes its spec without a content version until its step is recorded); the split index, the
 * document's color mode and the safe-compositing switch. The proxy scale and tile are fixed per
 * [ProxyTiles] instance. A render override drawing a layer below changes what it draws without
 * changing anything here: the cache is then never trusted ([matches] is false). Main thread.
 */
internal class BelowKey {
    private var refs = arrayOfNulls<Any>(0)
    private var nums = LongArray(0)
    private var split = -1
    private var colorMode: ColorMode? = null
    private var safe = false

    /** True when the below-cache captured with [capture] is still what layers [0, split) draw. */
    fun matches(doc: Document, split: Int, safe: Boolean, override: LayerRenderOverride?): Boolean {
        if (this.split != split || colorMode != doc.colorMode || this.safe != safe) return false
        if (overrideBelow(doc, split, override)) return false
        val layers = doc.layers
        if (split > layers.size) return false
        for (i in 0 until split) {
            val l = layers[i]
            val r = i * REFS
            if (refs[r] !== l || refs[r + 1] !== l.bitmap || refs[r + 2] !== l.mask || refs[r + 3] !== l.adjustment || refs[r + 4] !== l.maskSpec) return false
            val n = i * NUMS
            if (nums[n] != l.contentVersion || nums[n + 1] != packed(l)) return false
        }
        return true
    }

    /** Remembers what layers [0, split) are now (after the below-cache was drawn). */
    fun capture(doc: Document, split: Int, safe: Boolean) {
        if (refs.size < split * REFS) refs = arrayOfNulls(split * REFS)
        if (nums.size < split * NUMS) nums = LongArray(split * NUMS)
        refs.fill(null)
        for (i in 0 until split) {
            val l = doc.layers[i]
            val r = i * REFS
            refs[r] = l; refs[r + 1] = l.bitmap; refs[r + 2] = l.mask; refs[r + 3] = l.adjustment; refs[r + 4] = l.maskSpec
            val n = i * NUMS
            nums[n] = l.contentVersion
            nums[n + 1] = packed(l)
        }
        this.split = split
        colorMode = doc.colorMode
        this.safe = safe
    }

    /** Forgets the capture (the next [matches] is false); drops the references. */
    fun clear() {
        refs.fill(null)
        split = -1
        colorMode = null
    }

    private fun packed(l: Layer): Long {
        var flags = 0L
        if (l.visible) flags = flags or 1L
        if (l.clipping) flags = flags or 2L
        if (l.maskEnabled) flags = flags or 4L
        return (java.lang.Float.floatToRawIntBits(l.opacity).toLong() shl 32) or (l.blendMode.ordinal.toLong() shl 8) or flags
    }

    companion object {
        private const val REFS = 5
        private const val NUMS = 2

        /** True when [override] draws a layer of [0, split) (what it draws may change at any time). */
        fun overrideBelow(doc: Document, split: Int, override: LayerRenderOverride?): Boolean {
            if (override == null || split <= 0) return false
            if (override is MultiLayerRenderOverride) {
                if (doc.indexOf(override.layer) in 0 until split) return true
                for (l in override.layers) if (doc.indexOf(l) in 0 until split) return true
                return false
            }
            return doc.indexOf(override.layer) in 0 until split
        }
    }
}

/**
 * The proxy tiles of a live adjustment session at one proxy [scale] (a power of two in 1/8..1):
 * tiles of [tileSize] proxy px covering `tileSize / scale` document px each, so every display tile
 * of the canvas (also [tileSize] document px) lies in exactly one of them. Each holds the frame
 * (the composite at [scale]) and, when layers lie below the adjustment, the below-cache: the
 * composite of those layers, restored into the frame before the adjustment and the layers above
 * it are drawn again. Proxies are views (I7): nothing here is ever saved, exported or sampled.
 * Main thread.
 */
internal class ProxyTiles(val docWidth: Int, val docHeight: Int, val tileSize: Int, val scale: Float) {
    /** Document px per proxy px (1, 2, 4 or 8). */
    val inv: Int = Math.round(1f / scale).coerceAtLeast(1)

    /** Document px per proxy tile side. */
    val span: Int = tileSize * inv
    val cols: Int = (docWidth + span - 1) / span
    val rows: Int = (docHeight + span - 1) / span
    private val proxies = arrayOfNulls<Proxy>(cols * rows)

    /** Bytes the allocated proxies hold. */
    var bytes: Long = 0
        private set

    inner class Proxy(val index: Int) {
        /** The document area this proxy covers (within the document). */
        val docRect: Rect = Rect(
            (index % cols) * span, (index / cols) * span,
            minOf(docWidth, (index % cols + 1) * span), minOf(docHeight, (index / cols + 1) * span),
        )
        val pw: Int = (docRect.width() + inv - 1) / inv
        val ph: Int = (docRect.height() + inv - 1) / inv

        /** Document -> proxy px. */
        val matrix: Matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(-docRect.left * scale, -docRect.top * scale)
        }
        val bitmap: Bitmap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        /** The adjustment layer's mask at this proxy's scale (kept while it doesn't change; a slider drag). */
        val maskCache = MaskFactorCache()
        val target = CompositeTarget(bitmap, matrix, display = true, maskCache = maskCache)

        /** The below-cache (null until drawn, or when nothing lies below). */
        var below: Bitmap? = null
        val key = BelowKey()

        /** A foreign change was drawn here: the below-cache can't be trusted. */
        var belowStale = true

        /** Bytes counted for this proxy in [bytes]. */
        var accounted: Long = 0

        /** Document area to draw again (null: the frame is up to date). */
        var dirty: Rect? = Rect(docRect)

        /** Where the frame's proxy px lie in the document (the last column / row may reach past it). */
        val drawnRect: RectF = RectF(docRect.left.toFloat(), docRect.top.toFloat(), (docRect.left + pw * inv).toFloat(), (docRect.top + ph * inv).toFloat())

        fun release() {
            bitmap.recycle()
            below?.recycle()
            below = null
            key.clear()
            maskCache.release()
        }
    }

    /** Index of the proxy covering display tile ([col], [row]). */
    fun indexOfTile(col: Int, row: Int): Int = (row / inv) * cols + (col / inv)

    fun get(index: Int): Proxy? = proxies.getOrNull(index)

    /** Bytes proxy [index] needs (frame plus below-cache when [withBelow], plus a byte per pixel of mask factors). */
    fun bytesFor(index: Int, withBelow: Boolean): Long {
        val c = index % cols; val r = index / cols
        val w = minOf(docWidth, (c + 1) * span) - c * span
        val h = minOf(docHeight, (r + 1) * span) - r * span
        val px = ((w + inv - 1) / inv).toLong() * ((h + inv - 1) / inv)
        return px * 4L * (if (withBelow) 2 else 1) + px
    }

    /** Proxy [index], allocated (fully dirty) when missing. */
    fun obtain(index: Int, withBelow: Boolean): Proxy {
        proxies[index]?.let { return it }
        val p = Proxy(index)
        p.accounted = bytesFor(index, withBelow)
        proxies[index] = p
        bytes += p.accounted
        return p
    }

    /** Frees proxy [index] (and its below-cache). */
    fun free(index: Int) {
        val p = proxies[index] ?: return
        bytes = (bytes - p.accounted).coerceAtLeast(0)
        p.release()
        proxies[index] = null
    }

    /** Every allocated proxy index. */
    fun allocated(): List<Int> = proxies.indices.filter { proxies[it] != null }

    /**
     * Document area [r] changed: the proxies there draw it again; [foreign] (not the session's
     * own adjustment layer) also distrusts their below-cache.
     */
    fun invalidate(r: Rect, foreign: Boolean) {
        if (r.isEmpty) return
        val c0 = (r.left / span).coerceIn(0, cols - 1); val c1 = ((r.right - 1) / span).coerceIn(0, cols - 1)
        val r0 = (r.top / span).coerceIn(0, rows - 1); val r1 = ((r.bottom - 1) / span).coerceIn(0, rows - 1)
        for (row in r0..r1) for (col in c0..c1) {
            val p = proxies[row * cols + col] ?: continue
            val part = Rect(r)
            if (!part.intersect(p.docRect)) continue
            if (foreign) {
                // A change the session didn't make: what is below, and the mask itself (pixels
                // painted without a content version), can't be trusted any more.
                p.belowStale = true
                p.maskCache.clear()
            }
            val d = p.dirty
            if (d == null) p.dirty = part else d.union(part)
        }
    }

    /** Every proxy draws everything again (below-caches are re-checked by their keys). */
    fun invalidateAll() {
        for (p in proxies) p?.dirty = Rect(p.docRect)
    }

    fun release() {
        for (i in proxies.indices) { proxies[i]?.release(); proxies[i] = null }
        bytes = 0
    }

    companion object {
        /** Restores below-caches 1:1 (premultiplied pixels copied as they are). */
        val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        /** Draws frames onto the canvas under the view matrix (filtered, §3.1 C2). */
        val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        /**
         * Draws 1:1 frames ([scale] 1) unfiltered while the canvas shows crisp pixels (zoomed far
         * in). Explicitly: a new Paint filters bitmaps by default on current Android versions.
         */
        val crispPaint = Paint().apply { isFilterBitmap = false }
    }
}
