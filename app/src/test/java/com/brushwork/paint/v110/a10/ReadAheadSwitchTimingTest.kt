package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapReadAhead
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.v110.a8.A8Kit
import com.brushwork.paint.v110.lead.F1Pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 A10 step 8 (cut 1; design 2.17 "Layer switch, 4000 x 5000, tiles swapped", Q5): a switch
 * to the layer next to the active one, its tiles SWAPPED in a real [SlabSwapStore] and read ahead
 * ([SwapReadAhead], 300 ms after the last change), from the press to the end of its hold (FLAT,
 * input free) with no read of the swap file, within PC 100 ms (`LayerSwitchBigTimingTest`'s row:
 * the T606's 600 ms over the x 6 proxy; x 4 on CI). The design names no row of its own for step
 * 8 (cut 1 only relaxes Q5 to 1 s without it), so the read-ahead keeps the uncut row. The median
 * of 15 switches after 3 warm-ups, the layer parked, swapped out and read ahead again before each;
 * the same switch without the read-ahead (each tile read from the slab file) is printed beside
 * it, not asserted (`LayerSwitchBigTimingTest` holds that budget). On a 512 x 512 document always;
 * at 4000 x 5000 off CI only (`CI` unset).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class ReadAheadSwitchTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, storage = FakeStorageProbe(), residency = ClassicResidency)

    private lateinit var store: SlabSwapStore
    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null
    private var readAhead: SwapReadAhead? = null

    @Before
    fun setUp() {
        store = SwapKit.store()
        EngineConfig.swapStore = store
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        readAhead?.stop()
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
        SwapKit.cleanUp()
    }

    /** The editor as the read-ahead sees it (as `EngineEditorChrome`'s host: idle without a gesture or an activation). */
    private class Host(private val c: EditorController) : SwapReadAhead.Host {
        override val document: Document get() = c.doc
        override val idle: Boolean get() = c.activating == null && !c.isInteracting && c.busyMessage == null
    }

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /** Runs main's tasks without sleeping until [c]'s hold ends; the System.nanoTime then (as `LayerSwitchBigTimingTest`). */
    private fun untilHoldEnds(c: EditorController, timeoutMs: Long = 10_000L): Long {
        val looper = shadowOf(Looper.getMainLooper())
        val until = System.nanoTime() + timeoutMs * 1_000_000L
        while (c.activating != null) {
            looper.idle()
            if (System.nanoTime() > until) fail("still held after $timeoutMs ms")
            Thread.onSpinWait()
        }
        return System.nanoTime()
    }

    /** An editor over a [w] x [h] two-layer document (layer 1 active) with its policy. */
    private fun editor(w: Int, h: Int): EditorController {
        val doc = Document("a10", "a10", w, h).also { docs += it }
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer $i", F1Pixels.mixed(w, h, 60L + i).also { made += it }) }
        doc.activeLayerIndex = 1
        val sc = Smoke.newScope().also { scope = it }
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
    }

    private fun painted(l: Layer): List<Tile> = A8Kit.tilesOf(l).filter { it.solid == null }

    private fun copied(t: Tile): Boolean = synchronized(t.lock) { t.packed != null && t.slot != null && t.bitmap == null }

    /**
     * The median ms of [count] switches to layer 0 of [c] until its hold ends, after [warm]
     * warm-ups: before each, layer 0 is parked and swapped out again and, with [ahead], read
     * ahead (every painted tile copied); with it no switch reads the swap file, without it each does.
     */
    private fun switches(c: EditorController, ahead: Boolean, count: Int = 15, warm: Int = 3): Double {
        val p = EngineConfig.residency as ResidencyPolicy
        val ra = if (ahead) SwapReadAhead(Host(c), tileRam = { p.tileRam() }).also { readAhead = it } else null
        val target = c.doc.layers[0]
        val other = c.doc.layers[1]
        val ms = DoubleArray(count)
        for (k in 0 until warm + count) {
            p.tileRam = { Long.MAX_VALUE }
            c.selectLayer(other)
            A8Kit.advance(2600)
            A8Kit.drain()
            assertEquals("parked before switch $k", ResidencyState.PARKED, target.residency)
            p.tileRam = { 0L }
            A8Kit.advance(600)
            A8Kit.drain()
            p.tileRam = { Long.MAX_VALUE }
            assertTrue("swapped before switch $k", A8Kit.tilesOf(target).all { it.solid != null || it.state == TileState.SWAPPED })
            if (ra != null) {
                ra.changed()
                A8Kit.advance(SwapReadAhead.IDLE_MS)
                A8Kit.drain()
                assertTrue("read ahead before switch $k", painted(target).all { copied(it) })
            }
            val reads = store.readCount
            val t0 = System.nanoTime()
            c.selectLayer(target)
            assertSame("held", target, c.activating)
            val dt = (untilHoldEnds(c) - t0) / 1e6
            A8Kit.drain()
            assertNull("the hold ended", c.activating)
            assertEquals(ResidencyState.FLAT, target.residency)
            if (ahead) assertEquals("no read of the swap file in switch $k", reads, store.readCount)
            else assertTrue("the slab file read in switch $k", store.readCount > reads)
            if (k >= warm) ms[k - warm] = dt
        }
        ra?.stop()
        readAhead = null
        return median(ms)
    }

    private fun measure(w: Int, h: Int) {
        val c = editor(w, h)
        val cold = switches(c, ahead = false)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(100.0), what = "a switch to a read-ahead $w x $h layer") { switches(c, ahead = true) }
        println(
            "v110 A10 ReadAheadSwitchTimingTest ${w}x$h: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC 100 ms, device 600 ms); without the read-ahead %.3f ms (x 6 = %.1f ms)"
                .format(ms, ms * 6, cold, cold * 6),
        )
    }

    @Test
    fun aSwitchToAReadAheadLayer512() = measure(512, 512)

    @Test
    fun aSwitchToAReadAhead4000x5000Layer() {
        assumeTrue("the 4000 x 5000 probe runs off CI", System.getenv("CI") == null)
        measure(4000, 5000)
    }
}
