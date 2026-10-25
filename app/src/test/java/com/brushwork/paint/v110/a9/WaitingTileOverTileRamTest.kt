package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.live.WaitingTiles
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.10 A9 step 2 (design 2.0 P4, 2.16 "Compositor and thumbnails"): a display tile whose sources
 * (N layers of 1 MiB, every tile SWAPPED in a [FakeSwapStore]) together exceed the tile RAM
 * (`MemoryBudget.tileRam`, 16 MiB here) waits instead of reading on main, completes chunk by chunk
 * (each chunk's read copies stay near `WaitingTiles.chunkBytes`, far below the tile RAM), and then
 * equals the Classic composite of the same document bit for bit. No tile is made resident, no read
 * runs on main, and until it completes the tile is not exact and keeps what it showed.
 */
@RunWith(RobolectricTestRunner::class)
class WaitingTileOverTileRamTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(
        EngineLevel.FULL,
        memory = FakeMemoryProbe(MemoryPreset.T606),
        budgets = Budgets(javaMax = 512L shl 20, nativeBudget = TILE_RAM, known = true, stepDown = 0),
        residency = ChaosResidency(),
        swap = swap,
    )

    private val made = mutableListOf<Bitmap>()
    private var worker: ExecutorService? = null

    @After
    fun tearDown() {
        worker?.shutdownNow()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    /** [n] layers of 512 x 512 translucent noise (every tile ordinary), every third MULTIPLY, every fifth masked. */
    private fun document(n: Int): Document {
        val d = Document("waiting", "waiting", SIDE, SIDE)
        val r = Random(77L)
        repeat(n) { i ->
            val px = IntArray(SIDE * SIDE) { Color.argb(30 + r.nextInt(200), r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
            val b = keep(BitmapUtils.createLayerBitmap(SIDE, SIDE).apply { setPixels(px, 0, SIDE, 0, 0, SIDE, SIDE) })
            d.layers += Layer(d.newLayerId(), "L$i", b).apply {
                if (i % 3 == 2) blendMode = LayerBlendMode.MULTIPLY
                opacity = 0.9f
                if (i % 5 == 4) {
                    mask = keep(A9Docs.mask(i.toLong(), SIDE, SIDE))
                    maskEnabled = true
                }
            }
        }
        return d
    }

    /** Parks every layer and swaps every tile of its content and mask out; returns the swapped tiles' bytes. */
    private fun swapEverything(d: Document): Long {
        var bytes = 0L
        val chaos = ChaosResidency()
        for (l in d.layers) {
            assertTrue(chaos.park(l))
            for (store in listOf(l.pixels, l.maskPixels)) {
                val t = store as? TiledPixels ?: continue
                for (row in 0 until t.rows) for (col in 0 until t.cols) {
                    val tile = t.tileAt(col, row) ?: continue
                    if (tile.solidColor != null) continue
                    swap.swapOut(tile)
                    bytes += tile.bytes
                }
            }
        }
        return bytes
    }

    private fun waitingTiles(tiles: DisplayTiles): WaitingTiles {
        val w = Executors.newSingleThreadExecutor { r -> Thread(r, "waiting-test-worker").apply { isDaemon = true } }
        worker = w
        return WaitingTiles(tiles, w, Handler(Looper.getMainLooper())).also {
            it.chunkBytes = CHUNK
            tiles.waiting = it
        }
    }

    private fun shown(tiles: DisplayTiles): Bitmap {
        val out = keep(BitmapUtils.createLayerBitmap(SIDE, SIDE))
        tiles.drawTile(Canvas(out), 0, smooth = false)
        return out
    }

    /** Runs the main looper until no tile waits (the worker reads meanwhile). */
    private fun settle(tiles: DisplayTiles) {
        val until = System.nanoTime() + 60_000_000_000L
        while (tiles.hasWaiting) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!tiles.hasWaiting) break
            assertTrue("the waiting tile completes", System.nanoTime() < until)
            Thread.sleep(1)
        }
    }

    @Test
    fun aTileOverTheTileRamCompletesAndEqualsClassic() {
        val d = document(N)
        val expected = keep(Compositor(d) { null }.renderFlattened())
        val sources = swapEverything(d)
        assertTrue("the sources ($sources B) exceed the tile RAM", sources > EngineConfig.budget.tileRam())
        val tiles = DisplayTiles(SIDE, SIDE)
        val waiting = waitingTiles(tiles)
        val compositor = Compositor(d) { null }
        val main = Thread.currentThread().name
        tiles.update(compositor)
        assertTrue("the tile waits", tiles.hasWaiting)
        assertFalse("a waiting tile is not exact", tiles.isExact(0))
        settle(tiles)
        assertTrue("the tile is exact once complete", tiles.isExact(0))
        A9Docs.assertBitEqual("the waited tile against Classic", expected, shown(tiles))
        assertTrue("no read on main (${swap.readThreads.toSet()})", swap.readThreads.none { it == main })
        assertTrue("in several chunks (${waiting.chunks})", waiting.chunks >= 3)
        assertTrue("a chunk's copies (${waiting.peakChunkBytes} B) stay far below the tile RAM", waiting.peakChunkBytes <= CHUNK + 2 * LAYER_BYTES && waiting.peakChunkBytes < TILE_RAM / 2)
        for (l in d.layers) for (store in listOf(l.pixels, l.maskPixels)) {
            val t = store as? TiledPixels ?: continue
            for (row in 0 until t.rows) for (col in 0 until t.cols) {
                val tile = t.tileAt(col, row) ?: continue
                if (tile.solidColor == null) assertEquals("a source tile is never made resident", TileState.SWAPPED, tile.state)
            }
        }
        for (l in d.layers) assertEquals("every requested pin is closed", 0, l.pins)
    }

    @Test
    fun aWaitingTileKeepsItsStaleContentAndAnInvalidationStartsItAgain() {
        val d = document(12)
        val tiles = DisplayTiles(SIDE, SIDE)
        val compositor = Compositor(d) { null }
        tiles.update(compositor)
        val before = keep(shown(tiles).copy(Bitmap.Config.ARGB_8888, false))
        swapEverything(d)
        val waiting = waitingTiles(tiles)
        d.layers[0].visible = false
        tiles.invalidate(null)
        tiles.update(compositor)
        assertTrue(tiles.hasWaiting)
        A9Docs.assertBitEqual("the stale content stays until the tile completes", before, shown(tiles))
        // An edit while waiting: the job starts again and the result shows the edit.
        d.layers[1].visible = false
        tiles.invalidate(null)
        assertFalse("the invalidation cancels the job", tiles.hasWaiting)
        assertTrue(tiles.hasDirty)
        tiles.update(compositor)
        settle(tiles)
        assertTrue(waiting.reads > 0)
        val flat = document(12).apply {
            layers[0].visible = false
            layers[1].visible = false
        }
        A9Docs.assertBitEqual("the restarted tile against Classic", keep(Compositor(flat) { null }.renderFlattened()), shown(tiles))
        assertTrue(tiles.isExact(0))
    }

    @Test
    fun atClassicNothingWaits() {
        A9Docs.setLevel(EngineLevel.CLASSIC)
        val d = document(3)
        val tiles = DisplayTiles(SIDE, SIDE)
        tiles.update(Compositor(d) { null })
        assertFalse(tiles.hasWaiting)
        assertTrue(tiles.isExact(0))
        A9Docs.setLevel(EngineLevel.FULL)
    }

    private companion object {
        const val SIDE = 512
        const val N = 24
        const val LAYER_BYTES = 1L shl 20
        const val TILE_RAM = 16L shl 20
        const val CHUNK = 4L shl 20
    }
}
