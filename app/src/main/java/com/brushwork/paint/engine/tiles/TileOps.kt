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
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/**
 * v1.10 (F1 reference implementation, design 3.3; A8 owns and tunes): the bodies behind [Tile],
 * [TiledPixels] and [PixelPin], which only delegate here. Every copy is exact (SRC at integer
 * offsets). F1c: a tile's bitmap carries no density ([Bitmap.DENSITY_NONE]), and a tile is cut
 * onto a canvas that carries none, so no draw scales a tile: a tile, and above all a shared
 * [SolidTiles] bitmap, can outlive the default density it was made under, which
 * `drawBitmap(b, x, y, paint)` scales by the ratio.
 *
 * References: a store or an undo map holding a tile counts one [Tile.refs]; [release] frees the
 * tile when the last holder lets go and no pin holds it. Main thread throughout (the `Tile`
 * threading rules), except [inflate], [pack], [cutPieces] and the reads of a
 * leased tile.
 *
 * v1.10 (A8 step 1, design 2.0 and 2.7, J8): tuned. [cut] reuses one canvas and one pixel array
 * for every tile; [pack] and [inflate] reuse their Deflater, Inflater and buffers per thread (the
 * worker packs every tile it swaps out); a solid tile lets go of its [SolidTiles] count however it
 * is freed (F1 review gap 2) and is never packed or swapped (it owns no pixels). The swap-out
 * commits of the `Tile.kt` rule (2) are [commitPacked] and [commitWritten]; the frame epoch of
 * that rule is [frameEpoch], and a tile drawn on main is marked used in it ([drawTo]).
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
        bitmap.density = Bitmap.DENSITY_NONE
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

    /**
     * Recycles [t]'s own bitmap (or lets go of its [SolidTiles] color) and its packed bytes and
     * slot. Main only. A8 (F1 review gap 2): a solid tile's color count goes whatever its state,
     * and a [free] of a freed tile does nothing ([Tile.solid] is cleared with the first).
     */
    private fun free(t: Tile) {
        synchronized(t.lock) {
            val b = t.bitmap
            val color = t.solid
            if (color != null) {
                SolidTiles.release(color)
            } else if (b != null && !b.isRecycled) {
                b.recycle()
                EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
            }
            t.bitmap = null
            t.solid = null
            t.packed?.let { PackedPool.release(it.size.toLong()) }
            t.packed = null
            t.slot?.let { EngineConfig.swapStore.free(it) }
            t.slot = null
        }
    }

    /** Makes [t] RESIDENT (inflating its packed bytes or reading its slot). Main only, outside a frame. */
    fun makeResident(t: Tile) {
        if (t.bitmap != null) return
        adoptRead(t, t.bitmapForRead())
    }

    /**
     * Main only: [b], a read of [t]'s packed bytes or slot (on main or by the worker under a
     * lease), becomes [t]'s bitmap: RESIDENT again, its packed bytes and slot let go. False (and
     * [b] recycled) when [t] is resident already or was freed meanwhile.
     */
    fun adoptRead(t: Tile, b: Bitmap): Boolean {
        val adopted = synchronized(t.lock) {
            if (t.bitmap != null || (t.packed == null && t.slot == null)) {
                false
            } else {
                b.density = Bitmap.DENSITY_NONE
                t.bitmap = b
                t.packed?.let { PackedPool.release(it.size.toLong()) }
                t.packed = null
                t.slot?.let { EngineConfig.swapStore.free(it) }
                t.slot = null
                true
            }
        }
        if (adopted) EngineConfig.budget.ledger.add(LedgerCategory.TILES, t.bytes.toLong()) else b.recycle()
        return adopted
    }

    // ---------------------------------------------------------------- swap-out commits (Tile.kt rule 2)

    /**
     * Main only: the frame epoch of the commit rule. [nextEpoch] starts a new one (the compositor
     * at each frame, and `ResidencyPolicy` at each idle tick); a tile drawn on main in the current
     * epoch ([Tile.lastUse] == [frameEpoch]) is not swapped out in it.
     */
    var frameEpoch = 1L
        private set

    /** Main only: a new frame epoch. */
    fun nextEpoch(): Long = ++frameEpoch

    /** True while the current thread is the main looper's (where [Tile.lastUse] may be written). */
    private fun onMain(): Boolean = android.os.Looper.getMainLooper()?.isCurrentThread == true

    /**
     * Main only: may [t], leased by the worker with [leasedRefs] references (its lease included),
     * leave RESIDENT now? Rule (2): no pin, the same references as at the lease, not used in this
     * frame epoch; never a solid tile (it owns no pixels).
     */
    private fun mayLeave(t: Tile, leasedRefs: Int): Boolean =
        t.pins == 0 && t.refs == leasedRefs && t.lastUse < frameEpoch && t.solid == null

    /**
     * Main, in the task the worker posted: [bytes] packed from [t] (leased with [leasedRefs]
     * references) become its pixels (RESIDENT -> PACKED) when the commit rule allows and
     * [PackedPool] admits them; otherwise they are dropped. Releases the lease either way.
     * True when [t] is PACKED now.
     */
    fun commitPacked(t: Tile, bytes: ByteArray, leasedRefs: Int): Boolean {
        val ok = t.bitmap != null && t.slot == null && mayLeave(t, leasedRefs) && PackedPool.tryAdmit(bytes.size.toLong())
        if (ok) {
            val b = synchronized(t.lock) {
                val b = t.bitmap
                t.packed = bytes
                t.bitmap = null
                b
            }
            if (b != null && !b.isRecycled) b.recycle()
            EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
        }
        release(t)
        return ok
    }

    /**
     * Main, in the task the worker posted after writing [t] (leased with [leasedRefs] references)
     * to [slot]: the slot becomes its only copy (RESIDENT or PACKED -> SWAPPED) when the commit
     * rule allows; otherwise the slot is freed and [t] stays as it was. A failed write ([slot]
     * null) changes nothing. Releases the lease either way. True when [t] is SWAPPED now.
     */
    fun commitWritten(t: Tile, slot: SwapSlot?, leasedRefs: Int): Boolean {
        if (slot == null) {
            release(t)
            return false
        }
        val ok = t.slot === slot && mayLeave(t, leasedRefs)
        if (ok) {
            val b = synchronized(t.lock) {
                val b = t.bitmap
                t.bitmap = null
                t.packed?.let { PackedPool.release(it.size.toLong()) }
                t.packed = null
                b
            }
            if (b != null) {
                if (!b.isRecycled) b.recycle()
                EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
            }
        } else {
            synchronized(t.lock) { if (t.slot === slot) t.slot = null }
            EngineConfig.swapStore.free(slot)
        }
        release(t)
        return ok
    }

    /**
     * Runs [block] with [t]'s pixels: its bitmap, or an inflated copy recycled afterwards. A read
     * on main marks [t] used in this frame epoch (the commit rule then keeps it resident).
     */
    private inline fun withPixels(t: Tile, block: (Bitmap) -> Unit) {
        if (onMain()) t.lastUse = frameEpoch
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

    /**
     * A8: what one thread reuses between packs and inflates (the worker packs every tile it swaps
     * out): its Deflater and Inflater (reset before each use) and the buffers of one full tile.
     * Each lives as long as its thread (the main thread and the `tile-swapper`, both for good).
     */
    private class Zip {
        val deflater = Deflater(1, true)
        val inflater = Inflater(true)
        val raw: ByteBuffer = ByteBuffer.allocate(TILE_SIZE * TILE_SIZE * 4)
        var out = ByteArray(TILE_SIZE * TILE_SIZE + 64)
        val one = ByteArray(1)
    }

    private val zip = object : ThreadLocal<Zip>() {
        override fun initialValue() = Zip()
    }

    /** The raw premultiplied pixels of [b] as Deflate level 1 bytes (nowrap). Any thread. */
    fun pack(b: Bitmap): ByteArray {
        val n = b.byteCount
        val z = zip.get()!!
        val raw = if (n <= z.raw.capacity()) z.raw.apply { clear() } else ByteBuffer.allocate(n)
        b.copyPixelsToBuffer(raw)
        val def = z.deflater
        def.reset()
        def.setInput(raw.array(), 0, n)
        def.finish()
        var out = z.out
        var len = 0
        while (!def.finished()) {
            if (len == out.size) out = out.copyOf(out.size * 2)
            len += def.deflate(out, len, out.size - len)
        }
        // Keeps a grown buffer up to two tiles' worth (noise packs larger than it was).
        if (out.size <= TILE_SIZE * TILE_SIZE * 8) z.out = out
        return out.copyOf(len)
    }

    /** A new [w] x [h] bitmap (no density, as every tile's) from bytes [pack] made. Any thread. */
    fun inflate(packed: ByteArray, w: Int, h: Int): Bitmap {
        val size = w * h * 4
        val z = zip.get()!!
        val raw = if (size <= z.raw.capacity()) z.raw.array() else ByteArray(size)
        val inf = z.inflater
        inf.reset()
        inf.setInput(packed)
        var n = 0
        var padded = false
        while (n < size) {
            val k = inf.inflate(raw, n, size - n)
            n += k
            if (k > 0) continue
            // nowrap streams may need one byte past the end (java.util.zip.Inflater): fed once.
            if (inf.needsInput() && !inf.finished() && !padded) {
                padded = true
                inf.setInput(z.one)
                continue
            }
            break
        }
        check(n == size) { "a packed ${w}x$h tile holds $n of $size bytes" }
        return BitmapUtils.createLayerBitmap(w, h).apply {
            density = Bitmap.DENSITY_NONE
            copyPixelsFromBuffer(ByteBuffer.wrap(raw, 0, size))
        }
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

    /** Main only: [b] cut into tiles ([cutPieces], then [adoptPieces]); each tile held once by the new store. */
    fun cut(b: Bitmap, detectBlank: Boolean): TiledPixels = adoptPieces(cutPieces(b, detectBlank)!!)

    /**
     * A8: the pieces [cutPieces] cut from a bitmap, not yet tiles: per tile slot nothing (fully
     * transparent), one uniform opaque color ([solids]), or an exact copy ([bitmaps], no density).
     * Made on any thread (the park cut runs on the `tile-swapper`); [adoptPieces] turns them into
     * tiles on main, or [recycle] lets them go.
     */
    class CutPieces internal constructor(val width: Int, val height: Int) {
        val cols = (width + TILE_SIZE - 1) / TILE_SIZE
        val rows = (height + TILE_SIZE - 1) / TILE_SIZE
        internal val bitmaps = arrayOfNulls<Bitmap>(cols * rows)
        internal val solids = arrayOfNulls<Int>(cols * rows)

        /** Recycles the copies (a cut that was not adopted). Main only (rule 3). */
        fun recycle() {
            for (i in bitmaps.indices) {
                bitmaps[i]?.recycle()
                bitmaps[i] = null
            }
        }
    }

    /**
     * [b] cut into [CutPieces] on the calling thread, any thread: it reads only [b] (which the
     * caller keeps alive and unchanged) and touches no tile, ledger or count. With [detectBlank],
     * a fully transparent piece is nothing and a uniform opaque one a color, else every piece is a
     * copy. One canvas and one pixel array serve every piece. Null when [cancelled] said so
     * between two pieces (what was cut is recycled, which is why the worker never cancels: it
     * hands its pieces to main to recycle).
     */
    fun cutPieces(b: Bitmap, detectBlank: Boolean, cancelled: () -> Boolean = { false }): CutPieces? {
        val w = b.width
        val h = b.height
        require(w > 0 && h > 0) { "nothing to cut (${w}x$h)" }
        val pieces = CutPieces(w, h)
        val cols = pieces.cols
        val px = if (detectBlank) IntArray(TILE_SIZE * TILE_SIZE) else null
        val canvas = Canvas()
        try {
            for (row in 0 until pieces.rows) for (col in 0 until cols) {
                if (cancelled()) {
                    pieces.recycle()
                    return null
                }
                val x = col * TILE_SIZE
                val y = row * TILE_SIZE
                val tw = min(TILE_SIZE, w - x)
                val th = min(TILE_SIZE, h - y)
                val i = row * cols + col
                val kind = if (px == null) {
                    OTHER
                } else {
                    b.getPixels(px, 0, tw, x, y, tw, th)
                    classify(px, tw * th)
                }
                when (kind) {
                    BLANK -> Unit
                    UNIFORM_OPAQUE -> pieces.solids[i] = px!![0]
                    else -> pieces.bitmaps[i] = copyPiece(canvas, b, x, y, tw, th)
                }
            }
        } catch (e: Throwable) {
            pieces.recycle()
            throw e
        }
        return pieces
    }

    /** Main only: [p] as a store, each tile held once by it; [p] is spent (its copies belong to the tiles). */
    fun adoptPieces(p: CutPieces): TiledPixels {
        val tiles = arrayOfNulls<Tile>(p.cols * p.rows)
        try {
            for (i in tiles.indices) {
                val col = i % p.cols
                val row = i / p.cols
                val tw = min(TILE_SIZE, p.width - col * TILE_SIZE)
                val th = min(TILE_SIZE, p.height - row * TILE_SIZE)
                val color = p.solids[i]
                val tile = when {
                    color != null -> solidTile(color, tw, th)
                    else -> p.bitmaps[i]?.let { b -> p.bitmaps[i] = null; tileOf(b) }
                } ?: continue
                tile.refs = 1
                tiles[i] = tile
            }
        } catch (e: Throwable) {
            for (t in tiles) if (t != null) release(t)
            p.recycle()
            throw e
        }
        return TiledPixels(p.width, p.height, tiles)
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

    /**
     * An exact copy of [tw] x [th] pixels of [b] at ([x], [y]), drawn through [canvas] (reused
     * from piece to piece; it takes the copy's density, none, so [b]'s is never scaled).
     */
    private fun copyPiece(canvas: Canvas, b: Bitmap, x: Int, y: Int, tw: Int, th: Int): Bitmap {
        val out = BitmapUtils.createLayerBitmap(tw, th)
        try {
            out.density = Bitmap.DENSITY_NONE
            canvas.setBitmap(out)
            canvas.drawBitmap(b, -x.toFloat(), -y.toFloat(), copyPaint)
            canvas.setBitmap(null)
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
        return out
    }

    /**
     * A transparent flat bitmap of [s]'s size, admitted as a layer like [materialize]'s (A8: the
     * activation fills one with [drawIfResident] while the worker reads the rest, section 2.7).
     * Throws [OutOfMemoryError] when the budget or the heap has no room.
     */
    fun newFlatFor(s: TiledPixels): Bitmap {
        check(s.evictedTo == null) { "an evicted store reloads from its saved file (A11)" }
        val bytes = s.width.toLong() * s.height * 4
        val budget = EngineConfig.budget
        if (!budget.admitNative(bytes, NativePurpose.LAYER)) throw OutOfMemoryError("No room for a ${s.width}x${s.height} layer")
        // F1: a flat bitmap is admitted but not kept in the ledger (v1.9 code recycles flat bitmaps anywhere).
        budget.release(bytes, NativePurpose.LAYER)
        return BitmapUtils.createLayerBitmap(s.width, s.height)
    }

    /**
     * Main only (A8): draws tile [i] of [s] at its place through [c] (a canvas over a bitmap of
     * [s]'s size) when it needs no read, that is when it is resident or solid; true when drawn.
     */
    fun drawIfResident(c: Canvas, s: TiledPixels, i: Int): Boolean {
        val t = s.tiles[i] ?: return false
        if (t.bitmap == null) return false
        drawTile(c, t, (i % s.cols) * TILE_SIZE, (i / s.cols) * TILE_SIZE, copyPaint)
        return true
    }

    fun materialize(s: TiledPixels): Bitmap {
        val out = newFlatFor(s)
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
        for (t in held) unpinTile(t)
    }

    /** One pin fewer on [t]; frees it when no pin and no holder is left. Main only. */
    fun unpinTile(t: Tile) {
        t.pins--
        if (t.pins == 0 && t.refs <= 0) free(t)
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
