package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.NoSwap
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapJanitor
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v1.10 A10 (design 2.8, step 3; I34): what [SlabSwapStore] writes it reads back bit for bit: 2,000
 * tiles of random size and content (noise, one color, half transparent) across several slabs,
 * with frees and rewrites between them; a PACKED tile's bytes written as they are; a second write
 * of a SWAPPED tile a no-op; four threads at once; a SWAPPED tile read through
 * `Tile.bitmapForRead`. And its install: at FULL the editor's session gets a store under
 * `noBackupFilesDir/swap/<UUID>` (one only), which the session's close closes and deletes; never at
 * CLASSIC, over another store, or under Robolectric unless a test asks.
 */
@RunWith(RobolectricTestRunner::class)
class SwapRoundTripTest {
    private val storage = FakeStorageProbe()

    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, storage = storage)

    @After
    fun tearDown() {
        SlabSwapStore.installUnderRobolectric = false
        SwapKit.cleanUp()
    }

    @Test
    fun twoThousandRandomTilesReadBackBitForBit() {
        val store = SwapKit.store(slabBytes = 4L shl 20)
        val rnd = Random(110_8)
        val kept = ArrayList<Pair<Tile, Bitmap>>()
        var written = 0
        while (written < 2_000) {
            val (tile, src) = if (written % 250 == 0) SwapKit.noiseTile(rnd.nextLong(), 256, 256) else SwapKit.randomTile(rnd)
            store.write(tile)
            SwapKit.commit(tile)
            written++
            // About one in four goes again (its layer changed or was deleted); its pages are reused.
            if (kept.isNotEmpty() && rnd.nextInt(4) == 0) {
                val gone = kept.removeAt(rnd.nextInt(kept.size))
                SwapKit.free(store, gone.first)
            }
            kept += tile to src
        }
        assertTrue("several slabs: ${store.slabCount}", store.slabCount >= 2)
        val held = kept.sumOf { SwapKit.pageBytes(SwapKit.slot(it.first)!!.length) }
        assertEquals("used counts the pages of the slots held", held, store.usedBytes)
        for ((i, pair) in kept.withIndex()) {
            val (tile, src) = pair
            F1Pixels.assertBitEqual("tile $i (${src.width}x${src.height})", src, store.read(tile))
            assertEquals("a read leaves the tile SWAPPED", TileState.SWAPPED, tile.state)
        }
        // Every slot read twice gives the same pixels.
        val (tile, src) = kept[kept.size / 2]
        F1Pixels.assertBitEqual("read again", src, store.read(tile))
    }

    @Test
    fun aPackedTileIsWrittenAsItIsAndASecondWriteIsANoOp() {
        val store = SwapKit.store()
        val src = F1Pixels.noise(100, 60, 7)
        val packed = TileOps.pack(src)
        val tile = Tile(100, 60).apply { this.packed = packed }
        assertEquals(TileState.PACKED, tile.state)
        store.write(tile)
        val slot = SwapKit.slot(tile)!!
        assertEquals("the packed bytes as they are", packed.size, slot.length)
        val used = store.usedBytes
        store.write(tile)
        assertSame("a second write keeps the slot", slot, SwapKit.slot(tile))
        assertEquals("and adds nothing", used, store.usedBytes)
        SwapKit.commit(tile)
        F1Pixels.assertBitEqual("packed", src, store.read(tile))
    }

    @Test
    fun fourThreadsWriteAndReadAtOnce() {
        val store = SwapKit.store(slabBytes = 2L shl 20)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tiles = (0 until 4).map { t -> Random(t.toLong()).let { rnd -> (0 until 150).map { SwapKit.randomTile(rnd, 96) } } }
            val jobs = tiles.mapIndexed { t, mine ->
                pool.submit<Int> {
                    for ((tile, _) in mine) store.write(tile)
                    for ((tile, src) in mine) F1Pixels.assertBitEqual("thread $t", src, store.read(tile))
                    mine.size
                }
            }
            assertEquals(600, jobs.sumOf { it.get(60, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aSwappedTileReadsThroughBitmapForRead() {
        val store = SwapKit.store()
        EngineConfig.swapStore = store
        val (tile, src) = SwapKit.noiseTile(3, 256, 256)
        store.write(tile)
        SwapKit.commit(tile)
        val back = tile.bitmapForRead()
        F1Pixels.assertBitEqual("bitmapForRead", src, back)
        assertEquals("a read-back tile has no density, as every tile", Bitmap.DENSITY_NONE, back.density)
    }

    @Test
    fun theEditorAtFullInstallsOneStoreItsCloseDeletes() {
        // Under Robolectric nothing is installed unless a test asks (the UI tests' FULL sessions).
        assertNull(SlabSwapStore.installIfFull(SwapKit.app))
        assertSame(NoSwap, EngineConfig.swapStore)

        SlabSwapStore.installUnderRobolectric = true
        val store = requireNotNull(SlabSwapStore.installIfFull(SwapKit.app)) { "installed at FULL" }
        assertSame("installed", store, EngineConfig.swapStore)
        assertEquals("under noBackupFilesDir/swap", SwapJanitor.root(SwapKit.app).canonicalFile, store.dir.parentFile!!.canonicalFile)
        assertTrue("its directory exists", store.dir.isDirectory)
        assertTrue("a live session", store.sessionId in SwapJanitor.live)
        assertTrue("its capacity read the swap directory's free space", storage.dirs.any { it.canonicalFile == store.dir.canonicalFile })
        assertEquals("the cap of 8 GiB free", SlabSwapStore.capOf(8L shl 30), store.capacityBytes)
        assertNull("one store per session", SlabSwapStore.installIfFull(SwapKit.app))

        val (tile, _) = SwapKit.noiseTile(1)
        store.write(tile)
        assertTrue(store.dir.listFiles()!!.any { it.name == "slab-000.bin" })

        // The session's close closes the store and deletes its directory. (Without the test level,
        // as in the app: closed, the engine is back at CLASSIC; the rule restores the property.)
        System.clearProperty(EngineRule.MODE)
        EngineConfig.closeSession()
        assertSame(NoSwap, EngineConfig.swapStore)
        SwapKit.awaitClosed(store)
        assertFalse("its directory is gone", store.dir.exists())
        assertFalse("no longer live", store.sessionId in SwapJanitor.live)
        assertFalse("a closed store writes nothing", store.canWrite(1))
        assertNull("no session, no store", SlabSwapStore.installIfFull(SwapKit.app))
    }

    @Test
    fun noStoreAtClassicOrOverAnother() {
        SlabSwapStore.installUnderRobolectric = true
        val fake = FakeSwapStore()
        EngineConfig.swapStore = fake
        assertNull("over another store", SlabSwapStore.installIfFull(SwapKit.app))
        assertSame(fake, EngineConfig.swapStore)

        System.setProperty(EngineRule.MODE, "classic")
        EngineConfig.openSession(EngineLevel.CLASSIC, engine.sessionBudgets(), null)
        assertSame("a new session closes the old store", NoSwap, EngineConfig.swapStore)
        assertNull("never at CLASSIC", SlabSwapStore.installIfFull(SwapKit.app))
        System.setProperty(EngineRule.MODE, "tiles")
        EngineConfig.openSession(EngineLevel.TILES, engine.sessionBudgets(), null)
        assertNull("never at TILES", SlabSwapStore.installIfFull(SwapKit.app))
        assertSame(NoSwap, EngineConfig.swapStore)
        assertFalse("no swap root made", SwapJanitor.root(SwapKit.app).exists())
    }
}
