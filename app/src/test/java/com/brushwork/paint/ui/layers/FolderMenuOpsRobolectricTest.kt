package com.brushwork.paint.ui.layers

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.ui.common.FolderLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 8, §3.8): what the folder ⋮ items and folder properties do below the window: the
 * "Merge folder" gate (lock and eye, the folder's and those inside it) and its question
 * ([LayerOps.mergeChangesPicture]), the folder's blend list ("Pass through" off with a mode: one
 * step), "Layer from folder" and "Ungroup folder" as one step each, the moves the ⋮ menu offers
 * ([FolderMoves]) and the clipping toggle's bottom per level.
 */
@RunWith(RobolectricTestRunner::class)
class FolderMenuOpsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private class Pic(val c: EditorController) {
        val doc get() = c.doc
        val layers get() = doc.layers
        fun pixel(name: String, parent: Layer? = null, color: Int = 0xFF336699.toInt()) =
            Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(16, 12).also { it.eraseColor(color) }).also { l ->
                if (parent != null) l.parentId = parent.id
            }
        fun folder(name: String, parent: Layer? = null, passThrough: Boolean = true) =
            Layer.newFolder(doc.newLayerId(), name).also { f ->
                if (parent != null) f.parentId = parent.id
                f.folder = f.folder!!.copy(passThrough = passThrough)
            }
        fun set(vararg l: Layer) {
            doc.layers.clear()
            doc.layers += l
            doc.activeLayerIndex = doc.layers.lastIndex
            assertNull(LayerTree.check(doc.layers))
        }
        fun index(l: Layer) = doc.indexOf(l)
    }

    private fun pic(): Pic {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 16, 12)
        doc.layers += Layer(doc.newLayerId(), "BG", BitmapUtils.createLayerBitmap(16, 12))
        return Pic(EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) })
    }

    @Test
    fun mergeAsksOnlyWhenSomethingInsideBlendsWithWhatIsBelow() {
        val p = pic()
        val bg = p.pixel("BG")
        val f = p.folder("F")
        val a = p.pixel("A", f)
        p.set(bg, a, f)
        assertFalse("Normal inside", LayerOps.mergeChangesPicture(p.layers, p.index(f)))
        f.opacity = 0.5f
        assertFalse("opacity alone never changes it", LayerOps.mergeChangesPicture(p.layers, p.index(f)))
        a.blendMode = LayerBlendMode.MULTIPLY
        assertTrue("a Multiply layer inside", LayerOps.mergeChangesPicture(p.layers, p.index(f)))
        a.visible = false
        assertFalse("hidden: not drawn", LayerOps.mergeChangesPicture(p.layers, p.index(f)))
        a.visible = true
        f.folder = f.folder!!.copy(passThrough = false)
        assertFalse("an isolated folder merges as it shows", LayerOps.mergeChangesPicture(p.layers, p.index(f)))

        // A clipped Multiply layer blends inside its group, whose base is Normal.
        val p2 = pic()
        val f2 = p2.folder("F")
        val base = p2.pixel("Base", f2)
        val clip = p2.pixel("Clip", f2).also { it.clipping = true; it.blendMode = LayerBlendMode.MULTIPLY }
        p2.set(p2.pixel("BG"), base, clip, f2)
        assertFalse(LayerOps.mergeChangesPicture(p2.layers, p2.index(f2)))
        base.blendMode = LayerBlendMode.SCREEN
        assertTrue("the group's base blends with what is below", LayerOps.mergeChangesPicture(p2.layers, p2.index(f2)))

        // An adjustment layer inside reads what is below the folder.
        val p3 = pic()
        val f3 = p3.folder("F")
        val adj = p3.pixel("Adj", f3).also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert") }
        p3.set(p3.pixel("BG"), adj, f3)
        assertTrue(LayerOps.mergeChangesPicture(p3.layers, p3.index(f3)))
    }

    @Test
    fun mergeLooksThroughPassThroughFoldersOnly() {
        val p = pic()
        val outer = p.folder("Outer")
        val inner = p.folder("Inner", outer)
        val a = p.pixel("A", inner).also { it.blendMode = LayerBlendMode.MULTIPLY }
        p.set(p.pixel("BG"), a, inner, outer)
        assertTrue("through a pass-through folder", LayerOps.mergeChangesPicture(p.layers, p.index(outer)))
        inner.folder = inner.folder!!.copy(passThrough = false)
        assertFalse("an isolated Normal folder keeps its Multiply inside", LayerOps.mergeChangesPicture(p.layers, p.index(outer)))
        inner.blendMode = LayerBlendMode.MULTIPLY
        assertTrue("an isolated folder counts by its own blend", LayerOps.mergeChangesPicture(p.layers, p.index(outer)))

        // The folder itself clipped, or a clip base: composited isolated already, Normal (a
        // pass-through folder shows "Pass through", never its stored blend), as it merges.
        val q = pic()
        val f = q.folder("F")
        val m = q.pixel("M", f).also { it.blendMode = LayerBlendMode.MULTIPLY }
        val bg = q.pixel("BG")
        val clip = q.pixel("Clip").also { it.clipping = true }
        q.set(bg, m, f, clip)
        assertFalse("a clip base stored Normal", LayerOps.mergeChangesPicture(q.layers, q.index(f)))
        f.blendMode = LayerBlendMode.DARKEN
        assertFalse("a stored blend is not drawn while pass through is on", LayerOps.mergeChangesPicture(q.layers, q.index(f)))
        // Inside a pass-through folder, such a clip base blends Normal with what is below too.
        val q2 = pic()
        val outer2 = q2.folder("Outer")
        val f2 = q2.folder("F", outer2).also { it.blendMode = LayerBlendMode.DARKEN }
        val m2 = q2.pixel("M", f2).also { it.blendMode = LayerBlendMode.MULTIPLY }
        val clip2 = q2.pixel("Clip", outer2).also { it.clipping = true }
        q2.set(q2.pixel("BG"), m2, f2, clip2, outer2)
        assertFalse(LayerOps.mergeChangesPicture(q2.layers, q2.index(outer2)))
        f2.folder = f2.folder!!.copy(passThrough = false)
        assertTrue("an isolated Darken clip base", LayerOps.mergeChangesPicture(q2.layers, q2.index(outer2)))
        q.set(bg, clip, m, f)
        f.clipping = true
        clip.clipping = false
        assertFalse("clipped: isolated already", LayerOps.mergeChangesPicture(q.layers, q.index(f)))
    }

    @Test
    fun mergeFolderIsRefusedWhenLockedOrHidden() {
        val p = pic()
        val bg = p.pixel("BG")
        val f = p.folder("F")
        val a = p.pixel("A", f)
        val b = p.pixel("B", f)
        p.set(bg, a, b, f)
        val before = p.layers.toList()

        b.locked = true
        LayerOps.mergeFolder(p.c, f)
        assertEquals("Layer \"B\" is locked", p.c.message)
        assertEquals(before, p.layers.toList())
        assertFalse(p.c.undoManager.canUndo)
        b.locked = false

        f.visible = false
        assertFalse(LayerOps.canMergeFolder(p.c, f))
        f.visible = true

        val outer = p.folder("Outer").also { it.locked = true }
        f.parentId = outer.id
        p.set(bg, a, b, f, outer)
        LayerOps.mergeFolder(p.c, f)
        assertEquals(FolderLabels.locked("Outer"), p.c.message)
        assertFalse(p.c.undoManager.canUndo)

        // The strip's merge down on a folder takes the same gate.
        outer.locked = false
        b.locked = true
        LayerOps.mergeDown(p.c, f)
        assertEquals(5, p.layers.size)
        assertFalse(p.c.undoManager.canUndo)

        b.locked = false
        LayerOps.mergeFolder(p.c, f)
        assertEquals("one merged layer in the folder's place", listOf("BG", "F", "Outer"), p.layers.map { it.name })
        assertFalse(p.layers[1].isFolder)
        assertEquals(FolderLabels.MERGE, p.c.undoManager.undoLabel)
        p.c.undo()
        assertEquals(listOf(bg, a, b, f, outer), p.layers.toList())
    }

    @Test
    fun aBlendPickedOnAPassThroughFolderIsOneStep() {
        val p = pic()
        val f = p.folder("F")
        val a = p.pixel("A", f)
        p.set(p.pixel("BG"), a, f)
        LayerOps.setBlendMode(p.c, f, LayerBlendMode.MULTIPLY)
        assertFalse(f.folder!!.passThrough)
        assertEquals(LayerBlendMode.MULTIPLY, f.blendMode)
        assertEquals(1, p.c.undoManager.undoCount)
        assertEquals(LayerOps.BLEND_STEP, p.c.undoManager.undoLabel)
        p.c.undo()
        assertTrue(f.folder!!.passThrough)
        assertEquals(LayerBlendMode.NORMAL, f.blendMode)

        // "Normal" on a pass-through folder whose stored blend is Normal still turns it off.
        p.c.redo()
        p.c.undo()
        LayerOps.setBlendMode(p.c, f, LayerBlendMode.NORMAL)
        assertFalse(f.folder!!.passThrough)
        assertEquals(1, p.c.undoManager.undoCount)

        LayerOps.setPassThrough(p.c, f)
        assertTrue(f.folder!!.passThrough)
        assertEquals(FolderLabels.PASS_THROUGH, p.c.undoManager.undoLabel)
        assertEquals(2, p.c.undoManager.undoCount)
        LayerOps.setPassThrough(p.c, f)
        assertEquals("already on: no step", 2, p.c.undoManager.undoCount)
    }

    @Test
    fun layerFromFolderAndUngroupAreOneStepEach() {
        val p = pic()
        val bg = p.pixel("BG")
        val f = p.folder("F")
        val a = p.pixel("A", f)
        p.set(bg, a, f)
        LayerOps.layerFromFolder(p.c, f)
        // A raster layer directly above the folder, at its level; the folder is kept.
        assertEquals(listOf(bg, a, f), p.layers.take(3))
        assertEquals(4, p.layers.size)
        assertFalse(p.layers[3].isFolder)
        assertEquals(Layer.ROOT_ID, p.layers[3].parentId)
        assertEquals(FolderLabels.FROM_FOLDER, p.c.undoManager.undoLabel)
        p.c.undo()
        assertEquals(listOf(bg, a, f), p.layers.toList())

        LayerOps.ungroupFolder(p.c, f)
        assertEquals(listOf(bg, a), p.layers.toList())
        assertEquals(Layer.ROOT_ID, a.parentId)
        assertEquals(FolderLabels.UNGROUP, p.c.undoManager.undoLabel)
        p.c.undo()
        assertEquals(listOf(bg, a, f), p.layers.toList())
        assertEquals(f.id, a.parentId)
    }

    @Test
    fun theMenuOffersTheMovesTheTreeAllows() {
        val p = pic()
        val bg = p.pixel("BG")
        val x = p.pixel("X")
        p.set(bg, x)
        val plain = FolderMoves.of(p.layers, 1)
        assertFalse("no folders: no move items", plain.shown)
        assertEquals("v1.6: the row's own index", 1, plain.blockStart)
        assertTrue(LayerTreeRows.isBottomOfLevel(p.layers, 0))
        assertFalse(LayerTreeRows.isBottomOfLevel(p.layers, 1))

        val f = p.folder("F")
        val a = p.pixel("A", f)
        val b = p.pixel("B", f)
        p.set(bg, x, a, b, f)
        val belowFolder = FolderMoves.of(p.layers, p.index(x))
        assertTrue(belowFolder.shown && belowFolder.canMoveIn && !belowFolder.canMoveOut)
        val bottomChild = FolderMoves.of(p.layers, p.index(a))
        assertTrue(!bottomChild.canMoveIn && bottomChild.canMoveOut)
        assertFalse("only the bottom child moves out", FolderMoves.of(p.layers, p.index(b)).canMoveOut)
        assertEquals("a folder moves down with its block", p.index(a), FolderMoves.of(p.layers, p.index(f)).blockStart)
        assertTrue("nothing to clip to at the bottom of a folder", LayerTreeRows.isBottomOfLevel(p.layers, p.index(a)))
        assertFalse(LayerTreeRows.isBottomOfLevel(p.layers, p.index(b)))
        assertFalse("the folder's block sits on X", LayerTreeRows.isBottomOfLevel(p.layers, p.index(f)))

        assertTrue(p.c.putIntoFolderAbove(x))
        assertEquals(f.id, x.parentId)
        assertTrue(p.c.takeOutOfFolder(x))
        assertEquals(Layer.ROOT_ID, x.parentId)
    }
}
