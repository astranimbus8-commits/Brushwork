package com.brushwork.paint.ui.layers

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.model.Layer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Downscaled layer/mask previews for the layers panel. Main thread only.
 *
 * v1.10 A9 (design 4.4 A9 step 4, lazy keys): cached by (layer id, `contentVersion`,
 * `pixelGeneration`); the generation goes up when the content or the mask store is replaced
 * (flips and merges), never on park, unpark, evict or swap (J5). A preview is made only from a
 * FLAT store's bitmap: a layer whose store is not flat (parked, swapped, blank) keeps the preview
 * it had while its key is unchanged, else shows a transparent placeholder until it is flat again
 * (the active layer is), so the panel never materializes a layer.
 *
 * Thumbnails are never recycled explicitly: Compose may still be drawing the previous one, and at
 * ~100 px they are cheap enough to leave to the GC.
 */
class LayerThumbnails(private val maxSize: Int) {

    private class Entry(val version: Long, val generation: Long, val image: ImageBitmap, val exact: Boolean)

    private val content = HashMap<Long, Entry>()
    private val masks = HashMap<Long, Entry>()

    /** Previews made from pixels so far (not from the cache, not placeholders); for tests. */
    var made: Int = 0
        private set

    /** Preview of the layer's pixels. */
    fun content(layer: Layer): ImageBitmap = lookup(content, layer, layer.pixels)

    /** Preview of the layer's mask, or null when it has none. */
    fun mask(layer: Layer): ImageBitmap? {
        val m = layer.maskPixels ?: run { masks.remove(layer.id); return null }
        return lookup(masks, layer, m)
    }

    /** Drops entries of layers that no longer exist. */
    fun retain(ids: Set<Long>) {
        content.keys.retainAll(ids)
        masks.keys.retainAll(ids)
    }

    val size: Int get() = content.size + masks.size

    private fun lookup(cache: HashMap<Long, Entry>, layer: Layer, store: PixelStore): ImageBitmap {
        val version = layer.contentVersion
        val generation = layer.pixelGeneration
        val e = cache[layer.id]
        val current = e != null && e.version == version && e.generation == generation
        if (current && e!!.exact) return e.image
        val source = (store as? FlatPixels)?.bitmap
        if (source == null || source.isRecycled) {
            // Not flat: never materialize. The picture of the same key stays; else a placeholder
            // (exact for a blank store, which is transparent).
            if (current) return e!!.image
            val placeholder = placeholder(store)
            cache[layer.id] = Entry(version, generation, placeholder, exact = store is BlankPixels)
            return placeholder
        }
        val image = downscale(source, maxSize).asImageBitmap()
        made++
        cache[layer.id] = Entry(version, generation, image, exact = true)
        return image
    }

    /** A transparent picture of the thumbnail's size for [store]. */
    private fun placeholder(store: PixelStore): ImageBitmap {
        val (w, h) = fitSize(store.width, store.height, maxSize)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    companion object {
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
