package com.brushwork.paint.engine

import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.select.SavedSelectionOps
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
 * (`CanvasChangeAction`): after it the list is what `SavedSelectionOps.mappedForCanvas` makes of
 * it, and one undo restores the very list. On `main`, with the F2 stub, an operation that moves
 * the artwork (a flip) or changes the size empties the list (pinned by G's
 * `tools/select/SavedSelectionOpsStubTest`), and one that keeps the geometry and the size keeps
 * it. The expectations are taken from `mappedForCanvas` itself (compared by id, name, bounds and
 * pixels; the revision is the controller's), so they hold with G's mapping too (design §5.3: G
 * cannot change this file).
 */
@RunWith(RobolectricTestRunner::class)
class CanvasOpsSavedSelectionsTest {
    private val w = 40
    private val h = 30

    private fun rect(r: Rect): Selection {
        val p = Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }
        return Selection.fromPath(p, w, h, antiAlias = false)
    }

    private fun controller(job: Job = SupervisorJob()): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0xFF3366CC.toInt()) })
        doc.savedSelections = listOf(
            SavedSelection.of(doc.newSelectionId(), "Selection 1", rect(Rect(2, 3, 12, 9)), 1L)!!,
            SavedSelection.of(doc.newSelectionId(), "Selection 2", rect(Rect(5, 5, 30, 25)), 2L)!!,
        )
        return EditorController(app, doc, CoroutineScope(Dispatchers.Unconfined + job), AppSettings(app))
    }

    /** What a test compares: everything but the revision (and the instances). */
    private fun content(list: List<SavedSelection>) = list.map { listOf(it.id, it.name, Rect(it.bounds), it.packed.toList()) }

    /** Commits [op] on [c]'s document; returns what `mappedForCanvas` makes of the list for it. */
    private fun commit(c: EditorController, label: String, op: (CanvasSnapshot) -> CanvasResult): List<SavedSelection> {
        val snap = CanvasSnapshot.of(c.doc)
        val result = op(snap)
        val expected = SavedSelectionOps.mappedForCanvas(snap.savedSelections, result, snap.width, snap.height)
        CanvasOps.commit(c, label, snap, result)
        return expected
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
    fun aFlipMapsTheListAndOneUndoRestoresIt() {
        val c = controller()
        val saved = c.doc.savedSelections
        val expected = commit(c, "Flip canvas") { CanvasOps.flip(it, horizontal = true) }
        assertEquals(content(expected), content(c.doc.savedSelections))
        c.undo()
        assertSame("one undo restores the very list", saved, c.doc.savedSelections)
        c.redo()
        assertEquals(content(expected), content(c.doc.savedSelections))
        c.undo()
        assertSame(saved, c.doc.savedSelections)
    }

    @Test
    fun aBackgroundFlipCarriesTheListInItsStep() {
        val job = SupervisorJob()
        val c = controller(job)
        val saved = c.doc.savedSelections
        val snap = CanvasSnapshot.of(c.doc)
        val expected = SavedSelectionOps.mappedForCanvas(saved, CanvasOps.flip(snap, horizontal = false), w, h)
        assertTrue(CanvasOps.applyFlip(c, horizontal = false))
        awaitIdle(job)
        assertEquals(null, c.busyMessage)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(content(expected), content(c.doc.savedSelections))
        c.undo()
        assertSame(saved, c.doc.savedSelections)
    }

    @Test
    fun anIdentityResultOfTheSameSizeKeepsTheList() {
        val c = controller()
        val saved = c.doc.savedSelections
        val expected = commit(c, "Crop") { CanvasOps.cropTo(it, Rect(0, 0, w, h)) }
        assertEquals(content(saved), content(expected))
        assertEquals(content(saved), content(c.doc.savedSelections))
        c.undo()
        assertSame(saved, c.doc.savedSelections)
        c.redo()
        assertEquals(content(saved), content(c.doc.savedSelections))
    }

    /**
     * Gate 1 review: a mapped entry whose pixels changed gets a revision no earlier step used (its
     * file `sel_<id>_r<revision>.bin` is skipped by a save that listed it), even after an undo;
     * an entry the mapping kept (same `packed`) stays the very instance.
     */
    @Test
    fun aMappedEntryGetsARevisionNoUndoneStepUsed() {
        val c = controller()
        val saved = c.doc.savedSelections
        fun flipWith(packed: ByteArray) {
            val snap = CanvasSnapshot.of(c.doc)
            val first = snap.savedSelections[0]
            // What a mapping returns: the next revision by its own count, and the second entry as it was.
            val mapped = listOf(SavedSelection(first.id, first.name, Rect(first.bounds), packed, first.revision + 1), snap.savedSelections[1])
            CanvasOps.commit(c, "Flip canvas", snap, CanvasOps.flip(snap, horizontal = true), mapped)
        }
        flipWith(byteArrayOf(7))
        assertEquals(2L, c.doc.savedSelections[0].revision)
        assertSame("an entry the mapping kept", saved[1], c.doc.savedSelections[1])
        c.undo()
        assertSame(saved, c.doc.savedSelections)
        flipWith(byteArrayOf(8))
        assertEquals("revision 2 belongs to the undone flip", 3L, c.doc.savedSelections[0].revision)
        assertTrue(c.doc.savedSelections[0].packed.contentEquals(byteArrayOf(8)))
        c.undo()
        assertSame(saved, c.doc.savedSelections)
    }

    @Test
    fun aSmallerCropGivesWhatTheMappingMakesOfTheList() {
        val c = controller()
        val saved = c.doc.savedSelections
        val expected = commit(c, "Crop") { CanvasOps.cropTo(it, Rect(0, 0, w - 10, h - 10)) }
        assertEquals(content(expected), content(c.doc.savedSelections))
        c.undo()
        assertSame(saved, c.doc.savedSelections)
    }
}
