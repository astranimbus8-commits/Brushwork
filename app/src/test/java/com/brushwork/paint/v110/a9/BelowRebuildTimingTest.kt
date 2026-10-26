package com.brushwork.paint.v110.a9

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.ProjectionOracle
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A9 step 3 (design 2.17): the rebuild of the below projection after an eye toggle on a layer
 * below the active one at N = 200: the key drops the projection and the whole display renders
 * again progressively (budgeted updates), building the projection tile by tile. Budget: 170 ms on
 * the PC (1 s on the device), the PC median of 15 runs after 3 warm-ups. The result is exact.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class BelowRebuildTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    @Test
    fun theBelowProjectionRebuildsQuickly() {
        val kit = StrokeFrameKit(WaitingTimingKit.stack(N, 170L), N - 10)
        try {
            val below = kit.doc.layers[5]
            val rebuild = {
                below.visible = !below.visible
                kit.tiles.invalidate(null)
                WaitingTimingKit.renderAll(kit.tiles, kit.compositor)
                Unit
            }
            val builds = kit.projection.builds
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "below rebuild, N = 200") {
                repeat(3) { rebuild() }
                val times = DoubleArray(15) { PerfBudget.timeMs(rebuild) }
                times.sort()
                times[times.size / 2]
            }
            println("BelowRebuildTimingTest: median $ms ms (budget ${PerfBudget.ms(BUDGET_MS)} ms)")
            assertTrue("the projection was rebuilt", kit.projection.builds > builds)
            val expected = ProjectionOracle.frame(kit.doc, display = true)
            A9Docs.assertBitEqual("the rebuilt display against the oracle", expected, WaitingTimingKit.shown(kit.tiles))
            expected.recycle()
        } finally {
            kit.release()
        }
    }

    private companion object {
        const val N = 200

        /** Design 2.17: 170 ms on the PC. */
        const val BUDGET_MS = 170.0
    }
}
