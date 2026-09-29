package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.core.Parallel
import kotlin.math.max
import kotlin.math.min

/**
 * Tight bounds of a layer's content: pixels with non-zero alpha, or (for layer masks) pixels
 * that differ from the mask's background value. Reads the bitmap in strips so no full-size
 * copy is made; safe to call off the main thread.
 */
object ContentBounds {
    /** Pixels read per strip (bounded memory per worker). */
    private const val STRIP_PIXELS = 1 shl 18

    /**
     * Bounds of [bitmap]'s content, or null when empty. [emptyColor] null = "transparent is
     * empty" (also works for ALPHA_8 bitmaps); otherwise pixels equal to it (ARGB, as returned by
     * getPixels) are empty. Only [region] (clamped to the bitmap; null = everything) is scanned.
     * [cancelled] is polled between strips.
     */
    fun of(bitmap: Bitmap, emptyColor: Int? = null, region: Rect? = null, cancelled: () -> Boolean = { false }): Rect? {
        val area = Rect(0, 0, bitmap.width, bitmap.height)
        if (region != null && !area.intersect(region)) return null
        val w = area.width()
        val h = area.height()
        if (w <= 0 || h <= 0) return null
        val stripRows = max(1, min(h, STRIP_PIXELS / w))
        val strips = (h + stripRows - 1) / stripRows
        val alphaMode = emptyColor == null
        val empty = emptyColor ?: 0
        val total = newAccumulator(w, h)
        val lock = Any()
        Parallel.forRange(strips, 1) { s0, s1 ->
            val buf = IntArray(stripRows * w)
            val acc = newAccumulator(w, h)
            for (s in s0 until s1) {
                if (cancelled()) break
                val y0 = s * stripRows
                val rows = min(stripRows, h - y0)
                bitmap.getPixels(buf, 0, w, area.left, area.top + y0, w, rows)
                scanStrip(buf, w, rows, y0, alphaMode, empty, acc)
            }
            synchronized(lock) { merge(total, acc) }
        }
        if (total[2] < 0) return null
        return Rect(total[0], total[1], total[2] + 1, total[3] + 1).apply { offset(area.left, area.top) }
    }

    /** [minX, minY, maxX, maxY] (inclusive), "nothing found" = maxX < 0. */
    fun newAccumulator(width: Int, height: Int): IntArray = intArrayOf(width, height, -1, -1)

    fun merge(into: IntArray, from: IntArray) {
        if (from[2] < 0) return
        if (from[0] < into[0]) into[0] = from[0]
        if (from[1] < into[1]) into[1] = from[1]
        if (from[2] > into[2]) into[2] = from[2]
        if (from[3] > into[3]) into[3] = from[3]
    }

    /**
     * Accumulates the bounds of non-empty pixels in [rows] rows of [buf] (row stride [width])
     * that start at document row [y0].
     */
    fun scanStrip(buf: IntArray, width: Int, rows: Int, y0: Int, alphaMode: Boolean, emptyColor: Int, acc: IntArray) {
        for (r in 0 until rows) {
            val base = r * width
            var left = -1
            var x = 0
            if (alphaMode) {
                while (x < width) { if ((buf[base + x] ushr 24) != 0) { left = x; break }; x++ }
            } else {
                while (x < width) { if (buf[base + x] != emptyColor) { left = x; break }; x++ }
            }
            if (left < 0) continue
            var right = left
            x = width - 1
            if (alphaMode) {
                while (x > left) { if ((buf[base + x] ushr 24) != 0) { right = x; break }; x-- }
            } else {
                while (x > left) { if (buf[base + x] != emptyColor) { right = x; break }; x-- }
            }
            val y = y0 + r
            if (left < acc[0]) acc[0] = left
            if (y < acc[1]) acc[1] = y
            if (right > acc[2]) acc[2] = right
            if (y > acc[3]) acc[3] = y
        }
    }

    /** Most common of [values] (ties go to the earliest); used to find a mask's background. */
    fun majority(values: IntArray): Int {
        var best = values[0]
        var bestCount = 0
        for (v in values) {
            val count = values.count { it == v }
            if (count > bestCount) { best = v; bestCount = count }
        }
        return best
    }
}
