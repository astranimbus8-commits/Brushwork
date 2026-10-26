package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.live.WaitingTiles
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.FakeSwapStore
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.util.Random
import java.util.concurrent.ExecutorService

/**
 * v1.10 A9 step 2: the stacks of the waiting-tile timings (`FullRerender500TimingTest`,
 * `FirstCompositeEvictedTimingTest`). A "phone layer" is scaled to the tests' 512 x 512 canvas
 * with the design's 35 % occupancy (2.5 "Frame cost"): each layer paints a few shapes into one or
 * two of its four tiles, so the parked stack holds about 1.4 tiles per layer.
 */
object WaitingTimingKit {
    const val SIDE = 512

    /**
     * [n] sparse layers over an opaque backdrop (layer 0), each parked ([ChaosResidency.park]) as soon
     * as it is painted and its flat bitmap recycled, so the stack never holds more than one flat layer.
     */
    fun stack(n: Int, seed: Long): Document {
        val chaos = ChaosResidency()
        val d = A9Docs.track(Document("stack$n", "stack$n", SIDE, SIDE))
        val r = Random(seed)
        d.layers += parked(chaos, Layer(d.newLayerId(), "Backdrop", A9Docs.backdrop(seed, SIDE, SIDE)))
        val p = Paint().apply { isAntiAlias = true }
        repeat(n - 1) { i ->
            val b = BitmapUtils.createLayerBitmap(SIDE, SIDE)
            val c = Canvas(b)
            val tiles = if (i % 3 == 0) 2 else 1
            repeat(tiles) {
                val tx = r.nextInt(2) * 256
                val ty = r.nextInt(2) * 256
                repeat(3) {
                    p.color = Color.argb(80 + r.nextInt(176), r.nextInt(256), r.nextInt(256), r.nextInt(256))
                    c.drawCircle(tx + 20f + r.nextInt(216), ty + 20f + r.nextInt(216), 10f + r.nextInt(50), p)
                }
            }
            d.layers += parked(chaos, Layer(d.newLayerId(), "L$i", b).apply { opacity = 0.95f })
        }
        return d
    }

    private fun parked(chaos: ChaosResidency, l: Layer): Layer {
        val flat = l.bitmap
        assertTrue(chaos.park(l))
        flat.recycle()
        return l
    }

    /** The parked stores of [d]. */
    fun stores(d: Document): List<TiledPixels> = d.layers.mapNotNull { it.pixels as? TiledPixels }

    /** Swaps every ordinary tile of [stores] out to [swap]; returns how many. */
    fun swapOut(stores: List<TiledPixels>, swap: FakeSwapStore): Int {
        var n = 0
        for (s in stores) for (row in 0 until s.rows) for (col in 0 until s.cols) {
            val t = s.tileAt(col, row) ?: continue
            if (t.solidColor != null) continue
            A9Docs.swapOut(swap, t)
            n++
        }
        return n
    }

    /** Packs every ordinary tile of [stores] (Deflate level 1, as a reload leaves them); returns how many. */
    fun packAll(stores: List<TiledPixels>): Int {
        var n = 0
        for (s in stores) for (row in 0 until s.rows) for (col in 0 until s.cols) {
            val t = s.tileAt(col, row) ?: continue
            if (t.solidColor != null) continue
            val b = t.bitmap ?: continue
            val packed = TileOps.pack(b)
            synchronized(t.lock) {
                t.packed = packed
                t.bitmap = null
            }
            // As a real pack: the bitmap's bytes leave the ledger (else they stay there for every later test).
            EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
            n++
        }
        return n
    }

    internal fun install(tiles: DisplayTiles, worker: ExecutorService): WaitingTiles =
        WaitingTiles(tiles, worker, Handler(Looper.getMainLooper())).also { tiles.waiting = it }

    /**
     * Renders every dirty tile progressively as the view does (budgeted updates of [frameNanos],
     * the main looper run between them) until none is dirty or waiting; returns how many updates it
     * took. Nothing here is timed (the callers time it); the 120 s guard reads the wall clock.
     */
    fun renderAll(tiles: DisplayTiles, compositor: Compositor, frameNanos: Long = 8_000_000L): Int {
        val until = System.currentTimeMillis() + 120_000L
        var updates = 0
        while (tiles.hasDirty || tiles.hasWaiting) {
            tiles.updateBudgeted(compositor, null, frameNanos)
            updates++
            shadowOf(Looper.getMainLooper()).idle()
            if (!tiles.hasDirty && !tiles.hasWaiting) break
            check(System.currentTimeMillis() < until) { "the tiles never completed" }
            Thread.yield()
        }
        return updates
    }

    /** Tile [index] of [tiles] drawn alone onto a new bitmap of the document's size. */
    fun shown(tiles: DisplayTiles): Bitmap {
        val out = BitmapUtils.createLayerBitmap(tiles.docWidth, tiles.docHeight)
        val c = Canvas(out)
        for (i in 0 until tiles.tileCount) tiles.drawTile(c, i, smooth = false)
        return out
    }
}
