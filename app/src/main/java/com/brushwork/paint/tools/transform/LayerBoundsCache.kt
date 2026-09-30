package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.model.Layer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tight content bounds (non-transparent pixels) of layers, for smart guides. Cached per layer
 * by its content version (and bitmap), computed lazily: tiny layers right away, others one after
 * another on a background thread (the main thread only reads the cache). A result is dropped if
 * the layer changed while it was being scanned. Everything here is called on the main thread;
 * [onUpdated] runs there whenever new bounds arrived.
 */
class LayerBoundsCache(private val scope: CoroutineScope, private val onUpdated: () -> Unit) {

    private class Entry(val version: Long, val bitmapId: Int, val width: Int, val height: Int, val bounds: Rect?) {
        fun matches(layer: Layer): Boolean {
            val b = layer.bitmap
            return version == layer.contentVersion && bitmapId == System.identityHashCode(b) && width == b.width && height == b.height
        }
    }

    private val entries = HashMap<Long, Entry>()
    private val queue = ArrayDeque<Layer>()
    private var job: Job? = null

    /** True while layers are waiting to be scanned. */
    val isBusy: Boolean get() = job != null

    /** True when [layer]'s bounds are known for its current content. */
    fun isKnown(layer: Layer): Boolean = entries[layer.id]?.matches(layer) == true

    /** Content bounds of [layer] (document px), or null when it is empty or not known (yet). */
    fun bounds(layer: Layer): Rect? {
        val e = entries[layer.id] ?: return null
        return if (e.matches(layer)) e.bounds?.let { Rect(it) } else null
    }

    /**
     * Makes sure the bounds of [layers] get known: stale ones are scanned (tiny ones now, the
     * others in the background). Entries of layers not in [all] (deleted) are forgotten.
     */
    fun request(layers: List<Layer>, all: List<Layer> = layers) {
        if (entries.isNotEmpty()) {
            val alive = all.mapTo(HashSet()) { it.id }
            entries.keys.retainAll(alive)
        }
        var changed = false
        for (layer in layers) {
            if (isKnown(layer) || queue.any { it === layer }) continue
            val bmp = layer.bitmap
            if (bmp.isRecycled) continue
            if (bmp.width.toLong() * bmp.height <= SYNC_PIXELS) {
                val r = scan(bmp) { false } ?: continue
                store(layer, layer.contentVersion, bmp, r.bounds)
                changed = true
            } else {
                queue.addLast(layer)
            }
        }
        if (changed) onUpdated()
        if (queue.isNotEmpty() && job == null) startWorker()
    }

    private fun startWorker() {
        job = scope.launch(Dispatchers.Main) {
            val me = coroutineContext[Job]
            try {
                while (queue.isNotEmpty()) {
                    val layer = queue.removeFirst()
                    if (isKnown(layer)) continue
                    val bmp = layer.bitmap
                    val version = layer.contentVersion
                    if (bmp.isRecycled) continue
                    val r = withContext(Dispatchers.Default) { scan(bmp) { !isActive } } ?: continue
                    // Changed (or replaced) while it was being scanned: scan it again when asked.
                    if (layer.contentVersion != version || layer.bitmap !== bmp) continue
                    store(layer, version, bmp, r.bounds)
                    onUpdated()
                }
            } finally {
                if (job === me) job = null
            }
        }
    }

    private class Scan(val bounds: Rect?)

    /** Null when the bitmap could not be read (recycled meanwhile) or the scan was cancelled. */
    private fun scan(bmp: Bitmap, cancelled: () -> Boolean): Scan? = try {
        val r = ContentBounds.of(bmp, cancelled = cancelled)
        if (cancelled()) null else Scan(r)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        null // e.g. recycled while it was being read
    }

    private fun store(layer: Layer, version: Long, bmp: Bitmap, bounds: Rect?) {
        entries[layer.id] = Entry(version, System.identityHashCode(bmp), bmp.width, bmp.height, bounds)
    }

    /** Stops scanning (keeps what is known). */
    fun cancel() {
        job?.cancel()
        job = null
        queue.clear()
    }

    /** Forgets everything (the editor is closing). */
    fun clear() {
        cancel()
        entries.clear()
    }

    companion object {
        /** Layers up to this many pixels are scanned right away on the main thread (well under a millisecond). */
        const val SYNC_PIXELS = 1L shl 18
    }
}
