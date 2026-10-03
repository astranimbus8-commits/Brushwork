package com.brushwork.paint.ui.editor.chrome

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * I10 (v1.6 §3.7.11): labels are an API, unique among the visible clickables — on the main
 * screen, with the tool menu open, with the layer window open, with a minimized panel's pill
 * beside the X / Y pill, and with the More menu open — at the user's phone size, and on a 360 dp
 * phone ([UniqueLabelsNarrowTest]).
 *
 * One pair is shared by design and allowed here: with the tool menu open, the top row's "Ruler"
 * circle (the Ruler panel) and the tool menu's "Ruler" cell (the Ruler tool) — I10 keeps both
 * labels ("Ruler" on the top row, every tool label). The menu is checked with the Brush active
 * (another tool's options strip shows its own chips beside the menu).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.uniquelabelssandbox"])
class UniqueLabelsTest {
    @Test
    fun labelsAreUniqueAmongVisibleClickables() = UniqueLabels.run()
}

/** The same audit on a 360 × 760 dp phone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.uniquelabelsnarrowsandbox"])
class UniqueLabelsNarrowTest {
    @Test
    fun labelsAreUniqueAmongVisibleClickablesAt360dp() = UniqueLabels.run()
}

internal object UniqueLabels {

    /**
     * A value a control shows ("100%", "8.0", "12 px", "45°"), not its name: two controls may
     * show the same value (the brush's and the layer's opacity at 100 %).
     */
    private val VALUE = Regex("""^[-+]?[\d.,\s]+(%|px|°| px| %)?$""")

    private fun assertUnique(where: String, items: List<Clickables.Item>, allowed: Set<String> = emptySet()) {
        val dups = Clickables.duplicates(items).filterKeys { it !in allowed && !VALUE.matches(it) }
        assertTrue("$where: labels shared by several clickables: $dups", dups.isEmpty())
    }

    /**
     * The layer window's own labels of the I10 table (§3.7.11): each on one control while the
     * window is open, whichever version of the window (area F's ibis window or the v1.5 one).
     */
    private val WINDOW_LABELS = listOf(
        "Close layers", "Add layer", "Duplicate layer", "Delete layer", "Merge down", "More layer actions",
        "Choose blend mode", "Type layer opacity",
    )

    /** Back on the More menu's popup window closes it. */
    private fun closeMenu() {
        SmokeUi.windows().last().let { w ->
            w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        settle()
    }

    fun run() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("main screen, tool menu, layer window") {
            val s = h.editor()
            val main = Clickables.onScreen(s)
            assertTrue("the chrome is there: ${main.size}", main.size >= 20)
            assertUnique("main screen", main)
            // Every I10 label of the main screen is on its control.
            for (label in listOf(
                "Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options",
                "Switch to eraser", "Tools (current: Brush)", "Open brush settings", "Open color picker", "Hide interface",
                "Open layers (active layer 2)", "Back to gallery", "Type brush size", "Type brush opacity",
                "Smaller brush", "Bigger brush", "Less opacity", "More opacity",
            )) {
                assertEquals("\"$label\" on exactly one clickable", 1, main.count { label in it.labels })
            }

            click("Tools (current: Brush)")
            val menu = Clickables.onScreen(s)
            assertUnique("tool menu open", menu, allowed = setOf("Ruler"))
            assertTrue("at most the top-row circle and the tool cell share \"Ruler\"", menu.count { "Ruler" in it.labels } <= 2)
            for (label in listOf("Filters", "Canvas", "Settings", "Path", "Text frames")) {
                assertEquals("\"$label\" once with the menu open", 1, menu.count { label in it.labels })
            }
            click("Tools (current: Brush)")

            click("Open layers")
            val layers = Clickables.onScreen(s)
            val window = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
            if (layers.any { "Filters for this layer" in it.labels }) {
                // Area F's ibis window (its rows say "Hide layer N", "Reorder layer N"…): every
                // label on screen is unique. Inside the window a control is known by its own
                // labels: every row shows "100%" over "Normal" (values, not names).
                assertUnique("layer window open", Clickables.ownLabelsInside(layers, window))
            } else {
                // The v1.5 window (before area F merges) repeats its row labels ("Hide layer" on
                // every row): the chrome around it is unique, and so are the window's I10 labels.
                assertUnique("chrome around the layer window", Clickables.onScreen(s, outside = listOf(window)))
                assertUnique("the chrome and the window's I10 labels", layers.filter { item -> item.labels.any { it in WINDOW_LABELS } || !window.contains(item.bounds.center) })
            }
            for (label in WINDOW_LABELS.filter { l -> layers.any { l in it.labels } }) {
                assertEquals("\"$label\" on one control", 1, layers.count { label in it.labels })
            }
            assertEquals("the window ✕", 1, layers.count { "Close layers" in it.labels })
            assertEquals("the layers slot", 1, layers.count { "Close layers (active layer 2)" in it.labels })
            click("Close layers", exact = true)
            Smoke.assertQuiet(s.c, "labels")
        }
        h.section("a minimized panel's pill beside the X / Y pill") {
            val s = h.editor()
            s.c.selectAll()
            s.c.selectTool(ToolId.TRANSFORM)
            assertTrue(Smoke.pumpUntil { settle(1); (s.c.currentTool as TransformTool).transformState != null })
            settle()
            click("More options")
            click("Increments…", exact = true)
            // The sheet's title is area G's ("Increments" for the stub, "Increment steps" after G).
            val title = SmokeUi.assertIncrementsPanelShown()
            click("Minimize", exact = true)
            assertEquals(listOf(title), SmokeUi.pillTitles())
            val items = Clickables.onScreen(s)
            assertUnique("pill + X / Y", items)
            assertTrue("the pill acts as \"Show $title\"", items.any { "Show $title" in it.labels })
            // The bare title may be a control's label (the pill's "#" cell is "Increments"): the
            // minimized pill is never known by it alone.
            assertTrue("never as the bare title", items.none { title in it.labels && "Show $title" in it.labels })
            click("Close $title", exact = true)
            (s.c.currentTool as TransformTool).discard()
            settle()
        }
        h.section("the More menu") {
            val s = h.editor()
            click("More options")
            val popup = Clickables.onScreen(s).filter { it.window !== s.activity.window.decorView }
            assertTrue("the menu is a dropdown: ${popup.size}", popup.size >= 15)
            assertUnique("More menu", popup)
            // (The menu scrolls on a short phone: the last entries may be below its fold.)
            for (entry in listOf("Canvas…", "Increments…", "Settings", "Export SVG…", "Fit to screen")) {
                assertTrue("\"$entry\" in the menu", SmokeUi.has(entry, exact = true))
                assertTrue("\"$entry\" at most once on screen", popup.count { entry in it.labels } <= 1)
            }
            closeMenu()
            assertEquals(1, SmokeUi.windows().size)
            // Opened over the layer window, the menu leaves it open (like every chrome button):
            // only "Import picture" is in both (the same action; the open menu takes every touch).
            click("Open layers")
            assertNotNull(s.tagged(ChromeTags.LAYER_WINDOW))
            click("More options")
            val window = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("the layer window stays")
            val all = Clickables.onScreen(s)
            val menu = all.filter { it.window !== s.activity.window.decorView }
            val editor = all.filter { it.window === s.activity.window.decorView }
            assertTrue("the menu is open: ${menu.size}", menu.size >= 15)
            assertUnique("More menu and the chrome around the layer window", menu + editor.filter { !window.contains(it.bounds.center) })
            // Against the window's controls, known by their own names (rows show values).
            val windowNames = Clickables.ownLabelsInside(editor.filter { window.contains(it.bounds.center) }, window).flatMap { it.labels }.toSet()
            val clash = menu.flatMap { it.labels }.filter { it in windowNames && it != "Import picture" }
            assertTrue("More entries repeating a layer window control: $clash", clash.isEmpty())
            closeMenu()
            click("Close layers", exact = true)
            // Opened over the tool menu, the menu closes it first: its cells would repeat "Settings".
            click("Tools (current: Brush)")
            assertNotNull(s.tagged(ChromeTags.TOOL_MENU))
            click("More options")
            assertNull("the tool menu closed", s.tagged(ChromeTags.TOOL_MENU))
            assertUnique("More menu over the editor", Clickables.onScreen(s))
            closeMenu()
            assertEquals(1, SmokeUi.windows().size)
            Smoke.assertQuiet(s.c, "More menu")
        }
        dog.interrupt()
        h.finish()
    }
}
