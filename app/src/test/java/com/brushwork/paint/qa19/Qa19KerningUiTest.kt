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
import com.brushwork.paint.tools.text.TextKerningMode
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.BwSheetTitleKey
import com.brushwork.paint.ui.common.KernLabels19
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A3 step 3 (R5; I27): "Pair kerning" in the text sheet of the real editor on the 360 dp
 * phone, at font scale 1 and at each of [Qa19Ui.FONT_SCALES]:
 * - under the v1.7 "Font kerning" switch (its label kept) the chips "Off", "Auto", "Optical" and
 *   "Metrics" (tags [V19Tags.KERN_PREFIX] + the mode) wrap inside the screen, in order, each at
 *   least 76 dp wide and a 48 dp touch target; the chosen chip's note is under them (none for Off);
 * - "Off" and the switch are one setting: Off turns the switch off and keeps the mode, the switch
 *   turned on brings it back, another chip turns the switch on with its mode;
 * - vertical text says the modes need horizontal text;
 * - the v1.7 Kerning field fills its row, so its label fits, and no text in the sheet overflows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.kerningsandbox"])
class Qa19KerningUiTest {

    @Test
    fun pairKerningChipsAndTheSwitchAt360AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
            val where = "360 dp at font scale $scale"
            val s = h.editor(fontScale = scale) { it.selectTool(ToolId.TEXT) }
            assertEquals("the phone is 360 dp wide", 360f, s.widthDp, 1f)
            val t = s.c.tools.getValue(ToolId.TEXT) as TextTool
            t.startTextAt(200f, 150f)
            t.setText("AVATAR テキスト")
            Qa19Ui.settle(20)
            assertTrue("$where: the text sheet: ${SmokeUi.sheetTitles()}", SHEET in SmokeUi.sheetTitles())

            // Auto at first, with the switch on.
            assertTrue(Qa19Ui.has(KerningLabels.FONT_KERNING, exact = true))
            assertTrue("$where: the \"Pair kerning\" label", Qa19Ui.has(KernLabels19.PAIR_KERNING, exact = true))
            assertChips(s, where, selected = 1)
            assertTrue("$where: Auto's note", Qa19Ui.has(KernLabels19.AUTO_NOTE, exact = true))
            val switch = s.dp(box(clickableOf(KerningLabels.FONT_KERNING)))
            val off = s.dp(box(chip(0)))
            assertTrue("$where: the chips under the switch: $switch, $off", off.top >= switch.bottom - 0.5f)
            assertKerningRow(s, where)
            assertSheetFits("with Auto, $where")

            // Metrics: the switch stays on, Metrics' note.
            click(3)
            assertEquals(TextKerningMode.METRICS_LATIN, t.item!!.spec.kerningMode)
            assertTrue(t.item!!.spec.fontKerning)
            assertChips(s, where, selected = 3)
            assertTrue("$where: Metrics' note", Qa19Ui.has(KernLabels19.METRICS_NOTE, exact = true))
            assertFalse(Qa19Ui.has(KernLabels19.AUTO_NOTE, exact = true))
            assertSheetFits("with Metrics, $where")

            // Off: the switch turns off, the mode is kept, no note.
            click(0)
            assertFalse("$where: Off turns Font kerning off", t.item!!.spec.fontKerning)
            assertEquals("$where: Off keeps the mode", TextKerningMode.METRICS_LATIN, t.item!!.spec.kerningMode)
            assertEquals(false, switchOn())
            assertChips(s, where, selected = 0)
            assertFalse(Qa19Ui.has(KernLabels19.METRICS_NOTE, exact = true))
            assertSheetFits("with Off, $where")

            // The switch on again: the last mode comes back.
            SmokeUi.click(KerningLabels.FONT_KERNING, exact = true)
            assertTrue(t.item!!.spec.fontKerning)
            assertChips(s, where, selected = 3)
            // The switch off selects Off; Optical turns it on with its note.
            SmokeUi.click(KerningLabels.FONT_KERNING, exact = true)
            assertChips(s, where, selected = 0)
            click(2)
            assertEquals(true, switchOn())
            assertEquals(TextKerningMode.OPTICAL, t.item!!.spec.kerningMode)
            assertTrue("$where: Optical's note", Qa19Ui.has(KernLabels19.OPTICAL_NOTE, exact = true))
            assertSheetFits("with Optical, $where")
            click(1)
            assertNull(t.item!!.spec.kerningMode)
            assertTrue(t.item!!.spec.fontKerning)

            // Vertical text: the modes need horizontal text.
            click(3)
            t.updateSpec { it.copy(vertical = true) }
            Qa19Ui.settle()
            assertTrue("$where: vertical text says why", Qa19Ui.has(KerningLabels.VERTICAL_REFUSAL, exact = true))
            assertFalse(Qa19Ui.has(KernLabels19.METRICS_NOTE, exact = true))
            assertChips(s, where, selected = 3)
            assertSheetFits("vertical text, $where")

            t.cancelEditor()
            Qa19Ui.settle()
            Smoke.assertQuiet(s.c, where)
        }
    }

    /** The four chips, in order, wrapped inside the screen, each 76 dp wide at least and a 48 dp target; [selected] chosen. */
    private fun assertChips(s: ChromeScreen, where: String, selected: Int) {
        val rows = (0 until 4).map { s.dp(box(chip(it))) }
        for ((i, r) in rows.withIndex()) {
            val label = LABELS[i]
            assertTrue("$where: \"$label\" inside the screen: $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f)
            assertTrue("$where: \"$label\" 76 dp wide at least: $r", r.width >= 75.5f)
            // The chip draws 32 dp tall; its layout (the touch target around it) is 48 dp.
            val target = s.dp(Rect(0f, 0f, chip(i).layoutInfo.width.toFloat(), chip(i).layoutInfo.height.toFloat()))
            assertTrue("$where: \"$label\" a 48 dp target: $target", target.height >= 47.5f)
            assertEquals("$where: \"$label\" chosen", i == selected, chip(i).config.getOrNull(SemanticsProperties.Selected) == true)
        }
        for (k in 1 until rows.size) {
            val a = rows[k - 1]
            val b = rows[k]
            assertTrue("$where: \"${LABELS[k]}\" after \"${LABELS[k - 1]}\": $a, $b", b.top > a.top + 0.5f || (kotlin.math.abs(b.top - a.top) < 0.5f && b.left > a.right))
        }
    }

    /**
     * The v1.7 Kerning row: the field fills it (its label fits), the unit after it, inside the
     * screen. The cursor after the last letter names no gap, so the field is disabled (no text
     * action): it is found as the row's editable text.
     */
    private fun assertKerningRow(s: ChromeScreen, where: String) {
        val rowNode = Qa19Ui.tagged(com.brushwork.paint.ui.placement.KERNING_ROW_TAG) ?: throw AssertionError("$where: no Kerning row")
        val row = s.dp(box(rowNode))
        assertTrue("$where: the Kerning row inside the screen: $row", row.left >= -0.5f && row.right <= s.widthDp + 0.5f)
        val fieldNode = editableIn(rowNode) ?: throw AssertionError("$where: no Kerning field in its row")
        val field = s.dp(box(fieldNode))
        assertTrue("$where: the Kerning field is 72 dp wide at least: $field", field.width >= 72f)
        assertTrue("$where: the field is labelled \"${KerningLabels.KERNING}\"", Qa19Ui.has(KerningLabels.KERNING, exact = true))
    }

    /** The first text field in [n]'s subtree (enabled or not). */
    private fun editableIn(n: SemanticsNode): SemanticsNode? {
        if (n.config.getOrNull(SemanticsProperties.EditableText) != null) return n
        for (c in n.children) editableIn(c)?.let { return it }
        return null
    }

    /** The chip [i] (0 Off, 1 Auto, 2 Optical, 3 Metrics), found by its tag. */
    private fun chip(i: Int): SemanticsNode = Qa19Ui.tagged(V19Tags.KERN_PREFIX + TAGS[i]) ?: throw AssertionError("no chip ${TAGS[i]}; shown: ${Qa19Ui.shown().take(100)}")

    /** Clicks chip [i] and lets the sheet settle. */
    private fun click(i: Int) {
        val action = chip(i).config.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("chip ${TAGS[i]} is not clickable")
        action()
        Qa19Ui.settle()
    }

    /** The "Font kerning" switch's state (its row is the toggleable). */
    private fun switchOn(): Boolean? = clickableOf(KerningLabels.FONT_KERNING).config.getOrNull(SemanticsProperties.ToggleableState)?.let { it == androidx.compose.ui.state.ToggleableState.On }

    /** The placed clickable labelled [label] (text or description), or its clickable ancestor. */
    private fun clickableOf(label: String): SemanticsNode {
        val e = Qa19Ui.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${Qa19Ui.shown().take(100)}")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n ?: throw AssertionError("\"$label\" is not clickable")
    }

    /** I27 for the text sheet: no text in it overflows its box (the Text strip behind it is the strip's own check). */
    private fun assertSheetFits(where: String) {
        val bad = Qa19Ui.textOverflows { e -> inSheet(e.node) }
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

    /** [n]'s bounds in its window (px), not clipped (the sheet scrolls). */
    private fun box(n: SemanticsNode): Rect = Rect(n.positionInWindow, n.size.toSize())

    private companion object {
        const val SHEET = "Add text"
        val TAGS = listOf("OFF", "AUTO", "OPTICAL", "METRICS_LATIN")
        val LABELS = listOf(KernLabels19.OFF, KernLabels19.AUTO, KernLabels19.OPTICAL, KernLabels19.METRICS)
    }
}
