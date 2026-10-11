package com.brushwork.paint.ui.editor.chrome

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
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

    /**
     * Scrolls the open More menu (its scroll action, as a finger pushing it up would) until the
     * whole entry [label] shows: v1.8's section headers push the last entries under the fold.
     */
    private fun scrollMenuTo(s: ChromeScreen, label: String) {
        fun entry(): SemanticsNode? {
            var n = SmokeUi.find(label, exact = true)?.node
            while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            return n
        }
        fun whole() = entry()?.let { n -> n.boundsInWindow.height >= n.size.height - 1f } == true
        repeat(30) {
            if (whole()) return
            val n = entry() ?: throw AssertionError("no \"$label\" in the menu")
            var scroller: SemanticsNode? = n
            while (scroller != null && scroller.config.getOrNull(SemanticsActions.ScrollBy) == null) scroller = scroller.parent
            requireNotNull(scroller?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is clipped and the menu does not scroll" }
                .invoke(0f, 60f * s.density)
            settle()
        }
        throw AssertionError("\"$label\" never scrolled into the menu")
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
            scrollMenuTo(s, "Increments…")
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
            scrollMenuTo(s, "Increments…")
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
