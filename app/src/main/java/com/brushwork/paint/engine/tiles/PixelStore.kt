package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File

/**
 * v1.10 (F1, design 2.0, 2.2 and 3.3): where a layer's (or a mask's) pixels are. `Layer.pixels`
 * and `Layer.maskPixels` hold one; `Layer.bitmap` is the facade over it. In Classic every painted
 * layer is [FlatPixels], so the app behaves as v1.9.
 */
sealed class PixelStore {
    abstract val width: Int
    abstract val height: Int

    /** Native bytes held now; never materializes. */
    abstract fun residentBytes(): Long

    /** Flat = L, Blank = 0, Tiled = occupied tiles' bytes (resident or not); what undo counts. */
    abstract fun logicalBytes(): Long

    /** A never-painted store ([BlankPixels]). */
    abstract fun isBlank(): Boolean

    /** X3: no visible pixel inside [clip] (document px), known without reading pixels; Flat: false. */
    abstract fun isEmptyIn(clip: Rect): Boolean

    /** No visible pixel, without materializing: Blank true; Tiled: no occupied tile; Flat: v1.9's contentBounds scan. */
    abstract fun isEmpty(): Boolean
}

/** v1.10 (F1): the v1.9 store, one full-size bitmap (FLAT). */
class FlatPixels(val bitmap: Bitmap) : PixelStore() {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun residentBytes(): Long = width.toLong() * height * 4
    override fun logicalBytes(): Long = width.toLong() * height * 4
    override fun isBlank(): Boolean = false
    override fun isEmptyIn(clip: Rect): Boolean = false
    override fun isEmpty(): Boolean = TileOps.isTransparent(bitmap)
}

/** v1.10 (F1): a never-painted store (BLANK): transparent, 0 bytes; the first write materializes it. */
class BlankPixels(override val width: Int, override val height: Int) : PixelStore() {
    override fun residentBytes(): Long = 0L
    override fun logicalBytes(): Long = 0L
    override fun isBlank(): Boolean = true
    override fun isEmptyIn(clip: Rect): Boolean = true
    override fun isEmpty(): Boolean = true
}

/** v1.10 (F1): a folder's store (FOLDER): 1 x 1, no pixels, never drawn; the facade shows `Layer.FOLDER_BITMAP`. */
object FolderPixels : PixelStore() {
    override val width: Int get() = 1
    override val height: Int get() = 1
    override fun residentBytes(): Long = 0L
    override fun logicalBytes(): Long = 0L
    override fun isBlank(): Boolean = false
    override fun isEmptyIn(clip: Rect): Boolean = true
    override fun isEmpty(): Boolean = true
}

/**
 * v1.10 (F1): a parked store (PARKED): [cols] x [rows] tiles of [TILE_SIZE] px, row by row, null
 * where transparent. EVICTED when [evictedTo] is set: no tiles; the first read reloads the saved
 * file (A11). Copy on write: [snapshot] and [withTiles] make new stores sharing the tiles
 * (refs++); [replaceTiles] changes this one in place (undo on a parked layer). Main thread, except
 * reads under a lease (the `Tile` threading rules).
 */
class TiledPixels internal constructor(
    override val width: Int,
    override val height: Int,
    /** Row by row; null = transparent. */
    internal val tiles: Array<Tile?>,
    /** EVICTED: no tiles; reload from the file. */
    val evictedTo: SavedFileRef? = null,
) : PixelStore() {
    val cols: Int = (width + TILE_SIZE - 1) / TILE_SIZE
    val rows: Int = (height + TILE_SIZE - 1) / TILE_SIZE

    init {
        require(width > 0 && height > 0) { "a tiled store is at least 1 x 1 (got ${width}x$height)" }
        require(tiles.size == cols * rows) { "${tiles.size} tiles for a ${cols}x$rows grid" }
    }

    fun tileAt(col: Int, row: Int): Tile? = tiles[row * cols + col]

    /** The tiles that are not transparent. */
    fun occupied(): Int = tiles.count { it != null }

    /** Sets [out] to the union of the occupied tiles' rects; false (and [out] empty) when there is none. */
    fun occupiedBounds(out: Rect): Boolean = TileOps.occupiedBounds(this, out)

    /** X1: draws the tiles inside [clip] (document px) as `drawBitmap(full, 0, 0, paint)` would, bit for bit, on an integer-translate canvas. */
    fun drawTo(canvas: Canvas, paint: Paint?, clip: Rect) = TileOps.drawTo(this, canvas, paint, clip)

    /** Section 2.5: the mask through [maskPaint] (DST_IN): a null tile draws a clearing rect, a solid-white tile is skipped. */
    fun drawMaskTo(canvas: Canvas, maskPaint: Paint, clip: Rect) = TileOps.drawMaskTo(this, canvas, maskPaint, clip)

    /** Copies the pixels of [src] (document px) into [dst] at ([dx], [dy]) exactly (transparent outside the store). */
    fun copyRegion(src: Rect, dst: Bitmap, dx: Int, dy: Int) = TileOps.copyRegion(this, src, dst, dx, dy)

    /** A new full-size bitmap with these pixels (admitNative(LAYER) first). The store is unchanged. */
    fun materialize(): Bitmap = TileOps.materialize(this)

    /** Copy on write: a store sharing every tile (refs++ per tile). */
    fun snapshot(): TiledPixels = TileOps.snapshot(this)

    /** Copy on write: a new store with [changes] (tile index to tile, null = transparent) over these tiles; every tile it holds gets refs++. */
    fun withTiles(changes: Map<Int, Tile?>): TiledPixels = TileOps.withTiles(this, changes)

    /**
     * Main thread, undo on a parked layer (section 2.6): puts [changes] in place and returns the
     * tiles they replaced. References move with the tiles (no refs change): the caller's hold on
     * each new tile passes to this store, this store's hold on each replaced tile to the result.
     */
    internal fun replaceTiles(changes: Map<Int, Tile?>): Map<Int, Tile?> = TileOps.replaceTiles(this, changes)

    override fun residentBytes(): Long = TileOps.residentBytes(this)
    override fun logicalBytes(): Long = TileOps.logicalBytes(this)
    override fun isBlank(): Boolean = false
    override fun isEmptyIn(clip: Rect): Boolean = TileOps.isEmptyIn(this, clip)
    override fun isEmpty(): Boolean = evictedTo == null && occupied() == 0

    companion object {
        /**
         * Cuts [b] into tiles (each a copy; [b] is unchanged and still the caller's). With
         * [detectBlank] a transparent tile is stored as null and a uniform opaque one as
         * [Tile.solid]; without it every tile is an ordinary copy.
         */
        fun fromBitmap(b: Bitmap, detectBlank: Boolean = true): TiledPixels = TileOps.cut(b, detectBlank)
    }
}

/** v1.10 (F1): where an EVICTED store's pixels are: [file] in [projectDir], saved at [contentVersion]. */
data class SavedFileRef(val projectDir: File, val file: String, val contentVersion: Long)
