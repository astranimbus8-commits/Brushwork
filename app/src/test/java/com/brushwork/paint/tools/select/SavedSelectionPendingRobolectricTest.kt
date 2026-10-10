package com.brushwork.paint.tools.select

import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (§3.14 (c), area G): a saved selection still compressing is `pendingSavedSelections` at
 * once (its name and the id it will have), and lands as ONE step in the order asked for. Undo,
 * redo, a history mark and a canvas operation land it first (waiting for it), so undo takes it
 * back (it never lands on top of what undo took back), a mark keeps it, and a flip maps it.
 * Compression is slowed by the controller's test seam (a delay on the worker); the scope is the
 * app's (main thread, the looper pumped).
 */
@RunWith(RobolectricTestRunner::class)
class SavedSelectionPendingRobolectricTest {
    private val scope = Smoke.newScope()
    private val w = 64
    private val h = 48

    @After
    fun tearDown() = scope.cancel()

    private fun controller(): EditorController =
        Smoke.controller(RuntimeEnvironment.getApplication(), Smoke.document(w, h, layers = 1, whiteBottom = true), scope)

    private fun rect(r: Rect): Selection {
        val p = Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }
        return Selection.fromPath(p, w, h, antiAlias = false)
    }

    private fun bytes(s: Selection?) = s?.let { BitmapUtils.alpha8ToBytes(it.mask) }

    private fun EditorController.slowPacks(ms: Long) { beforeSavedSelectionPack = { delay(ms) } }

    @Test
    fun undoAndRedoWhileASaveOrUpdateIsPending() {
        val c = controller()
        c.setSelection(rect(Rect(2, 2, 20, 20)), recordUndo = false)
        val steps = c.undoManager.undoCount
        c.slowPacks(250)
        assertTrue(c.saveSelection())
        // At once: the pending entry with its name; no step, nothing in the list yet.
        val p = c.pendingSavedSelections.single()
        assertEquals("Selection 1", p.name)
        assertFalse(p.update)
        assertTrue(c.doc.savedSelections.isEmpty())
        assertEquals(steps, c.undoManager.undoCount)

        // Undo while pending: the save lands, then undo takes it back.
        c.undo()
        assertTrue(c.pendingSavedSelections.isEmpty())
        assertTrue(c.doc.savedSelections.isEmpty())
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(SavedSelectionLabels.SAVE, c.undoManager.redoLabel)
        // Its work finishing later adds nothing.
        Smoke.pump(400)
        assertEquals(steps, c.undoManager.undoCount)
        assertTrue(c.doc.savedSelections.isEmpty())
        assertTrue(c.canRedo)
        c.redo()
        val saved = c.doc.savedSelections.single()
        assertEquals(p.id, saved.id)
        assertEquals(steps + 1, c.undoManager.undoCount)

        // An update while pending: its entry stays until it lands; undo lands it and takes it back.
        c.setSelection(rect(Rect(30, 10, 60, 40)), recordUndo = false)
        assertTrue(c.updateSavedSelection(saved.id))
        val u = c.pendingSavedSelections.single()
        assertTrue(u.update)
        assertEquals(saved.id, u.id)
        assertSame(saved, c.doc.savedSelections.single())
        c.undo()
        assertTrue(c.pendingSavedSelections.isEmpty())
        assertSame(saved, c.doc.savedSelections.single())
        assertEquals(HistoryLabels.UPDATE_SAVED_SELECTION, c.undoManager.redoLabel)
        Smoke.pump(400)
        assertEquals(steps + 1, c.undoManager.undoCount)
        c.redo()
        assertArrayEquals(bytes(rect(Rect(30, 10, 60, 40))), bytes(c.doc.savedSelections.single().toSelection(w, h)))
        Smoke.assertQuiet(c, "pending save undo")
    }

    @Test
    fun savesLandInOrderAndAMarkLandsThemFirst() {
        val c = controller()
        c.setSelection(rect(Rect(2, 2, 20, 20)), recordUndo = false)
        val steps = c.undoManager.undoCount
        // The first save compresses longer than the second: the second waits for it.
        c.slowPacks(500)
        assertTrue(c.saveSelection())
        c.slowPacks(10)
        assertTrue(c.saveSelection())
        val (p1, p2) = c.pendingSavedSelections
        assertEquals(listOf("Selection 1", "Selection 2"), listOf(p1.name, p2.name))
        assertTrue("second done", Smoke.pumpUntil(10_000) { p2.ready })
        assertTrue("the second waits for the first", c.doc.savedSelections.isEmpty())
        assertEquals(2, c.pendingSavedSelections.size)
        assertTrue("both landed", Smoke.pumpUntil(10_000) { c.pendingSavedSelections.isEmpty() })
        assertEquals(listOf(p1.id, p2.id), c.doc.savedSelections.map { it.id })
        assertEquals(steps + 2, c.undoManager.undoCount)
        c.undo()
        assertEquals(listOf(p1.id), c.doc.savedSelections.map { it.id })
        c.redo()

        // A history mark (a two-finger tap's) lands a pending save first: rolling back to the mark
        // keeps it and takes back only what came after.
        c.slowPacks(250)
        assertTrue(c.saveSelection())
        val p3 = c.pendingSavedSelections.single()
        val m = c.uiMark()
        assertTrue(c.pendingSavedSelections.isEmpty())
        assertEquals(listOf(p1.id, p2.id, p3.id), c.doc.savedSelections.map { it.id })
        val marked = c.undoManager.undoCount
        assertEquals(steps + 3, marked)
        c.deselect()
        assertTrue(c.restoreUiMark(m))
        c.releaseUiMark(m)
        assertEquals(marked, c.undoManager.undoCount)
        assertEquals(listOf(p1.id, p2.id, p3.id), c.doc.savedSelections.map { it.id })
        assertTrue("the selection is back", c.selection != null)
        Smoke.pump(400)
        assertEquals(marked, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "pending saves in order")
    }

    @Test
    fun aCanvasFlipLandsAPendingSaveAndMapsIt() {
        val c = controller()
        val r = Rect(2, 2, 20, 20)
        c.setSelection(rect(r), recordUndo = false)
        val steps = c.undoManager.undoCount
        c.slowPacks(250)
        assertTrue(c.saveSelection())
        val p = c.pendingSavedSelections.single()
        assertTrue(CanvasOps.applyFlip(c, horizontal = true))
        assertTrue("flipped", Smoke.pumpUntil { c.busyMessage == null && c.undoManager.undoCount == steps + 2 })
        // The save is its own step before the flip, and the flip mapped it.
        val flipped = rect(Rect(w - r.right, r.top, w - r.left, r.bottom))
        assertEquals(p.id, c.doc.savedSelections.single().id)
        assertArrayEquals(bytes(flipped), bytes(c.doc.savedSelections.single().toSelection(w, h)))
        c.undo()
        assertArrayEquals(bytes(rect(r)), bytes(c.doc.savedSelections.single().toSelection(w, h)))
        assertEquals(SavedSelectionLabels.SAVE, c.undoManager.undoLabel)
        c.undo()
        assertTrue(c.doc.savedSelections.isEmpty())
        Smoke.pump(400)
        assertEquals(steps, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "pending save and flip")
    }
}
