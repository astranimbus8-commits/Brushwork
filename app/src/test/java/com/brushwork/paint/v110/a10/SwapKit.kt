package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapJanitor
import com.brushwork.paint.engine.tiles.SwapSlot
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.testing.FakeClock
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Random

/**
 * The A10 swap tests' shared pieces (design 2.8): stores under `noBackupFilesDir/swap`, tiles of
 * known pixels, main's commit after a write, and the cleanup every test runs after itself (each
 * store it opened closed, its directory gone and [SwapJanitor.live] as before).
 */
internal object SwapKit {
    val app get() = RuntimeEnvironment.getApplication()

    /** `noBackupFilesDir/swap`. */
    val root: File get() = SwapJanitor.root(app)

    private val opened = mutableListOf<SlabSwapStore>()

    /** A store under [root] with [storage] (8 GiB free by default), [clock] and [slabBytes]; [cleanUp] closes it. */
    fun store(
        storage: FakeStorageProbe = FakeStorageProbe(),
        clock: FakeClock = FakeClock(),
        slabBytes: Long = SlabSwapStore.SLAB_BYTES,
        root: File = this.root,
    ): SlabSwapStore = track(SlabSwapStore(root, storage, clock.millis, slabBytes))

    /** [store], which [cleanUp] closes (a store a test made itself). */
    fun track(store: SlabSwapStore): SlabSwapStore = store.also { synchronized(opened) { opened += it } }

    /** A tile of [src]'s pixels (its own copy) and [src]. */
    fun tileOf(src: Bitmap): Pair<Tile, Bitmap> = Tile.of(src.copy(Bitmap.Config.ARGB_8888, true)) to src

    /** A tile of noise (barely compressible). */
    fun noiseTile(seed: Long, w: Int = 64, h: Int = 64): Pair<Tile, Bitmap> = tileOf(F1Pixels.noise(w, h, seed))

    /** A tile of random size (1..[max] px a side) and random content: noise, one color, or noise over half of it. */
    fun randomTile(rnd: Random, max: Int = 128): Pair<Tile, Bitmap> {
        val w = 1 + rnd.nextInt(max)
        val h = 1 + rnd.nextInt(max)
        val src = when (rnd.nextInt(3)) {
            0 -> F1Pixels.noise(w, h, rnd.nextLong())
            1 -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(rnd.nextInt()) }
            else -> F1Pixels.noise(w, h, rnd.nextLong()).also { b ->
                for (y in 0 until h / 2) for (x in 0 until w) b.setPixel(x, y, 0)
            }
        }
        return tileOf(src)
    }

    /** Main's commit after a write, without a worker: the bitmap and packed bytes go (no recycle, no ledger). */
    fun commit(tile: Tile) {
        synchronized(tile.lock) {
            assertNotNull("the write recorded a slot", tile.slot)
            tile.bitmap = null
            tile.packed = null
        }
        assertEquals(TileState.SWAPPED, tile.state)
    }

    /** [tile]'s slot (main's read after the worker's write). */
    fun slot(tile: Tile): SwapSlot? = synchronized(tile.lock) { tile.slot }

    /** Main frees [tile]'s slot in [store] (the tile is gone or resident again). */
    fun free(store: SlabSwapStore, tile: Tile) {
        val s = synchronized(tile.lock) { tile.slot.also { tile.slot = null } } ?: return
        store.free(s)
    }

    /** The bytes a slot of [length] takes: whole pages. */
    fun pageBytes(length: Int): Long = (length + SlabSwapStore.PAGE - 1) / SlabSwapStore.PAGE * SlabSwapStore.PAGE

    /** Waits for [store]'s close to delete its directory. */
    fun awaitClosed(store: SlabSwapStore) {
        store.closer?.join(10_000)
    }

    /** Closes every store a test opened, waits for their directories to go and empties the swap root. */
    fun cleanUp() {
        val all = synchronized(opened) { opened.toList().also { opened.clear() } }
        for (s in all) {
            s.close()
            awaitClosed(s)
        }
        root.deleteRecursively()
    }
}
