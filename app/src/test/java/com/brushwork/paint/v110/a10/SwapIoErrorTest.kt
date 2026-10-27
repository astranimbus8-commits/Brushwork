package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * v1.10 A10 (design 2.8, step 3): a failed swap write (an I/O error, a slab file that cannot be
 * made, a swap directory that cannot be made) throws, gives its pages back, leaves the tile
 * RESIDENT with its pixels and no slot, and turns writes off for the session (`canWrite` false;
 * every later write throws at once), while what was written before still reads. An interrupted
 * thread neither fails a write or a read nor closes the slab for the other threads.
 */
@RunWith(RobolectricTestRunner::class)
class SwapIoErrorTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL)

    @After
    fun tearDown() {
        Thread.interrupted()
        SwapKit.cleanUp()
    }

    private fun assertWriteFails(what: String, store: SlabSwapStore, tile: Tile, part: String) {
        try {
            store.write(tile)
            fail("$what: the write must fail")
        } catch (e: IOException) {
            assertTrue("$what: \"${e.message}\" names \"$part\"", e.message.orEmpty().contains(part))
        }
        assertEquals("$what: the tile stays RESIDENT", TileState.RESIDENT, tile.state)
        assertNull("$what: no slot", SwapKit.slot(tile))
    }

    @Test
    fun aFailedWriteTurnsWritesOffAndKeepsTheTile() {
        val store = SwapKit.store()
        val (before, beforeSrc) = SwapKit.noiseTile(1)
        store.write(before)
        SwapKit.commit(before)
        val used = store.usedBytes
        assertTrue(store.canWrite(4096))

        var calls = 0
        store.writeFault = { _, _, _ -> calls++; throw IOException("EIO (test)") }
        val (tile, src) = SwapKit.noiseTile(2)
        assertWriteFails("an I/O error", store, tile, "EIO (test)")
        assertEquals("its pages went back", used, store.usedBytes)
        assertTrue("writes are off for the session", store.writesOff)
        assertFalse(store.canWrite(1))
        F1Pixels.assertBitEqual("the tile keeps its pixels", src, tile.bitmapForRead())

        store.writeFault = null
        assertWriteFails("a later write", store, tile, "writes are off")
        assertEquals("never reaching the file", 1, calls)
        F1Pixels.assertBitEqual("what was written still reads", beforeSrc, store.read(before))
    }

    @Test
    fun aSlabFileThatCannotBeMadeTurnsWritesOff() {
        val store = SwapKit.store()
        // The session directory went (and a file took its name): no slab file can be opened.
        assertTrue(store.dir.deleteRecursively())
        assertTrue(store.dir.createNewFile())
        val (tile, src) = SwapKit.noiseTile(3)
        try {
            store.write(tile)
            fail("the write must fail")
        } catch (e: IOException) {
            assertNotNull(e.message)
        }
        assertEquals(TileState.RESIDENT, tile.state)
        assertNull(SwapKit.slot(tile))
        assertEquals("nothing held", 0L, store.usedBytes)
        assertEquals("no slab", 0, store.slabCount)
        assertTrue(store.writesOff)
        F1Pixels.assertBitEqual("pixels kept", src, tile.bitmapForRead())
        assertTrue(store.dir.delete())
    }

    @Test
    fun aSwapDirectoryThatCannotBeMadeMeansNoSwap() {
        val blocked = File(SwapKit.app.noBackupFilesDir, "blocked-swap-root")
        blocked.parentFile!!.mkdirs()
        assertTrue(blocked.createNewFile())
        try {
            val store = SwapKit.store(root = blocked)
            assertTrue("no directory, no writes", store.writesOff)
            assertFalse(store.canWrite(1))
            val (tile, _) = SwapKit.noiseTile(4)
            assertWriteFails("under a file", store, tile, "writes are off")
        } finally {
            blocked.delete()
        }
    }

    @Test
    fun anInterruptedThreadWritesAndReadsAndOthersGoOn() {
        val store = SwapKit.store()
        val (a, srcA) = SwapKit.noiseTile(5)
        val (b, srcB) = SwapKit.noiseTile(6)
        Thread.currentThread().interrupt()
        store.write(a)
        SwapKit.commit(a)
        F1Pixels.assertBitEqual("interrupted read", srcA, store.read(a))
        assertTrue("still interrupted", Thread.interrupted())
        assertFalse(store.writesOff)

        var failure: Throwable? = null
        Thread {
            try {
                store.write(b)
                F1Pixels.assertBitEqual("another thread after the interrupt", srcB, store.read(b))
                F1Pixels.assertBitEqual("a's slab is open", srcA, store.read(a))
            } catch (e: Throwable) {
                failure = e
            }
        }.apply { start(); join(10_000) }
        failure?.let { throw it }
    }
}
