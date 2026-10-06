package com.brushwork.paint.model

import com.brushwork.paint.storage.CorruptProjectException
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.vector.CorruptVectorException
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** v1.7: an array container (`array_<id>_r<rev>.bin`) or an array spec could not be read. */
class CorruptArrayException(message: String) : IOException(message)

/**
 * v1.7 (item 3): an array's SOURCE as the container holds it. Text, shape and vector sources are
 * the layer's own data (the entry of an arrayed layer is a plain raster entry, so they live only
 * in the container); a raster source is its [ArrayPixels].
 */
sealed class ArraySourceBlob {
    class Pixels(val pixels: ArrayPixels) : ArraySourceBlob()
    class Vector(val content: VectorContent) : ArraySourceBlob()
    class Text(val textData: String) : ArraySourceBlob()
    class Shape(val shapeData: String) : ArraySourceBlob()

    companion object {
        /**
         * The source of [data]'s array: its text, shape or vector data (in that order, as the
         * layer kinds are exclusive), else the array's pixels. Null without an array, or for an
         * array with neither (damaged data: the layer is then written as plain pixels).
         */
        fun of(data: LayerData): ArraySourceBlob? {
            val array = data.array ?: return null
            data.text?.let { return Text(it) }
            data.shape?.let { return Shape(it) }
            data.vector?.let { return Vector(it) }
            return array.pixels?.let { Pixels(it) }
        }
    }
}

/**
 * v1.7 (item 3, §4.3): the array spec JSON (`project.json` entry field `array`) and the source
 * container (`array_<id>_r<rev>.bin`):
 *
 * magic "BWAR", u8 version ([VERSION]), u8 kind (0 PIXELS, 1 VECTOR, 2 TEXT, 3 SHAPE), then
 * - PIXELS: i32 left, i32 top, then the bytes of a pixel file (`LayerCodec`);
 * - VECTOR: the bytes of a `.vec` file (`VectorCodec`) up to the end;
 * - TEXT / SHAPE: u32 length, then that many bytes of UTF-8 codec JSON (`textData` / `shapeData`).
 *
 * Integers are big-endian (`DataOutputStream`). Readers refuse anything else with
 * [CorruptArrayException]: the loader then keeps the layer's copies as pixels.
 */
object ArrayCodec {
    const val VERSION = 1

    private const val MAGIC = "BWAR"
    private const val KIND_PIXELS = 0
    private const val KIND_VECTOR = 1
    private const val KIND_TEXT = 2
    private const val KIND_SHAPE = 3

    /** Largest TEXT / SHAPE JSON accepted (text objects are well below 1 MB). */
    private const val MAX_JSON_BYTES = 64 shl 20

    /** Largest compressed vector data accepted (`VectorCodec` caps the inflated JSON itself). */
    private const val MAX_VECTOR_BYTES = 128 shl 20

    /** An internal blob, not a v1.6 DTO: defaults are written. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun encodeSpec(s: ArraySpec): String = json.encodeToString(ArraySpec.serializer(), s)

    /** The spec in [s] (sanitized), or null when [s] is null or can't be read. */
    fun decodeSpec(s: String?): ArraySpec? {
        if (s == null) return null
        return try {
            json.decodeFromString(ArraySpec.serializer(), s).sanitized()
        } catch (e: Exception) {
            null
        }
    }

    /** Writes [source] as a container to [out] (left open). Blocking: call on IO. */
    fun writeSource(out: OutputStream, source: ArraySourceBlob) {
        if (source is ArraySourceBlob.Pixels) {
            val p = source.pixels
            val bmp = p.bitmap
            val length = LayerCodec.byteLength(bmp.width, bmp.height)
            val bytes = ByteArray(length)
            LayerCodec.copyPixels(bmp, bytes)
            writePixels(out, bmp.width, bmp.height, p.left, p.top, bytes, length)
            return
        }
        val data = DataOutputStream(out)
        writeHeader(data)
        when (source) {
            is ArraySourceBlob.Pixels -> Unit // above
            is ArraySourceBlob.Vector -> {
                data.writeByte(KIND_VECTOR)
                data.write(VectorCodec.encode(source.content))
            }
            is ArraySourceBlob.Text -> writeJson(data, KIND_TEXT, source.textData)
            is ArraySourceBlob.Shape -> writeJson(data, KIND_SHAPE, source.shapeData)
        }
        data.flush()
    }

    /**
     * A PIXELS container from pixels already copied out of the source bitmap ([data], `width ×
     * height × 4` bytes as `LayerCodec.copyPixels` gives them): the repository copies on the main
     * thread and compresses on IO, as for layer pixels. Blocking: call on IO.
     */
    internal fun writePixels(out: OutputStream, width: Int, height: Int, left: Int, top: Int, data: ByteArray, length: Int) {
        val header = DataOutputStream(out)
        writeHeader(header)
        header.writeByte(KIND_PIXELS)
        header.writeInt(left)
        header.writeInt(top)
        header.flush()
        LayerCodec.writeTo(out, width, height, data, length)
        out.flush()
    }

    private fun writeHeader(data: DataOutputStream) {
        data.write(MAGIC.toByteArray(Charsets.US_ASCII))
        data.writeByte(VERSION)
    }

    private fun writeJson(data: DataOutputStream, kind: Int, text: String) {
        data.writeByte(kind)
        val bytes = text.toByteArray(Charsets.UTF_8)
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    /**
     * The source in [input]. A PIXELS source is at most [docW] × [docH] and lies within ±that
     * size of the canvas (a source is cropped to the document). Throws [CorruptArrayException]
     * for a bad magic, an unknown version or kind, truncation or undecodable data. The caller
     * owns a returned bitmap. Blocking: call on IO.
     */
    fun readSource(input: InputStream, docW: Int, docH: Int): ArraySourceBlob {
        val data = DataInputStream(input)
        try {
            val magic = ByteArray(4)
            data.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) throw CorruptArrayException("not an array file")
            val version = data.readUnsignedByte()
            if (version !in 1..VERSION) throw CorruptArrayException("unsupported array file version $version")
            return when (val kind = data.readUnsignedByte()) {
                KIND_PIXELS -> {
                    val left = data.readInt()
                    val top = data.readInt()
                    if (left !in -docW..docW || top !in -docH..docH) throw CorruptArrayException("array pixels at $left, $top are off the canvas")
                    val bitmap = try {
                        LayerCodec.readBitmap(data, "the array pixels", docW, docH)
                    } catch (e: CorruptProjectException) {
                        throw CorruptArrayException(e.message ?: "the array pixels are damaged")
                    }
                    ArraySourceBlob.Pixels(ArrayPixels(bitmap, left, top))
                }
                KIND_VECTOR -> {
                    val bytes = readRest(data, MAX_VECTOR_BYTES)
                    val content = try {
                        VectorCodec.decode(bytes)
                    } catch (e: CorruptVectorException) {
                        throw CorruptArrayException(e.message ?: "the array's vector data is damaged")
                    }
                    ArraySourceBlob.Vector(content)
                }
                KIND_TEXT -> ArraySourceBlob.Text(readJson(data))
                KIND_SHAPE -> ArraySourceBlob.Shape(readJson(data))
                else -> throw CorruptArrayException("unknown array source kind $kind")
            }
        } catch (e: CorruptArrayException) {
            throw e
        } catch (e: EOFException) {
            throw CorruptArrayException("the array file is truncated")
        } catch (e: OutOfMemoryError) {
            throw CorruptArrayException("the array file is too large to read")
        } catch (e: IOException) {
            throw CorruptArrayException("the array file can't be read (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun readJson(data: DataInputStream): String {
        val length = data.readInt()
        if (length < 0 || length > MAX_JSON_BYTES) throw CorruptArrayException("array source of $length bytes")
        val bytes = ByteArray(length)
        data.readFully(bytes)
        if (data.read() != -1) throw CorruptArrayException("the array file has trailing data")
        return String(bytes, Charsets.UTF_8)
    }

    private fun readRest(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw CorruptArrayException("the array's vector data is too large")
        }
        return out.toByteArray()
    }
}
