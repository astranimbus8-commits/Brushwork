package com.brushwork.paint.qa17

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.HistoryTapFingers
import com.brushwork.paint.ui.editor.HistoryTapFingers.Companion.SEED
import com.brushwork.paint.ui.editor.HistoryTapFingers.Companion.seed
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 item 10 (design §3.10, QA row 8), the two exclusions and the plain layer row, on the user's
 * 392 dp phone with real multi-touch events:
 * - **Inside a dialog nothing happens:** the brush size dialog ("Type brush size") is its own
 *   window; two fingers on its title and range text undo nothing, three redo nothing, and the
 *   dialog stays open with its text untouched. Closed, the same two fingers over the canvas undo
 *   exactly one step.
 * - **Over an open dropdown the tap only closes it:** "More options" opens a focusable menu
 *   window that takes every finger while it is up (touch-modal, as on the phone). Two fingers on
 *   its "Flip view" and "Canvas…" items close it, and neither item fires; two fingers beside it
 *   (over the canvas) close it too. No undo either way.
 * - **The pill of a real Path tool:** the first finger on the trash ("Delete path"), the second on
 *   "Keep scale proportions": one in-tool undo (the last point goes), the path is not deleted and
 *   the chain keeps its state; three fingers redo the point.
 * - **A plain layer row:** two fingers on the rows of the layer window undo once ("Undo: Seed"),
 *   the row under the first finger does not select its layer and the window stays; three redo.
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox; one fresh
 * editor per section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.chromehistorytapssandbox"])
class Qa17ChromeHistoryTapsUiTest {

    @Test
    fun historyTapsStopAtDialogsAndMenusAndUndoOverLayerRows() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("inside the brush size dialog") { dialog(h) }
        h.section("over the More options menu") { moreMenu(h) }
        h.section("over the Curve tool's Stroke kind menu") { strokeKindMenu(h) }
        h.section("a plain layer row") { layerRow(h) }
        h.section("the trash and keep cells of a real path's pill") { pillTrash(h) }
        dog.interrupt()
        h.finish()
    }

    /** Placed elements of [window] showing exactly [text] (window px of that window). */
    private fun textsIn(window: View, text: String): List<RobolectricUi.Element> = RobolectricUi.elements().filter { e ->
        e.window === window && e.node.layoutInfo.isPlaced &&
            e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun centerOf(window: View, text: String, topmost: Boolean = false): Offset {
        val hits = textsIn(window, text).filter { it.bounds.width > 0f }
        val e = (if (topmost) hits.minByOrNull { it.bounds.top } else hits.lastOrNull())
            ?: throw AssertionError("no \"$text\" in the window; shown: ${SmokeUi.shown().take(80)}")
        return e.bounds.center
    }

    private fun Offset.pair(): Pair<Float, Float> = x to y

    private fun dialog(h: ChromeHarness) {
        val s = h.editor()
        val c = s.c
        val f = HistoryTapFingers(s)
        seed(c, 20f)
        seed(c, 40f)
        c.undo()
        settle()
        val steps = f.steps
        assertEquals("one step to undo", SEED, c.undoManager.undoLabel)
        assertEquals("one step to redo", SEED, c.undoManager.redoLabel)
        val size = c.presetFor(ToolId.BRUSH)!!.size

        Finger.tap(s, "Type brush size")
        val windows = SmokeUi.windows()
        assertEquals("the dialog is its own window", 2, windows.size)
        val dlg = windows.last()
        val shownText = SmokeUi.field("Brush size").text
        val title = centerOf(dlg, "Brush size", topmost = true)
        val range = centerOf(dlg, "0.5 – 1000 px")
        val t = Smoke.Touch(dlg)

        t.twoFingerTap(title.pair(), range.pair())
        settle()
        assertEquals("two fingers in a dialog undo nothing", steps, f.steps)
        assertFalse(f.saw("Undo: $SEED"))
        assertEquals("the dialog stays", 2, SmokeUi.windows().size)
        assertEquals("its text is untouched", shownText, SmokeUi.field("Brush size").text)

        t.threeFingerTap(title.pair(), range.pair(), Offset((title.x + range.x) / 2f, range.y).pair())
        settle()
        assertEquals("three fingers in a dialog redo nothing", steps, f.steps)
        assertEquals(SEED, c.undoManager.redoLabel)
        assertFalse(f.saw("Redo: $SEED"))
        assertEquals("the dialog stays", 2, SmokeUi.windows().size)

        SmokeUi.clickIn("Brush size", "Cancel")
        assertEquals("closed", 1, SmokeUi.windows().size)
        assertEquals("nothing applied", size, c.presetFor(ToolId.BRUSH)!!.size)

        // The dialog gone, two fingers over the canvas undo exactly one step.
        val a = s.screen(150f, 150f)
        val b = s.screen(250f, 150f)
        s.touch.twoFingerTap(a, b)
        settle()
        assertEquals("exactly one undo over the canvas", steps - 1, f.steps)
        assertTrue(f.saw("Undo: $SEED"))
        Smoke.assertQuiet(c, "dialog")
    }

    private fun moreMenu(h: ChromeHarness) {
        val s = h.editor()
        val c = s.c
        val f = HistoryTapFingers(s)
        seed(c)
        val steps = f.steps
        val mirrored = c.viewMirrored

        fun openMenu(): View {
            Finger.tap(s, "More options")
            val windows = SmokeUi.windows()
            assertEquals("the menu is its own window: ${SmokeUi.shown().take(40)}", 2, windows.size)
            assertTrue(SmokeUi.has("Flip view", exact = true))
            return windows.last()
        }

        fun nothingHappened(where: String) {
            assertEquals("$where: the menu closed", 1, SmokeUi.windows().size)
            assertFalse("$where: no menu item left", SmokeUi.has("Flip view", exact = true))
            assertEquals("$where: no undo", steps, f.steps)
            assertFalse("$where: no undo feedback", f.saw("Undo: $SEED"))
            assertEquals("$where: \"Flip view\" did not fire", mirrored, c.viewMirrored)
            assertTrue("$where: \"Canvas…\" did not fire: ${SmokeUi.sheetTitles()}", SmokeUi.sheetTitles().isEmpty())
            assertEquals(SEED, c.undoManager.undoLabel)
        }

        // Both fingers on menu items.
        val menu = openMenu()
        val flip = centerOf(menu, "Flip view")
        val canvas = centerOf(menu, "Canvas…")
        Smoke.Touch(menu).twoFingerTap(flip.pair(), canvas.pair())
        settle()
        nothingHappened("on the items")

        // Both fingers beside the menu, over the canvas: the menu window still takes them (its
        // own coordinates, outside its bounds), as a touch-modal window does on the phone.
        val again = openMenu()
        val w = again.width.toFloat()
        val hgt = again.height.toFloat()
        Smoke.Touch(again).twoFingerTap(w * 0.3f to hgt + 120f * s.density, w * 0.7f to hgt + 120f * s.density)
        settle()
        nothingHappened("beside it")

        // The menu closed, the same tap over the canvas undoes.
        s.touch.twoFingerTap(s.screen(150f, 150f), s.screen(250f, 150f))
        settle()
        assertEquals("then one undo", steps - 1, f.steps)
        Smoke.assertQuiet(c, "more menu")
    }

    /** The shared dropdown chip of the tool strips (the Curve tool's "Stroke kind"). */
    private fun strokeKindMenu(h: ChromeHarness) {
        val s = h.editor()
        val c = s.c
        val f = HistoryTapFingers(s)
        seed(c)
        c.selectTool(ToolId.CURVE)
        settle()
        val tool = c.currentTool as CurveTool
        val kind = tool.settings.stroke
        assertEquals(CurveStroke.BRUSH, kind)
        val steps = f.steps
        f.reachInStrip(CurveLabels17.STROKE_KIND)
        Finger.tap(s, CurveLabels17.STROKE_KIND)
        val windows = SmokeUi.windows()
        assertEquals("the menu is its own window: ${SmokeUi.shown().take(40)}", 2, windows.size)
        val menu = windows.last()
        val plain = centerOf(menu, CurveStroke.PLAIN.label)
        val brush = centerOf(menu, CurveStroke.BRUSH.label)
        Smoke.Touch(menu).twoFingerTap(plain.pair(), brush.pair())
        settle()
        assertEquals("the menu closed", 1, SmokeUi.windows().size)
        assertEquals("no kind picked", kind, tool.settings.stroke)
        assertEquals("no undo", steps, f.steps)
        assertFalse(f.saw("Undo: $SEED"))
        // Three fingers on it: the same.
        Finger.tap(s, CurveLabels17.STROKE_KIND)
        val again = SmokeUi.windows().last()
        val p = centerOf(again, CurveStroke.PLAIN.label)
        val b = centerOf(again, CurveStroke.BRUSH.label)
        Smoke.Touch(again).threeFingerTap(p.pair(), b.pair(), Offset(p.x + 40f * s.density, p.y).pair())
        settle()
        assertEquals("the menu closed", 1, SmokeUi.windows().size)
        assertEquals("no kind picked", kind, tool.settings.stroke)
        assertEquals("no redo, no undo", steps, f.steps)
        // One finger picks.
        Finger.tap(s, CurveLabels17.STROKE_KIND)
        SmokeUi.windows().last().let { w -> RobolectricUi.tap(w, centerOf(w, CurveStroke.PLAIN.label).x, centerOf(w, CurveStroke.PLAIN.label).y) }
        assertEquals("a one-finger tap picks", CurveStroke.PLAIN, tool.settings.stroke)
        Smoke.assertQuiet(c, "stroke kind menu")
    }

    private fun layerRow(h: ChromeHarness) {
        val s = h.editor()
        val c = s.c
        val f = HistoryTapFingers(s)
        seed(c)
        Finger.tap(s, "Open layers", exact = false)
        assertNotNull("the layer window", Finger.layerWindow(s))
        val active = c.doc.activeLayerIndex
        assertEquals("the top layer is active", 1, active)
        val windows = SmokeUi.windows().size
        with(f) {
            val steps = this.steps
            // The first finger on the bottom layer's row (a tap there selects it), the second on the active row.
            val other = control("Select layer 1", region(ChromeTags.LAYER_WINDOW))
            val mine = control("Select layer 2", region(ChromeTags.LAYER_WINDOW))
            twoFingers(other.at(0.5f), mine.at(0.5f))
            assertEquals("exactly one undo", steps - 1, this.steps)
            assertTrue("the feedback: ${SmokeUi.shown().take(60)}", saw("Undo: $SEED"))
            assertEquals("the row did not select its layer", active, c.doc.activeLayerIndex)
            assertNotNull("the layer window stays", Finger.layerWindow(s))
            assertEquals("nothing opened", windows, SmokeUi.windows().size)

            threeFingers(other.at(0.3f), other.at(0.7f), mine.at(0.5f))
            assertEquals("one redo", steps, this.steps)
            assertTrue(saw("Redo: $SEED"))
            assertEquals("no row picked", active, c.doc.activeLayerIndex)
            assertNotNull("the layer window stays", Finger.layerWindow(s))

            // One finger on the same row does select it (the control the tap kept from firing).
            Finger.tap(s, "Select layer 1")
            assertEquals("a one-finger tap selects", 0, c.doc.activeLayerIndex)
        }
        Smoke.assertQuiet(c, "layer row")
    }

    private fun pillTrash(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        c.selectTool(ToolId.PATH)
        settle()
        val tool = c.currentTool as CurveTool
        for ((x, y) in listOf(100f to 200f, 200f to 100f, 300f to 200f)) {
            s.touch.idle(300)
            val (sx, sy) = s.screen(x, y)
            s.touch.tap(sx, sy)
            settle(4)
        }
        assertEquals(3, tool.pointCount)
        val trash = requireNotNull(s.tagged(V17Tags.PILL_TRASH)) { "no trash; shown: ${SmokeUi.shown().take(60)}" }
        assertTrue("the trash deletes the path", SmokeUi.has(PillLabels.deleteObject("path"), exact = true))
        fun keep() = SmokeUi.find(PillLabels.KEEP_PROPORTIONS, exact = true)?.node?.config?.getOrNull(SemanticsProperties.ToggleableState)
        val chain = keep()
        assertNotNull("row 2's chain", chain)
        with(f) {
            val keepCell = control(PillLabels.KEEP_PROPORTIONS)
            twoFingers(trash.at(0.5f), keepCell.at(0.5f))
            assertEquals("one in-tool undo: the last point goes, not the path", 2, tool.pointCount)
            assertEquals("the chain did not toggle", chain, keep())
            threeFingers(trash.at(0.5f), keepCell.at(0.5f), control("X slider").at(0.5f))
            assertEquals("one redo", 3, tool.pointCount)
            assertEquals(chain, keep())
        }
        Smoke.assertQuiet(c, "pill trash")
    }
}
