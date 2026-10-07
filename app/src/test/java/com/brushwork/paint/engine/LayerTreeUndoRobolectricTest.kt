package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.random.Random

/**
 * v1.7 F2 (§3.8, §4.4): every layer-tree operation of the controller is ONE undo step, and its
 * undo and redo restore the layer order, the parents, the folder settings and the pixels bitwise
 * (the same `Layer` and `Bitmap` instances). A refused operation leaves no step and changes
 * nothing. Each operation once on a nested tree, then 500 random sequences, each undone to its
 * start and redone to its end. Every state keeps I11 and every folder's `FOLDER_BITMAP`.
 */
@RunWith(RobolectricTestRunner::class)
class LayerTreeUndoRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val size = 24

    /** What the test compares: identity, structure, folder settings and the pixels' hash. */
    private data class Row(val layer: Layer, val id: Long, val parent: Long, val folder: FolderSpec?, val bitmap: Bitmap, val pixels: Int)

    private fun state(c: EditorController): List<Row> =
        c.doc.layers.map { l -> Row(l, l.id, l.parentId, l.folder, l.bitmap, if (l.isFolder) 0 else hash(l.bitmap)) }

    private fun hash(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.contentHashCode()
    }

    /**
     * Bottom first: L1, [L3, [L4] F2] F1, L5 (F1 holds L3 and F2; F2 holds L4), every pixel layer
     * painted its own colour, L5 active.
     */
    private fun nested(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", size, size)
        fun pixel(name: String, color: Int, parent: Long = Layer.ROOT_ID): Layer =
            Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(size, size)).also { l ->
                l.parentId = parent
                Canvas(l.bitmap).drawRect(Rect(2 + l.id.toInt(), 3, 12 + l.id.toInt(), 15), Paint().apply { this.color = color })
            }
        val l1 = pixel("L1", 0xFF2266CC.toInt())
        val f1 = Layer.newFolder(doc.newLayerId(), "F1")
        val f2 = Layer.newFolder(doc.newLayerId(), "F2", FolderSpec(passThrough = false)).also { it.parentId = f1.id }
        val l3 = pixel("L3", 0x80CC2200.toInt(), f1.id)
        val l4 = pixel("L4", 0xFF11AA44.toInt(), f2.id)
        val l5 = pixel("L5", 0xC0AA00AA.toInt())
        doc.layers += listOf(l1, l3, l4, f2, f1, l5)
        doc.activeLayerIndex = 5
        assertNull(LayerTree.check(doc.layers))
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun assertTree(c: EditorController, what: String) {
        assertNull(what, LayerTree.check(c.doc.layers))
        for (l in c.doc.layers) if (l.isFolder) assertSame("$what: a folder keeps the shared bitmap", Layer.FOLDER_BITMAP, l.bitmap)
        assertTrue(what, c.doc.activeLayerIndex in c.doc.layers.indices)
        assertTrue("$what: FOLDER_BITMAP alive", !Layer.FOLDER_BITMAP.isRecycled)
    }

    /**
     * Runs [op] and checks the step: none and no change when refused; else exactly one, whose
     * undo restores [state] before and whose redo restores it after. Returns true for a step.
     */
    private fun stepChecked(c: EditorController, what: String, op: () -> Unit): Boolean {
        val before = state(c)
        val steps = c.undoManager.undoCount
        op()
        assertTree(c, what)
        val after = state(c)
        val added = c.undoManager.undoCount - steps
        if (added == 0) {
            assertEquals("$what: refused, nothing changed", before, after)
            return false
        }
        assertEquals("$what: one step", 1, added)
        c.undo()
        assertTree(c, "$what, undone")
        assertEquals("$what: undo restores order, parents and pixels", before, state(c))
        c.redo()
        assertTree(c, "$what, redone")
        assertEquals("$what: redo restores the result", after, state(c))
        return true
    }

    private fun EditorController.byName(name: String): Layer = doc.layers.first { it.name == name }

    @Test
    fun everyOperationIsOneStepThatUndoesAndRedoesBitwise() {
        val ops: List<Pair<String, (EditorController) -> Unit>> = listOf(
            "add folder" to { c -> c.addFolder() },
            "add layer" to { c -> c.addLayer() },
            "put in new folder" to { c -> c.putInNewFolder(c.byName("L1")) },
            "move into folder above" to { c -> c.putIntoFolderAbove(c.byName("L1")) },
            "move out of folder" to { c -> c.takeOutOfFolder(c.byName("L3")) },
            "drag a folder's block" to { c -> c.moveBlock(c.byName("F2"), c.doc.layers.lastIndex, Layer.ROOT_ID) },
            "drag into a folder" to { c -> c.moveBlock(c.byName("L5"), c.doc.indexOf(c.byName("F2")), c.byName("F2").id) },
            "drag to the bottom" to { c -> c.moveBlock(c.byName("L5"), 0, Layer.ROOT_ID) },
            "layer up" to { c -> c.moveLayerUp(c.byName("L4")) },
            "layer down" to { c -> c.moveLayerDown(c.byName("L3")) },
            "folder up" to { c -> c.moveLayerUp(c.byName("F2")) },
            "pass through" to { c -> c.setFolderPassThrough(c.byName("F2"), true) },
            "duplicate folder" to { c -> c.duplicateLayer(c.byName("F1")) },
            "layer from folder" to { c -> c.layerFromFolder(c.byName("F1")) },
            "merge folder" to { c -> c.mergeFolder(c.byName("F2")) },
            "ungroup folder" to { c -> c.ungroupFolder(c.byName("F2")) },
            "delete folder only" to { c -> c.deleteFolder(c.byName("F2"), keepChildren = true) },
            "delete folder and its layers" to { c -> c.deleteFolder(c.byName("F1"), keepChildren = false) },
            "delete a child" to { c -> c.deleteLayer(c.byName("L4")) },
            "duplicate a child" to { c -> c.duplicateLayer(c.byName("L4")) },
            "merge down on a folder merges it" to { c -> c.mergeDown(c.byName("F2")) },
        )
        for ((name, op) in ops) {
            val c = nested()
            assertTrue("$name records one step", stepChecked(c, name) { op(c) })
        }
    }

    @Test
    fun openingAFolderIsNoStep() {
        val c = nested()
        val f1 = c.byName("F1")
        val before = state(c)
        c.setFolderOpen(f1, false)
        assertEquals(false, f1.folderOpen)
        assertEquals(0, c.undoManager.undoCount)
        assertEquals(before, state(c))
    }

    @Test
    fun fiveHundredRandomSequencesUndoToTheirStartAndRedoToTheirEnd() {
        val rnd = Random(1717)
        repeat(500) { seq ->
            val c = nested()
            val start = state(c)
            var steps = 0
            repeat(8) { k ->
                val what = "sequence $seq, op $k"
                val layers = c.doc.layers
                val any = layers[rnd.nextInt(layers.size)]
                val folders = layers.filter { it.isFolder }
                val folder = folders.randomOrNull(rnd)
                val stepped = stepChecked(c, what) {
                    when (rnd.nextInt(17)) {
                        0 -> c.addLayer()
                        1 -> c.addFolder()
                        2 -> c.putInNewFolder(any)
                        3 -> c.putIntoFolderAbove(any)
                        4 -> c.takeOutOfFolder(any)
                        5 -> c.moveBlock(any, rnd.nextInt(layers.size), null)
                        6 -> c.moveLayerUp(any)
                        7 -> c.moveLayerDown(any)
                        8 -> folder?.let { c.setFolderPassThrough(it, !(it.folder?.passThrough ?: true)) }
                        9 -> folder?.let { c.mergeFolder(it) }
                        10 -> folder?.let { c.layerFromFolder(it) }
                        11 -> folder?.let { c.ungroupFolder(it) }
                        12 -> folder?.let { c.deleteFolder(it, keepChildren = rnd.nextBoolean()) }
                        13 -> c.duplicateLayer(any)
                        14 -> c.deleteLayer(any)
                        15 -> c.mergeDown(any)
                        else -> {
                            val folderId = folder?.id ?: Layer.ROOT_ID
                            c.moveBlock(any, rnd.nextInt(layers.size), folderId)
                        }
                    }
                }
                if (stepped) steps++
            }
            val end = state(c)
            repeat(steps) { c.undo() }
            assertTree(c, "sequence $seq undone")
            assertEquals("sequence $seq: undoing every step restores the start", start, state(c))
            repeat(steps) { c.redo() }
            assertTree(c, "sequence $seq redone")
            assertEquals("sequence $seq: redoing every step restores the end", end, state(c))
        }
    }
}
