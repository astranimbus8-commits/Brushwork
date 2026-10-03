package com.brushwork.paint.ui.editor.chrome

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 §3.1a (A ↔ E integration): "Fast adjustment preview" is one setting with two switches, the
 * Masks tool's Components sheet and Settings. On the real editor at the user's size: the sheet is
 * open, Settings (More › Settings) turns the setting off over it, and when Settings closes the
 * sheet's switch says "off" too (it doesn't keep showing "on" while drags no longer preview);
 * one tap on it then turns the setting on again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.fastbothsandbox"])
class FastPreviewBothPlacesUiTest {

    /** The state of the placed "Fast adjustment preview" switch (the topmost one shown). */
    private fun state(): ToggleableState? {
        var n: SemanticsNode? = SmokeUi.find(FAST_ADJUST_PREVIEW_LABEL, exact = true)?.node
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.ToggleableState)?.let { return it }
            n = n.parent
        }
        return null
    }

    @Test
    fun theMasksSheetFollowsSettings() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("Masks sheet, then Settings over it") {
            val s = h.editor()
            val c = s.c
            assertTrue("default on", c.settings.fastAdjustPreview)
            c.selectTool(ToolId.MASK)
            settle()
            click("+ Radial", exact = true)
            c.pointerDown(ToolPoint(200f, 150f)); c.pointerMove(ToolPoint(230f, 150f)); c.pointerMove(ToolPoint(260f, 150f)); c.pointerUp(ToolPoint(260f, 150f))
            settle()
            assertTrue(c.activeLayer.isAdjustmentLayer)
            click("Components (")
            assertEquals("the sheet's switch: on", ToggleableState.On, state())
            val sheet = SmokeUi.sheetTitles().lastOrNull()

            click("More options")
            click("Settings", exact = true)
            assertTrue("the Settings dialog", has("Editor settings", exact = true))
            assertEquals("Settings shows on", ToggleableState.On, state())
            click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
            assertFalse("saved off", c.settings.fastAdjustPreview)
            assertEquals(ToggleableState.Off, state())
            click("Close", exact = true)
            assertFalse("Settings closed", has("Editor settings", exact = true))

            // Back to the Masks sheet (brought back from its pill if Settings minimized it).
            if (!has(FAST_ADJUST_PREVIEW_LABEL, exact = true)) {
                val pills = SmokeUi.pillTitles()
                assertTrue("the sheet or its pill: sheets ${SmokeUi.sheetTitles()} pills $pills (was $sheet)", pills.isNotEmpty())
                click(pills.last(), exact = true)
            }
            assertTrue("the sheet's switch is shown: ${SmokeUi.shown().take(60)}", has(FAST_ADJUST_PREVIEW_LABEL, exact = true))
            assertEquals("the sheet's switch follows Settings", ToggleableState.Off, state())
            click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
            assertTrue("one tap turns it on again", c.settings.fastAdjustPreview)
            assertTrue(c.liveAdjust.fastPreview)
            assertEquals(ToggleableState.On, state())
            Smoke.assertQuiet(c, "fast preview in both places")
        }
        dog.interrupt()
        h.finish()
    }
}
