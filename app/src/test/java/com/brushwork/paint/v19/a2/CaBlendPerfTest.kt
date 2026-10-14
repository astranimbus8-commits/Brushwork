package com.brushwork.paint.v19.a2

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.CaBlend
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.filters.art.ChromaticAberrationZoomingFilter
import com.brushwork.paint.filters.art.FringeFixtures
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolHistory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * v1.9 (R8 (c), R10 (c)): the time budgets of A2. Pure Kotlin, measured as `FringePerfTest`
 * measures (one thread named as Parallel's workers, a small picture, a long warm-up, the median
 * of interleaved batches; [PerfBudget.measuredRatioWithin] passes at the first of 5 tries).
 *
 * - The blend pass at most 0.4 x the CA pass with a separable mode (Multiply) and 0.6 x with an
 *   HSL mode (Color);
 * - the whole filter at most 1.4 x v1.8 (the same filter at Normal and 100 %, which skips the
 *   pass and is v1.8's code) with Multiply at 60 %, and 1.6 x with Color;
 * - a filter-session checkpoint (the comparison with the current step and the record) at most
 *   0.1 ms, for Curves' values (the largest a filter has).
 *
 * The device rows (Q13: Zooming, Multiply 60 % on PRINT at most 1.4 x; an undo re-renders the
 * preview within 120 ms at the proxy scale) are the phone's to show.
 */
@Category(Timing::class)
class CaBlendPerfTest {
    private val src: PixelBuffer = FringeFixtures.random(192, 144, 42)
    private val ctx = FilterContext()

    private fun medians(a: () -> Unit, b: () -> Unit): Pair<Double, Double> {
        val ta = DoubleArray(BATCHES); val tb = DoubleArray(BATCHES)
        val ra = { repeat(RUNS) { a() } }; val rb = { repeat(RUNS) { b() } }
        for (i in 0 until BATCHES) {
            if (i % 2 == 0) { ta[i] = PerfBudget.timeMs(ra); tb[i] = PerfBudget.timeMs(rb) } else { tb[i] = PerfBudget.timeMs(rb); ta[i] = PerfBudget.timeMs(ra) }
        }
        val ratios = DoubleArray(BATCHES) { ta[it] / tb[it] }.also { it.sort() }
        tb.sort()
        val b50 = tb[BATCHES / 2]
        return ratios[BATCHES / 2] * b50 to b50
    }

    /** [a] within [limit] x [b], on one thread named as Parallel's workers (the rows run serially). */
    private fun ratio(what: String, limit: Double, a: () -> Unit, b: () -> Unit) {
        var err: Throwable? = null
        val t = Thread({
            try {
                repeat(WARM_UP) { a(); b() }
                val (ta, tb) = PerfBudget.measuredRatioWithin(limit, what = what) { medians(a, b) }
                println("CaBlendPerfTest: $what: ${"%.3f".format(ta)} ms vs ${"%.3f".format(tb)} ms (x${"%.3f".format(ta / tb)}, budget x$limit)")
                assertTrue(ta > 0 && tb > 0)
            } catch (e: Throwable) {
                err = e
            }
        }, "bw-parallel-ca-blend-perf")
        t.start(); t.join()
        err?.let { throw it }
    }

    private fun with(values: FilterValues, mode: LayerBlendMode, opacity: Float) =
        values.copy().set(CaBlend.modeParam.key, mode.ordinal).set(CaBlend.opacityParam.key, opacity)

    @Test
    fun theBlendPassAgainstTheCaPass() {
        val m = ChromaticAberrationMovingFilter()
        val v18 = m.defaultValues().set("distance", 12f).set("angle", 30f)
        val aberrated = m.apply(src, v18, ctx)
        val scratch = aberrated.copy()
        for ((mode, limit) in listOf(LayerBlendMode.MULTIPLY to 0.4, LayerBlendMode.COLOR to 0.6)) {
            val values = with(v18, mode, 60f)
            ratio(
                "blend pass, $mode",
                limit,
                { System.arraycopy(aberrated.pixels, 0, scratch.pixels, 0, scratch.pixels.size); CaBlend.over(src, scratch, values, ctx) },
                { m.apply(src, v18, ctx) },
            )
        }
    }

    @Test
    fun theWholeFilterAgainstV18() {
        val m = ChromaticAberrationMovingFilter()
        val z = ChromaticAberrationZoomingFilter()
        val mv = m.defaultValues().set("distance", 12f).set("angle", 30f)
        val zv = z.defaultValues().set("distance", 12f).set("center", floatArrayOf(0.4f, 0.6f))
        for ((mode, limit) in listOf(LayerBlendMode.MULTIPLY to 1.4, LayerBlendMode.COLOR to 1.6)) {
            ratio("Moving, $mode 60 %", limit, { m.apply(src, with(mv, mode, 60f), ctx) }, { m.apply(src, mv, ctx) })
            ratio("Zooming, $mode 60 %", limit, { z.apply(src, with(zv, mode, 60f), ctx) }, { z.apply(src, zv, ctx) })
        }
    }

    @Test
    fun aCheckpointCostsAtMostATenthOfAMillisecond() {
        val curves = com.brushwork.paint.filters.FilterRegistry.all.first { f -> f.params.any { it is FilterParam.Curve } }
        val history = ToolHistory(curves.defaultValues())
        var v = curves.defaultValues()
        val n = 2_000
        repeat(WARM_UP) { FilterSessionMath.sameValues(v, history.current) }
        val ms = PerfBudget.within(PerfBudget.ms(0.1 * n), what = "$n checkpoints") {
            repeat(n) { i ->
                v = v.copy().set(curves.params.first().key, curves.params.first().defaultValue().let { d -> if (d is Float) d + (i % 7) else d })
                if (!FilterSessionMath.sameValues(v, history.current)) history.record(v)
            }
        }
        println("CaBlendPerfTest: a checkpoint ${"%.4f".format(ms / n)} ms")
    }

    private companion object {
        const val WARM_UP = 400
        const val BATCHES = 7
        const val RUNS = 20
    }
}
