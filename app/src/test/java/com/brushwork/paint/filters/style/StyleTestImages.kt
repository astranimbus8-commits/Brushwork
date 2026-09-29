package com.brushwork.paint.filters.style

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import kotlin.math.hypot

/** Small synthetic images for the Style filter tests (pixel centres at x + 0.5). */
internal object StyleTestImages {

    /** Supersampled (8x8) coverage of [inside] for pixel (x, y). */
    inline fun coverage(x: Int, y: Int, inside: (Float, Float) -> Boolean): Float {
        var n = 0
        for (sy in 0 until 8) for (sx in 0 until 8) {
            if (inside(x + (sx + 0.5f) / 8f, y + (sy + 0.5f) / 8f)) n++
        }
        return n / 64f
    }

    /** Antialiased disc of [color] centred at ([cx], [cy]) with radius [r] on a transparent canvas. */
    fun disc(w: Int, h: Int, cx: Float, cy: Float, r: Float, color: Int = 0xFFFF8800.toInt()): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val cov = coverage(x, y) { px, py -> hypot(px - cx, py - cy) < r }
            if (cov > 0f) b[x, y] = ColorUtils.withAlpha(color, (cov * (color ushr 24) + 0.5f).toInt())
        }
        return b
    }

    /** Axis-aligned opaque rectangle `[l, r) x [t, b)` of [color] on a transparent canvas. */
    fun rect(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, color: Int = 0xFF2E7DFF.toInt()): PixelBuffer {
        val img = PixelBuffer(w, h)
        for (y in t until b) for (x in l until r) img[x, y] = color
        return img
    }

    /** Opaque image split vertically into two flat colors at column [split]. */
    fun twoColors(w: Int, h: Int, split: Int, left: Int, right: Int): PixelBuffer {
        val img = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) img[x, y] = if (x < split) left else right
        return img
    }

    /** Alpha-weighted centroid (x, y) of an image, or null when fully transparent. */
    fun alphaCentroid(img: PixelBuffer): FloatArray? {
        var sa = 0.0; var sx = 0.0; var sy = 0.0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val a = (img[x, y] ushr 24).toDouble()
            sa += a; sx += a * x; sy += a * y
        }
        return if (sa <= 0.0) null else floatArrayOf((sx / sa).toFloat(), (sy / sa).toFloat())
    }

    fun alpha(c: Int): Int = c ushr 24
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF
    fun luma(c: Int): Int = ColorUtils.luminance(c)
}
