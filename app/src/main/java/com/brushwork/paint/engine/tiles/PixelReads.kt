package com.brushwork.paint.engine.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer

/**
 * v1.10 (F1, design 2.3 C-REG; signatures frozen, bodies A9): reads of a layer that may be
 * inactive (parked), for the tools that sample other layers. The F1 bodies read the store as it
 * is (a flat bitmap, or the tiles under the region), so they never materialize a parked layer.
 * Main thread (a parked layer's tiles are read on main, `Tile` rule 5).
 */
object PixelReads {
    /** Allocations from this size on ask `admitNative` first. */
    private const val ADMIT_FROM = 4L shl 20

    /** The color at ([x], [y]) as `layer.bitmap.getPixel` gives it (unpremultiplied ARGB); 0 outside the layer. */
    fun sample(layer: Layer, x: Int, y: Int): Int = TileOps.sample(layer.pixels, x, y)

    /** The pixels of [r] (document px; transparent outside the layer) at (0, 0) of [out] (r-sized ARGB, else allocated). */
    fun region(layer: Layer, r: Rect, out: Bitmap? = null): Bitmap {
        val dst = out ?: allocate(r)
        TileOps.readRegion(layer.pixels, r, dst)
        return dst
    }

    /** The alpha of [r] row by row into [out] (at least r.width() x r.height() bytes; 0 outside the layer). */
    fun alphaRegion(layer: Layer, r: Rect, out: ByteArray) {
        val w = r.width()
        val h = r.height()
        require(out.size >= w * h) { "${out.size} bytes for a ${w}x$h region" }
        val tmp = region(layer, r)
        try {
            val row = IntArray(w)
            for (y in 0 until h) {
                tmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) out[y * w + x] = (row[x] ushr 24).toByte()
            }
        } finally {
            tmp.recycle()
        }
    }

    /** The visible composite of [doc] (no paper, no tool preview) over [r] at (0, 0) of [out] (r-sized ARGB, else allocated). */
    fun flattenedRegion(doc: Document, r: Rect, out: Bitmap? = null): Bitmap {
        val dst = out ?: allocate(r)
        val c = Canvas(dst)
        c.clipRect(0, 0, r.width(), r.height())
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.translate(-r.left.toFloat(), -r.top.toFloat())
        Compositor(doc) { null }.drawDocument(c, r, useOverrides = false, target = CompositeTarget.translate(dst, r.left, r.top))
        return dst
    }

    private fun allocate(r: Rect): Bitmap {
        require(r.width() > 0 && r.height() > 0) { "an empty region ($r)" }
        val bytes = r.width().toLong() * r.height() * 4
        if (bytes >= ADMIT_FROM) {
            val budget = EngineConfig.budget
            if (!budget.admitNative(bytes, NativePurpose.TRANSIENT)) throw OutOfMemoryError("No room for a ${r.width()}x${r.height()} region")
            // The caller owns (and recycles) the bitmap; F1 does not keep it in the ledger.
            budget.release(bytes, NativePurpose.TRANSIENT)
        }
        return BitmapUtils.createLayerBitmap(r.width(), r.height())
    }
}
