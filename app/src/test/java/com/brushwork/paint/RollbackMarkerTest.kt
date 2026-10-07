package com.brushwork.paint

import android.graphics.Matrix
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 F2 (item 10, §3.10 d): `undoMarker` / `rollbackTo` drop the steps pushed after the mark
 * without touching the redo stack; `uiMark` / `restoreUiMark` also take back a tool's in-tool
 * steps (`Tool.historyMark` / `rollbackHistory`), after which one `undo()` undoes exactly one
 * document step; `releaseUiMark` empties the `AppSettings` journal, which then records nothing.
 */
@RunWith(RobolectricTestRunner::class)
class RollbackMarkerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun controller(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 48, 32)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(48, 32)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun names(c: EditorController) = c.doc.layers.map { it.name }

    /** A tool with its own step stack, as the Path tool's: [historyMark] is the step count. */
    private class FakeStepsTool(c: EditorController) : Tool(c) {
        override val id = ToolId.LASSO
        var steps = 0
        override val hasPendingWork: Boolean get() = steps > 0
        override val hasUserChanges: Boolean get() = steps > 0
        override val canUndoStep: Boolean get() = steps > 0
        override fun undoStep(): Boolean = if (steps > 0) { steps--; true } else false
        override fun discard() { steps = 0 }
        override fun historyMark(): Any = steps
        override fun rollbackHistory(mark: Any?) { steps = minOf(steps, mark as Int) }
    }

    private fun install(c: EditorController): FakeStepsTool {
        val tool = FakeStepsTool(c)
        @Suppress("UNCHECKED_CAST")
        (c.tools as MutableMap<ToolId, Tool>)[ToolId.LASSO] = tool
        c.selectTool(ToolId.LASSO)
        return tool
    }

    @Test
    fun stepsAfterTheMarkerAreDroppedAndTheRedoStackIsUntouched() {
        val c = controller()
        c.addLayer("A")
        c.addLayer("B")
        c.undo() // B waits on the redo stack
        assertTrue(c.undoManager.canRedo)
        val m = c.undoMarker()
        // Nothing after the mark: nothing to do, the redo stack stays.
        assertTrue(c.rollbackTo(m))
        assertEquals(1, c.undoManager.undoCount)
        assertTrue("the redo stack stays", c.undoManager.canRedo)
        assertEquals(listOf("Layer 1", "Layer 2", "A"), names(c))

        // Two steps after the mark (the new step clears redo, as always): both go, none to redo.
        c.addLayer("C")
        c.addLayer("D")
        val redoBefore = c.undoManager.canRedo
        assertTrue(c.rollbackTo(m))
        assertEquals(listOf("Layer 1", "Layer 2", "A"), names(c))
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("rolled-back steps never reach redo", redoBefore, c.undoManager.canRedo)
        // The step before the mark is still the newest.
        c.undo()
        assertEquals(listOf("Layer 1", "Layer 2"), names(c))
    }

    @Test
    fun aMarkerOnAnEmptyHistoryRollsBackEverything() {
        val c = controller()
        val m = c.undoMarker()
        c.addLayer("A")
        c.addLayer("B")
        assertTrue(c.rollbackTo(m))
        assertEquals(0, c.undoManager.undoCount)
        assertFalse(c.undoManager.canRedo)
        assertEquals(listOf("Layer 1", "Layer 2"), names(c))
    }

    @Test
    fun aMarkerTheHistoryNoLongerHoldsIsRefused() {
        val c = controller()
        c.addLayer("A")
        val m = c.undoMarker()
        c.addLayer("B")
        c.undoManager.clear()
        assertFalse(c.rollbackTo(m))
        assertEquals("nothing taken back", listOf("Layer 1", "Layer 2", "A", "B"), names(c))
        // Undone past the mark: its step left the undo stack.
        val c2 = controller()
        c2.addLayer("A")
        val m2 = c2.undoMarker()
        c2.undo()
        assertFalse(c2.rollbackTo(m2))
        assertTrue(c2.undoManager.canRedo)
    }

    @Test
    fun anInToolChangeAfterTheUiMarkIsRolledBackThenOneUndoUndoesOneDocumentStep() {
        val c = controller()
        c.addLayer("A")
        c.addLayer("B")
        val tool = install(c)
        val m = c.uiMark()
        // The first finger moves a slider that pushes an in-tool step.
        tool.steps++
        assertTrue(tool.hasPendingWork)
        assertTrue(c.restoreUiMark(m))
        assertEquals("the in-tool step is gone, not redoable in the tool", 0, tool.steps)
        val before = c.undoManager.undoCount
        c.undo()
        assertEquals("exactly one document step undone", before - 1, c.undoManager.undoCount)
        assertEquals(listOf("Layer 1", "Layer 2", "A"), names(c))
        assertEquals(0, tool.steps)
        c.releaseUiMark(m)
    }

    @Test
    fun theUiMarkTakesBackDocumentStepsAndKeepsEarlierInToolSteps() {
        val c = controller()
        c.addLayer("A")
        val tool = install(c)
        tool.steps = 2 // pending in-tool work from before the mark stays
        val m = c.uiMark()
        tool.steps++
        c.addLayer("B")
        assertTrue(c.restoreUiMark(m))
        assertEquals(2, tool.steps)
        assertEquals(listOf("Layer 1", "Layer 2", "A"), names(c))
        assertFalse("nothing left to restore", c.restoreUiMark(m))
        c.releaseUiMark(m)
    }

    @Test
    fun afterReleaseTheSettingsJournalIsEmptyAndRecordsNothing() {
        val c = controller()
        val s = c.settings
        val was = s.leftHanded
        val m = c.uiMark()
        val start = s.journalPosition()
        s.leftHanded = !was
        assertNotEquals("recorded while the mark is open", start, s.journalPosition())
        assertTrue(c.restoreUiMark(m))
        assertEquals("the setting is put back", was, s.leftHanded)
        s.autosaveSeconds = s.autosaveSeconds + 30
        c.releaseUiMark(m)
        val closed = s.journalPosition()
        assertFalse("the journal is empty", s.rollbackJournal(start))
        s.leftHanded = !was
        assertEquals("nothing recorded without a mark", closed, s.journalPosition())
        assertFalse(s.rollbackJournal(start))
        assertEquals(!was, s.leftHanded)
    }
}
