package com.brushwork.paint.exchange.pdf

import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.image.FilteredZlib
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Random access to the bytes of a PDF file. */
interface PdfBytes : Closeable {
    val size: Long

    /** Reads up to [len] bytes at [pos] into [buf]; returns how many (0 at the end). */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int

    override fun close() {}
}

/** A PDF held in memory. */
class ArrayPdfBytes(private val bytes: ByteArray) : PdfBytes {
    override val size: Long get() = bytes.size.toLong()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= bytes.size) return 0
        val n = minOf(len.toLong(), bytes.size - pos).toInt()
        System.arraycopy(bytes, pos.toInt(), buf, off, n)
        return n
    }
}

/** A PDF file read in place. */
class FilePdfBytes(file: File) : PdfBytes {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return 0
        raf.seek(pos)
        val n = raf.read(buf, off, minOf(len.toLong(), size - pos).toInt())
        return if (n < 0) 0 else n
    }

    override fun close() = raf.close()
}

/** A PDF value as read. */
sealed class PdfObj {
    object Null : PdfObj()
    data class Bool(val value: Boolean) : PdfObj()
    data class Num(val value: Double) : PdfObj() {
        val int: Int get() = value.toInt()
    }
    class Str(val bytes: ByteArray) : PdfObj() {
        /** As text (UTF-16 with a byte order mark, else Latin-1 / PDFDocEncoding's ASCII part). */
        val text: String
            get() = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            } else {
                String(bytes, Charsets.ISO_8859_1)
            }
    }
    data class Name(val name: String) : PdfObj()
    data class Arr(val items: List<PdfObj>) : PdfObj()
    data class Dict(val map: Map<String, PdfObj>) : PdfObj() {
        operator fun get(key: String): PdfObj? = map[key]
    }
    data class Ref(val num: Int, val gen: Int) : PdfObj()
    /** A stream: its dictionary and where its (encoded) data lies in the file. */
    class Stream(val dict: Dict, val offset: Long, val length: Long) : PdfObj()
}

/**
 * A small PDF reader for the files Brushwork writes (v1.5 §4.10e / §4.11): classic cross-reference
 * tables (following `/Prev`; a damaged table is rebuilt by scanning for `N G obj`), direct and
 * indirect objects, Flate streams with PNG predictors. It finds the Brushwork payload and its
 * pictures; foreign PDFs that use cross-reference streams simply have no payload here (they are
 * imported as pictures through the platform renderer). Not thread-safe.
 */
class OwnPdfReader(private val src: PdfBytes) : Closeable {

    /** Object number -> byte offset of `N G obj`. */
    var offsets: Map<Int, Long>
        private set

    /** The trailer dictionary. */
    val trailer: PdfObj.Dict

    private val cache = HashMap<Int, PdfObj>()

    /** The table was rebuilt by scanning the whole file (done at most once). */
    private var scanned = false

    init {
        if (!startsWith(0, "%PDF-")) throw IOException("Not a PDF file")
        var table: Map<Int, Long>
        var tr: PdfObj.Dict?
        try {
            val r = readXref()
            table = r.first
            tr = r.second
        } catch (e: XrefStreamException) {
            // A cross-reference stream (PDF 1.5+, nearly every other app's files): never a
            // Brushwork file, and scanning a large foreign file just to find out would be slow.
            throw IOException("Cross-reference streams are not supported", e)
        } catch (e: Exception) {
            table = emptyMap()
            tr = null
        }
        if (table.isEmpty() || tr == null || !plausible(table, tr)) {
            val s = scanObjects()
            scanned = true
            table = s.first
            tr = tr ?: s.second ?: throw IOException("The PDF has no trailer")
        }
        offsets = table
        trailer = tr
    }

    /** A cross-reference stream where a classic table was expected. */
    private class XrefStreamException : IOException("Cross-reference streams are not supported")

    /**
     * True when a sample of the table's entries (about 16, the first and last, and the catalog)
     * point at their objects: checking every entry of a large file reads it all. A wrong entry
     * found later makes [obj] rebuild the table once.
     */
    private fun plausible(table: Map<Int, Long>, tr: PdfObj.Dict): Boolean {
        val keys = table.keys.sorted()
        val step = maxOf(1, keys.size / 16)
        var i = 0
        while (i < keys.size) {
            val n = keys[i]
            if (!objectAt(table.getValue(n), n)) return false
            i += step
        }
        val last = keys.last()
        if (!objectAt(table.getValue(last), last)) return false
        val root = tr["Root"] as? PdfObj.Ref ?: return true
        val off = table[root.num] ?: return false
        return objectAt(off, root.num)
    }

    override fun close() = src.close()

    // ------------------------------------------------------------------ high level

    /** The document catalog. */
    fun catalog(): PdfObj.Dict? = resolve(trailer["Root"]) as? PdfObj.Dict

    /** True when the file carries Brushwork data. */
    fun hasPayload(): Boolean = catalog()?.get("BrushworkPayload") != null

    /** The Brushwork payload (null when the file has none); throws [IOException] when it is damaged. */
    fun payload(): BrushworkPayload? {
        val s = resolve(catalog()?.get("BrushworkPayload")) as? PdfObj.Stream ?: return null
        return Payload.fromJson(streamData(s, MAX_PAYLOAD))
    }

    /** The picture named [key] in the catalog's `/BrushworkImages` (null when missing or unreadable). */
    fun payloadImage(key: String): ArgbImage? {
        val dict = resolve(catalog()?.get("BrushworkImages")) as? PdfObj.Dict ?: return null
        val ref = dict[key] as? PdfObj.Ref ?: return null
        return image(ref.num)
    }

    /**
     * Image XObject [num] as ARGB: DeviceRGB or DeviceGray 8-bit data, with its `/SMask` as
     * alpha (opaque without one). Null for other kinds.
     */
    fun image(num: Int): ArgbImage? {
        val s = obj(num) as? PdfObj.Stream ?: return null
        val w = (resolve(s.dict["Width"]) as? PdfObj.Num)?.int ?: return null
        val h = (resolve(s.dict["Height"]) as? PdfObj.Num)?.int ?: return null
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_IMAGE_PIXELS) return null
        val cs = (resolve(s.dict["ColorSpace"]) as? PdfObj.Name)?.name
        val channels = when (cs) { "DeviceRGB" -> 3; "DeviceGray" -> 1; else -> return null }
        val bpc = (resolve(s.dict["BitsPerComponent"]) as? PdfObj.Num)?.int ?: 8
        if (bpc != 8) return null
        val data = streamData(s, w.toLong() * h * channels + h)
        if (data.size < w * h * channels) return null
        val alpha = (s.dict["SMask"] as? PdfObj.Ref)?.let { ref ->
            val ms = obj(ref.num) as? PdfObj.Stream ?: return@let null
            val mw = (resolve(ms.dict["Width"]) as? PdfObj.Num)?.int
            val mh = (resolve(ms.dict["Height"]) as? PdfObj.Num)?.int
            if (mw != w || mh != h) return@let null
            streamData(ms, w.toLong() * h + h).takeIf { it.size >= w * h }
        }
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val a = alpha?.let { it[i].toInt() and 0xFF } ?: 0xFF
            px[i] = if (channels == 3) {
                (a shl 24) or ((data[i * 3].toInt() and 0xFF) shl 16) or ((data[i * 3 + 1].toInt() and 0xFF) shl 8) or (data[i * 3 + 2].toInt() and 0xFF)
            } else {
                val g = data[i].toInt() and 0xFF
                (a shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return ArgbImage(w, h, px)
    }

    // ------------------------------------------------------------------ objects

    /** Indirect object [num] (null when missing). */
    fun obj(num: Int): PdfObj? {
        cache[num]?.let { return it }
        var o = read(num)
        if (o == null && !scanned && offsets.containsKey(num)) {
            // The table (only sampled when opened) is wrong here: rebuilt once from the file.
            scanned = true
            offsets = offsets + scanObjects().first
            o = read(num)
        }
        if (o != null) cache[num] = o
        return o
    }

    private fun read(num: Int): PdfObj? {
        val off = offsets[num] ?: return null
        return try {
            Parser(off).readIndirect(num)
        } catch (e: IOException) {
            null
        }
    }

    /** [o] with references followed (null for a dangling reference). */
    fun resolve(o: PdfObj?): PdfObj? {
        var cur = o
        var hops = 0
        while (cur is PdfObj.Ref) {
            if (++hops > 32) return null
            cur = obj(cur.num)
        }
        return cur
    }

    /**
     * The decoded data of [s] (FlateDecode with or without PNG predictors, or unfiltered); at
     * most [limit] bytes, else [IOException].
     */
    fun streamData(s: PdfObj.Stream, limit: Long = MAX_STREAM): ByteArray {
        val raw = rawData(s)
        val filter = resolve(s.dict["Filter"])
        val filters = when (filter) {
            null -> emptyList()
            is PdfObj.Name -> listOf(filter.name)
            is PdfObj.Arr -> filter.items.mapNotNull { (resolve(it) as? PdfObj.Name)?.name }
            else -> throw IOException("Unsupported stream filter")
        }
        if (filters.isEmpty()) return raw
        if (filters != listOf("FlateDecode")) throw IOException("Unsupported stream filter $filters")
        var data = inflate(raw, limit)
        val parms = resolve(s.dict["DecodeParms"]) as? PdfObj.Dict
        val predictor = (resolve(parms?.get("Predictor")) as? PdfObj.Num)?.int ?: 1
        if (predictor >= 10) {
            val colors = (resolve(parms?.get("Colors")) as? PdfObj.Num)?.int ?: 1
            val columns = (resolve(parms?.get("Columns")) as? PdfObj.Num)?.int ?: 1
            val rowBytes = colors * columns
            val rows = data.size / (rowBytes + 1)
            FilteredZlib.unfilter(data, rows, rowBytes, colors)
            val out = ByteArray(rows * rowBytes)
            for (y in 0 until rows) System.arraycopy(data, y * (rowBytes + 1) + 1, out, y * rowBytes, rowBytes)
            data = out
        }
        return data
    }

    /** The undecoded bytes of [s]. */
    fun rawData(s: PdfObj.Stream): ByteArray {
        if (s.length < 0 || s.length > MAX_STREAM || s.offset + s.length > src.size) throw IOException("Damaged stream")
        val out = ByteArray(s.length.toInt())
        var n = 0
        while (n < out.size) {
            val k = src.read(s.offset + n, out, n, out.size - n)
            if (k <= 0) throw IOException("Damaged stream")
            n += k
        }
        return out
    }

    private fun inflate(data: ByteArray, limit: Long): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(data)
            val out = java.io.ByteArrayOutputStream(maxOf(64, minOf(limit, data.size * 4L).toInt()))
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IOException("A stream is too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw IOException("A stream is damaged", e)
        } finally {
            inf.end()
        }
    }

    // ------------------------------------------------------------------ cross reference

    private fun readXref(): Pair<Map<Int, Long>, PdfObj.Dict> {
        val tailLen = minOf(src.size, 2048L).toInt()
        val tail = ByteArray(tailLen)
        src.read(src.size - tailLen, tail, 0, tailLen)
        val text = String(tail, Charsets.ISO_8859_1)
        val at = text.lastIndexOf("startxref")
        if (at < 0) throw IOException("No startxref")
        var start = Regex("\\d+").find(text, at + 9)?.value?.toLong() ?: throw IOException("No startxref")
        val table = HashMap<Int, Long>()
        var first: PdfObj.Dict? = null
        val seen = HashSet<Long>()
        while (start >= 0 && seen.add(start)) {
            val p = Parser(start)
            if (p.keyword() != "xref") {
                if (indirectObjectAt(start)) throw XrefStreamException()
                throw IOException("Damaged cross-reference table")
            }
            while (true) {
                val tok = p.peekKeyword()
                if (tok == "trailer") { p.keyword(); break }
                val firstNum = (p.value() as? PdfObj.Num)?.int ?: throw IOException("Damaged xref")
                val count = (p.value() as? PdfObj.Num)?.int ?: throw IOException("Damaged xref")
                if (count < 0 || count > 10_000_000) throw IOException("Damaged xref")
                for (i in 0 until count) {
                    val off = (p.value() as? PdfObj.Num)?.value?.toLong() ?: throw IOException("Damaged xref")
                    p.value()
                    val kind = p.keyword()
                    val num = firstNum + i
                    if (kind == "n" && num !in table && off > 0) table[num] = off
                }
            }
            val tr = p.value() as? PdfObj.Dict ?: throw IOException("Damaged trailer")
            if (first == null) first = tr
            start = (tr["Prev"] as? PdfObj.Num)?.value?.toLong() ?: -1
        }
        return table to (first ?: throw IOException("No trailer"))
    }

    /** True when `N G obj` starts at [off]. */
    private fun objectAt(off: Long, num: Int): Boolean = try {
        val p = Parser(off)
        (p.value() as? PdfObj.Num)?.int == num && p.value() is PdfObj.Num && p.keyword() == "obj"
    } catch (e: Exception) {
        false
    }

    /** True when any `N G obj` starts at [off] (a cross-reference stream where `xref` was expected). */
    private fun indirectObjectAt(off: Long): Boolean = try {
        val p = Parser(off)
        p.value() is PdfObj.Num && p.value() is PdfObj.Num && p.keyword() == "obj"
    } catch (e: Exception) {
        false
    }

    /** Rebuilds the table by scanning for `N G obj` (and the last `trailer`). */
    private fun scanObjects(): Pair<Map<Int, Long>, PdfObj.Dict?> {
        val table = HashMap<Int, Long>()
        var trailerDict: PdfObj.Dict? = null
        val chunk = 1 shl 20
        val buf = ByteArray(chunk + 64)
        var pos = 0L
        val re = Regex("(?<![0-9])(\\d{1,9})\\s+(\\d{1,5})\\s+obj\\b")
        while (pos < src.size) {
            val n = src.read(pos, buf, 0, buf.size)
            if (n <= 0) break
            val text = String(buf, 0, n, Charsets.ISO_8859_1)
            for (m in re.findAll(text)) {
                if (m.range.first >= chunk && pos + n < src.size) continue
                table[m.groupValues[1].toInt()] = pos + m.range.first
            }
            var t = text.indexOf("trailer")
            while (t >= 0) {
                if (t < chunk || pos + n >= src.size) {
                    try {
                        val p = Parser(pos + t + 7)
                        (p.value() as? PdfObj.Dict)?.let { trailerDict = it }
                    } catch (e: IOException) {
                        // keep looking
                    }
                }
                t = text.indexOf("trailer", t + 7)
            }
            pos += chunk
        }
        return table to trailerDict
    }

    private fun startsWith(pos: Long, s: String): Boolean {
        val b = ByteArray(s.length)
        if (src.read(pos, b, 0, b.size) < b.size) return false
        return String(b, Charsets.ISO_8859_1) == s
    }

    // ------------------------------------------------------------------ parser

    /** Reads PDF tokens from the file starting at [start] (buffered window). */
    private inner class Parser(start: Long) {
        private var pos = start
        private val buf = ByteArray(16 * 1024)
        private var bufStart = -1L
        private var bufLen = 0
        private var depth = 0

        private fun byteAt(p: Long): Int {
            if (p < bufStart || p >= bufStart + bufLen) {
                bufStart = p
                bufLen = src.read(p, buf, 0, buf.size)
                if (bufLen <= 0) return -1
            }
            return buf[(p - bufStart).toInt()].toInt() and 0xFF
        }

        private fun peek(): Int = byteAt(pos)

        private fun isWhite(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

        private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code ||
            c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        private fun skipSpace() {
            while (true) {
                val c = peek()
                if (c < 0) return
                if (isWhite(c)) { pos++; continue }
                if (c == '%'.code) {
                    while (true) { val d = peek(); if (d < 0 || d == 10 || d == 13) break; pos++ }
                    continue
                }
                return
            }
        }

        private fun regular(): String {
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                if (c < 0 || isWhite(c) || isDelim(c)) break
                sb.append(c.toChar())
                pos++
                if (sb.length > 256) throw IOException("Damaged token")
            }
            return sb.toString()
        }

        /** The next bare keyword (obj, xref, n, f, R...). */
        fun keyword(): String {
            skipSpace()
            return regular()
        }

        fun peekKeyword(): String {
            val save = pos
            val k = keyword()
            pos = save
            return k
        }

        /** Reads `N G obj value [stream] endobj`. */
        fun readIndirect(num: Int): PdfObj {
            val n = value() as? PdfObj.Num ?: throw IOException("Damaged object")
            if (n.int != num) throw IOException("Object $num not found")
            value()
            if (keyword() != "obj") throw IOException("Damaged object")
            val v = value()
            val save = pos
            if (v is PdfObj.Dict && keyword() == "stream") {
                // EOL after the keyword: CRLF or LF.
                if (peek() == 13) pos++
                if (peek() == 10) pos++
                val len = when (val l = v["Length"]) {
                    is PdfObj.Num -> l.value.toLong()
                    is PdfObj.Ref -> (obj(l.num) as? PdfObj.Num)?.value?.toLong() ?: throw IOException("Damaged stream length")
                    else -> throw IOException("Damaged stream length")
                }
                return PdfObj.Stream(v, pos, len)
            }
            pos = save
            return v
        }

        /** The next value; `N G R` becomes a reference. */
        fun value(): PdfObj {
            skipSpace()
            val c = peek()
            if (c < 0) throw IOException("Unexpected end of file")
            if (++depth > 64) throw IOException("Nesting too deep")
            try {
                return when {
                    c == '/'.code -> { pos++; PdfObj.Name(nameText()) }
                    c == '('.code -> { pos++; literal() }
                    c == '<'.code -> {
                        if (byteAt(pos + 1) == '<'.code) { pos += 2; dict() } else { pos++; hex() }
                    }
                    c == '['.code -> { pos++; array() }
                    c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> number()
                    else -> {
                        val k = regular()
                        when (k) {
                            "true" -> PdfObj.Bool(true)
                            "false" -> PdfObj.Bool(false)
                            "null" -> PdfObj.Null
                            else -> throw IOException("Unexpected token '$k'")
                        }
                    }
                }
            } finally {
                depth--
            }
        }

        private fun number(): PdfObj {
            val t = regular()
            val v = t.toDoubleOrNull() ?: throw IOException("Bad number '$t'")
            // An integer may start an indirect reference: N G R.
            if (t.all { it.isDigit() }) {
                val save = pos
                skipSpace()
                val c = peek()
                if (c in '0'.code..'9'.code) {
                    val g = regular()
                    skipSpace()
                    val num = t.toIntOrNull()
                    val gen = g.toIntOrNull()
                    if (num != null && gen != null && peek() == 'R'.code) {
                        val after = byteAt(pos + 1)
                        if (after < 0 || isWhite(after) || isDelim(after)) {
                            pos++
                            return PdfObj.Ref(num, gen)
                        }
                    }
                }
                pos = save
            }
            return PdfObj.Num(v)
        }

        private fun nameText(): String {
            val raw = regular()
            if ('#' !in raw) return raw
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                if (ch == '#' && i + 2 < raw.length) {
                    val h = raw.substring(i + 1, i + 3).toIntOrNull(16)
                    if (h != null) { out.write(h); i += 3; continue }
                }
                out.write(ch.code)
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private fun literal(): PdfObj {
            val out = java.io.ByteArrayOutputStream()
            var nest = 1
            while (true) {
                val c = peek()
                if (c < 0) throw IOException("Unterminated string")
                pos++
                when (c) {
                    '('.code -> { nest++; out.write(c) }
                    ')'.code -> { nest--; if (nest == 0) break; out.write(c) }
                    '\\'.code -> {
                        val e = peek()
                        pos++
                        when (e) {
                            'n'.code -> out.write(10)
                            'r'.code -> out.write(13)
                            't'.code -> out.write(9)
                            'b'.code -> out.write(8)
                            'f'.code -> out.write(12)
                            13 -> if (peek() == 10) pos++
                            10 -> {}
                            in '0'.code..'7'.code -> {
                                var v = e - '0'.code
                                for (k in 0 until 2) {
                                    val d = peek()
                                    if (d in '0'.code..'7'.code) { v = v * 8 + (d - '0'.code); pos++ } else break
                                }
                                out.write(v and 0xFF)
                            }
                            else -> out.write(e)
                        }
                    }
                    else -> out.write(c)
                }
                if (out.size() > MAX_STRING) throw IOException("String too long")
            }
            return PdfObj.Str(out.toByteArray())
        }

        private fun hex(): PdfObj {
            val out = java.io.ByteArrayOutputStream()
            var hi = -1
            while (true) {
                val c = peek()
                if (c < 0) throw IOException("Unterminated string")
                pos++
                if (c == '>'.code) break
                val d = Character.digit(c, 16)
                if (d < 0) continue
                if (hi < 0) hi = d else { out.write(hi * 16 + d); hi = -1 }
                if (out.size() > MAX_STRING) throw IOException("String too long")
            }
            if (hi >= 0) out.write(hi * 16)
            return PdfObj.Str(out.toByteArray())
        }

        private fun array(): PdfObj {
            val items = ArrayList<PdfObj>()
            while (true) {
                skipSpace()
                val c = peek()
                if (c < 0) throw IOException("Unterminated array")
                if (c == ']'.code) { pos++; break }
                items += value()
                if (items.size > MAX_ITEMS) throw IOException("Array too long")
            }
            return PdfObj.Arr(items)
        }

        private fun dict(): PdfObj {
            val map = LinkedHashMap<String, PdfObj>()
            while (true) {
                skipSpace()
                val c = peek()
                if (c < 0) throw IOException("Unterminated dictionary")
                if (c == '>'.code) {
                    if (byteAt(pos + 1) == '>'.code) { pos += 2; break }
                    throw IOException("Damaged dictionary")
                }
                val key = value() as? PdfObj.Name ?: throw IOException("Damaged dictionary key")
                map[key.name] = value()
                if (map.size > MAX_ITEMS) throw IOException("Dictionary too large")
            }
            return PdfObj.Dict(map)
        }
    }

    companion object {
        private const val MAX_STREAM = 512L shl 20
        private const val MAX_PAYLOAD = 256L shl 20
        private const val MAX_IMAGE_PIXELS = 64L * 1024 * 1024
        private const val MAX_STRING = 1 shl 20
        private const val MAX_ITEMS = 1_000_000

        /** Reads [file]; the caller closes the reader. */
        fun open(file: File): OwnPdfReader {
            val bytes = FilePdfBytes(file)
            return try {
                OwnPdfReader(bytes)
            } catch (e: Throwable) {
                bytes.close()
                throw e
            }
        }
    }
}
