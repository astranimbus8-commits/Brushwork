package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TiledPixels
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
 * horizontal / vertical lines drawn in them ([LineDetector], looked for inside the bounds, as
 * the mask shows them).
 * Cached per layer by its content version (and pixel generation / mask), computed lazily: tiny
 * layers right away, others one after another on a background thread. Bounds of a small layer
 * are known at once while its lines may still be found in the background ([SYNC_PIXELS] /
 * [LINES_SYNC_PIXELS]). A result is dropped if the layer changed while it was being scanned.
 * Everything here is called on the main thread; [onUpdated] runs there whenever new bounds or
 * lines arrived.
 *
 * v1.9 (A7b step 6, design 3.2 A7b, R9, risk H4): the background thread never reads a layer's own
 * pixels. Undo history trimmed meanwhile (`LayerTreeAction.dispose`) recycles the bitmaps of the
 * layers it removed, on the main thread, and a native read of a recycled bitmap can crash the app
 * where no exception is caught. So each background scan reads immutable copies of the layer and
 * its mask, taken on the main thread just before ([copyPixels]) and recycled once it ends (also
 * when it is cancelled). One layer is scanned at a time: at most one copy (and its mask's) exists.
 * A copy that cannot be made (out of memory) is like a scan that ran out of memory: nothing to
 * snap to in this content, not retried until it changes.
 *
 * v1.10 (A8, design 2.2, J5, I28): an entry is keyed by the layer's `contentVersion` and
 * `pixelGeneration` (and whether a mask applies), never by the identity of a bitmap, so a park, an
 * unpark or a materialization (the same pixels in another store) keeps what is known. Nothing here
 * materializes a layer or makes its tiles resident. A FLAT layer is read as in v1.9. A parked one
 * (tiled) larger than [SYNC_PIXELS] gets its bounds at once from its occupied tiles, refined by
 * reading only the tiles along the content's edges ([edgeScan], under a pin); a smaller one is read
 * from a full-size copy of its tiles taken on the main thread (the TRANSIENT bytes reserved first;
 * no room = nothing to snap to, as a v1.9 copy out of memory). Main never reads a slot here: a
 * layer whose content or applying mask holds a SWAPPED tile gets its bounds in the background, from
 * a `snapshot()` taken on main under a pin and read off main (edge tiles only), and the lines of
 * any parked layer are read there from a full-size copy of such a snapshot. A blank layer has
 * nothing to snap to; an evicted one (A11) is not looked at until it is reloaded.
 */
class LayerBoundsCache(
    private val scope: CoroutineScope,
    /** Also find the straight horizontal / vertical lines drawn in each layer ([lines]). */
    private val detectLines: Boolean = false,
    private val onUpdated: () -> Unit,
) {

    /** What is known of a layer's content [version] and pixel [generation]; [lines] null = still to be found. */
    private class Entry(val version: Long, val generation: Long, val masked: Boolean, val width: Int, val height: Int, val bounds: Rect?, val lines: List<DetectedLine>?) {
        fun matches(layer: Layer): Boolean {
            val p = layer.pixels
            return version == layer.contentVersion && generation == layer.pixelGeneration && masked == maskOn(layer) &&
                width == p.width && height == p.height
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
            if (isKnown(layer) || queue.any { it === layer } || evicted(layer)) continue
            if (requestOne(layer)) changed = true
        }
        if (changed) onUpdated()
        if (queue.isNotEmpty() && job == null) startWorker()
    }

    /** [request] for one stale [layer]: true when something new is known now (else it may be queued). */
    private fun requestOne(layer: Layer): Boolean {
        val p = layer.pixels
        if (p !is FlatPixels && p !is TiledPixels) {
            // v1.10 (A8): blank (never drawn in): nothing to snap to, known without materializing it.
            store(layer, null, emptyList())
            return true
        }
        if (p is FlatPixels && layer.bitmap.isRecycled) return false
        if (current(layer) != null || swapped(layer)) {
            // Bounds already known (a small layer): only its lines are missing. v1.10 (A8): a
            // store with SWAPPED tiles is read in the background only (main never reads a slot).
            queue.addLast(layer)
            return false
        }
        if (p.width.toLong() * p.height <= SYNC_PIXELS) return scanNow(layer)
        if (p is TiledPixels) {
            // v1.10 (A8): bounds now from the tiles, lines in the background.
            val m = layer.maskPixels?.takeIf { maskOn(layer) }
            val flatMask = if (m is FlatPixels) layer.mask?.takeIf { !it.isRecycled } else null
            val scan = layer.pin().use { tiledBounds(p, m?.takeIf { it !is FlatPixels }, flatMask) } ?: return false
            val lines = if (!detectLines || scan.bounds == null) emptyList<DetectedLine>() else null
            store(layer, scan.bounds, lines)
            if (lines == null) queue.addLast(layer)
            return true
        }
        queue.addLast(layer)
        return false
    }

    /**
     * Bounds of tiny [layer] now; lines too when the content is tiny, else in the background.
     * A FLAT store is read in place (as in v1.9), another one from a copy ([sourceOf]).
     */
    private fun scanNow(layer: Layer): Boolean {
        val src = sourceOf(layer, mask = false)
        val mask = if (src != null && maskOn(layer)) sourceOf(layer, mask = true) else null
        try {
            if (src == null || (maskOn(layer) && mask == null)) {
                // No room for a copy: nothing to snap to in this content.
                store(layer, null, emptyList())
                return true
            }
            val bmp = src.image
            val m = mask?.image?.takeIf { !it.isRecycled }
            val r = scanBounds(bmp, m) { false } ?: return false
            val lines = when {
                !detectLines || r.bounds == null -> emptyList()
                r.bounds.width().toLong() * r.bounds.height() <= LINES_SYNC_PIXELS -> scanLines(bmp, r.bounds, m) { false }
                else -> null
            }
            store(layer, r.bounds, lines)
            if (lines == null) queue.addLast(layer)
            return true
        } finally {
            src?.done()
            mask?.done()
        }
    }

    private fun startWorker() {
        job = scope.launch(Dispatchers.Main) {
            val me = coroutineContext[Job]
            try {
                while (queue.isNotEmpty()) {
                    val layer = queue.removeFirst()
                    if (isKnown(layer) || evicted(layer)) continue
                    val p = layer.pixels
                    if (p !is FlatPixels && p !is TiledPixels) continue
                    // v1.9 (H4): a recycled layer (removed meanwhile) is never read.
                    val own = if (p is FlatPixels) layer.bitmap else null
                    if (own != null && own.isRecycled) continue
                    val version = layer.contentVersion
                    val generation = layer.pixelGeneration
                    val masked = maskOn(layer)
                    // Bounds already known (a small or a parked layer): only its lines are missing.
                    val known = current(layer)
                    // v1.9 (H4): what the background reads, taken here on the main thread. v1.10
                    // (A8): a FLAT store as a copy, a parked one as a snapshot under a pin.
                    val plan = plan(layer)
                    if (plan == null) {
                        // Out of memory for the copy: nothing to snap to in this content.
                        store(layer, known?.bounds, emptyList())
                        onUpdated()
                        continue
                    }
                    val scan: Scan?
                    try {
                        scan = withContext(Dispatchers.Default) {
                            val c = plan.content
                            val m = plan.mask
                            val snap = c.store as? TiledPixels
                            val bounds = when {
                                known != null -> Scan(known.bounds)
                                // v1.10 (A8): a parked layer's bounds from the tiles along its edges.
                                snap != null -> tiledBounds(snap, m?.store, m?.flat?.image) { !isActive }
                                else -> c.image()?.let { scanBounds(it, m?.image()) { !isActive } }
                            }
                            if (bounds == null || !detectLines || bounds.bounds == null) {
                                bounds
                            } else {
                                val bmp = c.image()
                                if (bmp == null) Scan(bounds.bounds, emptyList())
                                else scanLines(bmp, bounds.bounds, m?.image()) { !isActive }?.let { Scan(bounds.bounds, it) }
                            }
                        }
                    } finally {
                        // withContext returns (or throws) only once its block has ended: nothing reads them now.
                        plan.done()
                    }
                    if (scan == null) continue
                    // Changed (replaced, or recycled with a removed layer) while it was being scanned:
                    // scan it again when asked. v1.10 (F0, C16): a replaced bitmap or mask bumped the
                    // generation; a mask turned on or off changes what applies. v1.10 (A8): parked or
                    // made flat again meanwhile is the same content (J5).
                    if (layer.contentVersion != version || layer.pixelGeneration != generation || own?.isRecycled == true ||
                        maskOn(layer) != masked
                    ) continue
                    store(layer, scan.bounds, if (detectLines) scan.lines ?: emptyList() else emptyList())
                    onUpdated()
                }
            } finally {
                if (job === me) job = null
            }
        }
    }

    private class Scan(val bounds: Rect?, val lines: List<DetectedLine>? = null)

    /**
     * What a scan reads: a FLAT store's own bitmap as [image] (main thread only, [copy] false), or a copy
     * recycled by [done], which also gives back the TRANSIENT bytes it [reserved] (v1.10, A8).
     */
    private class Source(val image: Bitmap, val copy: Boolean, val reserved: Long = 0L) {
        fun done() {
            if (!copy) return
            image.recycle()
            if (reserved > 0L) EngineConfig.budget.release(reserved, NativePurpose.TRANSIENT)
        }
    }

    /**
     * v1.10 (A8): [layer]'s content (or mask) for a scan on the main thread now: a FLAT store's own
     * bitmap, another store's full-size copy ([copyStore]); null when it has none or no room.
     */
    private fun sourceOf(layer: Layer, mask: Boolean): Source? {
        val p = (if (mask) layer.maskPixels else layer.pixels) ?: return null
        if (p !is FlatPixels) return copyStore(p)
        val own = (if (mask) layer.mask else layer.bitmap) ?: return null
        return Source(own, copy = false)
    }

    /**
     * v1.10 (A8): what a background scan reads of one store, taken on the main thread: a FLAT
     * store's copy ([flat], v1.9's H4 copy), or a parked store's `snapshot()` (or a blank store)
     * as [store], read only off main, its full-size copy ([image]) made there when the lines need
     * it, the TRANSIENT bytes [reserved] on main first. [done] (main) lets go of everything.
     */
    private class Part(val flat: Source?, val store: PixelStore?, private val reserved: Long) {
        private var copy: Bitmap? = null

        /**
         * Off main: the full-size pixels: the FLAT copy, or [store] read into a copy (made once;
         * a packed or SWAPPED tile is inflated or read back into it and stays as it was). Null
         * when none was reserved or there is no room.
         */
        fun image(): Bitmap? {
            flat?.let { return it.image }
            copy?.let { return it }
            val s = store ?: return null
            if (reserved == 0L) return null
            val b = try {
                BitmapUtils.createLayerBitmap(s.width, s.height)
            } catch (e: OutOfMemoryError) {
                return null
            }
            try {
                TileOps.readRegion(s, Rect(0, 0, s.width, s.height), b)
            } catch (e: Exception) {
                // A slot that could not be read back: nothing to read lines from.
                b.recycle()
                return null
            }
            copy = b
            return b
        }

        /** Main: recycles the copies, lets go of the snapshot and gives back the bytes reserved. */
        fun done() {
            flat?.done()
            copy?.recycle()
            copy = null
            (store as? TiledPixels)?.let { TileOps.releaseStore(it) }
            if (reserved > 0L) EngineConfig.budget.release(reserved, NativePurpose.TRANSIENT)
        }
    }

    /** v1.10 (A8): the [content] and applying [mask] a background scan reads; [pin] keeps the snapshots' tiles where they are. */
    private class Plan(val content: Part, val mask: Part?, private val pin: AutoCloseable?) {
        /** Main: lets go of both parts and the pin. */
        fun done() {
            content.done()
            mask?.done()
            pin?.close()
        }
    }

    /**
     * v1.9 (H4): what a background scan of [layer] reads, taken on the main thread (like every
     * change and recycling of a layer's bitmaps): immutable copies of a FLAT content and mask (when
     * one applies), or null when one could not be made (out of memory; nothing is left allocated
     * then). v1.10 (A8): a parked store is taken as a `snapshot()` under a pin (its tiles read
     * only off main: main never reads a slot here), a full-size copy of it reserved for the lines.
     */
    private fun plan(layer: Layer): Plan? {
        val full = detectLines || layer.pixels is FlatPixels
        val tiled = layer.pixels is TiledPixels || (maskOn(layer) && layer.maskPixels is TiledPixels)
        val pin = if (tiled) layer.pin() else null
        val content = partOf(layer, mask = false, full)
        if (content == null) {
            pin?.close()
            return null
        }
        val p = layer.maskPixels
        if (!maskOn(layer) || (p is FlatPixels && layer.mask?.isRecycled != false)) return Plan(content, null, pin)
        val mask = partOf(layer, mask = true, full)
        if (mask == null) {
            content.done()
            pin?.close()
            return null
        }
        return Plan(content, mask, pin)
    }

    /** v1.10 (A8): one [Part] of [plan] (main): a FLAT store's copy ([copyOf]), else its snapshot, [full] reserving its copy; null when no room. */
    private fun partOf(layer: Layer, mask: Boolean, full: Boolean): Part? {
        val p = (if (mask) layer.maskPixels else layer.pixels) ?: return null
        if (p is FlatPixels) return copyOf(layer, mask)?.let { Part(it, null, 0L) }
        if (p is TiledPixels && p.evictedTo != null) return null
        val bytes = if (full) p.width.toLong() * p.height * 4 else 0L
        if (bytes > 0L && !EngineConfig.budget.admitNative(bytes, NativePurpose.TRANSIENT)) return null
        return Part(null, if (p is TiledPixels) p.snapshot() else p, bytes)
    }

    /** v1.10 (A8): [layer]'s content, or the mask that applies, holds a SWAPPED tile (read off main only). */
    private fun swapped(layer: Layer): Boolean =
        hasSwapped(layer.pixels) || (maskOn(layer) && hasSwapped(layer.maskPixels))

    private fun hasSwapped(p: PixelStore?): Boolean =
        p is TiledPixels && p.tiles.any { it != null && it.state == TileState.SWAPPED }

    /** One of [copies]: [copyPixels] of a FLAT store's bitmap, else [copyStore]. */
    private fun copyOf(layer: Layer, mask: Boolean): Source? {
        val p = (if (mask) layer.maskPixels else layer.pixels) ?: return null
        if (p !is FlatPixels) return copyStore(p)
        val own = (if (mask) layer.mask else layer.bitmap) ?: return null
        val copy = try {
            copyPixels(own)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        return Source(copy, copy = true)
    }

    /**
     * v1.10 (A8): a full-size copy of [p] (a parked or blank store) read from its tiles without
     * materializing it or making them resident, its TRANSIENT bytes reserved first; null when the
     * budget or the heap has no room for it, or the store is evicted (A11). Main thread.
     */
    private fun copyStore(p: PixelStore): Source? {
        if (p is TiledPixels && p.evictedTo != null) return null
        val bytes = p.width.toLong() * p.height * 4
        val budget = EngineConfig.budget
        if (!budget.admitNative(bytes, NativePurpose.TRANSIENT)) return null
        val b = try {
            BitmapUtils.createLayerBitmap(p.width, p.height)
        } catch (e: OutOfMemoryError) {
            null
        }
        if (b == null) {
            budget.release(bytes, NativePurpose.TRANSIENT)
            return null
        }
        val source = Source(b, copy = true, reserved = bytes)
        try {
            TileOps.readRegion(p, Rect(0, 0, p.width, p.height), b)
        } catch (e: Throwable) {
            source.done()
            if (e is OutOfMemoryError || e is RuntimeException) return null
            throw e
        }
        return source
    }

    /**
     * How a layer's (or mask's) pixels are copied for the background (main thread; null or
     * [OutOfMemoryError] when there is no memory for it; replaceable in tests): an immutable copy
     * of the same size and config.
     */
    internal var copyPixels: (Bitmap) -> Bitmap? = { b -> b.copy(b.config ?: Bitmap.Config.ARGB_8888, false) }

    /**
     * Content bounds of [bmp], trimmed by [mask] (the layer's enabled mask, if any) to where it
     * shows something. Null when a bitmap could not be read (recycled meanwhile) or the scan was
     * cancelled.
     */
    private fun scanBounds(bmp: Bitmap, mask: Bitmap?, cancelled: () -> Boolean): Scan? = guarded(cancelled, { Scan(null) }) {
        var r = ContentBounds.of(bmp, cancelled = cancelled)
        if (r != null && mask != null && mask.width == bmp.width && mask.height == bmp.height) {
            r = ContentBounds.of(mask, HIDDEN, region = r, cancelled = cancelled)
        }
        Scan(r)
    }

    /**
     * v1.10 (A8, design 2.2): [scanBounds] of a parked layer's content [p] without materializing
     * it: the rect of its occupied tiles, refined by reading the tiles along the content's edges
     * ([edgeScan]), then trimmed the same way by the mask that applies: [flatMask] (a FLAT mask's
     * bitmap), or [mask] (a tiled or blank one, read the same way). A tile that is not resident
     * is read into a scratch tile (inflated or read back) and stays as it was. On main (under a
     * pin, only when no tile is SWAPPED) or off main (a snapshot under a pin; [flatMask] a copy).
     * Null when a tile could not be read or the scan was [cancelled].
     */
    private fun tiledBounds(p: TiledPixels, mask: PixelStore?, flatMask: Bitmap?, cancelled: () -> Boolean = { false }): Scan? =
        guarded(cancelled, { Scan(null) }) {
            val occupied = Rect()
            if (!p.occupiedBounds(occupied)) return@guarded Scan(null)
            val scratch = BitmapUtils.createLayerBitmap(TILE_SIZE, TILE_SIZE)
            try {
                var r = edgeScan(occupied) { cell ->
                    if (p.tileAt(cell.left / TILE_SIZE, cell.top / TILE_SIZE) == null) null else cellBounds(p, cell, scratch, null)
                }
                if (r != null && flatMask != null && flatMask.width == p.width && flatMask.height == p.height) {
                    r = edgeScan(r) { cell -> ContentBounds.of(flatMask, HIDDEN, region = cell) }
                } else if (r != null && mask != null && mask !is FlatPixels && mask.width == p.width && mask.height == p.height) {
                    r = edgeScan(r) { cell ->
                        // A transparent mask tile is not HIDDEN (as in the materialized mask).
                        if (mask is TiledPixels && mask.tileAt(cell.left / TILE_SIZE, cell.top / TILE_SIZE) == null) Rect(cell)
                        else cellBounds(mask, cell, scratch, HIDDEN)
                    }
                }
                Scan(r)
            } finally {
                scratch.recycle()
            }
        }

    /** v1.10 (A8): [ContentBounds.of] the pixels of [cell] (inside one tile) of [s], read into [scratch]. */
    private fun cellBounds(s: PixelStore, cell: Rect, scratch: Bitmap, emptyColor: Int?): Rect? {
        TileOps.readRegion(s, cell, scratch)
        return ContentBounds.of(scratch, emptyColor, region = Rect(0, 0, cell.width(), cell.height()))?.apply { offset(cell.left, cell.top) }
    }

    /**
     * v1.10 (A8): the tight bounds, inside [area], of what [inCell] finds in the cells of the
     * [TILE_SIZE] grid (each clipped to [area]; each read once). Cell columns are read from the
     * left until one holds something, then from the right, and cell rows from the top and from the
     * bottom, so mostly the cells along the edges of the content are read: the leftmost pixel lies
     * in the first column that holds one, and so on. Null when no cell holds anything.
     */
    private fun edgeScan(area: Rect, inCell: (Rect) -> Rect?): Rect? {
        if (area.isEmpty) return null
        val c0 = area.left / TILE_SIZE
        val c1 = (area.right - 1) / TILE_SIZE
        val r0 = area.top / TILE_SIZE
        val r1 = (area.bottom - 1) / TILE_SIZE
        val cols = c1 - c0 + 1
        val read = BooleanArray(cols * (r1 - r0 + 1))
        val found = arrayOfNulls<Rect>(read.size)
        fun cell(col: Int, row: Int): Rect? {
            val i = (row - r0) * cols + (col - c0)
            if (!read[i]) {
                val r = Rect(col * TILE_SIZE, row * TILE_SIZE, (col + 1) * TILE_SIZE, (row + 1) * TILE_SIZE)
                found[i] = if (r.intersect(area)) inCell(r) else null
                read[i] = true
            }
            return found[i]
        }
        var left = Int.MAX_VALUE
        for (col in c0..c1) {
            for (row in r0..r1) cell(col, row)?.let { if (it.left < left) left = it.left }
            if (left != Int.MAX_VALUE) break
        }
        if (left == Int.MAX_VALUE) return null
        var right = Int.MIN_VALUE
        for (col in c1 downTo c0) {
            for (row in r0..r1) cell(col, row)?.let { if (it.right > right) right = it.right }
            if (right != Int.MIN_VALUE) break
        }
        var top = Int.MAX_VALUE
        for (row in r0..r1) {
            for (col in c0..c1) cell(col, row)?.let { if (it.top < top) top = it.top }
            if (top != Int.MAX_VALUE) break
        }
        var bottom = Int.MIN_VALUE
        for (row in r1 downTo r0) {
            for (col in c0..c1) cell(col, row)?.let { if (it.bottom > bottom) bottom = it.bottom }
            if (bottom != Int.MIN_VALUE) break
        }
        return Rect(left, top, right, bottom)
    }

    /**
     * Lines drawn in [bmp] inside [bounds] where [mask] (if any) shows them, or null when it could
     * not be read or was cancelled.
     */
    private fun scanLines(bmp: Bitmap, bounds: Rect, mask: Bitmap?, cancelled: () -> Boolean): List<DetectedLine>? =
        guarded(cancelled, { emptyList() }) { findLines(bmp, bounds, mask, cancelled) }

    /**
     * How lines are found (runs on a background thread, given the full-size copies of the layer
     * and its mask, never the layer's own bitmaps; replaceable in tests).
     */
    internal var findLines: (Bitmap, Rect, Bitmap?, () -> Boolean) -> List<DetectedLine> = { b, r, m, c -> LineDetector.detect(b, r, m, c) }

    /**
     * [block]'s result, or null when it was cancelled or failed (e.g. the bitmap was recycled
     * while it was being read: asked again later). Out of memory, it gives up on this content
     * with [onOutOfMemory] (nothing to snap to) instead of crashing the app from a background
     * thread or trying again on every gesture.
     */
    private inline fun <T : Any> guarded(noinline cancelled: () -> Boolean, onOutOfMemory: () -> T, block: () -> T): T? = try {
        val r = block()
        if (cancelled()) null else r
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        if (cancelled()) null else onOutOfMemory()
    } catch (e: Exception) {
        null // e.g. recycled while it was being read; v1.10 (A8): a slot that could not be read back
    }

    /** Remembers [bounds] and [lines] for [layer]'s current content (checked unchanged by the caller). */
    private fun store(layer: Layer, bounds: Rect?, lines: List<DetectedLine>?) {
        val p = layer.pixels
        entries[layer.id] = Entry(layer.contentVersion, layer.pixelGeneration, maskOn(layer), p.width, p.height, bounds, lines)
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

        /** v1.10 (A8): a mask applies to [layer] now (it has one and it is turned on); never materializes. */
        private fun maskOn(layer: Layer): Boolean = layer.hasMask && layer.maskEnabled

        /** v1.10 (A8): [layer]'s content or applying mask is evicted (A11 reloads it before it is read). */
        private fun evicted(layer: Layer): Boolean =
            (layer.pixels as? TiledPixels)?.evictedTo != null || (maskOn(layer) && (layer.maskPixels as? TiledPixels)?.evictedTo != null)
    }
}
