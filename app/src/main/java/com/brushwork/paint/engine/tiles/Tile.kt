package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineConfig

/** v1.10 (F1, design 2.0): the side of a tile in pixels; the grid starts at document (0, 0). */
const val TILE_SIZE = 256

/** v1.10 (F1, design 2.0 "Tile states"): where a [Tile]'s pixels are while its layer is parked. */
enum class TileState { RESIDENT, PACKED, SWAPPED }

/**
 * v1.10 (F1, design 2.0 and 3.3): one 256 x 256 (edge: smaller) piece of a [TiledPixels]. Pixels
 * are immutable once shared (refs > 1). A uniform opaque tile is [solid]: it shares one immutable
 * 256 x 256 bitmap per color with every other tile of that color ([SolidTiles]).
 *
 * [refs] counts the holders of the tile (stores and undo maps); a tile is freed when the last
 * holder releases it and no pin holds it ([TileOps.release]).
 *
 * Threading rules (frozen, I29, section 2.7):
 * (1) The worker never frees, recycles or changes [state]. It only produces the packed bytes or the
 *     slab slot of a tile it holds by a lease, or an inflated bitmap, and posts them to main.
 * (2) Main commits RESIDENT -> PACKED or SWAPPED in that posted task, only when pins == 0, refs is
 *     unchanged since the lease was taken and lastUse < currentFrameEpoch; otherwise it discards
 *     the packed copy and frees the slot.
 * (3) Bitmap.recycle() and ledger.sub happen only on main.
 * (4) An off-main reader holds a lease taken on main (snapshot(), which is refs++, or a pin) and
 *     releases it with a post.
 * (5) refs, pins and lastUse are read and written only on main.
 */
class Tile internal constructor(val w: Int, val h: Int) {
    /** Guards [bitmap], [packed] and [slot] against a concurrent [bitmapForRead]. */
    internal val lock = Any()

    /** RESIDENT: the tile's own bitmap, or the shared [SolidTiles] bitmap of a [solid] tile. */
    @Volatile internal var bitmap: Bitmap? = null

    /** PACKED: the pixels as Deflate level 1 bytes (nowrap). */
    @Volatile internal var packed: ByteArray? = null

    /** SWAPPED: where the packed bytes are in the swap file. */
    @Volatile internal var slot: SwapSlot? = null

    /** Main only: the holders of this tile (stores and undo maps). */
    internal var refs = 0

    /** Main only; > 0 forbids park, swap-out and recycle. */
    internal var pins = 0

    /** Main only; the ResidencyPolicy frame epoch of the last read. */
    internal var lastUse = 0L

    /** Set once by [TileOps.solidTile]: the color of a tile sharing a [SolidTiles] bitmap. */
    internal var solid: Int? = null

    /** The color of every pixel of a uniform tile sharing a [SolidTiles] bitmap (as `Bitmap.getPixel` gives it); else null. */
    val solidColor: Int? get() = solid

    /** Where the pixels are now. */
    val state: TileState
        get() = when {
            bitmap != null -> TileState.RESIDENT
            slot != null -> TileState.SWAPPED
            packed != null -> TileState.PACKED
            else -> TileState.RESIDENT
        }

    /** Native bytes of the pixels when resident (a solid tile shares its bitmap, so it holds none of its own). */
    val bytes: Int get() = w * h * 4

    /**
     * Off main, under a lease: the resident bitmap, or an inflated copy in the caller's scratch (the
     * tile stays PACKED or SWAPPED; it is never made resident by this call). Never called on main
     * during a frame (P4). Like every tile bitmap, an inflated or read copy carries no density.
     */
    fun bitmapForRead(): Bitmap = synchronized(lock) {
        bitmap ?: packed?.let { TileOps.inflate(it, w, h) }
            ?: slot?.let { EngineConfig.swapStore.read(this).apply { density = Bitmap.DENSITY_NONE } }
            ?: error("tile ${w}x$h holds no pixels")
    }

    companion object {
        /** A RESIDENT tile of [bitmap], which it takes ownership of (counted under TILES; no holder yet). */
        fun of(bitmap: Bitmap): Tile = TileOps.tileOf(bitmap)

        /** A uniform tile of [color], sharing `SolidTiles.of(color)` while fewer than 256 colors are shared (else its own bitmap). */
        fun solid(color: Int, w: Int, h: Int): Tile = TileOps.solidTile(color, w, h)
    }
}
