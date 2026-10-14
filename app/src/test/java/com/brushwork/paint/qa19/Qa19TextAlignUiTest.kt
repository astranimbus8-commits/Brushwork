package com.brushwork.paint.qa19

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.toSize
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.PlaceholderAmount
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.BwSheetTitleKey
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A3 step 2 (R4, R6; I27): the text sheet's Alignment section in the real editor on the
 * 360 dp phone, at font scale 1 and at each of [Qa19Ui.FONT_SCALES]:
 * - "Justify lines" and "Justify the last line too" sit under the three alignment buttons, each a
 *   full-width row at least 48 dp tall; the second is disabled until the first is on;
 * - with "Justify lines" on the caption "Last line:" stands before the buttons, which then read
 *   "Last line: Left" (and so on); with "Justify the last line too" on the buttons are disabled;
 *   every button stays a 40 dp target inside the sheet;
 * - vertical text shows its "Column alignment" buttons without the justify rows (step 12);
 * - the five placeholder amounts ("3 words" first, "Fill the box" last) wrap inside the sheet
 *   instead of hiding behind a scroll edge (R6);
 * - no text on screen overflows its box in any of these states.
 * 360 dp is the narrower of the two phones: the 392 dp sheet has more room for the same rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.textalignsandbox"])
class Qa19TextAlignUiTest {

    @Test
    fun justifyRowsAndTheLastLineAt360AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
            val where = "360 dp at font scale $scale"
            val s = h.editor(fontScale = scale) { it.selectTool(ToolId.TEXT) }
            assertEquals("the phone is 360 dp wide", 360f, s.widthDp, 1f)
            val t = s.c.tools.getValue(ToolId.TEXT) as TextTool
            t.startTextAt(200f, 150f)
            t.setText(PARA)
            // A fixed width: justified lines spread, and "Fill the box" is offered.
            t.updateSpec { it.copy(box = TextBoxSpec(width = 300f)) }
            Qa19Ui.settle(20)
            assertTrue("$where: the text sheet: ${SmokeUi.sheetTitles()}", SHEET in SmokeUi.sheetTitles())

            // Off: no caption, the buttons as v1.8, the second row disabled.
            assertFalse("$where: no \"Last line:\" while justify is off", Qa19Ui.has(TextLabels19.LAST_LINE, exact = true))
            assertTrue(Qa19Ui.has(TextLabels19.JUSTIFY, exact = true))
            assertTrue(SmokeUi.isEnabled(TextLabels19.JUSTIFY))
            assertFalse("$where: \"${TextLabels19.JUSTIFY_LAST}\" waits for \"${TextLabels19.JUSTIFY}\"", SmokeUi.isEnabled(TextLabels19.JUSTIFY_LAST))
            for (label in listOf(TextLabels19.JUSTIFY, TextLabels19.JUSTIFY_LAST)) {
                val row = s.dp(box(clickableOf(label)))
                assertTrue("$where: \"$label\" is a 48 dp row: $row", row.height >= 47.5f)
                assertTrue("$where: \"$label\" inside the screen: $row", row.left >= -0.5f && row.right <= s.widthDp + 0.5f)
            }
            assertButtons(s, where, lastLine = false, enabled = true)
            assertSheetFits("with justify off, $where")
            assertChips(s, where)

            // "Justify lines": the caption, the buttons name the last line.
            SmokeUi.click(TextLabels19.JUSTIFY, exact = true)
            assertEquals(TextJustify.ON, t.item!!.spec.justify)
            assertTrue("$where: \"Last line:\" before the buttons", Qa19Ui.has(TextLabels19.LAST_LINE, exact = true))
            assertTrue(SmokeUi.isEnabled(TextLabels19.JUSTIFY_LAST))
            assertButtons(s, where, lastLine = true, enabled = true)
            val caption = s.dp(box(Qa19Ui.find(TextLabels19.LAST_LINE, exact = true)!!.node))
            val first = s.dp(box(buttons().first()))
            assertTrue("$where: the caption before the first button: $caption, $first", caption.right <= first.left + 0.5f)
            assertSheetFits("with justify on, $where")
            // The buttons still set the last line's alignment.
            SmokeUi.click(TextAlign.END.horizontalLabel, exact = true)
            assertEquals(TextAlign.END, t.item!!.spec.align)
            assertEquals(TextJustify.ON, t.item!!.spec.justify)

            // "Justify the last line too": the buttons change nothing, so they are disabled.
            SmokeUi.click(TextLabels19.JUSTIFY_LAST, exact = true)
            assertEquals(TextJustify.ALL, t.item!!.spec.justify)
            assertButtons(s, where, lastLine = true, enabled = false)
            assertSheetFits("with the last line justified too, $where")
            // Off again: the second row turns off with the first.
            SmokeUi.click(TextLabels19.JUSTIFY, exact = true)
            assertEquals(TextJustify.OFF, t.item!!.spec.justify)
            assertFalse(SmokeUi.isEnabled(TextLabels19.JUSTIFY_LAST))
            assertButtons(s, where, lastLine = false, enabled = true)

            // Vertical text: its column buttons, no justify rows until step 12.
            t.updateSpec { it.copy(vertical = true, justify = TextJustify.ON) }
            Qa19Ui.settle()
            assertTrue("$where: the column alignment header", Qa19Ui.has("Column alignment".uppercase(), exact = true))
            assertFalse("$where: vertical text shows no justify row", Qa19Ui.has(TextLabels19.JUSTIFY, exact = true))
            assertFalse(Qa19Ui.has(TextLabels19.LAST_LINE, exact = true))
            assertTrue(Qa19Ui.has(TextAlign.START.verticalLabel, exact = true))
            assertSheetFits("vertical text, $where")

            t.cancelEditor()
            Qa19Ui.settle()
            Smoke.assertQuiet(s.c, where)
        }
    }

    /** The three alignment buttons: each 40 dp at least, inside the screen, named for the last line when [lastLine], enabled as [enabled]. */
    private fun assertButtons(s: ChromeScreen, where: String, lastLine: Boolean, enabled: Boolean) {
        val labels = TextAlign.entries.map { it.horizontalLabel }
        for (label in labels) {
            val n = clickableOf(label)
            val r = s.dp(box(n))
            assertTrue("$where: \"$label\" is a 40 dp target: $r", r.width >= 39.5f && r.height >= 39.5f)
            assertTrue("$where: \"$label\" inside the screen: $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f)
            assertEquals("$where: \"$label\" enabled", enabled, !n.config.contains(SemanticsProperties.Disabled))
            val state = n.config.getOrNull(SemanticsProperties.StateDescription)
            if (lastLine) {
                assertEquals("$where: \"$label\" names the last line", "${TextLabels19.LAST_LINE} $label", state)
            } else {
                assertFalse("$where: \"$label\" names no last line: $state", state?.startsWith(TextLabels19.LAST_LINE) == true)
            }
        }
    }

    /** The five placeholder amounts, in their order, wrapped inside the screen: none behind a scroll edge (R6). */
    private fun assertChips(s: ChromeScreen, where: String) {
        val order = PlaceholderAmount.CHIP_ORDER.map { it.label }
        assertEquals(TextLabels19.WORDS, order.first())
        for (label in order) {
            val r = s.dp(box(clickableOf(label)))
            assertTrue("$where: \"$label\" inside the screen: $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f)
        }
        val rows = order.map { s.dp(box(clickableOf(it))) }
        for (k in 1 until rows.size) {
            val a = rows[k - 1]
            val b = rows[k]
            assertTrue("$where: \"${order[k]}\" after \"${order[k - 1]}\": $a, $b", b.top > a.top + 0.5f || (kotlin.math.abs(b.top - a.top) < 0.5f && b.left > a.right))
        }
    }

    /** The placed clickable labelled [label] (text or description), or its clickable ancestor. */
    private fun clickableOf(label: String): SemanticsNode {
        val e = Qa19Ui.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${Qa19Ui.shown().take(100)}")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n ?: throw AssertionError("\"$label\" is not clickable")
    }

    /**
     * I27 for the text sheet ("Add text"): no text in it overflows its box ([Qa19Ui.textOverflows]
     * of the nodes under the sheet's panel; the Text strip behind it is the strip's own check).
     * Until step 3 the v1.7 Kerning field's own label ("Kerning" in a 72 dp field) is left out:
     * step 3 gives it room and checks it in `Qa19KerningUiTest`.
     */
    private fun assertSheetFits(where: String) {
        val bad = Qa19Ui.textOverflows { e -> inSheet(e.node) && e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } != KerningLabels.KERNING }
        if (bad.isNotEmpty()) throw AssertionError("texts overflow their box in the text sheet $where:\n" + bad.joinToString("\n"))
    }

    /** Whether [n] lies in the "Add text" sheet's panel. */
    private fun inSheet(n: SemanticsNode): Boolean {
        var p: SemanticsNode? = n
        while (p != null) {
            if (p.config.getOrNull(BwSheetTitleKey) == SHEET) return true
            p = p.parent
        }
        return false
    }

    /**
     * [n]'s bounds in its window (px), not clipped: the sheet scrolls, and a row below its fold
     * has empty clipped bounds while it still has its size and place.
     */
    private fun box(n: SemanticsNode): Rect = Rect(n.positionInWindow, n.size.toSize())

    /** The alignment buttons, left to right. */
    private fun buttons(): List<SemanticsNode> = TextAlign.entries.map { clickableOf(it.horizontalLabel) }

    private companion object {
        const val SHEET = "Add text"
        const val PARA = "Brushwork sets justified lines flush with both edges of the box, word by word, while the last line keeps its alignment."
    }
}
