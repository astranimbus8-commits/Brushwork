package com.brushwork.paint.filters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/** Bitmap helpers of [FilterSession]. */
internal object FilterSessionBitmaps {

    /**
     * [src] scaled down to [width] x [height], keeping its config (ARGB_8888 or ALPHA_8).
     *
     * A single bilinear step samples only a few source pixels per output pixel, so at 3-4x
     * reductions thin lines and thin selections break up or vanish. Here each axis is first halved
     * repeatedly (a bilinear halving averages 2x2 pixels, like a mipmap level) until it is within
     * 2x of the target, then one bilinear step finishes. Returns [src] itself when the size
     * already matches; intermediate bitmaps are recycled.
     */
    fun downscale(src: Bitmap, width: Int, height: Int): Bitmap {
        require(width in 1..src.width && height in 1..src.height) { "downscale only: ${src.width}x${src.height} -> ${width}x$height" }
        if (src.width == width && src.height == height) return src
        var cur = src
        try {
            while (cur.width >= 2 * width || cur.height >= 2 * height) {
                val nw = if (cur.width >= 2 * width) cur.width / 2 else cur.width
                val nh = if (cur.height >= 2 * height) cur.height / 2 else cur.height
                val next = resample(cur, nw, nh)
                if (cur !== src) cur.recycle()
                cur = next
            }
            if (cur.width == width && cur.height == height) return cur
            val out = resample(cur, width, height)
            if (cur !== src) cur.recycle()
            return out
        } catch (e: Throwable) {
            if (cur !== src) cur.recycle()
            throw e
        }
    }

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun resample(src: Bitmap, w: Int, h: Int): Bitmap {
        val config = if (src.config == Bitmap.Config.ALPHA_8) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
        val dst = Bitmap.createBitmap(w, h, config)
        Canvas(dst).drawBitmap(src, null, Rect(0, 0, w, h), filterPaint)
        return dst
    }
}
