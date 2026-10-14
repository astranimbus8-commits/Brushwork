package com.brushwork.paint.v19.a8

import android.content.Context
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.storage.backup.BackupRestore
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupSettings
import com.brushwork.paint.ui.color.Palette
import com.brushwork.paint.ui.color.PaletteData
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.v19.a8.BackupKit.app
import com.brushwork.paint.v19.a8.BackupKit.choose
import com.brushwork.paint.v19.a8.BackupKit.newArtwork
import com.brushwork.paint.v19.a8.BackupKit.paint
import com.brushwork.paint.v19.a8.BackupKit.repo
import com.brushwork.paint.v19.a8.BackupKit.source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A8 (R16 (a), (b)): restoring twice adds nothing. Artworks the gallery has at the backup's
 * revision are "Already in the gallery" and unchecked; deleted ones are listed after the others,
 * unchecked. "Also restore settings, brushes and palettes" writes the settings back and merges
 * brushes and palettes by id: the missing ones are added, none is replaced.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreRepeatTest {
    @Before
    fun setUp() {
        BackupKit.freshInstall()
    }

    private fun checkedIds() = runBlocking { BackupService.listRestore(app, source()) }.filter { it.checkedByDefault }.map { it.id }

    @Test
    fun aSecondRestoreAddsNothing() {
        val a = newArtwork("Alpha")
        val b = newArtwork("Beta")
        choose()
        BackupKit.reinstall()

        val first = runBlocking { BackupService.listRestore(app, source()) }
        assertEquals(setOf(a, b), first.filter { it.checkedByDefault }.map { it.id }.toSet())
        assertEquals(2, runBlocking { BackupService.restore(app, source(), checkedIds(), alsoSettings = false) })

        val again = runBlocking { BackupService.listRestore(app, source()) }
        assertTrue("already in the gallery: $again", again.all { it.already && !it.checkedByDefault })
        assertEquals(0, runBlocking { BackupService.restore(app, source(), checkedIds(), alsoSettings = false) })
        assertEquals(2, runBlocking { repo.list() }.size)
    }

    @Test
    fun aChangedArtworkIsNotAlreadyThere() {
        val a = newArtwork("Alpha")
        choose()
        BackupKit.reinstall()
        runBlocking { BackupService.restore(app, source(), listOf(a), alsoSettings = false) }
        paint(a, BackupKit.RED)
        val item = runBlocking { BackupService.listRestore(app, source()) }.single()
        assertFalse("another revision here", item.already)
    }

    @Test
    fun deletedArtworksComeLastUnchecked() {
        val a = newArtwork("Alpha")
        val b = newArtwork("Beta")
        choose()
        runBlocking { repo.delete(b) }
        BackupKit.idle()
        BackupKit.reinstall()
        val items = runBlocking { BackupService.listRestore(app, source()) }
        assertEquals(listOf(a, b), items.map { it.id })
        assertEquals(listOf(false, true), items.map { it.deleted })
        assertEquals(listOf(a), items.filter { it.checkedByDefault }.map { it.id })
        assertEquals("a deleted artwork can be restored", 1, runBlocking { BackupService.restore(app, source(), listOf(b), alsoSettings = false) })
        assertEquals("Beta", runBlocking { repo.list() }.single().name)
    }

    @Test
    fun brushesAndPalettesMergeById() {
        // The first install: two palettes, an edited pen and marker, a setting.
        val mine = PaletteData(palettes = listOf(Palette("u-1", "One", listOf(1)), Palette("u-2", "Two", listOf(2))))
        prefs(PaletteStore.PREFS_NAME).edit().putString(PaletteStore.KEY, PaletteData.encode(mine)).commit()
        prefs(BrushPresetStore.PREFS_NAME).edit().putString("preset.pen", "pen-1").putString("preset.marker", "marker-1").commit()
        prefs(BackupSettings.SETTINGS_PREFS).edit().putBoolean("v19a8.flag", true).putFloat("v19a8.size", 2.5f).commit()
        newArtwork("Alpha")
        choose()
        BackupKit.reinstall()

        // The new install already has its own "u-1" and pen.
        val here = PaletteData(palettes = listOf(Palette("u-1", "One here", listOf(9)), Palette("u-3", "Three", listOf(3))))
        prefs(PaletteStore.PREFS_NAME).edit().putString(PaletteStore.KEY, PaletteData.encode(here)).commit()
        prefs(BrushPresetStore.PREFS_NAME).edit().putString("preset.pen", "pen-2").commit()

        val src = source()
        assertTrue(runBlocking { BackupRestore.restoreSettings(app, src.tree, src.root!!) })
        val merged = PaletteData.decode(prefs(PaletteStore.PREFS_NAME).getString(PaletteStore.KEY, null))
        assertEquals(listOf("u-1" to "One here", "u-3" to "Three", "u-2" to "Two"), merged.palettes.map { it.id to it.name })
        assertEquals(listOf(9), merged.palettes.first().colors)
        assertEquals("pen-2", prefs(BrushPresetStore.PREFS_NAME).getString("preset.pen", null))
        assertEquals("marker-1", prefs(BrushPresetStore.PREFS_NAME).getString("preset.marker", null))
        assertTrue(prefs(BackupSettings.SETTINGS_PREFS).getBoolean("v19a8.flag", false))
        assertEquals(2.5f, prefs(BackupSettings.SETTINGS_PREFS).getFloat("v19a8.size", 0f), 0f)

        // Twice: nothing more.
        runBlocking { BackupRestore.restoreSettings(app, src.tree, src.root!!) }
        assertEquals(merged, PaletteData.decode(prefs(PaletteStore.PREFS_NAME).getString(PaletteStore.KEY, null)))
    }

    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)
}
