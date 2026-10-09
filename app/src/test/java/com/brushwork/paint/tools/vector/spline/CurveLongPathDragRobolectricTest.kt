package com.brushwork.paint.tools.vector.spline

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.H
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.W
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sin

/**
 * v1.7 (item 1, design §3.1c and §6.3): a group gesture on a long path. While it drags more than
 * [CurveTool.LIVE_OUTLINE_MAX_POINTS] points, a plain line of varying thickness shows its centre
 * line only, and its outline again once the finger lifts; a shorter path keeps its outline. The
 * highlight of 500 selected points and their gizmo add next to nothing to the v1.6 overlay (the
 * phone's budget for both is 0.5 ms a frame, a device row; Robolectric draws in software).
 */
@RunWith(RobolectricTestRunner::class)
class CurveLongPathDragRobolectricTest {

    /** A Curve plain line of [n] anchors across the document, every other one at 50 % thickness. */
    private fun curve(n: Int): CurveTool {
        val c = controller()
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        for (i in 0 until n) {
            val x = 10f + (W - 20f) * i / (n - 1)
            assertTrue(t.addAnchor(Vec2(x, H / 2f + 80f * sin(i * 0.3f))))
        }
        val odd = (1 until n step 2).toList()
        t.setWidths(odd, FloatArray(odd.size) { 0.5f })
        assertFalse(t.uniformWidth)
        return t
    }

    @Test
    fun aLongPathDraggedAsAGroupShowsItsCentreLineUntilTheFingerLifts() {
        val t = curve(CurveTool.LIVE_OUTLINE_MAX_POINTS + 100)
        t.selectPoints(PointSelection.all(t.pointCount))
        assertFalse("at rest: the outline", t.centreLineOnly)
        t.beginGroupEdit("Move")
        t.setGroupTransform(Affine2.translate(5f, -3f))
        assertTrue("dragged: the centre line", t.centreLineOnly)
        t.endGroupEdit()
        assertFalse("the finger lifted: the outline again", t.centreLineOnly)
        t.discard()

        val short = curve(CurveTool.LIVE_OUTLINE_MAX_POINTS - 100)
        short.selectPoints(PointSelection.all(short.pointCount))
        short.beginGroupEdit("Move")
        short.setGroupTransform(Affine2.translate(5f, -3f))
        assertFalse("a shorter path keeps its outline", short.centreLineOnly)
        short.endGroupEdit()
        short.discard()
    }

    /** The median time (ms) of [t]'s overlay with [sel] selected, after a warm-up. */
    private fun overlayMs(t: CurveTool, sel: PointSelection, canvas: Canvas): Double {
        t.selectPoints(sel)
        val view = t.controller.viewTransform
        repeat(20) { t.drawOverlay(canvas, view) }
        return DoubleArray(21) {
            val t0 = System.nanoTime()
            t.drawOverlay(canvas, view)
            (System.nanoTime() - t0) / 1e6
        }.sorted()[10]
    }

    @Test
    fun theHighlightOf500PointsAndTheGizmoAddNextToNothingToTheOverlay() {
        val t = curve(600)
        val canvas = Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888))
        // (Robolectric draws in software: the v1.6 overlay of one selected point is the yardstick.)
        val one = overlayMs(t, PointSelection.of(600, 3), canvas)
        val group = PointSelection.none(600).plusAll((50 until 550).toList())
        assertEquals(500, group.count)
        val ms = overlayMs(t, group, canvas)
        println("600-anchor curve: overlay %.3f ms with one point selected, %.3f ms with 500 and the gizmo (medians)".format(one, ms))
        assertTrue("$ms ms against $one ms", ms <= one * NOISE + PerfBudget.ms(GROUP_OVERLAY_MS))
        t.discard()
    }

    private companion object {
        /** What the highlight and the gizmo may add (the phone's whole overlay budget, §6.3). */
        const val GROUP_OVERLAY_MS = 0.5
        /** Run-to-run spread of the software overlay timing. */
        const val NOISE = 1.5
    }
}
