package com.brushwork.paint.compat19

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** v1.9's `CorruptVectorException`: [VectorCodecV19] could not read a file. */
class CorruptVectorExceptionV19(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * v1.10 G2 (compat19 kit): a FROZEN copy of v1.9's `VectorCodec` (`vector/VectorCodec.kt` at tag v1.9.0), in test
 * sources only: the `.vec` file (and the SVG / PDF payload's vector layers) as v1.9 writes and
 * reads it, Deflate (zlib, level 5) of the JSON of a [VectorContentV19], with v1.9's JSON
 * settings (unknown keys ignored, so v1.9 drops v1.10's new fields; an unknown enum value takes
 * the field's default) and v1.9's sanitizing on read. A cog is written with its v1.7 fallback
 * outline ([forWriting]) and read back as the cog ([sanitizedShapes]). Never change it.
 */
object VectorCodecV19 {
    const val VERSION = 1

    private const val MAX_JSON_BYTES = 128L shl 20

    private const val MAX_ID = 1L shl 52

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** The JSON v1.9 deflates (tests compare it with the app's). */
    fun encodeJson(c: VectorContentV19): String = json.encodeToString(VectorContentV19.serializer(), forWriting(c))

    fun encode(c: VectorContentV19): ByteArray {
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

    /** The content stored in [b], as v1.9 reads it; throws [CorruptVectorExceptionV19] when v1.9 can't. */
    fun decode(b: ByteArray): VectorContentV19 = decode(b, MAX_JSON_BYTES)

    /** [decode] accepting at most [maxJsonBytes] of inflated JSON. */
    fun decode(b: ByteArray, maxJsonBytes: Long): VectorContentV19 {
        val text = inflate(b, maxJsonBytes)
        val content = try {
            json.decodeFromString(VectorContentV19.serializer(), String(text, Charsets.UTF_8))
        } catch (e: OutOfMemoryError) {
            throw CorruptVectorExceptionV19("The vector data is too large to read", e)
        } catch (e: StackOverflowError) {
            throw CorruptVectorExceptionV19("The vector data is nested too deeply", e)
        } catch (e: Exception) {
            throw CorruptVectorExceptionV19("The vector data can't be read (${e.message?.take(200) ?: e.javaClass.simpleName})", e)
        }
        return sanitized(content)
    }

    private fun inflate(b: ByteArray, maxJsonBytes: Long): ByteArray {
        if (b.isEmpty()) throw CorruptVectorExceptionV19("The vector data is empty")
        val inflater = Inflater()
        try {
            inflater.setInput(b)
            val out = ByteArrayOutputStream(minOf(maxJsonBytes, maxOf(64L, b.size * 3L)).toInt())
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && !inflater.finished()) {
                    if (inflater.needsDictionary()) throw CorruptVectorExceptionV19("The vector data is damaged (needs a dictionary)")
                    throw CorruptVectorExceptionV19("The vector data is truncated")
                }
                out.write(buf, 0, n)
                if (out.size() > maxJsonBytes) throw CorruptVectorExceptionV19("The vector data is too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw CorruptVectorExceptionV19("The vector data is damaged", e)
        } catch (e: OutOfMemoryError) {
            throw CorruptVectorExceptionV19("The vector data is too large to read", e)
        } finally {
            inflater.end()
        }
    }

    /** v1.9's sanitizing on read: splines, strokes' copies, ids, then cogs' fallback outlines. */
    fun sanitized(c: VectorContentV19): VectorContentV19 = sanitizedShapes(sanitizedIds(sanitizedCopies(sanitizedSplines(c))))

    /** v1.9's `VectorCodec.forWriting`: every cog shape as `shape.forWriting()`; the same instance without cogs. */
    fun forWriting(c: VectorContentV19): VectorContentV19 {
        if (c.objects.none { it is VShapeV19 && it.shape.type == ShapeTypeV19.COG }) return c
        var changed = false
        val objects = c.objects.map { o ->
            if (o !is VShapeV19 || o.shape.type != ShapeTypeV19.COG) return@map o
            val w = o.shape.forWriting()
            if (w === o.shape) o else { changed = true; o.copy(shape = w) }
        }
        return if (changed) c.copy(objects = objects) else c
    }

    /** v1.9's `VectorCodec.sanitizedShapes`: a cog's fallback outline dropped; the same instance without one. */
    fun sanitizedShapes(c: VectorContentV19): VectorContentV19 {
        if (c.objects.none { it is VShapeV19 && it.shape.cogFallback }) return c
        val objects = c.objects.map { o ->
            if (o !is VShapeV19 || !o.shape.cogFallback) return@map o
            o.copy(shape = o.shape.sanitized() ?: o.shape.copy(points = null, cogFallback = false))
        }
        return c.copy(objects = objects)
    }

    /** v1.9's `VectorCodec.sanitizedCopies`: every stroke's copies reduced to usable maps. */
    fun sanitizedCopies(c: VectorContentV19): VectorContentV19 {
        if (c.objects.none { it is VStrokeV19 && it.copies.isNotEmpty() }) return c
        var changed = false
        val objects = c.objects.map { o ->
            if (o !is VStrokeV19 || o.copies.isEmpty()) return@map o
            val t = StrokeCopiesV19.sanitized(o.copies)
            if (t === o.copies) o else { changed = true; o.copy(copies = t) }
        }
        return if (changed) c.copy(objects = objects) else c
    }

    /** v1.9's `VectorCodec.sanitizedSplines`: Path-tool control points reduced to usable numbers. */
    fun sanitizedSplines(c: VectorContentV19): VectorContentV19 {
        if (c.objects.none { it is VPathV19 && it.spline != null }) return c
        var changed = false
        val objects = c.objects.map { o ->
            val s = (o as? VPathV19)?.spline ?: return@map o
            val t = s.sanitized()
            if (t === s) o else { changed = true; o.copy(spline = t) }
        }
        return if (changed) c.copy(objects = objects) else c
    }

    private fun sanitizedIds(c: VectorContentV19): VectorContentV19 {
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
