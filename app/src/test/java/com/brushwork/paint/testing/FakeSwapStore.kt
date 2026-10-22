package com.brushwork.paint.testing

import android.graphics.Bitmap
import com.brushwork.paint.engine.tiles.SwapSlot
import com.brushwork.paint.engine.tiles.SwapStore
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * v1.10 F1 (design 2.8 and 2.16; lead, frozen kit): a [SwapStore] in memory, for the swap tests
 * of A8, A9 and A10 (`UndoActiveSwappedTest`, `SwapOutRacesFrameTest`, `PinPreventsSwapOutTest`,
 * `WaitingTileOverTileRamTest`, ...). It packs as the real store does (Deflate level 1, nowrap:
 * `TileOps.pack`), keeps the bytes per slot with their CRC-32, and reads them back through the CRC
 * check and `TileOps.inflate`.
 *
 * Faults: [latencyMs] sleeps in every [write] and [read] (a slow card; `Thread.sleep`, on the
 * calling thread); [failAfterWrites] makes every write after that many successful ones throw
 * `IOException` (a full or broken card); [corrupt] flips a byte of one slot, so its next read
 * fails the CRC check with `IOException`. [capacityBytes] bounds [canWrite].
 *
 * [write] records the slot in `tile.slot` (under the tile's lock) and leaves the tile's bitmap
 * alone: dropping it is main's commit (the `Tile.kt` threading rules, A8's `TileWorker`). A test
 * without a worker calls [swapOut], which writes and then drops the bitmap and packed bytes at
 * once, on the calling thread, without recycling or the ledger: it skips the commit protocol on
 * purpose. [reads], [writes], [frees] and [readThreads] (the thread names of the reads, so a test
 * can assert that none ran on main) record what happened. Any thread.
 */
class FakeSwapStore(
    @Volatile override var capacityBytes: Long = 256L shl 20,
    @Volatile var latencyMs: Long = 0L,
    @Volatile var failAfterWrites: Int = Int.MAX_VALUE,
) : SwapStore {
    private val slots = ConcurrentHashMap<SwapSlot, ByteArray>()
    private val used = AtomicLong()
    private val nextOffset = AtomicLong()
    private val writeCount = AtomicInteger()
    private val readCount = AtomicInteger()
    private val freeCount = AtomicInteger()

    /** The thread names of the reads, in order. */
    val readThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** True after [close]. */
    @Volatile var closed = false
        private set

    override val usedBytes: Long get() = used.get()

    /** Successful writes, reads and frees so far. */
    val writes: Int get() = writeCount.get()
    val reads: Int get() = readCount.get()
    val frees: Int get() = freeCount.get()

    /** The slots that hold bytes now. */
    val liveSlots: Set<SwapSlot> get() = slots.keys.toSet()

    override fun canWrite(bytes: Long): Boolean = !closed && used.get() + bytes <= capacityBytes

    override fun write(tile: Tile) {
        delay()
        if (closed) throw IOException("swap store closed")
        if (writeCount.get() >= failAfterWrites) throw IOException("injected write failure after $failAfterWrites writes")
        val bytes = TileOps.pack(tile.bitmapForRead())
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        val slot = SwapSlot(0, nextOffset.getAndAdd(bytes.size.toLong()), bytes.size, crc)
        slots[slot] = bytes
        used.addAndGet(bytes.size.toLong())
        synchronized(tile.lock) { tile.slot = slot }
        writeCount.incrementAndGet()
    }

    @Throws(IOException::class)
    override fun read(tile: Tile): Bitmap {
        delay()
        readThreads += Thread.currentThread().name
        val slot = tile.slot ?: throw IOException("tile ${tile.w}x${tile.h} is not swapped")
        val bytes = slots[slot] ?: throw IOException("slot $slot was freed")
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        if (crc != slot.crc) throw IOException("CRC mismatch in slot $slot")
        readCount.incrementAndGet()
        return TileOps.inflate(bytes, tile.w, tile.h)
    }

    override fun free(slot: SwapSlot) {
        val bytes = slots.remove(slot) ?: return
        used.addAndGet(-bytes.size.toLong())
        freeCount.incrementAndGet()
    }

    override fun close() {
        closed = true
        slots.clear()
        used.set(0L)
    }

    /** Flips one byte of [slot]'s bytes, so its next [read] fails the CRC check. */
    fun corrupt(slot: SwapSlot) {
        val bytes = slots[slot] ?: error("slot $slot holds nothing")
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte()
    }

    /** Test shortcut for a worker and main's commit: [write]s [tile], then drops its bitmap and packed bytes (no recycle, no ledger). */
    fun swapOut(tile: Tile) {
        write(tile)
        synchronized(tile.lock) {
            tile.bitmap = null
            tile.packed = null
        }
    }

    private fun delay() {
        val ms = latencyMs
        if (ms > 0L) Thread.sleep(ms)
    }
}
