package com.brushwork.paint.compat17

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** v1.7's `CorruptVectorException`: [VectorCodecV17] could not read a file. */
class CorruptVectorExceptionV17(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * v1.8 F0: a FROZEN copy of v1.7's `VectorCodec` (`vector/VectorCodec.kt` at 885793b4), in test
 * sources only: the `.vec` file (and the SVG / PDF payload's vector layers) as v1.7 writes and
 * reads it, Deflate (zlib, level 5) of the JSON of a [VectorContentV17], with v1.7's JSON
 * settings (unknown keys ignored, so v1.7 drops v1.8's new fields) and v1.7's sanitizing on
 * read. Never change it.
 */
object VectorCodecV17 {
    const val VERSION = 1

    private const val MAX_JSON_BYTES = 128L shl 20

    private const val MAX_ID = 1L shl 52

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** The JSON v1.7 deflates (tests compare it with the app's). */
    fun encodeJson(c: VectorContentV17): String = json.encodeToString(VectorContentV17.serializer(), c)

    fun encode(c: VectorContentV17): ByteArray {
        val text = encodeJson(c).toByteArray(Charsets.UTF_8)
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

    /** The content stored in [b], as v1.7 reads it; throws [CorruptVectorExceptionV17] when v1.7 can't. */
    fun decode(b: ByteArray): VectorContentV17 = decode(b, MAX_JSON_BYTES)

    /** [decode] accepting at most [maxJsonBytes] of inflated JSON. */
    fun decode(b: ByteArray, maxJsonBytes: Long): VectorContentV17 {
        val text = inflate(b, maxJsonBytes)
        val content = try {
            json.decodeFromString(VectorContentV17.serializer(), String(text, Charsets.UTF_8))
        } catch (e: OutOfMemoryError) {
            throw CorruptVectorExceptionV17("The vector data is too large to read", e)
        } catch (e: StackOverflowError) {
            throw CorruptVectorExceptionV17("The vector data is nested too deeply", e)
        } catch (e: Exception) {
            throw CorruptVectorExceptionV17("The vector data can't be read (${e.message?.take(200) ?: e.javaClass.simpleName})", e)
        }
        return sanitized(content)
    }

    private fun inflate(b: ByteArray, maxJsonBytes: Long): ByteArray {
        if (b.isEmpty()) throw CorruptVectorExceptionV17("The vector data is empty")
        val inflater = Inflater()
        try {
            inflater.setInput(b)
            val out = ByteArrayOutputStream(minOf(maxJsonBytes, maxOf(64L, b.size * 3L)).toInt())
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && !inflater.finished()) {
                    if (inflater.needsDictionary()) throw CorruptVectorExceptionV17("The vector data is damaged (needs a dictionary)")
                    throw CorruptVectorExceptionV17("The vector data is truncated")
                }
                out.write(buf, 0, n)
                if (out.size() > maxJsonBytes) throw CorruptVectorExceptionV17("The vector data is too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw CorruptVectorExceptionV17("The vector data is damaged", e)
        } catch (e: OutOfMemoryError) {
            throw CorruptVectorExceptionV17("The vector data is too large to read", e)
        } finally {
            inflater.end()
        }
    }

    /** v1.7's sanitizing on read: splines, then strokes' copies, then ids. */
    fun sanitized(c: VectorContentV17): VectorContentV17 = sanitizedIds(sanitizedCopies(sanitizedSplines(c)))

    /** v1.7's `sanitizedCopies`: every stroke's copies reduced to usable maps ([StrokeCopiesV17.sanitized]). */
    fun sanitizedCopies(c: VectorContentV17): VectorContentV17 {
        if (c.objects.none { it is VStrokeV17 && it.copies.isNotEmpty() }) return c
        var changed = false
        val objects = c.objects.map { o ->
            if (o !is VStrokeV17 || o.copies.isEmpty()) return@map o
            val t = StrokeCopiesV17.sanitized(o.copies)
            if (t === o.copies) o else { changed = true; o.copy(copies = t) }
        }
        return if (changed) c.copy(objects = objects) else c
    }

    /** v1.7's `sanitizedSplines`: Path-tool control points reduced to usable numbers ([VSplineV17.sanitized]). */
    fun sanitizedSplines(c: VectorContentV17): VectorContentV17 {
        if (c.objects.none { it is VPathV17 && it.spline != null }) return c
        var changed = false
        val objects = c.objects.map { o ->
            val s = (o as? VPathV17)?.spline ?: return@map o
            val t = s.sanitized()
            if (t === s) o else { changed = true; o.copy(spline = t) }
        }
        return if (changed) c.copy(objects = objects) else c
    }

    private fun sanitizedIds(c: VectorContentV17): VectorContentV17 {
        val seen = HashSet<Long>(c.objects.size * 2)
        var maxId = 0L
        var dup = false
        var outOfRange = c.nextId >= MAX_ID
        for (o in c.objects) {
            if (!seen.add(o.id)) dup = true
            if (o.id > maxId) maxId = o.id
            if (o.id >= MAX_ID || o.id <= -MAX_ID) outOfRange = true
        }
        if (outOfRange) {
            return c.copy(objects = c.objects.mapIndexed { i, o -> if (o.id == i + 1L) o else o.withId(i + 1L) }, nextId = c.objects.size + 1L)
        }
        val next0 = maxOf(c.nextId, maxId + 1, 1L)
        if (!dup && next0 == c.nextId) return c
        if (!dup) return c.copy(nextId = next0)
        var next = next0
        val ids = HashSet<Long>(c.objects.size * 2)
        val objects = c.objects.map { o -> if (ids.add(o.id)) o else o.withId(next++).also { ids += it.id } }
        return c.copy(objects = objects, nextId = next)
    }
}
