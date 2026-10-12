package com.brushwork.paint

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 F1a (design 2.3.4): [UiPrefs] is a file of its own (`brushwork_ui`), outside
 * AppSettings' journal: a history tap over the UI (`rollbackJournal`) takes back a setting
 * written while the mark is open but never which strip rows are open or the object bar's More.
 */
@RunWith(RobolectricTestRunner::class)
class UiPrefsJournalTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aRollbackTakesBackTheSettingsAndLeavesTheUiPrefs() {
        val settings = AppSettings(app)
        val ui = UiPrefs(app)
        assertEquals("no row open at first", emptySet<String>(), ui.stripOpenRows)
        assertFalse(ui.objectBarMoreOpen)

        settings.openJournal()
        val at = settings.journalPosition()
        settings.drawPreview = AppSettings.DRAW_PREVIEW_EXACT
        settings.textNewLayerEachTime = true
        ui.stripOpenRows = setOf("text.more")
        ui.objectBarMoreOpen = true
        assertEquals("only the AppSettings writes are recorded", at + 2, settings.journalPosition())
        assertTrue(settings.rollbackJournal(at))
        settings.closeJournal()

        assertEquals("the settings are taken back", AppSettings.DRAW_PREVIEW_FASTER, settings.drawPreview)
        assertFalse(settings.textNewLayerEachTime)
        assertEquals("the UI prefs stay", setOf("text.more"), ui.stripOpenRows)
        assertTrue(ui.objectBarMoreOpen)
        assertEquals("and are stored", setOf("text.more"), UiPrefs(app).stripOpenRows)
        assertTrue(UiPrefs(app).objectBarMoreOpen)

        val raw = app.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE)
        assertFalse("not in AppSettings' file", raw.contains(UiPrefs.STRIP_OPEN_ROWS) || raw.contains(UiPrefs.OBJECT_BAR_MORE_OPEN))
        assertTrue(app.getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE).contains(UiPrefs.STRIP_OPEN_ROWS))
    }

    @Test
    fun theOpenRowsAreCopiedInAndOut() {
        val ui = UiPrefs(app)
        val rows = hashSetOf("text.more", "shape.more")
        ui.stripOpenRows = rows
        rows += "eraser.more"
        assertEquals("a later change to the set written is not stored", setOf("text.more", "shape.more"), ui.stripOpenRows)
        val read = ui.stripOpenRows
        (read as? MutableSet<String>)?.add("curve.more")
        assertEquals("a change to the set read is not stored", setOf("text.more", "shape.more"), ui.stripOpenRows)
        ui.stripOpenRows = emptySet()
        assertEquals(emptySet<String>(), ui.stripOpenRows)
        ui.objectBarMoreOpen = true
        ui.objectBarMoreOpen = false
        assertFalse(UiPrefs(app).objectBarMoreOpen)
    }
}
