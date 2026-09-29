package com.brushwork.paint.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils

enum class SelectionMode(val label: String) {
    REPLACE("New"),
    ADD("Add"),
    SUBTRACT("Subtract"),
    INTERSECT("Intersect"),
}

/**
 * A soft selection: ALPHA_8 [mask] the size of the document, 255 = fully selected.
 * Instances are treated as IMMUTABLE once published to the controller (create a new one for
 * changes) so undo can keep references safely.
 */
class Selection private constructor(val mask: Bitmap, bounds: Rect?) {
    val width: Int get() = mask.width
    val height: Int get() = mask.height

    /** Tight bounds of non-zero pixels (empty rect if nothing selected). */
    val bounds: Rect = bounds ?: computeBounds(mask)

    /**
     * Outline for marching ants in document coordinates. Filled asynchronously by
     * `SelectionOutline` after the selection is published; null until ready.
     */
    @Volatile var outline: Path? = null

    val isEmpty: Boolean get() = bounds.isEmpty

    fun toBytes(): ByteArray = BitmapUtils.alpha8ToBytes(mask)

    fun alphaAt(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else (mask.getPixel(x, y) ushr 24)

    fun copy(): Selection = Selection(mask.copy(Bitmap.Config.ALPHA_8, true), Rect(bounds))

    fun inverted(): Selection {
        val b = toBytes()
        for (i in b.indices) b[i] = (255 - (b[i].toInt() and 0xFF)).toByte()
        return fromBytes(b, width, height)
    }

    /** Combines this (existing) selection with [other] (new) using [mode]. Returns a new Selection. */
    fun combine(other: Selection, mode: SelectionMode): Selection {
        if (mode == SelectionMode.REPLACE) return other
        val out = mask.copy(Bitmap.Config.ALPHA_8, true)
        val c = Canvas(out)
        val p = Paint()
        when (mode) {
            SelectionMode.ADD -> p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            SelectionMode.SUBTRACT -> p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            SelectionMode.INTERSECT -> p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            SelectionMode.REPLACE -> {}
        }
        c.drawBitmap(other.mask, 0f, 0f, p)
        return Selection(out, null)
    }

    companion object {
        fun empty(width: Int, height: Int): Selection =
            Selection(Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8), Rect())

        fun all(width: Int, height: Int): Selection {
            val m = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            m.eraseColor(0xFF000000.toInt())
            return Selection(m, Rect(0, 0, width, height))
        }

        /** From a packed width*height array of coverage bytes (0..255). */
        fun fromBytes(bytes: ByteArray, width: Int, height: Int): Selection =
            Selection(BitmapUtils.bytesToAlpha8(bytes, width, height), null)

        /** From coverage floats 0..1. */
        fun fromFloats(values: FloatArray, width: Int, height: Int): Selection =
            fromBytes(ByteArray(values.size) { (values[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }, width, height)

        /** Fills [path] (document coordinates). */
        fun fromPath(path: Path, width: Int, height: Int, antiAlias: Boolean = true): Selection {
            val m = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            val p = Paint().apply { isAntiAlias = antiAlias; style = Paint.Style.FILL; color = 0xFF000000.toInt() }
            Canvas(m).drawPath(path, p)
            return Selection(m, null)
        }

        /** Wraps an existing ALPHA_8 bitmap (takes ownership). */
        fun wrap(alpha8: Bitmap): Selection {
            require(alpha8.config == Bitmap.Config.ALPHA_8) { "Selection mask must be ALPHA_8" }
            return Selection(alpha8, null)
        }

        /**
         * Wraps an ALPHA_8 bitmap whose tight non-zero [bounds] are already known (skips the full
         * scan). The caller guarantees the bounds are correct (they are clamped to the bitmap).
         */
        fun wrap(alpha8: Bitmap, bounds: Rect): Selection {
            require(alpha8.config == Bitmap.Config.ALPHA_8) { "Selection mask must be ALPHA_8" }
            val b = Rect(bounds)
            if (!b.intersect(0, 0, alpha8.width, alpha8.height)) b.setEmpty()
            return Selection(alpha8, b)
        }

        fun computeBounds(mask: Bitmap): Rect {
            val bytes = BitmapUtils.alpha8ToBytes(mask)
            val w = mask.width; val h = mask.height
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            for (y in 0 until h) {
                val row = y * w
                var rowHas = false
                for (x in 0 until w) {
                    if (bytes[row + x].toInt() != 0) {
                        rowHas = true
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                    }
                }
                if (rowHas) { if (y < minY) minY = y; maxY = y }
            }
            return if (maxX < 0) Rect() else Rect(minX, minY, maxX + 1, maxY + 1)
        }
    }
}
