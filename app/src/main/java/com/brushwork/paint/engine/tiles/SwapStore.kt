package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import java.io.IOException

/** v1.10 (F1, design 2.8): where a SWAPPED tile's packed bytes are: [length] bytes at [offset] of slab [slab], with their [crc]. */
data class SwapSlot(val slab: Int, val offset: Long, val length: Int, val crc: Int)

/**
 * v1.10 (F1, design 2.8; `SlabSwapStore` is A10's): the session's swap file. Only FULL swaps;
 * CLASSIC and TILES run with [NoSwap].
 */
interface SwapStore {
    val capacityBytes: Long
    val usedBytes: Long

    fun canWrite(bytes: Long): Boolean

    /** Worker: Deflate-1 nowrap, append; tile -> SWAPPED. */
    fun write(tile: Tile)

    /** Worker or reader: read, verify CRC, inflate; IOException on failure. */
    @Throws(IOException::class)
    fun read(tile: Tile): Bitmap

    fun free(slot: SwapSlot)

    /** Deletes the session directory. */
    fun close()
}

/** v1.10 (F1, design 2.8): no swap (CLASSIC and TILES): nothing can be written, so nothing is ever read. */
object NoSwap : SwapStore {
    override val capacityBytes: Long get() = 0L
    override val usedBytes: Long get() = 0L

    override fun canWrite(bytes: Long): Boolean = false

    override fun write(tile: Tile): Unit = throw IllegalStateException("no swap at this level")

    override fun read(tile: Tile): Bitmap = throw IOException("no swap at this level")

    override fun free(slot: SwapSlot) = Unit

    override fun close() = Unit
}
