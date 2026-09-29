package com.brushwork.paint.tools.select

import com.brushwork.paint.core.Parallel
import kotlin.math.max
import kotlin.math.min

/**
 * Exact Euclidean distance transform limited to a maximum radius, used for grow / shrink /
 * expand / gap closing. Pure Kotlin (JVM-testable) and memory-light: besides the caller's data it
 * needs one byte per pixel of the working window (capped vertical distances) plus per-thread
 * row buffers, so it runs on 4000x5000 canvases without int/float planes.
 */
internal object Distance {
    /** Squared distance reported for pixels farther than the radius from every source pixel. */
    const val FAR: Int = Int.MAX_VALUE

    /** Largest supported radius (vertical distances are stored in one byte). */
    const val MAX_RADIUS: Int = 254

    /** Source predicate on a packed row-major pixel index (`y * width + x`). */
    fun interface Source {
        fun test(index: Int): Boolean
    }

    /**
     * Receives the squared distances of one output row: `d2[i]` belongs to pixel (x0 + i, y).
     * Called concurrently for different rows; the array is only valid during the call.
     */
    fun interface RowSink {
        fun accept(y: Int, d2: IntArray)
    }

    /**
     * For every pixel of the window [x0, x1) x [y0, y1) of a [width] x [height] image, reports the
     * squared Euclidean distance (between pixel centers) to the nearest pixel for which [source]
     * is true, or [FAR] when that distance exceeds [maxRadius]. Pixels outside the image are never
     * sources. Values <= maxRadius² are exact.
     */
    fun bounded(
        width: Int, height: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        maxRadius: Int,
        source: Source,
        sink: RowSink,
        cancelled: () -> Boolean = { false },
    ) {
        require(maxRadius in 0..MAX_RADIUS) { "radius $maxRadius out of range" }
        val wx0 = max(0, x0); val wy0 = max(0, y0)
        val wx1 = min(width, x1); val wy1 = min(height, y1)
        if (wx0 >= wx1 || wy0 >= wy1) return
        val r = maxRadius
        val cap = r + 1
        // Columns that can influence the window, and rows that can influence those columns.
        val cx0 = max(0, wx0 - r); val cx1 = min(width, wx1 + r)
        val sy0 = max(0, wy0 - r); val sy1 = min(height, wy1 + r)
        val cw = cx1 - cx0
        val wh = wy1 - wy0
        val col = ByteArray(cw * wh) // capped vertical distance, window rows x influencing columns

        // Vertical pass, row-major for cache locality; parallel over column strips.
        Parallel.forRange(cw, 16) { a, b ->
            val n = b - a
            val last = IntArray(n) { Int.MIN_VALUE / 2 }
            for (y in sy0 until sy1) {
                if (cancelled()) return@forRange
                val rowIdx = y * width + cx0
                val inWin = y >= wy0 && y < wy1
                val out = (y - wy0) * cw
                for (k in 0 until n) {
                    val c = a + k
                    if (source.test(rowIdx + c)) last[k] = y
                    if (inWin) {
                        val d = y - last[k]
                        col[out + c] = (if (d > cap) cap else d).toByte()
                    }
                }
            }
            val next = IntArray(n) { Int.MAX_VALUE / 2 }
            for (y in sy1 - 1 downTo sy0) {
                if (cancelled()) return@forRange
                val rowIdx = y * width + cx0
                val inWin = y >= wy0 && y < wy1
                val out = (y - wy0) * cw
                for (k in 0 until n) {
                    val c = a + k
                    if (source.test(rowIdx + c)) next[k] = y
                    if (inWin) {
                        val d = next[k] - y
                        val cur = col[out + c].toInt() and 0xFF
                        if (d < cur) col[out + c] = d.toByte()
                    }
                }
            }
        }
        if (cancelled()) return

        // Horizontal pass: lower envelope of parabolas (Felzenszwalb & Huttenlocher) per row.
        val ww = wx1 - wx0
        val r2 = r.toLong() * r
        Parallel.forRange(wh, 4) { ra, rb ->
            val v = IntArray(cw)
            val z = DoubleArray(cw + 1)
            val f = IntArray(cw)
            val d2 = IntArray(ww)
            for (ry in ra until rb) {
                if (cancelled()) return@forRange
                val off = ry * cw
                var k = -1
                for (q in 0 until cw) {
                    val g = col[off + q].toInt() and 0xFF
                    if (g >= cap) continue
                    val fq = g * g
                    f[q] = fq
                    if (k < 0) {
                        k = 0; v[0] = q; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY
                        continue
                    }
                    var s: Double
                    while (true) {
                        val vk = v[k]
                        s = ((fq + q.toLong() * q) - (f[vk] + vk.toLong() * vk)).toDouble() / (2.0 * (q - vk))
                        if (s <= z[k]) k-- else break
                    }
                    k++
                    v[k] = q; z[k] = s; z[k + 1] = Double.POSITIVE_INFINITY
                }
                if (k < 0) {
                    d2.fill(FAR)
                } else {
                    var j = 0
                    for (i in 0 until ww) {
                        val x = (wx0 + i - cx0).toDouble()
                        while (z[j + 1] < x) j++
                        val dx = (wx0 + i - cx0 - v[j]).toLong()
                        val dd = dx * dx + f[v[j]]
                        d2[i] = if (dd > r2) FAR else dd.toInt()
                    }
                }
                sink.accept(wy0 + ry, d2)
            }
        }
    }
}
