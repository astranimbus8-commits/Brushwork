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
 * (v1.5; owned by A1, which keeps reading version 1). Stroke points are stored as base64
 * little-endian float32 (see PackedPoints), so a reloaded stroke replays identically. Pure
 * Kotlin; thread-safe.
 *
 * Hardened against damaged files: [decode] throws [CorruptVectorException] for anything it can't
 * read — truncated or garbage bytes, a zip bomb (inflated JSON beyond [MAX_JSON_BYTES]), malformed
 * or mistyped JSON, unknown object kinds, point data whose size doesn't match, sizes that would
 * exhaust memory, nesting that would exhaust the stack — never another exception or a crash. A
 * readable file is sanitized: objects with duplicate ids get new ones and `nextId` is moved past
 * every id, so later edits can't confuse two objects.
 */
object VectorCodec {
    const val VERSION = 1

    /** Largest inflated JSON accepted (2000 long strokes are about 10 MB). */
    private const val MAX_JSON_BYTES = 128L shl 20

    /** Ids (or a nextId) this far from 0 come from a damaged file: the objects are numbered again. */
    private const val MAX_ID = 1L shl 52

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
    fun decode(b: ByteArray): VectorContent = decode(b, MAX_JSON_BYTES)

    /** [decode] accepting at most [maxJsonBytes] of inflated JSON (tests use a small cap). */
    internal fun decode(b: ByteArray, maxJsonBytes: Long): VectorContent {
        val text = inflate(b, maxJsonBytes)
        val content = try {
            json.decodeFromString(VectorContent.serializer(), String(text, Charsets.UTF_8))
        } catch (e: OutOfMemoryError) {
            throw CorruptVectorException("The vector data is too large to read", e)
        } catch (e: StackOverflowError) {
            throw CorruptVectorException("The vector data is nested too deeply", e)
        } catch (e: Exception) {
            throw CorruptVectorException("The vector data can't be read (${e.message?.take(200) ?: e.javaClass.simpleName})", e)
        }
        return sanitized(content)
    }

    private fun inflate(b: ByteArray, maxJsonBytes: Long): ByteArray {
        if (b.isEmpty()) throw CorruptVectorException("The vector data is empty")
        val inflater = Inflater()
        try {
            inflater.setInput(b)
            val out = ByteArrayOutputStream(minOf(maxJsonBytes, maxOf(64L, b.size * 3L)).toInt())
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && !inflater.finished()) {
                    if (inflater.needsDictionary()) throw CorruptVectorException("The vector data is damaged (needs a dictionary)")
                    throw CorruptVectorException("The vector data is truncated")
                }
                out.write(buf, 0, n)
                if (out.size() > maxJsonBytes) throw CorruptVectorException("The vector data is too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw CorruptVectorException("The vector data is damaged", e)
        } catch (e: OutOfMemoryError) {
            throw CorruptVectorException("The vector data is too large to read", e)
        } finally {
            inflater.end()
        }
    }

    /**
     * [c] with unique ids (a later duplicate gets a new id) and a `nextId` beyond every id; the
     * same instance when it already is. Ids (or a `nextId`) so large that new ids would overflow
     * come from a damaged file: the objects are then numbered 1..n again (order kept).
     */
    internal fun sanitized(c: VectorContent): VectorContent = sanitizedIds(sanitizedSplines(c))

    /**
     * v1.6: Path-tool control points ([VPath.spline]) reduced to usable numbers
     * ([VSpline.sanitized]); the same instance when every spline already is (a spline that
     * changes no longer matches its Bézier form, so the Path tool's I9 check then treats the
     * path as a plain Bézier path).
     */
    private fun sanitizedSplines(c: VectorContent): VectorContent {
        if (c.objects.none { it is VPath && it.spline != null }) return c
        var changed = false
        val objects = c.objects.map { o ->
            val s = (o as? VPath)?.spline ?: return@map o
            val t = s.sanitized()
            if (t === s) o else { changed = true; o.copy(spline = t) }
        }
        return if (changed) c.copy(objects = objects) else c
    }

    private fun sanitizedIds(c: VectorContent): VectorContent {
        val seen = HashSet<Long>(c.objects.size * 2)
        var maxId = 0L
        var dup = false
        var outOfRange = c.nextId >= MAX_ID
        for (o in c.objects) {
            if (!seen.add(o.id)) dup = true
            if (o.id > maxId) maxId = o.id
            // Ids are far from overflowing (new ones count up from nextId).
            if (o.id >= MAX_ID || o.id <= -MAX_ID) outOfRange = true
        }
        if (outOfRange) {
            // Damaged ids: number the objects again from 1 (their order is kept).
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
