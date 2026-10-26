package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.live.BelowProjection
import com.brushwork.paint.engine.live.WaitingTiles
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.ProjectionOracle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.10 A9 (design 2.0 P4 with 2.5 X4): the below projection and the waiting tiles together. A
 * render checks only the units it reads for tiles that are not resident: with the projection's
 * cache tile built, the units from the split up inside the dirty part, so tiles below that went
 * cold (packed, swapped: P3 takes them first, the projection having made them cold) never make a
 * stroke frame wait; before it is built, the build itself reads through the waiting tiles (never
 * on main) and the projection adopts their accumulator at the split. Every frame equals the frame
 * without projections ([ProjectionOracle]) of a twin document that was never packed, nothing is
 * materialized, and no packed or swapped tile is made resident.
 */
@RunWith(RobolectricTestRunner::class)
class BelowProjectionWaitingTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency(), swap = swap)

    private val made = mutableListOf<Bitmap>()
    private var worker: ExecutorService? = null

    @After
    fun tearDown() {
        A9Docs.disposeTracked()
        worker?.shutdownNow()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private class Setup(
        val doc: Document,
        val twin: Document,
        val active: Layer,
        val tiles: DisplayTiles,
        val compositor: Compositor,
        val projection: BelowProjection,
        val waiting: WaitingTiles,
    )

    /**
     * [N] parked layers ([WaitingTimingKit.stack]) with the active layer flat: a new empty layer
     * on top, or the stack's layer [activeIndex]. The twin is the same document, never packed.
     */
    private fun setup(activeIndex: Int? = null): Setup {
        fun document(): Document = WaitingTimingKit.stack(N, SEED).also { d ->
            if (activeIndex == null) {
                d.layers += Layer(d.newLayerId(), "Top", A9Docs.empty(d.width, d.height))
                d.activeLayerIndex = d.layers.lastIndex
            } else {
                d.activeLayerIndex = activeIndex
                ChaosResidency().unpark(d.layers[activeIndex])
            }
        }
        val doc = document()
        val twin = document()
        val w = Executors.newSingleThreadExecutor { r -> Thread(r, "projection-waiting-worker").apply { isDaemon = true } }
        worker = w
        val tiles = DisplayTiles(doc.width, doc.height, tileSize = 256)
        val waiting = WaitingTimingKit.install(tiles, w)
        val active = doc.layers[doc.activeLayerIndex]
        val projection = BelowProjection({ doc }, { active }, { null })
        projection.install(tiles)
        return Setup(doc, twin, active, tiles, Compositor(doc) { null }, projection, waiting)
    }

    /** Stroke frame [i]: the same dab on the active layer of both documents, its 256 x 256 area invalidated. */
    private fun dab(s: Setup, i: Int) {
        val x = 70 + (i * 97) % 372
        val y = 70 + (i * 61) % 372
        val p = Paint().apply {
            isAntiAlias = true
            color = Color.argb(200, (i * 40) and 255, 90, 255 - ((i * 30) and 255))
        }
        for (d in listOf(s.doc, s.twin)) {
            Canvas((d.layers[d.activeLayerIndex].pixels as FlatPixels).bitmap).drawCircle(x.toFloat(), y.toFloat(), 14f, p)
        }
        s.tiles.invalidate(Rect(x - 128, y - 128, x + 128, y + 128))
    }

    private fun assertExact(message: String, s: Setup) {
        val expected = ProjectionOracle.frame(s.twin, display = true).also { made += it }
        A9Docs.assertBitEqual(message, expected, WaitingTimingKit.shown(s.tiles).also { made += it })
        for (i in 0 until s.tiles.tileCount) assertTrue("$message: tile $i is exact", s.tiles.isExact(i))
    }

    /** No packed or swapped tile of [d] was made resident. */
    private fun assertStillCold(d: Document, where: String) {
        for (l in d.layers) {
            val t = l.pixels as? TiledPixels ?: continue
            for (row in 0 until t.rows) for (col in 0 until t.cols) {
                val tile = t.tileAt(col, row) ?: continue
                if (tile.solidColor == null) assertTrue("$where: a tile of ${l.name} stays cold", tile.bitmap == null)
            }
        }
    }

    @Test
    fun aBuildOverPackedTilesWaitsOnceAndStrokeFramesThenReadNothingBelow() {
        val s = setup()
        assertTrue("tiles to pack", WaitingTimingKit.packAll(WaitingTimingKit.stores(s.doc)) >= N)
        val mats = PixelFacade.materializations
        s.tiles.update(s.compositor)
        assertTrue("the first composite waits (every tile below is packed)", s.tiles.hasWaiting)
        assertEquals("no cache tile is built on main", 0L, s.projection.builds)
        WaitingTimingKit.renderAll(s.tiles, s.compositor)
        assertEquals("every cache tile was built by the waiting tiles", s.tiles.tileCount.toLong(), s.projection.builds)
        assertExact("the first composite", s)
        val reads = s.waiting.reads
        assertTrue("the build read the packed tiles off main", reads > 0)
        val hits = s.projection.hits
        repeat(FRAMES) { i ->
            dab(s, i)
            s.tiles.update(s.compositor)
            assertFalse("stroke frame $i waits for nothing", s.tiles.hasWaiting)
            assertExact("stroke frame $i", s)
        }
        assertEquals("no tile below the split was read again", reads, s.waiting.reads)
        assertTrue("the stroke frames drew from the projection", s.projection.hits >= hits + FRAMES)
        assertEquals("each cache tile was built once", s.tiles.tileCount.toLong(), s.projection.builds)
        assertEquals("nothing materialized", mats, PixelFacade.materializations)
        assertStillCold(s.doc, "after the stroke frames")
    }

    @Test
    fun tilesBelowThatGoColdAfterTheBuildNeverMakeAStrokeFrameWait() {
        val s = setup()
        s.tiles.update(s.compositor)
        assertFalse("everything resident: nothing waits", s.tiles.hasWaiting)
        assertEquals(s.tiles.tileCount.toLong(), s.projection.builds)
        // P3: the tiles below go cold (the projection keeps them from being read).
        assertTrue(WaitingTimingKit.packAll(WaitingTimingKit.stores(s.doc)) >= N)
        repeat(FRAMES) { i ->
            dab(s, i)
            s.tiles.update(s.compositor)
            assertFalse("stroke frame $i waits for nothing", s.tiles.hasWaiting)
            assertExact("stroke frame $i", s)
        }
        assertEquals("no tile was read", 0L, s.waiting.reads)
        assertStillCold(s.doc, "after the stroke frames")
        // An eye toggle below drops the projection: it is built again through the waiting tiles.
        for (d in listOf(s.doc, s.twin)) d.layers[3].visible = false
        s.tiles.invalidate(null)
        s.tiles.update(s.compositor)
        assertTrue("the rebuild over packed tiles waits", s.tiles.hasWaiting)
        WaitingTimingKit.renderAll(s.tiles, s.compositor)
        assertEquals("rebuilt by the waiting tiles", 2L * s.tiles.tileCount, s.projection.builds)
        assertExact("after an eye toggle below", s)
        assertStillCold(s.doc, "after the rebuild")
    }

    @Test
    fun withTheCacheBuiltOnlyColdLayersAboveTheSplitAreRead() {
        val s = setup(activeIndex = ACTIVE)
        s.tiles.update(s.compositor)
        assertEquals(s.tiles.tileCount.toLong(), s.projection.builds)
        // Below the split packed, above it swapped: a read of a swapped tile is the swap's.
        val below = s.doc.layers.subList(0, ACTIVE).mapNotNull { it.pixels as? TiledPixels }
        val above = s.doc.layers.subList(ACTIVE + 1, s.doc.layers.size).mapNotNull { it.pixels as? TiledPixels }
        assertTrue(WaitingTimingKit.packAll(below) > 0)
        assertTrue(WaitingTimingKit.swapOut(above, swap) > 0)
        val main = Thread.currentThread().name
        repeat(FRAMES) { i ->
            dab(s, i)
            WaitingTimingKit.renderAll(s.tiles, s.compositor)
            assertExact("stroke frame $i", s)
        }
        assertTrue("the frames read the swapped tiles above", s.waiting.reads > 0)
        assertEquals("every read was of a tile above the split", s.waiting.reads, swap.reads.toLong())
        assertTrue("no read on main", swap.readThreads.none { it == main })
        assertEquals("each cache tile was built once", s.tiles.tileCount.toLong(), s.projection.builds)
        assertStillCold(s.doc, "after the stroke frames")
    }

    private companion object {
        const val N = 24
        const val ACTIVE = 15
        const val FRAMES = 6
        const val SEED = 9L
    }
}
