package com.brushwork.paint.v19.a7b

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.StabilityLabels19
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 A7b step 1 (design 3.2 A7b, R9): an editor's undo history is the governor's last entry.
 * The system's CRITICAL trim (as `BrushworkApp`'s ComponentCallbacks2 delivers it) drops its
 * oldest steps until the memory in use (Java and native heap) is under 70 % and says "Memory is
 * low: older undo steps were cleared"; a LOW trim, or memory already under 70 %, leaves the
 * history alone and says nothing. The entry goes with the editor's scope (A7b 7/7 review).
 */
@Suppress("DEPRECATION") // the trim levels are what Android still delivers
@RunWith(RobolectricTestRunner::class)
class UndoTrimToastTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var savedHeap: GovernorPolicyV19.Heap

    @Before
    fun setUp() {
        savedHeap = GovernorPolicyV19.heap
    }

    @After
    fun tearDown() {
        GovernorPolicyV19.heap = savedHeap
    }

    @Test
    fun criticalPressureClearsTheOldestStepsAndSaysSo() {
        val before = MemoryGovernor.entries()
        val c = Smoke.controller(app, Smoke.document(64, 48, layers = 1))
        val entry = MemoryGovernor.entries().single { e -> e.name == GovernorPolicyV19.UNDO_ENTRY && before.none { it === e } }
        assertEquals("the editor's history is registered last in line", GovernorPolicyV19.PRIORITY_UNDO, entry.priority)
        // The steps' tiles are native (360 of 900 in use): the Java heap alone is under 70 %.
        val heap = FakeHeap(1000L, 540L, native = 360L)
        GovernorPolicyV19.heap = heap
        val steps = (1..12).map { HeapStep("Step $it", heap, 30L) }
        steps.forEach { c.undoManager.push(it) }
        c.message = null

        MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals("LOW keeps the history", 12, c.undoManager.undoCount)
        assertNull(c.message)

        MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(StabilityLabels19.UNDO_TRIMMED, c.message)
        assertTrue("under 70 %: ${heap.used + heap.native}", heap.used + heap.native < 700L)
        val left = c.undoManager.undoCount
        assertEquals("7 steps of 30 bring 900 under 700", 5, left)
        assertEquals("no collection on the main thread", 0, heap.collections)
        assertEquals("the newest step stays", "Step 12", c.undoManager.undoLabel)
        assertTrue(c.canUndo)
        assertTrue("oldest first", steps.take(12 - left).all { it.disposed } && steps.drop(12 - left).none { it.disposed })

        // Under 70 % now: another CRITICAL trim drops nothing and says nothing.
        c.message = null
        MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(left, c.undoManager.undoCount)
        assertNull(c.message)

        // The editor's own entry, asked directly: only CRITICAL frees anything.
        heap.used = 950L
        assertEquals(0L, c.trimUndoForMemory(MemoryPressure.LOW))
        assertEquals(left, c.undoManager.undoCount)
        assertNull(c.message)

        // The editor closes (EditorSession.close cancels its scope after dispose): the entry goes
        // once the scope's work has ended, while the editor is still reachable.
        c.dispose()
        c.scope.launch { delay(60_000L) }
        c.scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the scope ended", c.scope.coroutineContext[Job]!!.isCompleted)
        assertTrue("its entry went with it", MemoryGovernor.entries().none { it === entry })
        c.undoMemoryRegistration.close()
        assertTrue(MemoryGovernor.entries().none { it === entry })
    }
}
