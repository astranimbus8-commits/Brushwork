package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.layers.LayerOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 (item 11, design §3.11 a) review: the folder audit's "transform layer" entry with area F.
 * The same nested document and gesture as `FolderAuditTest` (F1 holding A and the isolated F2
 * holding B, a selection, F1 active; the layer menu's Transform, a drag, the tool put away) now
 * records ONE step that moves both layers inside F1, and undo / redo restore the very tree,
 * pixels and composite. The audit's exemption of "transform layer" (no step until F) can go at
 * F's merge.
 */
@RunWith(RobolectricTestRunner::class)
class TransformFolderAuditRobolectricTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 96
    private val h = 64

    private val red = 0xFFDD2211.toInt()
    private val green = 0xFF22AA44.toInt()
    private val blue = 0xFF2244CC.toInt()

    /** Bottom first: Background (white), [A (red), [B (green)] F2] F1, Top (blue); F2 isolated. */
    private fun nested(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("audit", "audit", w, h)
        fun pixel(name: String, parent: Long, draw: (Bitmap) -> Unit) =
            Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { it.parentId = parent; draw(it.bitmap) }
        fun rect(color: Int, r: Rect): (Bitmap) -> Unit = { b -> Canvas(b).drawRect(r, Paint().apply { this.color = color }) }
        val bg = pixel("Background", Layer.ROOT_ID) { it.eraseColor(-1) }
        val f1 = Layer.newFolder(doc.newLayerId(), "F1")
        val f2 = Layer.newFolder(doc.newLayerId(), "F2", FolderSpec(passThrough = false)).also { it.parentId = f1.id }
        val a = pixel("A", f1.id, rect(red, Rect(10, 10, 40, 30)))
        val b = pixel("B", f2.id, rect(green, Rect(30, 20, 60, 50)))
        val top = pixel("Top", Layer.ROOT_ID, rect(blue, Rect(70, 5, 90, 25)))
        doc.layers += listOf(bg, a, b, f2, f1, top)
        doc.activeLayerIndex = 5
        assertNull(LayerTree.check(doc.layers))
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun EditorController.byName(name: String): Layer = doc.layers.first { it.name == name }

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun picture(c: EditorController): List<Any> =
        c.doc.layers.map { l -> Triple(l.id, l.parentId, if (l.isFolder) "folder" else px(l.bitmap).contentHashCode()) }

    private fun flat(c: EditorController): IntArray = c.compositor.renderFlattened().let { b -> try { px(b) } finally { b.recycle() } }

    /** Runs [action], then waits for the background jobs it started, the main looper running meanwhile. */
    private fun settled(what: String, action: () -> Unit) {
        val baseline = job.children.toSet()
        action()
        val until = System.currentTimeMillis() + 15_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val busy = job.children.filter { it.isActive && it !in baseline }.toList()
            if (busy.isEmpty()) break
            check(System.currentTimeMillis() < until) { "$what: ${busy.size} background jobs did not finish" }
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun assertFoldersIntact(c: EditorController, what: String) {
        assertNull(what, LayerTree.check(c.doc.layers))
        for (l in c.doc.layers) if (l.isFolder) assertSame("$what: ${l.name} keeps FOLDER_BITMAP", Layer.FOLDER_BITMAP, l.bitmap)
        assertFalse("$what: FOLDER_BITMAP alive", Layer.FOLDER_BITMAP.isRecycled)
    }

    @Test
    fun transformingAFolderFromTheLayerMenuIsOneStepThatUndoAndRedoRestore() {
        val c = nested()
        c.selectLayer(c.byName("A"))
        c.setSelection(Selection.fromPath(Path().apply { addRect(RectF(5f, 5f, 50f, 40f), Path.Direction.CW) }, w, h, antiAlias = false), label = "Select")
        assertTrue(c.copySelection())
        val f1 = c.byName("F1")
        c.selectLayer(f1)
        val steps = c.undoManager.undoCount
        val before = picture(c)
        val flatBefore = flat(c)

        settled("transform layer") {
            LayerOps.transform(c, f1)
            c.drag(12f to 12f, 20f to 16f, 28f to 22f, 36f to 26f)
        }
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertEquals("the folder was lifted", TransformTool.Lifted.FOLDER, tool.lifted)
        settled("put away") { c.selectTool(ToolId.LASSO) }
        assertFoldersIntact(c, "transformed")

        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        // Both layers inside F1 changed (the drag starts on the box's corner handle: a scale);
        // Top and the background did not.
        val after = picture(c)
        val changed = c.doc.layers.indices.filter { before[it] != after[it] }.map { c.doc.layers[it].name }
        assertEquals(listOf("A", "B"), changed)

        settled("undone") { c.undo() }
        assertFoldersIntact(c, "undone")
        assertEquals("undone: the tree and pixels", before, picture(c))
        assertTrue("undone: the picture", flatBefore.contentEquals(flat(c)))
        settled("redone") { c.redo() }
        assertFoldersIntact(c, "redone")
        assertEquals("redone", after, picture(c))
    }
}
