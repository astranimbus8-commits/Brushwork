package com.brushwork.paint.filters

import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.model.ColorMode
import kotlin.math.max
import kotlin.math.min

/**
 * v1.9 (A7b step 3, R9, design 3.2 A7b): a filter apply in bands, for the local filters.
 *
 * v1.8's apply read the whole layer into one buffer and wrote the filter's whole result into a
 * second one: 8 bytes a pixel on the Java heap, 128 MB for a 4000 x 4000 layer. A local filter (a
 * pointwise one: its [PixelMapper] maps each pixel exactly like [Filter.apply] does, which
 * `PixelMapperContractTest` holds every such filter to) needs no neighbours, so [run] reads the
 * region it applies to (the selection's bounds, else the layer) a band of rows at a time, maps the
 * band, and composes it with the same [FilterSessionMath] rules and the same colour-mode constraint
 * as the whole-image path; only the region's result stays (4 bytes a pixel), plus two bands.
 * The result, the changed bounds and so the committed pixels are byte-identical to the whole-image
 * path (`FilterTilingEqualsWholeTest`).
 *
 * Filters that need their neighbours (the blurs, distortions, content-dependent ones) keep the
 * whole-image path; `MemoryGovernor.admit` checks them before they start.
 */
object FilterTiling {
    /** Pixels in one band (about 4 MB a band buffer). */
    const val BAND_PIXELS = 1 shl 20

    /** Rows in one band at most. */
    const val MAX_BAND_ROWS = 256

    /** Test seam: false runs every filter on the whole image (v1.8's path). */
    @Volatile
    internal var enabled = true

    /** Test seam: the pixels in one band ([BAND_PIXELS]); a test makes it small for many bands. */
    @Volatile
    internal var bandPixels = BAND_PIXELS

    /** Test seam: the bands [run] has applied in this process (a test sees which path ran). */
    @Volatile
    internal var bandsRun = 0L

    /**
     * What an apply produced: [pixels] holds the result for [region] (row stride [region]'s
     * width); [changed] (inside [region]) is what differs from the layer, null when nothing does.
     */
    class Result(val pixels: IntArray, val region: PixelRect, val changed: PixelRect?) {
        /** Offset in [pixels] of the document pixel ([x], [y]) inside [region]. */
        fun offsetOf(x: Int, y: Int): Int = (y - region.top) * region.width + (x - region.left)

        companion object {
            /** A whole-image result ([out] the size of the layer). */
            fun whole(out: PixelBuffer, changed: PixelRect?) = Result(out.pixels, PixelRect.full(out.width, out.height), changed)
        }
    }

    /** The mapper [run] applies [filter] with for [values], or null when it keeps the whole-image path. */
    fun mapperFor(filter: Filter, values: FilterValues): PixelMapper? {
        if (!enabled || filter.generatesContent) return null
        return filter.pixelMapper(values)
    }

    /** Rows in a band of [width]-pixel rows. */
    fun bandRows(width: Int): Int = (max(1, bandPixels) / max(1, width)).coerceIn(1, MAX_BAND_ROWS)

    /** [region] (null: the whole layer) inside a [width] x [height] layer, or null when empty. */
    fun clamp(region: PixelRect?, width: Int, height: Int): PixelRect? {
        val r = region ?: return PixelRect.full(width, height)
        val c = PixelRect(max(0, r.left), max(0, r.top), min(width, r.right), min(height, r.bottom))
        return if (c.isEmpty) null else c
    }

    /**
     * The Java heap [run] needs for a [width] x [height] layer and [region]: the region's result,
     * two bands, a band of selection and, with a [selection], its coverage for the whole layer.
     */
    fun estimate(width: Int, height: Int, region: PixelRect?, selection: Boolean): Long {
        val r = clamp(region, width, height) ?: return 0L
        val band = r.width.toLong() * min(bandRows(r.width), r.height)
        return r.width.toLong() * r.height * 4 + band * 9 + if (selection) width.toLong() * height else 0L
    }

    /** Reads the rows [top] until [top] + [rows] of columns [left] until [left] + [cols] (non-premultiplied ARGB) into a packed array. */
    fun interface Source {
        fun read(dst: IntArray, left: Int, top: Int, cols: Int, rows: Int)
    }

    /**
     * Applies [mapper] to [region] (null: everything) of a [width] x [height] layer read from
     * [source], band by band, with the whole-image path's rules: [FilterSessionMath.compose]
     * ([selection] coverage, [alphaLocked], [maskTarget]), then the [colorMode] constraint, then
     * [FilterSessionMath.changedBounds]. [checkCancelled] runs before each band; [progress] gets
     * the fraction done after each. Any thread but main.
     */
    fun run(
        width: Int,
        height: Int,
        source: Source,
        mapper: PixelMapper,
        selection: ByteArray?,
        alphaLocked: Boolean,
        maskTarget: Boolean,
        colorMode: ColorMode,
        region: PixelRect?,
        checkCancelled: () -> Unit = {},
        progress: (Float) -> Unit = {},
    ): Result {
        val r = clamp(region, width, height) ?: return Result(IntArray(0), PixelRect(0, 0, 0, 0), null)
        val rw = r.width
        val out = IntArray(rw * r.height)
        val rows = min(bandRows(rw), r.height)
        var src = IntArray(rw * rows)
        var res = IntArray(rw * rows)
        var sel = if (selection != null) ByteArray(rw * rows) else null
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        var y = r.top
        while (y < r.bottom) {
            checkCancelled()
            val bh = min(rows, r.bottom - y)
            val n = rw * bh
            if (src.size != n) {
                // The last band is shorter: buffers of its size (a PixelBuffer is exactly w x h).
                src = IntArray(n); res = IntArray(n); sel = if (selection != null) ByteArray(n) else null
            }
            source.read(src, r.left, y, rw, bh)
            System.arraycopy(src, 0, res, 0, n)
            if (selection != null) for (row in 0 until bh) System.arraycopy(selection, (y + row) * width + r.left, sel!!, row * rw, rw)
            Parallel.forRange(bh, 4) { y0, y1 -> mapper.map(res, y0 * rw, y1 * rw) }
            val before = PixelBuffer(rw, bh, src)
            val after = PixelBuffer(rw, bh, res)
            FilterSessionMath.compose(before, after, sel, alphaLocked, maskTarget, PixelRect.full(rw, bh), checkCancelled)
            if (colorMode != ColorMode.RGB) {
                Parallel.forRange(bh, 4) { y0, y1 ->
                    for (i in y0 * rw until y1 * rw) res[i] = ColorModeOps.constrainPixel(res[i], colorMode)
                }
            }
            FilterSessionMath.changedBounds(before, after)?.let { c ->
                minX = min(minX, r.left + c.left); maxX = max(maxX, r.left + c.right)
                minY = min(minY, y + c.top); maxY = max(maxY, y + c.bottom)
            }
            System.arraycopy(res, 0, out, (y - r.top) * rw, n)
            bandsRun++
            y += bh
            progress((y - r.top).toFloat() / r.height)
        }
        checkCancelled()
        val changed = if (maxX < 0) null else PixelRect(minX, minY, maxX, maxY)
        return Result(out, r, changed)
    }
}
