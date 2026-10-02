package com.brushwork.paint.exchange.image

import com.brushwork.paint.core.Parallel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Pure-Kotlin image encoding for SVG / PDF export and payload restore (v1.5 §4.10, A8): PNG files
 * (data URIs in SVG) and PNG-predicted zlib streams (PDF image XObjects) are written from the
 * same filtered rows, deflated in parallel bands (pigz style) so a 4 MP layer uses every core of
 * the phone, and streamed band by band (memory stays a few bands whatever the image size).
 */

/** Non-premultiplied ARGB pixels ([Color] ints), row-major, [width] x [height]. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width >= 0 && height >= 0 && pixels.size >= width.toLong() * height) { "Pixel array too small" }
    }

    /** True when every pixel is fully opaque. */
    fun isOpaque(): Boolean {
        val n = width * height
        for (i in 0 until n) if ((pixels[i] ushr 24) != 0xFF) return false
        return true
    }
}

/** Which bytes a row of an [ArgbImage] becomes. */
enum class RowLayout(val bytesPerPixel: Int) {
    /** R, G, B, A (PNG color type 6). */
    RGBA(4),

    /** R, G, B (PNG color type 2, PDF DeviceRGB). */
    RGB(3),

    /** Alpha only (PDF soft mask). */
    ALPHA(1),

    /** Rec. 601 luma of R, G, B (gray masks: PNG color type 0, PDF DeviceGray). */
    GRAY(1),
}

/**
 * Writes the zlib stream of an image's PNG-filtered rows (a filter-type byte, then the filtered
 * bytes, per row: what PNG's IDAT and a PDF FlateDecode stream with `/Predictor 15` hold).
 */
object FilteredZlib {
    /** Raw bytes per band handed to one deflater. */
    private const val BAND_BYTES = 256 * 1024

    /**
     * Streams the zlib data of [img] laid out as [layout] to [sink] (called on the calling thread,
     * in order, possibly several times). [level] is the Deflater level; [cancelled] is polled
     * between bands (a [CancellationException] is thrown then).
     */
    fun write(img: ArgbImage, layout: RowLayout, level: Int, sink: (ByteArray, Int, Int) -> Unit, cancelled: () -> Boolean = { false }) {
        val w = img.width
        val h = img.height
        val bpp = layout.bytesPerPixel
        val rowBytes = w * bpp
        val stride = rowBytes + 1
        // zlib header (deflate, 32 K window, default compression flag).
        sink(byteArrayOf(0x78, 0x9C.toByte()), 0, 2)
        val adler = Adler32()
        if (h == 0 || w == 0) {
            // Zero rows still need a finished deflate stream; a zero-width image has a filter byte per row.
            val raw = ByteArray(if (w == 0) h else 0)
            val out = deflateBand(raw, raw.size, level, last = true)
            adler.update(raw, 0, raw.size)
            sink(out, 0, out.size)
            writeAdler(adler, sink)
            return
        }
        val bandRows = max(1, BAND_BYTES / stride)
        val bands = (h + bandRows - 1) / bandRows
        val threads = max(1, min(Parallel.threadCount, 8))
        var b = 0
        while (b < bands) {
            if (cancelled()) throw CancellationException("Export stopped")
            val batch = min(threads, bands - b)
            val raws = arrayOfNulls<ByteArray>(batch)
            val outs = arrayOfNulls<ByteArray>(batch)
            val first = b
            Parallel.forRange(batch, 1) { s, e ->
                val prev = ByteArray(rowBytes)
                val cur = ByteArray(rowBytes)
                for (k in s until e) {
                    val band = first + k
                    val y0 = band * bandRows
                    val y1 = min(h, y0 + bandRows)
                    val raw = ByteArray((y1 - y0) * stride)
                    if (y0 > 0) rowBytesOf(img, layout, y0 - 1, prev) else prev.fill(0)
                    for (y in y0 until y1) {
                        rowBytesOf(img, layout, y, cur)
                        filterRow(cur, prev, bpp, raw, (y - y0) * stride)
                        cur.copyInto(prev)
                    }
                    raws[k] = raw
                    outs[k] = deflateBand(raw, raw.size, level, last = band == bands - 1)
                }
            }
            for (k in 0 until batch) {
                val raw = raws[k]!!
                adler.update(raw, 0, raw.size)
                val out = outs[k]!!
                sink(out, 0, out.size)
            }
            b += batch
        }
        writeAdler(adler, sink)
    }

    /** The whole zlib stream of [img] as one array (small images, tests). */
    fun toByteArray(img: ArgbImage, layout: RowLayout, level: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        write(img, layout, level, { b, o, n -> out.write(b, o, n) })
        return out.toByteArray()
    }

    private fun writeAdler(adler: Adler32, sink: (ByteArray, Int, Int) -> Unit) {
        val v = adler.value
        sink(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()), 0, 4)
    }

    /** Raw deflate of [raw]: sync-flushed (byte aligned, not final) unless [last]. */
    private fun deflateBand(raw: ByteArray, n: Int, level: Int, last: Boolean): ByteArray {
        val d = Deflater(level, true)
        try {
            d.setInput(raw, 0, n)
            val out = ByteArrayOutputStream(max(64, n / 3))
            val buf = ByteArray(32 * 1024)
            if (last) {
                d.finish()
                while (!d.finished()) {
                    val k = d.deflate(buf)
                    out.write(buf, 0, k)
                }
            } else {
                while (true) {
                    val k = d.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
                    out.write(buf, 0, k)
                    if (k < buf.size && d.needsInput()) break
                }
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    /** The unfiltered bytes of row [y] of [img] as [layout]. */
    fun rowBytesOf(img: ArgbImage, layout: RowLayout, y: Int, out: ByteArray) {
        val w = img.width
        val px = img.pixels
        var i = y * w
        var o = 0
        when (layout) {
            RowLayout.RGBA -> for (x in 0 until w) {
                val c = px[i++]
                out[o] = (c shr 16).toByte(); out[o + 1] = (c shr 8).toByte(); out[o + 2] = c.toByte(); out[o + 3] = (c ushr 24).toByte()
                o += 4
            }
            RowLayout.RGB -> for (x in 0 until w) {
                val c = px[i++]
                out[o] = (c shr 16).toByte(); out[o + 1] = (c shr 8).toByte(); out[o + 2] = c.toByte()
                o += 3
            }
            RowLayout.ALPHA -> for (x in 0 until w) out[o++] = (px[i++] ushr 24).toByte()
            RowLayout.GRAY -> for (x in 0 until w) {
                val c = px[i++]
                out[o++] = luma(c).toByte()
            }
        }
    }

    /** Rec. 601 luma (0..255) of an ARGB color, as the compositor's mask paint reads masks. */
    fun luma(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114 + 500) / 1000
    }

    /**
     * Writes the filter byte and the filtered bytes of [cur] (previous row [prev]) at [off] of
     * [out]: the PNG filter (None, Sub, Up or Paeth) with the smallest sum of absolute values.
     */
    internal fun filterRow(cur: ByteArray, prev: ByteArray, bpp: Int, out: ByteArray, off: Int) {
        val n = cur.size
        var best = 0
        var bestSum = Long.MAX_VALUE
        for (f in intArrayOf(0, 1, 2, 4)) {
            var sum = 0L
            for (i in 0 until n) {
                val v = filtered(f, cur, prev, i, bpp)
                sum += abs(v.toByte().toInt())
                if (sum >= bestSum) break
            }
            if (sum < bestSum) { bestSum = sum; best = f }
        }
        out[off] = best.toByte()
        for (i in 0 until n) out[off + 1 + i] = filtered(best, cur, prev, i, bpp).toByte()
    }

    private fun filtered(f: Int, cur: ByteArray, prev: ByteArray, i: Int, bpp: Int): Int {
        val x = cur[i].toInt() and 0xFF
        return when (f) {
            0 -> x
            1 -> x - (if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0)
            2 -> x - (prev[i].toInt() and 0xFF)
            3 -> x - (((if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0) + (prev[i].toInt() and 0xFF)) ushr 1)
            else -> x - paeth(if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0, prev[i].toInt() and 0xFF, if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0)
        }
    }

    internal fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /**
     * Reverses the PNG filter of one row in place: [row] is the filter byte then [rowBytes] bytes;
     * [prev] the row above, already unfiltered (same layout), or null for the first row.
     */
    internal fun unfilterRow(row: ByteArray, prev: ByteArray?, rowBytes: Int, bpp: Int) {
        val f = row[0].toInt() and 0xFF
        if (f == 0) return
        if (f > 4) throw IOException("Unknown PNG filter $f")
        for (i in 0 until rowBytes) {
            val idx = 1 + i
            val a = if (i >= bpp) row[idx - bpp].toInt() and 0xFF else 0
            val b = if (prev != null) prev[idx].toInt() and 0xFF else 0
            val c = if (prev != null && i >= bpp) prev[idx - bpp].toInt() and 0xFF else 0
            val x = row[idx].toInt() and 0xFF
            val v = when (f) {
                1 -> x + a
                2 -> x + b
                3 -> x + ((a + b) ushr 1)
                else -> x + paeth(a, b, c)
            }
            row[idx] = v.toByte()
        }
    }

    /**
     * Inflates zlib [data] row by row: each row of 1 + [rowBytes] bytes (the PNG filter byte
     * first) is unfiltered and handed to [onRow] with its index; the array is reused (the row
     * above stays valid until the next call). False when the data ends early or is damaged.
     * Memory: two rows, whatever the image size.
     */
    internal fun inflateRows(data: ByteArray, rows: Int, rowBytes: Int, bpp: Int, onRow: (ByteArray, Int) -> Unit): Boolean {
        val inf = Inflater()
        try {
            inf.setInput(data)
            var cur = ByteArray(rowBytes + 1)
            var prev = ByteArray(rowBytes + 1)
            for (y in 0 until rows) {
                var n = 0
                while (n < cur.size) {
                    val k = inf.inflate(cur, n, cur.size - n)
                    if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) return false
                    n += k
                }
                unfilterRow(cur, if (y > 0) prev else null, rowBytes, bpp)
                onRow(cur, y)
                val t = prev
                prev = cur
                cur = t
            }
            return true
        } catch (e: DataFormatException) {
            return false
        } catch (e: IOException) {
            return false
        } finally {
            inf.end()
        }
    }

    /** Reverses PNG row filters in place: [raw] holds rows of 1 + [rowBytes] bytes. */
    internal fun unfilter(raw: ByteArray, rows: Int, rowBytes: Int, bpp: Int) {
        val stride = rowBytes + 1
        for (y in 0 until rows) {
            val o = y * stride
            val f = raw[o].toInt() and 0xFF
            val p = o - stride // previous row's filter byte
            for (i in 0 until rowBytes) {
                val idx = o + 1 + i
                val a = if (i >= bpp) raw[idx - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) raw[p + 1 + i].toInt() and 0xFF else 0
                val c = if (y > 0 && i >= bpp) raw[p + 1 + i - bpp].toInt() and 0xFF else 0
                val x = raw[idx].toInt() and 0xFF
                val v = when (f) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) ushr 1)
                    4 -> x + paeth(a, b, c)
                    else -> throw IOException("Unknown PNG filter $f")
                }
                raw[idx] = v.toByte()
            }
        }
    }
}

/** PNG files from [ArgbImage]s (8-bit RGBA, or RGB when opaque, or gray). */
object PngEncoder {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * Writes [img] as a PNG to [out]: RGBA, RGB when every pixel is opaque, or 8-bit gray with
     * [gray]. Each band of compressed data is its own IDAT chunk (valid PNG, streamed).
     */
    fun encode(img: ArgbImage, out: OutputStream, gray: Boolean = false, level: Int = 6, cancelled: () -> Boolean = { false }) {
        val layout = when {
            gray -> RowLayout.GRAY
            img.isOpaque() -> RowLayout.RGB
            else -> RowLayout.RGBA
        }
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, img.width)
        putInt(ihdr, 4, img.height)
        ihdr[8] = 8
        ihdr[9] = when (layout) { RowLayout.RGBA -> 6; RowLayout.RGB -> 2; else -> 0 }.toByte()
        ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        chunk(out, "IHDR", ihdr, 0, ihdr.size)
        // Small writes (header, checksum) are merged into the next band's chunk.
        val pending = ByteArrayOutputStream()
        FilteredZlib.write(img, layout, level, { b, o, n ->
            pending.write(b, o, n)
            if (pending.size() >= 4096) {
                val bytes = pending.toByteArray()
                chunk(out, "IDAT", bytes, 0, bytes.size)
                pending.reset()
            }
        }, cancelled)
        if (pending.size() > 0) {
            val bytes = pending.toByteArray()
            chunk(out, "IDAT", bytes, 0, bytes.size)
        }
        chunk(out, "IEND", ByteArray(0), 0, 0)
    }

    /** [encode] into a byte array. */
    fun toByteArray(img: ArgbImage, gray: Boolean = false, level: Int = 6): ByteArray =
        ByteArrayOutputStream().also { encode(img, it, gray, level) }.toByteArray()

    private fun chunk(out: OutputStream, type: String, data: ByteArray, off: Int, len: Int) {
        val head = ByteArray(8)
        putInt(head, 0, len)
        for (i in 0 until 4) head[4 + i] = type[i].code.toByte()
        out.write(head)
        out.write(data, off, len)
        val crc = CRC32()
        crc.update(head, 4, 4)
        crc.update(data, off, len)
        val c = ByteArray(4)
        putInt(c, 0, crc.value.toInt())
        out.write(c)
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}

/**
 * Reads PNG files (8-bit gray, gray + alpha, RGB, RGBA and palette images, not interlaced):
 * exactly what [PngEncoder] writes and what Brushwork payload images are. Returns null for other
 * kinds (16-bit, interlaced): callers fall back to the platform decoder.
 */
object PngDecoder {
    /** Largest image decoded (pixels). */
    const val MAX_PIXELS = 64L * 1024 * 1024

    fun decode(bytes: ByteArray, maxPixels: Long = MAX_PIXELS): ArgbImage? {
        if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte() || bytes[2] != 'N'.code.toByte() || bytes[3] != 'G'.code.toByte()) return null
        var pos = 8
        var w = -1
        var h = -1
        var depth = 0
        var type = -1
        var interlace = 0
        var palette: IntArray? = null
        var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= bytes.size) {
            val len = getInt(bytes, pos)
            if (len < 0 || pos + 12L + len > bytes.size) return null
            val t = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val d = pos + 8
            when (t) {
                "IHDR" -> {
                    if (len < 13) return null
                    w = getInt(bytes, d); h = getInt(bytes, d + 4)
                    depth = bytes[d + 8].toInt() and 0xFF
                    type = bytes[d + 9].toInt() and 0xFF
                    interlace = bytes[d + 12].toInt() and 0xFF
                }
                "PLTE" -> palette = IntArray(len / 3) { i ->
                    val k = d + i * 3
                    (0xFF shl 24) or ((bytes[k].toInt() and 0xFF) shl 16) or ((bytes[k + 1].toInt() and 0xFF) shl 8) or (bytes[k + 2].toInt() and 0xFF)
                }
                "tRNS" -> trns = bytes.copyOfRange(d, d + len)
                "IDAT" -> idat.write(bytes, d, len)
                "IEND" -> break
            }
            pos += 12 + len
        }
        if (w <= 0 || h <= 0 || depth != 8 || interlace != 0) return null
        if (w.toLong() * h > maxPixels) return null
        val channels = when (type) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> return null }
        val rowBytes = w * channels
        val pal = palette
        val tr = trns
        if (type == 3 && pal == null) return null
        // Rows are inflated and unfiltered one at a time straight into the pixels (no copy of the
        // whole filtered image: a 4000 x 5000 layer needs 80 MB less).
        val px = IntArray(w * h)
        var bad = false
        val ok = FilteredZlib.inflateRows(idat.toByteArray(), h, rowBytes, channels) { raw, y ->
            var o = 1
            var i = y * w
            for (x in 0 until w) {
                px[i++] = when (type) {
                    0 -> {
                        val g = raw[o].toInt() and 0xFF
                        val a = if (tr != null && tr.size >= 2 && ((tr[0].toInt() and 0xFF) shl 8 or (tr[1].toInt() and 0xFF)) == g) 0 else 0xFF
                        (a shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    2 -> (0xFF shl 24) or ((raw[o].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
                    3 -> {
                        val k = raw[o].toInt() and 0xFF
                        val c = pal!!.getOrNull(k)
                        if (c == null) {
                            bad = true
                            0
                        } else {
                            val a = if (tr != null && k < tr.size) tr[k].toInt() and 0xFF else 0xFF
                            (a shl 24) or (c and 0xFFFFFF)
                        }
                    }
                    4 -> {
                        val g = raw[o].toInt() and 0xFF
                        ((raw[o + 1].toInt() and 0xFF) shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    else -> ((raw[o + 3].toInt() and 0xFF) shl 24) or ((raw[o].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
                }
                o += channels
            }
        }
        if (!ok || bad) return null
        return ArgbImage(w, h, px)
    }

    /** Size of a PNG ([width], [height]) from its header, or null. */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || bytes[1] != 'P'.code.toByte() || String(bytes, 12, 4, Charsets.ISO_8859_1) != "IHDR") return null
        return getInt(bytes, 16) to getInt(bytes, 20)
    }

    private fun getInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
