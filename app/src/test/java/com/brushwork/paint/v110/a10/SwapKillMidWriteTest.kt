package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.SwapJanitor
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/**
 * v1.10 A10 (design 2.8, I34, step 3): swap is never resumed. A write cut off halfway (the bytes
 * partly in the slab, then an error) leaves the tile RESIDENT with no slot and its pages free, and
 * the next write of the session is refused before the file. A process killed with a session open
 * leaves its directory; the next process's purge at start deletes it (partial slabs too), and that
 * process's store starts empty in a new directory, where no slot of the dead one reads.
 */
@RunWith(RobolectricTestRunner::class)
class SwapKillMidWriteTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL)

    @After
    fun tearDown() = SwapKit.cleanUp()

    @Test
    fun aWriteCutOffHalfwayLeavesTheTileResident() {
        val store = SwapKit.store()
        val (first, firstSrc) = SwapKit.noiseTile(1)
        store.write(first)
        SwapKit.commit(first)
        val used = store.usedBytes
        val slab = File(store.dir, "slab-000.bin")

        // Half the bytes reach the slab, then the write fails (a kill would stop it at the same point).
        store.writeFault = { _, offset, length ->
            RandomAccessFile(slab, "rw").use { it.seek(offset); it.write(ByteArray(length / 2) { 0x33 }) }
            throw IOException("cut off (test)")
        }
        val (tile, src) = SwapKit.noiseTile(2)
        try {
            store.write(tile)
            fail("the write must fail")
        } catch (e: IOException) {
            assertEquals("cut off (test)", e.message)
        }
        assertEquals(TileState.RESIDENT, tile.state)
        assertNull("no slot points at the half-written bytes", SwapKit.slot(tile))
        assertEquals("their pages are free", used, store.usedBytes)
        assertTrue(store.writesOff)
        F1Pixels.assertBitEqual("the tile's pixels", src, tile.bitmapForRead())
        F1Pixels.assertBitEqual("the slot before", firstSrc, store.read(first))
    }

    @Test
    fun aKilledSessionIsPurgedAtTheNextStartAndNeverResumed() {
        val running = SwapKit.store()
        val tiles = (1L..5L).map { SwapKit.noiseTile(it).first.also { t -> running.write(t); SwapKit.commit(t) } }
        val oldSlot = SwapKit.slot(tiles[0])!!
        // The kill: the session's files stay as they were, with a slab cut short. (A copy under
        // another session name: this JVM still holds the slabs open, which Windows never deletes,
        // and a dead process holds nothing.)
        val dead = File(SwapKit.root, UUID.randomUUID().toString())
        assertTrue(running.dir.copyRecursively(dead))
        RandomAccessFile(File(dead, "slab-001.bin"), "rw").use { it.write(ByteArray(1000)) }
        assertTrue(dead.listFiles()!!.size >= 2)
        running.close()
        SwapKit.awaitClosed(running)

        // The next process: the dead session is not in its live set, so its directory is a leftover.
        val next = SwapKit.store()
        assertEquals("one leftover deleted", 1, SwapJanitor.purge(SwapKit.root))
        assertFalse("the dead session's directory is gone", dead.exists())
        assertTrue("the root stays", SwapKit.root.isDirectory)
        assertTrue("the new session's directory stays", next.dir.isDirectory)

        assertNotEquals("a new directory", dead.name, next.sessionId)
        assertEquals("the new store starts empty", 0L, next.usedBytes)
        assertEquals(0, next.slabCount)
        synchronized(tiles[0].lock) { tiles[0].slot = oldSlot }
        try {
            next.read(tiles[0])
            fail("a dead session's slot never reads")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("no slab"))
        }
    }
}
