package com.brushwork.paint.brush

import android.graphics.Bitmap

/** [PixelSurface] over a mutable ARGB_8888 bitmap (getPixels/setPixels on sub-rectangles). */
class BitmapSurface(private val bitmap: Bitmap) : PixelSurface {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun read(left: Int, top: Int, w: Int, h: Int, out: IntArray) {
        bitmap.getPixels(out, 0, w, left, top, w, h)
    }

    override fun write(left: Int, top: Int, w: Int, h: Int, src: IntArray) {
        bitmap.setPixels(src, 0, w, left, top, w, h)
    }
}

/** Reads an ALPHA_8 mask (e.g. `Selection.mask`) as coverage values 0..255. */
class AlphaMaskReader(private val mask: Bitmap) : CoverageReader {
    override fun read(left: Int, top: Int, w: Int, h: Int, out: IntArray) {
        mask.getPixels(out, 0, w, left, top, w, h)
        for (i in 0 until w * h) out[i] = out[i] ushr 24
    }
}
