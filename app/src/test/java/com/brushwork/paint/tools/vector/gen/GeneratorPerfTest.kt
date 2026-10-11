package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 E (design §3.7 c "T606 budgets", §6.3 Q1 and Q10): what the curve generators cost on the
 * JVM, against the design's budgets (scaled by [PerfBudget.ms] on CI):
 * - a build of at most [CurveGenerators.MAX_ANCHORS] anchors ≤ 5 ms, every kind, Curve and Path,
 *   at its densest (most turns, waves or petals, the most points per turn);
 * - a slider drag ≤ 8 ms per frame: the median frame of a "Turns" drag (a rose: "Petals"), one
 *   change of the open curve through [CurveTool.changeGenerator] inside one numeric edit, and its
 *   outline (the geometry only: drawing the frame on the T606 is the device check Q10);
 * - the I16 check on reopen ([CurveGenerators.matches]) ≤ 2 ms on the densest curve.
 * The T606-size probes (a 2048 px canvas: a 614 px spiral; Q1's Kaleidoscope 8 commit of a
 * generated curve on a vector layer) run locally only; the T606's own times are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class GeneratorPerfTest {

    /** [kind] at its densest, [size] px. */
    private fun densest(kind: CurveGenKind, size: Float, density: Int = CurveGenerator.MAX_DENSITY) = CurveGenerator(
        kind = kind, cx = 200f, cy = 150f, size = size, angleDeg = 10f, turns = CurveGenerator.MAX_TURNS, inner = 0.1f,
        waves = 50f, amplitude = size * 0.2f, petalsN = 23, petalsD = 12, density = density,
    )

    private fun path(g: CurveGenerator, kind: CurveKind): VPath {
        val b = CurveGenerators.build(g, kind)
        return VPath(1, subpaths = listOf(b.subpath), stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 3f), spline = b.spline, generator = g)
    }

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

    private fun assertBuilds(size: Float, budgetMs: Double, where: String) {
        for (kind in CurveGenKind.entries) for (k in listOf(CurveKind.CURVE, CurveKind.PATH)) {
            val g = densest(kind, size)
            val n = CurveGenerators.build(g, k).subpath.anchors.size
            assertTrue("$where $kind / $k: $n anchors, at most ${CurveGenerators.MAX_ANCHORS}", n <= CurveGenerators.MAX_ANCHORS + 1)
            PerfBudget.within(budgetMs, what = "$where: build $kind / $k ($n anchors)") { CurveGenerators.build(g, k) }
        }
    }

    @Test
    fun aBuildOfAtMost400AnchorsTakesAtMost5ms() = assertBuilds(140f, PerfBudget.ms(5.0), "140 px")

    @Test
    fun theReopenCheckTakesAtMost2ms() {
        for (kind in CurveGenKind.entries) for (k in listOf(CurveKind.CURVE, CurveKind.PATH)) {
            val p = path(densest(kind, 140f), k)
            assertTrue("$kind / $k matches", CurveGenerators.matches(p))
            PerfBudget.within(PerfBudget.ms(2.0), what = "the I16 check, $kind / $k") { CurveGenerators.matches(p) }
        }
    }

    /** Frame [i] of a slider drag from [start]: "Turns" (a rose ignores the turns: "Petals"). */
    private fun dragged(start: CurveGenerator, i: Int): CurveGenerator =
        if (start.kind == CurveGenKind.ROSE) start.copy(petalsN = 2 + i % 20) else start.copy(turns = start.turns + i * 0.25f)

    /**
     * The median frame (ms) of a slider drag of [frames] changes ([dragged]) on [t]'s open generated
     * curve, each with its outline: the frame's geometry only (drawing it is the device check Q10).
     */
    private fun sliderDrag(t: CurveTool, frames: Int = 40): Double {
        val start = t.generator!!
        val times = ArrayList<Double>(frames)
        t.beginNumericEdit()
        for (i in 1..frames) {
            val next = dragged(start, i)
            times += PerfBudget.timeMs {
                t.changeGenerator(next)
                t.path().flatten(0.25f)
            }
        }
        t.endNumericEdit()
        assertEquals(dragged(start, frames), t.generator)
        // ONE in-tool step: one undo is the drag.
        assertTrue(t.undoStep())
        assertEquals(start, t.generator)
        return median(times)
    }

    @Test
    fun aSliderDragFrameTakesAtMost8ms() {
        val c = controller()
        for ((id, kind) in listOf(ToolId.CURVE to CurveGenKind.GOLDEN, ToolId.PATH to CurveGenKind.GOLDEN, ToolId.CURVE to CurveGenKind.ROSE)) {
            val t = c.tool(id)
            t.plainLine()
            assertTrue(t.startGenerator(kind, null))
            PerfBudget.measuredWithin(PerfBudget.ms(8.0), what = "a slider drag frame, $id / $kind") { sliderDrag(t) }
            t.discard()
        }
    }

    /** T606 size, locally only: the 2048 px canvas's spirals, Q10's Turns drag and Q1's commit. */
    @Test
    fun t606SizeProbes() {
        assumeTrue("T606-size probes run locally only", System.getenv("CI") == null)
        val reach = 0.3f * 2048f
        assertBuilds(reach, 5.0, "T606 size, $reach px")
        // Q10: a Golden spiral, "Turns" drag, ≤ 8 ms per frame.
        val c = controller()
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        assertTrue(t.startGenerator(CurveGenKind.GOLDEN, null))
        t.changeGenerator(t.generator!!.copy(size = reach))
        PerfBudget.measuredWithin(8.0, what = "Q10 at T606 size") { sliderDrag(t) }
        t.discard()
        // Q1: Kaleidoscope 8, a generated curve of about 300 anchors committed on a vector layer, ≤ 50 ms.
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 8))
        PerfBudget.measuredWithin(50.0, what = "Q1: a Kaleidoscope 8 commit") { _ ->
            assertTrue(t.startGenerator(CurveGenKind.ARCHIMEDEAN, null))
            t.changeGenerator(t.generator!!.copy(turns = 12f, size = 140f, density = 25))
            assertTrue("about 300 anchors: ${t.pointCount}", t.pointCount in 150..CurveGenerators.MAX_ANCHORS)
            assertTrue("copies", t.hasCopies)
            PerfBudget.timeMs { t.commit() }
        }
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.OFF))
    }
}
