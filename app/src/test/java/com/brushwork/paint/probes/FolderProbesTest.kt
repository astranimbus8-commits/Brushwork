package com.brushwork.paint.probes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.qa16.QaDocs
import com.brushwork.paint.testing.PerfBudget
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 area A (item 8, §6.3 folder rows) on the JVM (Robolectric NATIVE): the folder rows' costs
 * against the no-folder picture, measured like the F0 baselines (`V17BaselinesTest`) so the
 * device numbers have a desk reference. Each prints its numbers and guards only against
 * order-of-magnitude regressions; the budgets themselves are T606 rows. Local only, never on CI.
 *
 * - a full display-tile render of QaDocs.stack at 2000 × 2500 (a quarter of the T606 document:
 *   the rows are ratios and per-tile costs): no folders; pass-through folders at 100 %; one
 *   isolated folder; one pass-through folder at 50 %; three isolated folders;
 * - a folder thumbnail (`FolderComposite.renderBlockThumbnail`, what `FolderThumbnails` renders
 *   300 ms after the last change) of a 4000 × 5000 folder holding two layers.
 */
@RunWith(RobolectricTestRunner::class)
class FolderProbesTest {
    @Before
    fun notOnCi() = assumeTrue("probes run locally only", System.getenv("CI") == null)

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    private inline fun timeMs(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    /** QaDocs.stack's layers, bottom first: Photo, Below 1, Below 2, Above 1 (Multiply), Above 2. */
    private enum class Tree { NONE, PASS_THROUGH_100, ONE_ISOLATED, PASS_THROUGH_50, THREE_ISOLATED }

    /** QaDocs.stack wrapped as [tree] says: folders placed above their children (I11). */
    private fun doc(w: Int, h: Int, tree: Tree): Document {
        val doc = QaDocs.stack(w, h)
        val (photo, below1, below2, above1, above2) = doc.layers.toList()
        fun folder(name: String, passThrough: Boolean, opacity: Float = 1f, vararg children: Layer): Layer =
            Layer.newFolder(doc.newLayerId(), name, FolderSpec(passThrough = passThrough)).also { f ->
                f.opacity = opacity
                children.forEach { it.parentId = f.id }
            }
        val order = when (tree) {
            Tree.NONE -> return doc
            Tree.PASS_THROUGH_100 -> listOf(
                photo, below1, below2, folder("Below", true, 1f, below1, below2),
                above1, above2, folder("Above", true, 1f, above1, above2),
            )
            Tree.ONE_ISOLATED -> listOf(photo, below1, below2, folder("Below", false, 1f, below1, below2), above1, above2)
            Tree.PASS_THROUGH_50 -> listOf(photo, below1, below2, folder("Below", true, 0.5f, below1, below2), above1, above2)
            Tree.THREE_ISOLATED -> listOf(
                photo, below1, folder("F1", false, 1f, below1), below2, folder("F2", false, 1f, below2),
                above1, above2, folder("F3", false, 1f, above1, above2),
            )
        }
        doc.layers.clear()
        doc.layers += order
        assertNull(LayerTree.check(doc.layers))
        return doc
    }

    @Test
    fun theFolderRowsAgainstTheNoFolderPicture() {
        val w = 2000
        val h = 2500
        val all = Rect(0, 0, w, h)
        val results = Tree.values().associateWith { tree ->
            val d = doc(w, h, tree)
            val compositor = Compositor(d) { null }
            val tiles = DisplayTiles(w, h)
            fun redraw(): Double {
                tiles.invalidate(null)
                return timeMs { tiles.update(compositor, all) }
            }
            redraw() // warm up (tile bitmaps, scratch, JIT)
            val med = median(List(3) { redraw() })
            tiles.release()
            for (l in d.layers) if (!l.isFolder) l.bitmap.recycle()
            med
        }
        val base = results.getValue(Tree.NONE)
        val tileCount = ((w + 511) / 512) * ((h + 511) / 512)
        fun extraPerTile(t: Tree) = (results.getValue(t) - base) / tileCount
        println(
            "v17 A probe: 2000 x 2500 (QaDocs.stack, $tileCount tiles), full tile render medians: " +
                results.entries.joinToString { (t, ms) -> "$t ${"%.0f".format(ms)} ms (${"%.2f".format(ms / base)} x)" } +
                "; per tile extra: one isolated ${"%.2f".format(extraPerTile(Tree.ONE_ISOLATED))} ms (T606 budget 2.5)," +
                " pass-through 50 % ${"%.2f".format(extraPerTile(Tree.PASS_THROUGH_50))} ms (budget 3)",
        )
        // Order-of-magnitude guards (budgets: 1.05 x, 1.3 x, 2.5 and 3 ms per tile on the T606).
        assertTrue("pass-through at 100 %: ${results[Tree.PASS_THROUGH_100]} vs $base ms", results.getValue(Tree.PASS_THROUGH_100) <= 2.0 * base + PerfBudget.ms(50.0))
        assertTrue("three isolated: ${results[Tree.THREE_ISOLATED]} vs $base ms", results.getValue(Tree.THREE_ISOLATED) <= 4.0 * base + PerfBudget.ms(50.0))
        assertTrue("one isolated per tile", extraPerTile(Tree.ONE_ISOLATED) <= PerfBudget.ms(25.0))
        assertTrue("pass-through 50 % per tile", extraPerTile(Tree.PASS_THROUGH_50) <= PerfBudget.ms(30.0))
    }

    @Test
    fun aFolderThumbnailOfA20MegapixelFolder() {
        val w = 4000
        val h = 5000
        val doc = Document("thumb", "Thumb", w, h)
        val f = Layer.newFolder(doc.newLayerId(), "F", FolderSpec(passThrough = false))
        val a = Layer(doc.newLayerId(), "A", BitmapUtils.createLayerBitmap(w, h)).also { l ->
            l.parentId = f.id
            Canvas(l.bitmap).drawCircle(w * 0.4f, h * 0.4f, w * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2060C0.toInt() })
        }
        val b = Layer(doc.newLayerId(), "B", BitmapUtils.createLayerBitmap(w, h)).also { l ->
            l.parentId = f.id
            Canvas(l.bitmap).drawRect(w * 0.3f, h * 0.5f, w * 0.9f, h * 0.9f, Paint().apply { color = 0x80E04020.toInt() })
        }
        doc.layers += listOf(a, b, f)
        val compositor = Compositor(doc) { null }
        repeat(2) { FolderComposite.renderBlockThumbnail(compositor, doc, f, 96).recycle() } // warm up
        val times = List(5) { timeMs { FolderComposite.renderBlockThumbnail(compositor, doc, f, 96).recycle() } }
        val med = median(times)
        println("v17 A probe: folder thumbnail (96 px) of a 4000 x 5000 isolated folder with 2 layers: median ${"%.1f".format(med)} ms (${times.joinToString { "%.1f".format(it) }}); T606 budget 30 ms")
        a.bitmap.recycle()
        b.bitmap.recycle()
        assertTrue("folder thumbnail $med ms", med <= PerfBudget.ms(300.0))
    }
}
