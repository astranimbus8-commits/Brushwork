package com.brushwork.paint.ui.editor.chrome

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 §3.4 / §3.7.6 (areas E and G): the increments are set from two places of the editor's
 * chrome, and both work. More › "Increments…" opens area G's Increments sheet, whose "Use
 * increments" switch turns them on (the menu entry then shows it checked). Settings (More ›
 * Settings) has the same "Increments" section: the switch, and a step typed there is the step.
 * Changing a setting records no edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.incrementsentrysandbox"])
class IncrementsEntryPointsUiTest {

    /** Back on the topmost window (a popup menu, a dialog). */
    private fun back() {
        SmokeUi.windows().last().let { w ->
            w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        settle()
    }

    @Test
    fun moreIncrementsOpensTheSheetAndSettingsHasTheSection() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("More › Increments… opens the Increments sheet") {
            val s = h.editor()
            val steps = s.c.undoManager.undoCount
            assertFalse("off by default (I8)", s.c.increments.state.enabled)
            click("More options")
            val entry = Clickables.onScreen(s).single { "Increments…" in it.labels }
            assertFalse("unchecked while off: ${entry.labels}", "On" in entry.labels)
            click("Increments…", exact = true)
            val title = SmokeUi.assertIncrementsPanelShown()
            assertEquals("area G's sheet", "Increment steps", title)
            assertEquals("the menu closed", 1, SmokeUi.windows().size)
            click("Use increments", exact = true)
            assertTrue("switched on", s.c.increments.state.enabled)
            click("Close", exact = true)
            assertFalse("closed", title in SmokeUi.sheetTitles())
            click("More options")
            val checked = Clickables.onScreen(s).single { "Increments…" in it.labels }
            assertTrue("checked while on: ${checked.labels}", "On" in checked.labels)
            back()
            assertEquals(1, SmokeUi.windows().size)
            assertEquals("a setting, not an edit", steps, s.c.undoManager.undoCount)
            Smoke.assertQuiet(s.c, "More › Increments…")
        }
        h.section("Settings › Increments") {
            val s = h.editor()
            val steps = s.c.undoManager.undoCount
            click("More options")
            click("Settings", exact = true)
            assertTrue("the Settings dialog", has("Editor settings", exact = true))
            assertTrue("its Increments section (headers in capitals)", has("INCREMENTS", exact = true))
            val on = s.c.increments.state.enabled
            click("Use increments", exact = true)
            assertEquals("the switch", !on, s.c.increments.state.enabled)
            click("Use increments", exact = true)
            assertEquals("and back", on, s.c.increments.state.enabled)
            assertEquals(10f, s.c.increments.state.lengthPx, 0f)
            SmokeUi.typeAndDone("Length step", "25")
            assertEquals("the Length step typed in Settings", 25f, s.c.increments.state.lengthPx, 0f)
            click("Close", exact = true)
            assertFalse(has("Editor settings", exact = true))
            assertEquals("settings, not edits", steps, s.c.undoManager.undoCount)
            Smoke.assertQuiet(s.c, "Settings › Increments")
        }
        dog.interrupt()
        h.finish()
    }
}
