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
 * v1.10 A9 step 2 (design 2.17): the first composite after an open whose 200 phone layers are not
 * in memory: a new display (every tile dirty) over layers whose every tile is Deflate-packed, the
 * pixels a reload of an evicted layer inflates (the reload itself, from the saved file, is A10's
 * and A11's: F1's `TileOps` refuses to read an `evictedTo` store). Progressive, no tile is made
 * resident, and the result equals the Classic composite. Budget: 400 ms on the PC (2.4 s on the
 * device), the PC median of 15 runs after 3 warm-ups.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class FirstCompositeEvictedTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency(), swap = FakeSwapStore())

    private val made = mutableListOf<Bitmap>()
    private var worker: ExecutorService? = null

    @After
    fun tearDown() {
        worker?.shutdownNow()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test
    fun theFirstCompositeOf200PackedLayersIsProgressiveAndQuick() {
        val d = WaitingTimingKit.stack(N, 200L)
        val expected = Compositor(d) { null }.renderFlattened().also { made += it }
        val packed = WaitingTimingKit.packAll(WaitingTimingKit.stores(d))
        assertTrue("every layer holds packed tiles ($packed)", packed >= N)
        val w = Executors.newSingleThreadExecutor { r -> Thread(r, "first-composite-worker").apply { isDaemon = true } }
        worker = w
        val compositor = Compositor(d) { null }
        var last: DisplayTiles? = null
        val first = {
            last?.release()
            val tiles = DisplayTiles(d.width, d.height, tileSize = 256)
            WaitingTimingKit.install(tiles, w)
            WaitingTimingKit.renderAll(tiles, compositor)
            last = tiles
        }
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "first composite, 200 packed layers") {
            repeat(3) { first() }
            val times = DoubleArray(RUNS) { PerfBudget.timeMs { first() } }
            times.sort()
            times[times.size / 2]
        }
        println("FirstCompositeEvictedTimingTest: median $ms ms (budget ${PerfBudget.ms(BUDGET_MS)} ms)")
        A9Docs.assertBitEqual("the first composite against Classic", expected, WaitingTimingKit.shown(last!!).also { made += it })
        for (s in WaitingTimingKit.stores(d)) for (row in 0 until s.rows) for (col in 0 until s.cols) {
            val t = s.tileAt(col, row) ?: continue
            if (t.solidColor == null) assertTrue("a packed tile is never made resident", t.bitmap == null)
        }
        last.release()
    }

    private companion object {
        const val N = 200
        const val RUNS = 15

        /** Design 2.17: 400 ms on the PC. */
        const val BUDGET_MS = 400.0
    }
}
