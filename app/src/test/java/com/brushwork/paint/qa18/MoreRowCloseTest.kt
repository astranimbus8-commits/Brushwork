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
 * v1.8 (design §3.9, D25): how the options strip's More row closes, in the editor on the user's
 * phone (392 × 873 dp), with real touch events and a fake strip ([FakeStrips]). It stays open while
 * its items act and while a finger lands on its empty part. A canvas touch while it is open only
 * closes it: one finger paints nothing, two fingers neither zoom nor undo; the next touch acts.
 * A tool change, the More menu, the tool menu, the layer window, a panel, hiding the interface and
 * an item's `closeMore` (what an item opening a sheet calls) close it too. Beside the vector object
 * bar's own More row, one row shows at a time, and the bar going takes its row along at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.morerowclosesandbox"])
class MoreRowCloseTest {

    @Test
    fun theMoreRowClosesByTheD25Rules() {
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

    private fun assertClosed(s: ChromeScreen, why: String) {
        Smoke.pump(300); SmokeUi.settle()
        assertNull("$why: no More row", s.tagged(V18Tags.STRIP_MORE_ROW))
        if (Fingers18.described(s, StripLabels18.MORE_TOGGLE) != null) assertFalse("$why: collapsed", expanded(s))
    }

    private fun canvasTouches() = editor().use { e ->
        val s = e.screen
        val c = s.c
        assertEquals(ToolId.BRUSH, c.activeToolId)

        // ---- the row stays open while its items act, and over its empty part
        open(s)
        FakeStrips.taps.clear()
        Finger.tap(s, FakeStrips.more(1))
        assertEquals("the item acted", 1, FakeStrips.taps[FakeStrips.more(1)])
        assertTrue("the row stays open", expanded(s))
        val more = s.tagged(V18Tags.STRIP_MORE_ROW)!!
        val item = Finger.control(s, FakeStrips.more(1))!!
        var before = Fingers18.canvasState(s)
        Finger.tapAt(s, (item.right + more.right) / 2f, item.center.y)
        assertTrue("a finger on the row's empty part keeps it open", expanded(s))
        assertEquals("…and reaches nothing under it", before, Fingers18.canvasState(s))

        // ---- one finger: consumed, the row closes; the next stroke paints
        Fingers18.stroke(s, 100f, 200f, 300f, 240f)
        assertEquals("one finger: nothing painted, no step", before, Fingers18.canvasState(s))
        assertClosed(s, "a one-finger touch")
        Fingers18.stroke(s, 100f, 200f, 300f, 240f)
        val painted = Fingers18.canvasState(s)
        assertNotEquals("the next stroke paints", before.pixels, painted.pixels)
        assertEquals("one step", before.undo + 1, painted.undo)

        // ---- two fingers: no zoom, no undo; the next pinch zooms, the next two-finger tap undoes
        open(s)
        before = Fingers18.canvasState(s)
        Fingers18.pinch(s, 200f, 150f)
        assertEquals("a pinch: no zoom", before, Fingers18.canvasState(s))
        assertClosed(s, "a pinch")
        open(s)
        Fingers18.twoFingerTap(s, 200f, 150f)
        assertEquals("a two-finger tap: no undo", before, Fingers18.canvasState(s))
        assertClosed(s, "a two-finger tap")
        Fingers18.pinch(s, 200f, 150f)
        assertNotEquals("the next pinch zooms", before.zoom, Fingers18.canvasState(s).zoom)
        val zoomed = Fingers18.canvasState(s)
        Fingers18.twoFingerTap(s, 200f, 150f)
        assertEquals("the next two-finger tap undoes the stroke", zoomed.undo - 1, Fingers18.canvasState(s).undo)
        Smoke.assertQuiet(c, "More row canvas touches")
    }

    private fun chromeSurfaces() = editor().use { e ->
        val s = e.screen
        val c = s.c

        // A tool change.
        open(s)
        Finger.tap(s, "Switch to eraser")
        assertEquals(ToolId.ERASER, c.activeToolId)
        assertClosed(s, "a tool change")

        // The More menu (top row).
        open(s)
        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the More menu drops", 2, SmokeUi.windows().size)
        assertClosed(s, "the More menu")
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)

        // The tool menu (bottom bar).
        open(s)
        Finger.tap(s, "Tools (current:", exact = false)
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("the tool menu opens", Finger.toolMenu(s))
        assertClosed(s, "the tool menu")
        Finger.tap(s, "Tools (current:", exact = false)
        Smoke.pump(300); SmokeUi.settle()
        assertNull(Finger.toolMenu(s))

        // The layer window.
        open(s)
        Finger.tap(s, "Open layers", exact = false)
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("the layer window opens", Finger.layerWindow(s))
        assertClosed(s, "the layer window")
        Finger.tap(s, "Close layers", exact = false)
        Smoke.pump(300); SmokeUi.settle()
        assertNull(Finger.layerWindow(s))

        // A panel (brush settings, then the color picker).
        for (panel in listOf("Open brush settings", "Open color picker")) {
            open(s)
            Finger.tap(s, panel)
            Smoke.pump(300); SmokeUi.settle()
            assertTrue("$panel: a panel shows", SmokeUi.sheetTitles().isNotEmpty() || SmokeUi.pillTitles().isNotEmpty())
            assertClosed(s, panel)
            Finger.back()
            Smoke.pump(300); SmokeUi.settle()
            assertTrue("$panel: closed", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
        }

        // Hiding the interface.
        open(s)
        Finger.tap(s, "Hide interface")
        Smoke.pump(500); SmokeUi.settle()
        assertNull("the strip hides with its row", s.tagged(V18Tags.STRIP_MORE_ROW))
        Finger.tap(s, "Show interface")
        Smoke.pump(500); SmokeUi.settle()
        assertClosed(s, "hiding the interface")

        // An item's closeMore (an item opening a sheet calls it first).
        open(s)
        FakeStrips.taps.clear()
        Finger.tap(s, FakeStrips.CLOSER)
        assertEquals("the item acted", 1, FakeStrips.taps[FakeStrips.CLOSER])
        assertClosed(s, "closeMore")
        Smoke.assertQuiet(c, "More row chrome surfaces")
    }

    private fun objectExpanded(s: ChromeScreen): Boolean = when (Fingers18.state(s, StripLabels18.MORE_OBJECT)) {
        StripLabels18.EXPANDED -> true
        StripLabels18.COLLAPSED -> false
        else -> throw AssertionError("no \"${StripLabels18.MORE_OBJECT}\" with a state")
    }

    /**
     * Selected vector objects with a strip that has a More row: two toggles, one More row at a time
     * (each toggle closes the other's row). And the object bar going (Deselect) takes its row along
     * at once: a pinch while the bar still fades out zooms.
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

                // The strip's row, then the object bar's: the second closes the first.
                open(s)
                Finger.tap(s, StripLabels18.MORE_OBJECT)
                Smoke.pump(300); SmokeUi.settle()
                assertTrue("the object bar's toggle opens its row", objectExpanded(s))
                assertFalse("…and closes the strip's", expanded(s))
                assertNull("the strip's More row went", Finger.control(s, FakeStrips.more(1)))
                // The strip's toggle closes the object bar's row in turn.
                Finger.tap(s, StripLabels18.MORE_TOGGLE)
                Smoke.pump(300); SmokeUi.settle()
                assertTrue("the strip's toggle opens its row", expanded(s))
                assertFalse("…and closes the object bar's", objectExpanded(s))
                assertNull("the object bar's More row went", Finger.control(s, "Bring forward"))
                Finger.tap(s, StripLabels18.MORE_TOGGLE)
                assertClosed(s, "the strip's toggle again")

                // Deselect with the object bar's row open, then at once (the bar still fading) a pinch.
                Finger.tap(s, StripLabels18.MORE_OBJECT)
                Smoke.pump(300); SmokeUi.settle()
                assertTrue(objectExpanded(s))
                val zoom = c.viewTransform.zoom
                Fingers18.quickTap(s, "Deselect the objects")
                assertTrue("deselected", c.vectors.selectedIds.isEmpty())
                Fingers18.pinch(s, 200f, 150f)
                assertNotEquals("the bar going left nothing open: the pinch zooms", zoom, c.viewTransform.zoom)
                Smoke.assertQuiet(c, "the object bar's More row")
            }
        } finally {
            kit.close()
        }
    }
}
