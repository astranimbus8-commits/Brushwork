package com.brushwork.paint.engine.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.engine.tiles.PixelPin
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * v1.10 A9 step 2 (design 2.0 P4, "a frame never blocks on I/O"): the display tiles whose source
 * tiles are not resident (PACKED or SWAPPED tiles of parked layers and masks) are not composited in
 * the frame. Such a tile is queued as **waiting** and rendered incrementally instead:
 *
 * - Its layers are composited bottom-up, in chunks, into a per-tile accumulator (a bitmap of the
 *   display tile's size, `Compositor.drawSplit` onto it: `[0, k)` then `[k, n)` onto one target is
 *   the full draw bit for bit, so the finished accumulator is exactly what a plain render gives).
 * - A chunk is a run of layers between two cuts the compositor accepts ([cutAt]) whose
 *   non-resident tiles inside the tile's dirty part hold about [chunkBytes]; only the current
 *   chunk's layers are pinned (`Layer.pin()`: a "requested" pin, so neither park nor swap-out takes
 *   them before the chunk is drawn), and their tiles are read off main in unit order (bottom-up)
 *   by [worker] with `Tile.bitmapForRead()`, never made resident. The read copies are lent to
 *   their tiles while the chunk composites on main (`BandAssembler.lend`) and dropped right
 *   after (a lent copy is left to the GC, since a reader off main may hold it; a copy never lent
 *   is recycled; a tile's own bitmap, when it became resident meanwhile, is never touched), so a
 *   waiting tile completes even when its sources together exceed the tile RAM.
 * - Until it completes, the display tile keeps its previous content (stale after an invalidation,
 *   blank when never shown) and is never presented as exact ([DisplayTiles.isExact] is false); the
 *   finished accumulator replaces the dirty part in one draw, and [DisplayTiles.onWaitingDone]
 *   asks for a frame.
 * - An invalidation of a waiting tile cancels its job ([cancel]): the part is dirty again and is
 *   queued afresh by the next update.
 * - With the below projection (X4, `DisplayTiles.BelowRenderer.prepare`) a part reads only what
 *   its render reads: once the projection's cache tile is built, the job starts at the split from
 *   a copy of that cache ([queue] `start`, `base`); before, the job composites the units below the
 *   split across the whole tile, hands that to the projection as the tile's cache at the split
 *   ([queue] `cut`, `onCut`) and goes on above it inside the dirty part only.
 *
 * Main thread, like [DisplayTiles]; only [worker] reads (it never frees, recycles or changes a
 * tile's state: `Tile.kt` rule 1). At CLASSIC nothing is ever parked, so [needsReads] is false and
 * every tile renders at once as in v1.9.
 */
internal class WaitingTiles(
    private val tiles: DisplayTiles,
    private val worker: Executor = defaultWorker,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    /** The read copies one chunk may hold (bytes). */
    var chunkBytes: Long = CHUNK_BYTES

    private class Job(
        val index: Int,
        val tr: Rect,
        val d: Rect,
        val compositor: Compositor,
        start: Int,
        var cut: Int,
        val onCut: ((Rect, Bitmap) -> Unit)?,
    ) {
        var next = start
        var acc: Bitmap? = null
        var canvas: Canvas? = null
        var target: CompositeTarget? = null
        var cancelled = false

        /** The chunk could not be cut where it was ([cutAt] disagreed with the compositor): one chunk to the end. */
        var whole = false

        /** The part the chunk starting at [from] composites: the whole tile below [cut] (a projection's cache tile), else [d]. */
        fun regionFor(from: Int): Rect = if (from < cut) tr else d
    }

    private val jobs = arrayOfNulls<Job>(tiles.tileCount)
    private var count = 0

    /** Chunks composited so far (tests). */
    var chunks: Long = 0L
        private set

    /** Tiles read off main so far (tests). */
    var reads: Long = 0L
        private set

    /** The most bytes of read copies one chunk held at once (tests: a chunk stays near [chunkBytes]). */
    var peakChunkBytes: Long = 0L
        private set

    /** True while some display tile waits. */
    val pending: Boolean get() = count > 0

    fun isWaiting(index: Int): Boolean = jobs.getOrNull(index) != null

    /**
     * Queues the dirty part [d] of tile [index] (document rect [tr]) as waiting and starts its first
     * chunk. With [base] (a projection's built cache tile: units `[0, start)` over the whole tile)
     * the job starts at [start] from a copy of [base] inside [d], taken now (the cache may be
     * rebuilt or freed before the job completes). With [cut] (> 0, a split the compositor accepts)
     * the units below [cut] are composited across the whole tile and [onCut] gets the tile's rect
     * and that accumulator (units `[0, cut)`, valid during the call) before the units above.
     */
    fun queue(
        index: Int,
        tr: Rect,
        d: Rect,
        compositor: Compositor,
        start: Int = 0,
        base: Bitmap? = null,
        cut: Int = -1,
        onCut: ((Rect, Bitmap) -> Unit)? = null,
    ) {
        cancel(index)
        val from = if (base != null && start > 0 && !base.isRecycled) start else 0
        val job = Job(index, Rect(tr), Rect(d), compositor, from, if (from == 0) cut else -1, onCut)
        jobs[index] = job
        count++
        if (base != null && from > 0) {
            val c = accCanvas(job)
            val save = c.save()
            c.translate(-job.tr.left.toFloat(), -job.tr.top.toFloat())
            c.clipRect(job.d)
            c.drawBitmap(base, job.tr.left.toFloat(), job.tr.top.toFloat(), ProxyTiles.copyPaint)
            c.restoreToCount(save)
        }
        step(job)
    }

    /** Cancels tile [index]'s job; returns its dirty part (a copy) or null when it was not waiting. */
    fun cancel(index: Int): Rect? {
        val job = jobs.getOrNull(index) ?: return null
        drop(job)
        return Rect(job.d)
    }

    /** Cancels every job (the tiles are freed or replaced). */
    fun cancelAll() {
        for (j in jobs) if (j != null) drop(j)
    }

    private fun drop(job: Job) {
        job.cancelled = true
        if (jobs[job.index] === job) {
            jobs[job.index] = null
            count--
        }
        job.acc?.recycle()
        job.acc = null
        job.canvas = null
        job.target = null
    }

    // ------------------------------------------------------------------ the chunks

    /** Composites the job's chunks that need no read at once, and starts the reads of the next one. */
    private fun step(job: Job) {
        while (!job.cancelled) {
            val doc = job.compositor.document
            val n = doc.layers.size
            if (job.next >= n) {
                finish(job)
                return
            }
            val from = job.next
            val region = job.regionFor(from)
            val until = if (job.whole) n else chunkEnd(doc, from, region, if (from < job.cut) job.cut else n)
            val need = ArrayList<Tile>()
            val pins = ArrayList<PixelPin>()
            val seen = IdentityHashMap<Tile, Boolean>()
            for (i in from until until) {
                val l = doc.layers[i]
                if (l.isFolder || !l.visible || l.opacity <= 0f) continue
                val before = need.size
                collect(l.pixels, region, need, seen)
                if (l.maskEnabled) collect(l.maskPixels, region, need, seen)
                if (need.size > before) pins += l.pin()
            }
            if (need.isEmpty()) {
                if (!composite(job, from, until)) return
                continue
            }
            worker.execute {
                val read = ArrayList<Read>(need.size)
                var failure: Throwable? = null
                for (t in need) {
                    try {
                        val (b, own) = BandAssembler.readOff(t)
                        read += Read(t, b, own)
                    } catch (e: Throwable) {
                        failure = e
                        break
                    }
                }
                main.post { readDone(job, from, until, read, pins, failure) }
            }
            return
        }
    }

    /**
     * One tile read by [worker]: [bitmap] is the reader's [own] copy, or the bitmap the tile held
     * at the read (resident meanwhile, or lent by another job): that one is never recycled here.
     */
    private class Read(val tile: Tile, val bitmap: Bitmap, val own: Boolean) {
        var lent = false
    }

    private fun readDone(job: Job, from: Int, until: Int, read: List<Read>, pins: List<PixelPin>, failure: Throwable?) {
        reads += read.size
        var held = 0L
        for (r in read) held += r.tile.bytes
        if (held > peakChunkBytes) peakChunkBytes = held
        try {
            if (job.cancelled) return
            if (failure != null) {
                // A read failed (an I/O error in the swap): the tile keeps its stale content and
                // stays inexact; the next invalidation tries again.
                Log.w(TAG, "a waiting tile could not read its sources", failure)
                drop(job)
                return
            }
            try {
                for (r in read) r.lent = BandAssembler.lend(r.tile, r.bitmap)
                if (!composite(job, from, until)) return
            } finally {
                for (r in read) if (r.lent) BandAssembler.unlend(r.tile, r.bitmap)
            }
        } finally {
            // Only an own copy that was never lent is recycled: a lent copy may have been handed
            // to a reader off main by `bitmapForRead` meanwhile (the GC frees it), and a bitmap
            // that is not the reader's own is a tile's.
            for (r in read) if (r.own && !r.lent && !r.bitmap.isRecycled) r.bitmap.recycle()
            for (p in pins) p.close()
        }
        step(job)
    }

    /** The job's accumulator canvas (the accumulator made on first use). */
    private fun accCanvas(job: Job): Canvas {
        job.canvas?.let { return it }
        val acc = Bitmap.createBitmap(job.tr.width(), job.tr.height(), Bitmap.Config.ARGB_8888)
        job.acc = acc
        job.target = CompositeTarget.displayTile(acc, job.tr.left, job.tr.top)
        return Canvas(acc).also { job.canvas = it }
    }

    /** Composites layers [from, until) onto the job's accumulator; false when the job ended. */
    private fun composite(job: Job, from: Int, until: Int): Boolean {
        val c = accCanvas(job)
        val acc = job.acc!!
        val region = job.regionFor(from)
        val save = c.save()
        var refused = false
        try {
            c.translate(-job.tr.left.toFloat(), -job.tr.top.toFloat())
            c.clipRect(region)
            if (from == 0) c.drawColor(0, PorterDuff.Mode.CLEAR)
            job.compositor.drawSplit(c, region, job.target, from, until)
        } catch (e: IllegalArgumentException) {
            refused = true
        } finally {
            c.restoreToCount(save)
        }
        if (refused) {
            // A cut the compositor refuses: start again with one chunk to the end.
            if (job.whole) {
                drop(job)
                return false
            }
            job.whole = true
            job.next = 0
            job.cut = -1
            acc.eraseColor(0)
            return true
        }
        chunks++
        job.next = until
        if (until == job.cut) job.onCut?.invoke(job.tr, acc)
        return true
    }

    private fun finish(job: Job) {
        val acc = job.acc
        jobs[job.index] = null
        count--
        job.cancelled = true
        if (acc != null) tiles.completeWaiting(job.index, job.tr, job.d, acc)
        acc?.recycle()
        job.acc = null
        tiles.onWaitingDone?.invoke()
    }

    /**
     * The end of the chunk starting at [from]: the first cut after [from] where the chunk's
     * non-resident tiles inside [d] reach [chunkBytes], else [limit] (the layer count, or the
     * job's projection split).
     */
    private fun chunkEnd(doc: Document, from: Int, d: Rect, limit: Int): Int {
        var bytes = 0L
        var k = from
        while (k < limit) {
            val l = doc.layers[k]
            if (!l.isFolder && l.visible && l.opacity > 0f) {
                bytes += readBytes(l.pixels, d)
                if (l.maskEnabled) bytes += readBytes(l.maskPixels, d)
            }
            k++
            if (bytes >= chunkBytes && k < limit && cutAt(doc, k)) return k
        }
        return limit
    }

    companion object {
        private const val TAG = "WaitingTiles"

        /** 32 source tiles of 256 KiB. */
        const val CHUNK_BYTES = 8L shl 20

        /** The worker of every display: one thread, reading tiles in the order they were queued. */
        val defaultWorker: Executor by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "bw-waiting-tiles").apply { isDaemon = true } }
        }

        /**
         * True when compositing document part [d] would read a tile that is not resident: some
         * layer shown there (of the flat indices `[from, until)`; default all) holds a PACKED or
         * SWAPPED tile (content or enabled mask) inside [d]. Always false at CLASSIC, where nothing
         * parks.
         */
        fun needsReads(compositor: Compositor, d: Rect, from: Int = 0, until: Int = Int.MAX_VALUE): Boolean {
            if (EngineConfig.level == EngineLevel.CLASSIC) return false
            val layers = compositor.document.layers
            for (i in max(from, 0) until min(until, layers.size)) {
                val l = layers[i]
                if (l.isFolder || !l.visible || l.opacity <= 0f) continue
                if (hasReads(l.pixels, d)) return true
                if (l.maskEnabled && hasReads(l.maskPixels, d)) return true
            }
            return false
        }

        /**
         * True when a chunk may end before layer [k]: the compositor's split rule
         * (`Compositor.isSplitBoundary`) and, with folders, every folder [k] is in draws its
         * children as is and [k] is not clipped (the `StrokeBelowCache.splitFor` rule).
         */
        fun cutAt(doc: Document, k: Int): Boolean {
            val layers = doc.layers
            val n = layers.size
            if (k <= 0 || k >= n) return true
            if (FolderComposite.isClipped(layers, k) && !layers[k].isAdjustmentLayer) return false
            if (!doc.hasFolders) return true
            return FolderComposite.drawnAsIs(layers, k) && !FolderComposite.isClipped(layers, k)
        }

        private inline fun forTiles(p: PixelStore?, d: Rect, block: (Tile) -> Unit) {
            if (p !is TiledPixels) return
            val l = max(d.left, 0)
            val t = max(d.top, 0)
            val r = min(d.right, p.width)
            val b = min(d.bottom, p.height)
            if (l >= r || t >= b) return
            for (row in t / TILE_SIZE..(b - 1) / TILE_SIZE) for (col in l / TILE_SIZE..(r - 1) / TILE_SIZE) {
                val tile = p.tileAt(col, row) ?: continue
                block(tile)
            }
        }

        private fun hasReads(p: PixelStore?, d: Rect): Boolean {
            forTiles(p, d) { if (!BandAssembler.isResident(it)) return true }
            return false
        }

        private fun readBytes(p: PixelStore?, d: Rect): Long {
            var bytes = 0L
            forTiles(p, d) { if (!BandAssembler.isResident(it)) bytes += it.bytes }
            return bytes
        }

        private fun collect(p: PixelStore?, d: Rect, out: MutableList<Tile>, seen: IdentityHashMap<Tile, Boolean>) {
            forTiles(p, d) { if (!BandAssembler.isResident(it) && seen.put(it, true) == null) out += it }
        }
    }
}
