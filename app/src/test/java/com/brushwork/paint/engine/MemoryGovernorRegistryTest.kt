package com.brushwork.paint.engine

import android.content.ComponentCallbacks2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.9 F1b (V28, design 2.4.3): the [MemoryGovernor] registry: registering and closing entries,
 * their order, the trim levels' pressures, what the policy is handed, and the default policy,
 * which does what v1.8 did (nothing; every allocation admitted). Since A7b step 1
 * `GovernorPolicies.current()` is `GovernorPolicyV19`.
 */
@Suppress("DEPRECATION") // the trim levels are what Android still delivers
class MemoryGovernorRegistryTest {
    private val open = ArrayList<MemoryGovernor.Registration>()
    private lateinit var before: List<MemoryGovernor.Entry>

    @Before
    fun setUp() {
        MemoryGovernor.policy = MemoryGovernor.DefaultPolicy
        before = MemoryGovernor.entries()
    }

    @After
    fun tearDown() {
        open.forEach { it.close() }
        MemoryGovernor.policy = MemoryGovernor.DefaultPolicy
    }

    private fun register(name: String, priority: Int, bytes: Long = 0L, trim: Trimmable = Trimmable { 0L }) =
        MemoryGovernor.register(name, priority, { bytes }, trim).also { open += it }

    /** The entries this test registered (another test of the JVM may have left none, but never assume). */
    private fun mine(): List<MemoryGovernor.Entry> = MemoryGovernor.entries().filter { e -> before.none { it === e } }

    @Test
    fun entriesComeLowestPriorityFirstInRegistrationOrder() {
        register("undo", 50)
        register("tiles", 0)
        register("proxy", 10)
        register("tiles 2", 0)
        register("below", 20)
        assertEquals(listOf("tiles", "tiles 2", "proxy", "below", "undo"), mine().map { it.name })
        assertEquals(listOf(0, 0, 10, 20, 50), mine().map { it.priority })
    }

    @Test
    fun closingRemovesTheEntryOnce() {
        val a = register("a", 5, bytes = 100L)
        val b = register("b", 5, bytes = 200L)
        assertEquals(listOf("a", "b"), mine().map { it.name })
        assertEquals(listOf(100L, 200L), mine().map { it.bytes() })
        a.close()
        assertEquals(listOf("b"), mine().map { it.name })
        a.close()
        assertEquals("a second close does nothing", listOf("b"), mine().map { it.name })
        b.close()
        assertTrue(mine().isEmpty())
        assertEquals(before.size, MemoryGovernor.entries().size)
    }

    @Test
    fun trimLevelsMapToPressures() {
        val p = MemoryGovernor::pressureOf
        assertEquals(MemoryPressure.CRITICAL, p(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MemoryPressure.CRITICAL, p(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertEquals(MemoryPressure.LOW, p(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryPressure.MODERATE, p(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryPressure.MODERATE, p(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(MemoryPressure.MODERATE, p(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(MemoryPressure.NORMAL, p(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(MemoryPressure.NORMAL, p(0))
        assertEquals(MemoryPressure.NORMAL, p(-1))
    }

    @Test
    fun thePolicyGetsThePressureAndTheOrderedEntries() {
        val seen = ArrayList<Pair<MemoryPressure, List<String>>>()
        val admitted = ArrayList<Pair<Long, String>>()
        MemoryGovernor.policy = object : GovernorPolicy {
            override fun onPressure(pressure: MemoryPressure, entries: List<MemoryGovernor.Entry>) {
                seen += pressure to entries.filter { e -> before.none { it === e } }.map { it.name }
                for (e in entries) e.trimmable.trim(pressure)
            }
            override fun admit(bytes: Long, purpose: String): Boolean { admitted += bytes to purpose; return bytes < 1000L }
        }
        val trimmed = ArrayList<String>()
        register("history", 50, trim = { trimmed += "history:$it"; 10L })
        register("display", 0, trim = { trimmed += "display:$it"; 20L })
        MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(listOf(MemoryPressure.LOW to listOf("display", "history")), seen)
        assertEquals(listOf("display:LOW", "history:LOW"), trimmed)
        assertTrue(MemoryGovernor.admit(999L, "layer"))
        assertEquals(false, MemoryGovernor.admit(1000L, "proxy"))
        assertEquals(listOf(999L to "layer", 1000L to "proxy"), admitted)
    }

    @Test
    fun theDefaultPolicyIsV18AndTheAppRunsV19() {
        // v1.9 (A7b step 1, design 3.2 A7b, R9): the app runs GovernorPolicyV19 (GovernorPolicyTest
        // pins its levels); MemoryGovernor's own default, until BrushworkApp.onCreate sets it, is v1.8's.
        // v1.10 (A7 step 2): the app runs GovernorPolicyV110, v1.9's levels with the undo trim on R.
        assertSame(GovernorPolicyV110, GovernorPolicies.current())
        assertSame(MemoryGovernor.DefaultPolicy, MemoryGovernor.policy)
        var trims = 0
        register("cache", 0, bytes = 1L shl 30, trim = { trims++; 1L })
        for (level in intArrayOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )) MemoryGovernor.onTrimMemory(level)
        assertEquals("v1.8 trims nothing", 0, trims)
        assertTrue("v1.8 admits every allocation", MemoryGovernor.admit(Long.MAX_VALUE, "anything"))
    }
}
