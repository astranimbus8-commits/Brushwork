package com.brushwork.paint.vector.geom

import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.vector.StrokeCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VStroke
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A set of [tile] px squares of the document grid (v1.5 §4.9c: dirty regions are tile sets, not
 * bounding boxes): deleting two distant objects re-renders their tiles only, a long diagonal
 * stroke the tiles along it. Re-rendering whole grid tiles gives exactly a full render's pixels
 * there (`VectorLayerRenderer.TILE`), so the cache stays a fresh rendering of the data.
 *
 * Tiles are clipped to the document ([docW] x [docH]); [rects] merges them into few rectangles.
 * Not thread-safe (build it on one thread, then read it anywhere).
 */
class TileSet(val docW: Int, val docH: Int, val tile: Int) {
    val cols: Int = max(0, (docW + tile - 1) / tile)
    val rows: Int = max(0, (docH + tile - 1) / tile)
    private val bits = BooleanArray(cols * rows)

    /** Number of tiles in the set. */
    var count: Int = 0
        private set

    val isEmpty: Boolean get() = count == 0

    operator fun contains(cr: Pair<Int, Int>): Boolean = has(cr.first, cr.second)

    fun has(col: Int, row: Int): Boolean = col in 0 until cols && row in 0 until rows && bits[row * cols + col]

    private fun set(col: Int, row: Int) {
        val i = row * cols + col
        if (!bits[i]) { bits[i] = true; count++ }
    }

    /** Adds every tile the pixels of [left]..[right] x [top]..[bottom] (document px, rounded out) touch. */
    fun addRect(left: Float, top: Float, right: Float, bottom: Float) {
        if (cols == 0 || rows == 0) return
        if (!(left < right && top < bottom)) return
        val l = floorPx(left); val t = floorPx(top)
        val r = ceilPx(right); val b = ceilPx(bottom)
        if (r <= 0 || b <= 0 || l >= docW || t >= docH) return
        val c0 = max(0, l) / tile
        val c1 = (min(docW, r) - 1) / tile
        val r0 = max(0, t) / tile
        val r1 = (min(docH, b) - 1) / tile
        for (row in r0..r1) for (col in c0..c1) set(col, row)
    }

    fun addRect(r: RectF) = addRect(r.left, r.top, r.right, r.bottom)

    fun addRect(r: Rect) = addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())

    /** Adds every tile of the document. */
    fun addAll() {
        for (row in 0 until rows) for (col in 0 until cols) set(col, row)
    }

    /** Adds every tile of [other] (same grid). */
    fun addAll(other: TileSet) {
        require(other.cols == cols && other.rows == rows && other.tile == tile)
        for (i in bits.indices) if (other.bits[i] && !bits[i]) { bits[i] = true; count++ }
    }

    /**
     * Adds the tiles [o] can paint: a stroke's tiles along its points, any other object's paint
     * [bounds]. The stroke sampler smooths the input into quadratic curves from midpoint to
     * midpoint with the input points as control points (a straight piece from the first point
     * and to the last), each inside the triangle of its control points: those triangles' boxes,
     * grown by the brush reach, hold everything the stroke paints. v1.7: a stroke with symmetry
     * copies adds the tiles of each copy ([StrokeCopies.expanded]).
     */
    fun addObject(o: VObject, bounds: RectF) {
        if (bounds.isEmpty) return
        if (o is VStroke && o.copies.isNotEmpty()) {
            for (c in StrokeCopies.expanded(o)) addObject(c, StrokeRaster.strokeBounds(c.preset, c.sizeScale, c.points))
            return
        }
        val n = (o as? VStroke)?.points?.size ?: 0
        if (o !is VStroke || n < 2) { addRect(bounds); return }
        val xs = o.points.x; val ys = o.points.y
        for (i in 0 until n) if (!(xs[i].isFinite() && ys[i].isFinite())) { addRect(bounds); return }
        val e = StrokeRaster.reach(o.preset, o.sizeScale)
        for (i in 0 until n) {
            // From the midpoint before point i (or the point itself) to the midpoint after it.
            val ax = if (i > 0) (xs[i - 1] + xs[i]) / 2f else xs[i]
            val ay = if (i > 0) (ys[i - 1] + ys[i]) / 2f else ys[i]
            val cx = if (i < n - 1) (xs[i] + xs[i + 1]) / 2f else xs[i]
            val cy = if (i < n - 1) (ys[i] + ys[i + 1]) / 2f else ys[i]
            val l = min(ax, min(xs[i], cx)); val r = max(ax, max(xs[i], cx))
            val t = min(ay, min(ys[i], cy)); val b = max(ay, max(ys[i], cy))
            addRect(l - e, t - e, r + e, b + e)
        }
    }

    /** The tile at ([col], [row]) in document px, clipped to the document. */
    fun tileRect(col: Int, row: Int, out: Rect = Rect()): Rect {
        out.set(col * tile, row * tile, min(docW, (col + 1) * tile), min(docH, (row + 1) * tile))
        return out
    }

    /** Bounding box of the set (document px; empty when the set is). */
    fun bounds(): Rect {
        val out = Rect()
        val t = Rect()
        for (row in 0 until rows) for (col in 0 until cols) if (bits[row * cols + col]) out.union(tileRect(col, row, t))
        return out
    }

    /** Pixels covered (clipped to the document). */
    fun area(): Long {
        var a = 0L
        val t = Rect()
        for (row in 0 until rows) for (col in 0 until cols) if (bits[row * cols + col]) {
            tileRect(col, row, t)
            a += t.width().toLong() * t.height()
        }
        return a
    }

    /**
     * The set as rectangles (document px, clipped to the document, disjoint): runs of tiles in
     * a row, merged with the identical runs of the rows below.
     */
    fun rects(): List<Rect> {
        val out = ArrayList<Rect>()
        // Open rects by their column span (c0 shl 16 or c1).
        var open = HashMap<Int, Rect>()
        for (row in 0 until rows) {
            val next = HashMap<Int, Rect>()
            var col = 0
            while (col < cols) {
                if (!bits[row * cols + col]) { col++; continue }
                val c0 = col
                while (col < cols && bits[row * cols + col]) col++
                val c1 = col - 1
                val k = (c0 shl 16) or c1
                val prev = open.remove(k)
                if (prev != null) {
                    prev.bottom = min(docH, (row + 1) * tile)
                    next[k] = prev
                } else {
                    next[k] = Rect(c0 * tile, row * tile, min(docW, (c1 + 1) * tile), min(docH, (row + 1) * tile))
                }
            }
            out += open.values
            open = next
        }
        out += open.values
        out.sortWith(compareBy({ it.top }, { it.left }))
        return out
    }

    private fun floorPx(v: Float): Int = floor(v.coerceIn(-1e9f, 1e9f)).toInt()
    private fun ceilPx(v: Float): Int = ceil(v.coerceIn(-1e9f, 1e9f)).toInt()

    companion object {
        /** The tiles covering [r] (document px). */
        fun of(docW: Int, docH: Int, tile: Int, rects: List<Rect>): TileSet = TileSet(docW, docH, tile).also { s -> rects.forEach { s.addRect(it) } }
    }
}
