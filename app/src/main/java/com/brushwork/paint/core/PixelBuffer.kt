package com.brushwork.paint.core

import kotlin.math.floor

/**
 * A plain ARGB image stored in an [IntArray] (row-major, NON-premultiplied 0xAARRGGBB —
 * exactly what android.graphics.Bitmap.getPixels returns). No Android dependencies so that all
 * filters can be unit-tested on the JVM.
 */
class PixelBuffer(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "PixelBuffer must be non-empty: ${width}x$height" }
        require(pixels.size == width * height) { "pixels.size ${pixels.size} != ${width}x$height" }
    }

    val size: Int get() = pixels.size

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, v: Int) { pixels[y * width + x] = v }

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Pixel with coordinates clamped to the edge. */
    fun getClamped(x: Int, y: Int): Int {
        val cx = if (x < 0) 0 else if (x >= width) width - 1 else x
        val cy = if (y < 0) 0 else if (y >= height) height - 1 else y
        return pixels[cy * width + cx]
    }

    /** Pixel or [outside] when out of bounds. */
    fun getOr(x: Int, y: Int, outside: Int = 0): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) outside else pixels[y * width + x]

    fun copy(): PixelBuffer = PixelBuffer(width, height, pixels.copyOf())

    fun fill(color: Int): PixelBuffer { pixels.fill(color); return this }

    fun copyFrom(other: PixelBuffer) {
        require(other.width == width && other.height == height)
        System.arraycopy(other.pixels, 0, pixels, 0, pixels.size)
    }

    /**
     * Bilinear sample at floating-point coordinates (pixel centers are at +0.5), interpolating in
     * premultiplied space so transparent pixels don't bleed dark fringes. Edge mode: clamp, or
     * transparent when [transparentOutside] is true.
     */
    fun sampleBilinear(fx: Float, fy: Float, transparentOutside: Boolean = false): Int {
        val x = fx - 0.5f
        val y = fy - 0.5f
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val tx = x - x0; val ty = y - y0
        val c00: Int; val c10: Int; val c01: Int; val c11: Int
        if (transparentOutside) {
            c00 = getOr(x0, y0); c10 = getOr(x0 + 1, y0); c01 = getOr(x0, y0 + 1); c11 = getOr(x0 + 1, y0 + 1)
        } else {
            c00 = getClamped(x0, y0); c10 = getClamped(x0 + 1, y0); c01 = getClamped(x0, y0 + 1); c11 = getClamped(x0 + 1, y0 + 1)
        }
        return bilerpPremul(c00, c10, c01, c11, tx, ty)
    }

    companion object {
        fun filled(width: Int, height: Int, color: Int) = PixelBuffer(width, height).fill(color)

        /** Bilinear interpolation of four NON-premultiplied colors, weighting color by alpha. */
        fun bilerpPremul(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Int {
            val w00 = (1 - tx) * (1 - ty); val w10 = tx * (1 - ty); val w01 = (1 - tx) * ty; val w11 = tx * ty
            val a00 = (c00 ushr 24) * w00; val a10 = (c10 ushr 24) * w10
            val a01 = (c01 ushr 24) * w01; val a11 = (c11 ushr 24) * w11
            val a = a00 + a10 + a01 + a11
            if (a <= 0.001f) return 0
            val r = (((c00 shr 16) and 0xFF) * a00 + ((c10 shr 16) and 0xFF) * a10 + ((c01 shr 16) and 0xFF) * a01 + ((c11 shr 16) and 0xFF) * a11) / a
            val g = (((c00 shr 8) and 0xFF) * a00 + ((c10 shr 8) and 0xFF) * a10 + ((c01 shr 8) and 0xFF) * a01 + ((c11 shr 8) and 0xFF) * a11) / a
            val b = ((c00 and 0xFF) * a00 + (c10 and 0xFF) * a10 + (c01 and 0xFF) * a01 + (c11 and 0xFF) * a11) / a
            return ColorUtils.argb((a + 0.5f).toInt(), (r + 0.5f).toInt(), (g + 0.5f).toInt(), (b + 0.5f).toInt())
        }
    }
}
