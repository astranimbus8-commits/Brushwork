package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SlabSwapStore.Companion.MIN_FREE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeClock
import com.brushwork.paint.testing.FakeStorageProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * v1.10 A10 (design 2.8, J10, UQ13, step 3): the swap is at most 16 GiB and at most half the free
 * space above 1 GiB (`swapCap = min(16 GiB, (free - 1 GiB) / 2)`), read from the swap directory at
 * the start and again once 60 s have passed. Under 1 GiB free, or once the slots held reach the
 * cap, `canWrite` is false and a write throws without touching the file: the tile stays RESIDENT
 * (parking goes on in RAM) and writes are not turned off, so they resume when space comes back or
 * a slot is freed. A free-space reading that fails counts as no space.
 */
@RunWith(RobolectricTestRunner::class)
class SwapDiskFullTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL)

    @After
    fun tearDown() = SwapKit.cleanUp()

    private val gib = 1L shl 30

    private fun assertFull(what: String, store: SlabSwapStore, tile: Tile) {
        try {
            store.write(tile)
            fail("$what: the write must fail")
        } catch (e: IOException) {
            assertTrue("$what: ${e.message}", e.message.orEmpty().contains("swap is full"))
        }
        assertEquals("$what: RESIDENT", TileState.RESIDENT, tile.state)
        assertNull(SwapKit.slot(tile))
        assertFalse("$what: a full swap is not a failed write", store.writesOff)
    }

    @Test
    fun theCapFormula() {
        assertEquals("8 GiB free: 3.5 GiB", 7 * gib / 2, SlabSwapStore.capOf(8 * gib))
        assertEquals("2 GiB free: 512 MiB", gib / 2, SlabSwapStore.capOf(2 * gib))
        assertEquals("33 GiB free: 16 GiB", 16 * gib, SlabSwapStore.capOf(33 * gib))
        assertEquals("100 GiB free: still 16 GiB", 16 * gib, SlabSwapStore.capOf(100 * gib))
        assertEquals("1 GiB free: nothing", 0L, SlabSwapStore.capOf(gib))
        assertEquals("under 1 GiB: nothing, never negative", 0L, SlabSwapStore.capOf(gib / 2))
        assertEquals(0L, SlabSwapStore.capOf(0L))
        assertEquals(16 * gib, SlabSwapStore.MAX_CAP)
        assertEquals(gib, MIN_FREE)
        assertEquals(64L shl 20, SlabSwapStore.SLAB_BYTES)
        assertEquals(4096L, SlabSwapStore.PAGE)
    }

    @Test
    fun underOneGibFreeNothingIsWrittenUntilTheNextReading() {
        val storage = FakeStorageProbe(free = MIN_FREE - 1)
        val clock = FakeClock()
        val store = SwapKit.store(storage, clock)
        assertEquals(0L, store.capacityBytes)
        assertFalse(store.canWrite(1))
        val (tile, _) = SwapKit.noiseTile(1)
        assertFull("under 1 GiB", store, tile)
        assertFalse("no slab file made", store.dir.list()!!.isNotEmpty())

        // Space comes back; the store sees it at the next reading, 60 s after the last.
        storage.free = 8 * gib
        clock.advance(SlabSwapStore.CAP_REFRESH_MS - 1)
        assertFalse("not read again before 60 s", store.canWrite(1))
        clock.advance(1)
        assertTrue("read again at 60 s", store.canWrite(1))
        assertEquals(SlabSwapStore.capOf(8 * gib), store.capacityBytes)
        store.write(tile)
        assertTrue(SwapKit.slot(tile) != null)
        assertTrue("every reading asks about the swap directory", storage.dirs.all { it.canonicalFile == store.dir.canonicalFile })
        assertEquals("two readings", 2, storage.dirs.size)

        // The disk fills again: writes stop at the next reading, the tile slots held stay readable.
        storage.free = MIN_FREE - 4096
        clock.advance(SlabSwapStore.CAP_REFRESH_MS)
        val (next, _) = SwapKit.noiseTile(2)
        assertFull("full again", store, next)
        SwapKit.commit(tile)
        assertEquals(64, store.read(tile).width)
    }

    @Test
    fun writesStopAtTheCapAndGoOnWhenASlotIsFreed() {
        // 1 GiB + 128 KiB free: a cap of 64 KiB, 16 pages.
        val storage = FakeStorageProbe(free = MIN_FREE + (128L shl 10))
        val store = SwapKit.store(storage)
        assertEquals(64L shl 10, store.capacityBytes)
        val held = ArrayList<Tile>()
        while (held.size < 100) {
            val (t, _) = SwapKit.noiseTile(held.size.toLong(), 32, 32)
            if (!store.canWrite(SwapKit.pageBytes(TileOps.pack(t.bitmapForRead()).size))) {
                assertFull("at the cap", store, t)
                break
            }
            store.write(t)
            SwapKit.commit(t)
            held += t
            assertTrue("never past the cap: ${store.usedBytes}", store.usedBytes <= store.capacityBytes)
        }
        assertTrue("several fit first: ${held.size}", held.size >= 4)
        assertTrue(store.capacityBytes - store.usedBytes < 2 * SlabSwapStore.PAGE)

        SwapKit.free(store, held.removeAt(0))
        val (again, _) = SwapKit.noiseTile(1000, 32, 32)
        store.write(again)
        assertTrue("a freed slot makes room", SwapKit.slot(again) != null)
    }

    @Test
    fun aFailedReadingOfFreeSpaceMeansNoSwap() {
        val storage = FakeStorageProbe().apply { failure = SecurityException("statfs refused (test)") }
        val clock = FakeClock()
        val store = SwapKit.store(storage, clock)
        assertEquals(0L, store.freeBytes)
        assertEquals(0L, store.capacityBytes)
        assertFalse(store.canWrite(1))
        assertFull("no reading", store, SwapKit.noiseTile(1).first)

        storage.failure = null
        clock.advance(SlabSwapStore.CAP_REFRESH_MS)
        assertTrue("the next good reading allows writes", store.canWrite(1))
        assertEquals(8 * gib, store.freeBytes)
    }
}
