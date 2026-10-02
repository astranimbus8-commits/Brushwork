package com.brushwork.paint.exchange.svg

import java.io.IOException
import java.util.Base64

/** A file that is not usable SVG (malformed XML, over the limits that stop parsing). */
class SvgFormatException(message: String) : IOException(message)

/**
 * A run of the source bytes (UTF-8) kept without copying: large attribute values and texts
 * (data URIs, payloads) stay in the file's byte array instead of becoming large strings.
 */
class ByteSlice(private val bytes: ByteArray, val offset: Int, val length: Int) {
    override fun toString(): String = String(bytes, offset, length, Charsets.UTF_8)

    /** True when the slice starts with the ASCII [prefix] (after leading whitespace), ignoring case. */
    fun startsWith(prefix: String): Boolean {
        var i = offset
        val end = offset + length
        while (i < end && isSpace(bytes[i])) i++
        if (end - i < prefix.length) return false
        for (k in prefix.indices) {
            if (Character.toLowerCase(bytes[i + k].toInt().toChar()) != Character.toLowerCase(prefix[k])) return false
        }
        return true
    }

    /** Index (relative to [offset]) of the first [ch], or -1. */
    fun indexOf(ch: Char): Int {
        for (i in 0 until length) if (bytes[offset + i].toInt() == ch.code) return i
        return -1
    }

    /**
     * The base64 data after [from] (relative): whitespace ignored, URL escapes decoded (`%2B` is
     * `+`, `%2F` `/`, `%3D` `=`; escaped whitespace such as `%20` or `%0A` is ignored too).
     */
    fun decodeBase64(from: Int = 0): ByteArray {
        val clean = ByteArray(length - from)
        var n = 0
        var i = offset + from
        val end = offset + length
        while (i < end) {
            val b = bytes[i]
            if (b == '%'.code.toByte() && i + 2 < end) {
                val hi = Character.digit(bytes[i + 1].toInt(), 16)
                val lo = Character.digit(bytes[i + 2].toInt(), 16)
                if (hi >= 0 && lo >= 0) {
                    val v = (hi shl 4 or lo).toByte()
                    if (!isSpace(v)) clean[n++] = v
                    i += 3
                    continue
                }
            }
            if (!isSpace(b)) clean[n++] = b
            i++
        }
        return try {
            Base64.getMimeDecoder().decode(clean.copyOf(n))
        } catch (e: IllegalArgumentException) {
            throw IOException("Damaged base64 data", e)
        }
    }

    private fun isSpace(b: Byte): Boolean = b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == '\t'.code.toByte()
}

/** An attribute: its qualified name and value (large values only as [slice]). */
class XmlAttr(val name: String, val value: String?, val slice: ByteSlice?) {
    /** The value as text (decodes a slice). */
    val text: String get() = value ?: slice?.toString() ?: ""
}

/** What [XmlTokenizer] reads. */
sealed class XmlToken {
    class Start(val name: String, val attrs: List<XmlAttr>, val selfClosing: Boolean) : XmlToken()
    class End(val name: String) : XmlToken()

    /** Character data (entities decoded); large runs without entities only as [slice]. */
    class Text(val text: String?, val slice: ByteSlice?) : XmlToken()
}

/**
 * A small pull tokenizer for XML in UTF-8 bytes (v1.5 §4.11b, "own tokenizer"): tags,
 * attributes, text and CDATA. It never processes a DOCTYPE (the declaration and its internal
 * subset are skipped: no entity expansion, no external entities); only the five predefined
 * entities and numeric character references are decoded, any other `&name;` stays literal text.
 * Comments and processing instructions are skipped. Malformed input throws [SvgFormatException].
 */
class XmlTokenizer(private val b: ByteArray) {
    private var pos = 0

    init {
        // UTF-8 byte order mark.
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) pos = 3
    }

    /** The next token, or null at the end. */
    fun next(): XmlToken? {
        while (pos < b.size) {
            if (b[pos] != LT) return text()
            when {
                startsWith("<!--") -> skipPast("-->", "comment")
                startsWith("<![CDATA[") -> {
                    val start = pos + 9
                    val end = find("]]>", start) ?: throw SvgFormatException("Unterminated CDATA section")
                    pos = end + 3
                    return chars(start, end, decode = false)
                }
                startsWith("<!DOCTYPE") || startsWith("<!doctype") -> skipDoctype()
                startsWith("<?") -> skipPast("?>", "processing instruction")
                startsWith("<!") -> skipPast(">", "declaration")
                startsWith("</") -> {
                    pos += 2
                    val name = name()
                    skipSpace()
                    if (pos >= b.size || b[pos] != GT) throw SvgFormatException("Malformed end tag </$name")
                    pos++
                    return XmlToken.End(name)
                }
                else -> return startTag()
            }
        }
        return null
    }

    private fun text(): XmlToken {
        val start = pos
        while (pos < b.size && b[pos] != LT) pos++
        return chars(start, pos, decode = true)
    }

    private fun chars(start: Int, end: Int, decode: Boolean): XmlToken.Text {
        val len = end - start
        if (len > BIG && (!decode || !contains(AMP, start, end))) return XmlToken.Text(null, ByteSlice(b, start, len))
        val s = String(b, start, len, Charsets.UTF_8)
        return XmlToken.Text(if (decode) decodeEntities(s) else s, null)
    }

    private fun startTag(): XmlToken {
        pos++ // <
        val name = name()
        if (name.isEmpty()) throw SvgFormatException("Malformed tag at byte $pos")
        val attrs = ArrayList<XmlAttr>()
        while (true) {
            skipSpace()
            if (pos >= b.size) throw SvgFormatException("Unterminated tag <$name")
            val c = b[pos]
            if (c == GT) { pos++; return XmlToken.Start(name, attrs, false) }
            if (c == SLASH) {
                if (pos + 1 < b.size && b[pos + 1] == GT) { pos += 2; return XmlToken.Start(name, attrs, true) }
                throw SvgFormatException("Malformed tag <$name")
            }
            val an = name()
            if (an.isEmpty()) throw SvgFormatException("Malformed attribute in <$name")
            skipSpace()
            if (pos >= b.size || b[pos] != EQ) {
                // HTML-style attribute without a value: tolerated as empty.
                attrs += XmlAttr(an, "", null)
                continue
            }
            pos++
            skipSpace()
            if (pos >= b.size) throw SvgFormatException("Unterminated tag <$name")
            val q = b[pos]
            if (q != QUOT && q != APOS) throw SvgFormatException("Unquoted attribute $an in <$name")
            val start = pos + 1
            var end = start
            while (end < b.size && b[end] != q) end++
            if (end >= b.size) throw SvgFormatException("Unterminated attribute $an in <$name")
            pos = end + 1
            val len = end - start
            attrs += if (len > BIG && !contains(AMP, start, end)) {
                XmlAttr(an, null, ByteSlice(b, start, len))
            } else {
                XmlAttr(an, normalize(decodeEntities(String(b, start, len, Charsets.UTF_8))), null)
            }
            if (attrs.size > MAX_ATTRS) throw SvgFormatException("Too many attributes in <$name")
        }
    }

    private fun name(): String {
        val start = pos
        while (pos < b.size) {
            val c = b[pos].toInt() and 0xFF
            val ok = c >= 0x80 || c == ':'.code || c == '_'.code || c == '-'.code || c == '.'.code ||
                (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code)
            if (!ok) break
            pos++
            if (pos - start > 256) throw SvgFormatException("Name too long")
        }
        return String(b, start, pos - start, Charsets.UTF_8)
    }

    /** Skips `<!DOCTYPE ... [internal subset] >` without interpreting any of it. */
    private fun skipDoctype() {
        pos += 9
        var depth = 0
        while (pos < b.size) {
            val c = b[pos]
            when {
                c == QUOT || c == APOS -> {
                    val q = c
                    pos++
                    while (pos < b.size && b[pos] != q) pos++
                }
                startsWith("<!--") -> { skipPast("-->", "comment"); continue }
                c == '['.code.toByte() -> depth++
                c == ']'.code.toByte() -> depth--
                c == GT && depth <= 0 -> { pos++; return }
            }
            pos++
        }
        throw SvgFormatException("Unterminated DOCTYPE")
    }

    private fun skipPast(end: String, what: String) {
        val at = find(end, pos + 2) ?: throw SvgFormatException("Unterminated $what")
        pos = at + end.length
    }

    private fun skipSpace() {
        while (pos < b.size) {
            val c = b[pos]
            if (c == SP || c == '\n'.code.toByte() || c == '\r'.code.toByte() || c == '\t'.code.toByte()) pos++ else break
        }
    }

    private fun startsWith(s: String): Boolean {
        if (pos + s.length > b.size) return false
        for (i in s.indices) if (b[pos + i].toInt() != s[i].code) return false
        return true
    }

    private fun find(s: String, from: Int): Int? {
        val first = s[0].code.toByte()
        var i = from
        val last = b.size - s.length
        while (i <= last) {
            if (b[i] == first) {
                var k = 1
                while (k < s.length && b[i + k].toInt() == s[k].code) k++
                if (k == s.length) return i
            }
            i++
        }
        return null
    }

    private fun contains(c: Byte, from: Int, to: Int): Boolean {
        for (i in from until to) if (b[i] == c) return true
        return false
    }

    companion object {
        /** Values longer than this (bytes) without entities are kept as [ByteSlice]s. */
        const val BIG = 4096
        private const val MAX_ATTRS = 1000
        private const val LT = '<'.code.toByte()
        private const val GT = '>'.code.toByte()
        private const val SLASH = '/'.code.toByte()
        private const val EQ = '='.code.toByte()
        private const val QUOT = '"'.code.toByte()
        private const val APOS = '\''.code.toByte()
        private const val AMP = '&'.code.toByte()
        private const val SP = ' '.code.toByte()

        /** Attribute value normalization: tabs and line breaks become spaces. */
        private fun normalize(s: String): String =
            if (s.none { it == '\n' || it == '\r' || it == '\t' }) s else s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

        /**
         * Decodes `&lt; &gt; &amp; &quot; &apos;` and `&#n;` / `&#xh;`; every other `&...` is kept
         * literally (no entity expansion of any kind).
         */
        fun decodeEntities(s: String): String {
            if ('&' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch != '&') { sb.append(ch); i++; continue }
                val semi = s.indexOf(';', i + 1)
                if (semi < 0 || semi - i > 12) { sb.append(ch); i++; continue }
                val ent = s.substring(i + 1, semi)
                val rep: String? = when {
                    ent == "lt" -> "<"
                    ent == "gt" -> ">"
                    ent == "amp" -> "&"
                    ent == "quot" -> "\""
                    ent == "apos" -> "'"
                    ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePoint)
                    ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> null
                }
                if (rep == null) { sb.append(ch); i++; continue }
                sb.append(rep)
                i = semi + 1
            }
            return sb.toString()
        }

        private fun codePoint(cp: Int): String? =
            if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
    }
}
