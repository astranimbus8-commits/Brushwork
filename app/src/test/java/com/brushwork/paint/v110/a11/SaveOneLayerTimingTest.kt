package com.brushwork.paint.v110.a11

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A11, design 2.17): saving an artwork of 20 painted phone layers (1080 x 2408, about
 * 35 % covered, opened at FULL) in which one parked (tiled) layer changed stays within PC 50 ms
 * (the T606's 300 ms over the x 6 proxy; x 4 on CI): the median of 15 saves after 3 warm-ups,
 * from `ProjectRepository.save` to its return (the snapshot on main, the bands assembled from
 * tiles and encoded on IO, `project.json`, the old file deleted). The other layers reuse their
 * files.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class SaveOneLayerTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL)

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    @Test
    fun saveOneChangedPhoneLayer() {
        val w = 1080
        val h = 2408
        A11Kit.freshRepo()
        A11Kit.writeProject(
            "save1", w, h,
            List(LAYERS) { i -> { f: java.io.File -> A11Kit.writeRows(f, w, h, A11Kit.PaintedRows(w, h, seed = 500L + i)::fill) } },
            active = LAYERS - 1,
        )
        val repo = A11Kit.repo()
        val doc = runBlocking { repo.load("save1") }
        try {
            val changed = doc.layers[LAYERS / 2]
            assertTrue("a parked layer", changed.pixels is TiledPixels)
            fun saves(count: Int = 15, warm: Int = 3): Double {
                val ms = DoubleArray(count)
                for (k in 0 until warm + count) {
                    changed.markChanged()
                    val t = PerfBudget.timeMs { runBlocking { repo.save(doc, null) } }
                    assertTrue("saved", changed.savedVersion == changed.contentVersion)
                    if (k >= warm) ms[k - warm] = t
                }
                return median(ms)
            }
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "save, one changed 1080 x 2408 layer") { saves() }
            println("v110 A11 SaveOneLayerTimingTest: save, one changed 1080 x 2408 layer of 20: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; device budget 300 ms)".format(ms, ms * 6))
        } finally {
            A11Kit.dispose(doc)
        }
    }

    private companion object {
        const val LAYERS = 20
        const val BUDGET_MS = 50.0
    }
}
