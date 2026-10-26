package com.brushwork.paint.v110.a9

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.live.AboveProjection
import com.brushwork.paint.engine.live.ProjectionRules
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.ProjectionOracle
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A9 step 7 (design 2.5 X5, 2.17): the stroke frame that builds one display tile of the above
 * projection at N = 200, the active layer at index 10 (189 Normal units above): the dab's part is
 * the below projection, the active layer and the above units composited once into the cache
 * (`Compositor.drawSplit(u, n)` over a whole 256 px display tile), then drawn over. At most one
 * tile is built per update, so this is the largest frame the projection adds to a stroke. Budget:
 * self-chosen (no design budget), the PC median of 15 runs after 3 warm-ups times 6. The frames
 * after it (the cache built) are printed for comparison; the lift is exact.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class AboveBuildTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    @Before
    fun aboveOn() {
        A9Docs.setAboveProjection(true)
    }

    @Test
    fun oneAboveTileBuildsQuickly() {
        val kit = StrokeFrameKit(WaitingTimingKit.stack(N, 177L), ACTIVE)
        val above = AboveProjection({ kit.doc }, { kit.active }, { null })
        try {
            val u = ProjectionRules.aboveStart(kit.doc, kit.active)
            assertEquals(N - ACTIVE - 1, ProjectionRules.unitsAbove(kit.doc, u))
            assertTrue("eligible", ProjectionRules.aboveEligible(kit.doc, kit.active, u))
            above.install(kit.tiles)
            val paint = Paint().apply { isAntiAlias = true }
            var step = 0
            // A dab inside display tile 0: its part renders through the projection.
            val frame = {
                val b = (kit.active.pixels as FlatPixels).bitmap
                val x = 40 + (step * 37) % 176
                val y = 40 + (step * 53) % 176
                step++
                paint.color = Color.argb(200, step * 31 and 255, step * 17 and 255, 90)
                Canvas(b).drawCircle(x.toFloat(), y.toFloat(), 12f, paint)
                kit.tiles.invalidate(Rect(x - 14, y - 14, x + 14, y + 14))
                kit.tiles.gestureDown = true
                kit.tiles.update(kit.compositor)
                kit.tiles.gestureDown = false
            }
            val build = {
                above.dropAll()
                frame()
            }
            // Counted per try: PerfBudget.measuredWithin measures again when a try is over the budget.
            var built = 0L
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "above projection, one display tile built, N = 200") {
                val before = above.builds
                repeat(3) { build() }
                val times = DoubleArray(15) { PerfBudget.timeMs(build) }
                built = above.builds - before
                times.sort()
                times[times.size / 2]
            }
            println("AboveBuildTimingTest: median $ms ms (budget ${PerfBudget.ms(BUDGET_MS)} ms, self-chosen)")
            assertEquals("every frame of the try built one tile", 18L, built)
            // The frames after the build, for comparison (no budget).
            repeat(3) { frame() }
            val hits = above.hits
            val after = DoubleArray(15) { PerfBudget.timeMs(frame) }.also { it.sort() }[7]
            assertTrue("the frames after it drew through the projection", above.hits > hits)
            println("AboveBuildTimingTest: stroke frame with the tile built, median $after ms")
            // The lift: exact.
            kit.tiles.exactAfterGesture()
            kit.tiles.projectionsOff = true
            kit.tiles.update(kit.compositor)
            kit.tiles.projectionsOff = false
            val expected = ProjectionOracle.frame(kit.doc, display = true)
            A9Docs.assertBitEqual("the display after the lift against the oracle", expected, WaitingTimingKit.shown(kit.tiles))
            expected.recycle()
        } finally {
            above.freeAll()
            kit.release()
        }
    }

    /**
     * The first update after a gesture that drew through the projection on all 4 display tiles of
     * the 512 px document (a scribble whose dabs reach every edge of every tile): every part the
     * projection drew is rendered again exactly with the projections off (X5, I33), here the whole
     * document at N = 200. The stroke frames are not timed; only the lift is.
     */
    @Test
    fun liftAfterFourTileStroke() {
        val kit = StrokeFrameKit(WaitingTimingKit.stack(N, 177L), ACTIVE)
        val above = AboveProjection({ kit.doc }, { kit.active }, { null })
        try {
            above.install(kit.tiles)
            val paint = Paint().apply { isAntiAlias = true }
            var step = 0
            val stroke = {
                for (y in DABS) for (x in DABS) {
                    val b = (kit.active.pixels as FlatPixels).bitmap
                    step++
                    paint.color = Color.argb(200, step * 31 and 255, step * 17 and 255, 90)
                    Canvas(b).drawCircle(x.toFloat(), y.toFloat(), 12f, paint)
                    kit.tiles.invalidate(Rect(x - 14, y - 14, x + 14, y + 14))
                    kit.tiles.gestureDown = true
                    kit.tiles.update(kit.compositor)
                    kit.tiles.gestureDown = false
                }
            }
            val lift = {
                kit.tiles.exactAfterGesture()
                kit.tiles.projectionsOff = true
                kit.tiles.update(kit.compositor)
                kit.tiles.projectionsOff = false
            }
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(LIFT_BUDGET_MS), what = "the lift after a 4-tile stroke, N = 200") {
                repeat(3) { stroke(); lift() }
                val times = DoubleArray(15) {
                    stroke()
                    assertTrue("the stroke drew through the projection on every tile", (0 until kit.tiles.tileCount).all { kit.tiles.approxPart(it) != null })
                    PerfBudget.timeMs(lift)
                }
                times.sort()
                times[times.size / 2]
            }
            println("AboveBuildTimingTest: lift after a 4-tile stroke, median $ms ms (budget ${PerfBudget.ms(LIFT_BUDGET_MS)} ms, self-chosen)")
            assertTrue("nothing approximate after the lift", !kit.tiles.hasApprox)
            val expected = ProjectionOracle.frame(kit.doc, display = true)
            A9Docs.assertBitEqual("the display after the lift against the oracle", expected, WaitingTimingKit.shown(kit.tiles))
            expected.recycle()
        } finally {
            above.freeAll()
            kit.release()
        }
    }

    private companion object {
        const val N = 200
        const val ACTIVE = 10

        /** Self-chosen (no design budget): the frame that builds one 256 px tile stays under 48 ms at x6 (PC median about 2.5-3.7 ms). */
        const val BUDGET_MS = 8.0

        /** Self-chosen (no design budget): the lift after a stroke over 4 x 256 px at N = 200 stays under 180 ms at x6 (PC median about 17 ms). */
        const val LIFT_BUDGET_MS = 30.0

        /** Dab centres reaching every edge of the 4 display tiles of 256 px (radius 12, parts of 28 px). */
        val DABS = intArrayOf(14, 242, 270, 498)
    }
}
