package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.MemoryBudget
import com.brushwork.paint.engine.budget.NativePurpose
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * v1.10 (A9 step 1, design 2.5 X2): a tiled store drawn onto a SCALED canvas (thumbnails, the
 * Color select and Magnetic lasso proxies, a folder's small composite, export below scale 1) as
 * `drawBitmap(full, 0, 0, paint)` draws its full-size bitmap, bit for bit, without ever making
 * that bitmap.
 *
 * The target rows the draw reaches are cut into bands of at most [BAND_ROWS] rows (fewer when a
 * band's source rows would pass [MAX_BAND_BYTES]). For each band the source rows it samples,
 * plus an [APRON] of 2 px on every side and the bilinear neighbour, are copied out of the tiles
 * into one transient bitmap (exact: `TiledPixels.copyRegion`), which is drawn at its place under
 * the canvas's own matrix, clipped to the band's target rows. Every target pixel then samples the
 * same source pixels with the same weights as the full draw:
 *
 * - **Neighbours.** The band holds every source pixel a target pixel of the band reads, with 2 px
 *   to spare, and its edges are the store's own edges wherever it reaches them, so the clamp at
 *   an edge is the full bitmap's.
 * - **Coordinates.** Skia maps a target pixel centre through the inverse of `matrix ·
 *   translate(left, top)` in single precision (one row of a span at a time, the columns stepped
 *   in fixed point from the span's start, which is the canvas's clip either way), and filtering
 *   keeps only a few bits of the fraction. A band drawn at an arbitrary offset can therefore
 *   round a row or a column differently from the full draw, by a whole filter step (up to 16
 *   levels on noise). The band's offset is chosen so that it cannot: [exactOffset] checks, in
 *   the same float arithmetic (with and without a fused multiply-add), that every target row and
 *   column of the band maps to the full draw's coordinate minus the offset exactly, trying lower
 *   offsets (a slightly taller band) until one does; offset 0 is the full draw's own matrix.
 * - **Edges.** A non-anti-aliased bitmap draw covers the pixels whose centres lie inside its
 *   mapped rect; the band reaches past the clipped rows and columns on every side where it is
 *   not at the store's edge, so it covers exactly the pixels the full draw covers there.
 *
 * Masks go through the same path ([drawMask]): a `null` tile is transparent in the band, which
 * the DST_IN mask paint draws as a clearing source, as the full mask bitmap's transparent pixels.
 * Every band is admitted (`admitNative`, TRANSIENT) and freed after its draw. Any thread that
 * may read the store (main, or a holder of its snapshot and a pin).
 */
internal object BandAssembler {
    /** Target rows per band at most (design 2.5: bands of 256 target rows). */
    const val BAND_ROWS = 256

    /** Source px every band reaches beyond what its target pixels sample (design 2.5: a 2 px apron). */
    const val APRON = 2

    /** Bytes one band's source bitmap may hold before the band takes fewer target rows. */
    const val MAX_BAND_BYTES: Long = 8L shl 20

    /** Target rows per band in use: [BAND_ROWS] (tests lower it to cut a small target into many bands). */
    @Volatile
    internal var bandRows: Int = BAND_ROWS

    /** Lower offsets [exactOffset] tries before it settles for 0 (the store's own edge). */
    private const val SEARCH = 128

    /** Bands drawn so far (tests: a scaled draw went through here). */
    @Volatile
    internal var bands: Long = 0L
        private set

    /** X2: [p] drawn on [c] (any scale and translation) as `drawBitmap(full, 0, 0, paint)`. */
    fun draw(c: Canvas, p: TiledPixels, paint: Paint) = drawBands(c, p, paint)

    /** X2 for a mask: [m] drawn through [maskPaint] (DST_IN) as `drawBitmap(fullMask, 0, 0, maskPaint)`. */
    fun drawMask(c: Canvas, m: TiledPixels, maskPaint: Paint) = drawBands(c, m, maskPaint)

    /**
     * Any store drawn on [c] as `drawBitmap(bitmapOf(store), 0, 0, paint)` would draw it: Flat,
     * that call; Blank, a transparent rect through [paint] (a transparent bitmap); Tiled, the
     * tiles on an integer-translate canvas (X1: null tiles as transparent rects, which is what a
     * transparent part of the bitmap draws through [paint] whatever its mode), else the bands
     * (X2). A folder draws nothing. For readers outside the compositor's layer loop (the
     * adjustment stage's masks); never materializes.
     */
    fun drawStore(c: Canvas, store: PixelStore, paint: Paint) {
        when (store) {
            is FlatPixels -> c.drawBitmap(store.bitmap, 0f, 0f, paint)
            is BlankPixels -> c.drawRect(0f, 0f, store.width.toFloat(), store.height.toFloat(), transparent(paint))
            is TiledPixels -> {
                val clip = TileOps.clipOf(c) ?: return
                if (TileOps.isUnitTranslate(c)) {
                    TileOps.clearNullTiles(store, c, paint, clip)
                    store.drawTo(c, paint, clip)
                } else {
                    drawBands(c, store, paint)
                }
            }
            FolderPixels -> Unit
        }
    }

    /**
     * A mask store through [maskPaint] (DST_IN, luminance to alpha) as `drawBitmap(maskBitmap, 0,
     * 0, maskPaint)` would draw it: [drawStore], except that a tiled mask on an integer-translate
     * canvas goes through `drawMaskTo` (a solid white tile, DST_IN's identity, is skipped).
     */
    fun drawMaskStore(c: Canvas, store: PixelStore, maskPaint: Paint) {
        if (store is TiledPixels) {
            val clip = TileOps.clipOf(c) ?: return
            if (TileOps.isUnitTranslate(c)) store.drawMaskTo(c, maskPaint, clip) else drawBands(c, store, maskPaint)
        } else {
            drawStore(c, store, maskPaint)
        }
    }

    /**
     * The pixels of [r] (document px, inside the store) into [out] from [offset] with [stride], as
     * `Bitmap.getPixels` gives them (unpremultiplied ARGB); a blank store or a folder: 0. A tiled
     * store is copied through [tmp] (at least [r]-sized, its other pixels left as they are; null:
     * a bitmap made for the call). Never materializes.
     */
    fun readPixels(store: PixelStore, r: Rect, out: IntArray, offset: Int, stride: Int, tmp: Bitmap? = null) {
        val w = r.width()
        val h = r.height()
        if (w <= 0 || h <= 0) return
        when (store) {
            is FlatPixels -> store.bitmap.getPixels(out, offset, stride, r.left, r.top, w, h)
            is TiledPixels -> {
                val b = tmp?.takeIf { !it.isRecycled && it.width >= w && it.height >= h } ?: BitmapUtils.createLayerBitmap(w, h)
                try {
                    store.copyRegion(r, b, 0, 0)
                    b.getPixels(out, offset, stride, 0, 0, w, h)
                } finally {
                    if (b !== tmp) b.recycle()
                }
            }
            else -> for (y in 0 until h) out.fill(0, offset + y * stride, offset + y * stride + w)
        }
    }

    /**
     * The bounds of the pixels of [store] whose colour is not black (a mask's area that lets an
     * effect through), or null when there is none: a flat store scanned as v1.9 did, a tiled one
     * tile by tile (a null tile is transparent, which is black; a solid tile by its colour), a
     * blank store or a folder none. Never materializes.
     */
    fun nonBlackBounds(store: PixelStore): Rect? = when (store) {
        is FlatPixels -> nonBlackBounds(store.bitmap, 0, 0, store.width, store.height)
        is TiledPixels -> {
            var out: Rect? = null
            var tmp: Bitmap? = null
            try {
                for (row in 0 until store.rows) for (col in 0 until store.cols) {
                    val t = store.tileAt(col, row) ?: continue
                    val x = col * TILE_SIZE
                    val y = row * TILE_SIZE
                    val w = min(TILE_SIZE, store.width - x)
                    val h = min(TILE_SIZE, store.height - y)
                    val solid = t.solidColor
                    val r: Rect? = if (solid != null) {
                        if (solid and 0xFFFFFF != 0) Rect(x, y, x + w, y + h) else null
                    } else {
                        val b = tmp ?: BitmapUtils.createLayerBitmap(TILE_SIZE, TILE_SIZE).also { tmp = it }
                        store.copyRegion(Rect(x, y, x + w, y + h), b, 0, 0)
                        nonBlackBounds(b, 0, 0, w, h)?.apply { offset(x, y) }
                    }
                    if (r != null) out = out?.apply { union(r) } ?: r
                }
            } finally {
                tmp?.recycle()
            }
            out
        }
        else -> null
    }

    /** The bounds (relative to the bitmap) of the non-black pixels of [b] within ([x0], [y0]) + [w] x [h]. */
    private fun nonBlackBounds(b: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Rect? {
        if (w <= 0 || h <= 0) return null
        val row = IntArray(w)
        fun rowHas(y: Int): Boolean {
            b.getPixels(row, 0, w, x0, y, w, 1)
            for (x in 0 until w) if (row[x] and 0xFFFFFF != 0) return true
            return false
        }
        var top = y0
        val end = y0 + h
        while (top < end && !rowHas(top)) top++
        if (top == end) return null
        var bottom = end - 1
        while (bottom > top && !rowHas(bottom)) bottom--
        var left = w
        var right = -1
        for (y in top..bottom) {
            b.getPixels(row, 0, w, x0, y, w, 1)
            var x = 0
            while (x < left && row[x] and 0xFFFFFF == 0) x++
            if (x < left) left = x
            x = w - 1
            while (x > right && row[x] and 0xFFFFFF == 0) x--
            if (x > right) right = x
            if (left == 0 && right == w - 1) break
        }
        return if (right < left) null else Rect(x0 + left, top, x0 + right + 1, bottom + 1)
    }

    /**
     * [store] at [w] x [h] (at most its own size), a new bitmap the caller owns: a copy at its own
     * size; a flat store scaled as `Bitmap.createScaledBitmap(bitmap, w, h, true)` (v1.9); any
     * other drawn onto a canvas scaled by `w / width`, `h / height` with a filtering paint, the
     * tiled one through the bands (X2), which is what `createScaledBitmap` draws. RenderScaled's
     * layer source (the Color select and Magnetic lasso proxies). Throws `OutOfMemoryError`.
     */
    fun scaled(store: PixelStore, w: Int, h: Int): Bitmap {
        if (store is FlatPixels) {
            val bmp = store.bitmap
            if (w == bmp.width && h == bmp.height) return bmp.copy(Bitmap.Config.ARGB_8888, false) ?: throw OutOfMemoryError("layer copy ${bmp.width}x${bmp.height}")
            return Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        val out = BitmapUtils.createLayerBitmap(w, h)
        try {
            if (w == store.width && h == store.height) {
                TileOps.readRegion(store, Rect(0, 0, w, h), out)
            } else if (store is TiledPixels) {
                val c = Canvas(out)
                c.scale(w.toFloat() / store.width, h.toFloat() / store.height)
                drawBands(c, store, scalePaint)
            }
            return out
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
    }

    /** The paint `Bitmap.createScaledBitmap(…, filter = true)` draws with. */
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** [paint] drawing a transparent source (a transparent bitmap's pixels). */
    private fun transparent(paint: Paint): Paint = Paint(paint).apply { color = 0; shader = null }

    @Suppress("DEPRECATION")
    private fun drawBands(c: Canvas, p: TiledPixels, paint: Paint) {
        val m: Matrix = c.matrix
        val v = FloatArray(9)
        m.getValues(v)
        val sx = v[Matrix.MSCALE_X]
        val sy = v[Matrix.MSCALE_Y]
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]
        if (v[Matrix.MSKEW_X] != 0f || v[Matrix.MSKEW_Y] != 0f || v[Matrix.MPERSP_0] != 0f || v[Matrix.MPERSP_1] != 0f ||
            v[Matrix.MPERSP_2] != 1f || !(sx > 0f) || !(sy > 0f) || !tx.isFinite() || !ty.isFinite()
        ) {
            // A rotated or mirrored canvas (never a composite target today): the clip's band.
            TileOps.drawBand(p, c, paint)
            return
        }
        // The device clip (the canvas's clip in target px).
        val dev = Rect()
        val save = c.save()
        c.setMatrix(null)
        val any = c.getClipBounds(dev)
        c.restoreToCount(save)
        if (!any || dev.isEmpty) return
        val w = p.width
        val h = p.height
        // The target pixels the store's mapped rect can cover, within the clip.
        val t = Rect(
            max(dev.left, floor(tx.toDouble()).toInt()),
            max(dev.top, floor(ty.toDouble()).toInt()),
            min(dev.right, ceil(tx.toDouble() + sx.toDouble() * w).toInt()),
            min(dev.bottom, ceil(ty.toDouble() + sy.toDouble() * h).toInt()),
        )
        if (t.isEmpty) return
        val isx = 1f / sx
        val isy = 1f / sy
        // Columns: one band width for the whole draw (the spans start at the same column).
        val needL = sourceLow(t.left, tx, isx)
        val needR = sourceHigh(t.right, tx, isx)
        if (needL >= w || needR <= 0) return
        val x0 = exactOffset(needL.coerceAtLeast(0), t.left, t.right, sx, tx, isx)
        val x1 = min(w, needR)
        if (x1 <= x0) return
        val bandWidth = x1 - x0
        // Rows per band: at most BAND_ROWS, fewer when the band's source would pass MAX_BAND_BYTES.
        val maxSourceRows = max(1L, MAX_BAND_BYTES / (4L * bandWidth))
        val fit = floor((maxSourceRows - 2 * (APRON + 2)).toDouble() * sy).toInt()
        val rowsPerBand = fit.coerceIn(1, bandRows)
        val budget = EngineConfig.budget
        var b0 = t.top
        while (b0 < t.bottom) {
            val b1 = min(t.bottom, b0 + rowsPerBand)
            val needT = sourceLow(b0, ty, isy)
            val needB = sourceHigh(b1, ty, isy)
            if (needT < h && needB > 0) {
                val y0 = exactOffset(needT.coerceAtLeast(0), b0, b1, sy, ty, isy)
                val y1 = min(h, needB)
                if (y1 > y0) drawBand(c, p, paint, m, dev, b0, b1, Rect(x0, y0, x1, y1), budget)
            }
            b0 = b1
        }
    }

    /** One band: source rect [src] copied out of [p] and drawn at its place, clipped to target rows [b0, b1). */
    private fun drawBand(c: Canvas, p: TiledPixels, paint: Paint, m: Matrix, dev: Rect, b0: Int, b1: Int, src: Rect, budget: MemoryBudget) {
        val bytes = src.width().toLong() * src.height() * 4L
        if (!budget.admitNative(bytes, NativePurpose.TRANSIENT)) throw OutOfMemoryError("No room for a ${src.width()}x${src.height()} band")
        try {
            val band = BitmapUtils.createLayerBitmap(src.width(), src.height())
            try {
                p.copyRegion(src, band, 0, 0)
                val save = c.save()
                c.setMatrix(null)
                c.clipRect(dev.left, b0, dev.right, b1)
                c.setMatrix(m)
                c.drawBitmap(band, src.left.toFloat(), src.top.toFloat(), paint)
                c.restoreToCount(save)
                bands++
            } finally {
                band.recycle()
            }
        } finally {
            budget.release(bytes, NativePurpose.TRANSIENT)
        }
    }

    /** The lowest source index target index [t0] samples, less the apron (may be negative). */
    private fun sourceLow(t0: Int, trans: Float, inv: Float): Int =
        floor((t0 + 0.5 - trans) * inv - 0.5).toInt() - 1 - APRON

    /** One past the highest source index the target index below [t1] samples, plus the apron. */
    private fun sourceHigh(t1: Int, trans: Float, inv: Float): Int =
        floor((t1 - 0.5 - trans) * inv + 0.5).toInt() + 2 + APRON

    /**
     * The highest offset k <= [start] at which a bitmap drawn at k (under a matrix of [scale] and
     * [trans] along one axis) maps every target index of [t0, t1) to the full draw's coordinate
     * minus k exactly, in the arithmetic Skia uses: the concatenated translation
     * `scale · k + trans` and its inverse `-(that) · (1 / scale)` in float, then `(t + 0.5) · inv
     * + invTrans` per index, with and without a fused multiply-add. 0 when none of the next
     * [SEARCH] offsets does (0 is the full draw's matrix itself).
     */
    internal fun exactOffset(start: Int, t0: Int, t1: Int, scale: Float, trans: Float, inv: Float): Int {
        var k = start
        val stop = max(0, start - SEARCH)
        while (k > stop) {
            if (isExact(k, t0, t1, scale, trans, inv)) return k
            k--
        }
        return if (stop == 0 || isExact(stop, t0, t1, scale, trans, inv)) stop else 0
    }

    private fun isExact(k: Int, t0: Int, t1: Int, scale: Float, trans: Float, inv: Float): Boolean {
        if (k == 0) return true
        val kf = k.toFloat()
        val invFull = -trans * inv
        val plain = scale * kf + trans
        val fused = Math.fma(scale, kf, trans)
        for (sub in if (plain == fused) floatArrayOf(plain) else floatArrayOf(plain, fused)) {
            val invSub = -sub * inv
            for (i in t0 until t1) {
                val ct = i + 0.5f
                val p = ct * inv
                if ((p + invSub).toDouble() != (p + invFull).toDouble() - k) return false
                if (Math.fma(ct, inv, invSub).toDouble() != Math.fma(ct, inv, invFull).toDouble() - k) return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- A9 step 2 (P4): waiting display tiles

    /** True when [t]'s pixels are in memory as a bitmap (RESIDENT, or a shared solid tile): drawing it reads nothing. */
    fun isResident(t: Tile): Boolean = t.bitmap != null

    /**
     * Lends [b], a copy of [t]'s pixels read off main (`Tile.bitmapForRead`) under a pin, to [t]
     * while one chunk of a waiting display tile composites on main (`engine/live/WaitingTiles`), so
     * the compositor draws it without reading again. False when [t] became resident meanwhile (then
     * its own bitmap is drawn). The tile's state, slot, packed bytes and ledger are untouched: the
     * loan is not a swap-in. Main only; [unlend] in the same task.
     */
    fun lend(t: Tile, b: Bitmap): Boolean = synchronized(t.lock) {
        if (t.bitmap != null) {
            false
        } else {
            t.bitmap = b
            lent++
            true
        }
    }

    /** Ends a [lend] of [b] to [t] (the caller recycles [b]). Main only. */
    fun unlend(t: Tile, b: Bitmap) {
        synchronized(t.lock) { if (b === t.bitmap) t.bitmap = null }
    }

    /** Loans made so far (tests). */
    @Volatile
    internal var lent: Long = 0L
        private set
}
