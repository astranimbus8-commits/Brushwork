package com.brushwork.paint.v110.a7

import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.random.Random

/**
 * v1.10 A7 step 5 (design 2.16, `LayerTreeIndexTest`'s budget): building `LayerTree.Index` over
 * 10,000 layers (55 folders, nested up to 8 deep) and asking it every query once per layer takes at
 * most 20 ms on the PC (the median of 15 runs after 3 warm-ups), so the loops over every layer stay
 * O(n): the per-index queries would take seconds there.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class LayerTreeIndexBuildTimingTest {
    @Test
    fun tenThousandLayersBuildAndAnswerWithin20Ms() {
        val layers = IndexTrees.random(Random(10_000), size = 10_000, kidsMax = 400)
        assertEquals(10_000, layers.size)
        var sink = 0
        val run = {
            val tree = LayerTree.index(layers)
            for (i in layers.indices) {
                val l = layers[i]
                if (tree.effectiveVisible(l)) sink++
                if (tree.effectiveLocked(l)) sink++
                if (tree.showsAsIs(i)) sink++
                if (tree.isClipped(i) || tree.isClipBase(i)) sink++
                sink += tree.depth(i) + tree.descendantCount(i) + tree.parentOf(i)
            }
        }
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "LayerTree.Index over 10,000 layers") {
            repeat(WARM_UPS) { run() }
            val times = DoubleArray(RUNS) { PerfBudget.timeMs(run) }.sorted()
            times[RUNS / 2]
        }
        println(String.format(Locale.ROOT, "LayerTreeIndexBuildTimingTest: PC median %.3f ms of %d runs after %d warm-ups; x6 = %.3f ms; budget %.0f ms (%d)", ms, RUNS, WARM_UPS, ms * 6, BUDGET_MS, sink))
    }

    private companion object {
        const val BUDGET_MS = 20.0
        const val RUNS = 15
        const val WARM_UPS = 3
    }
}
