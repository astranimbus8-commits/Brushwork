package com.brushwork.paint.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.brushwork.paint.engine.BitmapUtils
import java.io.BufferedInputStream
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
        ProjectFormat.writeAtomically(file) { out -> writeBody(out, width, height, length, body) }
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
     */
    fun readBitmap(input: InputStream, name: String, maxWidth: Int, maxHeight: Int): Bitmap {
        try {
            val buffered = BufferedInputStream(input, IO_BUFFER)
            val (w, h, len) = readHeader(DataInputStream(buffered), name)
            if (w <= 0 || h <= 0 || w > maxWidth || h > maxHeight || len.toLong() != w.toLong() * h * 4) {
                throw CorruptProjectException("$name is ${w}x$h, larger than ${maxWidth}x$maxHeight or inconsistent")
            }
            val dst = ByteArray(len)
            inflateInto(buffered, name, dst, len)
            val bitmap = BitmapUtils.createLayerBitmap(w, h)
            try {
                check(bitmap.byteCount == len) { "Unexpected bitmap format ${bitmap.config}" }
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(dst, 0, len))
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
}
