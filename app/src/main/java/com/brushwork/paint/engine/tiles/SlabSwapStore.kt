package com.brushwork.paint.engine.tiles

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.NativeWatch
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.budget.StorageProbe
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.util.BitSet
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

/**
 * v1.10 (A10, design 2.8, J9, J10, I34): the session's swap file at FULL.
 *
 * **Place.** `noBackupFilesDir/swap/<session UUID>/slab-NNN.bin` ([dir]): never `cacheDir` (the
 * system may clear it while the editor runs) and never a project folder. The store joins
 * [SwapJanitor.live] before it makes [dir], so the janitor's purge at app start never deletes it.
 *
 * **Layout.** Slabs of [SLAB_BYTES] (64 MiB; tests may pass a smaller `slabBytes`) allocated in pages of [PAGE] (4 KiB): a tile's packed
 * bytes take the first run of free pages long enough in the lowest slab (a bit set per slab is its
 * free list), so freed pages and whole slabs are reused before a new slab file is made. Each
 * [SwapSlot] keeps the CRC-32 of the bytes it holds.
 *
 * **Bytes.** Deflate level 1, nowrap, on write only ([TileOps.pack]; a PACKED tile's bytes are
 * written as they are), checked against their CRC and inflated on read ([TileOps.inflate]).
 *
 * **Capacity (J10, UQ13).** [capOf]: `min(16 GiB, (free - 1 GiB) / 2)` from
 * [StorageProbe.freeBytes] of [dir], read at the start and again when [CAP_REFRESH_MS] (60 s) have
 * passed. [canWrite] is false once `used + bytes > swapCap` or free space is under [MIN_FREE]
 * (1 GiB); the residency then keeps parking in RAM.
 *
 * **Faults.** A failed write (an I/O error, a full disk) returns its pages, turns writes off for
 * the session ([writesOff]) and throws, so the tile stays resident. A failed read or a CRC
 * mismatch throws `IOException`; the caller restores the layer from its last save (design 2.8).
 *
 * **Slow reads (design 2.10).** Every read is timed; when the p95 of the last 50 exceeds 40 ms,
 * [slowReads] turns on and the installed store records `slowSwapReads` (TILES at the next open).
 *
 * **Read-ahead (step 8).** [readPacked] gives [SwapReadAhead] a slot's checked packed bytes, which
 * it keeps beside the slot for the tiles of the layers next to the active one.
 *
 * **Threads.** [write] on the worker (it records the slot in `tile.slot` under the tile's lock and
 * leaves the bitmap: dropping it is main's commit, the `Tile.kt` rules); [read] on the worker or a
 * reader; [free] and [close] on main. [close] closes the slab files at once and deletes [dir] on its
 * own thread, then leaves [SwapJanitor.live]. Never resumed after a process death.
 */
class SlabSwapStore internal constructor(
    root: File,
    private val storage: StorageProbe,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val slabBytes: Long = SLAB_BYTES,
    private val onSlowReads: (() -> Unit)? = null,
) : SwapStore {
    private val pagesPerSlab = (slabBytes / PAGE).toInt()

    /** The last [SLOW_WINDOW] read times in ms, a ring ([reads] in all). */
    private val latencies = LongArray(SLOW_WINDOW)
    private var reads = 0

    /** True once the p95 of the last [SLOW_WINDOW] reads exceeded [SLOW_READ_MS] this session ([onSlowReads] was called). */
    @Volatile var slowReads = false
        private set

    /** The reads on demand ([read]) this session; a read-ahead's ([readPacked]) is none (tests). */
    internal val readCount: Int get() = synchronized(latencies) { reads }

    /** This session's directory name (a random UUID). */
    val sessionId: String = UUID.randomUUID().toString()

    /** `root/<sessionId>`. */
    val dir: File = File(root, sessionId)

    private val lock = Any()
    private val slabs = ArrayList<Slab>()
    private var used = 0L
    private var capAt = Long.MIN_VALUE

    @Volatile private var closed = false
    @Volatile private var free = 0L
    @Volatile private var cap = 0L

    /** True once a write failed (or the directory could not be made): no more writes this session. */
    @Volatile var writesOff = false
        private set

    /** Tests: called before each slab write with (slab, offset, length); a throw is a failed write. */
    @Volatile internal var writeFault: ((Int, Long, Int) -> Unit)? = null

    /** Tests: the thread deleting [dir] after [close], or null before. */
    @Volatile internal var closer: Thread? = null
        private set

    init {
        SwapJanitor.live += sessionId
        if (!dir.mkdirs() && !dir.isDirectory) {
            writesOff = true
            Log.w(TAG, "swap directory not made; no swap this session")
        }
        refreshCap(force = true)
    }

    override val capacityBytes: Long
        get() {
            refreshCap()
            return cap
        }

    override val usedBytes: Long get() = synchronized(lock) { used }

    /** The free bytes of the last reading. */
    val freeBytes: Long get() = free

    /** How many slab files exist. */
    val slabCount: Int get() = synchronized(lock) { slabs.size }

    override fun canWrite(bytes: Long): Boolean {
        if (closed || writesOff) return false
        refreshCap()
        return free >= MIN_FREE && usedBytes + pages(bytes) * PAGE <= cap
    }

    override fun write(tile: Tile) {
        if (closed) throw IOException("swap store closed")
        if (writesOff) throw IOException("swap writes are off for this session")
        if (synchronized(tile.lock) { tile.slot } != null) return
        val bytes = synchronized(tile.lock) { tile.packed } ?: TileOps.pack(tile.bitmapForRead())
        if (!canWrite(bytes.size.toLong())) throw IOException("swap is full (${usedBytes shr 20} of ${cap shr 20} MiB, ${free shr 20} MiB free)")
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        val n = pages(bytes.size.toLong()).toInt()
        val (slab, page) = allocate(n)
        val offset = page.toLong() * PAGE
        try {
            writeFault?.invoke(slab.index, offset, bytes.size)
            slab.write(offset, bytes)
        } catch (e: IOException) {
            release(slab.index, page, n)
            turnWritesOff(e)
            throw e
        }
        synchronized(tile.lock) { tile.slot = SwapSlot(slab.index, offset, bytes.size, crc) }
    }

    @Throws(IOException::class)
    override fun read(tile: Tile): Bitmap {
        val t0 = clock()
        try {
            return readSlot(tile)
        } finally {
            recordRead(clock() - t0)
        }
    }

    /**
     * Slow swap reads in use (design 2.10): every read's time joins a ring of the last
     * [SLOW_WINDOW] (50); once it is full and its p95 (nearest rank: the 48th of 50) exceeds
     * [SLOW_READ_MS] (40 ms), [onSlowReads] runs once for the session (the next sessions run at
     * TILES).
     */
    private fun recordRead(ms: Long) {
        val fire = synchronized(latencies) {
            latencies[reads % SLOW_WINDOW] = ms
            reads++
            if (slowReads || reads < SLOW_WINDOW) return@synchronized false
            val p95 = latencies.sortedArray()[P95_RANK - 1]
            if (p95 > SLOW_READ_MS) slowReads = true
            slowReads
        }
        if (fire) {
            try {
                onSlowReads?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "slow swap reads not recorded", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun readSlot(tile: Tile): Bitmap {
        val slot = synchronized(tile.lock) { tile.slot } ?: throw IOException("tile ${tile.w}x${tile.h} is not swapped")
        val bytes = readPacked(slot)
        return try {
            TileOps.inflate(bytes, tile.w, tile.h)
        } catch (e: Exception) {
            throw IOException("slot $slot does not inflate", e)
        }
    }

    /**
     * The packed bytes [slot] holds, checked against its CRC and not inflated; `IOException` on
     * failure. The worker ([SwapReadAhead], A10 step 8) or a reader. Not timed for [slowReads] by
     * itself: the ring measures the reads someone waits on ([read]).
     */
    @Throws(IOException::class)
    internal fun readPacked(slot: SwapSlot): ByteArray {
        if (closed) throw IOException("swap store closed")
        val slab = synchronized(lock) { slabs.getOrNull(slot.slab) } ?: throw IOException("no slab ${slot.slab}")
        val bytes = slab.read(slot.offset, slot.length)
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        if (crc != slot.crc) throw IOException("CRC mismatch in slot $slot")
        return bytes
    }

    override fun free(slot: SwapSlot) {
        if (closed) return
        release(slot.slab, (slot.offset / PAGE).toInt(), pages(slot.length.toLong()).toInt())
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) {
            for (s in slabs) s.close()
            slabs.clear()
            used = 0L
        }
        closer = Thread({
            if (!dir.deleteRecursively()) Log.w(TAG, "swap directory not deleted; the janitor deletes it at the next start")
            SwapJanitor.live -= sessionId
        }, "swap-close").apply {
            isDaemon = true
            start()
        }
    }

    /** The lowest run of [n] free pages, in a new slab when none has one. */
    private fun allocate(n: Int): Pair<Slab, Int> = synchronized(lock) {
        for (s in slabs) {
            val page = s.run(n)
            if (page >= 0) return take(s, page, n)
        }
        val s = try {
            newSlab()
        } catch (e: IOException) {
            turnWritesOff(e)
            throw e
        }
        take(s, 0, n)
    }

    private fun take(s: Slab, page: Int, n: Int): Pair<Slab, Int> {
        s.pages.set(page, page + n)
        used += n.toLong() * PAGE
        return s to page
    }

    private fun release(slab: Int, page: Int, n: Int) = synchronized(lock) {
        val s = slabs.getOrNull(slab) ?: return@synchronized
        if (page < 0 || page + n > pagesPerSlab) return@synchronized
        val held = s.pages.get(page, page + n).cardinality()
        s.pages.clear(page, page + n)
        used -= held.toLong() * PAGE
    }

    @Throws(IOException::class)
    private fun newSlab(): Slab {
        if (closed) throw IOException("swap store closed")
        val index = slabs.size
        val file = File(dir, String.format(Locale.US, "slab-%03d.bin", index))
        val s = Slab(index, RandomAccessFile(file, "rw"), pagesPerSlab)
        slabs += s
        return s
    }

    private fun turnWritesOff(e: IOException) {
        if (!writesOff) Log.w(TAG, "swap write failed; no more swap writes this session", e)
        writesOff = true
    }

    private fun refreshCap(force: Boolean = false) {
        val now = clock()
        synchronized(lock) {
            if (!force && capAt != Long.MIN_VALUE && now - capAt < CAP_REFRESH_MS) return
            capAt = now
        }
        val f = try {
            storage.freeBytes(dir)
        } catch (e: Exception) {
            Log.w(TAG, "free space unknown; no swap writes until it is known", e)
            0L
        }
        free = f
        cap = capOf(f)
    }

    /**
     * One slab file and its used pages. Reads and writes seek and transfer under the slab's own lock
     * through a [RandomAccessFile], which, unlike a `FileChannel`, an interrupted thread does not
     * close for every other user.
     */
    private class Slab(val index: Int, private val file: RandomAccessFile, private val pagesPerSlab: Int) {
        val pages = BitSet(pagesPerSlab)

        /** The first page of the lowest run of [n] free pages, or -1. */
        fun run(n: Int): Int {
            var start = pages.nextClearBit(0)
            while (start + n <= pagesPerSlab) {
                val next = pages.nextSetBit(start)
                if (next < 0 || next >= start + n) return start
                start = pages.nextClearBit(next)
            }
            return -1
        }

        @Throws(IOException::class)
        fun write(offset: Long, bytes: ByteArray) = synchronized(this) {
            file.seek(offset)
            file.write(bytes)
        }

        @Throws(IOException::class)
        fun read(offset: Long, length: Int): ByteArray = synchronized(this) {
            if (offset + length > file.length()) throw IOException("a slot at $offset of $length bytes ends past the slab")
            val bytes = ByteArray(length)
            file.seek(offset)
            file.readFully(bytes)
            bytes
        }

        fun close() {
            synchronized(this) { runCatching { file.close() } }
        }
    }

    companion object {
        /** A slab file's size: 64 MiB. */
        const val SLAB_BYTES = 64L shl 20

        /** The allocation unit: 4 KiB. */
        const val PAGE = 4096L

        /** The largest swap: 16 GiB (J10). */
        const val MAX_CAP = 16L shl 30

        /** Writes stop under this much free space: 1 GiB (J10). */
        const val MIN_FREE = 1L shl 30

        /** The capacity is read again after 60 s. */
        const val CAP_REFRESH_MS = 60_000L

        /** Slow swap reads: the reads the p95 is taken over. */
        const val SLOW_WINDOW = 50

        /** Slow swap reads: the nearest rank of the p95 in [SLOW_WINDOW] reads (ceil(0.95 x 50)). */
        const val P95_RANK = 48

        /** Slow swap reads: a p95 above this many ms. */
        const val SLOW_READ_MS = 40L

        /** `swapCap = min(16 GiB, (free - 1 GiB) / 2)`, never negative (J10, UQ13). */
        fun capOf(free: Long): Long = minOf(MAX_CAP, ((free - MIN_FREE) / 2).coerceAtLeast(0L))

        /** Pages of [bytes], rounded up. */
        private fun pages(bytes: Long): Long = (bytes + PAGE - 1) / PAGE

        /**
         * Tests: under Robolectric (one JVM, many sessions) [installIfFull] installs nothing unless a
         * test sets this, so FULL sessions of the UI tests keep their own store or none.
         */
        @Volatile internal var installUnderRobolectric = false

        /**
         * The editor at FULL (design 2.8): installs a new store as `EngineConfig.swapStore` when the
         * session runs at FULL with none installed yet; the session's close (`EngineConfig.closeSession`)
         * closes it. Returns the store installed, or null. Main.
         */
        fun installIfFull(context: Context): SlabSwapStore? {
            if (EngineConfig.level != EngineLevel.FULL || EngineConfig.swapStore !== NoSwap) return null
            if (Build.FINGERPRINT == "robolectric" && !installUnderRobolectric) return null
            val app = context.applicationContext
            val store = SlabSwapStore(SwapJanitor.root(app), EngineConfig.storageProbe, onSlowReads = {
                Thread({ AutoCheckRunner.markSlowSwapReads(app) }, "swap-slow-reads").apply {
                    isDaemon = true
                    start()
                }
            })
            EngineConfig.swapStore = store
            return store
        }

        private const val TAG = "BrushworkEngine"
    }
}

/**
 * v1.10 (A10 step 8, cut 1; design 2.7, 2.8 "reads ... ahead", Q5): reads ahead, on A8's
 * [TileWorker], the SWAPPED tiles of the two layers next to the active one (the nearest pixel layer
 * above it and the nearest below it in `Document.layers`, folders skipped; content and mask) once
 * the editor has been idle for [IDLE_MS] (300 ms), so a switch to either finds their pixels without
 * a read of the swap file.
 *
 * **The copy.** A read-ahead copy is the slot's packed bytes ([SlabSwapStore.readPacked]: checked
 * against the slot's CRC, not inflated), kept in the tile's `packed` beside its slot and counted in
 * [PackedPool], at most [shareCap] (half the pool's cap: the other half stays for swap-out at TILES
 * or with a full swap file). The tile stays SWAPPED (its state reads the slot first) and the slot
 * stays the copy of record, so a copy is dropped at no cost and nothing is written again.
 * `Tile.bitmapForRead` takes the packed bytes before the slot, so the activation (`TileWorker.readIn`
 * at READ_URGENT), a waiting display tile, a save or an export inflates them with no swap read;
 * `TileOps.adoptRead` and a free let go of both, as for any tile.
 *
 * **Threads (the `Tile.kt` rules).** Main picks the tiles, leases each (one reference more) and
 * submits one job per tile at READ_AHEAD (after the urgent reads, before the writes and park cuts);
 * the worker reads the slot taken at the lease and posts the bytes; main keeps them, in that posted
 * task, only while the tile is still SWAPPED in that slot with no packed bytes, a store or an undo
 * step still holds it, the session's store and the neighbours are the same and the pool admits the
 * bytes; the lease goes either way. A read that fails (I/O, CRC) keeps nothing: the read on demand
 * meets the same fault and restores the layer (design 2.8).
 *
 * **When.** [changed] (the active layer, the layer list or an activation changed) starts a pass
 * [IDLE_MS] later. A pass waits [IDLE_MS] more while [Host.idle] is false (a gesture, a busy job, a
 * filter, a tool's pending work, an activation) or while the resident tiles exceed [tileRam] (P3
 * then swaps tiles out, and reads queued before its writes would delay that). While nothing changes
 * it looks again every [RECHECK_MS] (2 s), for tiles swapped out since. A pass drops the copies of
 * tiles no longer next to the active layer. LOW and CRITICAL memory pressure drop every copy (a
 * `MemoryGovernor` entry of priority 0, trimmed with the display caches, registered before the
 * first read is queued) and end the reads still queued, and nothing is read for [QUIET_MS] (60 s),
 * nor while `NativeWatch.refusingNewLayers` holds. A copy the pool refuses ([PackedPool.tryAdmit]:
 * the pool full, or the Java heap near its margin, which costs a collection on main) ends the
 * pass's queued reads and nothing is read for [REFUSED_MS] (10 s), so a pass asks the heap once,
 * not once per tile. [pause] (the editor in the background) and [stop] (the editor goes) drop every
 * copy and end the queued reads; after [pause], [changed] starts again.
 *
 * Only with a [SlabSwapStore] installed (FULL); with any other store a pass reads nothing. Main
 * thread throughout, except the jobs' reads.
 */
internal class SwapReadAhead(
    private val host: Host,
    /** P3's budget of resident tiles: a pass waits while the TILES ledger is over it. */
    private val tileRam: () -> Long = { EngineConfig.budget.tileRam() },
    /** The bytes the copies may hold in [PackedPool]: half its cap. */
    private val shareCap: () -> Long = { PackedPool.cap() / 2 },
) {
    /** What the read-ahead needs of the editor (main thread). */
    interface Host {
        /** The open document. */
        val document: Document

        /** True while nothing goes on: no gesture, busy job, filter, pending tool work or activation. */
        val idle: Boolean
    }

    /** A copy kept: its bytes (weakly: a tile freed or read in lets go of them) and their size. */
    private class Copy(val bytes: WeakReference<ByteArray>, val size: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { pass() }
    private var stopped = false

    /** The neighbours' stores of the last pass; other ones start a new [generation]. */
    private var neighbours: List<TiledPixels> = emptyList()

    /**
     * Bumped when the neighbours change, by a trim, a refused copy, [pause] and [stop]: a job of an
     * older one reads and keeps nothing (the worker reads it).
     */
    @Volatile private var generation = 0

    /** The copies kept, by tile. */
    private val copies = IdentityHashMap<Tile, Copy>()

    /** The tiles with a read in flight, and their slots' bytes. */
    private val pending = IdentityHashMap<Tile, Int>()

    /** No pass before this uptime (after a trim or a refused copy). */
    private var quietUntil = 0L

    /** The memory entry: made before the first read is queued, closed by [pause] and [stop]. */
    private var registration: MemoryGovernor.Registration? = null

    /**
     * The bytes of the copies kept: counted again by each pass, added to by each copy kept since (a
     * copy let go of meanwhile counts until the next pass). Any thread: the memory entry reads it.
     */
    @Volatile var heldBytes = 0L
        private set

    /** The tiles with a copy kept now (tests). */
    internal val copiedTiles: Int get() = copies.count { (t, c) -> ours(t, c) }

    /** Main: the active layer, the layer list or the activation changed; a pass runs [IDLE_MS] later. */
    fun changed() {
        if (stopped) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, IDLE_MS)
    }

    /** Main: the editor goes; every copy is dropped and nothing more is read. */
    fun stop() {
        if (stopped) return
        stopped = true
        pause()
    }

    /**
     * Main: the editor is in the background; every copy is dropped, the queued reads end and no pass
     * runs until the next [changed]. The memory entry goes too.
     */
    fun pause() {
        handler.removeCallbacks(tick)
        generation++
        dropAll()
        registration?.close()
        registration = null
    }

    /** Main: one pass ([IDLE_MS] after a change, or [RECHECK_MS] after the last one). */
    internal fun pass() {
        if (stopped) return
        handler.removeCallbacks(tick)
        val store = EngineConfig.swapStore as? SlabSwapStore ?: return
        val now = SystemClock.uptimeMillis()
        if (now < quietUntil) {
            handler.postDelayed(tick, quietUntil - now)
            return
        }
        if (!host.idle || NativeWatch.refusingNewLayers || EngineConfig.budget.ledger.of(LedgerCategory.TILES) > tileRam()) {
            handler.postDelayed(tick, IDLE_MS)
            return
        }
        val stores = neighbourStores()
        if (stores.size != neighbours.size || stores.indices.any { stores[it] !== neighbours[it] }) {
            generation++
            neighbours = stores
        }
        val wanted = Collections.newSetFromMap(IdentityHashMap<Tile, Boolean>())
        val order = ArrayList<Tile>()
        for (s in stores) for (t in s.tiles) if (t != null && t.solid == null && wanted.add(t)) order += t
        val it = copies.entries.iterator()
        while (it.hasNext()) {
            val (t, c) = it.next()
            if (t !in wanted) drop(t, c)
            if (t !in wanted || !ours(t, c)) it.remove()
        }
        count()
        var room = shareCap() - heldBytes - pending.values.sumOf { it.toLong() }
        val gen = generation
        for (t in order) {
            if (t.refs <= 0 || t.bitmap != null || t.packed != null || pending.containsKey(t) || TileWorker.busy(t)) continue
            val slot = t.slot ?: continue
            if (slot.length > room) break
            room -= slot.length
            read(t, slot, store, gen)
        }
        handler.postDelayed(tick, RECHECK_MS)
    }

    /** The content and mask stores (parked, not evicted) of the nearest pixel layer above the active one and the nearest below it. */
    private fun neighbourStores(): List<TiledPixels> {
        val doc = host.document
        val layers = doc.layers
        if (layers.isEmpty()) return emptyList()
        val active = doc.activeLayerIndex.coerceIn(0, layers.lastIndex)
        val out = ArrayList<TiledPixels>(4)
        fun add(l: Layer) {
            for (p in arrayOf(l.pixels, l.maskPixels)) {
                val s = p as? TiledPixels ?: continue
                if (s.evictedTo == null) out += s
            }
        }
        (active + 1 until layers.size).firstOrNull { !layers[it].isFolder }?.let { add(layers[it]) }
        (active - 1 downTo 0).firstOrNull { !layers[it].isFolder }?.let { add(layers[it]) }
        return out
    }

    /** Main: leases [t] and reads [slot] of [store] on the worker; the posted task keeps the bytes ([keep]) and releases the lease. */
    private fun read(t: Tile, slot: SwapSlot, store: SlabSwapStore, gen: Int) {
        // Before any read is queued, so a trim meanwhile ends the queued reads too.
        if (registration == null) registration = MemoryGovernor.register(MEMORY_NAME, MEMORY_PRIORITY, { heldBytes }) { p -> trim(p) }
        t.refs++
        pending[t] = slot.length
        TileWorker.submit(TileWorker.Priority.READ_AHEAD, { if (gen == generation) store.readPacked(slot) else null }) { r ->
            pending.remove(t)
            try {
                r.getOrNull()?.let { keep(t, slot, store, gen, it) }
            } finally {
                TileOps.release(t)
            }
        }
    }

    /**
     * Main, in the job's posted task: [bytes] read from [slot] become [t]'s copy when nothing changed
     * meanwhile and the pool admits them. A refusal of the pool ends the pass's queued reads and
     * quiets the read-ahead for [REFUSED_MS].
     */
    private fun keep(t: Tile, slot: SwapSlot, store: SlabSwapStore, gen: Int, bytes: ByteArray) {
        if (stopped || gen != generation || EngineConfig.swapStore !== store) return
        // Held besides the lease, still SWAPPED in that slot, nothing read in or kept meanwhile.
        if (t.refs <= 1 || t.bitmap != null || t.packed != null || t.slot !== slot) return
        val size = bytes.size.toLong()
        // heldBytes is at least what is kept (a copy let go of since the pass still counts): safe side.
        if (heldBytes + size > shareCap()) return
        if (!PackedPool.tryAdmit(size)) {
            generation++
            quietUntil = SystemClock.uptimeMillis() + REFUSED_MS
            return
        }
        val kept = synchronized(t.lock) {
            (t.bitmap == null && t.packed == null && t.slot === slot).also { if (it) t.packed = bytes }
        }
        if (!kept) {
            PackedPool.release(size)
            return
        }
        copies[t] = Copy(WeakReference(bytes), bytes.size)
        heldBytes += size
    }

    /** True while [c] is still [t]'s packed bytes beside its slot (not read in, freed or dropped). */
    private fun ours(t: Tile, c: Copy): Boolean {
        val b = c.bytes.get() ?: return false
        return t.packed === b && t.slot != null && t.bitmap == null
    }

    /** Counts [heldBytes] again, forgetting the copies that are no longer kept. */
    private fun count() {
        var n = 0L
        val it = copies.entries.iterator()
        while (it.hasNext()) {
            val (t, c) = it.next()
            if (ours(t, c)) n += c.size else it.remove()
        }
        heldBytes = n
    }

    /** Drops [t]'s copy [c] when it is still kept (the slot stays); the bytes it let go of. */
    private fun drop(t: Tile, c: Copy): Long {
        val b = c.bytes.get() ?: return 0L
        val dropped = synchronized(t.lock) {
            (t.packed === b && t.slot != null && t.bitmap == null).also { if (it) t.packed = null }
        }
        if (!dropped) return 0L
        PackedPool.release(c.size.toLong())
        return c.size.toLong()
    }

    /** Main: drops every copy; the bytes let go of. */
    private fun dropAll(): Long {
        var n = 0L
        for ((t, c) in copies) n += drop(t, c)
        copies.clear()
        heldBytes = 0L
        return n
    }

    /** The memory entry: LOW and CRITICAL drop every copy, end the queued reads and quiet the read-ahead for [QUIET_MS]. */
    private fun trim(p: MemoryPressure): Long {
        if (p != MemoryPressure.LOW && p != MemoryPressure.CRITICAL) return 0L
        val run = {
            quietUntil = SystemClock.uptimeMillis() + QUIET_MS
            generation++
            dropAll()
        }
        if (Looper.getMainLooper()?.isCurrentThread == true) return run()
        handler.post { run() }
        return 0L
    }

    companion object {
        /** A pass runs this long after the last change (and waits this long again while the editor is busy). */
        const val IDLE_MS = 300L

        /** While nothing changes, a pass looks again this often (tiles swapped out since). */
        const val RECHECK_MS = 2_000L

        /** After LOW or CRITICAL pressure, nothing is read ahead for this long. */
        const val QUIET_MS = 60_000L

        /** After the packed pool refused a copy, nothing is read ahead for this long. */
        const val REFUSED_MS = 10_000L

        /** Trimmed first, with the display caches: the copies are a cache of what the swap file holds. */
        private const val MEMORY_PRIORITY = 0

        private const val MEMORY_NAME = "read-ahead tiles"
    }
}
