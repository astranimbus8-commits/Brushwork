package com.brushwork.paint.compat19

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/**
 * v1.10 G2 (compat19 kit): a FROZEN copy of v1.9's pixel files (`storage/LayerCodec.kt` at tag
 * v1.9.0), in test sources only: `layer_<id>_r<rev>.bin` and `mask_<id>_r<rev>.bin` as v1.9
 * writes and reads them. A header (magic "BWLY", version 1, width, height, byte length, big-endian
 * ints) and the raw premultiplied ARGB_8888 bytes, zlib-compressed at [Deflater.BEST_SPEED] through
 * a 64 KiB [DeflaterOutputStream]. v1.9's banded write gives the deflater the same bytes in the
 * same order without a flush, so its files are [encode]'s. The bitmap side (copying pixels in
 * and out of a `Bitmap`, the color conversion) is not copied: the kit works on the raw bytes.
 * Never change it: it is what v1.9 is.
 */
object LayerCodecV19 {
    private const val MAGIC = 0x42574C59 // "BWLY"
    const val VERSION = 1
    private const val IO_BUFFER = 64 * 1024

    /** v1.9's `byteLength`: raw byte length of a [width] x [height] ARGB_8888 layer. */
    fun byteLength(width: Int, height: Int): Int {
        val n = width.toLong() * height * 4
        require(width > 0 && height > 0 && n <= Int.MAX_VALUE) { "Invalid layer size ${width}x$height" }
        return n.toInt()
    }

    /** The bytes of the pixel file v1.9 writes for [data] (`LayerCodec.write`, `writeBands` and `writeTo` write these). */
    fun encode(width: Int, height: Int, data: ByteArray): ByteArray {
        val length = byteLength(width, height)
        require(data.size >= length)
        val out = ByteArrayOutputStream(64 + length / 4)
        writeBody(out, width, height, length) { it.write(data, 0, length) }
        return out.toByteArray()
    }

    /** v1.9's `writeBody`: the header, then the deflated pixels; [out] is left open. */
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
     * v1.9's `LayerCodec.read`: the pixels of [file] into [dst] (at least `width * height * 4`
     * bytes). Throws [CorruptProjectExceptionV19] when the file is missing, truncated, damaged or
     * of another size, as v1.9 does.
     */
    fun read(file: File, width: Int, height: Int, dst: ByteArray) {
        val length = byteLength(width, height)
        require(dst.size >= length)
        if (!file.isFile) throw CorruptProjectExceptionV19("missing file ${file.name}")
        FileInputStream(file).use { readFrom(it, file.name, width, height, dst) }
    }

    /** [read] of the bytes [b] of a pixel file named [name]; returns the pixels. */
    fun decode(b: ByteArray, name: String, width: Int, height: Int): ByteArray {
        val dst = ByteArray(byteLength(width, height))
        readFrom(ByteArrayInputStream(b), name, width, height, dst)
        return dst
    }

    /** The (width, height, byte length) a pixel file's header holds, as v1.9 checks it. */
    fun header(b: ByteArray, name: String): Triple<Int, Int, Int> = try {
        readHeader(DataInputStream(ByteArrayInputStream(b)), name)
    } catch (e: EOFException) {
        throw CorruptProjectExceptionV19("$name is truncated", e)
    }

    private fun readFrom(stream: InputStream, name: String, width: Int, height: Int, dst: ByteArray) {
        val length = byteLength(width, height)
        try {
            val input = BufferedInputStream(stream, IO_BUFFER)
            val (w, h, len) = readHeader(DataInputStream(input), name)
            if (w != width || h != height || len != length) {
                throw CorruptProjectExceptionV19("$name is ${w}x$h, expected ${width}x$height")
            }
            inflateInto(input, name, dst, length)
        } catch (e: CorruptProjectExceptionV19) {
            throw e
        } catch (e: EOFException) {
            throw CorruptProjectExceptionV19("$name is truncated", e)
        } catch (e: ZipException) {
            throw CorruptProjectExceptionV19("$name is damaged", e)
        }
    }

    /** v1.9's `readHeader`: checks the magic and version; returns (width, height, byte length). */
    private fun readHeader(header: DataInputStream, name: String): Triple<Int, Int, Int> {
        if (header.readInt() != MAGIC) throw CorruptProjectExceptionV19("$name is not a layer file")
        val version = header.readInt()
        if (version !in 1..VERSION) throw CorruptProjectExceptionV19("$name has unsupported version $version")
        val w = header.readInt()
        val h = header.readInt()
        val len = header.readInt()
        return Triple(w, h, len)
    }

    /** v1.9's `inflateInto`: exactly [length] bytes into [dst], and the stream must end there (checksum checked). */
    private fun inflateInto(input: InputStream, name: String, dst: ByteArray, length: Int) {
        val inflater = Inflater()
        try {
            val zip = InflaterInputStream(input, inflater, IO_BUFFER)
            var off = 0
            while (off < length) {
                val n = zip.read(dst, off, length - off)
                if (n < 0) throw CorruptProjectExceptionV19("$name is truncated")
                off += n
            }
            if (zip.read() != -1) throw CorruptProjectExceptionV19("$name has trailing data")
        } finally {
            inflater.end()
        }
    }
}
