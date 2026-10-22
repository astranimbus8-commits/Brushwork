package com.brushwork.paint.v110.lead

import com.brushwork.paint.engine.GovernorPolicy
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.v19.a7b.FakeHeap
import com.brushwork.paint.v19.a7b.HeapStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.4, finding 8; lead, frozen): [GovernorPolicy.trimUndo]'s default is v1.9's
 * [GovernorPolicyV19.trimUndo]. A policy that does not override it (a fake one,
 * [MemoryGovernor.DefaultPolicy]) drops the same oldest steps, reports the same steps and bytes,
 * and leaves the same history as v1.9's policy does from the same heap and the same steps.
 */
@RunWith(RobolectricTestRunner::class)
class TrimUndoDelegatesTest {
    private lateinit var savedHeap: GovernorPolicyV19.Heap

    @Before
    fun setUp() {
        savedHeap = GovernorPolicyV19.heap
    }

    @After
    fun tearDown() {
        GovernorPolicyV19.heap = savedHeap
    }

    /** A policy of its own that keeps the interface's default [GovernorPolicy.trimUndo]. */
    private val fake = object : GovernorPolicy {
        override fun onPressure(pressure: MemoryPressure, entries: List<MemoryGovernor.Entry>) {}

        override fun admit(bytes: Long, purpose: String): Boolean = true
    }

    /** What one trim did and left: the steps and bytes reported, then the history's count and newest label, and the disposed steps. */
    private fun run(policy: GovernorPolicy, used: Long, native: Long, steps: Int, bytes: Long): List<Any?> {
        val heap = FakeHeap(1000L, used, native = native)
        GovernorPolicyV19.heap = heap
        val history = UndoManager(Long.MAX_VALUE)
        val pushed = (1..steps).map { HeapStep("Step $it", heap, bytes) }
        pushed.forEach { history.push(it) }
        val trimmed = policy.trimUndo(history)
        return listOf(trimmed.steps, trimmed.bytes, history.undoCount, history.undoLabel, pushed.count { it.disposed }, heap.native)
    }

    private data class Case(val used: Long, val native: Long, val steps: Int, val bytes: Long)

    @Test
    fun theDefaultEqualsV19() {
        // Over the target (drops some), at the target's edge, under it (drops none), and where only the newest stays.
        val cases = listOf(
            Case(300L, 600L, 20, 20L),
            Case(300L, 400L, 20, 20L),
            Case(150L, 500L, 5, 100L),
            Case(990L, 9L, 9, 1L),
        )
        for (c in cases) {
            val (used, native, steps, bytes) = c
            val v19 = run(GovernorPolicyV19, used, native, steps, bytes)
            assertEquals("a policy of its own, case $c", v19, run(fake, used, native, steps, bytes))
            assertEquals("MemoryGovernor.DefaultPolicy, case $c", v19, run(MemoryGovernor.DefaultPolicy, used, native, steps, bytes))
        }
    }

    @Test
    fun theDefaultDropsTheOldestStepsAsV19Does() {
        // The v1.9 case of GovernorPolicyTest: 11 of 20 steps go, 220 bytes, "Step 20" stays newest.
        assertEquals(listOf(11, 220L, 9, "Step 20", 11, 380L), run(fake, 300L, 600L, 20, 20L))
    }
}
