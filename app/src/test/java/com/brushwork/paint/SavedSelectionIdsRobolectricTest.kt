package com.brushwork.paint

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.storage.ProjectFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 14, §4.2): a saved selection's file is `sel_<id>_r<revision>.bin`, and a save skips
 * a file the last save listed. So an id is never reused in a document (`Document.newSelectionId`,
 * persisted as `nextSelectionId`), a deleted or undone entry's included, and an update after an
 * undo never reuses a revision: either would let a save keep another selection's pixels.
 */
@RunWith(RobolectricTestRunner::class)
class SavedSelectionIdsRobolectricTest {
    private val w = 40
    private val h = 30

    private fun controller(job: Job): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(w, h))
        return EditorController(app, doc, CoroutineScope(Dispatchers.Unconfined + job), AppSettings(app)).also { it.viewTransform.set(Matrix()) }
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

    /** The active selection becomes [r] without a step of its own (only the saved-selection steps are undone below). */
    private fun select(c: EditorController, r: Rect) {
        val p = Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }
        c.setSelection(Selection.fromPath(p, w, h, antiAlias = false), recordUndo = false)
    }

    @Test
    fun anIdIsNeverReusedAfterADelete() {
        val job = SupervisorJob()
        val c = controller(job)
        select(c, Rect(2, 2, 10, 10))
        assertTrue(c.saveSelection()); awaitIdle(job)
        select(c, Rect(5, 5, 20, 20))
        assertTrue(c.saveSelection()); awaitIdle(job)
        val second = c.doc.savedSelections.last()
        c.deleteSavedSelection(second.id)
        select(c, Rect(1, 1, 30, 25))
        assertTrue(c.saveSelection()); awaitIdle(job)
        val third = c.doc.savedSelections.last()
        assertTrue("a new id, above every one given", third.id > second.id)
        assertNotEquals(ProjectFormat.selectionFile(second.id, second.revision), ProjectFormat.selectionFile(third.id, third.revision))
        assertTrue("persisted: the next id stays above", c.doc.nextSelectionId > third.id)
        // Undo the save and the delete: the deleted entry comes back with its own id, the ids stay distinct.
        c.undo(); c.undo()
        assertEquals(second.id, c.doc.savedSelections.last().id)
        assertEquals(c.doc.savedSelections.size, c.doc.savedSelections.map { it.id }.toSet().size)
    }

    @Test
    fun anUpdateAfterAnUndoGetsANewRevision() {
        val job = SupervisorJob()
        val c = controller(job)
        select(c, Rect(2, 2, 10, 10))
        assertTrue(c.saveSelection()); awaitIdle(job)
        val id = c.doc.savedSelections.single().id
        assertEquals(1L, c.doc.savedSelections.single().revision)
        select(c, Rect(4, 4, 20, 20))
        assertTrue(c.updateSavedSelection(id)); awaitIdle(job)
        assertEquals("the revision + 1", 2L, c.doc.savedSelections.single().revision)
        c.undo()
        assertEquals(1L, c.doc.savedSelections.single().revision)
        select(c, Rect(6, 6, 30, 28))
        assertTrue(c.updateSavedSelection(id)); awaitIdle(job)
        assertEquals("revision 2 (and its file) belongs to the undone update", 3L, c.doc.savedSelections.single().revision)
    }
}
