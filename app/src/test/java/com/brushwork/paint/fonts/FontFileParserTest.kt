package com.brushwork.paint.fonts

import com.brushwork.paint.fonts.TestFonts.mac
import com.brushwork.paint.fonts.TestFonts.windows
import com.brushwork.paint.tools.text.TextFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The font file reader (`name` table, validity checks) and font ids, pure JVM. */
class FontFileParserTest {

    @Test
    fun readsNamesFromARealFont() {
        val bytes = TestFonts.real("Arvo-Regular.ttf")
        val info = requireNotNull(FontFileParser.parse(bytes))
        assertEquals(FontFormat.TRUETYPE, info.format)
        assertTrue("full name ${info.fullName}", info.fullName!!.startsWith("Arvo"))
        assertEquals("Arvo", info.family)
        assertTrue(info.unitsPerEm in 16..16384)
        assertTrue("name, cmap, glyphs", info.tables.containsAll(listOf("name", "cmap", "head", "glyf")))
        assertTrue(info.displayName!!.startsWith("Arvo"))
        // Same result from a file (read piece by piece).
        val f = Files.createTempFile("arvo", ".ttf").toFile().apply { writeBytes(bytes); deleteOnExit() }
        assertEquals(info, FontFileParser.parse(f))
        // Another real one.
        val coming = requireNotNull(FontFileParser.parse(TestFonts.real("ComingSoon.ttf")))
        assertTrue(coming.displayName!!.contains("Coming Soon"))
    }

    @Test
    fun prefersEnglishWindowsNamesAndTypographicFamily() {
        val bytes = TestFonts.synthetic(
            listOf(
                mac(1, "Mac Family"),
                windows(1, "Deutsch Familie", language = 0x0407),
                windows(1, "Legacy Family"),
                windows(16, "Brushwork Test Sans"),
                windows(2, "Bold"),
                windows(4, "Brushwork Test Sans Bold"),
            )
        )
        val info = requireNotNull(FontFileParser.parse(bytes))
        assertEquals("Brushwork Test Sans Bold", info.fullName)
        assertEquals("typographic family wins", "Brushwork Test Sans", info.family)
        assertEquals("Bold", info.subfamily)
        assertEquals("Brushwork Test Sans Bold", info.displayName)
        assertEquals(1000, info.unitsPerEm)
        assertEquals(-0.1f, info.xMin, 1e-6f)
        assertEquals(1.2f, info.xMax, 1e-6f)
    }

    @Test
    fun fallsBackToFamilyAndStyleAndMacNames() {
        // No full name: family + style, "Regular" left out.
        val regular = FontFileParser.parse(TestFonts.synthetic(listOf(windows(1, "Comic Pop"), windows(2, "Regular"))))!!
        assertNull(regular.fullName)
        assertEquals("Comic Pop", regular.displayName)
        val italic = FontFileParser.parse(TestFonts.synthetic(listOf(windows(1, "Comic Pop"), windows(2, "Italic"))))!!
        assertEquals("Comic Pop Italic", italic.displayName)
        // Only old Mac Roman names.
        val mac = FontFileParser.parse(TestFonts.synthetic(listOf(mac(1, "Café Script"), mac(4, "Café Script für"))))!!
        assertEquals("Café Script", mac.family)
        assertEquals("Café Script für", mac.displayName)
        // No names at all: the caller uses the file name.
        val none = FontFileParser.parse(TestFonts.synthetic(emptyList()))!!
        assertNull(none.displayName)
        assertEquals("Some Font Bold", FontIds.nameFromFile("fonts/Some_Font-Bold.ttf"))
        // Control characters and junk in names are cleaned.
        val junk = FontFileParser.parse(TestFonts.synthetic(listOf(windows(4, "  Bad\u0000Name\n  2 "))))!!
        assertEquals("Bad Name 2", junk.displayName)
    }

    @Test
    fun collectionsAndCffFontsAreRecognized() {
        val ttc = requireNotNull(FontFileParser.parse(TestFonts.collection(listOf(windows(4, "Collected One")))))
        assertEquals(FontFormat.COLLECTION, ttc.format)
        assertEquals("Collected One", ttc.displayName)
        // 'OTTO' with CFF outlines.
        val otf = TestFonts.synthetic(listOf(windows(4, "Cff Font")), sfntVersion = 0x4F54544F, omit = setOf("glyf", "loca"))
        assertNull("CFF font without a CFF table has no glyphs", FontFileParser.parse(otf))
        assertEquals(FontFormat.OPENTYPE_CFF, FontFileParser.sniff(otf))
    }

    @Test
    fun damagedOrForeignDataIsRejected() {
        val good = TestFonts.synthetic(listOf(windows(4, "Good")))
        assertNotNull(FontFileParser.parse(good))
        assertNull(FontFileParser.parse(ByteArray(0)))
        assertNull(FontFileParser.parse(ByteArray(3000) { (it * 31).toByte() }))
        assertNull("truncated table directory", FontFileParser.parse(good.copyOf(20)))
        assertNull("tables beyond the end", FontFileParser.parse(good.copyOf(good.size - 40)))
        assertNull("no character map", FontFileParser.parse(TestFonts.synthetic(listOf(windows(4, "x")), omit = setOf("cmap"))))
        assertNull("no head", FontFileParser.parse(TestFonts.synthetic(listOf(windows(4, "x")), omit = setOf("head"))))
        assertNull("bad head magic", FontFileParser.parse(TestFonts.synthetic(listOf(windows(4, "x")), headMagic = 0x12345678)))
        assertNull("no glyphs", FontFileParser.parse(TestFonts.synthetic(listOf(windows(4, "x")), omit = setOf("glyf", "loca"))))
        // Web fonts, zips, text: not fonts Android loads.
        assertNull(FontFileParser.sniff("wOFF".toByteArray()))
        assertNull(FontFileParser.sniff("PK\u0003\u0004".toByteArray()))
        assertNull(FontFileParser.sniff("Read".toByteArray()))
        assertNull(FontFileParser.parse(File("does/not/exist.ttf")))
    }

    @Test
    fun idsAndKeys() {
        val id = FontIds.idOf(ByteArray(32) { it.toByte() })
        assertEquals("000102030405060708090a0b0c0d0e0f", id)
        assertTrue(FontIds.isValid(id))
        assertFalse(FontIds.isValid("../../etc/passwd"))
        assertFalse(FontIds.isValid("ABCDEF0123456789"))
        assertFalse(FontIds.isValid("abc"))
        assertFalse(FontIds.isValid(null))
        assertEquals(TextFont.SERIF, FontIds.builtInOf(FontIds.keyOf(TextFont.SERIF)))
        assertNull(FontIds.builtInOf(FontIds.keyOf(id)))
        assertEquals(id, FontIds.importedOf(FontIds.keyOf(id)))
        assertNull(FontIds.importedOf("file:../x"))
        assertNull(FontIds.importedOf(FontIds.keyOf(TextFont.SANS)))
        assertEquals("Name (2)", FontImporter.uniqueName("Name", setOf("name")))
        assertEquals("Name (3)", FontImporter.uniqueName("Name", setOf("name", "name (2)")))
    }

    @Test
    fun indexDataIsRepaired() {
        val a = ImportedFont("0123456789abcdef0123456789abcdef", "Zeta", file = "x.ttf")
        val b = ImportedFont("fedcba9876543210fedcba9876543210", "alpha", file = "y.ttf")
        val bad = ImportedFont("../../evil", "Evil", file = "e.ttf")
        val data = FontIndexData(
            fonts = listOf(a, b, bad, a),
            favorites = listOf(FontIds.keyOf(a.id), "file:0000000000000000", FontIds.keyOf(TextFont.CASUAL), "builtin:NOPE"),
            recent = List(20) { FontIds.keyOf(if (it % 2 == 0) TextFont.SERIF else TextFont.MONOSPACE) } + FontIds.keyOf(b.id),
        ).sanitized()
        assertEquals(listOf("alpha", "Zeta"), data.fonts.map { it.name })
        assertEquals(listOf(FontIds.keyOf(a.id), FontIds.keyOf(TextFont.CASUAL)), data.favorites)
        assertEquals(listOf(FontIds.keyOf(TextFont.SERIF), FontIds.keyOf(TextFont.MONOSPACE), FontIds.keyOf(b.id)), data.recent)
        assertEquals(data, FontIndexData.decode(FontIndexData.encode(data)))
        assertEquals(FontIndexData(), FontIndexData.decode("garbage {"))
        val used = data.withRecent(FontIds.keyOf(a.id))
        assertEquals(FontIds.keyOf(a.id), used.recent.first())
        val gone = used.without(a.id)
        assertTrue(gone.fonts.none { it.id == a.id } && FontIds.keyOf(a.id) !in gone.favorites && FontIds.keyOf(a.id) !in gone.recent)
    }
}
