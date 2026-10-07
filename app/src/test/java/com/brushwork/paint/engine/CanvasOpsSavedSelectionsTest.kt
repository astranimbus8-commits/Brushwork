package com.brushwork.paint.engine

import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SavedSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 F2 (item 14, §3.14): a canvas operation carries the saved selections in its undo step
 * (`CanvasChangeAction`). With the F2 stub of `SavedSelectionOps.mappedForCanvas`, an operation
 * that moves the artwork (a flip) empties the list and one undo restores it; one whose
 * `CanvasResult` keeps the geometry and the size keeps the list.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasOpsSavedSelectionsTest {
    private val w = 40
    private val h = 30

    private fun controller(job: Job = SupervisorJob()): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0xFF3366CC.toInt()) })
        doc.savedSelections = listOf(
            SavedSelection(1L, "Selection 1", Rect(2, 3, 12, 9), byteArrayOf(1, 2, 3), 1L),
            SavedSelection(2L, "Selection 2", Rect(5, 5, 30, 25), byteArrayOf(4, 5), 2L),
        )
        return EditorController(app, doc, CoroutineScope(Dispatchers.Unconfined + job), AppSettings(app))
    }

    private fun commit(c: EditorController, label: String, op: (CanvasSnapshot) -> CanvasResult) {
        val snap = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, label, snap, op(snap))
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

    @Test
    fun aFlipEmptiesTheListAndOneUndoRestoresIt() {
        val c = controller()
        val saved = c.doc.savedSelections
        commit(c, "Flip canvas") { CanvasOps.flip(it, horizontal = true) }
        assertEquals(emptyList<SavedSelection>(), c.doc.savedSelections)
        c.undo()
        assertSame("one undo restores the very list", saved, c.doc.savedSelections)
        c.redo()
        assertEquals(emptyList<SavedSelection>(), c.doc.savedSelections)
        c.undo()
        assertSame(saved, c.doc.savedSelections)
    }

    @Test
    fun aBackgroundFlipCarriesTheListInItsStep() {
        val job = SupervisorJob()
        val c = controller(job)
        val saved = c.doc.savedSelections
        assertTrue(CanvasOps.applyFlip(c, horizontal = false))
        awaitIdle(job)
        assertEquals(null, c.busyMessage)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(emptyList<SavedSelection>(), c.doc.savedSelections)
        c.undo()
        assertSame(saved, c.doc.savedSelections)
    }

    @Test
    fun anIdentityResultOfTheSameSizeKeepsTheList() {
        val c = controller()
        val saved = c.doc.savedSelections
        commit(c, "Crop") { CanvasOps.cropTo(it, Rect(0, 0, w, h)) }
        assertSame(saved, c.doc.savedSelections)
        c.undo()
        assertSame(saved, c.doc.savedSelections)
        c.redo()
        assertSame(saved, c.doc.savedSelections)
    }

    @Test
    fun anIdentityResultOfAnotherSizeClearsTheListWithTheStub() {
        val c = controller()
        val saved = c.doc.savedSelections
        commit(c, "Crop") { CanvasOps.cropTo(it, Rect(0, 0, w - 10, h - 10)) }
        assertEquals(emptyList<SavedSelection>(), c.doc.savedSelections)
        c.undo()
        assertSame(saved, c.doc.savedSelections)
    }
}
