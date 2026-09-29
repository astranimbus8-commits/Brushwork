package com.brushwork.paint.ui.layers

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.brushwork.paint.model.Layer
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Downscaled layer/mask previews for the layers panel, cached by (layer id, contentVersion) and
 * the identity of the source bitmap (flips and merges replace it). Main thread only.
 *
 * Thumbnails are never recycled explicitly: Compose may still be drawing the previous one, and at
 * ~100 px they are cheap enough to leave to the GC.
 */
class LayerThumbnails(private val maxSize: Int) {

    /** [source] is weak: a flip or merge replaces the full-size bitmap, which must not be kept alive here. */
    private class Entry(val version: Long, val source: WeakReference<Bitmap>, val image: ImageBitmap)

    private val content = HashMap<Long, Entry>()
    private val masks = HashMap<Long, Entry>()

    /** Preview of the layer's pixels. */
    fun content(layer: Layer): ImageBitmap = lookup(content, layer.id, layer.contentVersion, layer.bitmap)

    /** Preview of the layer's mask, or null when it has none. */
    fun mask(layer: Layer): ImageBitmap? {
        val m = layer.mask ?: run { masks.remove(layer.id); return null }
        return lookup(masks, layer.id, layer.contentVersion, m)
    }

    /** Drops entries of layers that no longer exist. */
    fun retain(ids: Set<Long>) {
        content.keys.retainAll(ids)
        masks.keys.retainAll(ids)
    }

    val size: Int get() = content.size + masks.size

    private fun lookup(cache: HashMap<Long, Entry>, id: Long, version: Long, source: Bitmap): ImageBitmap {
        val e = cache[id]
        if (e != null && e.version == version && e.source.get() === source) return e.image
        if (source.isRecycled) return e?.image ?: EMPTY
        val image = downscale(source, maxSize).asImageBitmap()
        cache[id] = Entry(version, WeakReference(source), image)
        return image
    }

    companion object {
        private val EMPTY: ImageBitmap by lazy {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

        /** Size of the thumbnail of a [w] x [h] image fitted into [maxSize] (never upscaled). */
        fun fitSize(w: Int, h: Int, maxSize: Int): Pair<Int, Int> {
            val s = minOf(1f, maxSize.toFloat() / max(1, max(w, h)))
            return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
        }

        /**
         * A new bitmap of [src] fitted into [maxSize] x [maxSize]. Big reductions go through one
         * intermediate size so that a single bilinear pass doesn't skip most source pixels
         * (thin strokes would vanish). Never returns [src] itself.
         */
        fun downscale(src: Bitmap, maxSize: Int): Bitmap {
            val (w, h) = fitSize(src.width, src.height, maxSize)
            var from = src
            if (src.width > w * 8 && src.height > h * 8) {
                from = Bitmap.createScaledBitmap(src, w * 3, h * 3, true)
            }
            val out = Bitmap.createScaledBitmap(from, w, h, true)
            if (from !== src && from !== out) from.recycle()
            return if (out === src) src.copy(Bitmap.Config.ARGB_8888, false) else out
        }
    }
}
