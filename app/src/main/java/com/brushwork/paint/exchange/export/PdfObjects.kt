package com.brushwork.paint.exchange.export

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * The syntax layer of the pure-Kotlin PDF writer (v1.5 §4.10, V9): object numbering, byte offsets,
 * the classic cross-reference table, streams, and value formatting (numbers, names, strings).
 */

/** PDF value formatting. */
object Pdf {
    /** Largest coordinate written (implementation limit of many readers; off-canvas geometry only). */
    const val MAX_COORD = 32000f

    /**
     * [v] as a PDF number: at most 4 decimals, no exponent, trailing zeros dropped; non-finite
     * values become 0 and huge ones are clamped to ±[MAX_COORD].
     */
    fun num(v: Float): String = num(v.toDouble())

    fun num(v: Double): String {
        if (!v.isFinite()) return "0"
        val c = v.coerceIn(-MAX_COORD.toDouble(), MAX_COORD.toDouble())
        val scaled = (c * 10000.0).roundToLong()
        if (scaled == 0L) return "0"
        val neg = scaled < 0
        val a = abs(scaled)
        val int = a / 10000
        var frac = a % 10000
        val sb = StringBuilder()
        if (neg) sb.append('-')
        sb.append(int)
        if (frac != 0L) {
            var digits = 4
            while (frac % 10 == 0L) { frac /= 10; digits-- }
            sb.append('.')
            val f = frac.toString()
            repeat(digits - f.length) { sb.append('0') }
            sb.append(f)
        }
        return sb.toString()
    }

    /** A color channel 0..255 as a 0..1 PDF number. */
    fun channel(c: Int): String = num((c and 0xFF) / 255.0)

    /** `/Name` with every character outside the regular set written as #xx. */
    fun name(n: String): String {
        val sb = StringBuilder("/")
        for (b in n.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val regular = c in 0x21..0x7E && c.toChar() !in "#()<>[]{}/%"
            if (regular) sb.append(c.toChar()) else sb.append('#').append(HEX[c shr 4]).append(HEX[c and 15])
        }
        return sb.toString()
    }

    /**
     * A text string: a literal `( )` string with `\ ( )` escaped when every character is
     * printable ASCII, else a hex string of UTF-16BE with a byte order mark.
     */
    fun text(s: String): String {
        if (s.all { it.code in 0x20..0x7E }) {
            val sb = StringBuilder("(")
            for (ch in s) {
                if (ch == '(' || ch == ')' || ch == '\\') sb.append('\\')
                sb.append(ch)
            }
            return sb.append(')').toString()
        }
        val sb = StringBuilder("<FEFF")
        for (ch in s) {
            val c = ch.code
            sb.append(HEX[(c shr 12) and 15]).append(HEX[(c shr 8) and 15]).append(HEX[(c shr 4) and 15]).append(HEX[c and 15])
        }
        return sb.append('>').toString()
    }

    fun ref(num: Int): String = "$num 0 R"

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** zlib-compresses [data] (FlateDecode). */
    fun flate(data: ByteArray, level: Int = 6): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 2))
            val buf = ByteArray(32 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }
}

/** A dictionary written in insertion order; values are PDF syntax strings (see [Pdf]). */
class PdfDict {
    private val entries = LinkedHashMap<String, String>()

    operator fun set(key: String, value: String) { entries[key] = value }

    operator fun set(key: String, value: PdfDict) { entries[key] = value.toString() }

    fun isEmpty(): Boolean = entries.isEmpty()

    override fun toString(): String {
        val sb = StringBuilder("<<")
        for ((k, v) in entries) sb.append(Pdf.name(k)).append(' ').append(v).append(' ')
        if (sb.length > 2) sb.setLength(sb.length - 1)
        return sb.append(">>").toString()
    }
}

/** Counts the bytes written through it (xref offsets are byte offsets). */
private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}

/**
 * Writes the objects of one PDF file to [out] in order and finishes it with the cross-reference
 * table and trailer. Object numbers can be reserved before the object is written (forward
 * references); every reserved number must be written before [finish].
 */
class PdfFile(out: OutputStream) {
    private val stream = CountingStream(out)
    private val offsets = ArrayList<Long>()

    init {
        // A binary comment after the header: transfer programs then treat the file as binary.
        write("%PDF-1.7\n")
        stream.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    /** Reserves the next object number. */
    fun reserve(): Int {
        offsets += -1L
        return offsets.size
    }

    /** Writes object [num] (reserved) with the syntax [body]. */
    fun obj(num: Int, body: String) {
        begin(num)
        write(body)
        write("\nendobj\n")
    }

    /** Reserves and writes an object; returns its number. */
    fun obj(body: String): Int = reserve().also { obj(it, body) }

    /** Writes stream object [num]: [dict] (its /Length is set here) and the raw [data]. */
    fun stream(num: Int, dict: PdfDict, data: ByteArray) {
        dict["Length"] = data.size.toString()
        begin(num)
        write(dict.toString())
        write("\nstream\n")
        stream.write(data)
        write("\nendstream\nendobj\n")
    }

    /** Reserves and writes a stream object; returns its number. */
    fun stream(dict: PdfDict, data: ByteArray): Int = reserve().also { stream(it, dict, data) }

    /**
     * Writes stream object [num] whose data [body] streams (its length is not known up front: it
     * goes into a separate length object written right after).
     */
    inline fun streaming(num: Int, dict: PdfDict, body: (OutputStream) -> Unit) {
        val lengthNum = reserve()
        dict["Length"] = Pdf.ref(lengthNum)
        val start = beginStream(num, dict)
        body(dataStream)
        val length = endStream(start)
        obj(lengthNum, length.toString())
    }

    /** The data part of a streaming object (do not close). */
    val dataStream: OutputStream get() = stream

    @PublishedApi
    internal fun beginStream(num: Int, dict: PdfDict): Long {
        begin(num)
        write(dict.toString())
        write("\nstream\n")
        return stream.count
    }

    @PublishedApi
    internal fun endStream(start: Long): Long {
        val length = stream.count - start
        write("\nendstream\nendobj\n")
        return length
    }

    private fun begin(num: Int) {
        require(num in 1..offsets.size) { "Object $num was not reserved" }
        check(offsets[num - 1] < 0) { "Object $num written twice" }
        offsets[num - 1] = stream.count
        write("$num 0 obj\n")
    }

    /** Writes the cross-reference table and the trailer naming [root] (and [info]); flushes. */
    fun finish(root: Int, info: Int? = null, id: String? = null) {
        val missing = offsets.indexOfFirst { it < 0 }
        check(missing < 0) { "Object ${missing + 1} was reserved but not written" }
        val xref = stream.count
        val sb = StringBuilder()
        sb.append("xref\n0 ").append(offsets.size + 1).append('\n')
        sb.append("0000000000 65535 f\r\n")
        for (o in offsets) sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n\r\n", o))
        val trailer = PdfDict()
        trailer["Size"] = (offsets.size + 1).toString()
        trailer["Root"] = Pdf.ref(root)
        if (info != null) trailer["Info"] = Pdf.ref(info)
        if (id != null) trailer["ID"] = "[<$id> <$id>]"
        sb.append("trailer\n").append(trailer).append("\nstartxref\n").append(xref).append("\n%%EOF\n")
        write(sb.toString())
        stream.flush()
    }

    private fun write(s: String) = stream.write(s.toByteArray(Charsets.ISO_8859_1))
}
