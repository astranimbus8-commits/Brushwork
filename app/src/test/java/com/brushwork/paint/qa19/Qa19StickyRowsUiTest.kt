package com.brushwork.paint.qa19

import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.MoreWalk
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa19.Qa19StickyRows.TOOL
import com.brushwork.paint.qa19.Qa19StickyRows.artwork
import com.brushwork.paint.qa19.Qa19StickyRows.assertOpen
import com.brushwork.paint.qa19.Qa19StickyRows.closeByToggle
import com.brushwork.paint.qa19.Qa19StickyRows.editor
import com.brushwork.paint.qa19.Qa19StickyRows.expanded
import com.brushwork.paint.qa19.Qa19StickyRows.remembered
import com.brushwork.paint.qa19.Qa19StickyRows.row
import com.brushwork.paint.qa19.Qa19StickyRows.settle
import com.brushwork.paint.qa19.Qa19StickyRows.view
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 F3 (R3 (a), (e)): the tool's More row stays open, on the user's 392 dp phone, in the real
 * editor (the Shape tool's row, [Qa19StickyRows]):
 * - canvas touches through the open row: a stroke drawn under it and one that starts beside a
 *   short line land, a pinch with a finger on the row zooms (no stroke, no step), whichever
 *   finger lifts first, a two-finger tap undoes, also with a finger on the row; the row stays
 *   and nothing on it acts;
 * - a tool switch and back shows the tool's remembered row again; the layers panel and "hide
 *   interface" cover it and it comes back; the "More options" menu still closes on an outside
 *   tap and leaves the row open;
 * - a relaunch shows the remembered row from the start, and the first fit leaves room for it; the
 *   row opening or closing never moves the canvas, and "Fit to screen" leaves room for the open row.
 * [Qa19StickyRowsNarrowUiTest] runs the canvas touches at 360 dp and the font scales of I27.
 *
 * v1.9 Gate 2 (R3 with the v1.9 strip items, design 3.4): [Qa19StickyRows.newItems], one section
 * per item: the row stays open while the item is used (the group thickness row and its slider,
 * the bevel popup, the erase-mode cell and its chooser, the "Turn into pixels" dialog, "New layer
 * each time", Duplicate / Bring forward / Send backward, justify, kerning, lists, a text path's
 * nodes, the Chromatic aberration blend mode, "Drawing preview"), and shows again, open, once its
 * popup, menu, dialog, sheet or panel closes; only the strip's row and the object bar's extra row
 * stay open, the menus and popups still close.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.stickyrowssandbox"])
class Qa19StickyRowsUiTest {

    @Test
    fun theToolsRowStaysOpenUntilItsToggle() = Qa19Ui.uiTest { h ->
        h.section("canvas touches through the open row") {
            val s = editor(h)
            assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
            Qa19StickyRows.canvasTouches(s, "392 dp")
            closeByToggle(s)
        }

        h.section("a tool switch and the chrome") {
            val s = editor(h)
            Qa19StickyRows.open(s)
            val ui = Qa16Ui(s)

            // Another tool shows its own row (the brush has none); the Shape tool's comes back.
            ui.tool(ToolId.BRUSH.label)
            settle()
            assertEquals(ToolId.BRUSH, s.c.activeToolId)
            assertNull("the brush shows no More row", row(s))
            assertTrue("the Shape tool's row stays remembered", remembered(s))
            ui.tool(TOOL.label)
            assertOpen(s, "back on the Shape tool: its remembered row")

            // The layers panel covers the row without forgetting it; it comes back.
            Finger.tap(s, "Open layers", exact = false)
            settle()
            assertNotNull("the layers panel opens", Finger.layerWindow(s))
            assertNull("the row hides under the layers panel", row(s))
            assertTrue("…remembered", remembered(s))
            Finger.tap(s, "Close layers", exact = false)
            settle()
            assertNull(Finger.layerWindow(s))
            assertOpen(s, "the layers panel closed")

            // "Hide interface" likewise.
            Finger.tap(s, "Hide interface")
            settle()
            assertNull("the row hides with the interface", row(s))
            Finger.tap(s, "Show interface")
            settle()
            assertOpen(s, "the interface shown again")

            // The "More options" menu still closes on an outside tap; the row stays open.
            Finger.tap(s, "More options")
            settle()
            assertEquals("the More options menu drops", 2, SmokeUi.windows().size)
            Qa19StickyRows.tapOutsideTheTopWindow()
            assertEquals("an outside tap closes the More options menu", 1, SmokeUi.windows().size)
            assertOpen(s, "the More options menu closed")
            Smoke.assertQuiet(s.c, "a tool switch and the chrome")
            // The row stays remembered for the relaunch.
        }

        h.section("a relaunch, and fits") {
            val s = editor(h, relaunch = true)
            assertOpen(s, "a relaunch: the remembered row shows from the start")
            val r = row(s)!!
            assertTrue("the first fit leaves room for the remembered row: ${artwork(s)} under $r", artwork(s).top >= r.bottom - 0.5f)

            // The row closing or opening never moves the canvas.
            val fitted = view(s)
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            settle()
            assertFalse(expanded(s))
            assertEquals("closing the row never moves the canvas", fitted, view(s))

            // "Fit to screen" with the row closed: the artwork moves up into the room.
            fit(s)
            val closedFit = view(s)
            assertNotEquals("a fit without the row frames the artwork higher", fitted, closedFit)
            Qa19StickyRows.open(s)
            assertEquals("opening the row never moves the canvas", closedFit, view(s))
            assertTrue("…so the row lies over the artwork's top", artwork(s).top < row(s)!!.bottom)

            // "Fit to screen" with the row open leaves room for it again.
            fit(s)
            assertTrue("a fit with the row open: ${artwork(s)} under ${row(s)}", artwork(s).top >= row(s)!!.bottom - 0.5f)
            assertEquals("…the same framing as the first fit", fitted, view(s))
            closeByToggle(s)
            Smoke.assertQuiet(s.c, "a relaunch, and fits")
        }

        Qa19StickyRows.newItems(h, "392 dp", fontScale = 1f)
    }

    /** "Fit to screen" from the "More options" menu, scrolled to it by a finger ([MoreWalk.pick]: the menu closes). */
    private fun fit(s: ChromeScreen) {
        MoreWalk.pick(s, "Fit to screen")
        settle()
    }
}
