package com.brushwork.paint.v110.a11

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A11, design 2.17): opening 200 painted layers (each about 35 % covered by opaque
 * strokes, as the check artworks) through `ProjectRepository.load` at FULL stays within PC 1.0 s
 * (the T606's 6 s over the x 6 proxy; x 4 on CI): the median of 15 opens after 3 warm-ups, the
 * loaded document released after each, on a 512 x 512 canvas (the proxy).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class Open200TimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL)

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /** A project of 200 painted [w] x [h] layers, the top one active. */
    private fun project(id: String, w: Int, h: Int) {
        A11Kit.freshRepo()
        A11Kit.writeProject(
            id, w, h,
            List(LAYERS) { i -> { f: java.io.File -> A11Kit.writeRows(f, w, h, A11Kit.PaintedRows(w, h, seed = 1000L + i)::fill) } },
            active = LAYERS - 1,
        )
    }

    /** The median ms of [count] opens of [id] after [warm] warm-ups. */
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
    fun open200PaintedLayersAt512() {
        project("open200", 512, 512)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "open, 200 painted 512 x 512 layers") { opens("open200") }
        println("v110 A11 Open200TimingTest: open, 200 painted 512 x 512 layers: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms)".format(ms, ms * 6))
    }

    private companion object {
        const val LAYERS = 200
        const val BUDGET_MS = 1000.0
    }
}
