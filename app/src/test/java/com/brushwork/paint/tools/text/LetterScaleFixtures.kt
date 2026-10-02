package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils

/** Rendering and ink measuring shared by the letter scaling tests (v1.6 §3.5). */
internal object LetterScaleFixtures {

    /** [item] drawn alone on a transparent [w] × [h] layer, as a text layer is drawn. */
    fun render(item: TextItem, w: Int, h: Int): Bitmap =
        BitmapUtils.createLayerBitmap(w, h).also { TextRenderer.drawItem(Canvas(it), item, TextRenderer.prepare(item), null) }

    fun alpha(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) px[i] = px[i] ushr 24
        return px
    }

    /**
     * The ink of every letter laid out left to right (column runs with alpha ≥ [min], separated
     * by empty columns), each with the rows its ink spans.
     */
    fun lettersLeftToRight(b: Bitmap, min: Int = 128): List<Rect> {
        val a = alpha(b)
        val w = b.width
        val h = b.height
        val inked = BooleanArray(w) { x -> (0 until h).any { y -> a[y * w + x] >= min } }
        val out = ArrayList<Rect>()
        var x = 0
        while (x < w) {
            if (!inked[x]) { x++; continue }
            val x0 = x
            while (x < w && inked[x]) x++
            var top = h
            var bottom = -1
            for (cx in x0 until x) for (y in 0 until h) if (a[y * w + cx] >= min) { top = minOf(top, y); bottom = maxOf(bottom, y) }
            out += Rect(x0, top, x, bottom + 1)
        }
        return out
    }

    /** Like [lettersLeftToRight] for letters stacked top to bottom (vertical text). */
    fun lettersTopToBottom(b: Bitmap, min: Int = 128): List<Rect> {
        val a = alpha(b)
        val w = b.width
        val h = b.height
        val inked = BooleanArray(h) { y -> (0 until w).any { x -> a[y * w + x] >= min } }
        val out = ArrayList<Rect>()
        var y = 0
        while (y < h) {
            if (!inked[y]) { y++; continue }
            val y0 = y
            while (y < h && inked[y]) y++
            var left = w
            var right = -1
            for (cy in y0 until y) for (x in 0 until w) if (a[cy * w + x] >= min) { left = minOf(left, x); right = maxOf(right, x) }
            out += Rect(left, y0, right + 1, y)
        }
        return out
    }

    /** Pixels with alpha ≥ [min]. */
    fun inkCount(b: Bitmap, min: Int = 128): Int = alpha(b).count { it >= min }

    /** Intersection over union of the inked pixels (alpha ≥ [min]) of [a] and [b]. */
    fun iou(a: Bitmap, b: Bitmap, min: Int = 128): Float {
        val pa = alpha(a)
        val pb = alpha(b)
        var inter = 0
        var union = 0
        for (i in pa.indices) {
            val x = pa[i] >= min
            val y = pb[i] >= min
            if (x && y) inter++
            if (x || y) union++
        }
        return if (union == 0) 1f else inter.toFloat() / union
    }

    const val BLACK = 0xFF000000.toInt()
}
