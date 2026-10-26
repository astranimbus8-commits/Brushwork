package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.MemoryGovernor
import java.util.IdentityHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * v1.10 (A8 step 1, design 2.0, 2.7 and 2.8, J8, I29): the `tile-swapper`, one daemon thread at
 * background priority that does the slow part of moving tiles: it packs a tile (Deflate level 1,
 * nowrap), writes it to the swap file, reads or inflates it back, and cuts a parked layer's flat
 * bitmap into pieces. It only produces and posts (the `Tile.kt` threading rules): every result
 * goes back to the main looper in a posted task, where main commits it or lets it go. The worker
 * never frees, recycles or changes a tile's state, never touches [Tile.refs], [Tile.pins] or
 * [Tile.lastUse], and reads only tiles main leased to it (one reference more, taken here on main
 * and released in the posted task).
 *
 * Jobs run one at a time, by [Priority] and then in the order they came: an urgent read (the
 * active layer, an undo) goes before a read-ahead, which goes before a write, which goes before a
 * park cut. A job's main part always runs, also when its work failed (an `IOException` of the swap
 * file, out of memory), so a lease is always released. At most one job per tile is in flight
 * ([packOut], [writeOut], [readIn]): a tile's bitmap is recycled only by its commit, never while
 * another job of the same tile could be reading it.
 *
 * Main thread for everything here but the jobs' work; [isIdle] any thread.
 */
internal object TileWorker {
    /** The order of the queue: the first goes first. */
    enum class Priority { READ_URGENT, READ_AHEAD, WRITE, PARK_CUT }

    private class Job(val priority: Priority, val seq: Long, val run: () -> Unit) : Comparable<Job> {
        override fun compareTo(other: Job): Int =
            if (priority != other.priority) priority.compareTo(other.priority) else seq.compareTo(other.seq)
    }

    private val queue = PriorityBlockingQueue<Job>()
    private val seq = AtomicLong()

    /** Jobs submitted whose main part has not run yet. */
    private val pending = AtomicInteger()

    @Volatile private var thread: Thread? = null

    /** True when no job is queued, running or waiting for its main part. Any thread. */
    val isIdle: Boolean get() = pending.get() == 0

    /** Jobs not finished on main yet. Any thread. */
    val pendingJobs: Int get() = pending.get()

    /**
     * Runs [work] on the worker at [priority], then [onMain] on the main looper with its result
     * (or what it threw). [onMain] runs exactly once, also when [work] failed. Any thread.
     */
    fun <T> submit(priority: Priority, work: () -> T, onMain: (Result<T>) -> Unit) {
        pending.incrementAndGet()
        queue.put(
            Job(priority, seq.getAndIncrement()) {
                val r = try {
                    Result.success(work())
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                post {
                    try {
                        onMain(r)
                    } finally {
                        pending.decrementAndGet()
                    }
                }
            },
        )
        ensureThread()
    }

    /** Posts [task] to the main looper (resolved at each post: a test's looper is new in each test). */
    private fun post(task: () -> Unit) {
        val looper = Looper.getMainLooper()
        if (looper == null) {
            task()
            return
        }
        Handler(looper).post(task)
    }

    @Synchronized
    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread(::loop, "tile-swapper").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (e: RuntimeException) {
            // A priority the system refuses changes nothing else.
        }
        while (true) {
            val job = try {
                queue.take()
            } catch (e: InterruptedException) {
                continue
            }
            job.run()
        }
    }

    // ---------------------------------------------------------------- tile jobs (main)

    /** Main only: the tiles with a job in flight, and what waits for it to end. */
    private val inFlight = IdentityHashMap<Tile, ArrayList<() -> Unit>>()

    /** Main only: true while [t] has a job in flight. */
    fun busy(t: Tile): Boolean = inFlight.containsKey(t)

    /**
     * Main only: leases [t] for one job (refs + 1, marked in flight) and returns the references it
     * holds then, which the commit compares; null when it cannot be leased (freed, or a job of it
     * is in flight already).
     */
    private fun lease(t: Tile): Int? {
        if (t.refs <= 0 || inFlight.containsKey(t)) return null
        inFlight[t] = ArrayList(0)
        t.refs++
        return t.refs
    }

    /** Main only: [t]'s job ended (its lease is released by the caller); runs what waited for it. */
    private fun ended(t: Tile) {
        val waiting = inFlight.remove(t) ?: return
        for (w in waiting) w()
    }

    /**
     * Main only: packs [t] on the worker and, in the posted task, makes it PACKED when the commit
     * rule allows and the packed pool admits the bytes ([TileOps.commitPacked]). False when [t]
     * cannot go (not resident, solid, pinned, freed or busy); else [done] gets whether it went.
     */
    fun packOut(t: Tile, done: ((Boolean) -> Unit)? = null): Boolean {
        if (t.bitmap == null || t.slot != null || t.solid != null || t.pins > 0) return false
        val leased = lease(t) ?: return false
        submit(Priority.WRITE, { TileOps.pack(t.bitmapForRead()) }) { r ->
            val bytes = r.getOrNull()
            val ok = if (bytes != null) TileOps.commitPacked(t, bytes, leased) else false.also { TileOps.release(t) }
            ended(t)
            done?.invoke(ok)
        }
        return true
    }

    /**
     * Main only: writes [t] to the swap file on the worker and, in the posted task, makes the slot
     * its only copy when the commit rule allows ([TileOps.commitWritten]); otherwise the slot is
     * freed. False when [t] cannot go (solid, pinned, freed, busy, swapped already, or the swap
     * file has no room); else [done] gets whether it went.
     */
    fun writeOut(t: Tile, done: ((Boolean) -> Unit)? = null): Boolean {
        if (t.slot != null || t.solid != null || t.pins > 0 || (t.bitmap == null && t.packed == null)) return false
        if (!EngineConfig.swapStore.canWrite(t.packed?.size?.toLong() ?: (t.bytes / 2L))) return false
        val store = EngineConfig.swapStore
        val leased = lease(t) ?: return false
        submit(Priority.WRITE, {
            store.write(t)
            t.slot
        }) { r ->
            val slot = r.getOrNull()
            val ok = if (EngineConfig.swapStore === store) {
                TileOps.commitWritten(t, slot, leased)
            } else {
                // The session closed meanwhile: its store (and every slot) is gone.
                synchronized(t.lock) { if (t.slot === slot) t.slot = null }
                TileOps.release(t)
                false
            }
            ended(t)
            done?.invoke(ok)
        }
        return true
    }

    /**
     * Main only: reads [t] back on the worker (inflating its packed bytes or reading its slot) and
     * makes it RESIDENT in the posted task ([TileOps.adoptRead]). [done] gets true when [t] is
     * resident then (also when it already was), false when the read failed. A tile with a job in
     * flight is read once that job ended. False when [t] is freed.
     */
    fun readIn(t: Tile, priority: Priority, done: ((Boolean) -> Unit)? = null): Boolean {
        if (t.refs <= 0 && t.pins <= 0) return false
        inFlight[t]?.let { waiting ->
            waiting += { if (!readIn(t, priority, done)) done?.invoke(false) }
            return true
        }
        if (t.bitmap != null) {
            done?.invoke(true)
            return true
        }
        val leased = lease(t)
        if (leased == null) {
            // Pinned but held by no store (an undo held it alone): a pin is a lease too.
            inFlight[t] = ArrayList(0)
            t.pins++
        }
        submit(priority, { t.bitmapForRead() }) { r ->
            val b: Bitmap? = r.getOrNull()
            if (b != null) TileOps.adoptRead(t, b)
            if (leased != null) TileOps.release(t) else TileOps.unpinTile(t)
            ended(t)
            done?.invoke(t.bitmap != null)
        }
        return true
    }

    /**
     * Main only (A8, design 2.6, an undo that finds SWAPPED tiles): pins each of [tiles] (the
     * commit rule then keeps a tile resident once read) and reads the SWAPPED ones back at
     * READ_URGENT ([readIn]). [then] runs on main once every read ended, at once when none is
     * SWAPPED, with true when every read made its tile resident; the pins go after it. A packed
     * tile is not read here: main inflates it where it is drawn, as the compositor does.
     */
    fun readThen(tiles: Collection<Tile>, then: (Boolean) -> Unit) {
        val held = ArrayList<Tile>(tiles.size)
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Tile, Boolean>())
        for (t in tiles) if (seen.add(t)) held += t
        for (t in held) t.pins++
        var ok = true
        val finish = {
            try {
                then(ok)
            } finally {
                for (t in held) TileOps.unpinTile(t)
            }
        }
        val swapped = held.filter { it.state == TileState.SWAPPED }
        if (swapped.isEmpty()) {
            finish()
            return
        }
        var left = swapped.size
        for (t in swapped) {
            val queued = readIn(t, Priority.READ_URGENT) { resident ->
                if (!resident) ok = false
                if (--left == 0) finish()
            }
            if (!queued) {
                ok = false
                if (--left == 0) finish()
            }
        }
    }
}

/**
 * v1.10 (A8 step 1, design 2.8, J8): the Java heap held by PACKED tiles (packed by the worker and
 * waiting to be written, or read ahead). At most [cap] bytes, `min(64 MiB, J/8)`, and every buffer
 * admitted passes `MemoryGovernor.admit` (so packed tiles never starve filters and text layout of
 * the heap margin). [TileOps] counts a buffer in when it commits a tile PACKED and out when the
 * tile leaves PACKED or is freed. Any thread.
 */
internal object PackedPool {
    private const val MAX_BYTES = 64L shl 20

    private val held = AtomicLong()

    /** The bytes of packed tiles now. */
    val heldBytes: Long get() = held.get()

    /** `min(64 MiB, J/8)`. */
    fun cap(): Long = min(MAX_BYTES, EngineConfig.budget.javaMax / 8)

    /** Counts [bytes] in when they fit under [cap] and the heap admits them; false (nothing counted) otherwise. */
    fun tryAdmit(bytes: Long): Boolean {
        val cap = cap()
        while (true) {
            val now = held.get()
            if (now + bytes > cap) return false
            if (held.compareAndSet(now, now + bytes)) break
        }
        if (MemoryGovernor.admit(bytes, PURPOSE)) return true
        held.addAndGet(-bytes)
        return false
    }

    /** Counts [bytes] out (a packed buffer let go). */
    fun release(bytes: Long) {
        held.addAndGet(-bytes)
    }

    private const val PURPOSE = "packed tile"
}
