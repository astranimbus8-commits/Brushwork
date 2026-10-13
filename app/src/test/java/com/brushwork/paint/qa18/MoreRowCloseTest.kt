package com.brushwork.paint.qa18

import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
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
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (design §3.9, D25), v1.9 (R3, design 2.6): how the options strip's More row closes, in the
 * editor on the user's phone (392 × 873 dp), with real touch events and a fake strip
 * ([FakeStrips]). v1.9: only its toggle closes it.
 * - It stays open while its items act (an item's `closeMore`, what an item opening a sheet calls
 *   first, is a no-op) and through every canvas touch, which is never consumed: one finger paints,
 *   a pinch zooms, a two-finger tap undoes (the history taps through an open row are
 *   `MoreRowHistoryTapsTest`'s), and a finger beside a short line reaches no item of the row.
 * - A tool change shows the new tool's remembered row; the old tool's row shows again on its
 *   return. The tool menu, the layer window, a panel and hiding the interface cover it without
 *   forgetting it: it shows again when they close. The More menu drops over it and leaves it open.
 * - Beside the vector object bar's own More row, one row shows at a time; the bar going (Deselect)
 *   or its row closing brings the strip's remembered row back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.morerowclosesandbox"])
class MoreRowCloseTest {

    @Test
    fun theMoreRowClosesOnlyByItsToggle() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("canvas touches") { canvasTouches() }
        h.section("chrome surfaces") { chromeSurfaces() }
        h.section("the object bar's More row beside the strip's") { besideTheObjectBar() }
        dog.interrupt()
        h.finish()
    }

    private fun editor(): FakeStripEditor =
        FakeStripEditor.open(Smoke.document(400, 300, layers = 2, whiteBottom = true), FakeStrips.spec(2, closer = true))

    private fun expanded(s: ChromeScreen): Boolean = when (Fingers18.state(s, StripLabels18.MORE_TOGGLE)) {
        StripLabels18.EXPANDED -> true
        StripLabels18.COLLAPSED -> false
        else -> throw AssertionError("no \"${StripLabels18.MORE_TOGGLE}\" with a state")
    }

    private fun open(s: ChromeScreen) {
        assertFalse("closed before", expanded(s))
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("the toggle opens the More row", expanded(s))
        assertNotNull(s.tagged(V18Tags.STRIP_MORE_ROW))
    }

    /** The toggle closes the row (the only way, v1.9), so the next section starts with nothing remembered. */
    private fun closeByToggle(s: ChromeScreen) {
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        assertClosed(s, "the toggle")
    }

    private fun assertOpen(s: ChromeScreen, why: String) {
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("$why: the More row stays", s.tagged(V18Tags.STRIP_MORE_ROW))
        assertTrue("$why: expanded", expanded(s))
        assertNotNull("$why: its items show", Finger.control(s, FakeStrips.more(1)))
    }

    private fun assertClosed(s: ChromeScreen, why: String) {
        Smoke.pump(300); SmokeUi.settle()
        assertNull("$why: no More row", s.tagged(V18Tags.STRIP_MORE_ROW))
        if (Fingers18.described(s, StripLabels18.MORE_TOGGLE) != null) assertFalse("$why: collapsed", expanded(s))
    }

    private fun canvasTouches() = editor().use { e ->
        val s = e.screen
        val c = s.c
        assertEquals(ToolId.BRUSH, c.activeToolId)

        // ---- the row stays open while its items act
        open(s)
        FakeStrips.taps.clear()
        Finger.tap(s, FakeStrips.more(1))
        assertEquals("the item acted", 1, FakeStrips.taps[FakeStrips.more(1)])
        assertOpen(s, "an item")

        // ---- a finger beside the short last line (content-width, end-aligned) reaches no item
        val closer = Finger.control(s, FakeStrips.CLOSER)!!
        val more = s.tagged(V18Tags.STRIP_MORE_ROW)!!
        assertTrue("the short line ends under the toggle, with room beside it", closer.left - more.left > 100f)
        FakeStrips.taps.clear()
        Finger.tapAt(s, (more.left + closer.left) / 2f, closer.center.y)
        assertTrue("…nothing on the row acted", FakeStrips.taps.isEmpty())
        assertOpen(s, "a finger beside a line")

        // ---- one finger: the stroke paints, one step; the row stays
        val blank = Fingers18.canvasState(s)
        Fingers18.stroke(s, 100f, 200f, 300f, 240f)
        val painted = Fingers18.canvasState(s)
        assertNotEquals("one finger paints", blank.pixels, painted.pixels)
        assertEquals("one step", blank.undo + 1, painted.undo)
        assertOpen(s, "a one-finger stroke")

        // ---- two fingers: a pinch zooms (no step, no paint), a two-finger tap undoes; the row stays
        Fingers18.pinch(s, 200f, 150f)
        val zoomed = Fingers18.canvasState(s)
        assertNotEquals("a pinch zooms", painted.zoom, zoomed.zoom)
        assertEquals("…no step", painted.undo, zoomed.undo)
        assertEquals("…no paint", painted.pixels, zoomed.pixels)
        assertOpen(s, "a pinch")
        Fingers18.twoFingerTap(s, 200f, 150f)
        val undone = Fingers18.canvasState(s)
        assertEquals("a two-finger tap: one undo (the stroke)", painted.undo - 1, undone.undo)
        assertEquals("…the stroke went", blank.pixels, undone.pixels)
        assertTrue("…redo", undone.redo)
        assertOpen(s, "a two-finger tap")

        // ---- only the toggle closes it; the canvas acts as before (zoomed in: a stroke in view)
        closeByToggle(s)
        Fingers18.stroke(s, 170f, 140f, 230f, 160f)
        assertEquals("painted again: one step", undone.undo + 1, Fingers18.canvasState(s).undo)
        Smoke.assertQuiet(c, "More row canvas touches")
    }

    private fun chromeSurfaces() = editor().use { e ->
        val s = e.screen
        val c = s.c

        // A tool change: the eraser's own row (never opened), the brush's again on its return.
        open(s)
        Finger.tap(s, "Switch to eraser")
        assertEquals(ToolId.ERASER, c.activeToolId)
        assertClosed(s, "the eraser's own row")
        Finger.tap(s, "Eraser on: switch to brush")
        assertEquals(ToolId.BRUSH, c.activeToolId)
        assertOpen(s, "back to the brush: its remembered row")

        // The More menu (top row) drops over the row and leaves it open.
        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the More menu drops", 2, SmokeUi.windows().size)
        assertOpen(s, "the More menu")
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)
        assertOpen(s, "the More menu closed")

        // The tool menu (bottom bar) covers it; it shows again when the menu closes.
        Finger.tap(s, "Tools (current:", exact = false)
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("the tool menu opens", Finger.toolMenu(s))
        assertClosed(s, "under the tool menu")
        Finger.tap(s, "Tools (current:", exact = false)
        Smoke.pump(300); SmokeUi.settle()
        assertNull(Finger.toolMenu(s))
        assertOpen(s, "the tool menu closed")

        // The layer window.
        Finger.tap(s, "Open layers", exact = false)
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("the layer window opens", Finger.layerWindow(s))
        assertClosed(s, "under the layer window")
        Finger.tap(s, "Close layers", exact = false)
        Smoke.pump(300); SmokeUi.settle()
        assertNull(Finger.layerWindow(s))
        assertOpen(s, "the layer window closed")

        // A panel (brush settings, then the color picker).
        for (panel in listOf("Open brush settings", "Open color picker")) {
            Finger.tap(s, panel)
            Smoke.pump(300); SmokeUi.settle()
            assertTrue("$panel: a panel shows", SmokeUi.sheetTitles().isNotEmpty() || SmokeUi.pillTitles().isNotEmpty())
            assertClosed(s, "under $panel")
            Finger.back()
            Smoke.pump(300); SmokeUi.settle()
            assertTrue("$panel: closed", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
            assertOpen(s, "$panel closed")
        }

        // Hiding the interface.
        Finger.tap(s, "Hide interface")
        Smoke.pump(500); SmokeUi.settle()
        assertNull("the strip hides with its row", s.tagged(V18Tags.STRIP_MORE_ROW))
        Finger.tap(s, "Show interface")
        Smoke.pump(500); SmokeUi.settle()
        assertOpen(s, "the interface shown again")

        // An item's closeMore (an item opening a sheet calls it first) is a no-op (v1.9).
        FakeStrips.taps.clear()
        Finger.tap(s, FakeStrips.CLOSER)
        assertEquals("the item acted", 1, FakeStrips.taps[FakeStrips.CLOSER])
        assertOpen(s, "closeMore")
        closeByToggle(s)
        Smoke.assertQuiet(c, "More row chrome surfaces")
    }

    private fun objectExpanded(s: ChromeScreen): Boolean = when (Fingers18.state(s, StripLabels18.MORE_OBJECT)) {
        StripLabels18.EXPANDED -> true
        StripLabels18.COLLAPSED -> false
        else -> throw AssertionError("no \"${StripLabels18.MORE_OBJECT}\" with a state")
    }

    /**
     * Selected vector objects with a strip that has a More row: two toggles, one More row at a time
     * (each toggle hides the other's row, remembered). The object bar going (Deselect) or its row
     * closing brings the strip's row back; a selection again brings the object bar's row back.
     */
    private fun besideTheObjectBar() {
        val kit = ObjectTestKit(400, 300)
        val doc = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 1
        }
        try {
            FakeStripEditor.open(doc, FakeStrips.spec(1)) { c ->
                val layer = c.doc.layers[1]
                c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 120f, 110f), kit.stroke(60f, 200f, 200f, 230f)), "Add")
                c.vectors.setSelection(layer, setOf(1L))
            }.use { e ->
                val s = e.screen
                val c = s.c
                Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
                SmokeUi.settle()
                assertFalse(objectExpanded(s))

                // The strip's row, then the object bar's: the second hides the first.
                open(s)
                Finger.tap(s, StripLabels18.MORE_OBJECT)
                Smoke.pump(300); SmokeUi.settle()
                assertTrue("the object bar's toggle opens its row", objectExpanded(s))
                assertFalse("…and hides the strip's", expanded(s))
                assertNull("the strip's More row went", Finger.control(s, FakeStrips.more(1)))
                // The strip's toggle shows its row again and hides the object bar's in turn.
                Finger.tap(s, StripLabels18.MORE_TOGGLE)
                Smoke.pump(300); SmokeUi.settle()
                assertTrue("the strip's toggle shows its row", expanded(s))
                assertFalse("…and hides the object bar's", objectExpanded(s))
                assertNull("the object bar's More row went", Finger.control(s, "Bring forward"))

                // The object bar's row again; its toggle closes it: the strip's row shows again.
                Finger.tap(s, StripLabels18.MORE_OBJECT)
                Smoke.pump(300); SmokeUi.settle()
                assertTrue(objectExpanded(s) && !expanded(s))
                Finger.tap(s, StripLabels18.MORE_OBJECT)
                Smoke.pump(300); SmokeUi.settle()
                assertFalse("the object bar's toggle closes its row", objectExpanded(s))
                assertTrue("…and the strip's remembered row shows again", expanded(s))
                assertNotNull(Finger.control(s, FakeStrips.more(1)))

                // Deselect with the object bar's row open: at once (the bar still fading) a pinch
                // zooms, and the strip's row shows again.
                Finger.tap(s, StripLabels18.MORE_OBJECT)
                Smoke.pump(300); SmokeUi.settle()
                assertTrue(objectExpanded(s) && !expanded(s))
                val zoom = c.viewTransform.zoom
                Fingers18.quickTap(s, "Deselect the objects")
                assertTrue("deselected", c.vectors.selectedIds.isEmpty())
                Fingers18.pinch(s, 200f, 150f)
                assertNotEquals("the pinch zooms", zoom, c.viewTransform.zoom)
                Smoke.pump(300); SmokeUi.settle()
                assertTrue("deselecting brings the strip's row back", expanded(s))
                assertNotNull(Finger.control(s, FakeStrips.more(1)))

                // Selected again: the object bar's remembered row shows again, over the strip's.
                c.vectors.setSelection(c.doc.layers[1], setOf(1L))
                Smoke.pump(300); SmokeUi.settle()
                assertTrue("the object bar's row is remembered", objectExpanded(s))
                assertFalse(expanded(s))
                Smoke.assertQuiet(c, "the object bar's More row")
            }
        } finally {
            kit.close()
        }
    }
}
