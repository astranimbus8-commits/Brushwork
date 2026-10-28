package com.brushwork.paint.v110.g2open

import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.ui.layers.FrameInfoCache
import com.brushwork.paint.ui.layers.LayerRowModel
import com.brushwork.paint.ui.layers.LayerTreeRows
import com.brushwork.paint.v110.a7.IndexTrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * v1.10 Gate 2 (lane open, O4): the layer window's rows at 10,000 layers come from one pass over
 * the tree's index (`LayerTreeRows.clipInfos`, `LayerTree.Index.siblingAbove`), and every answer
 * is the per-row query's: on 1,000 random trees (folders nested up to 8 deep, open and closed,
 * clipping and adjustment layers) and on one of 10,000 layers, and in the rows
 * `LayerRowModel.build` gives the window (clip mark and base, folder sizes, level, the inherited
 * eye and lock, listed or not, also while a folder is dragged). The time it takes is
 * `G2OpenLayerRowsTimingTest`.
 */
@RunWith(RobolectricTestRunner::class)
class G2OpenLayerRowsTest {
    /** [layers] with each folder open or closed at random (the clip marks read it). */
    private fun opened(rnd: Random, layers: List<Layer>): List<Layer> {
        for (l in layers) if (l.isFolder) l.folderOpen = rnd.nextInt(3) != 0
        return layers
    }

    private fun assertOnePass(what: String, layers: List<Layer>) {
        val index = LayerTree.index(layers)
        val clips = LayerTreeRows.clipInfos(layers, index)
        assertEquals(what, layers.size, clips.size)
        for (i in layers.indices) {
            val at = "$what, index $i"
            assertEquals(at, LayerTreeRows.siblingAbove(layers, i), index.siblingAbove(i))
            assertEquals(at, LayerTreeRows.clipInfo(layers, i), clips[i])
        }
        assertEquals(-1, index.siblingAbove(-1))
        assertEquals(-1, index.siblingAbove(layers.size))
    }

    @Test
    fun theOnePassAnswersAsThePerRowQueries() {
        val rnd = Random(110_204)
        var clipped = 0
        var closed = 0
        repeat(1_000) { t ->
            val layers = opened(rnd, IndexTrees.random(rnd, size = rnd.nextInt(1, 61), kidsMax = 5))
            clipped += LayerTreeRows.clipInfos(layers).count { it.clipped }
            closed += layers.count { it.isFolder && !it.folderOpen }
            assertOnePass("tree $t", layers)
        }
        assertTrue("clipped rows ($clipped) and closed folders ($closed)", clipped > 1_000 && closed > 100)
        assertOnePass("10,000 layers", opened(rnd, IndexTrees.random(Random(10_000), size = 10_000, kidsMax = 400)))
    }

    @Test
    fun theRowsOfTenThousandLayersAreThePerRowOnes() {
        val layers = opened(Random(7), IndexTrees.random(Random(10_000), size = 10_000, kidsMax = 400))
        val doc = Document("rows", "Rows", 8, 8).also { it.layers += layers }
        doc.activeLayerIndex = layers.size - 1
        val topFirst = layers.asReversed().toList()
        // As the window lists them, and while an open folder with nested folders is dragged.
        val dragged = layers.first { f -> f.isFolder && f.folderOpen && layers.any { it.isFolder && it.parentId == f.id } }.id
        var hiddenRows = 0
        for (draggedId in listOf(null, dragged)) {
            val rows = LayerRowModel.build(doc, topFirst, FrameInfoCache(), draggedId)
            assertEquals(10_000, rows.size)
            val tree = LayerTreeRows.Tree(layers, draggedId)
            var folders = 0
            for ((k, row) in rows.withIndex()) {
                val i = layers.size - 1 - k
                val l = layers[i]
                val at = "row $k (index $i), dragged $draggedId"
                assertTrue(at, row.layer === l)
                assertEquals(at, LayerTreeRows.clipInfo(layers, i), row.clip)
                assertEquals(at, LayerTree.descendantCount(layers, i), row.descendants)
                assertEquals(at, tree.depth(l), row.depth)
                assertEquals(at, tree.hiddenByFolder(l), row.hiddenByFolder)
                assertEquals(at, tree.lockedByFolder(l), row.lockedByFolder)
                assertEquals(at, tree.shown(l), row.shownInList)
                if (row.isFolder) folders++
                if (!row.shownInList) hiddenRows++
            }
            assertTrue("folders ($folders)", folders > 50)
            assertTrue("the top row is the active one", rows.first().active)
        }
        assertTrue("closed and dragged folders hide rows ($hiddenRows)", hiddenRows > 100)
    }
}
