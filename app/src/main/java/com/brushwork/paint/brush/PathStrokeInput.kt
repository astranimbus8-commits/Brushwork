package com.brushwork.paint.brush

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Input points of a stroke along a vector path (document px): positions and stylus pressures in
 * parallel arrays, so a long path costs no object per point. Reused from one replay to the next:
 * [clear], then [add].
 */
class PathStrokeInput(capacity: Int = 256) {
    var size = 0
        private set
    var x = FloatArray(capacity)
        private set
    var y = FloatArray(capacity)
        private set
    var pressure = FloatArray(capacity)
        private set

    fun clear() { size = 0 }

    /** Keeps only the first [n] points. */
    fun truncate(n: Int) { size = n.coerceIn(0, size) }

    fun add(px: Float, py: Float, p: Float) {
        if (size == x.size) {
            val n = max(16, size * 2)
            x = x.copyOf(n); y = y.copyOf(n); pressure = pressure.copyOf(n)
        }
        x[size] = px; y[size] = py; pressure[size] = p
        size++
    }

    /** Copies [other] into this input. */
    fun set(other: PathStrokeInput) {
        clear()
        for (i in 0 until other.size) add(other.x[i], other.y[i], other.pressure[i])
    }
}

/**
 * A document split into square cells of [cellSize] px with one flag per cell: the parts of a
 * path stroke that must be cleared / redrawn. Rows of flagged cells are handed out as runs
 * (maximal horizontal rectangles), which never overlap.
 */
internal class CellGrid(val docWidth: Int, val docHeight: Int, val cellSize: Int) {
    val cols = max(1, (docWidth + cellSize - 1) / cellSize)
    val rows = max(1, (docHeight + cellSize - 1) / cellSize)
    private val flags = BooleanArray(cols * rows)
    private var any = false

    val isEmpty: Boolean get() = !any

    fun isSet(col: Int, row: Int): Boolean = flags[row * cols + col]

    /** Flags every cell touched by [l, r) x [t, b) (document px, clipped to the document). */
    fun mark(l: Int, t: Int, r: Int, b: Int) {
        val cl = max(0, l); val ct = max(0, t)
        val cr = min(docWidth, r); val cb = min(docHeight, b)
        if (cr <= cl || cb <= ct) return
        for (row in ct / cellSize..(cb - 1) / cellSize) {
            val base = row * cols
            for (col in cl / cellSize..(cr - 1) / cellSize) flags[base + col] = true
        }
        any = true
    }

    fun mark(r: Rect) = mark(r.left, r.top, r.right, r.bottom)

    /** Rectangle of cells [c0, c1] in [row], clipped to the document, into [out]. */
    fun runRect(row: Int, c0: Int, c1: Int, out: Rect): Rect {
        out.set(c0 * cellSize, row * cellSize, min(docWidth, (c1 + 1) * cellSize), min(docHeight, (row + 1) * cellSize))
        return out
    }

    /** Calls [block] with every maximal run of flagged cells (row, first col, last col). */
    inline fun forEachRun(block: (row: Int, c0: Int, c1: Int) -> Unit) {
        if (isEmpty) return
        for (row in 0 until rows) {
            var col = 0
            while (col < cols) {
                if (!isSet(col, row)) { col++; continue }
                val start = col
                while (col + 1 < cols && isSet(col + 1, row)) col++
                block(row, start, col)
                col++
            }
        }
    }

    /**
     * Calls [block] with the runs of flagged cells within columns [c0, c1] of [row] (the part of
     * a dab's box that lies in flagged cells).
     */
    inline fun forEachRunIn(row: Int, c0: Int, c1: Int, block: (a: Int, b: Int) -> Unit) {
        var col = c0
        while (col <= c1) {
            if (!isSet(col, row)) { col++; continue }
            val start = col
            while (col + 1 <= c1 && isSet(col + 1, row)) col++
            block(start, col)
            col++
        }
    }

    fun clear() {
        if (!any) return
        flags.fill(false)
        any = false
    }
}
