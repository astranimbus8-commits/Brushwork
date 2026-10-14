package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
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
    /** The part of each tile waiting to be rendered; meaningful only where [isDirtyAt] is set. */
    private val dirty = Array(cols * rows) { Rect() }
    private val isDirtyAt = BooleanArray(cols * rows)
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
     * Marks [rect] (document coords; null = everything) for re-rendering. The [invalidationHook]
     * gets a scratch rect valid during the call only (its users copy what they keep).
     */
    fun invalidate(rect: Rect?) {
        val r = invRect
        if (rect == null) r.set(0, 0, docWidth, docHeight) else r.set(rect)
        if (!r.intersect(0, 0, docWidth, docHeight)) return
        val left = r.left; val top = r.top; val right = r.right; val bottom = r.bottom
        invalidationHook?.invoke(r)
        val c0 = left / tileSize; val c1 = (right - 1) / tileSize
        val r0 = top / tileSize; val r1 = (bottom - 1) / tileSize
        for (row in r0..r1) for (col in c0..c1) {
            val idx = row * cols + col
            tileRect(col, row, tmpRect)
            val part = partRect
            part.set(left, top, right, bottom)
            if (!part.intersect(tmpRect)) continue
            if (isDirtyAt[idx]) dirty[idx].union(part)
            else { dirty[idx].set(part); isDirtyAt[idx] = true; dirtyCount++ }
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
            if (!isDirtyAt[idx]) continue
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
        d.set(dirty[idx])
        isDirtyAt[idx] = false
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
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        compositor.drawDocument(c, d, target = target)
        c.restoreToCount(save)
    }

    // ------------------------------------------------------------------ v1.6: budgeted refinement (§3.1, §4.3)

    /** Number of tiles ([cols] × [rows]); tile indices are `row * cols + col`. */
    val tileCount: Int get() = cols * rows

    /** Index of the tile in column [col], row [row]. */
    fun tileIndexOf(col: Int, row: Int): Int = row * cols + col

    /** True while tile [index] has a part waiting to be rendered. */
    fun isDirty(index: Int): Boolean = index in isDirtyAt.indices && isDirtyAt[index]

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
            if (!isDirtyAt[idx]) continue
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
            if (!isDirtyAt[idx]) continue
            tileRect(idx % cols, idx / cols, tr)
            render(compositor, idx, tr)
            done++
            if (budgetNanos != Long.MAX_VALUE && nanoClock() - start >= budgetNanos) break
        }
        var more = false
        while (pos < n) if (isDirtyAt[order[pos++]]) { more = true; break }
        return TileUpdate.of(changed = done > 0, hasMore = more)
    }

    /** The part of tile [index] waiting to be rendered (document px; a copy), or null when it is clean. */
    fun dirtyRect(index: Int): Rect? = if (isDirty(index)) Rect(dirty[index]) else null

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
        for (i in tiles.indices) { tiles[i]?.recycle(); tiles[i] = null; canvases[i] = null; targets[i] = null }
        tileBytes.fill(0)
        heldBytes = 0L
    }
}
