package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.LedgerCategory

/**
 * v1.10 (F1 reference, design 2.0 "Tile states"; A8 owns and tunes): one immutable 256 x 256
 * bitmap per color, shared by every uniform tile of that color ([Tile.solid]). An entry is
 * refcounted (one count per tile sharing it), counted under TILES in the ledger, and freed when
 * its last tile goes. At most [MAX_COLORS] colors are shared at once; beyond that [of] says null
 * and a uniform tile is an ordinary tile. Thread-safe; [release] runs on main (it recycles).
 */
internal object SolidTiles {
    /** The most colors shared at once. */
    const val MAX_COLORS = 256

    private class Entry(val bitmap: Bitmap) {
        var refs = 0
    }

    private val entries = HashMap<Int, Entry>()

    /** The shared bitmap of [color] (as `Bitmap.getPixel` gives it), with one more count; null when [MAX_COLORS] other colors are shared. */
    @Synchronized
    fun of(color: Int): Bitmap? {
        val e = entries[color] ?: run {
            if (entries.size >= MAX_COLORS) return null
            val b = Bitmap.createBitmap(IntArray(TILE_SIZE * TILE_SIZE) { color }, TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
            EngineConfig.budget.ledger.add(LedgerCategory.TILES, TILE_BYTES)
            Entry(b).also { entries[color] = it }
        }
        e.refs++
        return e.bitmap
    }

    /** One count fewer for [color]; at 0 the bitmap is recycled and the entry goes. Main only. */
    @Synchronized
    fun release(color: Int) {
        val e = entries[color] ?: return
        if (--e.refs > 0) return
        entries.remove(color)
        e.bitmap.recycle()
        EngineConfig.budget.ledger.sub(LedgerCategory.TILES, TILE_BYTES)
    }

    /** The colors shared now. */
    @Synchronized
    fun colors(): Int = entries.size

    /** The tiles sharing [color] now. */
    @Synchronized
    fun refs(color: Int): Int = entries[color]?.refs ?: 0

    private const val TILE_BYTES = TILE_SIZE.toLong() * TILE_SIZE * 4
}
