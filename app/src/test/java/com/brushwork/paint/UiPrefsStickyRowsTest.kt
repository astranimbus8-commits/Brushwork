package com.brushwork.paint

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.tools.strip.StripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 F3 (R3 (a), fact V27; design 2.8's `UiPrefsJournalTest` cases, here because F3 changes
 * no existing test outside the R3 rewrites): what writes [UiPrefs] for the sticky rows.
 * - A history tap ([EditorController.restoreUiMark], what a two- or three-finger tap runs over
 *   the UI) takes back the settings written meanwhile, never "strip.openRows": a row the toggle
 *   opened or closed during the mark stays so, and a relaunch's [StripState] reads it.
 * - Only the explicit toggles write, each its own key: [StripState.closeAll], [StripState.drop],
 *   a tool entry, the chrome covers, the object bar coming and going and the one-row-at-a-time
 *   rule never write "strip.openRows"; opening and closing the object bar's row writes only
 *   "objectBar.moreOpen".
 */
@RunWith(RobolectricTestRunner::class)
class UiPrefsStickyRowsTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    /** [UiPrefs]' file, recording the keys of each write (an editor's apply or commit). */
    private class Recording(private val real: SharedPreferences) : SharedPreferences by real {
        val writes = mutableListOf<String>()

        override fun edit(): SharedPreferences.Editor = Editor(real.edit())

        private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor by e {
            private val keys = mutableListOf<String>()

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                keys += key.orEmpty()
                e.putStringSet(key, values)
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                keys += key.orEmpty()
                e.putBoolean(key, value)
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                keys += key.orEmpty()
                e.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                keys += "(clear)"
                e.clear()
                return this
            }

            override fun apply() {
                writes += keys
                keys.clear()
                e.apply()
            }

            override fun commit(): Boolean {
                writes += keys
                keys.clear()
                return e.commit()
            }
        }
    }

    /** The app's context with [UiPrefs.FILE] recorded. */
    private class Recorded(base: Context) : ContextWrapper(base) {
        val prefs = Recording(base.getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE))

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            if (name == UiPrefs.FILE) prefs else super.getSharedPreferences(name, mode)
    }

    @Test
    fun aHistoryTapNeverChangesTheOpenRows() {
        val c = Smoke.controller(app)
        val ui = UiPrefs(app)
        val state = StripState(ui)
        state.enter(ToolId.SHAPE)
        state.toggle(ToolId.SHAPE)
        assertEquals(setOf("SHAPE"), ui.stripOpenRows)

        // The mark of a history tap; meanwhile a setting and both toggles change.
        val mark = c.uiMark()
        c.settings.textNewLayerEachTime = true
        state.toggle(ToolId.SHAPE)
        state.enter(ToolId.CURVE)
        state.toggle(ToolId.CURVE)
        state.objectBarShown(true)
        state.toggleObjectRow()
        assertTrue(c.restoreUiMark(mark))
        c.releaseUiMark(mark)

        assertFalse("the setting is taken back", c.settings.textNewLayerEachTime)
        assertEquals("the rows stay as the toggles left them", setOf("CURVE"), ui.stripOpenRows)
        assertTrue(ui.objectBarMoreOpen)
        assertTrue("…in the state too", state.remembers(ToolId.CURVE) && !state.remembers(ToolId.SHAPE))

        // An undo (what the tap performs) leaves them too; a relaunch reads them.
        c.undo()
        assertEquals(setOf("CURVE"), UiPrefs(app).stripOpenRows)
        val relaunched = StripState(UiPrefs(app))
        assertTrue(relaunched.remembers(ToolId.CURVE) && !relaunched.remembers(ToolId.SHAPE))
        assertTrue(relaunched.objectRowRemembered)
    }

    @Test
    fun onlyTheTogglesWriteEachItsOwnKey() {
        val ctx = Recorded(app)
        val writes = ctx.prefs.writes
        // A name of a later version's tool is kept as it is.
        UiPrefs(ctx).stripOpenRows = setOf("SHAPE", "LATER_TOOL")
        writes.clear()
        val state = StripState(UiPrefs(ctx))
        assertTrue(state.remembers(ToolId.SHAPE))

        // Tool entries, closeAll, drop, the chrome covers: they hide, and write nothing.
        state.enter(ToolId.SHAPE)
        state.closeAll()
        state.enter(ToolId.CURVE)
        state.enter(ToolId.SHAPE)
        state.drop(ToolId.SHAPE)
        state.undrop(ToolId.SHAPE)
        state.hiddenByChrome = true
        state.hiddenByChrome = false
        assertTrue(state.isOpen(ToolId.SHAPE))
        assertEquals("hiding never writes", emptyList<String>(), writes)

        // The object bar: its showing and going write nothing; its toggle writes only its own key,
        // and hides the tool's row (one row at a time) without writing "strip.openRows".
        state.objectBarShown(true)
        state.toggleObjectRow()
        assertTrue(state.objectRowOpen && !state.isOpen(ToolId.SHAPE))
        state.objectBarGone()
        state.objectBarShown(true)
        state.closeAll()
        state.enter(ToolId.SHAPE)
        assertEquals("the object row's opening writes only its key", listOf(UiPrefs.OBJECT_BAR_MORE_OPEN), writes)
        assertEquals(setOf("SHAPE", "LATER_TOOL"), UiPrefs(app).stripOpenRows)

        // The strip's toggle over the object row: only "strip.openRows" (the object row hides, remembered).
        writes.clear()
        state.toggle(ToolId.SHAPE)
        assertTrue(state.isOpen(ToolId.SHAPE) && !state.objectRowOpen && state.objectRowRemembered)
        assertEquals("the strip's toggle writes only its key", listOf(UiPrefs.STRIP_OPEN_ROWS), writes)
        assertTrue(UiPrefs(app).objectBarMoreOpen)

        // Its toggle closes the row and forgets it; the later version's name stays.
        writes.clear()
        state.toggle(ToolId.SHAPE)
        assertEquals(listOf(UiPrefs.STRIP_OPEN_ROWS), writes)
        assertEquals(setOf("LATER_TOOL"), UiPrefs(app).stripOpenRows)
    }
}
