package com.brushwork.paint.ui.editor.chrome

import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.layers.FrameBadge
import com.brushwork.paint.ui.layers.LayerLabels
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
 * screen, with the tool menu open, with the layer window open (also over two layers of each kind:
 * their badges name their rows), with a minimized panel's pill beside the X / Y pill, and with the
 * More menu open — at the user's phone size, and on a 360 dp phone ([UniqueLabelsNarrowTest]).
 * v1.7 F5 (design §4.8) adds the screens where the foundation puts new strings: the selection bar
 * with a selection, the tool menu's three new cells, the Ruler panel and the layer ⋮.
 *
 * One pair is shared by design and allowed here: with the tool menu open, the top row's "Ruler"
 * circle (the Ruler panel) and the tool menu's "Ruler" cell (the Ruler tool) — I10 keeps both
 * labels ("Ruler" on the top row, every tool label). The menu is checked with the Brush active
 * (another tool's options strip shows its own chips beside the menu). The More menu opened over
 * the layer window leaves out its "Import picture" (the window's button is the one).
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

    /**
     * Above the two plain layers: two text layers, two vector layers, two Tone adjustment layers
     * with editable masks, two frames "1 of 1" of two stories, two shape layers (bottom first,
     * layers 3 to 12).
     */
    private fun twoOfEachKind(c: EditorController) {
        fun text(item: TextItem) {
            c.selectLayer(c.doc.layers.last())
            c.addLayer()!!.textData = TextCodec.encode(item)
        }
        text(TextItem("One", cx = 100f, cy = 80f))
        text(TextItem("Two", cx = 100f, cy = 160f))
        repeat(2) { c.selectLayer(c.doc.layers.last()); c.addVectorLayer()!! }
        repeat(2) {
            c.selectLayer(c.doc.layers.last())
            c.addAdjustmentLayer(
                AdjustmentEffects.defaultSpec(),
                MaskSpec(components = listOf(LinearMask(1, x0 = 40f, y0 = 0f, x1 = 360f, y1 = 0f)), nextId = 2),
            )!!
        }
        for (story in 1L..2L) {
            val words = "Story $story"
            text(
                TextItem(
                    words,
                    spec = TextSpec(box = TextBoxSpec(width = 120f, minHeight = 40f)),
                    cx = 200f, cy = 60f * story,
                    thread = TextThreadSpec(storyId = story, index = 0, story = words, start = 0, end = words.length, rev = 1),
                ),
            )
        }
        repeat(2) { i ->
            c.selectLayer(c.doc.layers.last())
            c.addLayer()!!.shapeData = ShapeCodec.encode(ShapeObject(cx = 120f + 100f * i, cy = 220f, w = 60f, h = 40f))
        }
        c.notifyLayersChanged()
    }

    /**
     * The More menu scrolls between the top row and the bottom bar (§3.7.6; some entries are below
     * its fold): [check] gets the clickables on screen (the menu's and the editor's) at each
     * position from the menu's top to its end. Returns every menu entry seen, by node.
     */
    private fun walkMenu(s: ChromeScreen, check: (List<Clickables.Item>) -> Unit): Map<Int, Clickables.Item> {
        val decor = s.activity.window.decorView
        val seen = linkedMapOf<Int, Clickables.Item>()
        fun body() = s.placed().firstOrNull {
            it.window !== decor && it.node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) != null
        }?.node
        body()?.let { n ->
            assertEquals("the menu opens at its top", 0f, n.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), 0.5f)
        }
        for (step in 0 until 30) {
            val all = Clickables.onScreen(s)
            check(all)
            all.filter { it.window !== decor }.forEach { seen[it.node.id] = it }
            val node = body() ?: break
            val range = node.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue() - 0.5f) break
            node.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action?.invoke(0f, node.size.height * 0.6f)
            settle()
        }
        return seen
    }

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
            // v1.7 (§4.8): the three new cells sit in the last rows, below the menu's fold on a
            // phone: scrolled to its end, the menu is still unique and shows each once ("Path"
            // above is matched whole: "Pathfinder" contains it).
            val scroller = s.placed().map { it.node }.lastOrNull { n ->
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) != null &&
                    generateSequence(n) { it.parent }.any { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == ChromeTags.TOOL_MENU }
            }
            scroller?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)?.action?.invoke(0f, 10_000f)
            settle()
            val menuEnd = Clickables.onScreen(s)
            assertUnique("tool menu scrolled to its end", menuEnd, allowed = setOf("Ruler"))
            for (label in listOf("Array", "Pathfinder", "Symmetry")) {
                assertEquals("\"$label\" once at the menu's end: ${menuEnd.map { it.labels }}", 1, menuEnd.count { label in it.labels })
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
        h.section("two layers of each kind in the layer window") {
            val s = h.editor(setup = ::twoOfEachKind)
            val layers = s.c.doc.layers
            assertEquals(
                "bottom first: 2 plain, 2 text, 2 vector, 2 masked adjustments, 2 frames, 2 shapes",
                listOf("-", "-", "T", "T", "V", "V", "A", "A", "F", "F", "S", "S"),
                layers.map { l ->
                    when {
                        l.isAdjustmentLayer -> "A"
                        l.isVectorLayer -> "V"
                        l.textData?.let { TextCodec.decode(it)?.threaded } == true -> "F"
                        l.isTextLayer -> "T"
                        l.isShapeLayer -> "S"
                        else -> "-"
                    }
                },
            )
            assertTrue("the adjustments' masks are editable (spec) masks", layers.subList(6, 8).all { it.mask != null && it.maskSpec != null })
            assertEquals("Layer 10: text frame 1 of 1", LayerLabels.frameBadge(10, FrameBadge(0, 1, overset = false)))
            assertEquals("Editable mask of layer 8", LayerLabels.specMaskBadge(8))
            // Each pair on screen together (the list opens with the row above the active one
            // first): every badge names its own row, so no two rows share a label (I10).
            for ((top, badges) in listOf(
                12 to listOf(LayerLabels.badge(12, LayerLabels.SHAPE_BADGE), LayerLabels.badge(11, LayerLabels.SHAPE_BADGE)),
                10 to listOf(LayerLabels.frameBadge(10, FrameBadge(0, 1, false)), LayerLabels.frameBadge(9, FrameBadge(0, 1, false))),
                8 to listOf(
                    LayerLabels.badge(8, LayerLabels.ADJUSTMENT_BADGE), LayerLabels.badge(7, LayerLabels.ADJUSTMENT_BADGE),
                    LayerLabels.specMaskBadge(8), LayerLabels.specMaskBadge(7),
                ),
                6 to listOf(LayerLabels.badge(6, LayerLabels.VECTOR_BADGE), LayerLabels.badge(5, LayerLabels.VECTOR_BADGE)),
                4 to listOf(LayerLabels.badge(4, LayerLabels.TEXT_BADGE), LayerLabels.badge(3, LayerLabels.TEXT_BADGE)),
            )) {
                s.c.selectLayer(layers[top - 1])
                settle()
                click("Open layers")
                val window = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
                val items = Clickables.onScreen(s)
                for (badge in badges) {
                    assertEquals("\"$badge\" on one row: ${items.map { it.own }}", 1, items.count { badge in it.own })
                }
                assertUnique("layer window, layers $top and ${top - 1}", Clickables.ownLabelsInside(items, window))
                click("Close layers", exact = true)
            }
            Smoke.assertQuiet(s.c, "two of each kind")
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
        h.section("v1.7: the selection bar with a selection") {
            val s = h.editor()
            s.c.selectAll()
            settle()
            val items = Clickables.onScreen(s)
            assertUnique("selection bar", items)
            // The two v1.7 buttons sit after More (the bar scrolls to them on a phone): in the bar,
            // each known by its own description ("Array" and "Save" are only their visible texts).
            for (label in listOf(ArrayLabels.FROM_SELECTION, SavedSelectionLabels.SAVE)) {
                assertTrue("\"$label\" in the selection bar", SmokeUi.has(label, exact = true))
                assertTrue("\"$label\" at most once on screen", items.count { label in it.labels } <= 1)
            }
            assertTrue("\"Selection menu\" on one control", items.count { "Selection menu" in it.labels } == 1)
            Smoke.assertQuiet(s.c, "selection bar")
        }
        h.section("v1.7: the Ruler panel") {
            val s = h.editor()
            click("Ruler", exact = true)
            assertTrue("the Ruler panel: ${SmokeUi.shown()}", SmokeUi.has("Use ruler", exact = true))
            // (Two v1.6 controls show the length unit "px": a value they show, like "100%", not a name.)
            assertUnique("Ruler panel open", Clickables.onScreen(s), allowed = setOf("px"))
            Smoke.assertQuiet(s.c, "Ruler panel")
        }
        h.section("v1.7: the layer ⋮") {
            val s = h.editor()
            click("Open layers")
            click("More layer actions", exact = true)
            val window = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
            val decor = s.activity.window.decorView
            val whole = walkMenu(s) { all ->
                val menu = all.filter { it.window !== decor }
                assertUnique("layer ⋮", menu)
                // Against the window's controls, known by their own names (rows show values).
                val windowNames = Clickables.ownLabelsInside(all.filter { it.window === decor && window.contains(it.bounds.center) }, window)
                    .flatMap { it.labels }.toSet()
                val clash = menu.flatMap { it.labels }.filter { it in windowNames }
                assertTrue("layer ⋮ entries repeating a layer window control: $clash", clash.isEmpty())
            }
            assertUnique("the whole layer ⋮", whole.values.toList())
            for (entry in listOf(FolderLabels.PUT_IN_NEW, ArrayLabels.OPEN)) {
                assertEquals("\"$entry\" once in the layer ⋮: ${whole.values.map { it.labels }}", 1, whole.values.count { entry in it.labels })
            }
            closeMenu()
            click("Close layers", exact = true)
            Smoke.assertQuiet(s.c, "layer ⋮")
        }
        h.section("the More menu") {
            val s = h.editor()
            click("More options")
            val popup = Clickables.onScreen(s).filter { it.window !== s.activity.window.decorView }
            assertTrue("the menu is a dropdown: ${popup.size} entries in view", popup.size >= 8)
            // (The menu scrolls between the top row and the bottom bar: the last entries are below
            // its fold. Every position of it is checked, and the whole menu is counted.)
            val whole = walkMenu(s) { all -> assertUnique("More menu", all.filter { it.window !== s.activity.window.decorView }) }
            assertTrue("the menu is a dropdown: ${whole.size} entries ${whole.values.map { it.labels }}", whole.size >= 15)
            assertUnique("the whole More menu", whole.values.toList())
            for (entry in listOf("Canvas…", "Increments…", "Settings", "Export SVG…", "Fit to screen")) {
                assertTrue("\"$entry\" in the menu", SmokeUi.has(entry, exact = true))
                assertTrue("\"$entry\" at most once on screen", popup.count { entry in it.labels } <= 1)
            }
            assertTrue("\"Import picture\" in the menu without the layer window", SmokeUi.has("Import picture", exact = true))
            closeMenu()
            assertEquals(1, SmokeUi.windows().size)
            // Opened over the layer window, the menu leaves it open (like every chrome button)
            // and leaves out "Import picture": the window's own button is the only one.
            click("Open layers")
            assertNotNull(s.tagged(ChromeTags.LAYER_WINDOW))
            click("More options")
            val window = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("the layer window stays")
            val menuOpen = Clickables.onScreen(s).filter { it.window !== s.activity.window.decorView }
            assertTrue("the menu is open: ${menuOpen.size} entries in view", menuOpen.size >= 8)
            // At each position of the scrolling menu (§3.7.6).
            val wholeOver = walkMenu(s) { all ->
                val menu = all.filter { it.window !== s.activity.window.decorView }
                val editor = all.filter { it.window === s.activity.window.decorView }
                assertUnique("More menu and the chrome around the layer window", menu + editor.filter { !window.contains(it.bounds.center) })
                // Against the window's controls, known by their own names (rows show values).
                val windowNames = Clickables.ownLabelsInside(editor.filter { window.contains(it.bounds.center) }, window).flatMap { it.labels }.toSet()
                assertTrue("the window's \"Import picture\": $windowNames", "Import picture" in windowNames)
                assertTrue("not in the menu over it", menu.none { "Import picture" in it.labels })
                val clash = menu.flatMap { it.labels }.filter { it in windowNames }
                assertTrue("More entries repeating a layer window control: $clash", clash.isEmpty())
                assertEquals("\"Import picture\" on one control", 1, all.count { "Import picture" in it.labels })
            }
            assertTrue("the menu is open: ${wholeOver.size} entries ${wholeOver.values.map { it.labels }}", wholeOver.size >= 15)
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
