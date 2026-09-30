package com.brushwork.paint.fonts

import java.io.File
import java.io.RandomAccessFile

/** Kinds of font files Android can load. [ext] is the extension a stored copy gets. */
enum class FontFormat(val ext: String) {
    TRUETYPE("ttf"),
    /** OpenType with CFF outlines ("OTTO"). */
    OPENTYPE_CFF("otf"),
    /** TrueType / OpenType collection: the first font of the file is used. */
    COLLECTION("ttc"),
}

/**
 * What [FontFileParser] read from a font file: its format, names (from the `name` table) and
 * glyph bounding box (from `head`, in em units with y pointing UP like in the font).
 */
data class FontFileInfo(
    val format: FontFormat,
    /** Name ID 4 ("Some Font Bold"), when the font has one. */
    val fullName: String?,
    /** Name ID 16 (typographic family) or 1 (family). */
    val family: String?,
    /** Name ID 17 (typographic subfamily) or 2 ("Regular", "Bold"...). */
    val subfamily: String?,
    val unitsPerEm: Int,
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    val tables: Set<String>,
) {
    /**
     * The name to show: the full name, else the family plus a non-regular style, else null
     * (the caller then uses the file name).
     */
    val displayName: String?
        get() {
            fullName?.let { FontIds.cleanName(it) }?.let { return it }
            val fam = family?.let { FontIds.cleanName(it) } ?: return null
            val sub = subfamily?.let { FontIds.cleanName(it) }
            return if (sub == null || sub.equals("Regular", ignoreCase = true) || sub.equals("Normal", ignoreCase = true)) fam
            else FontIds.cleanName("$fam $sub") ?: fam
        }
}

/**
 * A small, defensive reader of TrueType / OpenType / collection files (pure Kotlin): checks that a
 * file really is a usable font (header, table directory inside the file, `head` with its magic
 * number, `cmap`, glyph outlines) and reads its names from the `name` table. Damaged or hostile
 * data never throws: [parse] returns null.
 */
object FontFileParser {
    private const val SFNT_V1 = 0x00010000L
    private const val TAG_TRUE = 0x74727565L // 'true' (old Apple TrueType)
    private const val TAG_OTTO = 0x4F54544FL // 'OTTO'
    private const val TAG_TTCF = 0x74746366L // 'ttcf'
    private const val HEAD_MAGIC = 0x5F0F3CF5L

    private const val MAX_TABLES = 1024
    private const val MAX_FONTS_IN_COLLECTION = 1024
    /** Name tables are a few KB; anything much larger is damaged (or an attack on memory). */
    private const val MAX_NAME_TABLE = 1 shl 20

    /** Name IDs read from the `name` table. */
    const val NAME_FAMILY = 1
    const val NAME_SUBFAMILY = 2
    const val NAME_FULL = 4
    const val NAME_TYPO_FAMILY = 16
    const val NAME_TYPO_SUBFAMILY = 17

    /** Format of a file starting with [header] (at least 4 bytes), or null if it isn't a font we can load. */
    fun sniff(header: ByteArray, length: Int = header.size): FontFormat? {
        if (length < 4) return null
        return when (u32(header, 0)) {
            SFNT_V1, TAG_TRUE -> FontFormat.TRUETYPE
            TAG_OTTO -> FontFormat.OPENTYPE_CFF
            TAG_TTCF -> FontFormat.COLLECTION
            else -> null
        }
    }

    fun parse(bytes: ByteArray): FontFileInfo? = parse(ArraySource(bytes))

    fun parse(file: File): FontFileInfo? = try {
        RandomAccessFile(file, "r").use { raf -> parse(FileSource(raf)) }
    } catch (e: java.io.IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    // ------------------------------------------------------------------ sources

    private interface Source {
        val size: Long
        /** [len] bytes at [pos], or null when they aren't all inside the file. */
        fun read(pos: Long, len: Int): ByteArray?
    }

    private class ArraySource(private val b: ByteArray) : Source {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, len: Int): ByteArray? {
            if (pos < 0 || len < 0 || pos + len > b.size) return null
            return b.copyOfRange(pos.toInt(), pos.toInt() + len)
        }
    }

    private class FileSource(private val raf: RandomAccessFile) : Source {
        override val size: Long = raf.length()
        override fun read(pos: Long, len: Int): ByteArray? {
            if (pos < 0 || len < 0 || pos + len > size) return null
            val out = ByteArray(len)
            raf.seek(pos)
            raf.readFully(out)
            return out
        }
    }

    // ------------------------------------------------------------------ parsing

    private class Table(val offset: Long, val length: Long)

    private fun parse(src: Source): FontFileInfo? = try {
        parseOrNull(src)
    } catch (e: RuntimeException) {
        // Index arithmetic on damaged data: not a usable font.
        null
    } catch (e: java.io.IOException) {
        null
    }

    private fun parseOrNull(src: Source): FontFileInfo? {
        val head0 = src.read(0, 12) ?: return null
        val format = sniff(head0) ?: return null
        var fontOffset = 0L
        if (format == FontFormat.COLLECTION) {
            val numFonts = u32(head0, 8)
            if (numFonts < 1 || numFonts > MAX_FONTS_IN_COLLECTION) return null
            val first = src.read(12, 4) ?: return null
            fontOffset = u32(first, 0)
            val inner = src.read(fontOffset, 4) ?: return null
            val innerFormat = sniff(inner) ?: return null
            if (innerFormat == FontFormat.COLLECTION) return null
        }
        val header = src.read(fontOffset, 12) ?: return null
        val numTables = u16(header, 4)
        if (numTables < 1 || numTables > MAX_TABLES) return null
        val dir = src.read(fontOffset + 12, numTables * 16) ?: return null
        val tables = HashMap<String, Table>()
        for (i in 0 until numTables) {
            val o = i * 16
            val tag = String(CharArray(4) { k -> (dir[o + k].toInt() and 0xFF).toChar() })
            val offset = u32(dir, o + 8)
            val length = u32(dir, o + 12)
            // Every table inside the file (a few bytes of padding at the very end are tolerated).
            if (offset + length > src.size + 3) return null
            tables[tag] = Table(offset, length)
        }
        if ("cmap" !in tables) return null
        val hasOutlines = ("glyf" in tables && "loca" in tables) || "CFF " in tables || "CFF2" in tables || "CBDT" in tables
        if (!hasOutlines) return null
        val headTable = tables["head"] ?: return null
        if (headTable.length < 54) return null
        val head = src.read(headTable.offset, 54) ?: return null
        if (u32(head, 12) != HEAD_MAGIC) return null
        val upem = u16(head, 18)
        if (upem < 16 || upem > 16384) return null
        val em = upem.toFloat()
        val names = tables["name"]?.let { readNames(src, it) } ?: emptyMap()
        return FontFileInfo(
            format = format,
            fullName = names[NAME_FULL],
            family = names[NAME_TYPO_FAMILY] ?: names[NAME_FAMILY],
            subfamily = names[NAME_TYPO_SUBFAMILY] ?: names[NAME_SUBFAMILY],
            unitsPerEm = upem,
            xMin = s16(head, 36) / em,
            yMin = s16(head, 38) / em,
            xMax = s16(head, 40) / em,
            yMax = s16(head, 42) / em,
            tables = tables.keys.toSet(),
        )
    }

    /** Best string of each wanted name ID (English Windows names first). */
    private fun readNames(src: Source, t: Table): Map<Int, String> {
        if (t.length < 6 || t.length > MAX_NAME_TABLE) return emptyMap()
        val b = src.read(t.offset, t.length.toInt()) ?: return emptyMap()
        val count = u16(b, 2)
        val stringBase = u16(b, 4)
        val best = HashMap<Int, Pair<Int, String>>()
        for (i in 0 until count) {
            val r = 6 + i * 12
            if (r + 12 > b.size) break
            val platform = u16(b, r)
            val encoding = u16(b, r + 2)
            val language = u16(b, r + 4)
            val nameId = u16(b, r + 6)
            if (nameId != NAME_FAMILY && nameId != NAME_SUBFAMILY && nameId != NAME_FULL && nameId != NAME_TYPO_FAMILY && nameId != NAME_TYPO_SUBFAMILY) continue
            val len = u16(b, r + 8)
            val off = stringBase + u16(b, r + 10)
            if (len == 0 || off + len > b.size) continue
            val score = score(platform, encoding, language)
            if (score <= 0) continue
            if ((best[nameId]?.first ?: -1) >= score) continue
            val s = decode(b, off, len, platform) ?: continue
            if (s.isBlank()) continue
            best[nameId] = score to s
        }
        return best.mapValues { it.value.second }
    }

    private fun score(platform: Int, encoding: Int, language: Int): Int = when (platform) {
        3 -> when {
            encoding !in intArrayOf(0, 1, 10) -> 0
            language == 0x0409 -> 100
            language and 0x3FF == 0x09 -> 90
            else -> 60
        }
        0 -> 80
        1 -> if (encoding == 0) (if (language == 0) 70 else 50) else 0
        else -> 0
    }

    private fun decode(b: ByteArray, off: Int, len: Int, platform: Int): String? = when (platform) {
        0, 3 -> if (len % 2 != 0) null else String(b, off, len, Charsets.UTF_16BE)
        1 -> buildString(len) {
            for (i in off until off + len) {
                val v = b[i].toInt() and 0xFF
                append(if (v < 0x80) v.toChar() else MAC_ROMAN[v - 0x80])
            }
        }
        else -> null
    }

    /** Upper half (0x80..0xFF) of the Mac OS Roman encoding used by old Mac name records. */
    private const val MAC_ROMAN =
        "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü" +
            "†°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø" +
            "¿¡¬√ƒ≈∆«»… ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ" +
            "‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ"

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
}
