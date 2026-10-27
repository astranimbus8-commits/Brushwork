package com.brushwork.paint.ui.engine

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapStore
import com.brushwork.paint.ui.common.EngineLabels110
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v1.10 A10 (J10, UQ13; design 4.4 A10 step 6): the low-storage message. Once per FULL session,
 * the first time its swap store reads less than [LOW_FREE] (1.5 GiB) of free space, the editor
 * shows [EngineLabels110.LOW_STORAGE] as its toast. The store reads the free space when it is
 * made and again once [SlabSwapStore.CAP_REFRESH_MS] (60 s) have passed, so the editor asks at its
 * start and then every 60 s ([watch], from `EngineEditorChrome`). An unknown reading (0) shows
 * nothing; CLASSIC and TILES sessions have no store, so they show nothing either. Below 1 GiB the
 * store writes nothing more, and adding layers then gives [EngineLabels110.STORAGE_FULL].
 */
internal object LowStorageNotice {
    /** The message shows below this much free space (1.5 GiB). */
    const val LOW_FREE = 1536L shl 20

    /** The session (store) the message was shown for. */
    @Volatile private var shownFor: String? = null

    /** The message to show now for [store]: once per store, while its last reading is known and under [LOW_FREE]; else null. */
    fun due(store: SwapStore): String? {
        if (store !is SlabSwapStore) return null
        store.capacityBytes // reads the free space again once 60 s have passed
        val free = store.freeBytes
        if (free <= 0L || free >= LOW_FREE) return null
        synchronized(this) {
            if (shownFor == store.sessionId) return null
            shownFor = store.sessionId
        }
        return EngineLabels110.LOW_STORAGE
    }

    /**
     * Main: asks [due] of the session's store now and then every 60 s, off the main thread (a new
     * reading of the free space is a `statfs`), and passes the message to [show] once on the
     * caller's thread; returns at once when the session has no swap store.
     */
    suspend fun watch(show: (String) -> Unit) {
        while (true) {
            val store = EngineConfig.swapStore as? SlabSwapStore ?: return
            withContext(Dispatchers.IO) { due(store) }?.let {
                show(it)
                return
            }
            delay(SlabSwapStore.CAP_REFRESH_MS)
        }
    }

    /** Tests: forgets the session it was shown for. */
    internal fun reset() {
        shownFor = null
    }
}
