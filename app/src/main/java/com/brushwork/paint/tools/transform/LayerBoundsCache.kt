package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.model.Layer
import com.brushwork.paint.snap.DetectedLine
import com.brushwork.paint.snap.LineDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tight content bounds (non-transparent pixels) of layers, for smart guides; an enabled layer
 * mask trims them to where the mask shows something. With [detectLines], also the straight
 * horizontal / vertical lines drawn in them ([LineDetector], looked for inside the bounds).
 * Cached per layer by its content version (and bitmap / mask), computed lazily: tiny layers
 * right away, others one after another on a background thread (the main thread only reads the
 * cache). Bounds of a small layer are known at once while its lines may still be found in the
 * background ([SYNC_PIXELS] / [LINES_SYNC_PIXELS]). A result is dropped if the layer changed
 * while it was being scanned. Everything here is called on the main thread; [onUpdated] runs
 * there whenever new bounds or lines arrived.
 */
class LayerBoundsCache(
    private val scope: CoroutineScope,
    /** Also find the straight horizontal / vertical lines drawn in each layer ([lines]). */
    private val detectLines: Boolean = false,
    private val onUpdated: () -> Unit,
) {

    /** What is known of a layer's content [version]; [lines] null = still to be found. */
    private class Entry(val version: Long, val bitmapId: Int, val maskId: Int, val width: Int, val height: Int, val bounds: Rect?, val lines: List<DetectedLine>?) {
        fun matches(layer: Layer): Boolean {
            val b = layer.bitmap
            return version == layer.contentVersion && bitmapId == System.identityHashCode(b) && maskId == maskIdOf(layer) &&
                width == b.width && height == b.height
        }
    }

    private val entries = HashMap<Long, Entry>()
    private val queue = ArrayDeque<Layer>()
    private var job: Job? = null

    /** True while layers are waiting to be scanned. */
    val isBusy: Boolean get() = job != null

    /** The entry of [layer]'s current content, if any. */
    private fun current(layer: Layer): Entry? = entries[layer.id]?.takeIf { it.matches(layer) }

    /** True when [layer]'s bounds (and lines, when they are looked for) are known for its current content. */
    fun isKnown(layer: Layer): Boolean {
        val e = current(layer) ?: return false
        return !detectLines || e.lines != null
    }

    /** Content bounds of [layer] (document px), or null when it is empty or not known (yet). */
    fun bounds(layer: Layer): Rect? = current(layer)?.bounds?.let { Rect(it) }

    /**
     * Straight horizontal / vertical lines drawn in [layer] (document px; only with
     * detectLines), or empty when it has none or they are not known (yet).
     */
    fun lines(layer: Layer): List<DetectedLine> = current(layer)?.lines ?: emptyList()

    /**
     * Makes sure the bounds (and lines) of [layers] get known: stale ones are scanned (tiny ones
     * now, the others in the background). Entries of layers not in [all] (deleted) are forgotten.
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
            if (current(layer) == null && bmp.width.toLong() * bmp.height <= SYNC_PIXELS) {
                // Bounds now; lines too when the content is tiny, else in the background.
                val mask = activeMask(layer)
                val r = scanBounds(bmp, mask) { false } ?: continue
                val lines = when {
                    !detectLines || r.bounds == null -> emptyList()
                    r.bounds.width().toLong() * r.bounds.height() <= LINES_SYNC_PIXELS -> scanLines(bmp, r.bounds) { false }
                    else -> null
                }
                store(layer, layer.contentVersion, bmp, mask, r.bounds, lines)
                changed = true
                if (lines == null) queue.addLast(layer)
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
                    val mask = activeMask(layer)
                    val version = layer.contentVersion
                    if (bmp.isRecycled) continue
                    // Bounds already known (a small layer): only its lines are missing.
                    val known = current(layer)
                    val scan = withContext(Dispatchers.Default) {
                        val bounds = if (known != null) Scan(known.bounds) else scanBounds(bmp, mask) { !isActive }
                        if (bounds == null || !detectLines || bounds.bounds == null) bounds
                        else scanLines(bmp, bounds.bounds) { !isActive }?.let { Scan(bounds.bounds, it) }
                    } ?: continue
                    // Changed (or replaced) while it was being scanned: scan it again when asked.
                    if (layer.contentVersion != version || layer.bitmap !== bmp || activeMask(layer) !== mask) continue
                    store(layer, version, bmp, mask, scan.bounds, if (detectLines) scan.lines ?: emptyList() else emptyList())
                    onUpdated()
                }
            } finally {
                if (job === me) job = null
            }
        }
    }

    private class Scan(val bounds: Rect?, val lines: List<DetectedLine>? = null)

    /**
     * Content bounds of [bmp], trimmed by [mask] (the layer's enabled mask, if any) to where it
     * shows something. Null when a bitmap could not be read (recycled meanwhile) or the scan was
     * cancelled.
     */
    private fun scanBounds(bmp: Bitmap, mask: Bitmap?, cancelled: () -> Boolean): Scan? = guarded(cancelled) {
        var r = ContentBounds.of(bmp, cancelled = cancelled)
        if (r != null && mask != null && mask.width == bmp.width && mask.height == bmp.height) {
            r = ContentBounds.of(mask, HIDDEN, region = r, cancelled = cancelled)
        }
        Scan(r)
    }

    /** Lines drawn in [bmp] inside [bounds], or null when it could not be read or was cancelled. */
    private fun scanLines(bmp: Bitmap, bounds: Rect, cancelled: () -> Boolean): List<DetectedLine>? = guarded(cancelled) {
        LineDetector.detect(bmp, bounds, cancelled)
    }

    private inline fun <T : Any> guarded(noinline cancelled: () -> Boolean, block: () -> T): T? = try {
        val r = block()
        if (cancelled()) null else r
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        null // e.g. recycled while it was being read
    }

    private fun store(layer: Layer, version: Long, bmp: Bitmap, mask: Bitmap?, bounds: Rect?, lines: List<DetectedLine>?) {
        val maskId = mask?.let { System.identityHashCode(it) } ?: 0
        entries[layer.id] = Entry(version, System.identityHashCode(bmp), maskId, bmp.width, bmp.height, bounds, lines)
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
        /** Layers up to this many pixels get their bounds right away on the main thread (well under a millisecond). */
        const val SYNC_PIXELS = 1L shl 18

        /**
         * Content (bounds area) up to this many pixels gets its lines right away too (about a
         * millisecond on a phone); larger content is looked at in the background.
         */
        const val LINES_SYNC_PIXELS = 1L shl 14

        /** A mask pixel that hides the layer there. */
        private const val HIDDEN = 0xFF000000.toInt()

        /** The mask that applies to [layer] now (null when it has none or it is turned off). */
        private fun activeMask(layer: Layer): Bitmap? = layer.mask?.takeIf { layer.maskEnabled && !it.isRecycled }

        private fun maskIdOf(layer: Layer): Int = activeMask(layer)?.let { System.identityHashCode(it) } ?: 0
    }
}
