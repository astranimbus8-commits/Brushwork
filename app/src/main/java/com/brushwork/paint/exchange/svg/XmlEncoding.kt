package com.brushwork.paint.exchange.svg

import java.nio.charset.Charset

/**
 * The character encoding of an XML file, for the UTF-8 [XmlTokenizer] (v1.5 §4.11b): SVG files
 * in UTF-16 (with or without a byte order mark) or in an 8-bit encoding named by their XML
 * declaration (Illustrator's SVG Options offer UTF-8, UTF-16 and ISO-8859-1) are transcoded to
 * UTF-8. UTF-8 and ASCII files, and files whose encoding is unknown, are returned as they are
 * (no copy).
 */
object XmlEncoding {

    /** [bytes] as UTF-8. */
    fun toUtf8(bytes: ByteArray): ByteArray {
        val cs = charsetOf(bytes) ?: return bytes
        return try {
            String(bytes, cs).removePrefix("﻿").toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            bytes
        }
    }

    /** The encoding [bytes] need to be transcoded from, or null when they are UTF-8 already (or unknown). */
    fun charsetOf(bytes: ByteArray): Charset? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        // Byte order marks.
        if (at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) return null
        if (at(0) == 0xFF && at(1) == 0xFE) return Charsets.UTF_16LE
        if (at(0) == 0xFE && at(1) == 0xFF) return Charsets.UTF_16BE
        // UTF-16 without one: "<?" or "<s" (or whitespace) as 16-bit units.
        if (at(0) == 0 && at(1) != 0 && at(2) == 0 && at(3) != 0) return Charsets.UTF_16BE
        if (at(0) != 0 && at(1) == 0 && at(2) != 0 && at(3) == 0) return Charsets.UTF_16LE
        // An 8-bit encoding named by the XML declaration.
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
        if (!head.trimStart().startsWith("<?xml")) return null
        val decl = head.substringBefore("?>")
        val name = ENCODING.find(decl)?.groupValues?.get(2)?.trim() ?: return null
        val upper = name.uppercase()
        if (upper == "UTF-8" || upper == "UTF8" || upper == "US-ASCII" || upper == "ASCII") return null
        val cs = try {
            Charset.forName(name)
        } catch (e: Exception) {
            return null
        }
        // A declaration that says UTF-16 in a file that isn't (handled above) is ignored.
        if (cs.name().startsWith("UTF-16") || cs.name().startsWith("UTF-32")) return null
        // A file that says Latin-1 but is valid UTF-8 (converted or hand-edited) is read as UTF-8:
        // real Latin-1 letters (é = E9 before ASCII) are never valid UTF-8.
        return if (validUtf8(bytes)) null else cs
    }

    /** True when [bytes] are well-formed UTF-8 (no copy; up to 20 MB are scanned). */
    internal fun validUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= n && extra > 0) return false
            for (k in 1..extra) if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            i += extra + 1
        }
        return true
    }

    private val ENCODING = Regex("""encoding\s*=\s*(["'])([A-Za-z0-9._:-]+)\1""")
}
