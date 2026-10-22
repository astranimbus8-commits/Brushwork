package com.brushwork.paint.engine

import android.content.ComponentCallbacks2
import java.util.concurrent.CopyOnWriteArrayList

/** v1.9 (design 2.4.3): how hard the system asks the app to give memory back. */
enum class MemoryPressure { NORMAL, MODERATE, LOW, CRITICAL }

/** Something that can free memory: [trim] frees what [pressure] asks for and returns the bytes freed. */
fun interface Trimmable {
    fun trim(pressure: MemoryPressure): Long
}

/** Decides what [MemoryGovernor] does under pressure and which large allocations it admits. */
interface GovernorPolicy {
    /** The system asked for memory: trim [entries] (lowest priority first) as [pressure] requires. */
    fun onPressure(pressure: MemoryPressure, entries: List<MemoryGovernor.Entry>)

    /** Whether an allocation of [bytes] for [purpose] (a short name, for logs) may go ahead. */
    fun admit(bytes: Long, purpose: String): Boolean

    /**
     * v1.10 (F1, design 2.4, finding 8): drops the oldest undo steps of [history] as memory
     * requires. The default is v1.9's ([GovernorPolicyV19.trimUndo]); A7's `GovernorPolicyV110`
     * overrides it.
     */
    fun trimUndo(history: UndoManager): GovernorPolicyV19.UndoTrim = GovernorPolicyV19.trimUndo(history)
}

/**
 * v1.9 (V28, design 2.4.3): one registry of the app's large memory users (display caches, proxies,
 * below-caches, the undo history), so a single policy decides what to free when the system asks
 * for memory ([onTrimMemory], from `BrushworkApp`'s ComponentCallbacks2) and whether a large
 * allocation may go ahead ([admit]).
 *
 * The [policy] is [DefaultPolicy] until `BrushworkApp.onCreate` sets `GovernorPolicies.current()`:
 * it does what v1.8 did, nothing. Owners [register] their caches and close the [Registration] when
 * the cache goes. Thread-safe.
 */
object MemoryGovernor {
    /**
     * A registered memory user: [name] for logs, [priority] (lower is trimmed first), [bytes] what
     * it holds now, [trimmable] what frees it.
     */
    class Entry(val name: String, val priority: Int, val bytes: () -> Long, val trimmable: Trimmable)

    /** A registration; [close] removes its entry (once; later calls do nothing). */
    class Registration internal constructor(private val entry: Entry) : AutoCloseable {
        override fun close() {
            registered.remove(entry)
        }
    }

    private val registered = CopyOnWriteArrayList<Entry>()

    /** Lower priority is trimmed first: 0 display caches, 10 proxies, 20 below-caches, 50 undo history. */
    fun register(name: String, priority: Int, bytes: () -> Long, trim: Trimmable): Registration {
        val entry = Entry(name, priority, bytes, trim)
        registered += entry
        return Registration(entry)
    }

    /**
     * From BrushworkApp's ComponentCallbacks2: TRIM_MEMORY_RUNNING_LOW -> LOW, RUNNING_CRITICAL or
     * COMPLETE -> CRITICAL; RUNNING_MODERATE, BACKGROUND and MODERATE -> MODERATE; any other level
     * (UI_HIDDEN) -> NORMAL. The [policy] then decides ([GovernorPolicy.onPressure]).
     */
    fun onTrimMemory(level: Int) {
        policy.onPressure(pressureOf(level), entries())
    }

    /** The pressure a ComponentCallbacks2 trim [level] means (see [onTrimMemory]). */
    @Suppress("DEPRECATION")
    internal fun pressureOf(level: Int): MemoryPressure = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressure.CRITICAL
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressure.LOW
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MemoryPressure.MODERATE
        else -> MemoryPressure.NORMAL
    }

    /** Whether an allocation of [bytes] for [purpose] may go ahead (the [policy] decides; v1.8: always). */
    fun admit(bytes: Long, purpose: String): Boolean = policy.admit(bytes, purpose)

    /** The registered entries, lowest priority first (registration order within a priority). */
    fun entries(): List<Entry> = registered.toList().sortedBy { it.priority }

    /** Set by `BrushworkApp.onCreate` to `GovernorPolicies.current()`. */
    @Volatile var policy: GovernorPolicy = DefaultPolicy

    /** v1.8 behaviour: onPressure does nothing; admit() is always true. */
    object DefaultPolicy : GovernorPolicy {
        override fun onPressure(pressure: MemoryPressure, entries: List<Entry>) {}

        override fun admit(bytes: Long, purpose: String): Boolean = true
    }
}
