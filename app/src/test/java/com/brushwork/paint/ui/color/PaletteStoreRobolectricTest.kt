package com.brushwork.paint.ui.color

import android.content.Context
import com.brushwork.paint.core.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Persistence through real SharedPreferences, and the picker's Compose state holder. */
@RunWith(RobolectricTestRunner::class)
class PaletteStoreRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PaletteStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun persistsAcrossInstances() {
        val red = ColorUtils.rgb(255, 0, 0)
        val a = PaletteStore(context)
        assertEquals(PaletteData(), a.data)
        a.createPalette("Sky")
        val sky = a.active
        a.addColor(sky.id, red)
        a.addColor(sky.id, -1)
        a.moveColor(sky.id, 1, -1)
        a.addRecent(red)
        a.setPickerMode(PickerMode.RGB)
        a.addColor("builtin:basic", red) // ignored: read-only

        val b = PaletteStore(context)
        assertEquals(a.data, b.data)
        assertEquals("Sky", b.active.name)
        assertEquals(listOf(-1, red), b.active.colors)
        assertEquals(listOf(red), b.data.recent)
        assertEquals(PickerMode.RGB.ordinal, b.data.pickerMode)
        assertEquals(BuiltInPalettes.byId("builtin:basic"), b.data.palette("builtin:basic"))

        b.deletePalette(sky.id)
        b.clearRecent()
        val c = PaletteStore(context)
        assertEquals(listOf(PaletteData.MY_PALETTE_ID), c.data.palettes.map { it.id })
        assertEquals(PaletteData.MY_PALETTE_ID, c.data.activeId)
        assertTrue(c.data.recent.isEmpty())
    }

    @Test
    fun corruptPreferencesFallBackToDefaults() {
        context.getSharedPreferences(PaletteStore.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PaletteStore.KEY, "{broken").commit()
        assertEquals(PaletteData(), PaletteStore(context).data)
    }

    @Test
    fun sharedInstance() {
        assertSame(PaletteStore.get(context), PaletteStore.get(context.applicationContext))
    }

    @Test
    fun editStateKeepsHueThroughBlackAndGray() {
        val changes = mutableListOf<Int>()
        val s = ColorEditState(ColorUtils.rgb(255, 0, 0), Hsb(0f, 1f, 1f)) { changes += it }
        s.setHsb(h = 200f, s = 0.8f, b = 0.9f)
        assertEquals(ColorUtils.hsvToColor(200f, 0.8f, 0.9f), s.color)
        // Brightness to zero: black, but the hue and saturation stay where they were.
        s.setHsb(b = 0f)
        assertEquals(0xFF000000.toInt(), s.color)
        assertEquals(Hsb(200f, 0.8f, 0f), s.hsb)
        // An external black keeps them too...
        s.setColor(0xFF000000.toInt(), notify = false)
        assertEquals(Hsb(200f, 0.8f, 0f), s.hsb)
        // ...and a gray keeps the hue.
        s.setRgb(100, 100, 100)
        assertEquals(200f, s.hsb.h, 0f)
        assertEquals(0f, s.hsb.s, 0f)
        assertEquals(ColorUtils.rgb(100, 100, 100), s.color)
        // Alpha edits keep RGB; HSB edits keep alpha.
        s.setAlpha(0x40)
        assertEquals(0x40646464, s.color)
        s.setHsb(s = 1f, b = 1f)
        assertEquals(0x40, s.alpha)
        assertEquals(ColorUtils.hsvToColor(200f, 1f, 1f) and 0xFFFFFF, s.color and 0xFFFFFF)
        // Only user edits notify (the notify=false sync did not).
        assertEquals(5, changes.size)
        assertEquals(s.color, changes.last())
    }

    @Test
    fun singleChannelSettersKeepTheOtherChannels() {
        val s = ColorEditState(0x80102030.toInt(), Hsb.fromColor(0x80102030.toInt())) {}
        s.setRed(200)
        s.setGreen(100)
        s.setBlue(300)
        assertEquals(ColorUtils.argb(0x80, 200, 100, 255), s.color)
        s.setRed(-4)
        assertEquals(ColorUtils.argb(0x80, 0, 100, 255), s.color)
    }

    @Test
    fun editStateClampsInput() {
        val s = ColorEditState(0xFF000000.toInt(), Hsb(0f, 0f, 0f)) {}
        s.setHsb(h = 400f, s = 2f, b = -1f)
        assertEquals(Hsb(360f, 1f, 0f), s.hsb)
        s.setAlpha(999)
        assertEquals(255, s.alpha)
    }

    @Test
    fun pickerMemoryRestoresHueForSameColor() {
        val s = ColorEditState(ColorUtils.rgb(10, 20, 30), Hsb.fromColor(ColorUtils.rgb(10, 20, 30))) {}
        s.setHsb(h = 123f, s = 0.5f, b = 0f)
        assertEquals(Hsb(123f, 0.5f, 0f), PickerMemory.hsbFor(0xFF000000.toInt()))
        // A different gray still inherits the remembered hue.
        assertEquals(123f, PickerMemory.hsbFor(ColorUtils.rgb(50, 50, 50)).h, 0f)
    }
}
