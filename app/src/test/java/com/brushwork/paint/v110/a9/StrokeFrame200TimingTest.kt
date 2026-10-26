package com.brushwork.paint.v110.a9

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.live.AboveProjection
import com.brushwork.paint.engine.live.ProjectionRules
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.ProjectionOracle
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * Step 7 (review): the same frame on layer 100, as the device check Q7 paints (design 7), with
 * both projections; same budget.
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

    /**
     * Q7's frame (design 7: check-200, paint on layer 100) with both projections (X4, X5): the
     * active layer at index 100 of 200, the 99 units below drawn from the below projection and the
     * 99 above from the above projection (the check's switch on, the finger down). Every display
     * tile of both is built before the frames are timed, so a frame is the two copies and the
     * active layer over its 256 x 256 dirty area. Budget: the design's 2.3 ms for "N = 200,
     * projections". The lift is exact.
     */
    @Test
    fun aStrokeFrameOnLayer100WithBothProjectionsIsQuick() {
        A9Docs.setAboveProjection(true)
        val kit = StrokeFrameKit(WaitingTimingKit.stack(N, 201L), MIDDLE)
        val above = AboveProjection({ kit.doc }, { kit.active }, { null })
        try {
            val u = ProjectionRules.aboveStart(kit.doc, kit.active)
            assertEquals(N - MIDDLE - 1, ProjectionRules.unitsAbove(kit.doc, u))
            assertTrue("eligible", ProjectionRules.aboveEligible(kit.doc, kit.active, u))
            above.install(kit.tiles)
            kit.tiles.gestureDown = true
            // One above tile is built per update: build them all before the timing.
            var frames = 0
            while (above.builds < kit.tiles.tileCount && frames++ < 40) kit.frame()
            assertEquals("every display tile of the above projection built", kit.tiles.tileCount.toLong(), above.builds)
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "stroke frame on layer 100 of 200, both projections") { kit.medianFrameMs(PerfBudget::timeMs) }
            println("StrokeFrame200TimingTest: layer 100, both projections, median $ms ms (budget ${PerfBudget.ms(BUDGET_MS)} ms)")
            assertTrue("the frames drew from the below projection", kit.projection.hits > 0)
            assertTrue("the frames drew through the above projection", above.hits > 0)
            assertEquals("nothing was built again while timed", kit.tiles.tileCount.toLong(), above.builds)
            kit.tiles.gestureDown = false
            // The lift: exact.
            kit.tiles.exactAfterGesture()
            kit.tiles.projectionsOff = true
            kit.tiles.update(kit.compositor)
            kit.tiles.projectionsOff = false
            assertFalse("nothing approximate after the lift", kit.tiles.hasApprox)
            val expected = ProjectionOracle.frame(kit.doc, display = true)
            A9Docs.assertBitEqual("the display after the lift against the oracle", expected, WaitingTimingKit.shown(kit.tiles))
            expected.recycle()
        } finally {
            kit.tiles.gestureDown = false
            above.freeAll()
            kit.release()
        }
    }

    private companion object {
        const val N = 200

        /** Q7 paints on layer 100. */
        const val MIDDLE = 100

        /** Design 2.17: 2.3 ms on the PC. */
        const val BUDGET_MS = 2.3
    }
}
