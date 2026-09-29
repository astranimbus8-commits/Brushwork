package com.brushwork.paint.ui.color

import com.brushwork.paint.core.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteDataTest {

    private val red = ColorUtils.rgb(255, 0, 0)
    private val green = ColorUtils.rgb(0, 255, 0)
    private val blue = ColorUtils.rgb(0, 0, 255)

    private fun userId(d: PaletteData) = d.palettes.first().id

    @Test
    fun defaults() {
        val d = PaletteData()
        assertEquals(PaletteData.MY_PALETTE_ID, d.activeId)
        assertEquals("My palette", d.active.name)
        assertTrue(d.isEditable(d.activeId))
        assertTrue(d.recent.isEmpty())
        assertEquals(listOf("My palette", "Basic", "Skin tones", "Pastel", "Grayscale", "Earth", "Neon"), d.all.map { it.name })
        assertTrue(BuiltInPalettes.all.all { it.builtIn && it.colors.isNotEmpty() })
        assertEquals(16, BuiltInPalettes.byId("gray")?.colors?.size)
    }

    @Test
    fun serializationRoundTrip() {
        val d = PaletteData()
            .withNewPalette("Sky", "p1", listOf(blue, 0x80FFFFFF.toInt()))
            .withColorAdded("p1", green)
            .withRecent(red).withRecent(blue)
            .withPickerMode(PickerMode.HSB.ordinal)
            .withActive("builtin:pastel")
        val text = PaletteData.encode(d)
        val back = PaletteData.decode(text)
        assertEquals(d, back)
        assertEquals(listOf(blue, 0x80FFFFFF.toInt(), green), back.palette("p1")!!.colors)
        assertEquals("builtin:pastel", back.activeId)
        // Built-ins are never written out.
        assertFalse(text.contains("Skin tones"))
        assertFalse(text.contains("builtIn"))
    }

    @Test
    fun decodeFallsBackOnGarbage() {
        assertEquals(PaletteData(), PaletteData.decode(null))
        assertEquals(PaletteData(), PaletteData.decode(""))
        assertEquals(PaletteData(), PaletteData.decode("not json"))
        assertEquals(PaletteData(), PaletteData.decode("{\"palettes\": 5}"))
        // Unknown keys are ignored; missing ones get defaults.
        assertEquals(PaletteData(), PaletteData.decode("{\"future\": true}"))
    }

    @Test
    fun decodeRepairsInconsistentData() {
        val json = """
            {"palettes":[
                {"id":"builtin:basic","name":"Hijack","colors":[1]},
                {"id":"a","name":"   A   pal  ","colors":[1,2]},
                {"id":"a","name":"Dup","colors":[3]},
                {"id":"","name":"Blank","colors":[]}
             ],
             "activeId":"missing",
             "recent":[1,2,1,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19],
             "pickerMode":99}
        """.trimIndent()
        val d = PaletteData.decode(json)
        assertEquals(listOf("a"), d.palettes.map { it.id })
        assertEquals("A pal", d.palettes[0].name)
        assertEquals("a", d.activeId)
        assertEquals(PaletteData.MAX_RECENT, d.recent.size)
        assertEquals(d.recent.distinct(), d.recent)
        assertEquals(listOf(1, 2, 3), d.recent.take(3))
        assertEquals(PickerMode.entries.lastIndex, d.pickerMode)
        // Built-in palette with the hijacked id is still the real one.
        assertEquals("Basic", d.palette("builtin:basic")!!.name)

        val empty = PaletteData.decode("""{"palettes":[],"activeId":"x"}""")
        assertEquals(listOf(PaletteData.MY_PALETTE_ID), empty.palettes.map { it.id })
        assertEquals(PaletteData.MY_PALETTE_ID, empty.activeId)
    }

    @Test
    fun recentColorsAreDistinctNewestFirstAndCapped() {
        var d = PaletteData()
        d = d.withRecent(red).withRecent(green).withRecent(blue)
        assertEquals(listOf(blue, green, red), d.recent)
        // Reusing a color moves it to the front without duplicating it.
        d = d.withRecent(red)
        assertEquals(listOf(red, blue, green), d.recent)
        // Using the newest color again changes nothing.
        assertSame(d, d.withRecent(red))
        // Cap: the oldest drop off.
        for (i in 1..20) d = d.withRecent(ColorUtils.gray(i))
        assertEquals(PaletteData.MAX_RECENT, d.recent.size)
        assertEquals(ColorUtils.gray(20), d.recent.first())
        assertEquals(ColorUtils.gray(5), d.recent.last())
        assertFalse(d.recent.contains(red))
        assertTrue(d.withRecentCleared().recent.isEmpty())
    }

    @Test
    fun colorEditsOnUserPalette() {
        val base = PaletteData().withNewPalette("Test", "t")
        var d = base.withColorAdded("t", red).withColorAdded("t", green).withColorAdded("t", blue)
        assertEquals(listOf(red, green, blue), d.palette("t")!!.colors)
        d = d.withColorMoved("t", 0, 1)
        assertEquals(listOf(green, red, blue), d.palette("t")!!.colors)
        assertSame(d, d.withColorMoved("t", 0, -1))
        assertSame(d, d.withColorMoved("t", 2, 1))
        d = d.withColorReplaced("t", 2, red)
        assertEquals(listOf(green, red, red), d.palette("t")!!.colors)
        d = d.withColorRemoved("t", 0)
        assertEquals(listOf(red, red), d.palette("t")!!.colors)
        assertSame(d, d.withColorRemoved("t", 5))
        assertSame(d, d.withColorReplaced("t", -1, blue))
    }

    @Test
    fun paletteSizeIsCapped() {
        var d = PaletteData().withNewPalette("Big", "b")
        repeat(PaletteData.MAX_COLORS + 10) { d = d.withColorAdded("b", it) }
        assertEquals(PaletteData.MAX_COLORS, d.palette("b")!!.colors.size)
    }

    @Test
    fun builtInPalettesAreReadOnly() {
        val d = PaletteData()
        val id = "builtin:basic"
        assertFalse(d.isEditable(id))
        assertSame(d, d.withColorAdded(id, red))
        assertSame(d, d.withColorRemoved(id, 0))
        assertSame(d, d.withColorReplaced(id, 0, red))
        assertSame(d, d.withColorMoved(id, 0, 1))
        assertSame(d, d.withRenamed(id, "Mine"))
        assertSame(d, d.withDeleted(id))
        // ...but can be duplicated into an editable copy.
        val copy = d.withDuplicate(id, "c")
        assertEquals("c", copy.activeId)
        assertEquals("Basic copy", copy.active.name)
        assertEquals(BuiltInPalettes.byId(id)!!.colors, copy.active.colors)
        assertTrue(copy.isEditable("c"))
    }

    @Test
    fun createRenameDeletePalettes() {
        var d = PaletteData().withNewPalette("  Sketch  ", "s1")
        assertEquals("s1", d.activeId)
        assertEquals("Sketch", d.active.name)
        d = d.withNewPalette("Sketch", "s2")
        assertEquals("Sketch 2", d.palette("s2")!!.name)
        d = d.withNewPalette("   ", "s3")
        assertEquals("Palette", d.palette("s3")!!.name)

        assertSame(d, d.withRenamed("s1", "   "))
        d = d.withRenamed("s1", "Ink")
        assertEquals("Ink", d.palette("s1")!!.name)
        d = d.withRenamed("s2", "Ink")
        assertEquals("Ink 2", d.palette("s2")!!.name)
        assertEquals(PaletteData.MAX_NAME_LENGTH, d.withRenamed("s1", "x".repeat(100)).palette("s1")!!.name.length)

        // Deleting the active palette activates its left neighbor.
        d = d.withActive("s2").withDeleted("s2")
        assertEquals(listOf(PaletteData.MY_PALETTE_ID, "s1", "s3"), d.palettes.map { it.id })
        assertEquals("s1", d.activeId)
        // Deleting a non-active palette keeps the active one.
        d = d.withDeleted("s3")
        assertEquals("s1", d.activeId)
        d = d.withDeleted(userId(d))
        assertEquals(listOf("s1"), d.palettes.map { it.id })
        // The last user palette stays.
        assertSame(d, d.withDeleted("s1"))
    }

    @Test
    fun activeSelection() {
        val d = PaletteData()
        assertSame(d, d.withActive("nope"))
        val e = d.withActive("builtin:neon")
        assertEquals("Neon", e.active.name)
        assertFalse(e.isEditable(e.activeId))
        assertNotNull(e.palette(PaletteData.MY_PALETTE_ID))
    }
}
