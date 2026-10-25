package com.brushwork.paint.engine.tiles

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.StorageProbe
import com.brushwork.paint.engine.check.AutoCheckRunner
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.BitSet
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
        if (closed) throw IOException("swap store closed")
        val slab = synchronized(lock) { slabs.getOrNull(slot.slab) } ?: throw IOException("no slab ${slot.slab}")
        val bytes = slab.read(slot.offset, slot.length)
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        if (crc != slot.crc) throw IOException("CRC mismatch in slot $slot")
        return try {
            TileOps.inflate(bytes, tile.w, tile.h)
        } catch (e: Exception) {
            throw IOException("slot $slot does not inflate", e)
        }
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
