package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.core.PixelBuffer
import java.nio.ByteBuffer

/** Android bitmap helpers shared by all modules. */
object BitmapUtils {

    /** A new transparent, mutable ARGB_8888 bitmap. */
    fun createLayerBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }

    /** A new opaque white mask bitmap (fully visible). */
    fun createMaskBitmap(width: Int, height: Int, fill: Int = -1): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(fill) }

    /** Exact mutable copy. */
    fun copy(src: Bitmap): Bitmap = src.copy(Bitmap.Config.ARGB_8888, true)

    /** Reads [rect] (or the whole bitmap) as a NON-premultiplied [PixelBuffer]. */
    fun toPixelBuffer(bitmap: Bitmap, rect: Rect? = null): PixelBuffer {
        val r = rect ?: Rect(0, 0, bitmap.width, bitmap.height)
        val buf = PixelBuffer(r.width(), r.height())
        bitmap.getPixels(buf.pixels, 0, r.width(), r.left, r.top, r.width(), r.height())
        return buf
    }

    /** Writes [buf] into [bitmap] at ([left], [top]). */
    fun writePixelBuffer(bitmap: Bitmap, buf: PixelBuffer, left: Int = 0, top: Int = 0) {
        bitmap.setPixels(buf.pixels, 0, buf.width, left, top, buf.width, buf.height)
    }

    /** Wraps a PixelBuffer into a new bitmap. */
    fun fromPixelBuffer(buf: PixelBuffer): Bitmap {
        val b = createLayerBitmap(buf.width, buf.height)
        writePixelBuffer(b, buf)
        return b
    }

    /** Paint that overwrites destination pixels exactly (used to restore snapshots). */
    val srcPaint: Paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** Exactly copies [src] into [dst] at (x, y), replacing the destination pixels. */
    fun blitExact(dst: Bitmap, src: Bitmap, x: Int, y: Int) {
        Canvas(dst).drawBitmap(src, x.toFloat(), y.toFloat(), srcPaint)
    }

    /**
     * Paint for applying a grayscale ARGB mask with DST_IN: luminance of the mask becomes the
     * alpha multiplier. Create a new instance per use-site if you mutate it.
     */
    fun newMaskApplyPaint(): Paint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0.299f, 0.587f, 0.114f, 0f, 0f,
                )
            )
        )
    }

    /** Returns a new bitmap mirrored horizontally or vertically. */
    fun flipped(src: Bitmap, horizontal: Boolean): Bitmap {
        val out = createLayerBitmap(src.width, src.height)
        val m = Matrix()
        if (horizontal) m.setScale(-1f, 1f, src.width / 2f, src.height / 2f) else m.setScale(1f, -1f, src.width / 2f, src.height / 2f)
        Canvas(out).drawBitmap(src, m, null)
        return out
    }

    /**
     * Mirrors [bmp] (ARGB_8888) in place, exactly. Undo steps that keep a layer's bitmap (canvas
     * operations, merges) must still find it as the layer's bitmap afterwards, with every later
     * edit undone in it: a flip that put a new bitmap into the layer cut them off (v1.5 QA).
     */
    fun flipInPlace(bmp: Bitmap, horizontal: Boolean) {
        val tmp = flipped(bmp, horizontal)
        try {
            blitExact(bmp, tmp, 0, 0)
        } finally {
            tmp.recycle()
        }
    }

    // ------------------------------------------------------------------ ALPHA_8 helpers

    /**
     * Multiplies everything drawn so far in [canvas] (within its clip) by the alpha of [alpha8]
     * (e.g. a selection mask), positioned at ([left], [top]).
     *
     * Do NOT use `drawBitmap(alpha8, DST_IN)` for this: Skia draws ALPHA_8 bitmaps as a coverage
     * mask for the paint color, which turns DST_IN into a no-op. A BitmapShader supplies the mask
     * as source alpha instead.
     */
    fun maskWith(canvas: Canvas, alpha8: Bitmap, left: Float = 0f, top: Float = 0f) {
        val p = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = android.graphics.BitmapShader(alpha8, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).also {
                if (left != 0f || top != 0f) it.setLocalMatrix(Matrix().apply { setTranslate(left, top) })
            }
        }
        val right = left + alpha8.width
        val bottom = top + alpha8.height
        canvas.drawRect(left, top, right, bottom, p)
        // Outside the mask bitmap nothing is selected: clear it (CLAMP would repeat the edge).
        val save = canvas.save()
        canvas.clipOutRect(left, top, right, bottom)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.restoreToCount(save)
    }

    /** Reads an ALPHA_8 bitmap into a tightly packed width*height byte array. */
    fun alpha8ToBytes(bitmap: Bitmap): ByteArray {
        require(bitmap.config == Bitmap.Config.ALPHA_8)
        val w = bitmap.width; val h = bitmap.height
        val rowBytes = bitmap.rowBytes
        val buffer = ByteBuffer.allocate(rowBytes * h)
        bitmap.copyPixelsToBuffer(buffer)
        val raw = buffer.array()
        if (rowBytes == w) return if (raw.size == w * h) raw else raw.copyOf(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(raw, y * rowBytes, out, y * w, w)
        return out
    }

    /** Creates an ALPHA_8 bitmap from a tightly packed width*height byte array. */
    fun bytesToAlpha8(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        writeAlpha8(bmp, bytes)
        return bmp
    }

    fun writeAlpha8(bmp: Bitmap, bytes: ByteArray) {
        val w = bmp.width; val h = bmp.height
        require(bytes.size >= w * h)
        val rowBytes = bmp.rowBytes
        val data = if (rowBytes == w) bytes else ByteArray(rowBytes * h).also { padded ->
            for (y in 0 until h) System.arraycopy(bytes, y * w, padded, y * rowBytes, w)
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(data, 0, rowBytes * h))
    }

    /** Converts a grayscale ARGB mask (luminance) to a packed byte array. */
    fun maskToBytes(mask: Bitmap): ByteArray {
        val w = mask.width; val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        return ByteArray(px.size) { i ->
            val c = px[i]
            val l = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            l.toByte()
        }
    }

    /** Clamps a rect to the bitmap bounds; returns false if empty. */
    fun clampRect(r: Rect, width: Int, height: Int): Boolean {
        if (!r.intersect(0, 0, width, height)) { r.setEmpty(); return false }
        return !r.isEmpty
    }
}
