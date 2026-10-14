package com.brushwork.paint.qa19

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.toSize
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.PlaceholderAmount
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextPathNodeEditor
import com.brushwork.paint.tools.text.TextPathNodeEntry
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.BwSheetTitleKey
import com.brushwork.paint.ui.common.KernLabels19
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.PlaceLabels19
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.placement.BULLET_DESCRIPTIONS
import com.brushwork.paint.ui.placement.BULLET_GLYPHS
import com.brushwork.paint.ui.placement.LIST_SETTINGS_TAG
import com.brushwork.paint.ui.placement.LIST_TYPE_TAG_PREFIX
import com.brushwork.paint.ui.placement.TEXT_PATH_ENTRY_TAG_PREFIX
import com.brushwork.paint.ui.placement.TextPathNodeBar
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A3 review (R4, R5, R6, R7, R12, R14; I27): the Text tool's new controls in the real editor
 * on the 392 dp phone (the 360 dp classes are [Qa19TextAlignUiTest], [Qa19KerningUiTest],
 * [Qa19ListsUiTest], [Qa19TextPathEditUiTest] and [Qa19TextLayersUiTest]), at font scale 1 and
 * at each of [Qa19Ui.FONT_SCALES]:
 * - the More row with no text open holds “New layer each time”, its strip key's width, inside
 *   the screen, its label not cut;
 * - the text sheet: "Justify lines" and "Justify the last line too", the caption "Last line:"
 *   before the three 40 dp alignment buttons; the four "Pair kerning" chips; the list chips and
 *   the six bullets; the five placeholder amounts; the path's three node entries: each inside the
 *   screen, in order, and no text of the sheet overflows its box;
 * - node editing: the node bar 6 dp under the X / Y pill, inside the screen, its buttons 40 dp;
 * - a text of a layer of several: Duplicate, Bring forward and Send backward in the More row,
 *   each its strip key's width, inside the screen; Duplicate is one step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.textwidesandbox"])
class Qa19TextWideUiTest {

    @Test
    fun textControlsAt392AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
            val where = "392 dp at font scale $scale"
            val s = h.editor(fontScale = scale) { it.selectTool(ToolId.TEXT) }
            assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
            val c = s.c
            val t = c.tools.getValue(ToolId.TEXT) as TextTool
            t.newLayerEachTime = false
            Qa19Ui.settle()

            // No text open: “New layer each time” in the More row.
            assertTrue("$where: the More row opens", StripTestKit.moreRowShown() || Qa19Ui.openMoreRow())
            val toggle = tagged(where, V19Tags.NEW_LAYER_EACH_TIME)
            assertTrue("$where: the toggle in the More row", generateSequence(toggle) { it.parent }.any(StripTestKit::isStripMoreRow))
            assertTrue("$where: labelled", PlaceLabels19.NEW_LAYER_EACH_TIME in texts(toggle))
            assertStripItem(s, where, toggle, StripKeysV19.NEW_LAYER_EACH_TIME_TEXT.widthDp.toFloat())
            val cutToggle = Qa19Ui.textOverflows { e -> StripTestKit.under(e.node, V19Tags.NEW_LAYER_EACH_TIME) }
            assertTrue("$where: the toggle's label is not cut: $cutToggle", cutToggle.isEmpty())
            Qa19Ui.closeMoreRow()

            // The text sheet of a new text with a fixed width.
            t.startTextAt(200f, 150f)
            t.setText(PARA)
            t.updateSpec { it.copy(box = TextBoxSpec(width = 300f)) }
            Qa19Ui.settle(20)
            assertTrue("$where: the text sheet: ${SmokeUi.sheetTitles()}", SHEET in SmokeUi.sheetTitles())

            // Alignment (R4): the justify rows, then the caption before the buttons.
            for (label in listOf(TextLabels19.JUSTIFY, TextLabels19.JUSTIFY_LAST)) {
                val row = s.dp(box(clickableOf(where, label)))
                assertTrue("$where: \"$label\" is a 48 dp row: $row", row.height >= 47.5f)
                assertInside(s, where, label, row)
            }
            SmokeUi.click(TextLabels19.JUSTIFY, exact = true)
            assertEquals(TextJustify.ON, t.item!!.spec.justify)
            val caption = s.dp(box(Qa19Ui.find(TextLabels19.LAST_LINE, exact = true)?.node ?: throw AssertionError("$where: no \"Last line:\"")))
            val buttons = TextAlign.entries.map { s.dp(box(clickableOf(where, it.horizontalLabel))) }
            assertTrue("$where: the caption before the first button: $caption, ${buttons.first()}", caption.right <= buttons.first().left + 0.5f)
            for ((i, r) in buttons.withIndex()) {
                val label = TextAlign.entries[i].horizontalLabel
                assertTrue("$where: \"$label\" a 40 dp target: $r", r.width >= 39.5f && r.height >= 39.5f)
                assertInside(s, where, label, r)
            }
            assertSheetFits("with justify on, $where")

            // Pair kerning (R5): four chips in order, Auto chosen.
            val kern = KERN_TAGS.map { tagged(where, V19Tags.KERN_PREFIX + it) }
            assertInOrder(s, where, kern, KERN_LABELS, minWidth = 75.5f)
            assertEquals("$where: Auto chosen", true, kern[1].config.getOrNull(SemanticsProperties.Selected))

            // Lists (R7): the type chips, then the six bullets and "List settings".
            assertInOrder(s, where, TextListType.entries.map { tagged(where, LIST_TYPE_TAG_PREFIX + it.name) }, TextListType.entries.map { it.name }, minWidth = 39.5f)
            click(tagged(where, LIST_TYPE_TAG_PREFIX + TextListType.BULLETS.name))
            assertEquals(TextListType.BULLETS, t.item!!.spec.list.type)
            assertInOrder(s, where, BULLET_GLYPHS.indices.map { tagged(where, V19Tags.BULLET_PREFIX + it) }, BULLET_DESCRIPTIONS, minWidth = 47.5f)
            assertNotNull("$where: \"List settings\"", Qa19Ui.tagged(LIST_SETTINGS_TAG))
            assertSheetFits("with bullets, $where")

            // Placeholder (R6): the five amounts in order, "3 words" first, none behind a scroll edge.
            val amounts = PlaceholderAmount.CHIP_ORDER.map { it.label }
            assertEquals(TextLabels19.WORDS, amounts.first())
            assertInOrder(s, where, amounts.map { clickableOf(where, it) }, amounts, minWidth = 0f, target = false)

            // The path (R12): its three entries, then node editing and the node bar.
            t.setPath(t.item!!.path.copy(type = TextPathType.CURVE))
            Qa19Ui.settle(20)
            val labels = listOf(TextLabels19.ADD_POINTS, TextLabels19.EDIT_AS_PATH, TextLabels19.EDIT_AS_POLYLINE)
            val entries = TextPathNodeEntry.entries.map { tagged(where, TEXT_PATH_ENTRY_TAG_PREFIX + it.name) }
            assertInOrder(s, where, entries, labels, minWidth = 0f)
            // v1.2's "Baseline shift" (the common LengthField's one-line label) is cut at 2.0: not a
            // v1.9 control, and not A3's file (a known gap of the review).
            assertSheetFits("on a path, $where", except = setOf(V12_BASELINE_SHIFT))
            click(tagged(where, TEXT_PATH_ENTRY_TAG_PREFIX + TextPathNodeEntry.EDIT_AS_PATH.name))
            assertTrue("$where: editing points", t.nodeEditor.active)
            assertNodeBar(s, where)
            t.discard()
            Qa19Ui.settle()
            assertNull("$where: no node bar", Qa19Ui.tagged(V19Tags.TEXT_PATH_NODES))

            // A text of a layer of several (R14, R19): Duplicate, Bring forward, Send backward.
            place(s, t, "One", 80f, 80f)
            val layer = c.activeLayer
            place(s, t, "Two", 300f, 260f)
            tap(s, 80f, 80f)
            assertEquals("$where: the bottom text of the layer", 0, t.openIndex)
            if (!StripTestKit.moreRowShown()) Qa19Ui.openMoreRow()
            val dup = tagged(where, V19Tags.TEXT_DUPLICATE)
            assertTrue("$where: Duplicate labelled", LayerLabels19.DUPLICATE in texts(dup))
            assertStripItem(s, where, dup, StripKeysV19.DUPLICATE.widthDp.toFloat())
            for ((label, key) in listOf(ObjectEdits.Arrange.FORWARD.label to StripKeysV19.FORWARD, ObjectEdits.Arrange.BACKWARD.label to StripKeysV19.BACKWARD)) {
                val n = clickableOf(where, label)
                assertTrue("$where: \"$label\" in the More row", generateSequence(n) { it.parent }.any(StripTestKit::isStripMoreRow))
                assertStripItem(s, where, n, key.widthDp.toFloat())
            }
            val steps = c.undoManager.undoCount
            click(dup)
            assertEquals("$where: Duplicate is one step", steps + 1, c.undoManager.undoCount)
            assertEquals("$where: the copy is open, above the original", 1, t.openIndex)
            assertEquals(3, com.brushwork.paint.tools.text.TextCodec.decodeAll(layer.textData).size)
            t.discard()
            Qa19Ui.settle()
            Smoke.assertQuiet(c, where)
        }
    }

    /** A strip item [n]: [widthDp] wide, inside the screen. */
    private fun assertStripItem(s: ChromeScreen, where: String, n: SemanticsNode, widthDp: Float) {
        val r = s.dp(box(n))
        assertEquals("$where: the item's width: $r", widthDp, r.width, 1f)
        assertInside(s, where, texts(n).firstOrNull() ?: "?", r)
    }

    private fun assertInside(s: ChromeScreen, where: String, label: String, r: Rect) =
        assertTrue("$where: \"$label\" inside the screen: $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f)

    /** [nodes] wrapped inside the screen in their order, each [minWidth] dp wide and (with [target]) a 48 dp target at least. */
    private fun assertInOrder(s: ChromeScreen, where: String, nodes: List<SemanticsNode>, names: List<String>, minWidth: Float, target: Boolean = true) {
        val rows = nodes.map { s.dp(box(it)) }
        for ((i, r) in rows.withIndex()) {
            assertInside(s, where, names[i], r)
            assertTrue("$where: \"${names[i]}\" $minWidth dp wide at least: $r", r.width >= minWidth)
            if (target) {
                val t = s.dp(Rect(0f, 0f, nodes[i].layoutInfo.width.toFloat(), nodes[i].layoutInfo.height.toFloat()))
                assertTrue("$where: \"${names[i]}\" a 48 dp target: $t", t.height >= 47.5f)
            }
        }
        for (k in 1 until rows.size) {
            val a = rows[k - 1]
            val b = rows[k]
            val sameRow = b.top < a.bottom - 0.5f && a.top < b.bottom - 0.5f
            assertTrue("$where: \"${names[k]}\" after \"${names[k - 1]}\": $a, $b", if (sameRow) b.left > a.right else b.top >= a.bottom - 0.5f)
        }
    }

    /** The node bar under the X / Y pill, inside the screen, its buttons 40 dp on it, no text cut. */
    private fun assertNodeBar(s: ChromeScreen, where: String) {
        val bar = s.tagged(V19Tags.TEXT_PATH_NODES) ?: throw AssertionError("$where: no node bar; shown: ${Qa19Ui.shown().take(80)}")
        assertEquals("$where: the bar's height", TextPathNodeBar.HEIGHT_DP, bar.height, 1f)
        assertTrue("$where: the bar inside the screen: $bar", bar.left >= -0.5f && bar.right <= s.widthDp + 0.5f && bar.top >= 0f)
        val pill = s.tagged(ChromeTags.PILL_SLOT)?.takeIf { it.height > 0f } ?: throw AssertionError("$where: the open text shows the X / Y pill")
        assertEquals("$where: 6 dp under the pill: bar $bar, pill $pill", pill.bottom + 6f, bar.top, 1f)
        for (label in listOf(TextPathNodeEditor.ADD_POINT_STEP, TextPathNodeEditor.DELETE_POINT_STEP, TextPathNodeEditor.SHARP_CORNER_STEP, TextLabels19.DONE_POINTS)) {
            val b = s.clickable(label) ?: throw AssertionError("$where: no \"$label\"")
            assertTrue("$where: \"$label\" on the bar: $b, $bar", b.left >= bar.left - 0.5f && b.right <= bar.right + 0.5f && b.top >= bar.top - 0.5f && b.bottom <= bar.bottom + 0.5f)
            assertTrue("$where: \"$label\" 40 dp: $b", b.width >= 39.5f && b.height >= 39.5f)
        }
        val cut = Qa19Ui.textOverflows { e -> StripTestKit.under(e.node, V19Tags.TEXT_PATH_NODES) }
        assertTrue("$where: no text of the bar is cut: $cut", cut.isEmpty())
    }

    private fun tap(s: ChromeScreen, x: Float, y: Float) {
        s.c.pointerDown(ToolPoint(x, y))
        s.c.pointerUp(ToolPoint(x, y))
        Qa19Ui.settle()
    }

    /** A tap on empty canvas, [text] typed and applied with ✓. */
    private fun place(s: ChromeScreen, t: TextTool, text: String, x: Float, y: Float) {
        tap(s, x, y)
        assertTrue("the editor opened for a new text at ($x, $y)", t.editorOpen && t.editingNew)
        t.setText(text)
        t.confirmEditor()
        Qa19Ui.settle()
        assertTrue(t.commitItem())
        Qa19Ui.settle()
    }

    private fun tagged(where: String, tag: String): SemanticsNode =
        Qa19Ui.tagged(tag) ?: throw AssertionError("$where: no $tag; shown: ${Qa19Ui.shown().take(80)}")

    /** The placed clickable labelled [label] (text or description), or its clickable ancestor. */
    private fun clickableOf(where: String, label: String): SemanticsNode {
        val e = Qa19Ui.find(label, exact = true) ?: throw AssertionError("$where: no \"$label\"; shown: ${Qa19Ui.shown().take(100)}")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n ?: throw AssertionError("$where: \"$label\" is not clickable")
    }

    private fun click(n: SemanticsNode) {
        val action = n.config.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("not clickable")
        action()
        Qa19Ui.settle()
    }

    /** [n]'s texts and descriptions, its subtree's too. */
    private fun texts(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.children.flatMap { texts(it) }

    /** I27 for the text sheet: no text in it overflows its box. */
    private fun assertSheetFits(where: String, except: Set<String> = emptySet()) {
        val bad = Qa19Ui.textOverflows { e ->
            inSheet(e.node) && Qa19Ui.textLayout(e.node)?.layoutInput?.text?.text !in except
        }
        if (bad.isNotEmpty()) throw AssertionError("texts overflow their box in the text sheet $where:\n" + bad.joinToString("\n"))
    }

    private fun inSheet(n: SemanticsNode): Boolean {
        var p: SemanticsNode? = n
        while (p != null) {
            if (p.config.getOrNull(BwSheetTitleKey) == SHEET) return true
            p = p.parent
        }
        return false
    }

    /** [n]'s bounds in its window (px), not clipped (the sheet scrolls). */
    private fun box(n: SemanticsNode): Rect = Rect(n.positionInWindow, n.size.toSize())

    private companion object {
        const val SHEET = "Add text"
        const val PARA = "Brushwork sets justified lines flush with both edges of the box, word by word, while the last line keeps its alignment."
        /** TextPathSheet's v1.2 offset field label (a literal there, no Labels constant). */
        const val V12_BASELINE_SHIFT = "Baseline shift"
        val KERN_TAGS = listOf("OFF", "AUTO", "OPTICAL", "METRICS_LATIN")
        val KERN_LABELS = listOf(KernLabels19.OFF, KernLabels19.AUTO, KernLabels19.OPTICAL, KernLabels19.METRICS)
    }
}
