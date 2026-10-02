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
data class TileUpdate(val changed: Boolean, val hasMore: Boolean)

/**
 * The on-screen composite, split into tiles so that a brush stroke only re-renders (and the GPU
 * only re-uploads) the tiles it touched. Not thread-safe: use from the main thread.
 */
class DisplayTiles(val docWidth: Int, val docHeight: Int, val tileSize: Int = 512) {
    val cols = (docWidth + tileSize - 1) / tileSize
    val rows = (docHeight + tileSize - 1) / tileSize
    private val tiles = arrayOfNulls<Bitmap>(cols * rows)
    private val dirty = arrayOfNulls<Rect>(cols * rows)
    private val tmpRect = Rect()
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init { invalidate(null) }

    fun tileRect(col: Int, row: Int, out: Rect = Rect()): Rect {
        val l = col * tileSize; val t = row * tileSize
        out.set(l, t, min(docWidth, l + tileSize), min(docHeight, t + tileSize))
        return out
    }

    /** Marks [rect] (document coords; null = everything) for re-rendering. */
    fun invalidate(rect: Rect?) {
        val r = if (rect == null) Rect(0, 0, docWidth, docHeight) else Rect(rect)
        if (!r.intersect(0, 0, docWidth, docHeight)) return
        val c0 = r.left / tileSize; val c1 = (r.right - 1) / tileSize
        val r0 = r.top / tileSize; val r1 = (r.bottom - 1) / tileSize
        for (row in r0..r1) for (col in c0..c1) {
            val idx = row * cols + col
            tileRect(col, row, tmpRect)
            val part = Rect(r)
            if (!part.intersect(tmpRect)) continue
            val d = dirty[idx]
            if (d == null) dirty[idx] = part else d.union(part)
        }
    }

    val hasDirty: Boolean get() = dirty.any { it != null }

    /**
     * Re-renders dirty tiles with [compositor]. Returns true if anything changed. [visibleDoc]
     * (document px; v1.5) is the area on screen: only dirty tiles that intersect it are rendered,
     * the others stay dirty until they become visible (a full-canvas adjustment slider drag then
     * only pays for what is on screen). Null renders every dirty tile.
     */
    fun update(compositor: Compositor, visibleDoc: Rect? = null): Boolean {
        var changed = false
        for (idx in tiles.indices) {
            val d = dirty[idx] ?: continue
            val col = idx % cols; val row = idx / cols
            val tr = tileRect(col, row)
            if (visibleDoc != null && !Rect.intersects(visibleDoc, tr)) continue
            render(compositor, idx, d, tr)
            changed = true
        }
        return changed
    }

    /** Renders the dirty part [d] of tile [idx] (document rect [tr]) and marks it clean. */
    private fun render(compositor: Compositor, idx: Int, d: Rect, tr: Rect) {
        dirty[idx] = null
        var bmp = tiles[idx]
        if (bmp == null || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(tr.width(), tr.height(), Bitmap.Config.ARGB_8888)
            bmp.setHasMipMap(true)
            tiles[idx] = bmp
        }
        val c = Canvas(bmp)
        c.translate(-tr.left.toFloat(), -tr.top.toFloat())
        c.clipRect(d)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        compositor.drawDocument(c, d, target = CompositeTarget.displayTile(bmp, tr.left, tr.top))
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
        var n = 0
        val order = IntArray(tiles.size)
        val tr = Rect()
        for (idx in tiles.indices) {
            if (dirty[idx] == null) continue
            tileRect(idx % cols, idx / cols, tr)
            if (visibleDoc != null && !Rect.intersects(visibleDoc, tr)) continue
            if (skip != null && skip(idx)) continue
            order[n++] = idx
        }
        if (n == 0) return TileUpdate(changed = false, hasMore = false)
        val sorted: List<Int> = if (center == null) order.take(n) else {
            val cx = center.x; val cy = center.y
            fun dist2(idx: Int): Float {
                val r = tileRect(idx % cols, idx / cols)
                val dx = r.exactCenterX() - cx
                val dy = r.exactCenterY() - cy
                return dx * dx + dy * dy
            }
            order.take(n).sortedWith(compareBy<Int>({ dist2(it) }, { it }))
        }
        val start = nanoClock()
        var done = 0
        for (idx in sorted) {
            val d = dirty[idx] ?: continue
            render(compositor, idx, d, tileRect(idx % cols, idx / cols))
            done++
            if (budgetNanos != Long.MAX_VALUE && nanoClock() - start >= budgetNanos) break
        }
        val more = sorted.drop(done).any { dirty[it] != null }
        return TileUpdate(changed = done > 0, hasMore = more)
    }

    /**
     * Draws the tiles into a canvas whose matrix already maps document -> screen.
     * [visibleDoc] (document coords) is used to skip off-screen tiles; null draws all.
     */
    fun draw(canvas: Canvas, visibleDoc: Rect?, smooth: Boolean = true) {
        drawPaint.isFilterBitmap = smooth
        for (idx in tiles.indices) {
            val bmp = tiles[idx] ?: continue
            val col = idx % cols; val row = idx / cols
            val tr = tileRect(col, row, tmpRect)
            if (visibleDoc != null && !Rect.intersects(visibleDoc, tr)) continue
            canvas.drawBitmap(bmp, tr.left.toFloat(), tr.top.toFloat(), drawPaint)
        }
    }

    fun release() {
        for (i in tiles.indices) { tiles[i]?.recycle(); tiles[i] = null }
    }
}
