package com.brushwork.paint.ui.layers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 review (item 8, I11): every folder operation of the layer window, on a tree with a
 * pass-through folder below 100 % holding an isolated Screen folder (a clip group and an
 * adjustment layer inside), is ONE undo step, and undo then redo restore exactly what was
 * there: the flat order, every parent, folder flag, property, bitmap and mask (by identity), and
 * the picture bit for bit. Run through the operations the window calls (`LayerOps`, the
 * controller), each from the same starting tree.
 */
@RunWith(RobolectricTestRunner::class)
class FolderUndoRedoRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 48
    private val h = 36

    private class Scene(val c: EditorController, val under: Layer, val f: Layer, val a: Layer, val s: Layer, val b: Layer, val clip: Layer, val adj: Layer, val top: Layer, val empty: Layer)

    private fun disc(doc: Document, name: String, color: Int, cx: Float, cy: Float, r: Float): Layer {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        Canvas(bmp).drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return Layer(doc.newLayerId(), name, bmp)
    }

    private fun folder(doc: Document, name: String, passThrough: Boolean): Layer =
        Layer.newFolder(doc.newLayerId(), name).also { it.folder = it.folder!!.copy(passThrough = passThrough) }

    /**
     * Bottom first: BG, Under, [F pass-through 70 %: A Multiply, [S isolated Screen 80 %: B, Clip
     * (clipping, Overlay), Adj (invert 50 %)]], Top, [Empty]. The active layer is Top.
     */
    private fun scene(): Scene {
        val settings = AppSettings(RuntimeEnvironment.getApplication())
        settings.prefs.edit().clear().commit()
        val doc = Document("undo", "undo", w, h)
        val bg = Layer(doc.newLayerId(), "BG", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0xFF8090A0.toInt()) })
        val under = disc(doc, "Under", 0xFFE04020.toInt(), 14f, 14f, 10f)
        val f = folder(doc, "F", passThrough = true).also { it.opacity = 0.7f }
        val a = disc(doc, "A", 0xC02060F0.toInt(), 24f, 18f, 12f).also { it.blendMode = LayerBlendMode.MULTIPLY; it.parentId = f.id }
        val s = folder(doc, "S", passThrough = false).also { it.blendMode = LayerBlendMode.SCREEN; it.opacity = 0.8f; it.parentId = f.id }
        val b = disc(doc, "B", 0xFF30C060.toInt(), 30f, 20f, 11f).also { it.parentId = s.id }
        val clip = disc(doc, "Clip", 0xB0F0F020.toInt(), 36f, 24f, 10f).also { it.clipping = true; it.blendMode = LayerBlendMode.OVERLAY; it.parentId = s.id }
        val adj = Layer(doc.newLayerId(), "Adj", BitmapUtils.createLayerBitmap(w, h)).also {
            it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.opacity = 0.5f; it.parentId = s.id
        }
        val top = disc(doc, "Top", 0x80FFFFFF.toInt(), 10f, 28f, 8f)
        val empty = folder(doc, "Empty", passThrough = true)
        doc.layers += listOf(bg, under, a, b, clip, adj, s, f, top, empty)
        assertNull(LayerTree.check(doc.layers))
        doc.activeLayerIndex = doc.layers.indexOf(top)
        val c = EditorController(RuntimeEnvironment.getApplication(), doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return Scene(c, under, f, a, s, b, clip, adj, top, empty)
    }

    /** Everything an undo must give back. */
    private data class Snapshot(val rows: List<String>, val bitmaps: List<Int>, val picture: List<Int>)

    private fun snap(c: EditorController): Snapshot {
        val layers = c.doc.layers
        val rows = layers.map { l ->
            "${l.id}:${l.name}:p${l.parentId}:${l.folder}:${l.folderOpen}:${l.props()}:${l.adjustment?.filterId}"
        }
        val bitmaps = layers.flatMap { listOf(System.identityHashCode(it.bitmap), System.identityHashCode(it.mask)) }
        val out: Bitmap = Compositor(c.doc) { null }.renderFlattened()
        val px = IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
        return Snapshot(rows, bitmaps, px.toList())
    }

    private fun check(name: String, op: (Scene) -> Unit) {
        val s = scene()
        val c = s.c
        val before = snap(c)
        val steps = c.undoManager.undoCount
        op(s)
        assertEquals("$name: one undo step", steps + 1, c.undoManager.undoCount)
        assertNull("$name: a sound tree", LayerTree.check(c.doc.layers))
        val after = snap(c)
        assertTrue("$name: changed something", after != before)
        c.undo()
        assertEquals("$name: undo restores the order, parents and properties", before.rows, snap(c).rows)
        assertEquals("$name: undo restores the bitmaps", before.bitmaps, snap(c).bitmaps)
        assertTrue("$name: undo restores the picture bit for bit", before.picture == snap(c).picture)
        c.redo()
        val again = snap(c)
        assertEquals("$name: redo restores the order, parents and properties", after.rows, again.rows)
        assertEquals("$name: redo restores the bitmaps", after.bitmaps, again.bitmaps)
        assertTrue("$name: redo restores the picture bit for bit", after.picture == again.picture)
        c.undo()
        assertEquals("$name: undone again", before, snap(c))
    }

    @Test
    fun everyFolderOperationIsOneStepAndUndoRedoRestoreItExactly() {
        check("New folder") { it.c.addFolder() }
        check("Put in new folder") { it.c.putInNewFolder(it.a) }
        check("Move into folder above") { assertTrue(it.c.putIntoFolderAbove(it.under)) }
        check("Move out of folder") { assertTrue(it.c.takeOutOfFolder(it.a)) }
        check("Move layer down (a folder's block)") { it.c.moveLayerDown(it.f) }
        check("Move layer up (out of the folder's top)") { it.c.moveLayerUp(it.s) }
        check("Merge folder (isolated)") { LayerOps.mergeFolder(it.c, it.s) }
        check("Merge folder (pass-through below 100 %)") { LayerOps.mergeFolder(it.c, it.f) }
        check("Duplicate folder") { LayerOps.duplicate(it.c, it.f) }
        check("Layer from folder") { LayerOps.layerFromFolder(it.c, it.s) }
        check("Ungroup folder") { LayerOps.ungroupFolder(it.c, it.s) }
        check("Delete all") { it.c.deleteFolder(it.f, keepChildren = false) }
        check("Folder only") { it.c.deleteFolder(it.f, keepChildren = true) }
        check("Delete an empty folder") { it.c.deleteLayer(it.empty) }
        check("Blend picked on a pass-through folder") { LayerOps.setBlendMode(it.c, it.f, LayerBlendMode.MULTIPLY) }
        check("Pass through on an isolated folder") { LayerOps.setPassThrough(it.c, it.s) }
        check("Hide a folder") { it.c.toggleVisibility(it.f) }
        check("Lock a folder") { it.c.toggleLock(it.s) }
        check("Folder opacity") { it.c.setLayerProps(it.s, it.s.props().copy(opacity = 0.3f), "Opacity") }
        check("Clip a folder") { it.c.toggleClipping(it.f) }
        check("Merge down inside a folder") { LayerOps.mergeDown(it.c, it.clip) }
        check("Delete a layer inside a folder") { it.c.deleteLayer(it.b) }
        check("Duplicate a layer inside a folder") { LayerOps.duplicate(it.c, it.a) }
    }
}
