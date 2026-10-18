package com.brushwork.paint.v19.a7b

import com.brushwork.paint.engine.GovernorPolicies
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
 * whose oldest steps go until the Java heap is under 70 % of its maximum (the newest step stays);
 * NORMAL and MODERATE trim nothing. The entries are made here and handed to the policy directly,
 * so other tests' registrations in this JVM never take part.
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
        assertSame(GovernorPolicyV19, GovernorPolicies.current())
        assertSame("BrushworkApp.onCreate set it", GovernorPolicyV19, MemoryGovernor.policy)
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

    /** 20 steps of 20 bytes in a 1000-byte heap with 900 in use: the oldest go until under 700. */
    @Test
    fun theOldestUndoStepsGoUntilTheHeapIsUnder70Percent() {
        val heap = FakeHeap(1000L, 900L)
        GovernorPolicyV19.heap = heap
        val history = UndoManager(Long.MAX_VALUE)
        val steps = (1..20).map { HeapStep("Step $it", heap, 20L) }
        steps.forEach { history.push(it) }
        val trimmed = GovernorPolicyV19.trimUndo(history)
        assertTrue("under 70 %: ${heap.used}", heap.used < 700L)
        assertEquals(20 - trimmed.steps, history.undoCount)
        assertEquals("what the collections showed", 900L - heap.used, trimmed.bytes)
        // Oldest first: the dropped ones are a prefix, the newest are kept.
        assertTrue(steps.take(trimmed.steps).all { it.disposed })
        assertTrue(steps.drop(trimmed.steps).none { it.disposed })
        assertEquals("Step 20", history.undoLabel)
        // In rounds of half the steps above the kept one, each followed by a collection.
        assertEquals(listOf(10, 5).sum(), trimmed.steps)
        assertEquals(2, heap.collections)
    }

    @Test
    fun theNewestStepStaysWhateverTheHeap() {
        val heap = FakeHeap(1000L, 990L)
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
        val heap = FakeHeap(1000L, 650L)
        GovernorPolicyV19.heap = heap
        val history = UndoManager(Long.MAX_VALUE)
        (1..5).forEach { history.push(HeapStep("Step $it", heap, 100L)) }
        assertEquals(0, GovernorPolicyV19.trimUndo(history).steps)
        assertEquals(0, heap.collections)
        heap.used = 950L
        history.holdTrim()
        assertEquals("an edit in progress (I2)", 0, GovernorPolicyV19.trimUndo(history).steps)
        assertEquals(5, history.undoCount)
        history.releaseTrim()
        assertTrue(GovernorPolicyV19.trimUndo(history).steps > 0)
    }

    @Test
    fun redoStepsAreKept() {
        val heap = FakeHeap(1000L, 900L)
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
