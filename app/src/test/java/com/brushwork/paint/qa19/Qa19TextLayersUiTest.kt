package com.brushwork.paint.qa19

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.toSize
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.PlaceLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.placement.TextStripItems
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.vector.select.ObjectEdits
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
 * v1.9 A3 step 7 (R14 a; I27): “New layer each time” in the Text tool's More row of the real
 * editor on the 360 dp phone, at font scale 1 and at each of [Qa19Ui.FONT_SCALES]:
 * - while no text is open, the More row holds it (tag [V19Tags.NEW_LAYER_EACH_TIME]; before
 *   Snap, which still ends the row as in v1.8, see [TextStripItems]), a checkbox-like toggle, off, [StripKeysV19.NEW_LAYER_EACH_TIME_TEXT] wide, inside the screen,
 *   its label not cut (it grows up to [TextStripItems.NEW_LAYER_MAX_FONT_SCALE]);
 * - a tap turns it on (the setting too) and off again;
 * - off, a tap on empty canvas adds the new text to the active text layer; on, it gets a layer
 *   of its own;
 * - while a text is open the toggle is gone and Duplicate ([V19Tags.TEXT_DUPLICATE],
 *   [StripKeysV19.DUPLICATE] wide) is in the More row, with Bring forward and Send backward for a
 *   text of a layer of several (step 8); Duplicate copies the open text in one step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.textlayerssandbox"])
class Qa19TextLayersUiTest {

    @Test
    fun newLayerEachTimeAt360AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
            val where = "360 dp at font scale $scale"
            val s = h.editor(fontScale = scale) { it.selectTool(ToolId.TEXT) }
            val c = s.c
            val t = c.tools.getValue(ToolId.TEXT) as TextTool
            t.newLayerEachTime = false
            Qa19Ui.settle()

            // No text open: the toggle in the More row, off.
            assertTrue("$where: the More row opens", StripTestKit.moreRowShown() || Qa19Ui.openMoreRow())
            val toggle = toggleNode(where)
            assertTrue("$where: in the More row", generateSequence(toggle) { it.parent }.any(StripTestKit::isStripMoreRow))
            assertEquals("$where: a toggle", Role.Checkbox, toggle.config.getOrNull(SemanticsProperties.Role))
            assertEquals("$where: off by default", ToggleableState.Off, toggle.config.getOrNull(SemanticsProperties.ToggleableState))
            assertTrue("$where: labelled", labels(toggle).any { it == PlaceLabels19.NEW_LAYER_EACH_TIME })
            val r = s.dp(box(tagged(where)))
            assertEquals("$where: its width", StripKeysV19.NEW_LAYER_EACH_TIME_TEXT.widthDp.toFloat(), r.width, 1f)
            assertTrue("$where: inside the screen: $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f)
            val cut = Qa19Ui.textOverflows { e -> StripTestKit.under(e.node, V19Tags.NEW_LAYER_EACH_TIME) }
            assertTrue("$where: the label is not cut: $cut", cut.isEmpty())
            val laid = textIn(tagged(where))?.let { Qa19Ui.textLayout(it) } ?: throw AssertionError("$where: the label is not laid out")
            assertEquals("$where: the label grows with the font scale up to its cap", minOf(scale, TextStripItems.NEW_LAYER_MAX_FONT_SCALE), laid.layoutInput.density.fontScale, 0.01f)

            // A tap turns it on, and off again.
            click(toggle)
            assertTrue("$where: on", t.newLayerEachTime && c.settings.textNewLayerEachTime)
            assertEquals(ToggleableState.On, toggleNode(where).config.getOrNull(SemanticsProperties.ToggleableState))
            click(toggleNode(where))
            assertFalse("$where: off", t.newLayerEachTime || c.settings.textNewLayerEachTime)

            // Off: the second text joins the first one's layer; on: a layer of its own.
            val layers = c.doc.layers.size
            place(s, t, "One", 60f, 60f)
            val first = c.activeLayer
            assertEquals(layers + 1, c.doc.layers.size)
            place(s, t, "Two", 300f, 240f)
            assertEquals("$where: joined", layers + 1, c.doc.layers.size)
            assertSame(first, c.activeLayer)
            assertEquals(listOf("One", "Two"), TextCodec.decodeAll(first.textData).map { it.text })
            if (!StripTestKit.moreRowShown()) Qa19Ui.openMoreRow()
            click(toggleNode(where))
            place(s, t, "Three", 200f, 150f)
            assertEquals("$where: a layer of its own", layers + 2, c.doc.layers.size)
            assertEquals(listOf("Three"), TextCodec.decodeAll(c.activeLayer.textData).map { it.text })

            // A text open: no toggle; Duplicate in the More row (alone in its layer: no arrange items).
            tap(s, 200f, 150f)
            assertNotNull(t.item)
            if (!StripTestKit.moreRowShown()) Qa19Ui.openMoreRow()
            assertNull("$where: no toggle while a text is open", Qa19Ui.tagged(V19Tags.NEW_LAYER_EACH_TIME))
            val dup = duplicateNode(where)
            assertTrue("$where: Duplicate in the More row", generateSequence(dup) { it.parent }.any(StripTestKit::isStripMoreRow))
            val dr = s.dp(box(dup))
            assertEquals("$where: Duplicate's width", StripKeysV19.DUPLICATE.widthDp.toFloat(), dr.width, 1f)
            assertTrue("$where: Duplicate inside the screen: $dr", dr.left >= -0.5f && dr.right <= s.widthDp + 0.5f)
            assertTrue("$where: labelled", labels(dup).any { it == LayerLabels19.DUPLICATE })
            assertFalse("$where: no arrange items for a text alone", Qa19Ui.has(ObjectEdits.Arrange.FORWARD.label, exact = true))
            t.discard()
            Qa19Ui.settle()

            // A text of a layer of several: Bring forward and Send backward too; Duplicate copies it.
            tap(s, 60f, 60f)
            assertSame("$where: the bottom text of the joined layer", first, t.editingLayer)
            assertEquals(0, t.openIndex)
            if (!StripTestKit.moreRowShown()) Qa19Ui.openMoreRow()
            assertTrue("$where: Bring forward", Qa19Ui.has(ObjectEdits.Arrange.FORWARD.label, exact = true))
            assertTrue("$where: Send backward", Qa19Ui.has(ObjectEdits.Arrange.BACKWARD.label, exact = true))
            val steps = c.undoManager.undoCount
            click(duplicateNode(where))
            assertEquals("$where: one step", steps + 1, c.undoManager.undoCount)
            assertEquals(listOf("One", "One", "Two"), TextCodec.decodeAll(first.textData).map { it.text })
            assertEquals("$where: the copy is open", 1, t.openIndex)
            t.discard()
            Qa19Ui.settle()
            t.newLayerEachTime = false
            Smoke.assertQuiet(c, where)
        }
    }

    private fun tap(s: com.brushwork.paint.ui.editor.chrome.ChromeScreen, x: Float, y: Float) {
        s.c.pointerDown(ToolPoint(x, y))
        s.c.pointerUp(ToolPoint(x, y))
        Qa19Ui.settle()
    }

    /** A tap on empty canvas, [text] typed and applied with ✓. */
    private fun place(s: com.brushwork.paint.ui.editor.chrome.ChromeScreen, t: TextTool, text: String, x: Float, y: Float) {
        tap(s, x, y)
        assertTrue("the editor opened for a new text at ($x, $y)", t.editorOpen && t.editingNew)
        t.setText(text)
        t.confirmEditor()
        Qa19Ui.settle()
        assertTrue(t.commitItem())
        Qa19Ui.settle()
    }

    private fun duplicateNode(where: String): SemanticsNode =
        Qa19Ui.tagged(V19Tags.TEXT_DUPLICATE) ?: throw AssertionError("$where: no Duplicate; shown: ${Qa19Ui.shown().take(80)}")

    private fun tagged(where: String): SemanticsNode =
        Qa19Ui.tagged(V19Tags.NEW_LAYER_EACH_TIME) ?: throw AssertionError("$where: no “New layer each time”; shown: ${Qa19Ui.shown().take(80)}")

    /** The toggle itself: the node under the tag that has a toggle state. */
    private fun toggleNode(where: String): SemanticsNode {
        fun find(n: SemanticsNode): SemanticsNode? {
            if (n.config.getOrNull(SemanticsProperties.ToggleableState) != null) return n
            for (k in n.children) find(k)?.let { return it }
            return null
        }
        return find(tagged(where)) ?: throw AssertionError("$where: “New layer each time” is not a toggle")
    }

    /** The first node with laid-out text in [n]'s subtree. */
    private fun textIn(n: SemanticsNode): SemanticsNode? {
        if (n.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) return n
        for (k in n.children) textIn(k)?.let { return it }
        return null
    }

    private fun labels(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.children.flatMap { labels(it) }

    private fun click(n: SemanticsNode) {
        val action = n.config.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("not clickable")
        action()
        Qa19Ui.settle()
    }

    /** [n]'s bounds in its window (px). */
    private fun box(n: SemanticsNode): Rect = Rect(n.positionInWindow, n.size.toSize())
}
