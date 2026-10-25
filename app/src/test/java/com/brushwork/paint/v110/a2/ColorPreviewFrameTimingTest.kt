package com.brushwork.paint.v110.a2

import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.composite
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.math.roundToInt

/**
 * v1.10 (R2 (c), R7 (c); design 4.4 A2 step 8, section 5 "Color preview frame"): a frame of the
 * Object colors sheet's live preview on a reopened shape outlined with the brush and drawn with 8
 * mirror copies (a kaleidoscope of 4 divisions), against the design's 5.5 ms on a desktop (33 ms
 * on the phone: the T606 proxy is 6 times the desktop; [PerfBudget] scales it on CI).
 *
 * A frame is what the sheet's hue drag does once a frame (the picker's previews are conflated to
 * one a frame): one `preview` of a new color, its state delivered to the observers, the work the
 * looper then runs (a brush replay) and one draw of the canvas. Each figure is the median of 15
 * frames after 3 warm-ups:
 * - the control, a drag frame of the same open shape (a finger moving it by 2 px): R2 (c) says a
 *   preview frame costs what a drag frame costs;
 * - the fill color: plain items in the live layer, no replay; at most 5.5 ms;
 * - the stroke color: the brush stroke starts again in the new color (the brush preview's key
 *   holds the color) and, having copies, is replayed exactly (`BrushTool.beginPath` ignores the
 *   draft budget for a stroke with copies): about a drag frame of this harness, where the replay
 *   of a drag is not held back (its cost is read on the looper's clock, which stands still), at
 *   most 1.25 times it. The design's absolute 5.5 ms does not hold for this stroke through the
 *   v1.8 brush engine (a lead request; ARCHITECTURE A2);
 * - printed only, the stroke color of the same shape outlined with a plain line.
 * Afterwards the canvas shows the last stroke color.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class ColorPreviewFrameTimingTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    /** Runs the main looper for [ms] (the brush preview replays there). */
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun line(what: String, ms: Double) = println("ColorPreviewFrameTimingTest $what: median ${"%.3f".format(ms)} ms (x6 ${"%.3f".format(ms * 6)} ms)")

    @Test
    fun aColorPreviewFrameOfABrushOutlinedShapeWith8CopiesCostsADragFrame() {
        val c = rig.controller(512, 512)
        c.snapping.enabled = false
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 4))
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE_FILL, fillColor = GREEN, strokeWith = ShapeStroke.BRUSH, editable = true)
        }
        // An ellipse off the centre (300..420 x 120..220): its copies go round the canvas.
        c.drag(300f to 120f, 360f to 170f, 420f to 220f)
        idle(SETTLE_MS)
        tool.commit()
        idle(SETTLE_MS)
        val layer = c.activeLayer
        val placed = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "a shape layer" }
        assertEquals("8 mirror copies", 8, placed.copies.maps.size)
        assertEquals("outlined with the brush", ShapeStroke.BRUSH, placed.strokeWith)

        c.tap(360f, 170f)
        idle(SETTLE_MS)
        assertSame("reopened", layer, tool.editingLayer)
        val ed = requireNotNull(tool.objectColors) { "an open shape has colors" }
        assertNotNull("the outline shows live in the layer", c.renderOverride)
        // The canvas's draw of every frame (one bitmap for them all).
        val screen = Canvas(BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height))
        fun drawCanvas() = c.compositor.drawDocument(screen, null, target = null)

        // ---- The control: a drag frame of the open shape, back and forth by 2 px once the
        // finger has moved past the touch slop.
        c.pointerDown(ToolPoint(360f, 170f))
        c.pointerMove(ToolPoint(370f, 170f))
        idle(SETTLE_MS)
        val grab = 380f
        var x = grab
        c.pointerMove(ToolPoint(x, 170f))
        idle(SETTLE_MS)
        fun dragFrame(): Double {
            x = if (x > grab) grab else grab + 2f
            val t0 = System.nanoTime()
            c.pointerMove(ToolPoint(x, 170f))
            frame()
            idle(FRAME_MS)
            drawCanvas()
            return (System.nanoTime() - t0) / 1e6
        }
        repeat(3) { dragFrame() }
        val drag = median(List(15) { dragFrame() })
        assertTrue("the drag moved the shape", requireNotNull(tool.pendingObject()).cx > 370f)
        c.pointerUp(ToolPoint(x, 170f))
        idle(REST_MS)
        line("drag frame (the control)", drag)

        // ---- The previews.
        ed.begin()
        var k = 0
        fun previewFrame(t: ObjectColorTarget): Double {
            // Every frame a new color, as a finger on the hue gives.
            val argb = if (k++ % 2 == 0) BLUE else RED
            val t0 = System.nanoTime()
            ed.preview(t, argb)
            frame()
            idle(FRAME_MS)
            drawCanvas()
            return (System.nanoTime() - t0) / 1e6
        }
        repeat(3) { previewFrame(ObjectColorTarget.FILL) }
        val fill = PerfBudget.measuredWithin(PerfBudget.ms(5.5), what = "a fill color preview frame, 8 copies") {
            median(List(15) { previewFrame(ObjectColorTarget.FILL) })
        }
        line("fill", fill)
        repeat(3) { previewFrame(ObjectColorTarget.STROKE) }
        val stroke = PerfBudget.measuredWithin(drag * DRAG_FACTOR, what = "a stroke color preview frame, 8 copies (at most $DRAG_FACTOR drag frames)") {
            median(List(15) { previewFrame(ObjectColorTarget.STROKE) })
        }
        line("stroke (over 5.5 ms: the v1.8 brush engine's exact replay of 8 copies)", stroke)

        // Once the stroke rests, the canvas shows its last color (left of the fill, on the outline).
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        frame()
        idle(REST_MS)
        assertEquals(ObjectColorFace.Solid(BLUE), ed.face(ObjectColorTarget.STROKE))
        val o = requireNotNull(tool.pendingObject())
        val p = c.composite().getPixel((o.cx - o.w / 2f - 1f).roundToInt(), o.cy.roundToInt())
        assertTrue("the outline is blue: %08X".format(p), Color.blue(p) > 160 && Color.red(p) < 96 && Color.green(p) < 96)

        // ---- Printed only: the same shape outlined with a plain line.
        tool.update { it.copy(strokeWith = ShapeStroke.PLAIN) }
        idle(SETTLE_MS)
        repeat(3) { previewFrame(ObjectColorTarget.STROKE) }
        line("stroke, a plain outline", median(List(15) { previewFrame(ObjectColorTarget.STROKE) }))

        ed.cancel()
        tool.discard()
        idle(SETTLE_MS)
    }

    private companion object {
        /** One 60 Hz frame and a bit: the brush preview's replay waits at least 16 ms after the last. */
        const val FRAME_MS = 17L

        /** Long enough for a brush preview to replay (as `ShapeCopiesKit.idle`). */
        const val SETTLE_MS = 300L

        /** Past the brush preview's refinement delay (200 ms) and its refinements. */
        const val REST_MS = 2_000L

        /** A stroke color preview frame costs at most this many drag frames (R2 (c): about one). */
        const val DRAG_FACTOR = 1.25
    }
}
