package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.live.WaitingTiles
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Random
import java.util.concurrent.Executor

/**
 * v1.10 A9 review (design 2.0 P4, `Tile.kt` rules 1-5): what the waiting tiles' worker reads is
 * recycled only when it is the reader's own copy that nobody else has seen.
 * - A tile made resident on main after its read was queued (a CoW duplicate made active, say):
 *   the worker gets the tile's own bitmap, which must never be recycled with the read copies.
 * - A copy lent to its tile while the chunk composites is what `bitmapForRead` hands any other
 *   reader off main meanwhile, so it is left to the GC rather than recycled after the loan.
 * The worker runs by hand ([tasks]) so the interleaving is fixed.
 */
@RunWith(RobolectricTestRunner::class)
class WaitingReadOwnershipTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(
        EngineLevel.FULL,
        memory = FakeMemoryProbe(MemoryPreset.T606),
        budgets = Budgets(javaMax = 512L shl 20, nativeBudget = 256L shl 20, known = true, stepDown = 0),
        residency = ChaosResidency(),
        swap = swap,
    )

    private val made = mutableListOf<Bitmap>()
    private val tasks = ArrayDeque<Runnable>()
    private val worker = Executor { tasks.addLast(it) }

    @After
    fun tearDown() {
        A9Docs.disposeTracked()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    /** Three 512 x 512 layers of translucent noise, the middle one MULTIPLY. */
    private fun document(): Document {
        val d = A9Docs.track(Document("ownership", "ownership", SIDE, SIDE))
        val r = Random(5L)
        repeat(3) { i ->
            val px = IntArray(SIDE * SIDE) { Color.argb(40 + r.nextInt(200), r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
            val b = keep(BitmapUtils.createLayerBitmap(SIDE, SIDE).apply { setPixels(px, 0, SIDE, 0, 0, SIDE, SIDE) })
            d.layers += Layer(d.newLayerId(), "L$i", b).apply { if (i == 1) blendMode = LayerBlendMode.MULTIPLY }
        }
        return d
    }

    /** Parks every layer and swaps every ordinary tile out. */
    private fun swapEverything(d: Document) {
        val chaos = ChaosResidency()
        for (l in d.layers) {
            assertTrue(chaos.park(l))
            val t = l.pixels as TiledPixels
            for (row in 0 until t.rows) for (col in 0 until t.cols) {
                val tile = t.tileAt(col, row) ?: continue
                if (tile.solidColor == null) swap.swapOut(tile)
            }
        }
    }

    private fun tileOf(l: Layer): Tile = (l.pixels as TiledPixels).tileAt(0, 0)!!

    private fun display(): DisplayTiles = DisplayTiles(SIDE, SIDE).also { t ->
        WaitingTiles(t, worker, Handler(Looper.getMainLooper())).also { t.waiting = it }
    }

    /** Runs the queued reads one by one, each followed by the main looper. */
    private fun runWorker() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun shown(tiles: DisplayTiles): Bitmap {
        val out = keep(BitmapUtils.createLayerBitmap(SIDE, SIDE))
        tiles.drawTile(Canvas(out), 0, smooth = false)
        return out
    }

    @Test
    fun aTileMadeResidentWhileItsReadIsQueuedKeepsItsBitmap() {
        val d = document()
        val expected = keep(Compositor(d) { null }.renderFlattened())
        swapEverything(d)
        val tiles = display()
        val compositor = Compositor(d) { null }
        tiles.update(compositor)
        assertTrue("the tile waits", tiles.hasWaiting)
        assertEquals("one read queued", 1, tasks.size)
        val tile = tileOf(d.layers[1])
        TileOps.makeResident(tile)
        val resident = tile.bitmap!!
        runWorker()
        assertFalse(tiles.hasWaiting)
        assertTrue(tiles.isExact(0))
        assertFalse("the tile's own bitmap is never recycled", resident.isRecycled)
        assertSame(resident, tile.bitmap)
        A9Docs.assertBitEqual("the waited tile against Classic", expected, shown(tiles))
        A9Docs.assertBitEqual("the layer still draws", expected, keep(Compositor(d) { null }.renderFlattened()))
        for (l in d.layers) assertEquals("every requested pin is closed", 0, l.pins)
    }

    @Test
    fun aLentCopyHandedToAnotherReaderStaysValid() {
        val d = document()
        val expected = keep(Compositor(d) { null }.renderFlattened())
        swapEverything(d)
        val tile = tileOf(d.layers[0])
        var captured: Bitmap? = null
        // The override provider runs inside the chunk's composite, while the copies are lent:
        // a read of the swapped tile then returns the lent copy (what a reader off main would get).
        val compositor = Compositor(d) {
            if (captured == null && tile.bitmap != null) captured = tile.bitmapForRead()
            null
        }
        val tiles = display()
        tiles.update(compositor)
        assertTrue(tiles.hasWaiting)
        runWorker()
        assertFalse(tiles.hasWaiting)
        A9Docs.assertBitEqual("the waited tile against Classic", expected, shown(tiles))
        val copy = captured
        assertNotNull("a read ran while the copy was lent", copy)
        assertFalse("a lent copy is never recycled after the loan", copy!!.isRecycled)
        val probe = keep(BitmapUtils.createLayerBitmap(copy.width, copy.height))
        Canvas(probe).drawBitmap(copy, 0f, 0f, null)
        assertEquals("the lent copy holds the tile's pixels", copy.getPixel(7, 9), probe.getPixel(7, 9))
        assertTrue("the tile was not made resident", tile.bitmap == null)
    }

    private companion object {
        const val SIDE = 512
    }
}
