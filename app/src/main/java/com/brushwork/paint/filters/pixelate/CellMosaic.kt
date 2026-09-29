package com.brushwork.paint.filters.pixelate

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Cell averaging shared by the pixelate filters. Memory stays bounded for 12+ MP images: the
 * per-pixel cell labels live in the output buffer's own array and per-cell sums are accumulated
 * in locals (one pass over each cell's bounding box), so the only extra allocation is one color
 * per cell.
 */
internal object CellMosaic {

    /** Writes the index of the cell containing each pixel center into [labels] (size w*h). */
    fun label(lattice: CellLattice, width: Int, height: Int, labels: IntArray, ctx: FilterContext) {
        Parallel.forRows(height) { y0, y1 ->
            for (y in y0 until y1) {
                if (((y - y0) and 15) == 0) ctx.checkCancelled()
                val py = y + 0.5f
                val row = y * width
                for (x in 0 until width) labels[row + x] = lattice.cellAt(x + 0.5f, py, null)
            }
        }
    }

    /**
     * Alpha-weighted average color (non-premultiplied ARGB) of every cell, given the pixel
     * [labels] from [label]. The alpha is the plain average, so transparent pixels thin out a
     * cell. Cells that contain no pixel get 0.
     */
    fun averages(src: PixelBuffer, lattice: CellLattice, labels: IntArray, ctx: FilterContext): IntArray {
        val w = src.width; val h = src.height; val px = src.pixels
        val colors = IntArray(lattice.cellCount)
        val reach = lattice.boundRadius + 1f
        Parallel.forRange(lattice.rowCount, 1) { r0, r1 ->
            val c = FloatArray(2)
            for (row in r0 until r1) {
                ctx.checkCancelled()
                for (cell in lattice.rowStart(row) until lattice.rowStart(row + 1)) {
                    lattice.centerOf(row, cell, c)
                    val x0 = max(0, floor(c[0] - reach).toInt()); val x1 = min(w - 1, ceil(c[0] + reach).toInt())
                    if (x0 > x1) continue
                    val y0 = max(0, floor(c[1] - reach).toInt()); val y1 = min(h - 1, ceil(c[1] + reach).toInt())
                    if (y0 > y1) continue
                    var n = 0; var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
                    for (y in y0..y1) {
                        var i = y * w + x0
                        val end = y * w + x1
                        while (i <= end) {
                            if (labels[i] == cell) {
                                n++
                                val p = px[i]
                                val a = p ushr 24
                                if (a != 0) {
                                    sa += a
                                    sr += ((p shr 16) and 0xFF) * a
                                    sg += ((p shr 8) and 0xFF) * a
                                    sb += (p and 0xFF) * a
                                }
                            }
                            i++
                        }
                    }
                    colors[cell] = averageColor(n, sa, sr, sg, sb)
                }
            }
        }
        return colors
    }

    /** Every pixel takes the average color of its cell. */
    fun pixelate(src: PixelBuffer, lattice: CellLattice, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val out = PixelBuffer(w, h)
        val labels = out.pixels
        label(lattice, w, h, labels, ctx)
        val colors = averages(src, lattice, labels, ctx)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (i in y0 * w until y1 * w) labels[i] = colors[labels[i]]
        }
        return out
    }

    /**
     * Average of [n] pixels whose alpha sum is [sa] and whose alpha-weighted channel sums are
     * [sr], [sg], [sb].
     */
    fun averageColor(n: Int, sa: Long, sr: Long, sg: Long, sb: Long): Int {
        if (n <= 0 || sa <= 0L) return 0
        val a = ((sa + n / 2) / n).toInt()
        if (a == 0) return 0
        val half = sa / 2
        return ColorUtils.argbUnchecked(
            min(255, a),
            min(255L, (sr + half) / sa).toInt(),
            min(255L, (sg + half) / sa).toInt(),
            min(255L, (sb + half) / sa).toInt(),
        )
    }
}

/**
 * Source-over of the color [rgb] (its alpha is ignored) at opacity [a] (0..1) onto the
 * non-premultiplied pixel [dst].
 */
internal fun blendOver(dst: Int, rgb: Int, a: Float): Int {
    if (a <= 0f) return dst
    if (a >= 1f) return rgb or -0x1000000
    val da = (dst ushr 24) * (1f / 255f) * (1f - a)
    val oa = a + da
    val inv = 1f / oa
    val r = (((rgb shr 16) and 0xFF) * a + ((dst shr 16) and 0xFF) * da) * inv
    val g = (((rgb shr 8) and 0xFF) * a + ((dst shr 8) and 0xFF) * da) * inv
    val b = ((rgb and 0xFF) * a + (dst and 0xFF) * da) * inv
    return ColorUtils.argb((oa * 255f + 0.5f).toInt(), (r + 0.5f).toInt(), (g + 0.5f).toInt(), (b + 0.5f).toInt())
}
