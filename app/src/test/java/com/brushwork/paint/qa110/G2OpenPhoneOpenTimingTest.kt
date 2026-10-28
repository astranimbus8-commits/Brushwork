package com.brushwork.paint.qa110

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.storage.LayerEntries
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.v110.a11.A11Kit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 Gate 2 (lane open, O1; design 4.4 A11 step 6): the honest phone-size probe of the open.
 * [LAYERS] painted 1080 x 2408 layers (seeded opaque strokes over about 35 % of each, as the check
 * artworks) open through `ProjectRepository.load` at FULL on a T606 memory probe; the median of 15
 * opens after 3 warm-ups, each loaded document released, gives the cost of one phone layer, which
 * the line printed here scales (x 6 for the T606) to the budgets of design 2.17: 200 layers in 6 s
 * (Q4), the 500-layer check artwork in 15 s (Q2) and the 50-layer 4000 x 5000 check artwork in
 * 15 s (Q6, the pixels scaled from the phone layer). Open200TimingTest's 512 x 512 proxy is about
 * a tenth of one phone layer. Runs locally only (CI's shared runners are not a phone's proxy).
 * The same opens with one decoder (`LayerEntries.maxDecoders = 1`, the open before O1) are
 * measured first in the same run and printed beside them, so before and after share the machine's
 * load.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class G2OpenPhoneOpenTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606))

    @Before
    fun notOnCi() = assumeTrue("T606-size probes run locally only", System.getenv("CI") == null)

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /** A project of [LAYERS] painted phone layers, the top one active. */
    private fun project(id: String) {
        A11Kit.freshRepo()
        A11Kit.writeProject(
            id, W, H,
            List(LAYERS) { i -> { f: java.io.File -> A11Kit.writeRows(f, W, H, A11Kit.PaintedRows(W, H, seed = 2000L + i)::fill) } },
            active = LAYERS - 1,
        )
    }

    /** The median ms of one whole open of [id] (15 after 3 warm-ups). */
    private fun opens(id: String, count: Int = 15, warm: Int = 3): Double {
        val ms = DoubleArray(count)
        for (k in 0 until warm + count) {
            val t0 = System.nanoTime()
            val doc = runBlocking { A11Kit.repo().load(id) }
            val t = (System.nanoTime() - t0) / 1e6
            assertEquals(LAYERS, doc.layers.size)
            A11Kit.dispose(doc)
            if (k >= warm) ms[k - warm] = t
        }
        return median(ms)
    }

    @Test
    fun openPaintedPhoneLayers() {
        project("phone-open")
        // The one-decoder open of before (the seam keeps v1.10 A11's single band), in the same run.
        LayerEntries.maxDecoders = 1
        val one = try {
            opens("phone-open")
        } finally {
            LayerEntries.maxDecoders = LayerEntries.DECODERS
        }
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "open, $LAYERS painted $W x $H layers") { opens("phone-open") }
        for ((what, v) in listOf("1 decoder (before)" to one, "${LayerEntries.DECODERS} decoders" to ms)) {
            val perLayer = v / LAYERS
            val big = perLayer * (4000.0 * 5000.0) / (W.toDouble() * H)
            println(
                "v110 G2 open G2OpenPhoneOpenTimingTest: open, $LAYERS painted $W x $H layers, $what: median %.3f ms of 15 after 3 warm-ups, %.3f ms per layer; x 6: 200 layers %.1f s (Q4 6 s), 500 layers %.1f s (Q2 15 s), 50 of 4000 x 5000 %.1f s (Q6 15 s)"
                    .format(v, perLayer, perLayer * 200 * 6 / 1000, perLayer * 500 * 6 / 1000, big * 50 * 6 / 1000),
            )
        }
    }

    private companion object {
        const val W = 1080
        const val H = 2408
        const val LAYERS = 20

        /**
         * The whole open on the PC, from Q4 (R13: 200 phone layers in 6 s on the T606): 30 ms a
         * layer there, 5 ms on the PC (x 6), 100 ms for these 20. Gate 2 review: it was 1000 ms, a
         * guard the one-decoder open of before (165 ms on a quiet PC) also passed.
         */
        const val BUDGET_MS = 100.0
    }
}
