package com.brushwork.paint.qa16

import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A finger walks the ibisPaint chrome on the user's phone (392 × 873 dp): every top-row circle
 * and bottom-bar button, tapped with real touch events, does what its label says (§3.7.3,
 * §3.7.5); Hide interface hides what it says and keeps what it says; Back closes the newest
 * floating thing first; the tool menu, the layer window, the panels and the More menu open and
 * close by the rules of §3.7.6–3.7.7.
 */
internal object Walk {
    fun run(name: String, block: (ChromeHarness) -> Unit) {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section(name) { block(h) }
        dog.interrupt()
        h.finish()
    }

    /** Editor dp of document point ([x], [y]). */
    fun docDp(s: ChromeScreen, x: Float, y: Float): Pair<Float, Float> {
        val (px, py) = s.screen(x, y)
        val r = s.root
        return ((px - r.left) / s.density) to ((py - r.top) / s.density)
    }

    fun shows(label: String) = SmokeUi.has(label, exact = true)

    /** A panel titled [title] is up; Back folds it away again and nothing is left. */
    fun panelThenBack(title: String) {
        SmokeUi.assertPanelShown(title)
        Finger.back()
        assertTrue("Back closed \"$title\": ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
    }

    /** A stroke across the page by a finger. */
    fun stroke(s: ChromeScreen) {
        Finger.slowDrag(s, docDp(s, 60f, 330f), docDp(s, 240f, 380f))
        Smoke.pump(300)
        settle()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.toprowwalksandbox"])
class TopRowFingerWalkTest {
    @Test
    fun everyCircleDoesWhatItSays() = Walk.run("top row") { h ->
        val s = h.editor(IbisDocs.page())
        assertFalse("nothing to undo yet", s.c.canUndo)
        Walk.stroke(s)
        assertTrue("the stroke is undoable", s.c.canUndo)
        Finger.tap(s, "Undo")
        assertFalse("Undo undid the stroke", s.c.canUndo)
        assertTrue("…and it can be redone", s.c.canRedo)
        Finger.tap(s, "Redo")
        assertTrue("Redo redid it", s.c.canUndo)
        assertFalse(s.c.canRedo)

        assertFalse(s.c.isVectorMode)
        Finger.tap(s, "Vector")
        assertTrue("Vector turns vector mode on", s.c.isVectorMode)
        Finger.tap(s, "Vector")
        assertFalse("…and off", s.c.isVectorMode)

        Finger.tap(s, "Selection"); Walk.panelThenBack("Selection")
        Finger.tap(s, "Stabilizer"); Walk.panelThenBack("Stabilizer")
        Finger.tap(s, "Grid"); Walk.panelThenBack("Grid")
        Finger.tap(s, "Ruler"); Walk.panelThenBack("Ruler")

        Finger.tap(s, "More options")
        Smoke.pump(300); settle()
        assertEquals("the More menu drops", 2, SmokeUi.windows().size)
        for (entry in listOf("Export PNG", "Fit to screen", "Increments…", "Settings")) assertTrue("More › $entry", Walk.shows(entry))
        Finger.back()
        assertEquals("Back closes the More menu", 1, SmokeUi.windows().size)
        Smoke.assertQuiet(s.c, "top row")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.bottombarwalksandbox"])
class BottomBarFingerWalkTest {
    @Test
    fun everyButtonDoesWhatItSays() = Walk.run("bottom bar") { h ->
        val s = h.editor(IbisDocs.page())
        Finger.tap(s, "Switch to eraser")
        assertEquals(ToolId.ERASER, s.c.activeToolId)
        Finger.tap(s, "Eraser on: switch to brush")
        assertEquals(ToolId.BRUSH, s.c.activeToolId)

        Finger.tap(s, "Tools (current: Brush)")
        assertNotNull("the tools button opens the tool menu", s.tagged(ChromeTags.TOOL_MENU))
        Finger.tap(s, "Tools (current: Brush)")
        assertNull("…and closes it", s.tagged(ChromeTags.TOOL_MENU))

        Finger.tap(s, "Open brush settings"); Walk.panelThenBack("Brush")
        Finger.tap(s, "Open color picker"); Walk.panelThenBack("Color")

        Finger.tap(s, "Open layers (active layer 2)")
        assertNotNull("the layers button opens the layer window", s.tagged(ChromeTags.LAYER_WINDOW))
        Finger.tap(s, "Close layers (active layer 2)")
        assertNull("…and closes it", s.tagged(ChromeTags.LAYER_WINDOW))

        Finger.tap(s, "Hide interface")
        Smoke.pump(400); settle()
        for (gone in listOf("Undo", "More options", "Smaller brush", "Brush size", "Type brush size")) assertFalse("hidden: $gone", Walk.shows(gone))
        assertNull("the X / Y pill hides", s.tagged(ChromeTags.PILL_SLOT))
        assertNotNull("the bottom bar stays", s.tagged(ChromeTags.BOTTOM_BAR))
        Finger.tap(s, "Show interface")
        Smoke.pump(400); settle()
        for (back in listOf("Undo", "More options", "Smaller brush", "Hide interface")) assertTrue("shown again: $back", Walk.shows(back))

        // Back to gallery: the editor's exit (autosave is the host's), with a host that records it.
        var exited = 0
        s.activity.setContent { BrushworkTheme { EditorScreen(s.c, onExit = { exited++ }, onSaveNow = {}) } }
        settle()
        Finger.tap(s, "Back to gallery")
        assertEquals("Back to gallery leaves the editor", 1, exited)
        Smoke.assertQuiet(s.c, "bottom bar")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.hidependingsandbox"])
class HideInterfaceWithPendingWorkTest {
    @Test
    fun applyAndDiscardStayWhileHidden() = Walk.run("hide with ✓ / ✕") { h ->
        val s = h.editor(IbisDocs.page())
        Finger.tap(s, "Tools (current: Brush)")
        Finger.tap(s, "Transform")
        assertEquals(ToolId.TRANSFORM, s.c.activeToolId)
        assertTrue(Smoke.pumpUntil { settle(1); (s.c.currentTool as TransformTool).transformState != null })
        // Move the red disc by a finger: the transform has changes to apply or discard.
        val from = Walk.docDp(s, 150f, 200f)
        Finger.slowDrag(s, from, (from.first + 30f) to (from.second + 20f))
        Smoke.pump(300); settle()
        assertTrue("✓ shows", Walk.shows("Apply transform edit"))
        Finger.tap(s, "Hide interface")
        Smoke.pump(400); settle()
        assertFalse("the X / Y pill hides", SmokeUi.has("X slider"))
        assertTrue("✓ stays", Walk.shows("Apply transform edit"))
        assertTrue("✕ stays", Walk.shows("Discard transform edit"))
        Finger.tap(s, "Apply transform edit")
        assertFalse("applied", s.c.currentTool.hasPendingWork)
        assertTrue("undoable", s.c.canUndo)
        Finger.tap(s, "Show interface")
        Smoke.pump(400); settle()
        assertTrue(Walk.shows("Undo"))
        Smoke.assertQuiet(s.c, "hide with pending work")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.backordersandbox"])
class BackKeyOrderTest {
    @Test
    fun backClosesTheNewestFirst() = Walk.run("back order") { h ->
        val s = h.editor(IbisDocs.page())
        fun state() = "menu=${s.tagged(ChromeTags.TOOL_MENU) != null} layers=${s.tagged(ChromeTags.LAYER_WINDOW) != null} sheets=${SmokeUi.sheetTitles()} pills=${SmokeUi.pillTitles()} windows=${SmokeUi.windows().size}"

        // A panel replaces the one before (one panel at a time); Back closes it.
        Finger.tap(s, "Open brush settings")
        Finger.tap(s, "Grid")
        assertEquals(state(), listOf("Grid"), SmokeUi.sheetTitles())
        Finger.back()
        assertTrue(state(), SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

        // A panel, then the layer window (the panel waits as a pill): the window first, then the panel.
        Finger.tap(s, "Open brush settings")
        Finger.tap(s, "Open layers (active layer 2)")
        assertNotNull(state(), s.tagged(ChromeTags.LAYER_WINDOW))
        assertTrue("the panel made room: ${state()}", SmokeUi.sheetTitles().isEmpty())
        Finger.back()
        assertNull("Back closes the layer window first: ${state()}", s.tagged(ChromeTags.LAYER_WINDOW))
        Finger.back()
        assertTrue("then the panel: ${state()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

        // A panel, then the tool menu: the menu first, then the panel.
        Finger.tap(s, "Open color picker")
        Finger.tap(s, "Tools (current: Brush)")
        assertNotNull(state(), s.tagged(ChromeTags.TOOL_MENU))
        Finger.back()
        assertNull("Back closes the tool menu first: ${state()}", s.tagged(ChromeTags.TOOL_MENU))
        assertTrue("the panel is still there: ${state()}", (SmokeUi.sheetTitles() + SmokeUi.pillTitles()).isNotEmpty())
        Finger.back()
        assertTrue("then the panel: ${state()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

        // The More menu over the layer window: Back closes the menu only.
        Finger.tap(s, "Open layers (active layer 2)")
        Finger.tap(s, "More options")
        Smoke.pump(300); settle()
        assertEquals(state(), 2, SmokeUi.windows().size)
        Finger.back()
        assertEquals("the More menu closed: ${state()}", 1, SmokeUi.windows().size)
        assertNotNull("the layer window stays: ${state()}", s.tagged(ChromeTags.LAYER_WINDOW))
        Finger.back()
        assertNull(state(), s.tagged(ChromeTags.LAYER_WINDOW))
        assertFalse("nothing left to close: the editor is still up", s.activity.isFinishing)
        Smoke.assertQuiet(s.c, "back order")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.openclosesandbox"])
class FloatingOpenCloseRulesTest {
    @Test
    fun theToolMenuAndTheLayerWindowFollowTheRules() = Walk.run("open / close rules") { h ->
        val s = h.editor(IbisDocs.page())
        fun state() = "menu=${s.tagged(ChromeTags.TOOL_MENU) != null} layers=${s.tagged(ChromeTags.LAYER_WINDOW) != null} sheets=${SmokeUi.sheetTitles()} pills=${SmokeUi.pillTitles()}"
        val canvasSpot = Walk.docDp(s, 280f, 60f)

        // The tool menu: a tap outside (on the canvas) closes it and doesn't paint.
        Finger.tap(s, "Tools (current: Brush)")
        assertNotNull(state(), s.tagged(ChromeTags.TOOL_MENU))
        Finger.tapAt(s, canvasSpot.first, canvasSpot.second)
        assertNull("a canvas tap closes the tool menu: ${state()}", s.tagged(ChromeTags.TOOL_MENU))

        // Opening the tool menu closes the layer window …
        Finger.tap(s, "Open layers (active layer 2)")
        assertNotNull(state(), s.tagged(ChromeTags.LAYER_WINDOW))
        Finger.tap(s, "Tools (current: Brush)")
        assertNotNull(state(), s.tagged(ChromeTags.TOOL_MENU))
        assertNull("the tool menu closes the layer window: ${state()}", s.tagged(ChromeTags.LAYER_WINDOW))
        // … and opening the layer window closes the tool menu.
        Finger.tap(s, "Open layers (active layer 2)")
        assertNotNull(state(), s.tagged(ChromeTags.LAYER_WINDOW))
        assertNull("the layer window closes the tool menu: ${state()}", s.tagged(ChromeTags.TOOL_MENU))
        Finger.tap(s, "Close layers (active layer 2)")

        // Opening the tool menu minimizes a panel (to its pill, not closed).
        Finger.tap(s, "Open brush settings")
        SmokeUi.assertPanelShown("Brush")
        Finger.tap(s, "Tools (current: Brush)")
        assertNotNull(state(), s.tagged(ChromeTags.TOOL_MENU))
        assertEquals("the panel folds away: ${state()}", listOf("Tools"), SmokeUi.sheetTitles())
        // Picking a tool closes the menu; the panel's button brings it back.
        Finger.tap(s, "Eraser")
        assertNull(state(), s.tagged(ChromeTags.TOOL_MENU))
        assertEquals(ToolId.ERASER, s.c.activeToolId)
        Finger.tap(s, "Open brush settings")
        assertEquals("the panel is back (titled by the tool): ${state()}", listOf("Eraser"), SmokeUi.sheetTitles())
        Finger.back()
        Finger.tap(s, "Eraser on: switch to brush")

        // The layer window stays open for chrome buttons (More, Undo) and canvas touches.
        Finger.tap(s, "Open layers (active layer 2)")
        Walk.stroke(s)
        assertNotNull("painting leaves the layer window open: ${state()}", s.tagged(ChromeTags.LAYER_WINDOW))
        Finger.tap(s, "Undo")
        assertNotNull("Undo leaves it open: ${state()}", s.tagged(ChromeTags.LAYER_WINDOW))
        Finger.tap(s, "Close layers (active layer 2)")
        Smoke.assertQuiet(s.c, "open / close rules")
    }
}
