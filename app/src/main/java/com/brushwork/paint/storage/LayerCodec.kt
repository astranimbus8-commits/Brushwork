package com.brushwork.paint.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.PerfTrace
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import kotlin.math.max

/** Thrown when a project file exists but can't be decoded. */
class CorruptProjectException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Lossless pixel files (`layer_<id>_r<rev>.bin`, `mask_<id>_r<rev>.bin`): a small header (magic, version,
 * width, height, byte length) followed by the raw premultiplied ARGB_8888 bytes exactly as
 * [Bitmap.copyPixelsToBuffer] produces them, zlib-compressed with [Deflater.BEST_SPEED].
 */
internal object LayerCodec {
    private const val MAGIC = 0x42574C59 // "BWLY"
    private const val VERSION = 1
    private const val IO_BUFFER = 64 * 1024

    /** Raw byte length of a [width] x [height] ARGB_8888 bitmap. */
    fun byteLength(width: Int, height: Int): Int {
        val n = width.toLong() * height * 4
        require(width > 0 && height > 0 && n <= Int.MAX_VALUE) { "Invalid layer size ${width}x$height" }
        return n.toInt()
    }

    /**
     * Copies [bitmap]'s pixels into [dst] as premultiplied sRGB ARGB_8888 bytes (MAIN thread for
     * document bitmaps). Bitmaps in another format or color space (a wide-gamut photo, a
     * hardware bitmap) are converted through a temporary copy.
     */
    fun copyPixels(bitmap: Bitmap, dst: ByteArray) {
        val len = byteLength(bitmap.width, bitmap.height)
        require(dst.size >= len)
        if (isStorable(bitmap)) {
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(dst, 0, len))
            return
        }
        val tmp = toStorable(bitmap)
        try {
            tmp.copyPixelsToBuffer(ByteBuffer.wrap(dst, 0, len))
        } finally {
            tmp.recycle()
        }
    }

    /**
     * True when [bitmap]'s raw bytes are exactly what pixel files hold: ARGB_8888 without row
     * padding, premultiplied (or opaque) and in sRGB.
     */
    fun isStorable(bitmap: Bitmap): Boolean {
        if (bitmap.config != Bitmap.Config.ARGB_8888) return false
        if (bitmap.rowBytes != bitmap.width * 4) return false
        if (bitmap.hasAlpha() && !bitmap.isPremultiplied) return false
        val cs = bitmap.colorSpace
        return cs == null || cs == SRGB
    }

    /** A new premultiplied sRGB ARGB_8888 copy of [bitmap] (colors converted by Skia). The caller owns it. */
    fun toStorable(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val out = BitmapUtils.createLayerBitmap(w, h)
        try {
            if (bitmap.hasAlpha() && !bitmap.isPremultiplied) {
                // Canvas refuses unpremultiplied bitmaps. getPixels returns exactly the stored
                // (unpremultiplied) colors converted to sRGB; setPixels premultiplies them.
                val band = max(1, (1 shl 20) / w).coerceAtMost(h)
                val row = IntArray(w * band)
                var y = 0
                while (y < h) {
                    val n = minOf(band, h - y)
                    bitmap.getPixels(row, 0, w, 0, y, w, n)
                    out.setPixels(row, 0, w, 0, y, w, n)
                    y += n
                }
                return out
            }
            val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            try {
                Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
            } finally {
                if (src !== bitmap) src.recycle()
            }
            return out
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
    }

    private val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    /** Writes [length] bytes of [data] as a pixel file (atomically). Blocking: call on IO. */
    fun write(file: File, width: Int, height: Int, data: ByteArray, length: Int = byteLength(width, height)) {
        require(length == byteLength(width, height) && data.size >= length)
        writeStream(file, width, height, length) { it.write(data, 0, length) }
    }

    /**
     * Writes a pixel file whose every row equals [row] (a uniformly filled layer) without
     * allocating a full-size buffer. [row] must hold `width * 4` bytes.
     */
    fun writeRepeatedRow(file: File, width: Int, height: Int, row: ByteArray) {
        require(row.size == width * 4)
        writeStream(file, width, height, byteLength(width, height)) { out -> repeat(height) { out.write(row) } }
    }

    private fun writeStream(file: File, width: Int, height: Int, length: Int, body: (OutputStream) -> Unit) {
        // v1.9 (design 2.4.4): every pixel file written is timed as PerfTrace.SAVE_LAYER.
        PerfTrace.span(PerfTrace.SAVE_LAYER) {
            ProjectFormat.writeAtomically(file) { out -> writeBody(out, width, height, length, body) }
        }
    }

    /**
     * v1.9 (A7b step 4, R9): [bitmap]'s pixels for a banded write ([writeBands]), taken at once
     * (MAIN thread for document bitmaps): a native copy whose bytes are exactly what [copyPixels]
     * gives, so it costs no Java heap. A storable bitmap is copied as is; another one is
     * converted ([toStorable]). The caller owns and recycles it.
     */
    fun snapshot(bitmap: Bitmap): Bitmap {
        if (!isStorable(bitmap)) return toStorable(bitmap)
        return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw OutOfMemoryError("No memory to copy a ${bitmap.width}x${bitmap.height} layer")
    }

    /** Rows of a [width]-pixel band that fit in [bandBytes] (at least one row). */
    fun bandRows(width: Int, bandBytes: Int): Int = max(1, bandBytes / (width * 4))

    /**
     * v1.9 (A7b step 4, R9): writes [snapshot] (a storable bitmap, see [snapshot]) as a pixel file
     * (atomically), a band of rows at a time through [scratch], which holds at least one row.
     * The deflater gets the same bytes in the same order as [write] gives it, without a flush, so
     * the file is byte-identical to [write]'s of the same pixels. Blocking: call on IO.
     */
    fun writeBands(file: File, snapshot: Bitmap, scratch: ByteArray) {
        val w = snapshot.width
        val h = snapshot.height
        val rowBytes = w * 4
        require(isStorable(snapshot) && scratch.size >= rowBytes)
        val rows = minOf(scratch.size / rowBytes, h)
        writeStream(file, w, h, byteLength(w, h)) { out ->
            var y = 0
            while (y < h) {
                val n = minOf(rows, h - y)
                val band = if (n == h) snapshot else Bitmap.createBitmap(snapshot, 0, y, w, n)
                try {
                    check(band.byteCount == n * rowBytes && isStorable(band)) { "Unexpected band format ${band.config}" }
                    band.copyPixelsToBuffer(ByteBuffer.wrap(scratch, 0, n * rowBytes))
                } finally {
                    if (band !== snapshot) band.recycle()
                }
                out.write(scratch, 0, n * rowBytes)
                y += n
            }
        }
    }

    /**
     * v1.7: the bytes of a pixel file (header and compressed pixels) written to [out], which is
     * left open; the array container (`ArrayCodec`, PIXELS) embeds them. Blocking: call on IO.
     */
    fun writeTo(out: OutputStream, width: Int, height: Int, data: ByteArray, length: Int = byteLength(width, height)) {
        require(length == byteLength(width, height) && data.size >= length)
        writeBody(out, width, height, length) { it.write(data, 0, length) }
    }

    private fun writeBody(out: OutputStream, width: Int, height: Int, length: Int, body: (OutputStream) -> Unit) {
        val header = DataOutputStream(out)
        header.writeInt(MAGIC)
        header.writeInt(VERSION)
        header.writeInt(width)
        header.writeInt(height)
        header.writeInt(length)
        header.flush()
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            val zip = DeflaterOutputStream(out, deflater, IO_BUFFER)
            body(zip)
            zip.finish()
        } finally {
            deflater.end()
        }
    }

    /**
     * Reads a pixel file into [dst] (at least `width * height * 4` bytes). Throws
     * [CorruptProjectException] when the file is truncated, damaged or has another size.
     * Blocking: call on IO.
     */
    fun read(file: File, width: Int, height: Int, dst: ByteArray) {
        val length = byteLength(width, height)
        require(dst.size >= length)
        if (!file.isFile) throw CorruptProjectException("missing file ${file.name}")
        try {
            FileInputStream(file).use { fis ->
                val input = BufferedInputStream(fis, IO_BUFFER)
                val (w, h, len) = readHeader(DataInputStream(input), file.name)
                if (w != width || h != height || len != length) {
                    throw CorruptProjectException("${file.name} is ${w}x$h, expected ${width}x$height")
                }
                inflateInto(input, file.name, dst, length)
            }
        } catch (e: CorruptProjectException) {
            throw e
        } catch (e: EOFException) {
            throw CorruptProjectException("${file.name} is truncated", e)
        } catch (e: ZipException) {
            throw CorruptProjectException("${file.name} is damaged", e)
        }
    }

    /**
     * v1.7: the pixels of a pixel file embedded at the end of [input] (see [writeTo]) as a new
     * bitmap the caller owns, at most [maxWidth] × [maxHeight]. [name] names the data in errors.
     * Throws [CorruptProjectException] when it is truncated, damaged (the zlib checksum), inflates
     * to more bytes than its header says or is too large. Bytes after the compressed stream are
     * not read. Blocking: call on IO.
     *
     * v1.10 (A11, design 2.9): read a [Band] of rows at a time (at most [BAND_BYTES_MAX] of Java
     * heap), never through a buffer the size of the bitmap.
     */
    fun readBitmap(input: InputStream, name: String, maxWidth: Int, maxHeight: Int): Bitmap {
        try {
            val buffered = BufferedInputStream(input, IO_BUFFER)
            val (w, h, len) = readHeader(DataInputStream(buffered), name)
            if (w <= 0 || h <= 0 || w > maxWidth || h > maxHeight || len.toLong() != w.toLong() * h * 4) {
                throw CorruptProjectException("$name is ${w}x$h, larger than ${maxWidth}x$maxHeight or inconsistent")
            }
            val bitmap = BitmapUtils.createLayerBitmap(w, h)
            try {
                check(bitmap.byteCount == len) { "Unexpected bitmap format ${bitmap.config}" }
                Band(w, minOf(h, bandRows(w, BAND_BYTES_MAX))).use { band ->
                    val canvas = Canvas(bitmap)
                    inflateBands(buffered, name, w, h, band.bytes) { y, n ->
                        band.load()
                        band.copyRows(canvas, 0, w, n, 0, y)
                    }
                }
            } catch (e: Throwable) {
                bitmap.recycle()
                throw e
            }
            return bitmap
        } catch (e: CorruptProjectException) {
            throw e
        } catch (e: EOFException) {
            throw CorruptProjectException("$name is truncated", e)
        } catch (e: ZipException) {
            throw CorruptProjectException("$name is damaged", e)
        }
    }

    /** Reads and checks the magic and version; returns (width, height, byte length). */
    private fun readHeader(header: DataInputStream, name: String): Triple<Int, Int, Int> {
        if (header.readInt() != MAGIC) throw CorruptProjectException("$name is not a layer file")
        val version = header.readInt()
        if (version !in 1..VERSION) throw CorruptProjectException("$name has unsupported version $version")
        val w = header.readInt()
        val h = header.readInt()
        val len = header.readInt()
        return Triple(w, h, len)
    }

    /** Inflates exactly [length] bytes into [dst] and checks that the stream ends there. */
    private fun inflateInto(input: InputStream, name: String, dst: ByteArray, length: Int) {
        val inflater = Inflater()
        try {
            val zip = InflaterInputStream(input, inflater, IO_BUFFER)
            var off = 0
            while (off < length) {
                val n = zip.read(dst, off, length - off)
                if (n < 0) throw CorruptProjectException("$name is truncated")
                off += n
            }
            // Reading past the end makes zlib verify the stream's checksum.
            if (zip.read() != -1) throw CorruptProjectException("$name has trailing data")
        } finally {
            inflater.end()
        }
    }

    /** Reads a pixel file straight into [bitmap] using [scratch] as the transfer buffer. */
    fun readInto(file: File, bitmap: Bitmap, scratch: ByteArray) {
        read(file, bitmap.width, bitmap.height, scratch)
        val len = byteLength(bitmap.width, bitmap.height)
        check(bitmap.byteCount == len) { "Unexpected bitmap format ${bitmap.config}" }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(scratch, 0, len))
    }

    // ------------------------------------------------------------------ v1.10 (A11): bands

    /** v1.10 (A11): the Java band of [readBitmap] at most (an array source is at most the canvas). */
    private const val BAND_BYTES_MAX = 4 * 1024 * 1024

    /** v1.10 (A11): the zero bytes [blankEncoding] deflates, a chunk at a time. */
    private const val ZERO_CHUNK = 64 * 1024

    /** v1.10 (A11): the canvas sizes whose [blankEncoding] is kept. */
    private const val BLANK_SIZES = 4

    /** v1.10 (A11): copies a band's rows exactly (the premultiplied values, no blending, no filtering). */
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    private val blankEncodings = object : LinkedHashMap<Long, ByteArray>(BLANK_SIZES * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > BLANK_SIZES
    }

    /**
     * v1.10 (A11, design 2.9): a band of rows on its way between a pixel file and bitmaps: [bytes]
     * (Java heap, `width × rows × 4`, the rows as pixel files hold them) and [native], its bitmap
     * twin. [load] copies [bytes] into [native]; [copyRows] then draws rows of it onto a canvas
     * exactly (SRC, source and destination rectangles of one size, no density), which is how
     * `TileOps` copies pixels too: no `setPixels`, which premultiplies again. The caller admits
     * both buffers before it makes one and [close]s it (main or the opener's thread).
     */
    class Band(val width: Int, val rows: Int) : AutoCloseable {
        val bytes = ByteArray(byteLength(width, rows))

        /** [bytes] read as longs and ints (whole-array offsets); the byte order does not matter to [isZero] and [kind]. */
        private val view: ByteBuffer = ByteBuffer.wrap(bytes)

        /** The bitmap twin of [bytes]. */
        val native: Bitmap = BitmapUtils.createLayerBitmap(width, rows).apply { density = Bitmap.DENSITY_NONE }

        private val src = Rect()
        private val dst = Rect()

        /**
         * Copies [bytes] into [native] (every row; a short last band copies stale rows it never draws).
         *
         * v1.10 Gate 2: one copy at a time, process-wide. The parallel decoders of an open (O1)
         * call this on up to 4 threads at once; Robolectric's native runtime (the JVM tests, not
         * a phone) aborts the test JVM when two buffer copies run together ("JniConstants: Class
         * not found: java/nio/ShortBuffer", exit 0xC0000409). The lock costs a band's memcpy
         * (about 0.1 ms) against the band's inflate (milliseconds), so the decoders still overlap.
         */
        fun load() {
            synchronized(LOAD_LOCK) { native.copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
        }

        /** Draws [w] x [n] pixels of [native] at ([x], 0) onto [c] at ([dx], [dy]) exactly. */
        fun copyRows(c: Canvas, x: Int, w: Int, n: Int, dx: Int, dy: Int) {
            src.set(x, 0, x + w, n)
            dst.set(dx, dy, dx + w, dy + n)
            c.drawBitmap(native, src, dst, srcPaint)
        }

        /** True when the first [n] rows of [bytes] are all zero bytes. */
        fun isZero(n: Int): Boolean = isZero(0, n * width * 4)

        /** [ZERO], [UNIFORM] or [OTHER]: the [w] pixels from column [x] of the first [n] rows of [bytes]. */
        fun kind(x: Int, w: Int, n: Int): Int {
            val rowBytes = width * 4
            var zero = true
            for (r in 0 until n) {
                if (!isZero(r * rowBytes + x * 4, w * 4)) {
                    zero = false
                    break
                }
            }
            if (zero) return ZERO
            val first = view.getInt(x * 4)
            for (r in 0 until n) {
                val start = r * rowBytes + x * 4
                var i = start
                val end = start + w * 4
                while (i < end) {
                    if (view.getInt(i) != first) return OTHER
                    i += 4
                }
            }
            return UNIFORM
        }

        /** True when [len] bytes from [from] are zero (longs, then the tail). */
        private fun isZero(from: Int, len: Int): Boolean {
            val end = from + len
            var i = from
            val longs = end - 7
            while (i < longs) {
                if (view.getLong(i) != 0L) return false
                i += 8
            }
            while (i < end) {
                if (bytes[i].toInt() != 0) return false
                i++
            }
            return true
        }

        override fun close() {
            native.recycle()
        }

        companion object {
            /** Every byte zero (transparent, and nothing else stored). */
            const val ZERO = 0

            /** Every pixel the same 4 bytes, not all zero. */
            const val UNIFORM = 1

            /** Anything else. */
            const val OTHER = 2

            /** Serializes [load] across threads (see there). */
            private val LOAD_LOCK = Any()
        }
    }

    /**
     * v1.10 (A11, design 2.9): reads the pixel file [file] of a [width] x [height] layer a band of
     * rows at a time into [band] (at least one row: `band.size / (width × 4)` rows per band, the
     * last one shorter) and hands each band to [onBand] as (first row, rows) before the next one
     * replaces it. The checks and messages are [read]'s: a missing, truncated or damaged file, one
     * of another size or with trailing data throws [CorruptProjectException]. What [onBand] throws
     * goes through unchanged. No buffer the size of the layer. Blocking: call on IO.
     */
    fun readBands(file: File, width: Int, height: Int, band: ByteArray, onBand: (y: Int, rows: Int) -> Unit) {
        val length = byteLength(width, height)
        if (!file.isFile) throw CorruptProjectException("missing file ${file.name}")
        try {
            FileInputStream(file).use { fis ->
                val input = BufferedInputStream(fis, IO_BUFFER)
                val (w, h, len) = readHeader(DataInputStream(input), file.name)
                if (w != width || h != height || len != length) {
                    throw CorruptProjectException("${file.name} is ${w}x$h, expected ${width}x$height")
                }
                inflateBands(input, file.name, width, height, band, onBand)
            }
        } catch (e: CorruptProjectException) {
            throw e
        } catch (e: EOFException) {
            throw CorruptProjectException("${file.name} is truncated", e)
        } catch (e: ZipException) {
            throw CorruptProjectException("${file.name} is damaged", e)
        }
    }

    /** Inflates the [width] x [height] rows after a header from [input] band by band (see [readBands]) and checks that the stream ends there. */
    private fun inflateBands(input: InputStream, name: String, width: Int, height: Int, band: ByteArray, onBand: (Int, Int) -> Unit) {
        val rowBytes = width * 4
        require(band.size >= rowBytes) { "a band holds at least one row" }
        val rows = minOf(band.size / rowBytes, height)
        val inflater = Inflater()
        try {
            val zip = InflaterInputStream(input, inflater, IO_BUFFER)
            var y = 0
            while (y < height) {
                val n = minOf(rows, height - y)
                val want = n * rowBytes
                var off = 0
                while (off < want) {
                    val k = zip.read(band, off, want - off)
                    if (k < 0) throw CorruptProjectException("$name is truncated")
                    off += k
                }
                onBand(y, n)
                y += n
            }
            // Reading past the end makes zlib verify the stream's checksum.
            if (zip.read() != -1) throw CorruptProjectException("$name has trailing data")
        } finally {
            inflater.end()
        }
    }

    /**
     * v1.10 (A11, design 2.9): the pixel file of a transparent [width] x [height] layer, byte for
     * byte as every save writes one (the same header and deflater, zero rows), computed once per
     * size: at most about 0.4 MB even at 10,000², as level 1 compresses zeros about 1000:1. The
     * last [BLANK_SIZES] sizes are kept. Any thread.
     */
    fun blankEncoding(width: Int, height: Int): ByteArray {
        val length = byteLength(width, height)
        val key = (width.toLong() shl 32) or height.toLong()
        synchronized(blankEncodings) { blankEncodings[key]?.let { return it } }
        val out = ByteArrayOutputStream(length / 512 + 64)
        val zeros = ByteArray(minOf(length, ZERO_CHUNK))
        writeBody(out, width, height, length) { zip ->
            var left = length
            while (left > 0) {
                val n = minOf(left, zeros.size)
                zip.write(zeros, 0, n)
                left -= n
            }
        }
        val bytes = out.toByteArray()
        synchronized(blankEncodings) { blankEncodings[key] = bytes }
        return bytes
    }

    /**
     * v1.10 (A11, design 2.9): writes a transparent [width] x [height] pixel file (atomically) as
     * its [blankEncoding], byte-identical to [write]'s of zero rows (X6), without a buffer of the
     * layer's size. Blocking: call on IO.
     */
    fun writeBlank(file: File, width: Int, height: Int) {
        val bytes = blankEncoding(width, height)
        PerfTrace.span(PerfTrace.SAVE_LAYER) {
            ProjectFormat.writeAtomically(file) { it.write(bytes) }
        }
    }

    /**
     * v1.10 (A11, design 2.9): the blank fast path. True only when [file] has exactly the length
     * of [blankEncoding] and every byte equal to it (a prefix is not enough: above about 12 Mpx
     * the encoding is longer than 64 KiB, and content in the last rows leaves a prefix unchanged).
     * False when the file can't be read (the decode then says what is wrong). Blocking: call on IO.
     */
    fun isBlankEncoding(file: File, width: Int, height: Int): Boolean {
        val length = file.length()
        if (length <= 0L) return false
        val enc = blankEncoding(width, height)
        if (length != enc.size.toLong()) return false
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(minOf(IO_BUFFER, enc.size))
                var off = 0
                while (off < enc.size) {
                    val n = input.read(buf, 0, minOf(buf.size, enc.size - off))
                    if (n < 0) return false
                    for (i in 0 until n) if (buf[i] != enc[off + i]) return false
                    off += n
                }
                input.read() == -1
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * v1.10 (A11, design 2.9): writes a [width] x [height] pixel file (atomically) whose rows
     * [fill] provides a band at a time: `fill(y, n, scratch)` puts rows y until y + n, as pixel
     * files hold them, at the start of [scratch] (at least one row). The deflater gets the same
     * bytes in the same order as [write] gives it, so the file is byte-identical to [write]'s of
     * the same pixels (X6). Blocking: call on IO.
     */
    fun writeRows(file: File, width: Int, height: Int, scratch: ByteArray, fill: (y: Int, rows: Int, into: ByteArray) -> Unit) {
        val rowBytes = width * 4
        require(scratch.size >= rowBytes) { "the scratch holds at least one row" }
        val rows = minOf(scratch.size / rowBytes, height)
        writeStream(file, width, height, byteLength(width, height)) { out ->
            var y = 0
            while (y < height) {
                val n = minOf(rows, height - y)
                fill(y, n, scratch)
                out.write(scratch, 0, n * rowBytes)
                y += n
            }
        }
    }
}
