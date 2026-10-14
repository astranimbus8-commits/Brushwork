package com.brushwork.paint.qa19

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.toSize
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextPathNodeEditor
import com.brushwork.paint.tools.text.TextPathNodeEntry
import com.brushwork.paint.tools.text.TextPathNodes
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.placement.TEXT_PATH_ENTRY_TAG_PREFIX
import com.brushwork.paint.ui.placement.TextPathNodeBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A3 step 10 (R12; I27): the node editing of a text path in the real editor on the 360 dp
 * phone, at font scale 1 and at each of [Qa19Ui.FONT_SCALES]:
 * - the text sheet's path settings show "Add points", "Edit as path" and "Edit as polyline" in
 *   that order (tags [TEXT_PATH_ENTRY_TAG_PREFIX] + the entry), inside the screen, 48 dp targets;
 * - each entry closes the sheet, starts the editing and shows TextLabels19.NODES_STARTED;
 * - the node bar ([V19Tags.TEXT_PATH_NODES]) is [TextPathNodeBar.HEIGHT_DP] high, inside the
 *   screen, 6 dp under the X / Y pill (where the selection bar goes); its buttons are labelled,
 *   40 dp, and its count reads "Points: n"; the hint shows under it; no strip text is cut;
 * - "Add point" adds a node as one step, ✓ ("Done editing points") ends the editing and the
 *   text sheet shows the point count instead of the shape's numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.textpathsandbox"])
class Qa19TextPathEditUiTest {

    @Test
    fun nodeEditingAt360AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
            val where = "360 dp at font scale $scale"
            val s = h.editor(fontScale = scale) { it.selectTool(ToolId.TEXT) }
            assertEquals("the phone is 360 dp wide", 360f, s.widthDp, 1f)
            val c = s.c
            val t = c.tools.getValue(ToolId.TEXT) as TextTool

            for (entry in TextPathNodeEntry.entries) {
                val w = "$where, ${entry.name}"
                openCurvedText(t)
                assertEntries(s, w)

                // The entry closes the sheet and starts the editing, with its toast (the editor
                // moves a message into its snackbar on its next frame: read it before that).
                c.message = null
                val pick = tagged(w, TEXT_PATH_ENTRY_TAG_PREFIX + entry.name).config.getOrNull(SemanticsActions.OnClick)?.action
                    ?: throw AssertionError("$w: the entry is not clickable")
                pick()
                assertEquals("$w: the toast", TextLabels19.NODES_STARTED, c.message)
                Qa19Ui.settle()
                assertFalse("$w: the sheet closed", t.editorOpen)
                assertTrue("$w: editing points", t.nodeEditor.active)
                assertSame(t.nodeEditor, c.inToolHistory)
                assertTrue(
                    "$w: the toast shows; shown: ${Qa19Ui.shown().take(40)}",
                    Smoke.pumpUntil(5_000) { Qa19Ui.settle(1); Qa19Ui.has(TextLabels19.NODES_STARTED, exact = true) },
                )
                val nodes = TextPathNodes.of(t.item!!.path)
                assertNotNull("$w: the guide is points", nodes)
                assertEquals("$w: polyline", entry == TextPathNodeEntry.EDIT_AS_POLYLINE, TextPathNodes.isPolyline(nodes!!))

                assertNodeBar(s, w, t.nodeEditor.count)
                t.discard()
                Qa19Ui.settle()
                assertFalse("$w: discarding ends the editing", t.nodeEditor.active)
                assertNull("$w: no node bar", Qa19Ui.tagged(V19Tags.TEXT_PATH_NODES))
            }

            // Add point is one step; ✓ ends the editing and the sheet shows the point count.
            openCurvedText(t)
            click(tagged(where, TEXT_PATH_ENTRY_TAG_PREFIX + TextPathNodeEntry.EDIT_AS_PATH.name))
            val before = t.nodeEditor.count
            // A finger on "Add point" (the bar lies outside the strip: the touch must reach it).
            val add = clickableNode(where, TextPathNodeEditor.ADD_POINT_STEP).boundsInWindow.center
            val steps = c.undoManager.undoCount
            s.touch.idle(300)
            s.touch.tap(add.x, add.y)
            Qa19Ui.settle()
            assertEquals("$where: a point added", before + 1, t.nodeEditor.count)
            assertEquals("$where: no document step", steps, c.undoManager.undoCount)
            assertTrue("$where: the count shown", Qa19Ui.has(String.format(TextLabels19.POINT_COUNT, before + 1), exact = true))
            assertEquals("$where: one in-tool step", TextPathNodeEditor.ADD_POINT_STEP, t.nodeEditor.undoLabel)
            Qa19Ui.click(TextLabels19.DONE_POINTS, exact = true)
            assertFalse("$where: done", t.nodeEditor.active)
            assertNull(c.inToolHistory)
            assertTrue("$where: back to the text sheet", t.editorOpen)
            assertNull("$where: no node bar", Qa19Ui.tagged(V19Tags.TEXT_PATH_NODES))
            assertTrue("$where: the sheet counts the points", Qa19Ui.has(String.format(TextLabels19.POINT_COUNT, before + 1), exact = true))
            t.cancelEditor()
            t.discard()
            Qa19Ui.settle()
            Smoke.assertQuiet(c, where)
        }
    }

    /** A new text along a curve, its sheet open. */
    private fun openCurvedText(t: TextTool) {
        t.startTextAt(180f, 200f)
        t.setText("Along the path")
        t.setPath(t.item!!.path.copy(type = TextPathType.CURVE))
        Qa19Ui.settle(20)
        assertTrue("the text sheet is open", t.editorOpen)
    }

    /** The three entries in order, "Add points" first, inside the screen, each labelled and a 48 dp target. */
    private fun assertEntries(s: ChromeScreen, where: String) {
        val labels = listOf(TextLabels19.ADD_POINTS, TextLabels19.EDIT_AS_PATH, TextLabels19.EDIT_AS_POLYLINE)
        val nodes = TextPathNodeEntry.entries.map { tagged(where, TEXT_PATH_ENTRY_TAG_PREFIX + it.name) }
        assertEquals("$where: ADD_POINTS first", TextPathNodeEntry.ADD_POINTS, TextPathNodeEntry.entries.first())
        val boxes = nodes.map { s.dp(box(it)) }
        for ((i, n) in nodes.withIndex()) {
            assertTrue("$where: \"${labels[i]}\" labelled", labels[i] in texts(n))
            val r = boxes[i]
            assertTrue("$where: \"${labels[i]}\" inside the screen: $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f)
            assertTrue("$where: \"${labels[i]}\" a 48 dp target: $r", r.height >= 47.5f)
        }
        for (k in 1 until boxes.size) {
            val a = boxes[k - 1]
            val b = boxes[k]
            val sameRow = b.top < a.bottom - 0.5f && a.top < b.bottom - 0.5f
            assertTrue("$where: \"${labels[k]}\" after \"${labels[k - 1]}\": $a, $b", if (sameRow) b.left > a.right else b.top >= a.bottom - 0.5f)
        }
        val cut = Qa19Ui.textOverflows { e -> labels.any { l -> texts(e.node).contains(l) } }
        assertTrue("$where: no entry label is cut: $cut", cut.isEmpty())
    }

    /** The node bar: its place under the pill, its height, its buttons and count, the hint. */
    private fun assertNodeBar(s: ChromeScreen, where: String, count: Int) {
        val bar = s.tagged(V19Tags.TEXT_PATH_NODES) ?: throw AssertionError("$where: no node bar; shown: ${Qa19Ui.shown().take(80)}")
        assertEquals("$where: the bar's height", TextPathNodeBar.HEIGHT_DP, bar.height, 1f)
        assertTrue("$where: inside the screen: $bar", bar.left >= -0.5f && bar.right <= s.widthDp + 0.5f && bar.top >= 0f)
        val pill = s.tagged(ChromeTags.PILL_SLOT)?.takeIf { it.height > 0f } ?: throw AssertionError("$where: the open text shows the X / Y pill")
        assertEquals("$where: 6 dp under the pill (where the selection bar goes): bar $bar, pill $pill", pill.bottom + 6f, bar.top, 1f)
        for (label in listOf(TextPathNodeEditor.ADD_POINT_STEP, TextPathNodeEditor.DELETE_POINT_STEP, TextPathNodeEditor.SHARP_CORNER_STEP, TextLabels19.DONE_POINTS)) {
            val b = s.clickable(label) ?: throw AssertionError("$where: no \"$label\" on the node bar")
            assertTrue("$where: \"$label\" on the bar: $b, $bar", b.left >= bar.left - 0.5f && b.right <= bar.right + 0.5f && b.top >= bar.top - 0.5f && b.bottom <= bar.bottom + 0.5f)
            assertTrue("$where: \"$label\" 40 dp: $b", b.width >= 39.5f && b.height >= 39.5f)
        }
        assertTrue("$where: the count", Qa19Ui.has(String.format(TextLabels19.POINT_COUNT, count), exact = true))
        assertTrue("$where: the hint", Qa19Ui.has(TextLabels19.NODES_HINT, exact = true))
        val cut = Qa19Ui.textOverflows { e -> generateSequence(e.node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == V19Tags.TEXT_PATH_NODES } }
        assertTrue("$where: no text of the bar is cut: $cut", cut.isEmpty())
    }

    /** The clickable labelled [label] (its own label or a child's). */
    private fun clickableNode(where: String, label: String): SemanticsNode {
        var n: SemanticsNode? = Qa19Ui.find(label, exact = true)?.node ?: throw AssertionError("$where: no \"$label\"")
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n ?: throw AssertionError("$where: \"$label\" is not clickable")
    }

    private fun tagged(where: String, tag: String): SemanticsNode =
        Qa19Ui.tagged(tag) ?: throw AssertionError("$where: no $tag; shown: ${Qa19Ui.shown().take(80)}")

    /** [n]'s texts and descriptions, its subtree's too. */
    private fun texts(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.children.flatMap { texts(it) }

    private fun click(n: SemanticsNode) {
        val action = n.config.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("not clickable")
        action()
        Qa19Ui.settle()
    }

    /** [n]'s bounds in its window (px), not clipped (the sheet scrolls). */
    private fun box(n: SemanticsNode): Rect = Rect(n.positionInWindow, n.size.toSize())
}
