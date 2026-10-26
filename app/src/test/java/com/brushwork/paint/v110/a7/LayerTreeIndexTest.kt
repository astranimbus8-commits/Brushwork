package com.brushwork.paint.v110.a7

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * v1.10 A7 step 5 (design 3.10): `LayerTree.Index` answers every query exactly as the per-index
 * queries of `LayerTree` (and `Document.effectiveVisible` / `effectiveLocked`) do, on 1,000 random
 * trees (folders nested up to 8 deep, eyes, locks, clipping, adjustment layers, pass-through and
 * isolated folders, opacity) and on one of 10,000 layers. Its build time is
 * `LayerTreeIndexBuildTimingTest`.
 */
@RunWith(RobolectricTestRunner::class)
class LayerTreeIndexTest {
    @Test
    fun matchesTheQueriesOn1000RandomTrees() {
        val rnd = Random(110_7)
        var folders = 0
        var nested = 0
        repeat(1_000) { t ->
            val layers = IndexTrees.random(rnd, size = rnd.nextInt(1, 61), kidsMax = 5)
            folders += layers.count { it.isFolder }
            nested += layers.count { it.isFolder && it.parentId != Layer.ROOT_ID }
            assertNull("tree $t holds I11", LayerTree.check(layers))
            IndexTrees.assertSame("tree $t", layers)
        }
        assertTrue("the trees had folders ($folders) and nested folders ($nested)", folders > 1_000 && nested > 100)
    }

    @Test
    fun matchesTheQueriesOnTenThousandLayers() {
        val layers = IndexTrees.random(Random(10_000), size = 10_000, kidsMax = 400)
        assertEquals(10_000, layers.size)
        val folders = layers.count { it.isFolder }
        assertTrue("many folders ($folders), at most ${LayerTree.MAX_FOLDERS}", folders in 50..LayerTree.MAX_FOLDERS)
        assertTrue("folders inside folders", layers.any { it.isFolder && it.parentId != Layer.ROOT_ID })
        assertNull(LayerTree.check(layers))
        IndexTrees.assertSame("10,000 layers", layers)
    }

    @Test
    fun aLayerOutsideTheListAnswersItsOwnEyeAndLock() {
        val layers = IndexTrees.random(Random(3), size = 20, kidsMax = 4)
        val tree = LayerTree.index(layers)
        val outside = Layer(99_999, "Outside", IndexTrees.px).apply { visible = false; locked = true; parentId = layers.first { it.isFolder }.id }
        assertEquals(-1, tree.indexOf(outside))
        assertFalse(tree.effectiveVisible(outside))
        assertTrue(tree.effectiveLocked(outside))
        outside.visible = true
        outside.locked = false
        assertTrue(tree.effectiveVisible(outside))
        assertFalse(tree.effectiveLocked(outside))
        assertEquals(0, LayerTree.index(emptyList()).size)
    }
}

/** Random trees that hold I11, and the comparison of an index with the per-index queries. */
internal object IndexTrees {
    val px: Bitmap by lazy { BitmapUtils.createLayerBitmap(1, 1) }

    /**
     * [size] layers bottom first: units of the top level, each a layer or a folder (at most
     * [LayerTree.MAX_FOLDERS], [LayerTree.MAX_DEPTH] deep) of up to [kidsMax] units directly below it.
     */
    fun random(rnd: Random, size: Int, kidsMax: Int): List<Layer> {
        val out = ArrayList<Layer>(size)
        var nextId = 1L
        var folders = 0
        var left = size

        fun decorate(l: Layer) {
            l.visible = rnd.nextInt(6) != 0
            l.locked = rnd.nextInt(8) == 0
            l.clipping = rnd.nextInt(3) == 0
            l.opacity = if (rnd.nextInt(4) == 0) 0.5f else 1f
        }

        fun unit(parentId: Long, depth: Int) {
            if (left <= 0) return
            left--
            if (left > 0 && folders < LayerTree.MAX_FOLDERS && depth < LayerTree.MAX_DEPTH && rnd.nextInt(4) == 0) {
                folders++
                val f = Layer.newFolder(nextId++, "F", FolderSpec(passThrough = rnd.nextInt(3) != 0))
                f.parentId = parentId
                repeat(rnd.nextInt(0, kidsMax + 1)) { unit(f.id, depth + 1) }
                decorate(f)
                out += f
            } else {
                val l = Layer(nextId++, "L", px)
                l.parentId = parentId
                decorate(l)
                if (rnd.nextInt(8) == 0) l.adjustment = AdjustmentSpec(filterId = "adjust.invert")
                out += l
            }
        }
        while (left > 0) unit(Layer.ROOT_ID, 0)
        return out
    }

    /** Every query of the index of [layers] equals the per-index query (and the document's). */
    fun assertSame(what: String, layers: List<Layer>) {
        val doc = Document("index", "Index", 8, 8).also { it.layers += layers }
        val tree = LayerTree.index(layers)
        assertEquals(what, layers.size, tree.size)
        for (i in layers.indices) {
            val l = layers[i]
            val at = "$what, index $i"
            assertEquals(at, LayerTree.parentOf(layers, i), tree.parentOf(i))
            assertEquals(at, LayerTree.parentOf(layers, i), tree.parent[i])
            assertEquals(at, LayerTree.depth(layers, i), tree.depth(i))
            assertEquals(at, LayerTree.depth(layers, i), tree.depth[i])
            assertEquals(at, LayerTree.descendantCount(layers, i), tree.descendantCount(i))
            assertEquals(at, LayerTree.block(layers, i), tree.block(i))
            assertEquals(at, LayerTree.shownByAncestors(layers, i), tree.shownByAncestors(i))
            assertEquals(at, LayerTree.lockedByAncestor(layers, i), tree.lockedByAncestor(i))
            assertEquals(at, LayerTree.showsAsIs(layers, i), tree.showsAsIs(i))
            assertEquals(at, LayerTree.isClipped(layers, i), tree.isClipped(i))
            assertEquals(at, LayerTree.isClipBase(layers, i), tree.isClipBase(i))
            assertEquals(at, doc.effectiveVisible(l), tree.effectiveVisible(l))
            assertEquals(at, doc.effectiveVisible(l), tree.effectiveVisible[i])
            assertEquals(at, doc.effectiveLocked(l), tree.effectiveLocked(l))
            assertEquals(at, doc.effectiveLocked(l), tree.effectiveLocked[i])
            assertEquals(at, i, tree.indexOf(l))
        }
        for (i in listOf(-1, layers.size)) {
            assertEquals(LayerTree.parentOf(layers, i), tree.parentOf(i))
            assertEquals(LayerTree.depth(layers, i), tree.depth(i))
            assertEquals(LayerTree.descendantCount(layers, i), tree.descendantCount(i))
            assertEquals(LayerTree.shownByAncestors(layers, i), tree.shownByAncestors(i))
            assertEquals(LayerTree.lockedByAncestor(layers, i), tree.lockedByAncestor(i))
        }
    }
}
