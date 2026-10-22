package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.recycleUnlessShared
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/**
 * v1.10 (F1 reference implementation, design 3.3; A8 owns and tunes): the bodies behind [Tile],
 * [TiledPixels] and [PixelPin], which only delegate here. Correct, not tuned: tiles are cut and
 * drawn on the calling thread, and every copy is exact (SRC at integer offsets).
 *
 * References: a store or an undo map holding a tile counts one [Tile.refs]; [release] frees the
 * tile when the last holder lets go and no pin holds it. Main thread throughout (the `Tile`
 * threading rules), except [inflate], [pack] and the reads of a leased tile.
 */
internal object TileOps {
    /** Copies pixels exactly: the premultiplied values, no blending, no filtering. */
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** Clears to transparent. */
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    private const val WHITE = -1

    /** The pixels a `drawBand` keeps around the clip, so bilinear filtering at its edges sees the same neighbours (X2). */
    private const val BAND_APRON = 2

    // ---------------------------------------------------------------- tiles

    fun tileOf(bitmap: Bitmap): Tile {
        require(bitmap.width in 1..TILE_SIZE && bitmap.height in 1..TILE_SIZE) { "a tile is at most $TILE_SIZE px (got ${bitmap.width}x${bitmap.height})" }
        return Tile(bitmap.width, bitmap.height).also {
            it.bitmap = bitmap
            EngineConfig.budget.ledger.add(LedgerCategory.TILES, it.bytes.toLong())
        }
    }

    fun solidTile(color: Int, w: Int, h: Int): Tile {
        require(w in 1..TILE_SIZE && h in 1..TILE_SIZE) { "a tile is at most $TILE_SIZE px (got ${w}x$h)" }
        val shared = SolidTiles.of(color)
            ?: return tileOf(BitmapUtils.createLayerBitmap(w, h).apply { eraseColor(color) })
        return Tile(w, h).also {
            it.bitmap = shared
            it.solid = color
        }
    }

    /** One fewer holder of [t]; frees it when none is left and no pin holds it. Main only. */
    fun release(t: Tile) {
        t.refs--
        if (t.refs <= 0 && t.pins == 0) free(t)
    }

    /** Recycles [t]'s own bitmap (or lets go of its [SolidTiles] color) and its packed bytes and slot. Main only. */
    private fun free(t: Tile) {
        synchronized(t.lock) {
            val b = t.bitmap
            val color = t.solid
            if (color != null) {
                if (b != null) SolidTiles.release(color)
            } else if (b != null && !b.isRecycled) {
                b.recycle()
                EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
            }
            t.bitmap = null
            t.solid = null
            t.packed = null
            t.slot?.let { EngineConfig.swapStore.free(it) }
            t.slot = null
        }
    }

    /** Makes [t] RESIDENT (inflating its packed bytes or reading its slot). Main only, outside a frame. */
    fun makeResident(t: Tile) {
        if (t.bitmap != null) return
        val b = t.bitmapForRead()
        synchronized(t.lock) {
            t.bitmap = b
            t.packed = null
            t.slot?.let { EngineConfig.swapStore.free(it) }
            t.slot = null
        }
        EngineConfig.budget.ledger.add(LedgerCategory.TILES, t.bytes.toLong())
    }

    /** Runs [block] with [t]'s pixels: its bitmap, or an inflated copy recycled afterwards. */
    private inline fun withPixels(t: Tile, block: (Bitmap) -> Unit) {
        val b = t.bitmap
        if (b != null) {
            block(b)
            return
        }
        val read = t.bitmapForRead()
        try {
            block(read)
        } finally {
            if (read !== t.bitmap) read.recycle()
        }
    }

    /** Draws [t] with its top left at ([x], [y]) (a shared solid bitmap is clipped to the tile's own size). */
    private fun drawTile(c: Canvas, t: Tile, x: Int, y: Int, paint: Paint?) {
        withPixels(t) { b ->
            if (b.width == t.w && b.height == t.h) {
                c.drawBitmap(b, x.toFloat(), y.toFloat(), paint)
            } else {
                val save = c.save()
                c.clipRect(x, y, x + t.w, y + t.h)
                c.drawBitmap(b, x.toFloat(), y.toFloat(), paint)
                c.restoreToCount(save)
            }
        }
    }

    // ---------------------------------------------------------------- packing (Deflate level 1, nowrap)

    /** The raw premultiplied pixels of [b] as Deflate level 1 bytes (nowrap). Any thread. */
    fun pack(b: Bitmap): ByteArray {
        val raw = ByteBuffer.allocate(b.byteCount)
        b.copyPixelsToBuffer(raw)
        val def = Deflater(1, true)
        try {
            def.setInput(raw.array())
            def.finish()
            val out = ByteArrayOutputStream(b.byteCount / 4 + 64)
            val buf = ByteArray(64 * 1024)
            while (!def.finished()) {
                val n = def.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            def.end()
        }
    }

    /** A new [w] x [h] bitmap from bytes [pack] made. Any thread. */
    fun inflate(packed: ByteArray, w: Int, h: Int): Bitmap {
        val raw = ByteArray(w * h * 4)
        val inf = Inflater(true)
        try {
            // nowrap streams may need one byte past the end (java.util.zip.Inflater).
            inf.setInput(packed.copyOf(packed.size + 1))
            var n = 0
            while (n < raw.size) {
                val k = inf.inflate(raw, n, raw.size - n)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            check(n == raw.size) { "a packed ${w}x$h tile holds $n of ${raw.size} bytes" }
        } finally {
            inf.end()
        }
        return BitmapUtils.createLayerBitmap(w, h).apply { copyPixelsFromBuffer(ByteBuffer.wrap(raw)) }
    }

    // ---------------------------------------------------------------- stores

    /** The width and height of tile slot ([col], [row]) of [s]. */
    private fun slotWidth(s: TiledPixels, col: Int): Int = min(TILE_SIZE, s.width - col * TILE_SIZE)
    private fun slotHeight(s: TiledPixels, row: Int): Int = min(TILE_SIZE, s.height - row * TILE_SIZE)

    private fun checkFits(s: TiledPixels, i: Int, t: Tile) {
        require(i in s.tiles.indices) { "tile $i is outside a ${s.cols}x${s.rows} grid" }
        val w = slotWidth(s, i % s.cols)
        val h = slotHeight(s, i / s.cols)
        require(t.w == w && t.h == h) { "a ${t.w}x${t.h} tile in a ${w}x$h slot ($i)" }
    }

    fun cut(b: Bitmap, detectBlank: Boolean): TiledPixels {
        val w = b.width
        val h = b.height
        require(w > 0 && h > 0) { "nothing to cut (${w}x$h)" }
        val cols = (w + TILE_SIZE - 1) / TILE_SIZE
        val rows = (h + TILE_SIZE - 1) / TILE_SIZE
        val tiles = arrayOfNulls<Tile>(cols * rows)
        val px = if (detectBlank) IntArray(TILE_SIZE * TILE_SIZE) else null
        try {
            for (row in 0 until rows) for (col in 0 until cols) {
                val x = col * TILE_SIZE
                val y = row * TILE_SIZE
                val tw = min(TILE_SIZE, w - x)
                val th = min(TILE_SIZE, h - y)
                val tile = if (px == null) {
                    copyTile(b, x, y, tw, th)
                } else {
                    b.getPixels(px, 0, tw, x, y, tw, th)
                    when (classify(px, tw * th)) {
                        BLANK -> null
                        UNIFORM_OPAQUE -> solidTile(px[0], tw, th)
                        else -> copyTile(b, x, y, tw, th)
                    }
                }
                if (tile != null) {
                    tile.refs = 1
                    tiles[row * cols + col] = tile
                }
            }
        } catch (e: Throwable) {
            for (t in tiles) if (t != null) release(t)
            throw e
        }
        return TiledPixels(w, h, tiles)
    }

    private const val BLANK = -1
    private const val OTHER = 0
    private const val UNIFORM_OPAQUE = 1

    /** [BLANK]: every alpha is 0; [UNIFORM_OPAQUE]: one opaque color; else [OTHER]. [px] as `getPixels` gives it. */
    private fun classify(px: IntArray, n: Int): Int {
        val first = px[0]
        var visible = false
        var uniform = true
        for (i in 0 until n) {
            val p = px[i]
            if (p ushr 24 != 0) visible = true
            if (p != first) uniform = false
            if (visible && !uniform) return OTHER
        }
        if (!visible) return BLANK
        return if (uniform && first ushr 24 == 0xFF) UNIFORM_OPAQUE else OTHER
    }

    /** An exact copy of [tw] x [th] pixels of [b] at ([x], [y]) as an ordinary tile. */
    private fun copyTile(b: Bitmap, x: Int, y: Int, tw: Int, th: Int): Tile {
        val out = BitmapUtils.createLayerBitmap(tw, th)
        try {
            Canvas(out).drawBitmap(b, -x.toFloat(), -y.toFloat(), copyPaint)
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
        return tileOf(out)
    }

    fun materialize(s: TiledPixels): Bitmap {
        check(s.evictedTo == null) { "an evicted store reloads from its saved file (A11)" }
        val bytes = s.width.toLong() * s.height * 4
        val budget = EngineConfig.budget
        if (!budget.admitNative(bytes, NativePurpose.LAYER)) throw OutOfMemoryError("No room for a ${s.width}x${s.height} layer")
        // F1: a flat bitmap is admitted but not kept in the ledger (v1.9 code recycles flat bitmaps anywhere).
        budget.release(bytes, NativePurpose.LAYER)
        val out = BitmapUtils.createLayerBitmap(s.width, s.height)
        try {
            val c = Canvas(out)
            for (i in s.tiles.indices) {
                val t = s.tiles[i] ?: continue
                drawTile(c, t, (i % s.cols) * TILE_SIZE, (i / s.cols) * TILE_SIZE, copyPaint)
            }
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
        return out
    }

    /** The first and last tile column and row inside [clip] (clamped to [s]), or null when none is. */
    private fun range(s: TiledPixels, clip: Rect): IntArray? {
        val l = max(clip.left, 0)
        val t = max(clip.top, 0)
        val r = min(clip.right, s.width)
        val b = min(clip.bottom, s.height)
        if (l >= r || t >= b) return null
        return intArrayOf(l / TILE_SIZE, t / TILE_SIZE, (r - 1) / TILE_SIZE, (b - 1) / TILE_SIZE)
    }

    fun drawTo(s: TiledPixels, c: Canvas, paint: Paint?, clip: Rect) {
        val g = range(s, clip) ?: return
        for (row in g[1]..g[3]) for (col in g[0]..g[2]) {
            val t = s.tiles[row * s.cols + col] ?: continue
            drawTile(c, t, col * TILE_SIZE, row * TILE_SIZE, paint)
        }
    }

    /**
     * Section 2.5, API 26-28: draws every null tile inside [clip] as a transparent rect with
     * [paint] (where a transparent source clears the destination, `BlendModes.clearsOnTransparent`),
     * as the flat draw of its transparent pixels would.
     */
    fun clearNullTiles(s: TiledPixels, c: Canvas, paint: Paint, clip: Rect) {
        val g = range(s, clip) ?: return
        val clear = Paint(paint).apply { color = 0 }
        for (row in g[1]..g[3]) for (col in g[0]..g[2]) {
            if (s.tiles[row * s.cols + col] != null) continue
            val x = col * TILE_SIZE
            val y = row * TILE_SIZE
            c.drawRect(x.toFloat(), y.toFloat(), (x + slotWidth(s, col)).toFloat(), (y + slotHeight(s, row)).toFloat(), clear)
        }
    }

    fun drawMaskTo(s: TiledPixels, c: Canvas, maskPaint: Paint, clip: Rect) {
        val g = range(s, clip) ?: return
        var clear: Paint? = null
        for (row in g[1]..g[3]) for (col in g[0]..g[2]) {
            val x = col * TILE_SIZE
            val y = row * TILE_SIZE
            val t = s.tiles[row * s.cols + col]
            when {
                // A transparent mask hides: its luminance gives alpha 0, which DST_IN turns into a clear.
                t == null -> {
                    val p = clear ?: Paint(maskPaint).apply { color = 0 }.also { clear = it }
                    c.drawRect(x.toFloat(), y.toFloat(), (x + slotWidth(s, col)).toFloat(), (y + slotHeight(s, row)).toFloat(), p)
                }
                // White gives alpha 255: DST_IN leaves the destination as it is.
                t.solidColor == WHITE -> Unit
                else -> drawTile(c, t, x, y, maskPaint)
            }
        }
    }

    fun copyRegion(s: TiledPixels, src: Rect, dst: Bitmap, dx: Int, dy: Int) {
        val c = Canvas(dst)
        c.clipRect(dx, dy, dx + src.width(), dy + src.height())
        c.drawPaint(clearPaint)
        c.translate((dx - src.left).toFloat(), (dy - src.top).toFloat())
        drawTo(s, c, copyPaint, src)
    }

    fun isEmptyIn(s: TiledPixels, clip: Rect): Boolean {
        if (s.evictedTo != null) return false
        val g = range(s, clip) ?: return true
        for (row in g[1]..g[3]) for (col in g[0]..g[2]) if (s.tiles[row * s.cols + col] != null) return false
        return true
    }

    fun occupiedBounds(s: TiledPixels, out: Rect): Boolean {
        out.setEmpty()
        if (s.evictedTo != null) {
            out.set(0, 0, s.width, s.height)
            return true
        }
        for (i in s.tiles.indices) {
            if (s.tiles[i] == null) continue
            val col = i % s.cols
            val row = i / s.cols
            out.union(col * TILE_SIZE, row * TILE_SIZE, col * TILE_SIZE + slotWidth(s, col), row * TILE_SIZE + slotHeight(s, row))
        }
        return !out.isEmpty
    }

    fun residentBytes(s: TiledPixels): Long {
        var n = 0L
        for (t in s.tiles) if (t != null && t.solid == null && t.bitmap != null) n += t.bytes
        return n
    }

    fun logicalBytes(s: TiledPixels): Long {
        var n = 0L
        for (t in s.tiles) if (t != null) n += t.bytes
        return n
    }

    fun snapshot(s: TiledPixels): TiledPixels {
        val copy = s.tiles.copyOf()
        for (t in copy) if (t != null) t.refs++
        return TiledPixels(s.width, s.height, copy, s.evictedTo)
    }

    fun withTiles(s: TiledPixels, changes: Map<Int, Tile?>): TiledPixels {
        check(s.evictedTo == null) { "an evicted store is reloaded before it is edited (A11)" }
        val copy = s.tiles.copyOf()
        for ((i, t) in changes) {
            if (t != null) checkFits(s, i, t) else require(i in copy.indices) { "tile $i is outside a ${s.cols}x${s.rows} grid" }
            copy[i] = t
        }
        for (t in copy) if (t != null) t.refs++
        return TiledPixels(s.width, s.height, copy)
    }

    fun replaceTiles(s: TiledPixels, changes: Map<Int, Tile?>): Map<Int, Tile?> {
        check(s.evictedTo == null) { "an evicted store is reloaded before it is edited (A11)" }
        for ((i, t) in changes) if (t != null) checkFits(s, i, t) else require(i in s.tiles.indices) { "tile $i is outside a ${s.cols}x${s.rows} grid" }
        val replaced = HashMap<Int, Tile?>(changes.size * 2)
        for ((i, t) in changes) {
            replaced[i] = s.tiles[i]
            s.tiles[i] = t
        }
        return replaced
    }

    /** Lets go of every tile [s] holds (refs--), leaving it empty. Main only. */
    fun releaseStore(s: TiledPixels) {
        for (i in s.tiles.indices) {
            val t = s.tiles[i] ?: continue
            s.tiles[i] = null
            release(t)
        }
    }

    /** Lets go of [p]: a flat bitmap is recycled (never `Layer.FOLDER_BITMAP`), a tiled store's tiles are released. Main only. */
    fun dispose(p: PixelStore?) {
        when (p) {
            is FlatPixels -> p.bitmap.recycleUnlessShared()
            is TiledPixels -> releaseStore(p)
            else -> Unit
        }
    }

    /** v1.9's emptiness scan of a flat bitmap: no pixel with alpha above 0 (row by row, stops at the first). */
    fun isTransparent(b: Bitmap): Boolean {
        val w = b.width
        val row = IntArray(w)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) if (row[x] ushr 24 != 0) return false
        }
        return true
    }

    // ---------------------------------------------------------------- pins

    /** Pins [layer] and every tile of its content and mask (making each resident when [resident]); returns the pinned tiles. Main only. */
    fun pin(layer: Layer, resident: Boolean): List<Tile> {
        val held = ArrayList<Tile>()
        try {
            for (store in arrayOf(layer.pixels, layer.maskPixels)) {
                if (store !is TiledPixels) continue
                for (t in store.tiles) {
                    if (t == null) continue
                    t.pins++
                    held += t
                    if (resident) makeResident(t)
                }
            }
        } catch (e: Throwable) {
            unpinTiles(held)
            throw e
        }
        layer.pins++
        return held
    }

    /** Undoes [pin]. Main only. */
    fun unpin(layer: Layer, held: List<Tile>) {
        layer.pins--
        unpinTiles(held)
    }

    private fun unpinTiles(held: List<Tile>) {
        for (t in held) {
            t.pins--
            if (t.pins == 0 && t.refs <= 0) free(t)
        }
    }

    // ---------------------------------------------------------------- compositor and reads

    /** True when [c] maps document px onto its target at scale 1 by a whole-pixel translation (X1 holds). */
    @Suppress("DEPRECATION")
    fun isUnitTranslate(c: Canvas): Boolean {
        val m: Matrix = c.matrix
        if (m.isIdentity) return true
        val v = FloatArray(9)
        m.getValues(v)
        return v[Matrix.MSCALE_X] == 1f && v[Matrix.MSCALE_Y] == 1f && v[Matrix.MSKEW_X] == 0f && v[Matrix.MSKEW_Y] == 0f &&
            v[Matrix.MPERSP_0] == 0f && v[Matrix.MPERSP_1] == 0f && v[Matrix.MPERSP_2] == 1f &&
            v[Matrix.MTRANS_X] == Math.round(v[Matrix.MTRANS_X]).toFloat() && v[Matrix.MTRANS_Y] == Math.round(v[Matrix.MTRANS_Y]).toFloat()
    }

    /**
     * X2 fallback until A9's `BandAssembler`: copies the part of [s] under [c]'s clip (with a
     * [BAND_APRON] px apron) into a transient bitmap and draws it with [paint] at its place.
     */
    fun drawBand(s: TiledPixels, c: Canvas, paint: Paint?) {
        val r = Rect()
        if (!c.getClipBounds(r)) return
        r.inset(-BAND_APRON, -BAND_APRON)
        if (!r.intersect(0, 0, s.width, s.height)) return
        val bytes = r.width().toLong() * r.height() * 4
        val budget = EngineConfig.budget
        if (!budget.admitNative(bytes, NativePurpose.TRANSIENT)) throw OutOfMemoryError("No room for a ${r.width()}x${r.height()} band")
        try {
            val band = BitmapUtils.createLayerBitmap(r.width(), r.height())
            try {
                copyRegion(s, r, band, 0, 0)
                c.drawBitmap(band, r.left.toFloat(), r.top.toFloat(), paint)
            } finally {
                band.recycle()
            }
        } finally {
            budget.release(bytes, NativePurpose.TRANSIENT)
        }
    }

    /** The bounds of [c]'s clip in document px (rounded out), or null when the clip is empty. */
    fun clipOf(c: Canvas): Rect? {
        val r = Rect()
        return if (c.getClipBounds(r)) r else null
    }

    /** Copies [r] of [p] into [out] at (0, 0) exactly, transparent outside the store. Never materializes. */
    fun readRegion(p: PixelStore, r: Rect, out: Bitmap) {
        when (p) {
            is TiledPixels -> {
                check(p.evictedTo == null) { "an evicted store reloads from its saved file (A11)" }
                p.copyRegion(r, out, 0, 0)
            }
            is FlatPixels -> {
                val c = Canvas(out)
                c.clipRect(0, 0, r.width(), r.height())
                c.drawPaint(clearPaint)
                c.drawBitmap(p.bitmap, -r.left.toFloat(), -r.top.toFloat(), copyPaint)
            }
            else -> {
                val c = Canvas(out)
                c.clipRect(0, 0, r.width(), r.height())
                c.drawPaint(clearPaint)
            }
        }
    }

    /** The color at ([x], [y]) of [p] as `Bitmap.getPixel` gives it; 0 outside the store. Never materializes. */
    fun sample(p: PixelStore, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= p.width || y >= p.height) return 0
        return when (p) {
            is FlatPixels -> p.bitmap.getPixel(x, y)
            is TiledPixels -> {
                check(p.evictedTo == null) { "an evicted store reloads from its saved file (A11)" }
                val col = x / TILE_SIZE
                val row = y / TILE_SIZE
                val t = p.tileAt(col, row) ?: return 0
                var color = 0
                withPixels(t) { b -> color = b.getPixel(x - col * TILE_SIZE, y - row * TILE_SIZE) }
                color
            }
            else -> 0
        }
    }
}
