package com.brushwork.paint.engine

import android.os.Debug
import com.brushwork.paint.diagnostics.CrashLog
import java.lang.ref.WeakReference

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
 *   ([trimUndo]) until the memory in use (the Java heap and the native heap, where bitmap pixels
 *   live since Android 8) is under [HEAP_TARGET] of `maxMemory`, and says
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

    /**
     * CRITICAL drops undo steps until the memory in use ([Heap.used] plus [Heap.nativeUsed]) is
     * under this share of [Heap.max] (`maxMemory`, the budget the layer cap also divides).
     */
    const val HEAP_TARGET = 0.70

    /** The newest undo steps CRITICAL always keeps. */
    const val KEEP_UNDO_STEPS = 1

    /** [admit]'s margin: an admitted allocation leaves this much of the Java heap free (64 MB). */
    const val ADMIT_MARGIN = 64L * 1024 * 1024

    /** The process' memory as the policy reads it ([heap]; a test seam). */
    interface Heap {
        /** Java heap bytes in use: live objects and garbage not yet collected. */
        fun used(): Long

        /** The most the Java heap may grow to (`Runtime.maxMemory`). */
        fun max(): Long

        /** Asks for a collection, so that [used] no longer counts what was let go. */
        fun collect()

        /**
         * Native heap bytes allocated. Bitmap pixels live there since Android 8 (API 26, the
         * minimum): the layers, the undo steps' tiles, the caches. A recycled bitmap's pixels
         * leave it at once, without a collection.
         */
        fun nativeUsed(): Long = 0L
    }

    /**
     * The process' own memory. Under Robolectric `Debug.getNativeHeapAllocatedSize` reads the
     * Java heap again, so [nativeUsed] doubles it there: tests that reach [trimUndo] put a fake
     * [heap] in place ([admit] reads the Java heap only).
     */
    object RuntimeHeap : Heap {
        override fun used(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        override fun max(): Long = Runtime.getRuntime().maxMemory()

        override fun collect() = Runtime.getRuntime().gc()

        override fun nativeUsed(): Long = Debug.getNativeHeapAllocatedSize()
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
     *
     * The Java heap only: a filter's buffers are Java arrays. A bitmap (a flatten, a merge, an
     * export) takes native memory, which `maxMemory` does not bound, so these are rarely refused
     * on the phone; counting the native heap here would refuse filters the Java heap can hold.
     */
    override fun admit(bytes: Long, purpose: String): Boolean {
        if (bytes <= 0L) return true
        val h = heap
        if (fits(h, bytes)) return true
        h.collect()
        return fits(h, bytes)
    }

    private fun fits(h: Heap, bytes: Long): Boolean = bytes <= h.max() - h.used() - ADMIT_MARGIN

    /** What [trimUndo] did: the steps dropped and the bytes they held (their `byteSize`). */
    class UndoTrim(val steps: Int, val bytes: Long)

    /**
     * Drops the oldest steps of [history] while the memory in use ([Heap.used] plus
     * [Heap.nativeUsed], read once) less what the dropped steps held is at or above [HEAP_TARGET]
     * of [Heap.max], keeping the newest [KEEP_UNDO_STEPS] (redo steps are kept). What a step held
     * is its `UndoAction.byteSize`, counted as it goes: a pixel step's tiles are recycled when it
     * is disposed, so their native memory goes at once. No collection runs here: one on the main
     * thread alone could take longer than the 50 ms a CRITICAL trim may. Nothing while
     * `UndoManager.holdTrim` holds (an edit in progress). Main thread.
     */
    fun trimUndo(history: UndoManager): UndoTrim {
        val h = heap
        val target = (h.max() * HEAP_TARGET).toLong()
        val over = h.used() + h.nativeUsed() - target
        if (over < 0L) return UndoTrim(0, 0L)
        var freed = 0L
        val dropped = history.trimOldest(KEEP_UNDO_STEPS) {
            // Asked before each drop: stop once under the target; else the oldest step (dropped next) counts.
            if (freed > over) true else { freed += history.undoAt(0)?.byteSize?.coerceAtLeast(0L) ?: 0L; false }
        }
        return UndoTrim(dropped, if (dropped == 0) 0L else freed)
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
