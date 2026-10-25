package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapSlot
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * v1.10 A10 (design 2.8, step 3): every swap read is checked against the CRC-32 its slot recorded
 * at write. A changed byte in the slab, a slot whose CRC does not match, a slot past the end of its
 * slab, bytes that match their CRC but do not inflate, a slab that does not exist and a read after
 * close all throw `IOException` (the caller restores the layer from its last save) and never give
 * wrong pixels; the tile stays SWAPPED and its neighbours read as written. Through
 * `Tile.bitmapForRead` the same `IOException` reaches the reader.
 */
@RunWith(RobolectricTestRunner::class)
class SwapCrcMismatchTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL)

    @After
    fun tearDown() = SwapKit.cleanUp()

    private fun assertReadFails(what: String, store: SlabSwapStore, tile: Tile, part: String) {
        try {
            store.read(tile)
            fail("$what: the read must fail")
        } catch (e: IOException) {
            assertTrue("$what: \"${e.message}\" names \"$part\"", e.message.orEmpty().contains(part))
        }
        assertEquals("$what: the tile stays SWAPPED", TileState.SWAPPED, tile.state)
    }

    private fun slabFile(store: SlabSwapStore, slab: Int) = File(store.dir, "slab-%03d.bin".format(slab))

    /** Writes [bytes] at [offset] of [file] through its own handle (as a disk fault would). */
    private fun poke(file: File, offset: Long, bytes: ByteArray) = RandomAccessFile(file, "rw").use { it.seek(offset); it.write(bytes) }

    @Test
    fun aChangedByteIsCaughtAndTheNeighboursStillRead() {
        val store = SwapKit.store()
        val tiles = (1L..3L).map { SwapKit.noiseTile(it).also { (t, _) -> store.write(t); SwapKit.commit(t) } }
        val middle = SwapKit.slot(tiles[1].first)!!
        val file = slabFile(store, middle.slab)
        val at = middle.offset + middle.length / 2
        val old = RandomAccessFile(file, "r").use { it.seek(at); it.read() }
        poke(file, at, byteArrayOf((old xor 0x5A).toByte()))
        assertReadFails("a changed byte", store, tiles[1].first, "CRC mismatch")
        F1Pixels.assertBitEqual("the tile before", tiles[0].second, store.read(tiles[0].first))
        F1Pixels.assertBitEqual("the tile after", tiles[2].second, store.read(tiles[2].first))
    }

    @Test
    fun aWrongSlotIsCaught() {
        val store = SwapKit.store()
        val (tile, _) = SwapKit.noiseTile(4)
        store.write(tile)
        SwapKit.commit(tile)
        val good = SwapKit.slot(tile)!!

        synchronized(tile.lock) { tile.slot = good.copy(crc = good.crc xor 1) }
        assertReadFails("a wrong CRC", store, tile, "CRC mismatch")

        synchronized(tile.lock) { tile.slot = good.copy(offset = 1L shl 22) }
        assertReadFails("past the slab's end", store, tile, "past the slab")

        synchronized(tile.lock) { tile.slot = good.copy(slab = 3) }
        assertReadFails("no such slab", store, tile, "no slab 3")

        synchronized(tile.lock) { tile.slot = good }
        assertEquals(64, store.read(tile).width)
    }

    @Test
    fun bytesThatMatchTheirCrcButDoNotInflateAreCaught() {
        val store = SwapKit.store()
        val (tile, _) = SwapKit.noiseTile(5)
        store.write(tile)
        SwapKit.commit(tile)
        val slot = SwapKit.slot(tile)!!
        val junk = ByteArray(slot.length) { 0xFF.toByte() }
        poke(slabFile(store, slot.slab), slot.offset, junk)
        val crc = CRC32().apply { update(junk) }.value.toInt()
        synchronized(tile.lock) { tile.slot = SwapSlot(slot.slab, slot.offset, slot.length, crc) }
        assertReadFails("junk with its own CRC", store, tile, "does not inflate")
    }

    @Test
    fun theReaderGetsTheIoExceptionAndAClosedStoreReadsNothing() {
        val store = SwapKit.store()
        EngineConfig.swapStore = store
        val (tile, src) = SwapKit.noiseTile(6)
        store.write(tile)
        SwapKit.commit(tile)
        val slot = SwapKit.slot(tile)!!
        synchronized(tile.lock) { tile.slot = slot.copy(crc = slot.crc.inv()) }
        try {
            tile.bitmapForRead()
            fail("bitmapForRead must throw")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("CRC mismatch"))
        }
        synchronized(tile.lock) { tile.slot = slot }
        F1Pixels.assertBitEqual("restored slot", src, tile.bitmapForRead())

        store.close()
        assertReadFails("after close", store, tile, "closed")
    }
}
