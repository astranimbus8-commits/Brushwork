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
 * v1.10 A9 step 3 (design 2.17): a stroke frame's composite at N = 200 phone layers with the
 * projections (the active layer 10 from the top, so 190 layers lie below the split and are drawn
 * from the below projection). Budget: 2.3 ms on the PC (14 ms on the device), the PC median of 15
 * frames after 3 warm-ups. The frames stay exact.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class StrokeFrame200TimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    @Test
    fun aStrokeFrameAt200LayersIsQuick() {
        val kit = StrokeFrameKit(WaitingTimingKit.stack(N, 200L), N - 10)
        try {
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "stroke frame, N = 200, projections") { kit.medianFrameMs(PerfBudget::timeMs) }
            println("StrokeFrame200TimingTest: median $ms ms (budget ${PerfBudget.ms(BUDGET_MS)} ms)")
            assertTrue("the frames drew from the below projection", kit.projection.hits > 0)
            val expected = ProjectionOracle.frame(kit.doc, display = true)
            A9Docs.assertBitEqual("the stroke frames against the oracle", expected, WaitingTimingKit.shown(kit.tiles))
            expected.recycle()
        } finally {
            kit.release()
        }
    }

    private companion object {
        const val N = 200

        /** Design 2.17: 2.3 ms on the PC. */
        const val BUDGET_MS = 2.3
    }
}
