package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.10 A9 step 2 (design 2.17): the full re-render at FULL of 500 phone layers, every tile
 * SWAPPED, after an eye toggle on the bottom layer. It is progressive (budgeted updates, the
 * display tiles wait for their reads, P4) and no read runs on main; the result equals the Classic
 * composite. Budget: 500 ms on the PC (3 s on the device), the PC median of 15 runs after 3
 * warm-ups.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class FullRerender500TimingTest {
    private val swap = FakeSwapStore(capacityBytes = 1L shl 30)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency(), swap = swap)

    private val made = mutableListOf<Bitmap>()
    private var worker: ExecutorService? = null

    @After
    fun tearDown() {
        worker?.shutdownNow()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test
    fun aFullReRenderOf500SwappedLayersIsProgressiveAndQuick() {
        val d = WaitingTimingKit.stack(N, 500L)
        val stores = WaitingTimingKit.stores(d)
        // Classic references while the tiles are resident (X1: a tiled draw is the flat draw).
        val shownRef = Compositor(d) { null }.renderFlattened().also { made += it }
        d.layers[0].visible = false
        val hiddenRef = Compositor(d) { null }.renderFlattened().also { made += it }
        d.layers[0].visible = true
        val swapped = WaitingTimingKit.swapOut(stores, swap)
        assertTrue("most layers hold swapped tiles ($swapped)", swapped >= N)
        val tiles = DisplayTiles(d.width, d.height, tileSize = 256)
        val w = Executors.newSingleThreadExecutor { r -> Thread(r, "rerender-worker").apply { isDaemon = true } }
        worker = w
        WaitingTimingKit.install(tiles, w)
        val compositor = Compositor(d) { null }
        WaitingTimingKit.renderAll(tiles, compositor)
        val main = Thread.currentThread().name
        val bottom = d.layers[0]
        val toggle = {
            bottom.visible = !bottom.visible
            tiles.invalidate(null)
            WaitingTimingKit.renderAll(tiles, compositor)
            Unit
        }
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "full re-render, 500 swapped layers") {
            repeat(3) { toggle() }
            val times = DoubleArray(RUNS) { PerfBudget.timeMs(toggle) }
            times.sort()
            times[times.size / 2]
        }
        println("FullRerender500TimingTest: median $ms ms (budget ${PerfBudget.ms(BUDGET_MS)} ms)")
        assertTrue("no read on main", swap.readThreads.none { it == main })
        val expected = if (bottom.visible) shownRef else hiddenRef
        A9Docs.assertBitEqual("the re-rendered tiles against Classic", expected, WaitingTimingKit.shown(tiles).also { made += it })
    }

    private companion object {
        const val N = 500
        const val RUNS = 15

        /** Design 2.17: 500 ms on the PC. */
        const val BUDGET_MS = 500.0
    }
}
