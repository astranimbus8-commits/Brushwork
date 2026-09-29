package com.brushwork.paint.ui.canvas

import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.engine.CanvasOps
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure helpers behind the canvas dialog's fields and summaries (JVM-testable). */
internal object CanvasAdjustMath {

    /** A typed pixel length rounded to a whole, valid pixel count. */
    fun toPixels(px: Double): Int = if (px.isNaN()) 1 else px.roundToInt().coerceAtLeast(1)

    /**
     * New pixel length after the resolution changes from [oldDpi] to [newDpi]: sizes typed in a
     * physical [unit] keep their physical size (so the pixel count follows the dpi), pixel sizes
     * stay as they are.
     */
    fun pxAfterDpiChange(px: Double, oldDpi: Double, newDpi: Double, unit: LengthUnit): Double =
        if (unit == LengthUnit.PX || oldDpi <= 0.0) px else px * newDpi / oldDpi

    /** "12.5 × 20 cm" for a [w] x [h] px area at [dpi]. */
    fun formatSize(w: Double, h: Double, unit: LengthUnit, dpi: Double): String =
        "${Units.format(w, unit, dpi, withSuffix = false)} × ${Units.format(h, unit, dpi, withSuffix = false)} ${unit.short}"

    fun percent(newPx: Int, oldPx: Int): String = Units.formatNumber(newPx * 100.0 / oldPx, 1) + "%"

    /**
     * How much each edge moves when the old image sits at ([ox], [oy]) in a [newW] x [newH]
     * canvas: left, top, right, bottom (positive = added space, negative = cropped).
     */
    fun edges(oldW: Int, oldH: Int, newW: Int, newH: Int, ox: Int, oy: Int): IntArray =
        intArrayOf(ox, oy, newW - (ox + oldW), newH - (oy + oldH))

    fun formatEdges(e: IntArray): String {
        fun f(v: Int) = when {
            v > 0 -> "+$v"
            v < 0 -> "−${-v}"
            else -> "0"
        }
        return "Left ${f(e[0])} · Top ${f(e[1])} · Right ${f(e[2])} · Bottom ${f(e[3])} px"
    }

    /** "3 layers + 1 mask" */
    fun describeBitmaps(layers: Int, masks: Int): String {
        val l = if (layers == 1) "1 layer" else "$layers layers"
        return if (masks == 0) l else if (masks == 1) "$l + 1 mask" else "$l + $masks masks"
    }

    /** Memory line for a document of [w] x [h] with [bitmapCount] bitmaps. */
    fun memoryLine(w: Int, h: Int, bitmapCount: Int, budget: Long): String =
        "${CanvasOps.formatBytes(CanvasOps.estimateBytes(bitmapCount, w, h))} of about ${CanvasOps.formatBytes(budget)} available"

    /** True when [newW] x [newH] noticeably changes the aspect ratio of [oldW] x [oldH]. */
    fun aspectChanged(oldW: Int, oldH: Int, newW: Int, newH: Int): Boolean {
        val a = oldW.toDouble() / oldH
        val b = newW.toDouble() / newH
        return abs(a - b) / a > 0.01
    }
}
