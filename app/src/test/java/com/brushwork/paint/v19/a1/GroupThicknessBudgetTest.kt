package com.brushwork.paint.v19.a1

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.MixedEdit
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.vector.CurveGroupEdit
import com.brushwork.paint.ui.vector.MAX_THICKNESS_PERCENT
import com.brushwork.paint.ui.vector.factors
import com.brushwork.paint.v19.a1.A1Tools.withPoints
import com.brushwork.paint.vector.VSpline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sin

/**
 * v1.9 R1 (c): a frame of the several points' thickness drag ([CurveGroupEdit.dragTo]) on a
 * Polyline of [VSpline.MAX_POINTS] (2 000) points, every one selected and of its own thickness.
 * The values' update ([MixedEdit.dragged] from the captured values, as percent factors) takes at
 * most 2 ms; the whole frame (the tool's `setWidths` and the in-tool preview's path, both v1.8's)
 * is printed for the device row. Medians after a warm-up (the JIT), as `CurveGroupBudgetTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class GroupThicknessBudgetTest {

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    private inline fun frames(n: Int, warm: Int = 15, f: (Int) -> Unit): Double {
        repeat(warm) { f(it) }
        val t = DoubleArray(n)
        for (k in 0 until n) {
            val t0 = System.nanoTime()
            f(warm + k)
            t[k] = (System.nanoTime() - t0) / 1e6
        }
        return median(t)
    }

    /** A plain Polyline of [n] points across the document, its thickness swelling and thinning along it, all selected. */
    private fun polyline(n: Int): CurveTool {
        val pts = List(n) { i -> Vec2(10f + (A1Tools.W - 20f) * i / (n - 1), A1Tools.H / 2f + 80f * sin(i * 0.3f)) }
        val (_, t) = withPoints(ToolId.POLYLINE, pts)
        t.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 4f) }
        t.selectPoints(PointSelection.all(n))
        t.setWidths(t.pointSelection.indices, FloatArray(n) { i -> (1f + 0.5f * sin(i * 0.1f)) })
        t.endNumericEdit()
        return t
    }

    @Test
    fun aDragFrameOf2000PointsUpdatesThemInAtMost2Ms() {
        val n = VSpline.MAX_POINTS
        val t = polyline(n)
        val e = CurveGroupEdit(t, CurveGroupEdit.Kind.THICKNESS)
        val base = e.values()
        val mean = MixedEdit.mean(base)
        var out = FloatArray(0)
        // The values' update of a frame: each point from its captured value, then as factors.
        val update = PerfBudget.measuredWithin(PerfBudget.ms(2.0), what = "2000 points, the values of a drag frame") {
            frames(41) { k -> out = factors(MixedEdit.dragged(base, mean, mean * (1f + 0.01f * (k % 40 + 1)), 0f, MAX_THICKNESS_PERCENT)) }
        }
        println("2000 points, group thickness drag, the values: %.3f ms per frame (median)".format(update))
        assertEquals(n, out.size)
        // The whole frame through the tool (its setWidths and the preview's path): printed, a device row.
        e.begin()
        val whole = frames(21, warm = 5) { k -> e.dragTo(mean * (1f + 0.01f * (k % 40 + 1))) }
        e.end()
        println("2000 points, group thickness drag, the whole frame (setWidths and the preview): %.3f ms (median)".format(whole))
        assertFalse("the drag moved the points", e.values().contentEquals(base))
    }
}
