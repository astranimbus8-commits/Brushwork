package com.brushwork.paint.engine.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.model.Layer
import kotlin.math.max
import kotlin.math.min

/**
 * v1.9 A6 (P3 and R11a; design 3.2 A6 steps 9 and 10): an exact below-cache for a painting
 * stroke, at scale 1, on the canvas's display tiles.
 *
 * While a stroke paints layer L (at flat index a), every frame redraws the dirty parts of the
 * tiles it touched. Without a cache that is the whole stack over each part; with it:
 * - k is the start of L's clipping group (L itself when it is not clipped; with folders, L when
 *   every folder it is in draws its children as is and L is not clipped, else no cache). For
 *   each display tile the stroke reaches, a bitmap of the tile's size holds the composite of
 *   layers `[0, k)` (`Compositor.drawSplit`, onto a display target, as the tile's own render),
 *   built where it is needed, 16 px cells at a time (a bit per cell: valid).
 * - A tile's dirty part is then the cache copied 1:1 (SRC), then `[k, n)` drawn over it with the
 *   stroke's override. `[0, k)` then `[k, n)` onto one canvas is the full draw, so the tile gets
 *   exactly the pixels a CLEAR and `drawDocument` give (I7, bit for bit).
 * - Validity: a [BelowKey] of `[0, k)` (each layer's identity, bitmap, mask, adjustment, mask
 *   spec, content version, packed properties and folder; the color mode, "Safe compositing")
 *   is checked at every use, and every cell is dropped when it changes. A part invalidated by
 *   anything but the stroke itself ([own]) drops the cells there (the next use rebuilds them);
 *   an override that draws a layer below k turns the cache off for that render.
 * - Memory: at most [MEMORY_CAP_BYTES] (24 MB: 24 tiles of 512 px); a tile beyond it takes the
 *   place of the one used longest ago, or composites as before when it can't. One
 *   [MemoryGovernor] entry, "below-caches" at priority [BELOW_PRIORITY] (20), while it holds
 *   bitmaps: any pressure above NORMAL frees them and the rest of the stroke composites as v1.8.
 * - The cache is dropped at the lift and at the cancel ([end]): it lives one stroke.
 *
 * [begin] decides per stroke: P3 ([strokesOn], shipped as [FOR_STROKES]) for brush, smudge, blur
 * and watercolor strokes, R11a ([eraserOn], shipped as [FOR_ERASER]) for the raster Eraser. When
 * on it runs under Robolectric too (it is exact, I8). Main thread only (I3), like the display
 * tiles it serves.
 */
class StrokeBelowCache internal constructor(private val c: EditorController) : DisplayTiles.BelowRenderer {

    /** Tests and the A6 bench: false keeps every stroke on v1.8's full composite. */
    internal var enabled: Boolean = true

    /** Most bytes the cache bitmaps may hold (tests change it). */
    internal var memoryCapBytes: Long = MEMORY_CAP_BYTES

    /** The stroke's layer while a stroke uses the cache, else null. */
    private var layer: Layer? = null

    /** The display tiles the cache is installed on (null: none). */
    private var tiles: DisplayTiles? = null

    /** A pressure trim freed the cache: the rest of the stroke composites as before. */
    private var trimmed = false

    private var cell = MIN_CELL
    private var bitmaps: Array<Bitmap?> = emptyArray()
    private var canvases: Array<Canvas?> = emptyArray()
    private var targets: Array<CompositeTarget?> = emptyArray()

    /** Per tile: one Int per row of cells, bit x set when cell x holds `[0, split)`. */
    private var valid: Array<IntArray?> = emptyArray()
    private var lastUse = LongArray(0)
    private var useClock = 0L
    private val key = BelowKey()
    private var split = -1
    private var own = 0
    private val buildRect = Rect()

    /** Bytes the cache bitmaps hold now (the "below-caches" entry). */
    var bytes: Long = 0L
        private set

    /** Renders drawn from the cache (tests: the cache was used). */
    internal var hits: Int = 0
        private set

    /** Parts of `[0, k)` composited into the cache. */
    internal var builds: Int = 0
        private set

    /** Pixels composited into the cache (the A6 bench: what the cache cost). */
    internal var builtPixels: Long = 0L
        private set

    /** True while a stroke uses the cache. */
    val isActive: Boolean get() = layer != null

    private var governed: MemoryGovernor.Registration? = null

    // ------------------------------------------------------------------ the stroke's API

    /**
     * A stroke starts on [layer]; [erase]: the raster Eraser's. True when its frames will draw the
     * layers below it from the cache (some layer below to cache, and the split is one the
     * compositor can make); the stroke then marks its own invalidations with [own] and calls
     * [end] after its lift or cancel.
     */
    fun begin(layer: Layer, erase: Boolean): Boolean {
        end()
        if (!enabled || !(if (erase) eraserOn else strokesOn)) return false
        if (splitFor(layer) <= 0) return false
        val t = c.tiles
        this.layer = layer
        trimmed = false
        install(t)
        return true
    }

    /** Runs [block] (the stroke invalidating what it painted): those invalidations keep the cache. */
    inline fun <T> own(block: () -> T): T {
        enterOwn()
        try {
            return block()
        } finally {
            leaveOwn()
        }
    }

    @PublishedApi internal fun enterOwn() { own++ }

    @PublishedApi internal fun leaveOwn() { own-- }

    /** The stroke ended (lift or cancel): the cache is dropped and its memory freed. */
    fun end() {
        tiles?.let { if (it.belowRenderer === this) it.belowRenderer = null }
        tiles = null
        layer = null
        split = -1
        key.clear()
        freeBitmaps()
    }

    /** Frees everything (the editor closing). */
    fun release() = end()

    /**
     * The flat index the cache splits the stack at for a stroke on [layer]: the first layer of
     * its clipping group (an adjustment layer is its own group); with folders, [layer] itself when
     * every folder it is in draws its children as is and it is not clipped. 0 or less: no cache.
     */
    internal fun splitFor(layer: Layer): Int {
        val doc = c.doc
        val layers = doc.layers
        val a = doc.indexOf(layer)
        if (a <= 0 || layer.isFolder) return -1
        if (doc.hasFolders) {
            return if (FolderComposite.drawnAsIs(layers, a) && !FolderComposite.isClipped(layers, a)) a else -1
        }
        var k = a
        while (k > 0 && FolderComposite.isClipped(layers, k)) k--
        return k
    }

    // ------------------------------------------------------------------ DisplayTiles.BelowRenderer

    override fun render(tiles: DisplayTiles, index: Int, tr: Rect, d: Rect, canvas: Canvas, target: CompositeTarget, compositor: Compositor): Boolean {
        val l = layer ?: return false
        if (trimmed || tiles !== this.tiles || tiles !== c.tiles || compositor !== c.compositor) return false
        val doc = c.doc
        if (tiles.docWidth != doc.width || tiles.docHeight != doc.height || index !in bitmaps.indices) return false
        val k = splitFor(l)
        if (k <= 0) return false
        val safe = AdjustmentStage.safeCompositing
        val ov = c.renderOverride
        if (BelowKey.overrideBelow(doc, k, ov)) return false
        if (k != split || !key.matches(doc, k, safe, ov)) {
            // Something below changed (or the split moved): every cell is built again.
            dropAll()
            key.capture(doc, k, safe)
            split = k
        }
        val cache = cacheFor(index, tr) ?: return false
        val v = valid[index] ?: return false
        try {
            // The cells of [d] the cache doesn't hold yet: build their bounding box.
            val cs = cell
            val x0 = (d.left - tr.left) / cs
            val x1 = (d.right - 1 - tr.left) / cs
            val y0 = (d.top - tr.top) / cs
            val y1 = (d.bottom - 1 - tr.top) / cs
            val rowMask = bits(x0, x1)
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = -1
            var maxY = -1
            for (y in y0..y1) {
                val missing = rowMask and v[y].inv()
                if (missing == 0) continue
                if (minY < 0) minY = y
                maxY = y
                minX = min(minX, Integer.numberOfTrailingZeros(missing))
                maxX = max(maxX, 31 - Integer.numberOfLeadingZeros(missing))
            }
            if (maxY >= 0) {
                buildRect.set(
                    tr.left + minX * cs, tr.top + minY * cs,
                    min(tr.right, tr.left + (maxX + 1) * cs), min(tr.bottom, tr.top + (maxY + 1) * cs),
                )
                build(index, tr, buildRect, compositor, k)
                val m = bits(minX, maxX)
                for (y in minY..maxY) v[y] = v[y] or m
            }
            // The tile's part: the cache 1:1, then the stroke's layer and everything above it.
            canvas.drawBitmap(cache, tr.left.toFloat(), tr.top.toFloat(), ProxyTiles.copyPaint)
            compositor.drawSplit(canvas, d, target, k, doc.layers.size)
        } catch (e: IllegalArgumentException) {
            // A split the compositor refuses (never expected: [splitFor] follows its rules): the
            // caller composites this part as before, and the rest of the stroke does too.
            trimmed = true
            dropAll()
            return false
        }
        lastUse[index] = ++useClock
        hits++
        return true
    }

    override fun invalidated(tiles: DisplayTiles, r: Rect) {
        if (own > 0 || tiles !== this.tiles || bytes == 0L) return
        // A change the stroke didn't make: the cells there are built again when next used.
        val ts = tiles.tileSize
        val cs = cell
        for (row in r.top / ts..(r.bottom - 1) / ts) for (col in r.left / ts..(r.right - 1) / ts) {
            val i = tiles.tileIndexOf(col, row)
            val v = valid.getOrNull(i) ?: continue
            val left = col * ts
            val top = row * ts
            val x0 = (max(r.left, left) - left) / cs
            val x1 = (min(r.right, left + ts) - 1 - left) / cs
            val y0 = (max(r.top, top) - top) / cs
            val y1 = (min(r.bottom, top + ts) - 1 - top) / cs
            val m = bits(x0, x1).inv()
            for (y in y0..y1) v[y] = v[y] and m
        }
    }

    // ------------------------------------------------------------------ cache tiles

    private fun install(t: DisplayTiles) {
        val n = t.tileCount
        if (bitmaps.size != n) {
            bitmaps = arrayOfNulls(n)
            canvases = arrayOfNulls(n)
            targets = arrayOfNulls(n)
            valid = arrayOfNulls(n)
            lastUse = LongArray(n)
        }
        // At most 32 cells per side (one Int per row of cells).
        cell = max(MIN_CELL, (t.tileSize + 31) / 32)
        t.belowRenderer = this
        tiles = t
    }

    /** Tile [index]'s cache bitmap (document rect [tr]), made within [memoryCapBytes]; null when it can't be. */
    private fun cacheFor(index: Int, tr: Rect): Bitmap? {
        bitmaps[index]?.let { return it }
        val need = tr.width().toLong() * tr.height() * 4L
        while (bytes + need > memoryCapBytes) if (!evictOldest(index)) return null
        val bmp = try {
            Bitmap.createBitmap(tr.width(), tr.height(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        bitmaps[index] = bmp
        canvases[index] = Canvas(bmp)
        targets[index] = CompositeTarget.displayTile(bmp, tr.left, tr.top)
        valid[index] = IntArray((tr.height() + cell - 1) / cell)
        bytes += bmp.allocationByteCount
        register()
        return bmp
    }

    /** Composites `[0, k)` over [b] (document px, within tile [index] at [tr]) into the cache. */
    private fun build(index: Int, tr: Rect, b: Rect, compositor: Compositor, k: Int) {
        val cv = canvases[index]!!
        val save = cv.save()
        try {
            cv.translate(-tr.left.toFloat(), -tr.top.toFloat())
            cv.clipRect(b)
            cv.drawColor(0, PorterDuff.Mode.CLEAR)
            compositor.drawSplit(cv, b, targets[index], 0, k)
        } finally {
            cv.restoreToCount(save)
        }
        builds++
        builtPixels += b.width().toLong() * b.height()
    }

    /** Frees the cache tile used longest ago other than [keep]; false when there is none. */
    private fun evictOldest(keep: Int): Boolean {
        var oldest = -1
        for (i in bitmaps.indices) {
            if (i == keep || bitmaps[i] == null) continue
            if (oldest < 0 || lastUse[i] < lastUse[oldest]) oldest = i
        }
        if (oldest < 0) return false
        free(oldest)
        return true
    }

    private fun free(i: Int) {
        val b = bitmaps[i] ?: return
        bytes -= b.allocationByteCount
        b.recycle()
        bitmaps[i] = null
        canvases[i] = null
        targets[i] = null
        valid[i] = null
        lastUse[i] = 0L
    }

    /** Every cell is built again when next used. */
    private fun dropAll() {
        for (v in valid) v?.fill(0)
    }

    private fun freeBitmaps() {
        for (i in bitmaps.indices) free(i)
        bytes = 0L
        governed?.close()
        governed = null
    }

    // ------------------------------------------------------------------ memory

    private fun register() {
        if (governed != null) return
        governed = WeakRegistration.register(this, "below-caches", BELOW_PRIORITY, { it.bytes }, { cache, p -> cache.trimFor(p) })
    }

    /**
     * The governor's trim (main thread): above NORMAL the cache is freed and the rest of the
     * stroke composites as before. Returns the bytes freed.
     */
    private fun trimFor(pressure: MemoryPressure): Long {
        if (pressure == MemoryPressure.NORMAL) return 0L
        val held = bytes
        if (layer != null) trimmed = true
        split = -1
        key.clear()
        freeBitmaps()
        return held
    }

    companion object {
        /**
         * P3 (step 9) as shipped: brush, smudge, blur and watercolor strokes use the cache. False:
         * CUT (proxy) on A6's bench (the composite it saves is a few percent of the frame, see
         * ARCHITECTURE), code kept; flipping it ships P3 as built.
         */
        const val FOR_STROKES = false

        /**
         * R11a (step 10) as shipped: the raster Eraser's strokes use the cache. False: CUT (proxy)
         * on A6's bench (it saves a quarter of the erasing composite on PHONE, a few percent of the
         * frame, see ARCHITECTURE), code kept; flipping it ships R11a as built.
         */
        const val FOR_ERASER = false

        /** P3 is on ([FOR_STROKES]; tests and the A6 bench turn it on and back off). */
        internal var strokesOn: Boolean = FOR_STROKES

        /** R11a is on ([FOR_ERASER]; tests and the A6 bench turn it on and back off). */
        internal var eraserOn: Boolean = FOR_ERASER

        /** At most 24 MB of cache tiles (24 display tiles of 512 px; design: LRU-capped, about 24 MB). */
        const val MEMORY_CAP_BYTES: Long = 24L shl 20

        /** The cache's MemoryGovernor priority (0 display caches, 10 proxies, 20 below-caches). */
        const val BELOW_PRIORITY = 20

        /** The smallest cell (document px) whose validity is kept. */
        const val MIN_CELL = 16

        /** Bits [x0]..[x1] (inclusive, within 0..31). */
        private fun bits(x0: Int, x1: Int): Int {
            val n = x1 - x0 + 1
            return if (n >= 32) -1 else ((1 shl n) - 1) shl x0
        }
    }
}
