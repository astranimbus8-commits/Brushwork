package com.brushwork.paint.engine

import com.brushwork.paint.diagnostics.CrashLog
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * v1.9 (A7b step 1, R9, design 2.4.3): the memory policy the app runs with
 * ([GovernorPolicies.current], set as [MemoryGovernor.policy] by `BrushworkApp.onCreate`).
 *
 * What the system's trim levels free ([onPressure]; entries lowest priority first):
 * - [MemoryPressure.NORMAL] and [MemoryPressure.MODERATE]: nothing (as v1.8).
 * - [MemoryPressure.LOW]: the display caches ([PRIORITY_DISPLAY]) and the proxies
 *   ([PRIORITY_PROXIES]).
 * - [MemoryPressure.CRITICAL]: those, the below-caches ([PRIORITY_BELOW]), then the undo history
 *   ([PRIORITY_UNDO]): its entry (`EditorController`'s `layerCap` region) drops the oldest steps
 *   ([trimUndo]) until the Java heap is under [HEAP_TARGET] of its maximum, and says
 *   `StabilityLabels19.UNDO_TRIMMED` when it dropped any.
 *
 * [admit] (step 2) refuses a large allocation whose estimate exceeds the free Java heap less
 * [ADMIT_MARGIN]; its callers then say `StabilityLabels19.NO_MEMORY_FOR` and change nothing.
 *
 * Each entry frees what the pressure asks of it ([Trimmable.trim] gets the pressure); one that
 * throws is kept as a quiet report and the others are still trimmed.
 *
 * Main thread for the undo history (the `ComponentCallbacks2` that call [MemoryGovernor.onTrimMemory]
 * run there); the other entries are thread-safe.
 */
object GovernorPolicyV19 : GovernorPolicy {
    /** Display caches (thumbnails, display tiles): trimmed first. */
    const val PRIORITY_DISPLAY = 0

    /** Proxies (reduced copies kept for speed). */
    const val PRIORITY_PROXIES = 10

    /** Below-caches (the flattened layers under the one being drawn). */
    const val PRIORITY_BELOW = 20

    /** The undo history: trimmed last, under [MemoryPressure.CRITICAL] only. */
    const val PRIORITY_UNDO = 50

    /** The undo history's entry name ([MemoryGovernor.entries]). */
    const val UNDO_ENTRY = "undo history"

    /** CRITICAL drops undo steps until the Java heap in use is under this share of its maximum. */
    const val HEAP_TARGET = 0.70

    /** The newest undo steps CRITICAL always keeps. */
    const val KEEP_UNDO_STEPS = 1

    /** [admit]'s margin: an admitted allocation leaves this much of the Java heap free (64 MB). */
    const val ADMIT_MARGIN = 64L * 1024 * 1024

    /** The Java heap as the policy reads it ([heap]; a test seam). */
    interface Heap {
        /** Bytes in use: live objects and garbage not yet collected. */
        fun used(): Long

        /** The most the heap may grow to (`Runtime.maxMemory`). */
        fun max(): Long

        /** Asks for a collection, so that [used] no longer counts what was let go. */
        fun collect()
    }

    /** The process' own heap. */
    object RuntimeHeap : Heap {
        override fun used(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        override fun max(): Long = Runtime.getRuntime().maxMemory()

        override fun collect() = Runtime.getRuntime().gc()
    }

    /** The heap [trimUndo] and [admit] read; tests put a fake one here and restore it. */
    @Volatile
    internal var heap: Heap = RuntimeHeap

    override fun onPressure(pressure: MemoryPressure, entries: List<MemoryGovernor.Entry>) {
        val below = when (pressure) {
            MemoryPressure.NORMAL, MemoryPressure.MODERATE -> return
            MemoryPressure.LOW -> PRIORITY_BELOW
            MemoryPressure.CRITICAL -> Int.MAX_VALUE
        }
        for (e in entries.sortedBy { it.priority }) {
            if (e.priority >= below) break
            try {
                e.trimmable.trim(pressure)
            } catch (t: Exception) {
                runCatching { CrashLog.record(t, "trim ${e.name}", fatal = false) }
            } catch (t: OutOfMemoryError) {
                runCatching { CrashLog.record(t, "trim ${e.name}", fatal = false) }
            }
        }
    }

    /**
     * Admits an allocation of [bytes] (a flatten, filter apply, export or merge, for [purpose]) when
     * it fits in the free Java heap ([Heap.max] minus [Heap.used]) less [ADMIT_MARGIN]. What is in
     * use may still be garbage: before refusing, one collection ([Heap.collect]) and a new reading.
     * Nothing asked (0 or less) is always admitted. Any thread.
     */
    override fun admit(bytes: Long, purpose: String): Boolean {
        if (bytes <= 0L) return true
        val h = heap
        if (fits(h, bytes)) return true
        h.collect()
        return fits(h, bytes)
    }

    private fun fits(h: Heap, bytes: Long): Boolean = bytes <= h.max() - h.used() - ADMIT_MARGIN

    /** What [trimUndo] did: the steps dropped and the heap bytes that went (0 when unknown). */
    class UndoTrim(val steps: Int, val bytes: Long)

    /**
     * Drops the oldest steps of [history] while the Java heap in use is at or above [HEAP_TARGET]
     * of its maximum, keeping the newest [KEEP_UNDO_STEPS] (redo steps are kept). Disposing a step
     * frees its memory only when the garbage is collected, so the heap cannot be read again after
     * each step: the steps go in rounds, each half of those above the kept ones, then a collection
     * ([Heap.collect]) and a new reading. At most about eight rounds for the 150 steps of a full
     * history. Nothing while `UndoManager.holdTrim` holds (an edit in progress). Main thread.
     */
    fun trimUndo(history: UndoManager): UndoTrim {
        val h = heap
        val target = (h.max() * HEAP_TARGET).toLong()
        val start = h.used()
        var used = start
        var dropped = 0
        while (used >= target) {
            val extra = history.undoCount - KEEP_UNDO_STEPS
            if (extra <= 0) break
            val round = max(1, (extra + 1) / 2)
            var asked = 0
            val n = history.trimOldest(KEEP_UNDO_STEPS) { asked++ >= round }
            if (n == 0) break
            dropped += n
            h.collect()
            used = h.used()
        }
        return UndoTrim(dropped, if (dropped == 0) 0L else (start - used).coerceAtLeast(0L))
    }

    /** The weak registrations made so far ([registerWeakly]), swept of collected owners. */
    private val weak = ArrayList<Pair<WeakReference<Any>, MemoryGovernor.Registration>>()

    /**
     * Registers [owner]'s memory with [MemoryGovernor], holding [owner] weakly: once it is
     * collected, its entry reads 0 bytes, frees nothing and is closed (when it is next trimmed, or
     * when the next weak entry registers). [bytes] and [trim] get the owner as their argument and
     * must not hold it themselves. The owner closes the returned registration when it goes.
     */
    fun <T : Any> registerWeakly(
        name: String,
        priority: Int,
        owner: T,
        bytes: (T) -> Long,
        trim: (T, MemoryPressure) -> Long,
    ): MemoryGovernor.Registration {
        val ref = WeakReference(owner)
        val self = arrayOfNulls<MemoryGovernor.Registration>(1)
        val registration = MemoryGovernor.register(name, priority, { ref.get()?.let(bytes) ?: 0L }) { pressure ->
            val o = ref.get()
            if (o == null) {
                self[0]?.close()
                0L
            } else {
                trim(o, pressure)
            }
        }
        self[0] = registration
        synchronized(weak) {
            val it = weak.iterator()
            while (it.hasNext()) {
                val (r, reg) = it.next()
                if (r.get() == null) {
                    reg.close()
                    it.remove()
                }
            }
            @Suppress("UNCHECKED_CAST")
            weak += (ref as WeakReference<Any>) to registration
        }
        return registration
    }
}
