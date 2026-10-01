package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import kotlin.math.min

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
     * (document px; v1.5) is the area on screen: tiles there may be rendered first and the
     * others left dirty until they become visible (owned by A5; currently every dirty tile is
     * rendered).
     */
    fun update(compositor: Compositor, visibleDoc: Rect? = null): Boolean {
        var changed = false
        for (idx in tiles.indices) {
            val d = dirty[idx] ?: continue
            dirty[idx] = null
            val col = idx % cols; val row = idx / cols
            val tr = tileRect(col, row)
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
            compositor.drawDocument(c, d, target = CompositeTarget.translate(bmp, tr.left, tr.top))
            changed = true
        }
        return changed
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
