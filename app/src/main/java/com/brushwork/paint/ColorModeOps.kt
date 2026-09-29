package com.brushwork.paint

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.model.ColorMode

/** Enforces the document color mode on pixels (grayscale / 1-bit monochrome). */
object ColorModeOps {
    /** Converts pixels inside [rect] of [bitmap] to satisfy [mode]. No-op for RGB. */
    fun constrain(bitmap: Bitmap, rect: Rect, mode: ColorMode, threshold: Int = 128) {
        if (mode == ColorMode.RGB) return
        val r = Rect(rect)
        if (!r.intersect(0, 0, bitmap.width, bitmap.height)) return
        val w = r.width(); val h = r.height()
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, r.left, r.top, w, h)
        for (i in px.indices) px[i] = constrainPixel(px[i], mode, threshold)
        bitmap.setPixels(px, 0, w, r.left, r.top, w, h)
    }

    fun constrainPixel(c: Int, mode: ColorMode, threshold: Int = 128): Int {
        val a = c ushr 24
        if (a == 0) return 0
        val l = ColorUtils.luminance(c)
        return when (mode) {
            ColorMode.RGB -> c
            ColorMode.GRAYSCALE -> ColorUtils.argbUnchecked(a, l, l, l)
            ColorMode.MONOCHROME -> {
                val v = if (l >= threshold) 255 else 0
                ColorUtils.argbUnchecked(if (a >= 128) 255 else 0, v, v, v).let { if (a < 128) 0 else it }
            }
        }
    }

    /** Color as it would be painted in [mode] (used to preview the brush color). */
    fun displayColor(c: Int, mode: ColorMode): Int = if (mode == ColorMode.RGB) c else constrainPixel(c or 0xFF000000.toInt(), mode)
}
