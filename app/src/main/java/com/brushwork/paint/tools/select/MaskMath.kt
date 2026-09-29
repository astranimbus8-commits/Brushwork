package com.brushwork.paint.tools.select

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.filters.FilterMath
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Soft-mask morphology for selections, on packed row-major byte masks (0..255, 255 = selected).
 * Pure Kotlin. Callers pass a window of the document (see [growMargin] / [featherMargin]):
 * pixels outside the array are treated as unselected and never as boundaries of the mask.
 */
object MaskMath {
    /** Largest grow / shrink / feather radius in px. */
    const val MAX_RADIUS = 250

    /** How far a grow by [radius] can reach beyond the mask bounds. */
    fun growMargin(radius: Int): Int = radius + 1

    /** How far a feather by [radius] can reach beyond the mask bounds. */
    fun featherMargin(radius: Float): Int = ceil(3f * sigmaFor(radius)).toInt() + 2

    /** Gaussian sigma used for a feather radius (the edge fades over about ±radius). */
    fun sigmaFor(radius: Float): Float = radius / 2f

    /**
     * Grows the mask by [radius] px: a pixel at distance d from the selected area (value >= 128)
     * gets coverage clamp(radius + 1 - d), so the edge moves out by exactly [radius] with a 1 px
     * anti-aliased rim. Existing soft values are kept where they are higher.
     */
    fun grow(mask: ByteArray, w: Int, h: Int, radius: Int, cancelled: () -> Boolean = { false }): ByteArray {
        val r = radius.coerceIn(0, MAX_RADIUS)
        val out = mask.copyOf()
        if (r == 0) return out
        Distance.bounded(w, h, 0, 0, w, h, r + 1, { i -> mask[i].toInt() and 0xFF >= 128 }, { y, d2 ->
            val row = y * w
            for (x in 0 until w) {
                val dd = d2[x]
                if (dd == Distance.FAR) continue
                val c = coverage(r + 1 - sqrt(dd.toDouble()))
                if (c > (out[row + x].toInt() and 0xFF)) out[row + x] = c.toByte()
            }
        }, cancelled)
        return out
    }

    /**
     * Shrinks the mask by [radius] px: a pixel at distance d from the unselected area gets
     * coverage clamp(d - radius). Only unselected pixels INSIDE the array act as boundaries, so a
     * selection touching the document edge doesn't shrink away from it.
     */
    fun shrink(mask: ByteArray, w: Int, h: Int, radius: Int, cancelled: () -> Boolean = { false }): ByteArray {
        val r = radius.coerceIn(0, MAX_RADIUS)
        val out = mask.copyOf()
        if (r == 0) return out
        Distance.bounded(w, h, 0, 0, w, h, r + 1, { i -> mask[i].toInt() and 0xFF < 128 }, { y, d2 ->
            val row = y * w
            for (x in 0 until w) {
                val dd = d2[x]
                if (dd == Distance.FAR) continue
                val c = coverage(sqrt(dd.toDouble()) - r)
                if (c < (out[row + x].toInt() and 0xFF)) out[row + x] = c.toByte()
            }
        }, cancelled)
        return out
    }

    /**
     * Gaussian feather (sigma = radius / 2, approximated by three box blurs). Edges of the array
     * are extended (clamp), so pass a window with [featherMargin] around the selected area, or the
     * whole document.
     */
    fun feather(mask: ByteArray, w: Int, h: Int, radius: Float, cancelled: () -> Boolean = { false }): ByteArray {
        val sigma = sigmaFor(radius)
        if (sigma < 0.3f) return mask.copyOf()
        val boxes = FilterMath.boxesForGauss(sigma, 3)
        val a = mask.copyOf()
        val b = ByteArray(mask.size)
        for (box in boxes) {
            val r = (box - 1) / 2
            if (r <= 0) continue
            boxH(a, b, w, h, r)
            if (cancelled()) return mask.copyOf()
            boxV(b, a, w, h, r)
            if (cancelled()) return mask.copyOf()
        }
        return a
    }

    private fun coverage(v: Double): Int {
        val c = v * 255.0
        return if (c >= 255.0) 255 else if (c <= 0.0) 0 else (c + 0.5).toInt()
    }

    /** Horizontal box blur with radius [r], edges clamped. */
    private fun boxH(src: ByteArray, dst: ByteArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        val half = n / 2
        Parallel.forRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var acc = 0
                for (k in -r..r) acc += src[row + min(w - 1, max(0, k))].toInt() and 0xFF
                for (x in 0 until w) {
                    dst[row + x] = ((acc + half) / n).toByte()
                    acc += (src[row + min(w - 1, x + r + 1)].toInt() and 0xFF) - (src[row + max(0, x - r)].toInt() and 0xFF)
                }
            }
        }
    }

    /** Vertical box blur with radius [r], edges clamped; row-major traversal over column strips. */
    private fun boxV(src: ByteArray, dst: ByteArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        val half = n / 2
        Parallel.forRange(w, 32) { x0, x1 ->
            val acc = IntArray(x1 - x0)
            for (k in -r..r) {
                val row = min(h - 1, max(0, k)) * w
                for (x in x0 until x1) acc[x - x0] += src[row + x].toInt() and 0xFF
            }
            for (y in 0 until h) {
                val row = y * w
                val addRow = min(h - 1, y + r + 1) * w
                val subRow = max(0, y - r) * w
                for (x in x0 until x1) {
                    val j = x - x0
                    dst[row + x] = ((acc[j] + half) / n).toByte()
                    acc[j] += (src[addRow + x].toInt() and 0xFF) - (src[subRow + x].toInt() and 0xFF)
                }
            }
        }
    }
}
