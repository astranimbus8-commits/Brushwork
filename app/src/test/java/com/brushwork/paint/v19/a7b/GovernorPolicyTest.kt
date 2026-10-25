package com.brushwork.paint.v19.a7b

import com.brushwork.paint.engine.GovernorPolicies
import com.brushwork.paint.engine.GovernorPolicyV110
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.Trimmable
import com.brushwork.paint.engine.UndoManager
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
 * v1.9 A7b step 1 (design 3.2 A7b, R9): [GovernorPolicyV19], the policy the app runs with. LOW
 * trims the display caches and the proxies; CRITICAL also the below-caches, then the undo history,
 * whose oldest steps go until the memory in use (Java heap and native heap, where the steps' bitmap
 * tiles live) is under 70 % of `maxMemory` (the newest step stays), counted from the steps' sizes
 * without a collection on the main thread; NORMAL and MODERATE trim nothing. The entries are made
 * here and handed to the policy directly, so other tests' registrations in this JVM never take part.
 */
@RunWith(RobolectricTestRunner::class)
class GovernorPolicyTest {
    private val seen = ArrayList<String>()
    private lateinit var savedHeap: GovernorPolicyV19.Heap

    @Before
    fun setUp() {
        savedHeap = GovernorPolicyV19.heap
    }

    @After
    fun tearDown() {
        GovernorPolicyV19.heap = savedHeap
    }

    private fun entry(name: String, priority: Int, trim: Trimmable = Trimmable { p -> seen += "$name:$p"; 1L }) =
        MemoryGovernor.Entry(name, priority, { 0L }, trim)

    private fun all() = listOf(
        entry("undo", GovernorPolicyV19.PRIORITY_UNDO),
        entry("display", GovernorPolicyV19.PRIORITY_DISPLAY),
        entry("below", GovernorPolicyV19.PRIORITY_BELOW),
        entry("proxy", GovernorPolicyV19.PRIORITY_PROXIES),
        entry("thumbnails", GovernorPolicyV19.PRIORITY_DISPLAY),
    )

    @Test
    fun theAppRunsWithIt() {
        // v1.10 (A7 step 2): the app runs GovernorPolicyV110, which keeps these levels and, while
        // memory is unknown (as here), this undo trim.
        assertSame(GovernorPolicyV110, GovernorPolicies.current())
        assertSame("BrushworkApp.onCreate set it", GovernorPolicyV110, MemoryGovernor.policy)
    }

    @Test
    fun lowTrimsTheDisplayCachesAndTheProxiesOnly() {
        GovernorPolicyV19.onPressure(MemoryPressure.LOW, all())
        assertEquals(listOf("display:LOW", "thumbnails:LOW", "proxy:LOW"), seen)
    }

    @Test
    fun criticalTrimsEverythingLowestPriorityFirstTheUndoHistoryLast() {
        GovernorPolicyV19.onPressure(MemoryPressure.CRITICAL, all())
        assertEquals(listOf("display:CRITICAL", "thumbnails:CRITICAL", "proxy:CRITICAL", "below:CRITICAL", "undo:CRITICAL"), seen)
    }

    @Test
    fun normalAndModerateTrimNothing() {
        GovernorPolicyV19.onPressure(MemoryPressure.NORMAL, all())
        GovernorPolicyV19.onPressure(MemoryPressure.MODERATE, all())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun anEntryThatThrowsDoesNotStopTheOthers() {
        val list = listOf(
            entry("display", 0),
            entry("broken", 10, Trimmable { throw IllegalStateException("trim test") }),
            entry("below", 20),
        )
        GovernorPolicyV19.onPressure(MemoryPressure.CRITICAL, list)
        assertEquals(listOf("display:CRITICAL", "below:CRITICAL"), seen)
    }

    /**
     * 20 steps of 20 bytes of native memory; 300 bytes of Java heap and 600 of native heap in use
     * out of 1000: the Java heap alone is under 70 %, the memory in use is not. The oldest steps go
     * until under 700 (11 of them: 900 - 220), with no collection.
     */
    @Test
    fun theOldestUndoStepsGoUntilTheMemoryInUseIsUnder70Percent() {
        val heap = FakeHeap(1000L, 300L, native = 600L)
        GovernorPolicyV19.heap = heap
        val history = UndoManager(Long.MAX_VALUE)
        val steps = (1..20).map { HeapStep("Step $it", heap, 20L) }
        steps.forEach { history.push(it) }
        val trimmed = GovernorPolicyV19.trimUndo(history)
        assertEquals(11, trimmed.steps)
        assertEquals("what the dropped steps held", 220L, trimmed.bytes)
        assertEquals(680L, heap.used + heap.native)
        assertEquals(9, history.undoCount)
        // Oldest first: the dropped ones are a prefix, the newest are kept.
        assertTrue(steps.take(11).all { it.disposed })
        assertTrue(steps.drop(11).none { it.disposed })
        assertEquals("Step 20", history.undoLabel)
        assertEquals("no collection on the main thread (CRITICAL trim: 50 ms at most)", 0, heap.collections)
    }

    @Test
    fun theNewestStepStaysWhateverTheHeap() {
        val heap = FakeHeap(1000L, 990L, native = 9L)
        GovernorPolicyV19.heap = heap
        val history = UndoManager(Long.MAX_VALUE)
        (1..9).forEach { history.push(HeapStep("Step $it", heap, 1L)) }
        val trimmed = GovernorPolicyV19.trimUndo(history)
        assertEquals(8, trimmed.steps)
        assertEquals(GovernorPolicyV19.KEEP_UNDO_STEPS, history.undoCount)
        assertEquals("Step 9", history.undoLabel)
        assertTrue(history.canUndo)
    }

    @Test
    fun underTheTargetOrWhileAnEditHoldsTheHistoryNothingGoes() {
        val heap = FakeHeap(1000L, 150L, native = 500L)
        GovernorPolicyV19.heap = heap
        val history = UndoManager(Long.MAX_VALUE)
        (1..5).forEach { history.push(HeapStep("Step $it", heap, 100L)) }
        assertEquals(0, GovernorPolicyV19.trimUndo(history).steps)
        assertEquals(0, heap.collections)
        heap.used = 450L
        history.holdTrim()
        assertEquals("an edit in progress (I2)", 0, GovernorPolicyV19.trimUndo(history).steps)
        assertEquals(5, history.undoCount)
        history.releaseTrim()
        assertTrue(GovernorPolicyV19.trimUndo(history).steps > 0)
    }

    @Test
    fun redoStepsAreKept() {
        val heap = FakeHeap(1000L, 300L, native = 600L)
        GovernorPolicyV19.heap = heap
        val history = UndoManager(Long.MAX_VALUE)
        val steps = (1..6).map { HeapStep("Step $it", heap, 100L) }
        steps.forEach { history.push(it) }
        history.undo(com.brushwork.paint.smoke.Smoke.controller(androidx.test.core.app.ApplicationProvider.getApplicationContext(), com.brushwork.paint.smoke.Smoke.document(32, 32, layers = 1)))
        assertTrue(history.canRedo)
        GovernorPolicyV19.trimUndo(history)
        assertTrue(history.canRedo)
        assertEquals("Step 6", history.redoLabel)
        assertFalse(steps.last().disposed)
    }
}
