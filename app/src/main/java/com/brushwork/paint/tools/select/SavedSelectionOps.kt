package com.brushwork.paint.tools.select

import android.graphics.Rect
import com.brushwork.paint.engine.CanvasGeometry
import com.brushwork.paint.engine.CanvasResult
import com.brushwork.paint.model.SavedSelection
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v1.7 (item 14, §3.14; area G): saved-selection helpers: the mapping a canvas operation applies
 * to the saved selections ([mappedForCanvas]), and the crop rows and thumbnails the layer
 * window's rows show ([rows], [thumbnail]).
 *
 * Everything works on an entry's crop ([SavedSelection.bounds]), never on a document-size mask:
 * a canvas operation maps the entries one at a time (inflate the crop, map it row by row into the
 * Deflate stream of the result), so at most one entry's crop is alive while the layers' new
 * bitmaps are. Pure Kotlin on the inflated bytes, so the result is the same on every device.
 */
object SavedSelectionOps {
    /**
     * The saved selections after a canvas operation (resize, crop, trim, rotate, flip...).
     * `CanvasOps` calls it once per operation on its background thread, after mapping the layers;
     * [oldWidth] / [oldHeight] are the size before. The result goes into the operation's undo step.
     *
     * Each entry is mapped with the operation's [CanvasGeometry] (old document px to new) and
     * clipped to the new canvas ([CanvasResult.width] × [CanvasResult.height]):
     * - flips, quarter turns and whole-pixel moves (crop, canvas size) move the coverage exactly;
     * - a scale (Resize image) samples it bilinearly, several samples per pixel when it shrinks;
     *   the old document's edge pixels reach its border (as the layers' own resampling does), so
     *   a selection that touches the border still fully covers the new border;
     * - the new bounds are the tight bounds of what is left; an entry cropped to nothing is
     *   dropped.
     *
     * An entry that keeps its pixels (the identity geometry and inside the new canvas) is returned
     * as the very instance, and [list] itself when every entry is kept. A changed entry gets a NEW
     * [SavedSelection.packed] (the same id, name and revision: the controller gives it the next
     * revision, `EditorController.withNewSavedRevisions`).
     */
    fun mappedForCanvas(list: List<SavedSelection>, result: CanvasResult, oldWidth: Int, oldHeight: Int): List<SavedSelection> {
        if (list.isEmpty()) return list
        val g = result.geometry
        val w = result.width
        val h = result.height
        if (g.isIdentity && w == oldWidth && h == oldHeight) return list
        if (w <= 0 || h <= 0 || g.determinant == 0.0 || !g.determinant.isFinite()) return emptyList()
        var changed = false
        val out = ArrayList<SavedSelection>(list.size)
        for (e in list) {
            val m = mapped(e, g, w, h, oldWidth, oldHeight)
            if (m !== e) changed = true
            if (m != null) out += m
        }
        return if (changed) out else list
    }

    /**
     * [e] (on an [oldWidth] × [oldHeight] document) mapped by [g] onto a [w] × [h] canvas; [e]
     * itself when unchanged; null when nothing is left.
     */
    internal fun mapped(e: SavedSelection, g: CanvasGeometry, w: Int, h: Int, oldWidth: Int, oldHeight: Int): SavedSelection? {
        val b = e.bounds
        if (b.isEmpty) return null
        if (g.isIdentity && b.left >= 0 && b.top >= 0 && b.right <= w && b.bottom <= h) return e
        val target = mappedBounds(b, g, w, h) ?: return null
        val src = rows(e)
        val sampler = Sampler(src, b, g, oldWidth, oldHeight)
        val line = ByteArray(target.width())
        // Pass 1: the tight bounds of the mapped coverage (nothing stored).
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in target.top until target.bottom) {
            sampler.row(y, target.left, line)
            var first = -1
            var last = -1
            for (i in line.indices) if (line[i].toInt() != 0) { if (first < 0) first = i; last = i }
            if (first >= 0) {
                minX = min(minX, target.left + first); maxX = max(maxX, target.left + last)
                if (minY == Int.MAX_VALUE) minY = y
                maxY = y
            }
        }
        if (maxX < 0) return null
        val tight = Rect(minX, minY, maxX + 1, maxY + 1)
        // Pass 2: the rows of the tight bounds, straight into the Deflate stream.
        val packer = Packer(tight.width().toLong() * tight.height())
        val row = if (tight.width() == line.size) line else ByteArray(tight.width())
        try {
            for (y in tight.top until tight.bottom) {
                sampler.row(y, tight.left, row)
                packer.add(row)
            }
            return SavedSelection(e.id, e.name, tight, packer.finish(), e.revision)
        } finally {
            packer.end()
        }
    }

    /** The new-canvas px covered by [b] mapped through [g], within the [w] × [h] canvas; null when outside. */
    internal fun mappedBounds(b: Rect, g: CanvasGeometry, w: Int, h: Int): Rect? {
        val xs = doubleArrayOf(
            g.mapX(b.left.toDouble(), b.top.toDouble()), g.mapX(b.right.toDouble(), b.top.toDouble()),
            g.mapX(b.left.toDouble(), b.bottom.toDouble()), g.mapX(b.right.toDouble(), b.bottom.toDouble()),
        )
        val ys = doubleArrayOf(
            g.mapY(b.left.toDouble(), b.top.toDouble()), g.mapY(b.right.toDouble(), b.top.toDouble()),
            g.mapY(b.left.toDouble(), b.bottom.toDouble()), g.mapY(b.right.toDouble(), b.bottom.toDouble()),
        )
        // (A tiny tolerance keeps exact edges from growing by a pixel through rounding.)
        val l = floor(xs.min() + EDGE_EPS).toInt()
        val t = floor(ys.min() + EDGE_EPS).toInt()
        val r = ceil(xs.max() - EDGE_EPS).toInt()
        val bt = ceil(ys.max() - EDGE_EPS).toInt()
        val out = Rect(l, t, r, bt)
        if (out.isEmpty || !out.intersect(0, 0, w, h)) return null
        return out
    }

    /** True for flips, quarter turns and whole-pixel moves: every pixel lands on exactly one pixel. */
    internal fun isExact(g: CanvasGeometry): Boolean {
        fun unit(v: Double) = v == 0.0 || v == 1.0 || v == -1.0
        fun whole(v: Double) = v.isFinite() && v == floor(v)
        return unit(g.a) && unit(g.b) && unit(g.c) && unit(g.d) && abs(g.determinant) == 1.0 && whole(g.tx) && whole(g.ty)
    }

    /**
     * Reads the coverage of a mapped entry: each new pixel's centre goes back through the inverse
     * geometry. Exact maps take the one source pixel there; scales average [n] × [n] bilinear
     * samples (n > 1 only when the map shrinks). A sample in the outer half pixel of the old
     * [oldW] × [oldH] document reads its edge pixel, not a blend with the nothing beyond it.
     */
    private class Sampler(private val src: ByteArray, private val b: Rect, g: CanvasGeometry, private val oldW: Int, private val oldH: Int) {
        private val inv = g.inverse()
        private val exact = isExact(g)
        private val sw = b.width()
        private val sh = b.height()
        private val n: Int = run {
            val stretch = max(abs(inv.a) + abs(inv.b), abs(inv.c) + abs(inv.d))
            ceil(stretch - 1e-9).toInt().coerceIn(1, MAX_SAMPLES)
        }

        fun row(y: Int, x0: Int, out: ByteArray) {
            if (exact) {
                val cy = y + 0.5
                for (i in out.indices) {
                    val cx = x0 + i + 0.5
                    val sx = floor(inv.mapX(cx, cy)).toInt() - b.left
                    val sy = floor(inv.mapY(cx, cy)).toInt() - b.top
                    out[i] = if (sx in 0 until sw && sy in 0 until sh) src[sy * sw + sx] else 0.toByte()
                }
                return
            }
            val step = 1.0 / n
            for (i in out.indices) {
                var sum = 0.0
                for (j in 0 until n) {
                    val py = y + (j + 0.5) * step
                    for (k in 0 until n) {
                        val px = x0 + i + (k + 0.5) * step
                        sum += bilinear(clamp(inv.mapX(px, py), oldW), clamp(inv.mapY(px, py), oldH))
                    }
                }
                out[i] = (sum / (n * n)).roundToInt().coerceIn(0, 255).toByte()
            }
        }

        /** [v] inside the old document's [size] moved onto its edge pixels' centres; outside it, as it is. */
        private fun clamp(v: Double, size: Int): Double =
            if (v < 0.0 || v > size) v else v.coerceIn(0.5, max(0.5, size - 0.5))

        /** The coverage (0..255) at document point ([x], [y]), interpolated between pixel centres. */
        private fun bilinear(x: Double, y: Double): Double {
            val fx = x - 0.5 - b.left
            val fy = y - 0.5 - b.top
            val ix = floor(fx).toInt()
            val iy = floor(fy).toInt()
            if (ix < -1 || iy < -1 || ix >= sw || iy >= sh) return 0.0
            val tx = fx - ix
            val ty = fy - iy
            val v00 = at(ix, iy); val v10 = at(ix + 1, iy)
            val v01 = at(ix, iy + 1); val v11 = at(ix + 1, iy + 1)
            val top = v00 + (v10 - v00) * tx
            val bottom = v01 + (v11 - v01) * tx
            return top + (bottom - top) * ty
        }

        private fun at(x: Int, y: Int): Double =
            if (x in 0 until sw && y in 0 until sh) (src[y * sw + x].toInt() and 0xFF).toDouble() else 0.0
    }

    /** A Deflate (zlib, level 5) stream fed row by row: the format of [SavedSelection.packed]. */
    private class Packer(rawSize: Long) {
        private val deflater = Deflater(PACK_LEVEL)
        private val out = ByteArrayOutputStream(max(64L, min(rawSize / 16, Int.MAX_VALUE.toLong() / 2)).toInt())
        private val buf = ByteArray(64 * 1024)

        fun add(row: ByteArray) {
            deflater.setInput(row, 0, row.size)
            while (!deflater.needsInput()) {
                val k = deflater.deflate(buf)
                if (k > 0) out.write(buf, 0, k)
            }
        }

        fun finish(): ByteArray {
            deflater.finish()
            while (!deflater.finished()) {
                val k = deflater.deflate(buf)
                out.write(buf, 0, k)
            }
            return out.toByteArray()
        }

        fun end() = deflater.end()
    }

    /**
     * The ALPHA_8 rows of [e] inside its bounds (width × height bytes, top row first). Data that
     * ends early (or is damaged) leaves the rest unselected, as `SavedSelection.toSelection` does.
     */
    fun rows(e: SavedSelection): ByteArray {
        val w = e.bounds.width()
        val h = e.bounds.height()
        if (w <= 0 || h <= 0) return ByteArray(0)
        val dst = ByteArray(w * h)
        val inflater = Inflater()
        try {
            inflater.setInput(e.packed)
            var off = 0
            while (off < dst.size && !inflater.finished()) {
                val k = inflater.inflate(dst, off, dst.size - off)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                off += k
            }
        } catch (x: DataFormatException) {
            // Damaged data: what was inflated so far is kept.
        } finally {
            inflater.end()
        }
        return dst
    }

    /**
     * Inflates [e]'s crop one row at a time into [row] (its width) and hands each row's index
     * (0 = the top) to [onRow]; reuses [row]. Data that ends early (or is damaged) stops after the
     * row it ends in, the rest of that row unselected, as [rows] reads it (the rows after it are
     * unselected and not handed over).
     */
    private inline fun eachRow(e: SavedSelection, row: ByteArray, onRow: (Int) -> Unit) {
        val h = e.bounds.height()
        if (row.isEmpty() || h <= 0) return
        val inflater = Inflater()
        try {
            inflater.setInput(e.packed)
            for (r in 0 until h) {
                var off = 0
                var ended = false
                try {
                    while (off < row.size) {
                        if (inflater.finished()) { ended = true; break }
                        val k = inflater.inflate(row, off, row.size - off)
                        if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) { ended = true; break }
                        off += k
                    }
                } catch (x: DataFormatException) {
                    ended = true
                }
                if (ended) {
                    if (off > 0) { row.fill(0, off, row.size); onRow(r) }
                    return
                }
                onRow(r)
            }
        } finally {
            inflater.end()
        }
    }

    /**
     * A [tw] × [th] coverage thumbnail of [e] on a [docW] × [docH] document (each thumbnail pixel
     * the mean coverage of the document px it covers), for the layer window's rows. Runs on a
     * worker, one per row shown, so it never holds the crop: it inflates one crop row at a time
     * into a single row buffer (a full-canvas entry of a 4000 × 5000 document would otherwise be
     * 20 MB per thumbnail).
     */
    fun thumbnail(e: SavedSelection, docW: Int, docH: Int, tw: Int, th: Int): ByteArray {
        val out = ByteArray(max(0, tw) * max(0, th))
        if (tw <= 0 || th <= 0 || docW <= 0 || docH <= 0) return out
        val eb = e.bounds
        val b = Rect(eb)
        if (eb.width() <= 0 || eb.height() <= 0 || !b.intersect(0, 0, docW, docH)) return out
        val sums = LongArray(tw * th)
        // The thumbnail column of each document column inside the bounds.
        val txOf = IntArray(b.width()) { ((b.left + it).toLong() * tw / docW).toInt().coerceIn(0, tw - 1) }
        val skip = b.left - eb.left
        val row = ByteArray(eb.width())
        eachRow(e, row) { r ->
            val y = eb.top + r
            if (y >= b.top && y < b.bottom) {
                val base = (y.toLong() * th / docH).toInt().coerceIn(0, th - 1) * tw
                for (i in txOf.indices) {
                    val v = row[skip + i].toInt() and 0xFF
                    if (v != 0) sums[base + txOf[i]] += v.toLong()
                }
            }
        }
        // Document px per thumbnail cell (the cells' areas differ by at most one row or column).
        val area = docW.toDouble() / tw * (docH.toDouble() / th)
        for (i in sums.indices) {
            if (sums[i] == 0L) continue
            // A covered cell shows at least faintly, however small the selection.
            out[i] = (sums[i] / area).roundToInt().coerceIn(MIN_VISIBLE, 255).toByte()
        }
        return out
    }

    /** Deflate level of [SavedSelection.packed] (`SavedSelection.of` uses the same). */
    private const val PACK_LEVEL = 5

    /** Most samples per axis when a scale shrinks the canvas (Resize image to a quarter and below). */
    private const val MAX_SAMPLES = 4

    private const val EDGE_EPS = 1e-6

    /** The faintest thumbnail pixel of a covered cell. */
    private const val MIN_VISIBLE = 48
}
