package com.brushwork.paint.ui.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.InToolHistoryOwner
import com.brushwork.paint.tools.ToolHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 F1b (D14, design 2.4.2): the in-tool history seam. With a fake owner in
 * [EditorController.inToolHistory], Undo and Redo walk the owner's own steps first (the
 * controller's history, edit count and filter session are left alone), the feedback reads
 * "Undo: <label>" / "Redo: <label>", the Redo button is enabled by the owner; at the owner's
 * bottom (or top) v1.8's undo (redo) runs. A null owner is v1.8.
 */
@RunWith(RobolectricTestRunner::class)
class InToolHistorySeamTest {

    /** A session with its own steps: each state is a label; the label of a step is its state's. */
    private class FakeOwner(initial: String) : InToolHistoryOwner {
        val history = ToolHistory(initial)
        var undoCalls = 0
        var redoCalls = 0
        override val canUndo: Boolean get() = history.canUndo
        override val canRedo: Boolean get() = history.canRedo
        override val undoLabel: String? get() = if (history.canUndo) history.current else null
        override val redoLabel: String? get() = if (history.canRedo) "again" else null
        override fun undoStep(): Boolean { undoCalls++; return history.undo() != null }
        override fun redoStep(): Boolean { redoCalls++; return history.redo() != null }
    }

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() = scope.cancel()

    /** A painted "Layer 1" renamed "Renamed" as the controller's one undo step "Rename layer". */
    private fun controller(): Pair<EditorController, Layer> {
        val doc = Document("t", "t", 40, 30)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(40, 30))
        layer.bitmap.eraseColor(0xFF2040C0.toInt())
        doc.layers += layer
        val c = EditorController(context, doc, scope, AppSettings(context))
        c.setLayerProps(layer, layer.props().copy(name = "Renamed"), "Rename layer")
        assertEquals(1, c.undoManager.undoCount)
        return c to layer
    }

    @Test
    fun withoutAnOwnerUndoAndRedoAreV18() {
        val (c, layer) = controller()
        assertNull(c.inToolHistory)
        assertEquals("Undo: Rename layer", HistoryLabels.undo(c))
        assertEquals("Nothing to redo", HistoryLabels.redo(c))
        assertFalse(canRedoNow(c))
        assertEquals("Undo: Rename layer", HistoryLabels.performUndo(c))
        assertEquals("Layer 1", layer.name)
        assertTrue(canRedoNow(c))
        assertEquals("Redo: Rename layer", HistoryLabels.redo(c))
        assertEquals("Redo: Rename layer", HistoryLabels.performRedo(c))
        assertEquals("Renamed", layer.name)
    }

    @Test
    fun theOwnersStepsComeFirst() {
        val (c, layer) = controller()
        val owner = FakeOwner("start").also { it.history.record("Move node"); it.history.record("Add node") }
        c.inToolHistory = owner
        val edits = c.editCount
        assertEquals("Undo: Add node", HistoryLabels.undo(c))
        c.undo()
        assertEquals(1, owner.undoCalls)
        assertEquals("Move node", owner.history.current)
        assertEquals("the controller's history is left alone", 1, c.undoManager.undoCount)
        assertEquals(edits, c.editCount)
        assertEquals("Renamed", layer.name)
        assertEquals("Undo: Move node", HistoryLabels.performUndo(c))
        assertEquals("start", owner.history.current)
        assertEquals(1, c.undoManager.undoCount)
        // At the owner's bottom v1.8's undo runs (and its label shows).
        assertFalse(owner.canUndo)
        assertEquals("Undo: Rename layer", HistoryLabels.undo(c))
        c.undo()
        assertEquals("the owner was asked first", 3, owner.undoCalls)
        assertEquals(0, c.undoManager.undoCount)
        assertEquals("Layer 1", layer.name)
    }

    @Test
    fun theOwnersRedoComesFirstAndEnablesTheButton() {
        val (c, layer) = controller()
        val owner = FakeOwner("start").also { it.history.record("Add node"); it.history.undo() }
        c.inToolHistory = owner
        assertFalse("the controller has nothing to redo", c.undoManager.canRedo)
        assertTrue("the owner's step enables Redo", canRedoNow(c))
        assertEquals("Redo: again", HistoryLabels.redo(c))
        assertEquals("Redo: again", HistoryLabels.performRedo(c))
        assertEquals("Add node", owner.history.current)
        assertEquals(1, owner.redoCalls)
        assertFalse(canRedoNow(c))
        assertEquals("Nothing to redo", HistoryLabels.redo(c))
        // At the owner's top v1.8's redo runs.
        c.inToolHistory = null
        c.undo()
        c.inToolHistory = owner
        assertEquals("Layer 1", layer.name)
        assertTrue(canRedoNow(c))
        assertEquals("Redo: Rename layer", HistoryLabels.redo(c))
        c.redo()
        assertEquals(2, owner.redoCalls)
        assertEquals("Renamed", layer.name)
    }

    @Test
    fun anOwnerStepLeavesTheFilterSessionOpen() {
        val (c, _) = controller()
        c.startFilter(FilterRegistry.byId("adjust.invert")!!)
        val session = c.filterSession
        assertNotNull("the filter session opened (${c.message})", session)
        try {
            val owner = FakeOwner("start").also { it.history.record("Edit") }
            c.inToolHistory = owner
            assertEquals("Undo: Edit", HistoryLabels.undo(c))
            c.undo()
            assertSame("the session is still open", session, c.filterSession)
            assertEquals("start", owner.history.current)
            assertEquals(1, c.undoManager.undoCount)
            // At the owner's bottom, v1.8: undo cancels the filter.
            assertEquals("Filter cancelled", HistoryLabels.undo(c))
            c.undo()
            assertNull(c.filterSession)
            assertEquals(1, c.undoManager.undoCount)
        } finally {
            c.filterSession?.cancel()
        }
    }

    @Test
    fun anOwnerWithoutALabelFallsBackToV18Labels() {
        val (c, _) = controller()
        val owner = object : InToolHistoryOwner {
            override val canUndo = false
            override val canRedo = false
            override val undoLabel: String? = "never shown"
            override val redoLabel: String? = "never shown"
            override fun undoStep() = false
            override fun redoStep() = false
        }
        c.inToolHistory = owner
        assertEquals("Undo: Rename layer", HistoryLabels.undo(c))
        assertEquals("Nothing to redo", HistoryLabels.redo(c))
        assertFalse(canRedoNow(c))
        c.undo()
        assertEquals("an owner that takes no step lets v1.8's undo run", 0, c.undoManager.undoCount)
    }
}
