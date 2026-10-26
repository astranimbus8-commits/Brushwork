package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A8 step 6 (design 2.0 "Park", 2.17 "Park one phone layer (background)"): the work the
 * `tile-swapper` does to park one layer, cutting its flat bitmap into 256 px pieces (a transparent
 * one dropped, a uniform one a solid color: `TileOps.cutPieces`, what `ParkCutter` runs), within
 * PC 4 ms (the T606's 24 ms over the x 6 proxy; x 4 on CI): the median of 15 cuts after 3
 * warm-ups, each recycled. The main thread only swaps the store at the commit. On a 512 x 512
 * layer always; at the phone's 1080 x 2408 off CI only (`CI` unset).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class ParkTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = FakeSwapStore())

    private val made = ArrayList<Bitmap>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        baseline.assertBack(emptyList())
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /** The median ms of [count] cuts of [b], after [warm] warm-ups. */
    private fun cuts(b: Bitmap, count: Int = 15, warm: Int = 3): Double {
        val ms = DoubleArray(count)
        val cols = (b.width + 255) / 256
        val rows = (b.height + 255) / 256
        for (k in 0 until warm + count) {
            val t0 = System.nanoTime()
            val pieces = TileOps.cutPieces(b, detectBlank = true)
            val dt = (System.nanoTime() - t0) / 1e6
            assertNotNull(pieces)
            assertEquals(cols * rows, pieces!!.cols * pieces.rows)
            pieces.recycle()
            if (k >= warm) ms[k - warm] = dt
        }
        return median(ms)
    }

    @Test
    fun theCutOfA512Layer() {
        val b = F1Pixels.mixed(512, 512, 61L).also { made += it }
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(4.0), what = "the park cut of a 512 x 512 layer") { cuts(b) }
        println("v110 A8 ParkTimingTest 512x512: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC 4 ms, device 24 ms)".format(ms, ms * 6))
    }

    @Test
    fun theCutOfAPhoneLayer() {
        assumeTrue("the phone-size probe runs off CI", System.getenv("CI") == null)
        val b = F1Pixels.mixed(1080, 2408, 62L).also { made += it }
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(4.0), what = "the park cut of a 1080 x 2408 layer") { cuts(b) }
        println("v110 A8 ParkTimingTest 1080x2408: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC 4 ms, device 24 ms)".format(ms, ms * 6))
    }
}
