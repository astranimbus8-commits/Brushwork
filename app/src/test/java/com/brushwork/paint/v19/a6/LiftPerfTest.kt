package com.brushwork.paint.v19.a6

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A6 step 7: timing guards of drawing and erasing (P1, P2; design 2.7). Each guard is the
 * median of several strokes on the P0 bench's canvas setup ([A6Bench]): a frame after every two
 * touch events, the lift timed by its PerfTrace span.
 *
 * On a 512 px canvas (every run): the brush's lift and the raster Eraser's frame stay within
 * desktop budgets (x4 on CI). On the T606-size canvases (local runs only): the PRINT lift within
 * 2.7's 40 ms at the T606 factor 6 (Q5), and the raster Eraser's frame on PHONE within 1.5x A6's
 * checkpoint value (6.23 ms at 8095ba88), so P2's gain does not wear away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h803dp-xxhdpi")
@Category(Timing::class)
class LiftPerfTest {

    /** A small screen for the 512 px canvas (fit about 0.95): the frame is the canvas's, not the screen's. */
    private fun small() = A6Bench(512, 512, layers = 8, active = 4, screenW = 540, screenH = 1204, freeH = 923)

    @Test
    fun aBrushLiftOnA512CanvasStaysWithinItsBudget() {
        small().use { b ->
            b.c.selectTool(ToolId.BRUSH)
            b.c.brush = BrushLibrary.byId("hardround")!!.copy(size = 40f)
            b.fit()
            val pts = A6Bench.serpentine(b.visibleArea(), 2400f)
            repeat(2) { b.strokeRun(pts) }
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(LIFT_512_MS), what = "brush.lift, 512 px, 8 layers") {
                A6Bench.median(List(5) { b.strokeRun(pts).second }).toDouble()
            }
            println("[a6] brush.lift 512: $ms ms")
        }
    }

    @Test
    fun theRasterEraserFrameOnA512CanvasStaysWithinItsBudget() {
        small().use { b ->
            b.c.toggleEraser()
            assertEquals(ToolId.ERASER, b.c.activeToolId)
            b.c.eraser = BrushLibrary.byId("softeraser")!!.copy(size = 90f)
            b.fit()
            val pts = A6Bench.serpentine(b.visibleArea(), 2400f)
            repeat(2) { b.strokeRun(pts) }
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(ERASE_FRAME_512_MS), what = "frame.erase, 512 px, 8 layers") {
                A6Bench.median(List(5) { b.strokeRun(pts).first }).toDouble()
            }
            println("[a6] frame.erase 512: $ms ms")
        }
    }

    /** Q5: the lift on PRINT (3000 x 4000, 3 layers) at the app's fit, x6, within 40 ms. Local only. */
    @Test
    fun aPrintLiftIsWithinQ5() {
        assumeTrue("a T606-size probe: local runs only", System.getenv("CI") == null)
        A6Bench(3000, 4000, layers = 3, active = 1).use { b ->
            b.c.selectTool(ToolId.BRUSH)
            b.c.brush = BrushLibrary.byId("hardround")!!.copy(size = 40f)
            b.fit()
            val pts = A6Bench.serpentine(b.visibleArea(), STROKE_LENGTH)
            repeat(2) { b.strokeRun(pts) }
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(Q5_LIFT_MS / T606_FACTOR), tries = 3, what = "brush.lift, PRINT") {
                A6Bench.median(List(5) { b.strokeRun(pts).second }).toDouble()
            }
            println("[a6] brush.lift PRINT: $ms ms")
        }
    }

    /** The raster Eraser's frame on PHONE (1080 x 2408, 20 layers) at the app's fit. Local only. */
    @Test
    fun aPhoneEraseFrameKeepsTheCheckpoint() {
        assumeTrue("a T606-size probe: local runs only", System.getenv("CI") == null)
        A6Bench(1080, 2408, layers = 20, active = 10).use { b ->
            b.c.toggleEraser()
            b.c.eraser = BrushLibrary.byId("softeraser")!!.copy(size = 90f)
            b.fit()
            val pts = A6Bench.serpentine(b.visibleArea(), STROKE_LENGTH)
            repeat(2) { b.strokeRun(pts) }
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(CHECKPOINT_ERASE_FRAME_PHONE_MS * 1.5), tries = 3, what = "frame.erase, PHONE") {
                A6Bench.median(List(5) { b.strokeRun(pts).first }).toDouble()
            }
            println("[a6] frame.erase PHONE: $ms ms")
        }
    }

    private companion object {
        const val LIFT_512_MS = 6.0
        const val ERASE_FRAME_512_MS = 6.0
        /** Q5 (design 6): the lift at most 40 ms on PRINT on the T606, taken as 6x this PC (LA1). */
        const val Q5_LIFT_MS = 40.0
        const val T606_FACTOR = 6.0
        /** frame.erase PHONE at A6's checkpoint (a6-after-p2, `.wt/v19-p0.txt`). */
        const val CHECKPOINT_ERASE_FRAME_PHONE_MS = 6.23
        /** The bench's 30 cm stroke at 350 dpi. */
        const val STROKE_LENGTH = 4134f
    }
}
