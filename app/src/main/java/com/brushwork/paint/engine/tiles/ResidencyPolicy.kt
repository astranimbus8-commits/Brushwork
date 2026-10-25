package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.TileHolder
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import java.util.IdentityHashMap

/**
 * v1.10 (A8 steps 2 and 3, design 2.0 P1-P6, 2.7, 2.8): where each layer's pixels live while an
 * editor is open at TILES or FULL. Main thread throughout; the slow parts run on the
 * `tile-swapper` ([TileWorker], [ParkCutter]) and come back in posted tasks.
 *
 * - **P1** The active layer (content and mask) is FLAT. [activate] unparks it: synchronously when
 *   every tile is resident and the layer is at most [SYNC_UNPARK_BYTES]; otherwise the worker
 *   reads the missing tiles (READ_URGENT) while [Host.activating] holds canvas input, and main
 *   materializes once they are in. [prepare] starts it at the press, before the switch (section
 *   2.7). A pinned layer keeps what it holds; a plain pin never swaps anything in.
 * - **P2** Every 500 ms ([TICK_MS]) [idleTick] parks the layers inactive for [PARK_DELAY_MS]
 *   (2 s): never while [Host.busy] (a gesture is down or a job runs), never a pinned layer.
 *   Switching back within 2 s costs nothing (J6: nothing was parked). Under
 *   [MemoryPressure.LOW] or [MemoryPressure.CRITICAL] every eligible layer parks at once, the
 *   coldest (longest inactive) first.
 * - **P3** Over [tileRam] (bytes of resident tiles, the TILES ledger), tiles leave: (2) undo-only
 *   tiles (held by undo steps, by no store of the document), the oldest step first, never those of
 *   the active layer's newest [UNDO_KEEP_STEPS] steps nor of the newest [UNDO_KEEP_BYTES] of undo;
 *   then (3) cold tiles of parked layers, coldest first by frame epoch ([Tile.lastUse]). At FULL a
 *   tile is written to the swap file when it has room, else (and at TILES, which has no swap) it
 *   is packed into the Java pool ([PackedPool]). Order (1), evicting clean layers to their saved
 *   file, is A10's. The commit rule of section 2.7 decides on main whether each tile really leaves
 *   ([TileOps.commitPacked], [TileOps.commitWritten]).
 * - **P6** CLASSIC never installs it ([attach]).
 *
 * Installed through `EngineConfig.residency` (its internal setter, here only): [attach] by the
 * editor at TILES and FULL, never over another residency (a test's, or `ChaosResidency` under
 * `-PengineMode`); a test installs one with `EngineRule(residency = ...)` and [start]s it. It
 * stops by itself once another residency replaced it (the session closed).
 */
class ResidencyPolicy internal constructor(
    private val host: Host,
    /** P3's budget for resident tiles (bytes); `MemoryBudget.tileRam()` of the session (a test sets its own). */
    internal var tileRam: () -> Long = { EngineConfig.budget.tileRam() },
) : ResidencyHooks {
    /** What the policy needs of the editor (main thread). */
    interface Host {
        /** The open document (the only one the policy acts on). */
        val document: Document

        /** A gesture is down, a tool or a busy job is running: nothing parks (P2). */
        val busy: Boolean

        /** The layer whose activation holds canvas input (`EditorController.activating`). */
        var activating: Layer?

        /** The undo history (P3 order 2), if any. */
        val history: UndoManager?
    }

    /** Uptime (ms) at which each inactive layer (by id) became inactive or was first seen so. */
    private val inactiveSince = HashMap<Long, Long>()

    /** The activation in flight (asynchronous): its layer, and a token later results compare. */
    private var activatingLayer: Layer? = null
    private var activationToken = 0

    /** Tests: told on main of every layer a park committed, in commit order. */
    internal var onParked: ((Layer) -> Unit)? = null

    private var started = false
    private var registration: MemoryGovernor.Registration? = null
    private val tick = Runnable { onTick() }

    private val doc: Document get() = host.document

    private fun activeLayer(): Layer? = doc.layers.getOrNull(doc.activeLayerIndex)

    private fun inDocument(layer: Layer): Boolean = doc.layers.any { it === layer }

    private fun now(): Long = SystemClock.uptimeMillis()

    // ---------------------------------------------------------------- lifecycle

    /**
     * Main only: starts the idle tick and the memory registration, once this is
     * `EngineConfig.residency`. Idempotent; false when another residency is installed.
     */
    fun start(): Boolean {
        if (EngineConfig.residency !== this) return false
        if (started) return true
        started = true
        // One residency per process: a policy that never saw its replacement (its editor went
        // without a tick) gives up its registration here.
        liveRegistration?.close()
        registration = MemoryGovernor.register(MEMORY_NAME, MEMORY_PRIORITY, { residentTileBytes() }) { p ->
            if (EngineConfig.residency === this) pressure(p)
            0L
        }.also { liveRegistration = it }
        schedule()
        return true
    }

    /** Main only: stops the tick and the registration (another residency took over, or the editor closed). */
    fun stop() {
        if (!started) return
        started = false
        registration?.close()
        if (liveRegistration === registration) liveRegistration = null
        registration = null
        Looper.getMainLooper()?.let { Handler(it).removeCallbacks(tick) }
    }

    private fun schedule() {
        val looper = Looper.getMainLooper() ?: return
        Handler(looper).postDelayed(tick, TICK_MS)
    }

    private fun onTick() {
        if (!started) return
        if (EngineConfig.residency !== this) {
            stop()
            return
        }
        idleTick()
        schedule()
    }

    private fun residentTileBytes(): Long = EngineConfig.budget.ledger.of(LedgerCategory.TILES)

    // ---------------------------------------------------------------- hooks

    /**
     * From the `Document.activeLayerIndex` setter, at the end of the task (P1): [layer] becomes
     * FLAT; its pending park and any cut in flight are dropped.
     */
    override fun activate(layer: Layer) {
        if (!inDocument(layer)) return
        inactiveSince.remove(layer.id)
        unpark(layer)
    }

    /** Deactivation schedules the park after [PARK_DELAY_MS] (P2). */
    override fun deactivated(layer: Layer) {
        if (!inDocument(layer)) return
        inactiveSince[layer.id] = now()
        if (activatingLayer === layer) endActivation(layer)
    }

    /** LOW and CRITICAL park every eligible layer at once, coldest first, then run P3. */
    override fun pressure(p: MemoryPressure) {
        if (p != MemoryPressure.LOW && p != MemoryPressure.CRITICAL) return
        parkEligible(force = true)
        relieveTileRam()
    }

    /** Main looper, every [TICK_MS]: a new frame epoch, the parks that are due (P2), then P3. */
    override fun idleTick() {
        TileOps.nextEpoch()
        parkEligible(force = false)
        relieveTileRam()
    }

    /** P3 order 2 alone: starts swapping undo-only tiles until [targetBytes]; returns the bytes started (they leave RAM as their writes commit). */
    override fun swapUndoTiles(targetBytes: Long): Long = sendOut(undoOnlyTiles(), targetBytes)

    // ---------------------------------------------------------------- P1 activation

    /**
     * Main only, at the press (section 2.7): starts making [layer] FLAT before it is activated, so
     * the switch finds it ready. True when it is FLAT (or BLANK, or a folder) when this returns;
     * false while an asynchronous unpark holds input.
     */
    fun prepare(layer: Layer): Boolean {
        // The user moved on from a layer still being read: its hold ends (its reads land, unused).
        activatingLayer?.let { if (it !== layer) endActivation(it) }
        if (!inDocument(layer)) return true
        inactiveSince.remove(layer.id)
        return unpark(layer)
    }

    /** True when [layer]'s content or mask is a parked (not evicted) tiled store. */
    private fun isParked(layer: Layer): Boolean =
        isParkedStore(layer.pixels) || isParkedStore(layer.maskPixels)

    private fun isParkedStore(p: PixelStore?): Boolean = p is TiledPixels && p.evictedTo == null

    /** P1: makes [layer] FLAT; true when done before returning. */
    private fun unpark(layer: Layer): Boolean {
        if (!isParked(layer)) return true
        if (activatingLayer === layer) return false
        val stores = listOfNotNull(layer.pixels as? TiledPixels, layer.maskPixels as? TiledPixels).filter { isParkedStore(it) }
        val missing = ArrayList<Tile>()
        for (s in stores) for (t in s.tiles) if (t != null && t.bitmap == null) missing += t
        val bytes = layer.pixels.width.toLong() * layer.pixels.height * 4
        if (missing.isEmpty() && bytes <= SYNC_UNPARK_BYTES) return materialize(layer)
        startAsync(layer, missing)
        return false
    }

    /**
     * Main: [layer]'s parked content and mask become FLAT (every tile is resident, or read on main
     * as a last resort); the tiles they held are released. False when out of memory (it stays
     * parked; the bitmap getter tries again) or the layer moved on meanwhile.
     */
    private fun materialize(layer: Layer, builds: List<FlatBuild> = emptyList()): Boolean {
        val readContent = layer.pixels
        val readMask = layer.maskPixels
        val content = (readContent as? TiledPixels)?.takeIf { isParkedStore(it) }
        val mask = (readMask as? TiledPixels)?.takeIf { isParkedStore(it) }
        val builtContent = content?.let { s -> builds.firstOrNull { it.store === s }?.take() }
        val builtMask = mask?.let { s -> builds.firstOrNull { it.store === s }?.take() }
        for (b in builds) b.drop()
        if (content == null && mask == null) return true
        val c = try {
            content?.let { FlatPixels(builtContent ?: it.materialize()) }
        } catch (e: OutOfMemoryError) {
            builtMask?.recycle()
            return false
        }
        val m = try {
            mask?.let { FlatPixels(builtMask ?: it.materialize()) }
        } catch (e: OutOfMemoryError) {
            c?.bitmap?.recycle()
            return false
        }
        val same = synchronized(layer) {
            val same = layer.pixels === readContent && layer.maskPixels === readMask
            if (same) layer.rehouse(c ?: readContent, m ?: readMask)
            same
        }
        if (same) {
            content?.let { TileOps.releaseStore(it) }
            mask?.let { TileOps.releaseStore(it) }
        } else {
            c?.bitmap?.recycle()
            m?.bitmap?.recycle()
        }
        return same
    }

    /**
     * An activation's flat copy of one parked [store], filled on main tile by tile: the resident
     * and solid tiles at once, each read tile as its read lands (so the worker's inflates and
     * main's copying overlap, and the switch ends with a rehouse instead of a whole copy).
     */
    private class FlatBuild(val store: TiledPixels, private var out: Bitmap?) {
        private val canvas = Canvas(out!!)

        /** The tile drawn at each index (null where none is yet, or the store holds none). */
        private val drawn = arrayOfNulls<Tile>(store.tiles.size)

        /** Draws every tile of [store] that is resident and not drawn yet. */
        fun drawResident() {
            if (out == null) return
            for (i in drawn.indices) {
                val t = store.tiles[i] ?: continue
                // A new flat bitmap is transparent already: a transparent solid tile needs no copy.
                if (drawn[i] !== t && (t.solid == 0 || TileOps.drawIfResident(canvas, store, i))) drawn[i] = t
            }
        }

        /** Draws [t] wherever [store] holds it (just read). */
        fun drawRead(t: Tile) {
            if (out == null) return
            for (i in drawn.indices) if (store.tiles[i] === t && TileOps.drawIfResident(canvas, store, i)) drawn[i] = t
        }

        /**
         * The finished copy, handed over (the caller owns it), when every tile [store] holds now
         * was drawn and none it no longer holds was; else null (and the copy is recycled).
         */
        fun take(): Bitmap? {
            drawResident()
            val b = out ?: return null
            out = null
            canvas.setBitmap(null)
            for (i in drawn.indices) if (drawn[i] !== store.tiles[i]) {
                b.recycle()
                return null
            }
            return b
        }

        /** Recycles the copy unless it was taken. */
        fun drop() {
            out?.let { canvas.setBitmap(null); it.recycle() }
            out = null
        }
    }

    /** The flat copies [startAsync] fills for [layer]'s parked content and mask (none for a store with no room). */
    private fun flatBuilds(layer: Layer): List<FlatBuild> {
        val out = ArrayList<FlatBuild>(2)
        for (s in listOfNotNull(layer.pixels as? TiledPixels, layer.maskPixels as? TiledPixels)) {
            if (!isParkedStore(s)) continue
            val b = try {
                TileOps.newFlatFor(s)
            } catch (e: OutOfMemoryError) {
                continue
            }
            out += FlatBuild(s, b).also { it.drawResident() }
        }
        return out
    }

    /**
     * P1, section 2.7: the worker reads [missing] (READ_URGENT) while `activating` holds input;
     * main copies each tile into [layer]'s flat bitmaps as its read lands ([FlatBuild]) and
     * rehouses the layer once every read ended, if it is still the one being activated. The
     * layer is pinned meanwhile, so P3 sends none of its tiles out again.
     * An undo or redo asked meanwhile waits for the activation to end (`UndoManager.beginWaiting`):
     * it never changes the store being read and copied.
     */
    private fun startAsync(layer: Layer, missing: List<Tile>) {
        val token = ++activationToken
        activatingLayer = layer
        host.activating = layer
        val pin = layer.pin()
        val endWait = host.history?.beginWaiting()
        var builds: List<FlatBuild> = emptyList()
        var left = missing.size
        var finished = false
        val finish = {
            finished = true
            pin.close()
            if (token == activationToken && activatingLayer === layer) {
                if (inDocument(layer)) materialize(layer, builds)
                endActivation(layer)
            }
            for (b in builds) b.drop()
            endWait?.invoke()
        }
        if (missing.isEmpty()) {
            finish()
            return
        }
        for (t in missing) {
            val queued = TileWorker.readIn(t, TileWorker.Priority.READ_URGENT) { ok ->
                if (token != activationToken) for (b in builds) b.drop() else if (ok) for (b in builds) b.drawRead(t)
                if (--left == 0) finish()
            }
            if (!queued && --left == 0) finish()
        }
        // The reads are queued first, so copying the resident tiles overlaps the worker's inflates.
        if (!finished) builds = flatBuilds(layer)
    }

    private fun endActivation(layer: Layer) {
        if (activatingLayer !== layer) return
        activatingLayer = null
        activationToken++
        if (host.activating === layer) host.activating = null
    }

    // ---------------------------------------------------------------- P2 parking

    /**
     * May [layer] park now? Not the active layer, not a folder, not pinned, something flat to cut,
     * no cut in flight, not while the editor is busy.
     */
    private fun eligible(layer: Layer, active: Layer?): Boolean =
        layer !== active && !layer.isFolder && layer.pins == 0 && !ParkCutter.isCutting(layer) &&
            (ParkCutter.parkable(layer.pixels) || ParkCutter.parkable(layer.maskPixels))

    /** P2: parks the layers due (inactive for [PARK_DELAY_MS]), or every eligible one with [force], the coldest first. */
    private fun parkEligible(force: Boolean) {
        if (host.busy) return
        val active = activeLayer()
        val t = now()
        val due = ArrayList<Layer>()
        val alive = HashSet<Long>()
        for (l in doc.layers) {
            alive += l.id
            if (!eligible(l, active)) continue
            val since = inactiveSince.getOrPut(l.id) { t }
            if (force || t - since >= PARK_DELAY_MS) due += l
        }
        inactiveSince.keys.retainAll(alive)
        due.sortBy { inactiveSince[it.id] ?: t }
        for (l in due) ParkCutter.park(l, { mayCommitPark(l) }) { parked ->
            if (parked) {
                inactiveSince.remove(l.id)
                onParked?.invoke(l)
            }
        }
    }

    /** At the park's commit: still in the document, still inactive, the editor not busy. */
    private fun mayCommitPark(layer: Layer): Boolean =
        EngineConfig.residency === this && !host.busy && layer !== activeLayer() && activatingLayer !== layer && inDocument(layer)

    // ---------------------------------------------------------------- P3 tile RAM

    /** P3: when the resident tiles exceed [tileRam], sends out undo-only tiles (order 2), then cold parked ones (order 3). */
    private fun relieveTileRam() {
        var over = residentTileBytes() - tileRam()
        if (over <= 0) return
        over -= sendOut(undoOnlyTiles(), over)
        if (over > 0) sendOut(coldParkedTiles(), over)
    }

    /** Starts sending [tiles] out, in order, until [targetBytes] are on their way; returns the bytes started. */
    private fun sendOut(tiles: List<Tile>, targetBytes: Long): Long {
        var started = 0L
        val swap = EngineConfig.level == EngineLevel.FULL
        for (t in tiles) {
            if (started >= targetBytes) break
            if (!leavable(t)) continue
            if (swap && TileWorker.writeOut(t)) {
                started += t.bytes
                continue
            }
            // A full pool would refuse the bytes at the commit: no pack is started for nothing.
            if (PackedPool.heldBytes + t.bytes / 2 > PackedPool.cap()) break
            if (TileWorker.packOut(t)) started += t.bytes
        }
        return started
    }

    /** A resident tile of its own pixels that no pin holds, not used in this epoch and with no job in flight. */
    private fun leavable(t: Tile): Boolean =
        t.bitmap != null && t.solid == null && t.pins == 0 && t.refs > 0 && t.lastUse < TileOps.frameEpoch && !TileWorker.busy(t)

    /** Every tile a store of the document holds (identity). */
    private fun liveTiles(): MutableSet<Tile> {
        val live = java.util.Collections.newSetFromMap(IdentityHashMap<Tile, Boolean>())
        for (l in doc.layers) {
            (l.pixels as? TiledPixels)?.tiles?.forEach { if (it != null) live += it }
            (l.maskPixels as? TiledPixels)?.tiles?.forEach { if (it != null) live += it }
        }
        return live
    }

    /**
     * P3 order 2: the undo-only tiles, those of the oldest step first; never those of the active
     * layer's newest [UNDO_KEEP_STEPS] steps nor of the newest [UNDO_KEEP_BYTES] of undo.
     */
    internal fun undoOnlyTiles(): List<Tile> {
        val history = host.history ?: return emptyList()
        val n = history.undoCount
        if (n == 0) return emptyList()
        val active = activeLayer()
        val kept = BooleanArray(n)
        var bytes = 0L
        var activeSteps = 0
        for (i in n - 1 downTo 0) {
            val step = history.undoAt(i) ?: continue
            if (bytes < UNDO_KEEP_BYTES) kept[i] = true
            bytes += step.byteSize
            if (active != null && (step as? TileHolder)?.tileLayer === active && activeSteps < UNDO_KEEP_STEPS) {
                kept[i] = true
                activeSteps++
            }
        }
        val live = liveTiles()
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Tile, Boolean>())
        val out = ArrayList<Tile>()
        for (i in 0 until n) {
            if (kept[i]) continue
            val step = history.undoAt(i) as? TileHolder ?: continue
            step.forEachTile { t -> if (t !in live && seen.add(t)) out += t }
        }
        return out
    }

    /** P3 order 3: the resident tiles of parked layers (not the active one, nor a pinned one), coldest first. */
    internal fun coldParkedTiles(): List<Tile> {
        val active = activeLayer()
        val out = ArrayList<Tile>()
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Tile, Boolean>())
        for (l in doc.layers) {
            if (l === active || l.pins > 0) continue
            for (p in arrayOf(l.pixels, l.maskPixels)) {
                val s = p as? TiledPixels ?: continue
                for (t in s.tiles) if (t != null && t.bitmap != null && t.solid == null && seen.add(t)) out += t
            }
        }
        out.sortBy { it.lastUse }
        return out
    }

    companion object {
        /** P2: a layer parks after this long inactive. */
        const val PARK_DELAY_MS = 2000L

        /** P2: the idle tick. */
        const val TICK_MS = 500L

        /** P1: a parked layer with every tile resident is materialized synchronously up to this size. */
        const val SYNC_UNPARK_BYTES = 32L shl 20

        /** P3 order 2: the active layer's newest steps whose tiles never leave. */
        const val UNDO_KEEP_STEPS = 3

        /** P3 order 2: the newest undo bytes whose tiles never leave. */
        const val UNDO_KEEP_BYTES = 32L shl 20

        /** Below the proxies (10) and above the display caches (0): LOW pressure reaches it. */
        private const val MEMORY_PRIORITY = 5

        private const val MEMORY_NAME = "parked layers"

        /** Main only: the registration of the policy started last (at most one is live). */
        private var liveRegistration: MemoryGovernor.Registration? = null

        /**
         * Main only: the editor's policy at TILES and FULL, installed as `EngineConfig.residency`
         * and started; null at CLASSIC (P6), or when another residency is installed already (a
         * test's, or `ChaosResidency` under `-PengineMode`), which is left alone.
         */
        fun attach(controller: EditorController): ResidencyPolicy? {
            if (EngineConfig.level == EngineLevel.CLASSIC || EngineConfig.residency !== ClassicResidency) return null
            // The residency is main-thread state; an editor built elsewhere runs without one (as CLASSIC).
            if (Looper.getMainLooper()?.isCurrentThread != true) return null
            val policy = ResidencyPolicy(ControllerHost(controller))
            EngineConfig.residency = policy
            policy.start()
            return policy
        }

        /**
         * Main only: installs [policy] as `EngineConfig.residency` (tests that build their own),
         * and starts its tick and memory registration with [start] (a test without drives
         * [idleTick] itself).
         */
        internal fun install(policy: ResidencyPolicy, start: Boolean = true) {
            EngineConfig.residency = policy
            if (start) policy.start()
        }
    }

    /** The editor as a [Host]. */
    private class ControllerHost(private val c: EditorController) : Host {
        override val document: Document get() = c.doc
        // A gesture, a busy job, or an open tool session (a filter, a tool with work not yet committed).
        override val busy: Boolean
            get() = c.isInteracting || c.busyMessage != null || c.filterSession != null || c.currentTool.hasPendingWork
        override var activating: Layer?
            get() = c.activating
            set(value) {
                c.activating = value
            }
        override val history: UndoManager get() = c.undoManager
    }
}
