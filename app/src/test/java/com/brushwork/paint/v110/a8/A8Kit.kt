package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.engine.TileHolder
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.PackedPool
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.SolidTiles
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Helpers of the A8 tests (v1.10, design 2.0, 2.6, 2.7): draining the `tile-swapper` and the main
 * looper it posts to, the TILES ledger, a [ResidencyPolicy] over a bare document ([FakeHost]),
 * the passing of time on the main looper, and undo steps that hold tiles ([TileStep]).
 */
internal object A8Kit {
    /**
     * Runs the main looper until every [TileWorker] job ran its main part (the worker posts each
     * result there), failing after [timeoutMs] of wall time.
     */
    fun drain(timeoutMs: Long = 10_000L) {
        val looper = shadowOf(Looper.getMainLooper())
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            looper.idle()
            if (TileWorker.isIdle) return
            if (System.currentTimeMillis() > until) fail("the tile-swapper still has ${TileWorker.pendingJobs} jobs after $timeoutMs ms")
            Thread.sleep(1)
        }
    }

    /** Runs the main looper's tasks due in the next [ms] (the policy's clock is the looper's uptime). */
    fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** Runs the main looper's tasks due now (the end-of-task activation, posted results). */
    fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The bytes the ledger counts under TILES now. */
    fun tiles(): Long = EngineConfig.budget.ledger.of(LedgerCategory.TILES)

    /**
     * The engine state every test of the fork shares, as it was when made (in `@Before`): the TILES
     * ledger, the [SolidTiles] colors and the [PackedPool] bytes. [assertBack] drains the worker,
     * lets every store of the test's documents go (`Layer.recycleBitmaps`) and asserts all three
     * are back, so no test leaves tiles to the next one.
     */
    class Baseline {
        private val tiles = tiles()
        private val colors = SolidTiles.colors()
        private val pool = PackedPool.heldBytes

        fun assertBack(docs: List<Document>) {
            drain()
            for (d in docs) for (l in d.layers) l.recycleBitmaps()
            assertEquals("the TILES ledger is back", tiles, tiles())
            assertEquals("the solid colors are back", colors, SolidTiles.colors())
            assertEquals("the packed pool is back", pool, PackedPool.heldBytes)
        }
    }

    /**
     * The policy's view of an editor: a document, a busy flag, the activation hold, a history.
     * [log] records each change of the hold with the state of its layer at that moment
     * ("hold PARKED", "release FLAT").
     */
    class FakeHost(override val document: Document, override val history: UndoManager? = null) : ResidencyPolicy.Host {
        override var busy = false
        val log = ArrayList<String>()
        override var activating: Layer? = null
            set(value) {
                val old = field
                field = value
                log += (if (value != null) "hold " else "release ") + (value ?: old)?.residency
            }
    }

    /**
     * A [ResidencyPolicy] over [doc], installed as `EngineConfig.residency` (the test's
     * `EngineRule` puts the old one back), with [tileRam] as P3's budget; started (its own 500 ms
     * tick) only with [start] (a started policy is stopped by [stop] in the test's tear-down).
     */
    fun policy(doc: Document, history: UndoManager? = null, tileRam: () -> Long = { Long.MAX_VALUE }, start: Boolean = false): Pair<ResidencyPolicy, FakeHost> {
        val host = FakeHost(doc, history)
        val p = ResidencyPolicy(host, tileRam)
        ResidencyPolicy.install(p, start)
        return p to host
    }

    /** A document of [n] noisy 512 x 300 layers (every odd one masked), each bitmap kept in [made]. */
    fun doc(n: Int, made: MutableList<Bitmap>, seed: Long = 70L): Document {
        val d = Document("a8", "a8", 512, 300)
        repeat(n) { i ->
            d.layers += Layer(d.newLayerId(), "Layer $i", F1Pixels.mixed(512, 300, seed + i).also { made += it }).apply {
                if (i % 2 == 1) mask = F1Pixels.mixedMask(512, 300, seed + 100 + i).also { made += it }
            }
        }
        return d
    }

    /** Every pixel of [layer]'s content and mask, without materializing it. */
    fun pixelsOf(layer: Layer): Pair<IntArray, IntArray?> = storePixels(layer.pixels) to layer.maskPixels?.let { storePixels(it) }

    private fun storePixels(p: com.brushwork.paint.engine.tiles.PixelStore): IntArray {
        val b = com.brushwork.paint.engine.BitmapUtils.createLayerBitmap(p.width, p.height)
        try {
            com.brushwork.paint.engine.tiles.TileOps.readRegion(p, android.graphics.Rect(0, 0, p.width, p.height), b)
            return F1Pixels.pixels(b)
        } finally {
            b.recycle()
        }
    }

    /** The tiles of [layer]'s parked content and mask. */
    fun tilesOf(layer: Layer): List<Tile> =
        listOfNotNull(layer.pixels as? TiledPixels, layer.maskPixels as? TiledPixels).flatMap { s -> s.tiles.filterNotNull() }

    /** An undo step holding [tiles] of [layer] by reference (refs + 1 each), declaring [bytes]. */
    class TileStep(private val layer: Layer?, private val tiles: List<Tile>, private val bytes: Long) : UndoAction, TileHolder {
        init {
            for (t in tiles) t.refs++
        }

        override val label: String get() = "tiles"
        override val byteSize: Long get() = bytes
        override val tileLayer: Layer? get() = layer
        override fun forEachTile(block: (Tile) -> Unit) = tiles.forEach(block)
        override fun undo(c: EditorController) = Unit
        override fun redo(c: EditorController) = Unit
        override fun dispose() {
            for (t in tiles) com.brushwork.paint.engine.tiles.TileOps.release(t)
        }
    }
}
