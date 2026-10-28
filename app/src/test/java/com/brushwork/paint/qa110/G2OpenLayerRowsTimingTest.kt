package com.brushwork.paint.qa110

import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.ui.layers.FrameInfoCache
import com.brushwork.paint.ui.layers.LayerRowModel
import com.brushwork.paint.ui.layers.LayerTreeRows
import com.brushwork.paint.v110.a7.IndexTrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.random.Random

/**
 * v1.10 Gate 2 (lane open, O4): the loops over every layer stay O(n) at the 10,000 layers v1.10
 * allows. On the PC (the median of 15 runs after 3 warm-ups) the layer window's rows
 * (`LayerRowModel.build`, made again after every edit) and a canvas operation's snapshot
 * (`CanvasSnapshot.of` and its `matches`) take at most [BUDGET_MS] each, over two artworks of
 * 10,000 small layers: a random tree (55 folders, nested up to 8 deep) and one folder of 10,000
 * layers that all clip to the bottom one, where the per-row clip mark walked the whole chain
 * (O(n²)). The per-row build of before is printed once beside it for the record.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class G2OpenLayerRowsTimingTest {
    private fun median(run: () -> Unit): Double {
        repeat(WARM_UPS) { run() }
        val times = DoubleArray(RUNS) { PerfBudget.timeMs(run) }.sorted()
        return times[RUNS / 2]
    }

    private fun docOf(layers: List<Layer>) = Document("rows", "Rows", 8, 8).also {
        it.layers += layers
        it.activeLayerIndex = layers.indexOfLast { l -> !l.isFolder }
    }

    /** One folder of [n] − 1 layers, every one clipping but the bottom one. */
    private fun clipChain(n: Int): List<Layer> {
        val folder = Layer.newFolder(n.toLong(), "F", FolderSpec(passThrough = false))
        val kids = List(n - 1) { i -> Layer(i + 1L, "L", IndexTrees.px).also { it.parentId = folder.id; it.clipping = i > 0 } }
        return kids + folder
    }

    /** The rows as v1.9 to A10 built their tree columns: a clip mark and a folder size per row (for the record only). */
    private fun perRow(doc: Document): Int {
        var sink = 0
        val layers = doc.layers
        for (l in layers.asReversed()) {
            val i = doc.indexOf(l)
            if (LayerTreeRows.clipInfo(layers, i).clipped) sink++
            if (l.isFolder) sink += LayerTree.descendantCount(layers, doc.indexOf(l))
        }
        return sink
    }

    private fun measure(what: String, doc: Document): Pair<Double, Double> {
        val topFirst = doc.layers.asReversed().toList()
        var sink = 0
        val rows = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "rows, $what") {
            median { sink += LayerRowModel.build(doc, topFirst, FrameInfoCache()).size }
        }
        val snapshot = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "snapshot, $what") {
            median { if (CanvasSnapshot.of(doc).matches(doc)) sink++ }
        }
        val before = PerfBudget.timeMs { sink += perRow(doc) }
        println(
            String.format(
                Locale.ROOT,
                "v110 G2 open G2OpenLayerRowsTimingTest: %s: rows PC median %.3f ms (x6 %.1f ms), snapshot %.3f ms (x6 %.1f ms) of %d runs after %d warm-ups, budget %.0f ms each; the per-row build of before %.1f ms once (%d)",
                what, rows, rows * 6, snapshot, snapshot * 6, RUNS, WARM_UPS, BUDGET_MS, before, sink,
            ),
        )
        return rows to snapshot
    }

    @Test
    fun tenThousandLayersGiveTheirRowsAndSnapshotInLinearTime() {
        val random = IndexTrees.random(Random(10_000), size = 10_000, kidsMax = 400)
        val rnd = Random(204)
        for (l in random) if (l.isFolder) l.folderOpen = rnd.nextInt(3) != 0
        assertEquals(10_000, random.size)
        measure("10,000 layers in a random tree", docOf(random))
        val chain = clipChain(10_000)
        assertEquals(10_000, chain.size)
        assertTrue("the chain clips", LayerTreeRows.clipInfos(chain).count { it.clipped } == 9_998)
        measure("10,000 layers clipping in one folder", docOf(chain))
    }

    private companion object {
        const val BUDGET_MS = 30.0
        const val RUNS = 15
        const val WARM_UPS = 3
    }
}
