package com.brushwork.paint.fonts

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Font files for tests: real fonts shipped with Robolectric's native runtime (on the test class
 * path under `fonts/`), and tiny hand-built sfnt files whose `name` table the test controls.
 */
internal object TestFonts {

    /** A real TrueType font from the class path, e.g. "Arvo-Regular.ttf". */
    fun real(name: String): ByteArray =
        requireNotNull(TestFonts::class.java.classLoader!!.getResourceAsStream("fonts/$name")) { "no fonts/$name on the class path" }.use { it.readBytes() }

    /** A name record: platform, encoding, language, name ID and the text. */
    class NameRecord(val platform: Int, val encoding: Int, val language: Int, val nameId: Int, val text: String)

    fun windows(nameId: Int, text: String, language: Int = 0x0409) = NameRecord(3, 1, language, nameId, text)

    fun mac(nameId: Int, text: String) = NameRecord(1, 0, 0, nameId, text)

    private fun nameTable(records: List<NameRecord>): ByteArray {
        val strings = ByteArrayOutputStream()
        val encoded = records.map { r ->
            val bytes = if (r.platform == 1) {
                // Mac Roman: ASCII plus the few accented letters the tests use.
                ByteArray(r.text.length) { i ->
                    when (val ch = r.text[i]) {
                        'é' -> 0x8E.toByte()
                        'ü' -> 0x9F.toByte()
                        else -> ch.code.toByte()
                    }
                }
            } else r.text.toByteArray(Charsets.UTF_16BE)
            val off = strings.size()
            strings.write(bytes)
            Triple(r, off, bytes.size)
        }
        val header = 6 + 12 * records.size
        val b = ByteBuffer.allocate(header + strings.size())
        b.putShort(0).putShort(records.size.toShort()).putShort(header.toShort())
        for ((r, off, len) in encoded) {
            b.putShort(r.platform.toShort()).putShort(r.encoding.toShort()).putShort(r.language.toShort())
            b.putShort(r.nameId.toShort()).putShort(len.toShort()).putShort(off.toShort())
        }
        b.put(strings.toByteArray())
        return b.array()
    }

    private fun headTable(upem: Int = 1000, headMagic: Long = 0x5F0F3CF5L): ByteArray {
        val b = ByteBuffer.allocate(54)
        b.putInt(0x00010000) // version
        b.putInt(0x00010000) // fontRevision
        b.putInt(0) // checksumAdjustment
        b.putInt(headMagic.toInt())
        b.putShort(0) // flags
        b.putShort(upem.toShort())
        b.putLong(0); b.putLong(0) // created, modified
        b.putShort((-100).toShort()); b.putShort((-250).toShort()); b.putShort(1200); b.putShort(900) // bbox
        return b.array()
    }

    /**
     * A structurally valid sfnt (not drawable: the glyph tables are dummies) with [names] and
     * the given tables; [omit] leaves tables out, [headMagic] damages the head table.
     */
    fun synthetic(
        names: List<NameRecord>,
        sfntVersion: Int = 0x00010000,
        omit: Set<String> = emptySet(),
        headMagic: Long = 0x5F0F3CF5L,
    ): ByteArray {
        val tables = linkedMapOf(
            "cmap" to ByteArray(12),
            "glyf" to ByteArray(8),
            "head" to headTable(headMagic = headMagic),
            "loca" to ByteArray(8),
            "name" to nameTable(names),
        ).filterKeys { it !in omit }
        return sfnt(sfntVersion, tables)
    }

    private fun sfnt(version: Int, tables: Map<String, ByteArray>, base: Int = 0): ByteArray {
        val dirSize = 12 + 16 * tables.size
        var offset = base + dirSize
        val layout = tables.map { (tag, data) ->
            val o = offset
            offset += (data.size + 3) and 3.inv()
            Triple(tag, o, data)
        }
        val b = ByteBuffer.allocate(offset - base)
        b.putInt(version).putShort(tables.size.toShort()).putShort(0).putShort(0).putShort(0)
        for ((tag, o, data) in layout) {
            b.put(tag.toByteArray(Charsets.ISO_8859_1)).putInt(0).putInt(o).putInt(data.size)
        }
        for ((_, o, data) in layout) {
            b.position(o - base)
            b.put(data)
        }
        return b.array()
    }

    /** A collection ('ttcf') holding one synthetic font. */
    fun collection(names: List<NameRecord>): ByteArray {
        val headerSize = 16
        val inner = sfntAt(headerSize, names)
        val b = ByteBuffer.allocate(headerSize + inner.size)
        b.putInt(0x74746366).putShort(1).putShort(0).putInt(1).putInt(headerSize)
        b.put(inner)
        return b.array()
    }

    private fun sfntAt(base: Int, names: List<NameRecord>): ByteArray {
        val tables = linkedMapOf(
            "cmap" to ByteArray(12), "glyf" to ByteArray(8), "head" to headTable(), "loca" to ByteArray(8), "name" to nameTable(names),
        )
        return sfnt(0x00010000, tables, base)
    }

    /** A zip of [entries] (name -> bytes), in order, entry names written in [charset]. */
    fun zip(vararg entries: Pair<String, ByteArray>, charset: Charset = Charsets.UTF_8): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun File.write(bytes: ByteArray): File = apply { parentFile?.mkdirs(); writeBytes(bytes) }
}
