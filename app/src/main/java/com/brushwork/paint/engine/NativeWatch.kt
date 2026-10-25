package com.brushwork.paint.engine

import android.util.Log
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.budget.MemSnapshot
import com.brushwork.paint.engine.tiles.SavedFileRef
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.LayerCodec
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

/**
 * v1.10 (A10, design 2.0 P3, 2.8 and 2.12): the engine's net under native-memory pressure.
 *
 * **Eviction to the saved file (P3 order 1).** A PARKED layer whose content is unchanged since its
 * last save (`contentVersion == savedVersion`) and whose saved file is current ([SavedFileRef],
 * given by the caller that knows the last save's `referencedFiles`) can drop every tile at no
 * write cost: its content becomes an EVICTED store (`TiledPixels.evictedTo`, no tiles), with no
 * `pixelGeneration` change (J5). The coldest go first, by the epoch of their last read (the newest
 * `Tile.lastUse` of their tiles, frame epochs set by the compositor's reads), and never a layer
 * read in the last [EVICT_MIN_AGE_EPOCHS] (8) epochs: an evicted layer comes back only as one
 * whole Deflate stream ([reload]), so a warm one is never evicted. A pinned layer, a FLAT, BLANK or
 * already EVICTED one, a changed one and one whose file is missing are never evicted. Its mask
 * stays as it is. Main thread, like every residency change.
 *
 * The callers: P3 order 1 in the residency policy (A8), which knows the saved files; the reload
 * reference [reload] reads the whole file and cuts it, until the streamed load of section 2.9
 * (A11) replaces it.
 *
 * **The pressure net (section 2.12).** Every [WATCH_MS] (2 s) while an editor is open,
 * `EngineEditorChrome` reads the memory off main and calls [tick] on main: below `threshold` +
 * 256 MiB of available memory it acts as CRITICAL (the residency policy parks and swaps down,
 * the governor's caches are trimmed) and [refusingNewLayers] holds until available memory is
 * above `threshold` + 512 MiB; a ledger drift over 256 MiB from the native heap asks for one
 * background GC. Each tick also records the ledger's total in `EngineStats`.
 */
object NativeWatch {
    /** A layer read within this many frame epochs is never evicted. */
    const val EVICT_MIN_AGE_EPOCHS = 8L

    /** A layer that may be evicted, with the saved file of its content. */
    class Candidate(val layer: Layer, val ref: SavedFileRef)

    /** The epoch of the last read of [store]'s tiles (their newest `lastUse`; 0 when none was read). Main. */
    fun lastRead(store: TiledPixels): Long {
        var newest = 0L
        for (i in 0 until store.cols * store.rows) {
            val t = store.tileAt(i % store.cols, i / store.cols) ?: continue
            if (t.lastUse > newest) newest = t.lastUse
        }
        return newest
    }

    /** True when [layer] may be evicted to [ref] at [nowEpoch] (the rules above). Main. */
    fun evictable(layer: Layer, ref: SavedFileRef, nowEpoch: Long): Boolean {
        if (layer.isFolder || layer.pins > 0) return false
        val store = layer.pixels as? TiledPixels ?: return false
        if (store.evictedTo != null) return false
        if (layer.contentVersion != layer.savedVersion || ref.contentVersion != layer.contentVersion) return false
        if (!File(ref.projectDir, ref.file).isFile) return false
        return nowEpoch - lastRead(store) >= EVICT_MIN_AGE_EPOCHS
    }

    /** P3 order 1: the evictable [candidates] at [nowEpoch], coldest first (ties keep their order). Main. */
    fun evictionOrder(candidates: List<Candidate>, nowEpoch: Long): List<Candidate> =
        candidates.filter { evictable(it.layer, it.ref, nowEpoch) }.sortedBy { lastRead(it.layer.pixels as TiledPixels) }

    /**
     * Evicts [layer]'s parked content to [ref] when it is still [evictable] at [nowEpoch]: the layer
     * holds an EVICTED store and the parked store's tiles are released (a tile an undo step or a
     * snapshot still holds stays theirs). Returns the TILES bytes the ledger let go (0: refused).
     * Main.
     */
    fun evict(layer: Layer, ref: SavedFileRef, nowEpoch: Long): Long {
        if (!evictable(layer, ref, nowEpoch)) return 0L
        val parked = layer.pixels as TiledPixels
        val evicted = TiledPixels(parked.width, parked.height, arrayOfNulls(parked.cols * parked.rows), ref)
        val ledger = EngineConfig.budget.ledger
        val before = ledger.of(LedgerCategory.TILES)
        val done = synchronized(layer) {
            val same = layer.pixels === parked
            if (same) layer.rehouse(evicted, layer.maskPixels)
            same
        }
        if (!done) return 0L
        TileOps.releaseStore(parked)
        return (before - ledger.of(LedgerCategory.TILES)).coerceAtLeast(0L)
    }

    /** P3 order 1 until [targetBytes] are freed: evicts the coldest [candidates] first; returns the bytes freed. Main. */
    fun evictColdest(candidates: List<Candidate>, nowEpoch: Long, targetBytes: Long): Long {
        var freed = 0L
        for (c in evictionOrder(candidates, nowEpoch)) {
            if (freed >= targetBytes) break
            freed += evict(c.layer, c.ref, nowEpoch)
        }
        return freed
    }

    /**
     * The pixels of the EVICTED [store], read back from its saved file and cut into tiles (blank
     * tiles null, uniform opaque ones shared, as `TiledPixels.fromBitmap` cuts) as a PARKED store.
     * The reference body: the file is inflated whole into one transient byte array (the codec's own
     * read) and each tile is cut from those bytes, with no layer-sized bitmap. Throws `IOException`
     * (the file is unreadable, damaged or not the store's size); the caller restores the layer as
     * for a failed swap read (design 2.8).
     */
    fun reload(store: TiledPixels): TiledPixels {
        val ref = store.evictedTo ?: throw IllegalArgumentException("not an evicted store")
        val w = store.width
        val h = store.height
        val bytes = try {
            ByteArray(LayerCodec.byteLength(w, h)).also { LayerCodec.read(File(ref.projectDir, ref.file), w, h, it) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("${ref.file} does not read back", e)
        }
        return cut(bytes, w, h)
    }

    // ---------------------------------------------------------------- the pressure net (design 2.12)

    /** The watch's period while an editor is open (`EngineEditorChrome`). */
    const val WATCH_MS = 2_000L

    /** Below `threshold` plus this much available memory the watch acts as CRITICAL (256 MiB). */
    const val CRITICAL_MARGIN = 256L shl 20

    /** New painted layers are refused until available memory is above `threshold` plus this (512 MiB). */
    const val RECOVER_MARGIN = 512L shl 20

    /** A ledger drift this large against the native heap requests one background GC (256 MiB). */
    const val DRIFT_MARGIN = 256L shl 20

    /** What one [tick] saw and did. */
    data class Tick(val critical: Boolean, val refusing: Boolean, val gcRequested: Boolean)

    /**
     * True from a CRITICAL tick until available memory is back above `threshold` +
     * [RECOVER_MARGIN]: no new painted layer should be admitted meanwhile (read by the write
     * admission, A7's `admitWrite`, after the merge). Any thread.
     */
    @Volatile
    var refusingNewLayers = false
        private set

    /** One GC per drift episode: true from the request until the drift falls back under [DRIFT_MARGIN]. */
    @Volatile
    private var gcAsked = false

    /** The background GC a drift asks for (tests put a counter here). */
    @Volatile
    internal var requestGc: () -> Unit = { Thread({ System.gc() }, "native-watch-gc").apply { isDaemon = true }.start() }

    /**
     * Tests: under Robolectric (one JVM, many apps) the editor's chrome runs no watch unless a test
     * sets this (reset it in `finally`).
     */
    @Volatile
    internal var runUnderRobolectric = false

    /** The editor's chrome runs the watch: on a phone always, under Robolectric only when a test asks. */
    val watchRuns: Boolean get() = runUnderRobolectric || android.os.Build.FINGERPRINT != "robolectric"

    /** A new editor session: nothing refused, no GC asked. Main. */
    fun reset() {
        refusingNewLayers = false
        gcAsked = false
    }

    /**
     * One watch tick with [snap] (read off main): records the ledger's total
     * ([EngineStat.LEDGER_PEAK]); with known memory, below `threshold` + [CRITICAL_MARGIN] it acts
     * as CRITICAL (the residency policy parks every eligible layer and swaps tiles down,
     * `ResidencyHooks.pressure`; the governor's caches and projections are trimmed as for a
     * CRITICAL trim, which API 34+ no longer sends) and refuses new painted layers until above
     * `threshold` + [RECOVER_MARGIN]; a drift of the ledger from the native heap over
     * [DRIFT_MARGIN] requests one background GC per episode. Main.
     */
    fun tick(snap: MemSnapshot): Tick {
        val ledger = EngineConfig.budget.ledger
        EngineStats.record(EngineStat.LEDGER_PEAK, ledger.native.toFloat())
        if (snap.totalMem <= 0L) return Tick(critical = false, refusing = refusingNewLayers, gcRequested = false)
        val critical = snap.availMem < snap.threshold + CRITICAL_MARGIN
        if (critical) {
            refusingNewLayers = true
            act(MemoryPressure.CRITICAL)
        } else if (snap.availMem > snap.threshold + RECOVER_MARGIN) {
            refusingNewLayers = false
        }
        val drift = abs(ledger.native - snap.nativeAllocated) > DRIFT_MARGIN
        var gc = false
        if (drift && !gcAsked) {
            gcAsked = true
            gc = true
            runCatching { requestGc() }
        } else if (!drift) {
            gcAsked = false
        }
        return Tick(critical, refusingNewLayers, gc)
    }

    /** CRITICAL: the residency policy, then the governor's registered caches. */
    private fun act(p: MemoryPressure) {
        try {
            EngineConfig.residency.pressure(p)
        } catch (e: Exception) {
            Log.w(TAG, "residency pressure", e)
        }
        try {
            MemoryGovernor.policy.onPressure(p, MemoryGovernor.entries())
        } catch (e: Exception) {
            Log.w(TAG, "governor pressure", e)
        }
    }

    private const val TAG = "BrushworkEngine"

    /** [bytes] (premultiplied ARGB_8888 as the codec stores them, [w] x [h]) cut into a PARKED store. */
    private fun cut(bytes: ByteArray, w: Int, h: Int): TiledPixels {
        val cols = (w + TILE_SIZE - 1) / TILE_SIZE
        val rows = (h + TILE_SIZE - 1) / TILE_SIZE
        val tiles = arrayOfNulls<Tile>(cols * rows)
        val px = ByteArray(TILE_SIZE * TILE_SIZE * 4)
        try {
            for (row in 0 until rows) for (col in 0 until cols) {
                val x = col * TILE_SIZE
                val y = row * TILE_SIZE
                val tw = min(TILE_SIZE, w - x)
                val th = min(TILE_SIZE, h - y)
                val line = tw * 4
                for (r in 0 until th) System.arraycopy(bytes, ((y + r) * w + x) * 4, px, r * line, line)
                val n = line * th
                val tile = when (classify(px, n)) {
                    BLANK -> null
                    UNIFORM_OPAQUE -> TileOps.solidTile(colorOf(px), tw, th)
                    else -> TileOps.tileOf(BitmapUtils.createLayerBitmap(tw, th).apply { copyPixelsFromBuffer(ByteBuffer.wrap(px, 0, n)) })
                }
                if (tile != null) {
                    tile.refs = 1
                    tiles[row * cols + col] = tile
                }
            }
        } catch (e: Throwable) {
            for (t in tiles) if (t != null) TileOps.release(t)
            throw e
        }
        return TiledPixels(w, h, tiles)
    }

    private const val BLANK = -1
    private const val OTHER = 0
    private const val UNIFORM_OPAQUE = 1

    /**
     * As the tile cut classifies: [BLANK] when every alpha is 0, [UNIFORM_OPAQUE] for one opaque
     * color, else [OTHER]. [px] holds [n] bytes, 4 per pixel with alpha last in either byte order.
     */
    private fun classify(px: ByteArray, n: Int): Int {
        var visible = false
        var uniform = true
        val b0 = px[0]
        val b1 = px[1]
        val b2 = px[2]
        val b3 = px[3]
        var i = 0
        while (i < n) {
            if (px[i + 3].toInt() != 0) visible = true
            if (px[i] != b0 || px[i + 1] != b1 || px[i + 2] != b2 || px[i + 3] != b3) uniform = false
            if (visible && !uniform) return OTHER
            i += 4
        }
        if (!visible) return BLANK
        return if (uniform && b3.toInt() and 0xFF == 0xFF) UNIFORM_OPAQUE else OTHER
    }

    /** The color of [px]'s first pixel as `Bitmap.getPixel` gives it (the bitmap knows its byte order). */
    private fun colorOf(px: ByteArray): Int {
        val one = BitmapUtils.createLayerBitmap(1, 1)
        try {
            one.copyPixelsFromBuffer(ByteBuffer.wrap(px, 0, 4))
            return one.getPixel(0, 0)
        } finally {
            one.recycle()
        }
    }
}
