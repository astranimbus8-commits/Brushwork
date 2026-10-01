package com.brushwork.paint.vector

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A vector file could not be read (damaged, truncated or not a vector file). */
class CorruptVectorException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The `.vec` file of a vector layer: Deflate (zlib, level 5) of the JSON of its [VectorContent]
 * (v1.5; owned by A1 after F1, which must keep reading version 1). Stroke points are stored as
 * base64 little-endian float32 (see PackedPoints), so a reloaded stroke replays identically.
 * Pure Kotlin; thread-safe.
 */
object VectorCodec {
    const val VERSION = 1

    /** Largest inflated JSON accepted (a damaged file must not exhaust memory). */
    private const val MAX_JSON_BYTES = 256L shl 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // An enum value written by a newer version falls back to the field's default.
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun encode(c: VectorContent): ByteArray {
        val text = json.encodeToString(VectorContent.serializer(), c).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(5)
        try {
            deflater.setInput(text)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, text.size / 3))
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

    /** The content stored in [b]; throws [CorruptVectorException] when it can't be read. */
    fun decode(b: ByteArray): VectorContent {
        val inflater = Inflater()
        val text = try {
            inflater.setInput(b)
            val out = ByteArrayOutputStream(maxOf(64, b.size * 3))
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && !inflater.finished()) throw CorruptVectorException("The vector data is truncated")
                out.write(buf, 0, n)
                if (out.size() > MAX_JSON_BYTES) throw CorruptVectorException("The vector data is too large")
            }
            out.toByteArray()
        } catch (e: DataFormatException) {
            throw CorruptVectorException("The vector data is damaged", e)
        } finally {
            inflater.end()
        }
        return try {
            json.decodeFromString(VectorContent.serializer(), String(text, Charsets.UTF_8))
        } catch (e: Exception) {
            throw CorruptVectorException("The vector data can't be read (${e.message ?: e.javaClass.simpleName})", e)
        }
    }
}
