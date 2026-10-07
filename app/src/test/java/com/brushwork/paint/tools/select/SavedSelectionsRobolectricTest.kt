package com.brushwork.paint.tools.select

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.7 (§3.14 [G], the design's `SavedSelectionsRobolectricTest` cases on the controller): Load
 * replaces the selection; Add / Subtract / Intersect equal `Selection.combine`; Update bumps the
 * revision and the file name; save and reload keep them; a canvas flip maps each saved selection
 * (its mask is the flipped mask), a crop crops them, the active selection is dropped as in v1.6
 * and one undo restores both; save and delete undo; the 33rd save and a full store are refused.
 * The layer window's rows are `ui/layers/SavedSelectionRows*` tests.
 */
@RunWith(RobolectricTestRunner::class)
class SavedSelectionsRobolectricTest {
    private val w = 40
    private val h = 30

    private fun controller(job: Job, doc: Document = document()): EditorController {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        return EditorController(app, doc, CoroutineScope(Dispatchers.Unconfined + job), AppSettings(app)).also { it.viewTransform.set(Matrix()) }
    }

    private fun document(id: String = "t"): Document {
        val doc = Document(id, id, w, h)
        doc.layers += Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0xFF3366CC.toInt()) })
        return doc
    }

    private fun awaitIdle(job: Job) = runBlocking {
        withTimeout(60_000) {
            while (true) {
                val active = job.children.filter { it.isActive }.toList()
                if (active.isEmpty()) break
                active.forEach { it.join() }
            }
        }
    }

    private fun rect(r: Rect): Selection {
        val p = Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }
        return Selection.fromPath(p, w, h, antiAlias = false)
    }

    private fun disc(cx: Float, cy: Float, r: Float): Selection =
        Selection.fromPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }, w, h, antiAlias = true)

    private fun bytes(s: Selection?): ByteArray? = s?.let { BitmapUtils.alpha8ToBytes(it.mask) }

    /** Saves [s] as the next saved selection (the active selection afterwards: none). */
    private fun save(c: EditorController, job: Job, s: Selection): SavedSelection {
        c.setSelection(s, recordUndo = false)
        assertTrue(c.saveSelection())
        awaitIdle(job)
        c.setSelection(null, recordUndo = false)
        return c.doc.savedSelections.last()
    }

    @Test
    fun loadReplacesTheSelection() {
        val job = SupervisorJob()
        val c = controller(job)
        val a = disc(12f, 10f, 6.5f)
        val saved = save(c, job, a)
        assertEquals("Selection 1", saved.name)
        val b = rect(Rect(20, 5, 35, 25))
        c.setSelection(b, recordUndo = false)
        val steps = c.undoManager.undoCount
        c.loadSavedSelection(saved.id, SelectionMode.REPLACE)
        awaitIdle(job)
        assertArrayEquals(bytes(a), bytes(c.selection))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(SavedSelectionLabels.LOAD, c.undoManager.undoLabel)
        c.undo()
        assertArrayEquals("one undo: the selection before", bytes(b), bytes(c.selection))
    }

    @Test
    fun addSubtractAndIntersectEqualCombine() {
        val job = SupervisorJob()
        val c = controller(job)
        val a = disc(15f, 15f, 9.2f)
        val saved = save(c, job, a)
        val base = rect(Rect(10, 4, 34, 22))
        val loaded = saved.toSelection(w, h)
        val labels = mapOf(SelectionMode.ADD to SavedSelectionLabels.ADD, SelectionMode.SUBTRACT to SavedSelectionLabels.SUBTRACT, SelectionMode.INTERSECT to SavedSelectionLabels.INTERSECT)
        for ((mode, label) in labels) {
            c.setSelection(base, recordUndo = false)
            c.loadSavedSelection(saved.id, mode)
            awaitIdle(job)
            assertArrayEquals("$mode", bytes(base.combine(loaded, mode)), bytes(c.selection))
            assertEquals(label, c.undoManager.undoLabel)
        }
        // Without a selection: Add loads it, Subtract and Intersect do nothing.
        c.setSelection(null, recordUndo = false)
        val steps = c.undoManager.undoCount
        c.loadSavedSelection(saved.id, SelectionMode.SUBTRACT); awaitIdle(job)
        c.loadSavedSelection(saved.id, SelectionMode.INTERSECT); awaitIdle(job)
        assertNull(c.selection)
        assertEquals(steps, c.undoManager.undoCount)
        c.loadSavedSelection(saved.id, SelectionMode.ADD); awaitIdle(job)
        assertArrayEquals(bytes(a), bytes(c.selection))
    }

    @Test
    fun updateBumpsTheRevisionAndTheFileName() {
        val job = SupervisorJob()
        val c = controller(job)
        val first = save(c, job, rect(Rect(2, 2, 10, 10)))
        c.setSelection(rect(Rect(5, 6, 30, 20)), recordUndo = false)
        assertTrue(c.updateSavedSelection(first.id))
        awaitIdle(job)
        val updated = c.doc.savedSelections.single()
        assertEquals(first.id, updated.id)
        assertEquals("Selection 1", updated.name)
        assertEquals(first.revision + 1, updated.revision)
        assertEquals(Rect(5, 6, 30, 20), updated.bounds)
        assertNotEquals(ProjectFormat.selectionFile(first.id, first.revision), ProjectFormat.selectionFile(updated.id, updated.revision))
        assertEquals(HistoryLabels.UPDATE_SAVED_SELECTION, c.undoManager.undoLabel)
        c.undo()
        assertSame(first, c.doc.savedSelections.single())
        // Rename: one step, the pixels kept.
        c.renameSavedSelection(first.id, "  Sky  ")
        assertEquals("Sky", c.doc.savedSelections.single().name)
        assertSame(first.packed, c.doc.savedSelections.single().packed)
        assertEquals(HistoryLabels.RENAME_SAVED_SELECTION, c.undoManager.undoLabel)
    }

    @Test
    fun saveAndReloadKeepThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        val job = SupervisorJob()
        val c = controller(job, document("g-saved"))
        save(c, job, disc(12f, 12f, 8f))
        save(c, job, rect(Rect(20, 3, 39, 28)))
        c.renameSavedSelection(c.doc.savedSelections[1].id, "Window")
        repo.save(c.doc, null)
        val loaded = repo.load(c.doc.id)
        assertEquals(c.doc.savedSelections.map { it.id }, loaded.savedSelections.map { it.id })
        assertEquals(listOf("Selection 1", "Window"), loaded.savedSelections.map { it.name })
        for ((a, b) in c.doc.savedSelections.zip(loaded.savedSelections)) {
            assertEquals(a.bounds, b.bounds)
            assertEquals(a.revision, b.revision)
            assertArrayEquals(bytes(a.toSelection(w, h)), bytes(b.toSelection(w, h)))
        }
    }

    @Test
    fun aFlipMapsThemAndDropsTheActiveSelectionAndOneUndoRestoresBoth() {
        val job = SupervisorJob()
        val c = controller(job)
        save(c, job, disc(10f, 9f, 6.3f))
        save(c, job, rect(Rect(22, 4, 37, 26)))
        val saved = c.doc.savedSelections
        val active = rect(Rect(3, 3, 9, 9))
        c.setSelection(active, recordUndo = false)
        assertTrue(CanvasOps.applyFlip(c, horizontal = true))
        awaitIdle(job)
        assertNull("the active selection is dropped (v1.6)", c.selection)
        assertEquals(2, c.doc.savedSelections.size)
        for (i in saved.indices) {
            val before = bytes(saved[i].toSelection(w, h))!!
            val expected = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) expected[y * w + x] = before[y * w + (w - 1 - x)]
            assertArrayEquals("saved selection $i is flipped", expected, bytes(c.doc.savedSelections[i].toSelection(w, h)))
            assertTrue("a new revision", c.doc.savedSelections[i].revision > saved[i].revision)
        }
        c.undo()
        assertSame("one undo: the very list", saved, c.doc.savedSelections)
        assertArrayEquals("and the active selection", bytes(active), bytes(c.selection))

        // A crop crops them (the second one is outside the crop: dropped).
        assertTrue(CanvasOps.applyCrop(c, Rect(0, 0, 20, 20)))
        awaitIdle(job)
        assertEquals(listOf(saved[0].id), c.doc.savedSelections.map { it.id })
        assertEquals(20, c.doc.width)
        val cropped = c.doc.savedSelections.single()
        assertTrue(cropped.bounds.right <= 20 && cropped.bounds.bottom <= 20)
        c.undo()
        assertSame(saved, c.doc.savedSelections)
    }

    @Test
    fun saveAndDeleteUndo() {
        val job = SupervisorJob()
        val c = controller(job)
        val first = save(c, job, rect(Rect(2, 2, 10, 10)))
        assertEquals(SavedSelectionLabels.SAVE, c.undoManager.undoLabel)
        c.undo()
        assertTrue(c.doc.savedSelections.isEmpty())
        c.redo()
        assertSame(first, c.doc.savedSelections.single())
        c.deleteSavedSelection(first.id)
        assertTrue(c.doc.savedSelections.isEmpty())
        assertEquals(SavedSelectionLabels.DELETE, c.undoManager.undoLabel)
        c.undo()
        assertSame(first, c.doc.savedSelections.single())
    }

    @Test
    fun theThirtyThirdAndAFullStoreAreRefused() {
        val job = SupervisorJob()
        val c = controller(job)
        for (i in 0 until SavedSelection.MAX) save(c, job, rect(Rect(i % 30, 0, i % 30 + 5, 5 + i % 20)))
        assertEquals(32, c.doc.savedSelections.size)
        assertEquals("Selection 32", c.doc.savedSelections.last().name)
        c.setSelection(rect(Rect(1, 1, 4, 4)), recordUndo = false)
        c.message = null
        assertFalse("the 33rd", c.saveSelection())
        assertEquals(SavedSelectionLabels.LIMIT, c.message)
        assertEquals(32, c.doc.savedSelections.size)

        // 32 MB of packed data: full.
        val big = SavedSelection(c.doc.newSelectionId(), "Big", Rect(0, 0, 1, 1), ByteArray(SavedSelection.MAX_TOTAL_BYTES.toInt()), 1)
        c.doc.savedSelections = listOf(big)
        c.message = null
        assertFalse(c.saveSelection())
        assertEquals(SavedSelectionLabels.FULL, c.message)
        assertNotNull(c.selection)
    }
}
