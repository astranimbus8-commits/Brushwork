package com.brushwork.paint.filters.art

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.FringeFixtures.NONE
import com.brushwork.paint.filters.art.v17.ChromaticAberrationV17
import com.brushwork.paint.filters.art.v17.GlitchV17
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import kotlin.math.hypot
import kotlin.math.max

/**
 * v1.8 (item 5, design §3.6 (c)): the fringe colors' time budgets. Pure Kotlin, no Robolectric.
 *
 * How it measures. Each guard runs on one thread named as Parallel's workers (the rows run
 * serially), on a small picture (192 x 144), warmed up by [WARM_UP] runs of each side so both
 * loops get the JIT's full compile (one run a call over a large picture would leave each loop to
 * on-stack replacement, whose code differs from compile to compile). A try is the median of the
 * ratios of [BATCHES] interleaved batches of [RUNS] runs (each batch pairs both sides back to
 * back); [PerfBudget.measuredRatioWithin] passes at the first of up to 5 tries within the budget.
 *
 * - The fast path: v1.7's loop, byte for byte ([FringeGoldenTest]). Two copies of one loop
 *   compiled apart (the filter's and F0's frozen copy) measured 0.94 to 1.26 x each other here, so
 *   the guard times the filter against ITS OWN fast loop (one compile): the v1.8 dispatch (resolving
 *   the colors, the one-hot test) adds at most 5 %. The ± 5 % against v1.7 is the phone's to show.
 * - The general path (three colors that each feed two or three channels: every sample read in
 *   full, the worst case) at most 1.3 x the frozen v1.7 copy, Moving, Zooming and Glitch.
 * - Glitch with a colored static at most 1.05 x the frozen v1.7 copy.
 *
 * The device row Q9 (Zooming, a custom color set, 4000 x 5000, at most 1.3 x) is checked on the phone.
 */
@Category(Timing::class)
class FringePerfTest {
    private val src: PixelBuffer = FringeFixtures.random(192, 144, 42)
    private val ctx = FilterContext()
    private val custom = intArrayOf(0xFFFF8000.toInt(), 0xFF8040C0.toInt(), 0xFF00A0A0.toInt())
    private val old = FilterValues(mapOf("distance" to 12f, "angle" to 30f, "order" to 0, "center" to floatArrayOf(0.4f, 0.6f)))

    private fun values(f: Filter, vararg kv: Pair<String, Any>): FilterValues = f.defaultValues().apply { for ((k, v) in kv) set(k, v) }

    /**
     * [BATCHES] interleaved batches of [RUNS] runs of [a] and of [b]: the median of the batches'
     * own ratios (a batch's two halves run back to back, so a slow spell of the machine — another
     * JVM, a GC, a throttled core — slows both and cancels out), returned as (that ratio x b's
     * median, b's median) in ms.
     */
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

    /** [a] (v1.8) within [limit] x [b] (the reference), measured as the class comment says. */
    private fun ratio(what: String, limit: Double, a: () -> Unit, b: () -> Unit) {
        var err: Throwable? = null
        val t = Thread({
            try {
                repeat(WARM_UP) { a(); b() }
                val (ta, tb) = PerfBudget.measuredRatioWithin(limit, what = what) {
                    medians(a, b).also { (x, y) -> println("FringePerfTest: $what try: ${"%.2f".format(x)} / ${"%.2f".format(y)} ms") }
                }
                println("FringePerfTest: $what: ${"%.2f".format(ta)} ms vs ${"%.2f".format(tb)} ms (x${"%.3f".format(ta / tb)}, budget x$limit)")
                assertTrue(ta > 0 && tb > 0)
            } catch (e: Throwable) {
                err = e
            }
        }, "bw-parallel-fringe-perf")
        t.start(); t.join()
        err?.let { throw it }
    }

    @Test
    fun theFastPathAddsNothingToV17sLoop() {
        val m = ChromaticAberrationMovingFilter()
        val z = ChromaticAberrationZoomingFilter()
        val dist = 12f
        val vx = ArtMath.unitX(30f) * dist; val vy = ArtMath.unitY(30f) * dist
        val rgb = FringeColors.oneHotFactors(FringeColors.rgb) ?: error("RGB takes the fast path")
        ratio("Moving, RGB", 1.05, { m.apply(src, values(m, "distance" to dist, "angle" to 30f), ctx) }, { m.fast(src, ctx, rgb, vx, vy) })
        val redCyan = FringeColors.PRESETS[1].colors
        val k = FringeColors.oneHotFactors(redCyan) ?: error("Red/Cyan takes the fast path")
        ratio("Moving, Red/Cyan", 1.05, { m.apply(src, values(m, "distance" to dist, "angle" to 30f, FringeColors.KEY to redCyan), ctx) }, { m.fast(src, ctx, k, vx, vy) })
        val w = src.width; val h = src.height
        val cx = 0.4f * w; val cy = 0.6f * h
        val s = dist / max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy)))
        ratio("Zooming, RGB", 1.05, { z.apply(src, values(z, "distance" to dist, "center" to floatArrayOf(0.4f, 0.6f)), ctx) }, { z.fast(src, ctx, rgb, cx, cy, s) })
    }

    @Test
    fun theGeneralPathIsAtMostOnePointThreeTimesV17() {
        val m = ChromaticAberrationMovingFilter()
        val z = ChromaticAberrationZoomingFilter()
        val g = GlitchFilter()
        assertTrue(FringeColors.oneHotFactors(custom) == null)
        ratio("Moving, custom", 1.3, { m.apply(src, values(m, "distance" to 12f, "angle" to 30f, FringeColors.KEY to custom), ctx) }, { ChromaticAberrationV17.moving(src, old, ctx) })
        ratio("Zooming, custom", 1.3, { z.apply(src, values(z, "distance" to 12f, "center" to floatArrayOf(0.4f, 0.6f), FringeColors.KEY to custom), ctx) }, { ChromaticAberrationV17.zooming(src, old, ctx) })
        val gv = values(g, "height" to 6f, "strength" to 100f, "blocks" to 50f, "noise" to 30f, "seed" to 9)
        ratio("Glitch, custom", 1.3, { g.apply(src, gv.copy().set(FringeColors.KEY, custom), ctx) }, { GlitchV17.apply(src, gv, ctx) })
        ratio("Glitch, no middle color", 1.3, { g.apply(src, gv.copy().set(FringeColors.KEY, intArrayOf(custom[0], NONE, custom[2])), ctx) }, { GlitchV17.apply(src, gv, ctx) })
    }

    @Test
    fun aColoredStaticIsAtMostOnePointOhFiveTimesV17() {
        val g = GlitchFilter()
        val gv = values(g, "strength" to 0f, "noise" to 100f, "height" to 4f, "seed" to 3)
        ratio("Glitch, red static", 1.05, { g.apply(src, gv.copy().set(GlitchFilter.STATIC_COLOR_KEY, 0xFFFF0000.toInt()), ctx) }, { GlitchV17.apply(src, gv, ctx) })
    }

    private companion object {
        const val WARM_UP = 800
        const val BATCHES = 7
        const val RUNS = 20
    }
}
