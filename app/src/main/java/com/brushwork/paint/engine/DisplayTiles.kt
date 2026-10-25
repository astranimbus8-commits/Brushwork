package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.engine.live.WaitingTiles
import kotlin.math.min

/**
 * What one [DisplayTiles.updateBudgeted] call did (v1.6): [changed] = at least one tile was
 * rendered; [hasMore] = dirty visible tiles (not skipped) are still waiting.
 */
data class TileUpdate(val changed: Boolean, val hasMore: Boolean) {
    internal companion object {
        private val ALL = arrayOf(TileUpdate(false, false), TileUpdate(false, true), TileUpdate(true, false), TileUpdate(true, true))

        /** v1.9 A6 (P2): the shared instance (one per pair; a frame allocates none). */
        fun of(changed: Boolean, hasMore: Boolean): TileUpdate = ALL[(if (changed) 2 else 0) + (if (hasMore) 1 else 0)]
    }
}

/**
 * The on-screen composite, split into tiles so that a brush stroke only re-renders (and the GPU
 * only re-uploads) the tiles it touched. Not thread-safe: use from the main thread.
 *
 * v1.9 A6 (P2): a frame allocates nothing here. Each tile keeps its dirty rect, its canvas and
 * its composite target for its bitmap's life; the scratch rects and the budgeted order are
 * reused.
 */
class DisplayTiles(val docWidth: Int, val docHeight: Int, val tileSize: Int = 512) {
    val cols = (docWidth + tileSize - 1) / tileSize
    val rows = (docHeight + tileSize - 1) / tileSize
    private val tiles = arrayOfNulls<Bitmap>(cols * rows)
    /** v1.9 A6 (P2): the canvas and composite target of each tile's current bitmap. */
    private val canvases = arrayOfNulls<Canvas>(cols * rows)
    private val targets = arrayOfNulls<CompositeTarget>(cols * rows)
    /** v1.9 A6: the allocation of each tile's current bitmap (their sum is [heldBytes]). */
    private val tileBytes = IntArray(cols * rows)
    /**
     * The part of each tile waiting to be rendered, null when it is clean (as in v1.8; perf tests
     * read this field). v1.9 A6 (P2): a non-null entry is the tile's own rect in [dirtyRects],
     * so marking a tile allocates nothing.
     */
    private val dirty = arrayOfNulls<Rect>(cols * rows)
    private val dirtyRects = Array(cols * rows) { Rect() }
    private var dirtyCount = 0
    private val tmpRect = Rect()
    private val invRect = Rect()
    private val partRect = Rect()
    private val updateRect = Rect()
    private val budgetRect = Rect()
    private val renderClip = Rect()
    private val order = IntArray(cols * rows)
    private val orderDist = FloatArray(cols * rows)
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init { invalidate(null) }

    fun tileRect(col: Int, row: Int, out: Rect = Rect()): Rect {
        val l = col * tileSize; val t = row * tileSize
        out.set(l, t, min(docWidth, l + tileSize), min(docHeight, t + tileSize))
        return out
    }

    /**
     * v1.6: told about every invalidated area (document px, within the document) as it is marked,
     * before anything is rendered: a live adjustment session (`engine/live`) refreshes its proxy
     * tiles there. Main thread.
     */
    internal var invalidationHook: ((Rect) -> Unit)? = null

    /**
     * v1.9 A6 (P3, R11a): renders a tile's dirty part from a cache of the layers below a stroke's
     * layer (`engine/live/StrokeBelowCache`), installed while such a stroke runs. Main thread.
     */
    internal interface BelowRenderer {
        /**
         * Draws document area [d] of tile [index] (document rect [tr]) onto [canvas] (translated
         * to document px and clipped to [d]; [target] is the tile) exactly as a CLEAR and a full
         * `Compositor.drawDocument` would, and returns true; false: it drew nothing (the caller
         * composites as usual).
         */
        fun render(tiles: DisplayTiles, index: Int, tr: Rect, d: Rect, canvas: Canvas, target: CompositeTarget, compositor: Compositor): Boolean

        /** [r] (document px, within the document; a scratch rect valid during the call) was invalidated. */
        fun invalidated(tiles: DisplayTiles, r: Rect)

        /**
         * v1.10 A9 (P4, X4): the split `k` at which this renderer would draw tile [index]
         * (document rect [tr]): units `[0, k)` from its cache of the whole tile, made ready for
         * the current document (key checked, the cache tile allocated); -1 when it would not draw
         * the tile that way. Asked before the waiting check, so that only the units a render
         * reads are checked for tiles that are not resident.
         */
        fun prepare(tiles: DisplayTiles, index: Int, tr: Rect, compositor: Compositor): Int = -1

        /** v1.10 A9: [render] after [prepare] returned [k] (> 0) for the same tile, in the same render. */
        fun renderSplit(tiles: DisplayTiles, index: Int, tr: Rect, d: Rect, canvas: Canvas, target: CompositeTarget, compositor: Compositor, k: Int): Boolean =
            render(tiles, index, tr, d, canvas, target, compositor)

        /** v1.10 A9 (P4): tile [index]'s cache of units `[0, k)` when it is built (after [prepare] returned k), else null. */
        fun builtCache(index: Int): Bitmap? = null

        /**
         * v1.10 A9 (P4): [acc] (tile [index], document rect [tr]) holds units `[0, k)` composited
         * over the whole tile through the waiting tiles; the renderer keeps it as that tile's
         * cache when its split and key are still those [prepare] saw.
         */
        fun adopt(tiles: DisplayTiles, index: Int, tr: Rect, k: Int, acc: Bitmap) = Unit
    }

    /** v1.9 A6 (P3): the [BelowRenderer] [render] asks first (null: none). */
    internal var belowRenderer: BelowRenderer? = null

    /**
     * v1.10 A9 step 2 (design 2.0 P4): the waiting tiles, made when a part first needs a tile that
     * is not resident (tests install one with their own worker and handler). Main thread.
     */
    internal var waiting: WaitingTiles? = null

    /** v1.10 A9 (P4): false renders every part at once, reading what is not resident on main (v1.9; tests and benches). */
    internal var waitingEnabled: Boolean = true

    /** v1.10 A9 (P4): called on main when a waiting tile completed (the view asks for a frame). */
    var onWaitingDone: (() -> Unit)? = null

    /** v1.10 A9 (P4): true while some display tile waits for its sources. */
    val hasWaiting: Boolean get() = waiting?.pending == true

    /**
     * v1.10 A9 (P4): true when tile [index] shows exactly the current composite: rendered, not
     * dirty and not waiting (a waiting tile shows stale content, or nothing, until it completes).
     */
    fun isExact(index: Int): Boolean = tiles.getOrNull(index) != null && dirty[index] == null && waiting?.isWaiting(index) != true

    /**
     * v1.10 A9 step 2 (design 4.4 A9): set by the view for the first update after a gesture lifts;
     * that update renders on the plain path, never through a projection ([belowRenderer]).
     */
    internal var projectionsOff: Boolean = false

    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** v1.10 A9 (P4): a waiting tile's finished accumulator [acc] (tile [index], document rect [tr]) replaces its part [d]. */
    internal fun completeWaiting(index: Int, tr: Rect, d: Rect, acc: Bitmap) {
        val bmp = tiles.getOrNull(index) ?: return
        val c = canvases[index] ?: return
        if (bmp.isRecycled) return
        val save = c.save()
        c.translate(-tr.left.toFloat(), -tr.top.toFloat())
        c.clipRect(d)
        c.drawBitmap(acc, tr.left.toFloat(), tr.top.toFloat(), srcPaint)
        c.restoreToCount(save)
    }

    /**
     * Marks [rect] (document coords; null = everything) for re-rendering. The [invalidationHook]
     * gets a scratch rect valid during the call only (its users copy what they keep).
     */
    fun invalidate(rect: Rect?) {
        val r = invRect
        if (rect == null) r.set(0, 0, docWidth, docHeight) else r.set(rect)
        if (!r.intersect(0, 0, docWidth, docHeight)) return
        val left = r.left; val top = r.top; val right = r.right; val bottom = r.bottom
        invalidationHook?.invoke(r)
        belowRenderer?.let { r.set(left, top, right, bottom); it.invalidated(this, r) }
        val c0 = left / tileSize; val c1 = (right - 1) / tileSize
        val r0 = top / tileSize; val r1 = (bottom - 1) / tileSize
        for (row in r0..r1) for (col in c0..c1) {
            val idx = row * cols + col
            tileRect(col, row, tmpRect)
            val part = partRect
            part.set(left, top, right, bottom)
            if (!part.intersect(tmpRect)) continue
            // v1.10 A9 (P4): a waiting tile's job is cancelled; its part is dirty again.
            waiting?.cancel(idx)?.let { part.union(it) }
            val d = dirty[idx]
            if (d != null) d.union(part)
            else { dirty[idx] = dirtyRects[idx].apply { set(part) }; dirtyCount++ }
        }
    }

    val hasDirty: Boolean get() = dirtyCount > 0

    /**
     * Re-renders dirty tiles with [compositor]. Returns true if anything changed. [visibleDoc]
     * (document px; v1.5) is the area on screen: only dirty tiles that intersect it are rendered,
     * the others stay dirty until they become visible (a full-canvas adjustment slider drag then
     * only pays for what is on screen). Null renders every dirty tile.
     */
    fun update(compositor: Compositor, visibleDoc: Rect? = null): Boolean {
        if (dirtyCount == 0) return false
        var changed = false
        val tr = updateRect
        for (idx in tiles.indices) {
            if (dirty[idx] == null) continue
            val col = idx % cols; val row = idx / cols
            tileRect(col, row, tr)
            if (visibleDoc != null && !Rect.intersects(visibleDoc, tr)) continue
            render(compositor, idx, tr)
            changed = true
        }
        return changed
    }

    /** Renders the dirty part of tile [idx] (document rect [tr]) and marks it clean. */
    private fun render(compositor: Compositor, idx: Int, tr: Rect) {
        val d = renderClip
        d.set(dirtyRects[idx])
        dirty[idx] = null
        dirtyCount--
        var bmp = tiles[idx]
        var c = canvases[idx]
        var target = targets[idx]
        if (bmp == null || bmp.isRecycled || c == null || target == null) {
            bmp = Bitmap.createBitmap(tr.width(), tr.height(), Bitmap.Config.ARGB_8888)
            bmp.setHasMipMap(true)
            tiles[idx] = bmp
            heldBytes += bmp.allocationByteCount - tileBytes[idx]
            tileBytes[idx] = bmp.allocationByteCount
            c = Canvas(bmp)
            canvases[idx] = c
            target = CompositeTarget.displayTile(bmp, tr.left, tr.top)
            targets[idx] = target
        }
        val save = c.save()
        c.translate(-tr.left.toFloat(), -tr.top.toFloat())
        c.clipRect(d)
        val below = if (projectionsOff) null else belowRenderer
        // v1.10 A9 (X4): a projection draws the units below its split from its cache.
        val k = below?.prepare(this, idx, tr, compositor) ?: -1
        // v1.10 A9 (P4): sources not resident: the part waits (the tile keeps what it shows).
        if (waitingEnabled && queueWaiting(compositor, idx, tr, d, below, k)) {
            c.restoreToCount(save)
            return
        }
        // v1.9 A6 (P3, R11a): a stroke's below-cache, when it can draw this part exactly.
        val drawn = when {
            below == null -> false
            k > 0 -> below.renderSplit(this, idx, tr, d, c, target, compositor, k)
            else -> below.render(this, idx, tr, d, c, target, compositor)
        }
        if (!drawn) {
            // A projection that refused (never expected): the plain path, which may read below its split.
            if (k > 0 && waitingEnabled && WaitingTiles.needsReads(compositor, d)) {
                c.restoreToCount(save)
                waitingTiles().queue(idx, tr, d, compositor)
                return
            }
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            compositor.drawDocument(c, d, target = target)
        }
        c.restoreToCount(save)
    }

    private fun waitingTiles(): WaitingTiles = waiting ?: WaitingTiles(this).also { waiting = it }

    /**
     * v1.10 A9 (P4): queues the part [d] of tile [idx] (document rect [tr]) as waiting when its
     * render would read a tile that is not resident; true when it did. Without a projection split
     * ([k] <= 0) every unit shown in [d] is checked. With one, only what the render reads: once
     * the projection's cache tile is built, the units from the split up inside [d] (a waiting part
     * then starts from a copy of that cache); before, also the units below the split across the
     * whole tile (its build), and the cache tile is then built by the waiting part itself
     * ([BelowRenderer.adopt]), never by a read on main.
     */
    private fun queueWaiting(compositor: Compositor, idx: Int, tr: Rect, d: Rect, below: BelowRenderer?, k: Int): Boolean {
        if (below == null || k <= 0) {
            if (!WaitingTiles.needsReads(compositor, d)) return false
            waitingTiles().queue(idx, tr, d, compositor)
            return true
        }
        val cache = below.builtCache(idx)
        if (cache != null) {
            if (!WaitingTiles.needsReads(compositor, d, from = k)) return false
            waitingTiles().queue(idx, tr, d, compositor, start = k, base = cache)
            return true
        }
        if (!WaitingTiles.needsReads(compositor, tr, until = k) && !WaitingTiles.needsReads(compositor, d, from = k)) return false
        waitingTiles().queue(idx, tr, d, compositor, cut = k) { jobTr, acc -> below.adopt(this, idx, jobTr, k, acc) }
        return true
    }

    // ------------------------------------------------------------------ v1.6: budgeted refinement (§3.1, §4.3)

    /** Number of tiles ([cols] × [rows]); tile indices are `row * cols + col`. */
    val tileCount: Int get() = cols * rows

    /** Index of the tile in column [col], row [row]. */
    fun tileIndexOf(col: Int, row: Int): Int = row * cols + col

    /** True while tile [index] has a part waiting to be rendered. */
    fun isDirty(index: Int): Boolean = dirty.getOrNull(index) != null

    /** Time source of [updateBudgeted] in nanoseconds (replaceable in tests). */
    internal var nanoClock: () -> Long = System::nanoTime

    /**
     * Renders dirty tiles that intersect [visibleDoc] (null = every dirty tile) nearest [center]
     * (document px; the distance to each tile's centre, ties in index order; null = index order)
     * first, until [budgetNanos] is spent — always at least one tile, so refinement always
     * progresses. Tiles for which [skip] (tile index) is true are left dirty and not counted.
     * [TileUpdate.hasMore]: dirty visible tiles not skipped are left. With [Long.MAX_VALUE] it
     * renders exactly the tiles [update] renders, with the same pixels.
     */
    fun updateBudgeted(
        compositor: Compositor,
        visibleDoc: Rect?,
        budgetNanos: Long,
        skip: ((Int) -> Boolean)? = null,
        center: PointF? = null,
    ): TileUpdate {
        if (dirtyCount == 0) return TileUpdate.of(changed = false, hasMore = false)
        var n = 0
        val tr = budgetRect
        for (idx in tiles.indices) {
            if (dirty[idx] == null) continue
            tileRect(idx % cols, idx / cols, tr)
            if (visibleDoc != null && !Rect.intersects(visibleDoc, tr)) continue
            if (skip != null && skip(idx)) continue
            if (center == null) { order[n++] = idx; continue }
            // Nearest first, ties in index order: a stable insertion by distance (the indices
            // arrive in increasing order, so an equal distance stays behind).
            val dx = tr.exactCenterX() - center.x
            val dy = tr.exactCenterY() - center.y
            val dist = dx * dx + dy * dy
            var k = n++
            while (k > 0 && orderDist[k - 1] > dist) {
                order[k] = order[k - 1]; orderDist[k] = orderDist[k - 1]; k--
            }
            order[k] = idx; orderDist[k] = dist
        }
        if (n == 0) return TileUpdate.of(changed = false, hasMore = false)
        val start = nanoClock()
        var done = 0
        var pos = 0
        while (pos < n) {
            val idx = order[pos++]
            if (dirty[idx] == null) continue
            tileRect(idx % cols, idx / cols, tr)
            render(compositor, idx, tr)
            done++
            if (budgetNanos != Long.MAX_VALUE && nanoClock() - start >= budgetNanos) break
        }
        var more = false
        while (pos < n) if (dirty[order[pos++]] != null) { more = true; break }
        return TileUpdate.of(changed = done > 0, hasMore = more)
    }

    /** The part of tile [index] waiting to be rendered (document px; a copy), or null when it is clean. */
    fun dirtyRect(index: Int): Rect? = dirty.getOrNull(index)?.let { Rect(it) }

    /**
     * Draws the tiles into a canvas whose matrix already maps document -> screen.
     * [visibleDoc] (document coords) is used to skip off-screen tiles; null draws all. v1.6:
     * tiles for which [skip] (tile index) is true are not drawn (a live session draws them).
     */
    fun draw(canvas: Canvas, visibleDoc: Rect?, smooth: Boolean = true, skip: ((Int) -> Boolean)? = null) {
        drawPaint.isFilterBitmap = smooth
        for (idx in tiles.indices) {
            val bmp = tiles[idx] ?: continue
            val col = idx % cols; val row = idx / cols
            val tr = tileRect(col, row, tmpRect)
            if (visibleDoc != null && !Rect.intersects(visibleDoc, tr)) continue
            if (skip != null && skip(idx)) continue
            canvas.drawBitmap(bmp, tr.left.toFloat(), tr.top.toFloat(), drawPaint)
        }
    }

    /** Draws tile [index] alone as [draw] does (nothing when it was never rendered). */
    fun drawTile(canvas: Canvas, index: Int, smooth: Boolean = true) {
        val bmp = tiles.getOrNull(index) ?: return
        drawPaint.isFilterBitmap = smooth
        val tr = tileRect(index % cols, index / cols, tmpRect)
        canvas.drawBitmap(bmp, tr.left.toFloat(), tr.top.toFloat(), drawPaint)
    }

    /**
     * v1.9 A6 (design 2.4.3): bytes the tile bitmaps hold now (the "display tiles" entry of the
     * MemoryGovernor; read from any thread).
     */
    @Volatile var heldBytes: Long = 0L
        private set

    /**
     * v1.9 A6: frees every tile bitmap under memory pressure and marks the whole document for
     * rendering, so the next [update] renders what is shown again, with the same pixels. Returns
     * the bytes freed. Main thread.
     */
    fun freeBitmaps(): Long {
        val freed = heldBytes
        release()
        if (freed > 0L) invalidate(null)
        return freed
    }

    fun release() {
        waiting?.cancelAll()
        for (i in tiles.indices) { tiles[i]?.recycle(); tiles[i] = null; canvases[i] = null; targets[i] = null }
        tileBytes.fill(0)
        heldBytes = 0L
    }
}
