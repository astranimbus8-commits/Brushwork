package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.floor
import kotlin.math.min

/**
 * Mosaic: divides the layer into square cells and fills each with the cell's average colour.
 * The average is alpha-weighted (premultiplied), so a cell half covered by red line art becomes
 * half-transparent red rather than dark. Cell sizes may be fractional (preview scale), in which
 * case cells alternate between neighbouring integer widths.
 */
class MosaicFilter : Filter("blur.mosaic", "Mosaic", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("size", "Cell size", 1f, 300f, 16f, step = 1f, pixels = true),
        FilterParam.Choice("grid", "Grid", listOf("From top left", "Centered")),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val cell = ctx.px(values.float("size"))
        if (!(cell > 1.0001f)) return src.copy()
        val w = src.width
        val h = src.height
        val centered = values.choice("grid") == 1
        // Cell boundaries: centred mode puts one cell's centre on the image centre.
        val colStart = cellStarts(w, cell, if (centered) w / 2f - cell / 2f else 0f)
        val rowStart = cellStarts(h, cell, if (centered) h / 2f - cell / 2f else 0f)
        val nx = colStart.size - 1
        val ny = rowStart.size - 1
        val colCell = IntArray(w)
        for (gx in 0 until nx) for (x in colStart[gx] until colStart[gx + 1]) colCell[x] = gx

        val s = src.pixels
        val out = PixelBuffer(w, h)
        val d = out.pixels
        Parallel.forRange(ny, 1) { gy0, gy1 ->
            val sums = LongArray(nx * 4)
            val colors = IntArray(nx)
            for (gy in gy0 until gy1) {
                ctx.checkCancelled()
                sums.fill(0L)
                val ya = rowStart[gy]
                val yb = rowStart[gy + 1]
                for (y in ya until yb) {
                    val row = y * w
                    for (x in 0 until w) {
                        val c = s[row + x]
                        val a = (c ushr 24).toLong()
                        if (a == 0L) continue
                        val k = colCell[x] * 4
                        sums[k] += a
                        sums[k + 1] += ((c shr 16) and 0xFF) * a
                        sums[k + 2] += ((c shr 8) and 0xFF) * a
                        sums[k + 3] += (c and 0xFF) * a
                    }
                }
                val cellH = (yb - ya).toLong()
                for (gx in 0 until nx) {
                    val k = gx * 4
                    val sa = sums[k]
                    val count = cellH * (colStart[gx + 1] - colStart[gx])
                    val alpha = ((sa + count / 2) / count).toInt()
                    colors[gx] = if (alpha <= 0) 0 else {
                        val half = sa / 2
                        (min(255, alpha) shl 24) or
                            (((sums[k + 1] + half) / sa).toInt() shl 16) or
                            (((sums[k + 2] + half) / sa).toInt() shl 8) or
                            ((sums[k + 3] + half) / sa).toInt()
                    }
                }
                for (y in ya until yb) {
                    val row = y * w
                    for (x in 0 until w) d[row + x] = colors[colCell[x]]
                }
            }
        }
        return out
    }

    /**
     * Start index of every cell along an axis of [n] pixels for cells of size [cell] whose grid
     * passes through [origin]; the last entry is [n]. A pixel belongs to the cell containing its
     * centre, and edge cells are clipped to the image.
     */
    private fun cellStarts(n: Int, cell: Float, origin: Float): IntArray {
        val first = floor((0.5f - origin) / cell).toInt()
        val last = floor((n - 0.5f - origin) / cell).toInt()
        val starts = IntArray(last - first + 2)
        var idx = 0
        var prev = Int.MIN_VALUE
        for (px in 0 until n) {
            val c = floor((px + 0.5f - origin) / cell).toInt()
            if (c != prev) { starts[idx++] = px; prev = c }
        }
        starts[idx] = n
        return if (idx + 1 == starts.size) starts else starts.copyOf(idx + 1)
    }
}
