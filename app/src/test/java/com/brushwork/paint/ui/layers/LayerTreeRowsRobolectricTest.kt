package com.brushwork.paint.ui.layers

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
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
 * v1.7 (item 8, §3.8): the layer window's tree rows: indent levels, closed folders hiding their
 * rows, the eye and lock a folder passes on, clip marks per level, and the drag of a whole folder
 * block mapped to `EditorController.moveBlock` with the drop rule.
 *
 * The tree, bottom first: BG, A (in F1, clipping: no base), B (in F2), F2 (in F1), C (in F1,
 * clipping onto F2), F1, Top. Top first: Top, F1, C, F2, B, A, BG.
 */
@RunWith(RobolectricTestRunner::class)
class LayerTreeRowsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private class Tree(val c: EditorController, val bg: Layer, val a: Layer, val b: Layer, val f2: Layer, val cl: Layer, val f1: Layer, val top: Layer) {
        val doc get() = c.doc
        val topFirst get() = doc.layers.asReversed().toList()
    }

    private fun tree(): Tree {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 24, 16)
        fun pixel(name: String, parent: Long = Layer.ROOT_ID) =
            Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(24, 16)).also { it.parentId = parent }
        val f1 = Layer.newFolder(doc.newLayerId(), "F1")
        val f2 = Layer.newFolder(doc.newLayerId(), "F2").also { it.parentId = f1.id }
        val bg = pixel("BG")
        val a = pixel("A", f1.id).also { it.clipping = true }
        val b = pixel("B", f2.id)
        val cl = pixel("C", f1.id).also { it.clipping = true }
        val top = pixel("Top")
        doc.layers += listOf(bg, a, b, f2, cl, f1, top)
        doc.activeLayerIndex = 6
        assertNull(LayerTree.check(doc.layers))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return Tree(c, bg, a, b, f2, cl, f1, top)
    }

    private fun rows(t: Tree, dragged: Long? = null) = LayerRowModel.build(t.doc, t.topFirst, FrameInfoCache(), dragged)

    private fun names(rows: List<LayerRowModel>) = rows.filter { it.shownInList }.map { it.name }

    @Test
    fun rowsCarryTheirLevelAndFoldersTheirState() {
        val t = tree()
        val rows = rows(t)
        assertEquals(listOf("Top", "F1", "C", "F2", "B", "A", "BG"), rows.map { it.name })
        assertEquals(listOf(0, 0, 1, 1, 2, 1, 0), rows.map { it.depth })
        val f1 = rows[1]
        assertTrue(f1.isFolder && f1.folderOpen && f1.passThrough)
        assertEquals(FolderLabels.PASS_THROUGH, f1.blendLabel)
        assertEquals("F1 holds C, F2, B and A", 4, f1.descendants)
        assertEquals(LayerBlendMode.NORMAL.label, rows[0].blendLabel)
        assertEquals(listOf(7, 6, 5, 4, 3, 2, 1), rows.map { it.number })
        assertTrue(rows.all { it.shownInList })
        // The indent: 12 dp a level, stopping at 4 levels; deeper rows draw the guide line.
        assertEquals(listOf(0f, 12f, 24f, 48f, 48f), listOf(0, 1, 2, 4, 7).map { LayerTreeRows.indent(it) })
        assertFalse(LayerTreeRows.hasGuide(4))
        assertTrue(LayerTreeRows.hasGuide(5))
    }

    @Test
    fun aClosedFolderHidesItsRowsButKeepsTheirModels() {
        val t = tree()
        t.c.setFolderOpen(t.f2, open = false)
        assertEquals(listOf("Top", "F1", "C", "F2", "A", "BG"), names(rows(t)))
        t.c.setFolderOpen(t.f1, open = false)
        val rows = rows(t)
        assertEquals(listOf("Top", "F1", "BG"), names(rows))
        assertEquals("every layer keeps a model (the active row's controls)", 7, rows.size)
        // While a folder is dragged its rows hide too.
        t.c.setFolderOpen(t.f1, open = true)
        t.c.setFolderOpen(t.f2, open = true)
        assertEquals(listOf("Top", "F1", "C", "F2", "A", "BG"), names(rows(t, dragged = t.f2.id)))
    }

    @Test
    fun aHiddenOrLockedFolderPassesItsEyeAndLockOn() {
        val t = tree()
        t.f2.visible = false
        t.f1.locked = true
        val byName = rows(t).associateBy { it.name }
        assertTrue(byName.getValue("B").hiddenByFolder)
        assertFalse("its own eye is not read", byName.getValue("F2").hiddenByFolder)
        assertFalse(byName.getValue("C").hiddenByFolder)
        assertTrue(listOf("C", "F2", "B", "A").all { byName.getValue(it).lockedByFolder })
        assertFalse(byName.getValue("F1").lockedByFolder)
        assertFalse(byName.getValue("Top").lockedByFolder)
        assertEquals(
            "Layer 3: 100%, Normal, in a locked folder, in a hidden folder",
            LayerLabels.rowState(3, 100, "Normal", inLockedFolder = true, inHiddenFolder = true),
        )
    }

    @Test
    fun clippingIsPerLevel() {
        val t = tree()
        val byName = rows(t).associateBy { it.name }
        val c = byName.getValue("C").clip
        assertTrue("C clips onto the folder F2, its sibling below", c.clipped)
        assertEquals(t.doc.indexOf(t.f2), c.baseIndex)
        assertTrue("F2's row is right below C's", c.lowestInGroup)
        assertFalse(c.continuesAbove)
        val a = byName.getValue("A").clip
        assertFalse("A is F1's bottom child: no base", a.clipped)
        assertTrue("its mark is greyed", a.noBase)
        // A top-level bottom layer that clips keeps v1.6's plain row.
        t.bg.clipping = true
        assertEquals(ClipInfo.NONE, rows(t).first { it.name == "BG" }.clip)
        // A hidden folder base hides its group.
        t.f2.visible = false
        assertTrue(rows(t).first { it.name == "C" }.baseHidden)
    }

    @Test
    fun aDraggedFolderPassesAClosedFolderWholeAndStopsUnderAnOpenOne() {
        val t = tree()
        // F2 (with B) dragged down one row, past A: it stays in F1, as its bottom unit.
        val unit = DraggedUnit.of(t.doc.layers, t.f2)
        assertEquals(DraggedUnit(t.f2.id, 2), unit)
        val shown = LayerTreeRows.Tree(t.doc.layers, unit.id)::shown
        val down = LayerTreeRows.movedBlock(t.topFirst, shown, unit.id, unit.size, from = 3, to = 4)
        assertEquals(listOf("Top", "F1", "C", "A", "F2", "B", "BG"), down.map { it.name })
        assertTrue(t.c.moveBlock(t.f2, LayerTreeRows.flatIndexIn(down, t.f2), null))
        assertEquals(listOf("BG", "B", "F2", "A", "C", "F1", "Top"), t.doc.layers.map { it.name })
        assertEquals(t.f1.id, t.f2.parentId)
        assertEquals(t.f2.id, t.b.parentId)
        assertNull(LayerTree.check(t.doc.layers))
        t.c.undo()
        assertEquals(listOf("BG", "A", "B", "F2", "C", "F1", "Top"), t.doc.layers.map { it.name })

        // With F2 closed, C dragged down past F2 passes B too.
        t.c.setFolderOpen(t.f2, open = false)
        val cUnit = DraggedUnit.of(t.doc.layers, t.cl)
        val cShown = LayerTreeRows.Tree(t.doc.layers, cUnit.id)::shown
        val pastClosed = LayerTreeRows.movedBlock(t.topFirst, cShown, cUnit.id, 1, from = 2, to = 3)
        assertEquals(listOf("Top", "F1", "F2", "B", "C", "A", "BG"), pastClosed.map { it.name })
        assertTrue(t.c.moveBlock(t.cl, LayerTreeRows.flatIndexIn(pastClosed, t.cl), null))
        assertEquals("C stays in F1, below the closed F2", t.f1.id, t.cl.parentId)
        t.c.undo()

        // Top dragged down past the open F1: it becomes F1's top child.
        val topUnit = DraggedUnit.of(t.doc.layers, t.top)
        val topShown = LayerTreeRows.Tree(t.doc.layers, topUnit.id)::shown
        val intoOpen = LayerTreeRows.movedBlock(t.topFirst, topShown, topUnit.id, 1, from = 0, to = 1)
        assertEquals(listOf("F1", "Top", "C", "F2", "B", "A", "BG"), intoOpen.map { it.name })
        assertTrue(t.c.moveBlock(t.top, LayerTreeRows.flatIndexIn(intoOpen, t.top), null))
        assertEquals(t.f1.id, t.top.parentId)
        assertEquals(1, t.c.undoManager.undoCount)
    }

    @Test
    fun aRowDraggedUpGoesAboveTheRowAndOutOfItsFolder() {
        val t = tree()
        val unit = DraggedUnit.of(t.doc.layers, t.cl)
        val shown = LayerTreeRows.Tree(t.doc.layers, unit.id)::shown
        // C (row 2) up past F1 (row 1): above the folder, at the top level.
        val up = LayerTreeRows.movedBlock(t.topFirst, shown, unit.id, 1, from = 2, to = 1)
        assertEquals(listOf("Top", "C", "F1", "F2", "B", "A", "BG"), up.map { it.name })
        assertTrue(t.c.moveBlock(t.cl, LayerTreeRows.flatIndexIn(up, t.cl), null))
        assertEquals(Layer.ROOT_ID, t.cl.parentId)
        // Indices that don't match the dragged unit change nothing.
        assertEquals(t.topFirst, LayerTreeRows.movedBlock(t.topFirst, shown, unit.id, 1, from = 0, to = 1))
    }

    @Test
    fun deepRowsGiveTheirIndentFromTheThumbnail() {
        // ibisPaint's 382 dp window (a 392 dp phone): a 220 dp list, 62 dp thumbnails.
        val m = LayerWindowMetrics.of(382f, 520f)
        assertEquals(62f, m.thumbAt(0), 0f)
        assertEquals("220 − 140 − 48 = 32", LayerWindowMetrics.DEEP_THUMB, m.thumbAt(4), 0f)
        assertEquals(m.thumbAt(4), m.thumbAt(8), 0f)
        assertEquals("220 − 140 − 12 = 68: the full thumbnail", 62f, m.thumbAt(1), 0f)
        assertEquals("220 − 140 − 24", 56f, m.thumbAt(2), 0f)
        assertEquals("220 − 140 − 36", 44f, m.thumbAt(3), 0f)
        // A folder's thumbnail opens and closes it: never under a 40 dp target.
        assertEquals(LayerWindowMetrics.FOLDER_THUMB_MIN, m.thumbAt(4, folder = true), 0f)
        assertEquals(56f, m.thumbAt(2, folder = true), 0f)
        // A 360 dp phone's 188 dp list: deep rows at the smallest thumbnail.
        val narrow = LayerWindowMetrics.of(350f, 520f)
        assertEquals(LayerWindowMetrics.DEEP_THUMB, narrow.thumbAt(2), 0f)
        // A wide window keeps the full thumbnail at every depth.
        val wide = LayerWindowMetrics.of(600f, 700f)
        assertEquals(wide.thumb, wide.thumbAt(4), 0f)
    }
}
