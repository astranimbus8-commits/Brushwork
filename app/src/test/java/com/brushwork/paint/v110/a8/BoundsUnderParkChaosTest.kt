package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import android.os.MessageQueue
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.tools.transform.LayerBoundsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A8 (design 2.2, J5; the nightly `-PengineMode=full -PparkChaos` failure of
 * `TransformSnapDeleteRobolectricTest.largeLayersAreMeasuredInTheBackground`, "expected:<700.0> but
 * was:<704.0>"): the smart-guide cache keeps a parked layer's EXACT pixel bounds (never its tiles'
 * rect) while park chaos makes the layer flat and parks it again into a new store after every
 * task. F1's cache keyed an entry by the identity of `layer.bitmap` and read that getter to
 * compare, which materialized the layer, so under chaos every idle gave it another bitmap: the
 * bounds were never known, nothing snapped and the drag kept its raw 704. A8's entry follows
 * `contentVersion` and `pixelGeneration`, which a park, an unpark and a materialization keep (J5):
 * once known, the bounds stay known and exact through every re-park, nothing is scanned again, and
 * asking for them materializes nothing. On a 512 x 512 document (the copy path) always; on the
 * failing test's 1000 x 600 (the tiles path, above `SYNC_PIXELS`) off CI only (`CI` unset).
 */
@RunWith(RobolectricTestRunner::class)
class BoundsUnderParkChaosTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = FakeSwapStore())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        scope.cancel()
        EngineConfig.residency = ClassicResidency
        A8Kit.drain()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    /**
     * The main queue going idle: its idle handlers run once, as `MessageQueue.next` runs them (the
     * paused looper of Robolectric does not run them itself); one that answers false is removed.
     */
    private fun queueIdle() {
        val q = Looper.getMainLooper().queue
        val f = MessageQueue::class.java.getDeclaredField("mIdleHandlers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val handlers = synchronized(q) { ArrayList(f.get(q) as ArrayList<MessageQueue.IdleHandler>) }
        for (h in handlers) if (!h.queueIdle()) q.removeIdleHandler(h)
    }

    /** Runs the main looper (and gives the background scan real time) until [done]. */
    private fun waitUntil(done: () -> Boolean) {
        repeat(4000) {
            if (done()) return
            A8Kit.idle()
            Thread.sleep(5)
        }
        A8Kit.idle()
        assertTrue("timed out", done())
    }

    /** A [w] x [h] document: a box filling [box] exactly (off the tile grid) under an active empty layer. */
    private fun doc(w: Int, h: Int, box: Rect): Document {
        val d = Document("a8", "a8", w, h).also { docs += it }
        val b = BitmapUtils.createLayerBitmap(w, h).also { made += it }
        Canvas(b).drawRect(box, Paint().apply { color = 0xFF2266AA.toInt() })
        d.layers += Layer(d.newLayerId(), "Box", b)
        d.layers += Layer(d.newLayerId(), "Active", BitmapUtils.createLayerBitmap(w, h).also { made += it })
        return d
    }

    private fun assertExactUnderChaos(w: Int, h: Int, box: Rect) {
        val d = doc(w, h, box)
        EngineConfig.residency = ChaosResidency(everyTask = true)
        d.activeLayerIndex = 1
        A8Kit.idle()
        val layer = d.layers[0]
        assertEquals("parked by the chaos", ResidencyState.PARKED, layer.residency)

        val cache = LayerBoundsCache(scope, detectLines = true) {}
        cache.request(listOf(layer))
        waitUntil { !cache.isBusy && cache.isKnown(layer) }
        assertEquals("the exact pixel bounds", box, cache.bounds(layer))

        repeat(4) { k ->
            val store = layer.pixels
            // What v1.9 code does on main: a facade read makes the layer flat until the next idle.
            assertTrue(layer.bitmap.width == w)
            assertEquals(ResidencyState.FLAT, layer.residency)
            assertTrue("known while flat ($k)", cache.isKnown(layer))
            assertEquals("exact while flat ($k)", box, cache.bounds(layer))
            queueIdle()
            assertEquals("parked again ($k)", ResidencyState.PARKED, layer.residency)
            assertNotSame("into a new store ($k)", store, layer.pixels)
            assertTrue("still known after the re-park ($k)", cache.isKnown(layer))
            assertEquals("still exact after the re-park ($k)", box, cache.bounds(layer))
            val materialized = com.brushwork.paint.model.PixelFacade.materializations
            cache.request(listOf(layer))
            assertFalse("nothing to scan again ($k)", cache.isBusy)
            assertEquals("asking materializes nothing ($k)", materialized, com.brushwork.paint.model.PixelFacade.materializations)
        }
    }

    @Test
    fun aParkedLayerKeepsItsExactBoundsThroughParkChaos512() {
        assertExactUnderChaos(512, 512, Rect(301, 77, 397, 141))
    }

    @Test
    fun theFailingTestsLayerKeepsItsExactBoundsThroughParkChaos() {
        assumeTrue("the 1000 x 600 probe runs off CI", System.getenv("CI") == null)
        assertTrue(1000L * 600 > LayerBoundsCache.SYNC_PIXELS)
        assertExactUnderChaos(1000, 600, Rect(700, 300, 800, 400))
    }
}
