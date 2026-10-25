package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
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
 * v1.10 A8 step 3 (design 2.7, 2.17 "Layer switch, 4000 x 5000, tiles swapped"): from the press
 * on a layer whose tiles of its own are all SWAPPED to the end of its hold (FLAT, input free),
 * within PC 100 ms (the T606's 600 ms over the x 6 proxy, the ring after 250 ms being A10's; x 4
 * on CI): the median of 15 switches after 3 warm-ups, the layer parked and swapped out again
 * before each. The [FakeSwapStore] reads at memory speed (its latency is whole milliseconds per
 * tile, more than the budget over 320 tiles), so the time is the worker's inflates and main's
 * materialization. On a 512 x 512 document always; at 4000 x 5000 off CI only (`CI` unset).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class LayerSwitchBigTimingTest {
    private val swap = FakeSwapStore(capacityBytes = 1L shl 30)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap)

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /**
     * Runs main's tasks, without sleeping between them, until [c]'s hold ends; the System.nanoTime
     * then. ([A8Kit.drain] sleeps 1 ms between rounds, and a sleep's timer granularity, up to 16 ms
     * on Windows, is no part of the switch: on the device the looper runs each post at once.)
     */
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
        val doc = Document("a8", "a8", w, h).also { docs += it }
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer $i", F1Pixels.mixed(w, h, 50L + i).also { made += it }) }
        doc.activeLayerIndex = 1
        val sc = Smoke.newScope().also { scope = it }
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
    }

    /** The median ms of [count] switches to layer 0 of [c] (parked and swapped out again before each) until its hold ends, after [warm] warm-ups. */
    private fun switches(c: EditorController, count: Int = 15, warm: Int = 3): Double {
        val p = EngineConfig.residency as ResidencyPolicy
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
            val t0 = System.nanoTime()
            c.selectLayer(target)
            assertSame("held", target, c.activating)
            val dt = (untilHoldEnds(c) - t0) / 1e6
            A8Kit.drain()
            assertNull("the hold ended", c.activating)
            assertEquals(ResidencyState.FLAT, target.residency)
            if (k >= warm) ms[k - warm] = dt
        }
        return median(ms)
    }

    @Test
    fun aSwitchToASwappedLayer512() {
        val c = editor(512, 512)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(100.0), what = "a switch to a swapped 512 x 512 layer") { switches(c) }
        println("v110 A8 LayerSwitchBigTimingTest 512x512: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC 100 ms, device 600 ms)".format(ms, ms * 6))
    }

    @Test
    fun aSwitchToASwapped4000x5000Layer() {
        assumeTrue("the 4000 x 5000 probe runs off CI", System.getenv("CI") == null)
        val c = editor(4000, 5000)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(100.0), what = "a switch to a swapped 4000 x 5000 layer") { switches(c) }
        println("v110 A8 LayerSwitchBigTimingTest 4000x5000: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC 100 ms, device 600 ms)".format(ms, ms * 6))
    }
}
