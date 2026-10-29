package com.brushwork.paint.v110.g2vector

import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.v110.a2.A2ToolRig
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.composite
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.10 G2 (V3, Q10; design section 5 "Color preview frame"): a frame of the Object colors
 * sheet's stroke color preview on an open Curve path with 8 kaleidoscope copies on a raster layer,
 * against the design's 5.5 ms on a desktop (33 ms on the phone: the T606 proxy is 6 times the
 * desktop; [PerfBudget] scales it on CI). As `ColorPreviewFrameTimingTest` measures it for a shape:
 * one `preview` of a new color, its state delivered to the observers, the looper's work and one
 * draw of the canvas; the median of 15 frames after 3 warm-ups.
 * - Drawn with the brush: the live stroke takes the color without a replay (`BrushTool.recolorPath`;
 *   v1.10 A2 replayed the 8 copies from scratch every frame).
 * - Drawn with a plain line: the path and its copies are drawn once a frame (the Path tool's
 *   observer of the main color no longer draws them a second time).
 * Afterwards the canvas shows the last stroke color.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class G2VectorPathColorPreviewTimingTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    /** Runs the main looper for [ms] (the brush preview replays there). */
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun line(what: String, ms: Double) = println("G2VectorPathColorPreviewTimingTest $what: median ${"%.3f".format(ms)} ms (x6 ${"%.3f".format(ms * 6)} ms)")

    @Test
    fun aStrokeColorPreviewFrameOfAPathWith8CopiesFitsTheFrameBudget() {
        val c = rig.controller(512, 512)
        c.snapping.enabled = false
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 4))
        c.selectTool(ToolId.CURVE)
        val t = c.tools.getValue(ToolId.CURVE) as CurveTool
        t.update { it.copy(stroke = CurveStroke.BRUSH, fill = false, closed = false) }
        // A path off the centre: its copies go round the canvas.
        for (p in listOf(Vec2(290f, 110f), Vec2(360f, 140f), Vec2(400f, 190f), Vec2(430f, 260f))) assertTrue(t.addAnchor(p))
        idle(SETTLE_MS)
        t.flushPreview()
        assertTrue("the brush stroke shows live", t.brushLive)
        assertEquals("8 copies", 8, t.copyMapsNow.size)
        val ed = requireNotNull(t.objectColors) { "an open path has colors" }
        val screen = Canvas(BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height))
        fun drawCanvas() = c.compositor.drawDocument(screen, null, target = null)

        ed.begin()
        var k = 0
        fun previewFrame(): Double {
            // Every frame a new color, as a finger on the hue gives.
            val argb = if (k++ % 2 == 0) BLUE else RED
            val t0 = System.nanoTime()
            ed.preview(ObjectColorTarget.STROKE, argb)
            frame()
            idle(FRAME_MS)
            drawCanvas()
            return (System.nanoTime() - t0) / 1e6
        }
        repeat(3) { previewFrame() }
        val brush = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "a stroke color preview frame of a brush path, 8 copies") {
            median(List(15) { previewFrame() })
        }
        line("brush path, 8 copies", brush)

        // Once the stroke rests, the canvas shows its last color (on the path's middle point).
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        frame()
        idle(REST_MS)
        assertTrue("still the live stroke", t.brushLive)
        val p = c.composite().getPixel(400, 190)
        assertTrue("the stroke is blue: %08X".format(p), Color.blue(p) > 160 && Color.red(p) < 96 && Color.green(p) < 96)

        // ---- The same path drawn with a plain line.
        t.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 6f, useBrushSize = false) }
        idle(SETTLE_MS)
        assertTrue("the path shows its copies", t.copiesShown)
        repeat(3) { previewFrame() }
        val plain = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "a stroke color preview frame of a plain path, 8 copies") {
            median(List(15) { previewFrame() })
        }
        line("plain path, 8 copies", plain)

        ed.cancel()
        t.discard()
        idle(SETTLE_MS)
    }

    private companion object {
        /** The design's color preview frame on a desktop (33 ms on the T606). */
        const val BUDGET_MS = 5.5

        /** One 60 Hz frame and a bit: the brush preview's replay waits at least 16 ms after the last. */
        const val FRAME_MS = 17L

        /** Long enough for a brush preview to replay. */
        const val SETTLE_MS = 300L

        /** Past the brush preview's refinement delay (200 ms) and its refinements. */
        const val REST_MS = 2_000L
    }
}
