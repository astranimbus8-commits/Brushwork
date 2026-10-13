package com.brushwork.paint.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 F1b (V28, design 2.4.3): [UndoManager.trimOldest], the one public way a memory policy
 * drops old steps: oldest first while `until` is false and more than `keepAtLeast` remain; each
 * dropped step disposed and counted in `dropped`; `onChanged` once; the redo steps kept; nothing
 * while an edit scope holds the trim (I2). The v1.8 limits (steps, bytes) are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class UndoTrimOldestTest {

    private class Step(override val label: String, override val byteSize: Long = 10L) : UndoAction {
        var disposed = 0
        var undone = 0
        var redone = 0
        override fun undo(c: EditorController) { undone++ }
        override fun redo(c: EditorController) { redone++ }
        override fun dispose() { disposed++ }
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

    private fun controller(): EditorController {
        val doc = Document("t", "t", 16, 16)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(16, 16))
        return EditorController(context, doc, scope, AppSettings(context))
    }

    private fun manager(n: Int, maxBytes: Long = Long.MAX_VALUE, maxSteps: Int = 150): Pair<UndoManager, List<Step>> {
        val um = UndoManager(maxBytes, maxSteps)
        val steps = (1..n).map { Step("s$it") }
        steps.forEach { um.push(it) }
        return um to steps
    }

    @Test
    fun dropsTheOldestUntilTheConditionHolds() {
        val (um, steps) = manager(5)
        var changed = 0
        um.onChanged = { changed++ }
        val dropped = um.dropped
        val n = um.trimOldest { um.undoCount <= 3 }
        assertEquals(2, n)
        assertEquals(3, um.undoCount)
        assertEquals(dropped + 2, um.dropped)
        assertEquals(listOf(1, 1, 0, 0, 0), steps.map { it.disposed })
        assertSame(steps[2], um.undoAt(0))
        assertEquals("s5", um.undoLabel)
        assertEquals("onChanged runs once", 1, changed)
    }

    @Test
    fun keepsAtLeastTheNewestSteps() {
        val (um, steps) = manager(4)
        assertEquals("one step is kept by default", 3, um.trimOldest { false })
        assertEquals(1, um.undoCount)
        assertSame(steps[3], um.undoAt(0))
        val (um2, _) = manager(4)
        assertEquals(2, um2.trimOldest(keepAtLeast = 2) { false })
        assertEquals(2, um2.undoCount)
        val (um3, steps3) = manager(3)
        assertEquals("keepAtLeast below 0 is 0", 3, um3.trimOldest(keepAtLeast = -4) { false })
        assertEquals(0, um3.undoCount)
        assertFalse(um3.canUndo)
        assertTrue(steps3.all { it.disposed == 1 })
    }

    @Test
    fun nothingDroppedIsNoChange() {
        val (um, steps) = manager(3)
        var changed = 0
        um.onChanged = { changed++ }
        val dropped = um.dropped
        assertEquals(0, um.trimOldest { true })
        assertEquals(0, um.trimOldest(keepAtLeast = 3) { false })
        assertEquals(3, um.undoCount)
        assertEquals(dropped, um.dropped)
        assertEquals(0, changed)
        assertTrue(steps.all { it.disposed == 0 })
        assertEquals(0, UndoManager(Long.MAX_VALUE).trimOldest { false })
    }

    @Test
    fun aHeldTrimDropsNothing() {
        val (um, steps) = manager(4)
        um.holdTrim()
        um.holdTrim()
        assertEquals(0, um.trimOldest { false })
        um.releaseTrim()
        assertEquals("still held once", 0, um.trimOldest { false })
        assertEquals(4, um.undoCount)
        um.releaseTrim()
        assertEquals(3, um.trimOldest { false })
        assertEquals(listOf(1, 1, 1, 0), steps.map { it.disposed })
    }

    @Test
    fun theRedoStepsAreKept() {
        val c = controller()
        val (um, steps) = manager(4)
        assertTrue(um.undo(c))
        assertEquals(1, steps[3].undone)
        assertEquals(2, um.trimOldest { false })
        assertEquals(1, um.undoCount)
        assertTrue(um.canRedo)
        assertEquals("s4", um.redoLabel)
        assertEquals(0, steps[3].disposed)
        assertTrue(um.redo(c))
        assertEquals(1, steps[3].redone)
        assertEquals(listOf("s3", "s4"), (0 until um.undoCount).map { um.undoAt(it)!!.label })
    }

    @Test
    fun inAnEditScopeTheControllersHistoryIsHeld() {
        val c = controller()
        val um = c.undoManager
        val steps = (1..3).map { Step("c$it") }
        steps.forEach { c.pushUndo(it) }
        val count = um.undoCount
        c.editScope { assertEquals("held inside an edit scope (I2)", 0, um.trimOldest { false }) }
        assertEquals(count, um.undoCount)
        assertEquals(count - 1, um.trimOldest { false })
        assertEquals("c3", um.undoLabel)
    }

    @Test
    fun theV18LimitsStillTrimOnPush() {
        val (um, steps) = manager(5, maxSteps = 3)
        assertEquals(3, um.undoCount)
        assertEquals(listOf(1, 1, 0, 0, 0), steps.map { it.disposed })
        val (byBytes, _) = manager(5, maxBytes = 25L)
        assertEquals("10 bytes a step, 25 at most", 2, byBytes.undoCount)
    }
}
