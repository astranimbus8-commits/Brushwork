package com.brushwork.paint.model

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * v1.7 (item 14): a saved selection ("Selection 1", ...), listed under the Selection Layer row.
 * Immutable: an edit replaces the entry (`SavedSelectionsAction` swaps whole lists, sharing the
 * entries by reference).
 *
 * [packed] is the Deflate stream (zlib format, level 5) of the ALPHA_8 coverage rows inside
 * [bounds] (width × height bytes, top row first), and the same bytes are the
 * `sel_<id>_r<rev>.bin` file. The zlib checksum lets the loader drop a damaged file.
 */
class SavedSelection(
    val id: Long,
    val name: String,
    /** Document px; the tight bounds of the selection when it was saved. */
    val bounds: Rect,
    /** Deflate (level 5) of the ALPHA_8 rows inside [bounds]; the same bytes are the file. */
    val packed: ByteArray,
    /** Bumps on "Update from selection"; names the file. */
    val revision: Long,
) {
    val bytes: Long get() = packed.size.toLong()

    fun renamed(n: String): SavedSelection = SavedSelection(id, n, Rect(bounds), packed, revision)

    /**
     * Inflates into a document-size Selection (≤ 20 MB raw at 4000 × 5000). Rows outside the
     * document are cut off; data that ends early leaves the rest unselected (the loader has
     * already dropped damaged files, [isIntact]). Runs on a worker.
     */
    fun toSelection(docW: Int, docH: Int): Selection {
        val w = bounds.width()
        val h = bounds.height()
        val visible = Rect(bounds)
        if (w <= 0 || h <= 0 || !visible.intersect(0, 0, docW, docH)) return Selection.empty(docW, docH)
        val crop = ByteArray(w * h)
        inflate(packed, crop)
        val full = ByteArray(docW * docH)
        val dx = visible.left - bounds.left
        val n = visible.width()
        // The tight bounds come from the rows themselves (no full-document scan).
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in visible.top until visible.bottom) {
            val src = (y - bounds.top) * w + dx
            System.arraycopy(crop, src, full, y * docW + visible.left, n)
            var first = -1
            var last = -1
            for (x in 0 until n) if (crop[src + x].toInt() != 0) { if (first < 0) first = x; last = x }
            if (first >= 0) {
                minX = minOf(minX, visible.left + first); maxX = maxOf(maxX, visible.left + last)
                if (minY == Int.MAX_VALUE) minY = y
                maxY = y
            }
        }
        val mask = BitmapUtils.bytesToAlpha8(full, docW, docH)
        return Selection.wrap(mask, if (maxX < 0) Rect() else Rect(minX, minY, maxX + 1, maxY + 1))
    }

    /** True when [packed] inflates to exactly the rows of [bounds] with a valid checksum. */
    fun isIntact(): Boolean {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0 || w.toLong() * h > Int.MAX_VALUE) return false
        val inflater = Inflater()
        return try {
            inflater.setInput(packed)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (!inflater.finished()) {
                val k = inflater.inflate(buf)
                if (k == 0 && !inflater.finished()) return false
                total += k
                if (total > w.toLong() * h) return false
            }
            total == w.toLong() * h && inflater.remaining == 0
        } catch (e: DataFormatException) {
            false
        } finally {
            inflater.end()
        }
    }

    companion object {
        const val MAX = 32
        const val MAX_TOTAL_BYTES = 32L shl 20

        /** Null for an empty selection. Runs on a worker. */
        fun of(id: Long, name: String, sel: Selection, revision: Long): SavedSelection? {
            if (sel.isEmpty) return null
            val b = Rect(sel.bounds)
            if (!b.intersect(0, 0, sel.width, sel.height)) return null
            val crop = if (b.left == 0 && b.top == 0 && b.width() == sel.width && b.height() == sel.height) sel.mask
            else Bitmap.createBitmap(sel.mask, b.left, b.top, b.width(), b.height())
            val rows = try {
                BitmapUtils.alpha8ToBytes(crop)
            } finally {
                if (crop !== sel.mask) crop.recycle()
            }
            return SavedSelection(id, name, b, deflate(rows), revision)
        }

        private fun deflate(raw: ByteArray): ByteArray {
            val deflater = Deflater(5)
            try {
                deflater.setInput(raw)
                deflater.finish()
                val out = ByteArrayOutputStream(maxOf(64, raw.size / 16))
                val buf = ByteArray(64 * 1024)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buf)
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        /** Inflates [packed] into [dst]; stops at the end of either. */
        private fun inflate(packed: ByteArray, dst: ByteArray) {
            val inflater = Inflater()
            try {
                inflater.setInput(packed)
                var off = 0
                while (off < dst.size && !inflater.finished()) {
                    val k = inflater.inflate(dst, off, dst.size - off)
                    if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    off += k
                }
            } catch (e: DataFormatException) {
                // Damaged data: what was inflated so far is kept, the rest stays unselected.
            } finally {
                inflater.end()
            }
        }
    }
}
