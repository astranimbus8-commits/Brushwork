package com.brushwork.paint.engine

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.model.ColorMode

/**
 * Pixel conversion for document color-mode changes, row by row (pure Kotlin, JVM-testable).
 * Results match `ColorModeOps.constrainPixel` so later edits (constrained by the controller)
 * look the same as converted pixels. Pixels are NON-premultiplied ARGB.
 *
 * Create one converter per image: Floyd–Steinberg dithering carries its error from one row to
 * the next, so rows must be fed top to bottom through [convertRows].
 */
class ColorModeConverter(
    private val width: Int,
    private val mode: ColorMode,
    threshold: Int = 128,
    private val dither: Boolean = false,
) {
    private val threshold = threshold.coerceIn(1, 255)

    // Error rows (luminance units) with one guard cell on each side.
    private var errCur = FloatArray(if (dither) width + 2 else 0)
    private var errNext = FloatArray(if (dither) width + 2 else 0)
    private var rowIndex = 0

    /** Converts [rows] rows of [px] (stride = width) in place. */
    fun convertRows(px: IntArray, rows: Int) {
        when {
            mode == ColorMode.RGB -> return
            mode == ColorMode.MONOCHROME && dither -> for (r in 0 until rows) ditherRow(px, r * width)
            else -> for (i in 0 until rows * width) px[i] = convertPixel(px[i])
        }
    }

    /** Single pixel without dithering. */
    fun convertPixel(c: Int): Int {
        val a = c ushr 24
        if (a == 0) return 0
        val l = ColorUtils.luminance(c)
        return when (mode) {
            ColorMode.RGB -> c
            ColorMode.GRAYSCALE -> ColorUtils.argbUnchecked(a, l, l, l)
            ColorMode.MONOCHROME -> if (a < 128) 0 else if (l >= threshold) WHITE else BLACK
        }
    }

    /** Serpentine Floyd–Steinberg on luminance; transparent pixels neither take nor pass error. */
    private fun ditherRow(px: IntArray, off: Int) {
        val cur = errCur
        val next = errNext
        next.fill(0f)
        val leftToRight = rowIndex % 2 == 0
        var i = 0
        while (i < width) {
            val x = if (leftToRight) i else width - 1 - i
            val c = px[off + x]
            val a = c ushr 24
            if (a < 128) {
                px[off + x] = 0
            } else {
                val v = ColorUtils.luminance(c) + cur[x + 1]
                val on = v >= threshold
                px[off + x] = if (on) WHITE else BLACK
                val e = v - (if (on) 255f else 0f)
                val fwd = if (leftToRight) 1 else -1
                cur[x + 1 + fwd] += e * (7f / 16f)
                next[x + 1 - fwd] += e * (3f / 16f)
                next[x + 1] += e * (5f / 16f)
                next[x + 1 + fwd] += e * (1f / 16f)
            }
            i++
        }
        errCur = next
        errNext = cur
        rowIndex++
    }

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
