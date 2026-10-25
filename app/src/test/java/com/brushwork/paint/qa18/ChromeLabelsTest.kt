package com.brushwork.paint.qa18

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.remove.ContentAwareFillJob
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.MenuLabels18
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.tools.FakePillTool
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
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
 * v1.8 I10 (labels are an API, design §3.9 item 9) over the chrome area H changed, on the user's
 * phone (392 × 873 dp), with real touch events:
 * - every control keeps its v1.7 name and visible text, in its v1.7 order: the top row's eight
 *   circles ("More options" under its three-dots glyph), the selection bar's ten items (and v1.9's
 *   "Duplicate" after them), the object
 *   bar's nine buttons plus "Detach mirror copies"; the v1.8 toggles carry `StripLabels18`'s
 *   names and states ("Show more tool options", "Show more object actions", "Expanded" /
 *   "Collapsed");
 * - every long-press bubble H added says exactly its control's name (the click label a screen
 *   reader reads, or its description) and does nothing else: the selection bar's items not
 *   pressed in [TopRowLongPressTest], the object bar's buttons and toggle, the strip's toggle and
 *   VECTOR cell, and the X / Y pill's ✥, trash and "Keep scale proportions" cells. Recolor keeps
 *   its own v1.6 long press ("Recolor the lines only") instead;
 * - the More menu's section headers are names, not controls: no tap reaches them, they are
 *   headings, and no control on screen goes by a header's name, so a lookup by label still finds
 *   the entry it found in v1.7.
 *
 * v1.10 (A6, R10, R11): the one selected path also shows [ObjectBarLabels110.EDIT] after Transform
 * (row 1: eight buttons at 392 dp) and [ObjectBarLabels110.TO_SHAPE] after Detach on the More row;
 * both keep their names, and their bubbles say them, like the v1.7 buttons.
 *
 * v1.10 (A3, R4, UQ4): [ArrayLabels110.BEHIND] follows "Array" in the selection bar and on the
 * object bar's More row, with [ArrayLabels110.BEHIND_DESC] as its name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.chromelabelssandbox"])
class ChromeLabelsTest {

    /** The top row's circles, left to right (v1.6 I10). */
    private val circles = listOf("Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options")

    /**
     * The selection bar with a selection: (click label, visible text), left to right (v1.7), with
     * v1.9's "Duplicate" after "Save" (R14, design 2.8: the v1.7 items keep their order).
     */
    private val selectionBar = listOf(
        "Copy selection" to "Copy", "Cut selection" to "Cut", "Paste as a new layer" to "Paste",
        "Clear the selection" to "Deselect", "Delete the selected pixels" to "Clear", ContentAwareFillJob.FILL_LABEL to "Content\naware",
        "Invert the selection" to "Invert", "Selection menu" to "More",
        ArrayLabels.FROM_SELECTION to ArrayLabels.BUTTON,
        ArrayLabels110.BEHIND_DESC to ArrayLabels110.BEHIND,
        SavedSelectionLabels.SAVE to SavedSelectionLabels.SAVE_BUTTON,
        LayerLabels19.DUPLICATE_DESC to LayerLabels19.DUPLICATE,
    )

    /** The object bar's row 1 at 392 dp: (click label, visible text), left to right. */
    private val objectRow1 = listOf(
        "Delete the selected objects" to "Delete", "Duplicate the selected objects" to "Duplicate",
        "Bring to front" to "Front", "Send to back" to "Back", "Recolor with the main color" to "Recolor",
        "Transform the selected objects" to "Transform", ObjectBarLabels110.EDIT_DESC to ObjectBarLabels110.EDIT,
        "Deselect the objects" to "Deselect",
    )

    /** Its More row, with a selected path that has mirror copies. */
    private val objectMore = listOf(
        "Bring forward" to "Forward", "Send backward" to "Backward",
        ArrayLabels.FROM_OBJECTS to ArrayLabels.BUTTON,
        ArrayLabels110.BEHIND_DESC to ArrayLabels110.BEHIND,
        MirrorLabels18.DETACH to MirrorLabels18.DETACH,
        ObjectBarLabels110.TO_SHAPE to ObjectBarLabels110.TO_SHAPE,
    )

    private val sections = listOf(MenuLabels18.CLIPBOARD, MenuLabels18.BRING_IN, MenuLabels18.SEND_OUT, MenuLabels18.VIEW, MenuLabels18.THIS_ARTWORK)

    /** The More menu's entries, top to bottom, without a selection (v1.7 labels and order). */
    private val menuEntries = listOf(
        "Copy layer", "Paste", "Import picture", "Import SVG or PDF…", "Export PNG", "Export JPG", "Export SVG…", "Export PDF…", "Share",
        "Flip view", "Fit to screen", "100% (actual pixels)", "Reset rotation", "Canvas…", "Increments…", "Save now", "Settings",
    )

    @Test
    fun labelsStayAndBubblesSayThem() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        val kit = ObjectTestKit(400, 300)
        h.section("the top row and the More menu's headers") { topRowAndMenu(h) }
        h.section("the selection bar") { selectionBar(h) }
        h.section("the object bar") { objectBar(h, kit) }
        h.section("the strip's toggle and VECTOR cell") { strip() }
        h.section("the X / Y pill") { pill(h) }
        kit.close()
        dog.interrupt()
        h.finish()
    }

    // ------------------------------------------------------------------ semantics

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    private fun SemanticsNode.isClickable() = config.getOrNull(SemanticsActions.OnClick) != null

    private fun SemanticsNode.subtree(): Sequence<SemanticsNode> = sequenceOf(this) + children.asSequence().flatMap { it.subtree() }

    /** The node tagged [tag] (the last placed one). */
    private fun tagged(s: ChromeScreen, tag: String): SemanticsNode =
        s.placed().lastOrNull { it.node.tag() == tag }?.node ?: throw AssertionError("nothing tagged $tag")

    /** The clickables under [root], in tree order (left to right in a row), not looking inside a clickable. */
    private fun clickablesUnder(root: SemanticsNode): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            for (c in n.children) if (c.isClickable()) out += c else walk(c)
        }
        walk(root)
        return out
    }

    /** What a screen reader calls [n]: its click label, else the first description in it. */
    private fun nameOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsActions.OnClick)?.label
        ?: n.subtree().firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    /** The first text shown in [n]. */
    private fun textOf(n: SemanticsNode): String? = n.subtree().firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    // ------------------------------------------------------------------ long presses

    /** Long-presses [label]'s control: the bubble says [label] while the finger is down. */
    private fun bubbleSays(s: ChromeScreen, label: String) {
        var shown: String? = null
        Fingers18.longPress(s, label) { shown = Fingers18.bubble() }
        assertEquals("the long press on \"$label\" shows its name", label, shown)
    }

    /** What any of the pressed controls would change if a long press acted. */
    private data class Snapshot(
        val undo: Int, val redo: Boolean, val vector: Boolean, val tool: Any, val layers: Int, val clip: Any?,
        val saved: Int, val windows: Int, val sheets: List<String>, val pills: List<String>,
    )

    private fun snapshot(s: ChromeScreen) = Snapshot(
        s.c.undoManager.undoCount, s.c.canRedo, s.c.isVectorMode, s.c.activeToolId, s.c.doc.layers.size, s.c.clipboard,
        s.c.doc.savedSelections.size, SmokeUi.windows().size, SmokeUi.sheetTitles(), SmokeUi.pillTitles(),
    )

    // ------------------------------------------------------------------ sections

    private fun topRowAndMenu(h: ChromeHarness) {
        val s = h.editor()
        val top = clickablesUnder(tagged(s, ChromeTags.TOP_ROW))
        assertEquals("the top row's circles keep their names and order", circles, top.map { nameOf(it) })

        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        val windows = SmokeUi.windows()
        assertEquals("the More menu drops", 2, windows.size)
        val popup = windows.last()
        val inMenu = RobolectricUi.elements().filter { it.window === popup }.map { it.node }
        val root = inMenu.firstOrNull { it.parent == null || inMenu.none { m -> m.id == it.parent?.id } }
            ?: throw AssertionError("no menu root")
        val entries = clickablesUnder(root)
        assertEquals("the entries keep their v1.7 labels and order", menuEntries, entries.map { textOf(it) })

        val headers = inMenu.filter { it.tag()?.startsWith(V18Tags.MENU_SECTION_PREFIX) == true }.sortedBy { it.positionInWindow.y }
        assertEquals("one header per section, in order", sections, headers.map { it.tag()!!.removePrefix(V18Tags.MENU_SECTION_PREFIX) })
        // Every clickable's names, the menu's scrolled-out entries included.
        val names = (Clickables.onScreen(s).flatMap { it.labels } + entries.flatMap { e -> listOfNotNull(nameOf(e), textOf(e)) }).toSet()
        for ((name, header) in sections.zip(headers)) {
            assertEquals("\"$name\" shows its name", name, textOf(header))
            assertTrue("\"$name\" is a heading", header.subtree().any { it.config.getOrNull(SemanticsProperties.Heading) != null })
            var n: SemanticsNode? = header
            while (n != null) {
                assertFalse("\"$name\" takes no tap", n.isClickable())
                n = n.parent
            }
            assertFalse("no control is called \"$name\"", name in names)
            assertNull("a lookup by \"$name\" finds no control", Finger.control(s, name))
        }
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)
        Smoke.assertQuiet(s.c, "top row and menu labels")
    }

    private fun selectionBar(h: ChromeHarness) {
        val s = h.editor { c -> c.selectAll() }
        val c = s.c
        val items = clickablesUnder(tagged(s, ChromeTags.SELECTION_BAR))
        assertEquals("the selection bar keeps its v1.7 click labels and order", selectionBar.map { it.first }, items.map { nameOf(it) })
        assertEquals("…and its visible texts", selectionBar.map { it.second }, items.map { textOf(it) })

        // The items TopRowLongPressTest does not press; Paste is disabled (nothing copied yet).
        val selection = c.selection
        val before = snapshot(s)
        for (label in listOf("Cut selection", "Paste as a new layer", "Clear the selection", "Delete the selected pixels", ContentAwareFillJob.FILL_LABEL)) {
            bubbleSays(s, label)
            assertEquals("$label: a long press does nothing else", before, snapshot(s))
            assertTrue("$label: the selection is untouched", c.selection === selection)
        }
        // The last two v1.7 items (and v1.9's Duplicate) sit past the bar's end at 392 dp: scrolled
        // into view, as a finger does.
        val scroller = tagged(s, ChromeTags.SELECTION_BAR).subtree().first { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        scroller.config[SemanticsActions.ScrollBy].action!!.invoke(10_000f, 0f)
        Smoke.pump(300); SmokeUi.settle()
        for (label in listOf(ArrayLabels.FROM_SELECTION, SavedSelectionLabels.SAVE)) {
            val r = Finger.control(s, label) ?: throw AssertionError("no \"$label\"")
            assertTrue("\"$label\" in view: $r", r.width >= 40f - 0.5f)
            bubbleSays(s, label)
            assertEquals("$label: a long press does nothing else", before, snapshot(s))
            assertTrue("$label: the selection is untouched", c.selection === selection)
        }
        Smoke.assertQuiet(c, "selection bar labels")
    }

    private fun objectBar(h: ChromeHarness, kit: ObjectTestKit) {
        val doc: Document = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 1
        }
        val s = h.editor(doc) { c ->
            val layer = c.doc.layers[1]
            val mirror = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), 400, 300))
            c.vectors.addObjects(layer, listOf(ObjectCopies.withCopies(kit.box(40f, 40f, 120f, 110f), mirror), kit.stroke(60f, 200f, 200f, 230f)), "Add")
            c.vectors.setSelection(layer, setOf(1L))
        }
        val c = s.c
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        SmokeUi.settle()

        // ---- names, texts and states
        var items = clickablesUnder(tagged(s, ChromeTags.SELECTION_BAR))
        assertEquals("row 1 keeps its v1.6 click labels, then the toggle", objectRow1.map { it.first } + StripLabels18.MORE_OBJECT, items.map { nameOf(it) })
        assertEquals("…and its visible texts", objectRow1.map { it.second }, items.dropLast(1).map { textOf(it) })
        assertEquals(StripLabels18.COLLAPSED, Fingers18.state(s, StripLabels18.MORE_OBJECT))
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        assertEquals(StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_OBJECT))
        items = clickablesUnder(tagged(s, ChromeTags.SELECTION_BAR))
        assertEquals(
            "the More row's buttons keep theirs",
            (objectRow1 + objectMore).map { it.first } + StripLabels18.MORE_OBJECT,
            items.map { nameOf(it) }.let { it.take(objectRow1.size) + it.drop(objectRow1.size + 1) + it[objectRow1.size] },
        )
        assertEquals("…and their texts", objectMore.map { it.second }, items.drop(objectRow1.size + 1).map { textOf(it) })
        val recolor = items.first { nameOf(it) == "Recolor with the main color" }
        assertEquals("Recolor keeps its own long press", "Recolor the lines only", recolor.config.getOrNull(SemanticsActions.OnLongClick)?.label)

        // ---- bubbles: every button but Recolor, and the toggle; the row stays open, nothing acts
        val layer = c.doc.layers[1]
        val objects = layer.vector!!.objects
        val selected = c.vectors.selectedIds.toSet()
        val before = snapshot(s)
        for (label in (objectRow1 + objectMore).map { it.first }.filter { it != "Recolor with the main color" } + StripLabels18.MORE_OBJECT) {
            bubbleSays(s, label)
            assertEquals("$label: a long press does nothing else", before, snapshot(s))
            assertEquals("$label: the objects are untouched", objects, c.doc.layers[1].vector!!.objects)
            assertEquals("$label: so is the selection", selected, c.vectors.selectedIds.toSet())
            assertEquals("$label: the More row stays open", StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_OBJECT))
        }
        Smoke.assertQuiet(c, "object bar labels")
    }

    private fun strip() {
        val doc = Smoke.document(400, 300, layers = 2, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 2
        }
        FakeStripEditor.open(doc, FakeStrips.spec(1)).use { e ->
            val s = e.screen
            assertTrue("vector mode", s.c.isVectorMode)
            val before = snapshot(s)
            // The VECTOR cell is no control: the finger goes on the cell itself.
            val cell = Fingers18.described(s, "Vector mode is on") ?: throw AssertionError("no VECTOR cell")
            assertFalse("the VECTOR cell takes no tap", cell.isClickable())
            var shown: String? = null
            Fingers18.longPressAt(s, s.dp(cell.boundsInWindow)) { shown = Fingers18.bubble() }
            assertEquals("the VECTOR cell's bubble", "Vector mode is on", shown)
            assertEquals("…and nothing else", before, snapshot(s))

            for (open in listOf(false, true)) {
                val state = if (open) StripLabels18.EXPANDED else StripLabels18.COLLAPSED
                assertEquals(state, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
                bubbleSays(s, StripLabels18.MORE_TOGGLE)
                assertEquals("a long press never toggles", state, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
                assertEquals(open, s.tagged(V18Tags.STRIP_MORE_ROW) != null)
                assertEquals(before, snapshot(s))
                if (!open) Finger.tap(s, StripLabels18.MORE_TOGGLE)
            }
            Smoke.assertQuiet(s.c, "strip labels")
        }
    }

    private fun pill(h: ChromeHarness) {
        lateinit var fake: FakePillTool
        val s = h.editor { c ->
            fake = FakePillTool(c, listOf(Vec2(40f, 40f), Vec2(120f, 40f), Vec2(120f, 100f), Vec2(40f, 100f)))
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = fake
            c.selectTool(ToolId.CURVE)
        }
        val c = s.c
        SmokeUi.settle()
        assertNotNull("the pill shows", s.tagged(ChromeTags.PILL_SLOT))
        assertFalse(c.settings.coordinateStripFolded)
        fun keep() = Fingers18.described(s, PillLabels.KEEP_PROPORTIONS)?.config?.getOrNull(SemanticsProperties.ToggleableState)
        assertEquals("proportions kept", ToggleableState.On, keep())
        val points = fake.points
        val before = snapshot(s)
        for (label in listOf("Fold the X / Y strip", PillLabels.deleteObject("curve"), PillLabels.KEEP_PROPORTIONS)) {
            bubbleSays(s, label)
            assertEquals("$label: a long press does nothing else", before, snapshot(s))
            assertFalse("$label: the pill stays unfolded", c.settings.coordinateStripFolded)
            assertEquals("$label: nothing deleted", 0, fake.deleteCalls)
            assertEquals("$label: nothing moved", points, fake.points)
            assertEquals("$label: proportions still kept", ToggleableState.On, keep())
        }
        Smoke.assertQuiet(c, "pill labels")
    }
}
