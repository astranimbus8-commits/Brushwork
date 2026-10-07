package com.brushwork.paint.ui.placement

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.7 §3.17d (item 17, area D): the text editor's Kerning row and "Font kerning" switch, on a
 * 392 dp phone. The row sits under "Letter spacing"; the text field's cursor or selection names
 * the gaps it edits, its caption names the letters, a selection of differing gaps shows "Mixed"
 * (−/+ move each gap, a typed number sets them all, also after leaving the text field), and
 * vertical text disables it saying why. Controls are found by their labels and the row's tag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.placement.kerningrowsandbox"])
class TextKerningRowTest {

    private val kerning = KerningLabels.KERNING
    private val decrease = "Decrease $kerning"
    private val increase = "Increase $kerning"

    @Test
    fun theKerningRowEditsTheGapsTheTextFieldNames() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(512, 384))
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(256f, 192f)
        tool.setText("AVATAR")
        activity.setContent { BrushworkTheme { TextEditorDialog(tool) } }
        SmokeUi.settle(20, 50)
        val dp = activity.resources.displayMetrics.density

        // The row, under "Letter spacing", with "Font kerning" (on by default).
        val row = byTag(KERNING_ROW_TAG)
        val spacing = SmokeUi.find("Letter spacing", exact = true) ?: throw AssertionError("no Letter spacing; shown: ${SmokeUi.shown().take(80)}")
        assertTrue("Kerning under Letter spacing", row.node.positionInRoot.y > spacing.node.positionInRoot.y)
        assertTrue(SmokeUi.has(KerningLabels.FONT_KERNING, exact = true))
        // The cursor at the end of the text names no gap: the row says how to pick one.
        assertTrue(SmokeUi.has(KERNING_HINT, exact = true))
        assertFalse(SmokeUi.isEnabled(increase))

        // The cursor between A and V.
        select(1, 1)
        assertTrue("caption: ${SmokeUi.shown().take(80)}", SmokeUi.has(KerningLabels.between("A", "V"), exact = true))
        assertTrue(SmokeUi.isEnabled(increase))
        // 392 dp: a 96 dp label, 40 dp steppers, a 72 dp field, all inside the screen.
        assertWidth("label", 96f * dp, rowLabel(row).node.size.width.toFloat())
        assertWidth("−", 40f * dp, clickable(decrease).size.width.toFloat())
        assertWidth("+", 40f * dp, clickable(increase).size.width.toFloat())
        assertWidth("field", 72f * dp, SmokeUi.field(kerning).node.size.width.toFloat())
        val unit = SmokeUi.find(KERNING_UNIT, exact = true)?.node ?: throw AssertionError("no unit")
        val rowRight = row.node.positionInRoot.x + row.node.size.width
        assertTrue("the unit fits in the row", unit.positionInRoot.x + unit.size.width <= rowRight + 0.5f)
        assertTrue("the row fits on the screen", rowRight <= activity.resources.displayMetrics.widthPixels + 0.5f)

        SmokeUi.click(increase, exact = true)
        assertEquals(listOf(TextKern(0, 10)), tool.item!!.kerns)
        SmokeUi.typeAndDone(kerning, "-80")
        assertEquals(listOf(TextKern(0, -80)), tool.item!!.kerns)

        // "AVA" selected: the gaps A|V (−80) and V|A (none) differ.
        select(0, 3)
        assertTrue(SmokeUi.has(KerningLabels.between("A", "A"), exact = true))
        assertTrue("mixed: ${SmokeUi.shown().take(80)}", SmokeUi.has(PointLabels.MIXED, exact = true))
        assertEquals("", SmokeUi.field(kerning).text)
        assertWidth("mixed −", 40f * dp, clickable(decrease).size.width.toFloat())
        assertWidth("mixed field", 72f * dp, SmokeUi.field(kerning).node.size.width.toFloat())
        // −/+ move each gap by 10.
        SmokeUi.click(increase, exact = true)
        assertEquals(listOf(TextKern(0, -70), TextKern(1, 10)), tool.item!!.kerns)
        // A typed number sets both, although leaving the text field collapsed its selection.
        SmokeUi.typeAndDone(kerning, "25")
        assertEquals(listOf(TextKern(0, 25), TextKern(1, 25)), tool.item!!.kerns)
        assertFalse(SmokeUi.has(PointLabels.MIXED, exact = true))

        // Typing keeps the kerns with their letters: "x" typed before "AVA" moves them by one.
        select(0, 0)
        SmokeUi.field("Text").type("xAVATAR")
        SmokeUi.settle()
        assertEquals("xAVATAR", tool.item!!.text)
        assertEquals(listOf(TextKern(1, 25), TextKern(2, 25)), tool.item!!.kerns)

        // Font kerning switches off and on.
        assertTrue(tool.item!!.spec.fontKerning)
        SmokeUi.click(KerningLabels.FONT_KERNING, exact = true)
        assertFalse(tool.item!!.spec.fontKerning)
        SmokeUi.click(KerningLabels.FONT_KERNING, exact = true)
        assertTrue(tool.item!!.spec.fontKerning)

        // Vertical text: disabled, saying why.
        select(2, 2)
        tool.updateSpec { it.copy(vertical = true) }
        SmokeUi.settle()
        assertTrue(SmokeUi.has(KerningLabels.VERTICAL_REFUSAL, exact = true))
        assertFalse(SmokeUi.isEnabled(increase))
        assertTrue("its kerns are kept", tool.item!!.kerns.isNotEmpty())
    }

    /** Focuses the text field and selects [start, end) in it (a cursor when equal). */
    private fun select(start: Int, end: Int) {
        SmokeUi.field("Text").focus()
        SmokeUi.settle(2)
        val set = SmokeUi.field("Text").node.config.getOrNull(SemanticsActions.SetSelection)?.action
            ?: throw AssertionError("the text field has no selection action")
        set(start, end, false)
        SmokeUi.settle()
    }

    private fun byTag(tag: String): RobolectricUi.Element =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag && it.node.layoutInfo.isPlaced }
            ?: throw AssertionError("no element tagged $tag; shown: ${SmokeUi.shown().take(80)}")

    /** The row's own "Kerning" label (the field's floating label is another "Kerning"). */
    private fun rowLabel(row: RobolectricUi.Element): RobolectricUi.Element =
        RobolectricUi.elements().filter { e ->
            e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == kerning } == true && abs(e.node.positionInRoot.x - row.node.positionInRoot.x) < 1f
        }.lastOrNull() ?: throw AssertionError("no row label")

    /** The clickable node of the stepper described [label] (its icon carries the description). */
    private fun clickable(label: String): SemanticsNode {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node ?: throw AssertionError("no $label")
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n ?: throw AssertionError("$label is not clickable")
    }

    private fun assertWidth(what: String, expected: Float, actual: Float) =
        assertTrue("$what: $actual px, expected $expected", abs(actual - expected) <= 1.5f)
}
