package com.brushwork.paint.engine.tiles

import android.os.Looper
import android.os.MessageQueue
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** v1.10 (F1, design 2.0 "Layer states"): where a layer's pixels are (`Layer.residency`). */
enum class ResidencyState { FOLDER, BLANK, FLAT, PARKED, EVICTED }

/**
 * v1.10 (F1, design 2.0 and 2.7): what the engine tells the residency policy
 * (`EngineConfig.residency`). Main thread. `ClassicResidency` until a session at TILES or FULL
 * installs another (A8's `ResidencyPolicy`; tests: [ChaosResidency]).
 */
interface ResidencyHooks {
    /** From the Document.activeLayerIndex setter, once per main-thread task (section 2.7). */
    fun activate(layer: Layer)

    fun deactivated(layer: Layer)

    fun pressure(p: MemoryPressure)

    /** Main looper, every 500 ms while an editor is open. */
    fun idleTick()

    /** P3(2): swap undo-only tiles, oldest step first, until targetBytes are freed; returns the bytes freed. Default: 0 (no-op). */
    fun swapUndoTiles(targetBytes: Long): Long = 0L
}

/** v1.10 (F1): CLASSIC (P6): every call is a no-op; nothing parks. */
object ClassicResidency : ResidencyHooks {
    override fun activate(layer: Layer) = Unit
    override fun deactivated(layer: Layer) = Unit
    override fun pressure(p: MemoryPressure) = Unit
    override fun idleTick() = Unit
}

/**
 * v1.10 (F1 reference, design 3.3; tests only): parks every unpinned inactive layer at once and
 * unparks the active one, on the calling thread: a park cuts the flat content and mask with
 * [TiledPixels.fromBitmap], an unpark materializes them. Neither changes `pixelGeneration` (J5).
 * The unit-full run (`-PengineMode=full|tiles`) installs it; with [everyTask] (`-PparkChaos`) it
 * also parks again whenever the main looper goes idle, so a layer a reader materialized is parked
 * once more before the next task.
 *
 * A park keeps the flat bitmap alive (it is not recycled): v1.9 code (undo steps, tools) may
 * still hold it. An unpark releases the tiles it replaced.
 */
class ChaosResidency(private val everyTask: Boolean = false) : ResidencyHooks {
    override fun activate(layer: Layer) = unpark(layer)

    override fun deactivated(layer: Layer) {
        park(layer)
    }

    override fun pressure(p: MemoryPressure) = Unit

    override fun idleTick() = Unit

    /** Parks [layer]'s flat content and mask; false when nothing was parked (a folder, a pinned layer, nothing flat). Main only. */
    fun park(layer: Layer): Boolean {
        if (layer.isFolder || layer.pins > 0) return false
        val content = layer.pixels as? FlatPixels
        val mask = layer.maskPixels as? FlatPixels
        if (!parkable(content) && !parkable(mask)) return false
        val c = if (parkable(content)) TiledPixels.fromBitmap(content!!.bitmap) else layer.pixels
        val m = if (parkable(mask)) TiledPixels.fromBitmap(mask!!.bitmap) else layer.maskPixels
        layer.rehouse(c, m)
        return true
    }

    private fun parkable(p: FlatPixels?): Boolean = p != null && !p.bitmap.isRecycled && p.bitmap.width > 0 && p.bitmap.height > 0

    /** Makes [layer]'s parked content and mask flat again and releases their tiles. Main only. */
    fun unpark(layer: Layer) {
        val content = layer.pixels as? TiledPixels
        val mask = layer.maskPixels as? TiledPixels
        if (content == null && mask == null) return
        val c = content?.let { FlatPixels(it.materialize()) } ?: layer.pixels
        val m = mask?.let { FlatPixels(it.materialize()) } ?: layer.maskPixels
        layer.rehouse(c, m)
        content?.let { TileOps.releaseStore(it) }
        mask?.let { TileOps.releaseStore(it) }
    }

    /** Parks every layer of [doc] but the active one; returns how many were parked. Main only. */
    fun parkInactive(doc: Document): Int {
        val active = doc.layers.getOrNull(doc.activeLayerIndex)
        var n = 0
        for (l in doc.layers) if (l !== active && park(l)) n++
        return n
    }

    /** `EngineConfig`, at the end of the task that changed [doc]'s active layer: the active layer flat, every other parked. */
    internal fun settled(doc: Document) {
        doc.layers.getOrNull(doc.activeLayerIndex)?.let { unpark(it) }
        parkInactive(doc)
        if (everyTask) watch(doc)
    }

    private val watched: MutableSet<Document> = Collections.newSetFromMap(WeakHashMap())

    /** [everyTask]: parks [doc]'s inactive layers again whenever the main looper is idle, while this is the residency and [doc] lives. */
    private fun watch(doc: Document) {
        val looper = Looper.getMainLooper() ?: return
        if (!watched.add(doc)) return
        val ref = WeakReference(doc)
        looper.queue.addIdleHandler(
            MessageQueue.IdleHandler {
                val d = ref.get()
                if (d == null || EngineConfig.residency !== this) {
                    d?.let { watched.remove(it) }
                    false
                } else {
                    parkInactive(d)
                    true
                }
            },
        )
    }
}
