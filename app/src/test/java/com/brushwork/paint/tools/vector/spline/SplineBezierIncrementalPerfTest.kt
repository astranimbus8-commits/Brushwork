package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.vector.VSpline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import org.junit.experimental.categories.Category

/**
 * v1.6 §3.2c, integration item 27: the worst case the Path tool's area engineer measured — 2000
 * control points, order 6, weights from 0.1 to 10 — dragged one point at a time. Each move goes
 * through [SplineBezier.Incremental] (only the spans the point influences are converted again);
 * a fresh [SplineBezier.toSubpath] converts all of them. The guard is a ratio of medians on the
 * same machine in the same run (absolute times swing with the load of the test machine): the
 * incremental move must take at most a quarter of the fresh conversion. The result is the fresh
 * one (bit for bit: [SplineBezierIncrementalTest]).
 */
@Category(Timing::class)
class SplineBezierIncrementalPerfTest {

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    @Test
    fun aDragMoveOnALongWeightedPathConvertsAFewSpans() {
        val rnd = Random(2000)
        var s = VSpline(SplineTestSupport.randomPoints(rnd, 2000, weights = true, widths = true), order = 6)
        val inc = SplineBezier.Incremental()
        inc.toSubpath(s)
        fun move(k: Int) {
            val i = (k * 397 + 11) % s.points.size
            val p = s.points[i]
            s = s.copy(points = s.points.toMutableList().also { it[i] = p.copy(x = p.x + 0.75f, y = p.y - 0.5f) })
        }
        // Warm-up (the JIT compiles both paths).
        repeat(12) { k ->
            move(k)
            SplineBezier.toSubpath(s)
            inc.toSubpath(s)
        }
        val moves = 31
        // incremental <= fresh / 4 per move, on the medians of one set of moves.
        PerfBudget.measuredRatioWithin(0.25, what = "incremental vs fresh per move") {
            val fresh = DoubleArray(moves)
            val incremental = DoubleArray(moves)
            for (k in 0 until moves) {
                move(100 + k)
                val t0 = System.nanoTime()
                val a = inc.toSubpath(s)
                val t1 = System.nanoTime()
                val b = SplineBezier.toSubpath(s)
                val t2 = System.nanoTime()
                incremental[k] = (t1 - t0) / 1e6
                fresh[k] = (t2 - t1) / 1e6
                assertTrue("move $k converted ${inc.lastConverted} spans", inc.lastConverted in 1..6)
                assertEquals("move $k: the anchors are the fresh ones", b, a)
            }
            val f = median(fresh)
            val i = median(incremental)
            println("2000 points, order 6, weights: fresh %.3f ms, incremental %.3f ms per move (medians of %d)".format(f, i, moves))
            i to f
        }
    }
}
