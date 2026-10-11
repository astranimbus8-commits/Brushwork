package com.brushwork.paint.ui.array

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.RED
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.filters.GradientEditing
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
 * v1.8 (area B, item 2, design §3.3 a) on a narrow 360 dp phone, by label and by finger: the
 * Array sheet's "Placement" | "Look" switch, both tabs' controls wholly in view (fields side by
 * side, each at least 64 dp wide, inside the screen), and the Look tab's controls each recording
 * ONE step "Array look": a typed End opacity, End size and End turn, the "Slows down" chip, a
 * Tint colors preset (its strength set to 100 % with it), a typed Tint strength, "Reset look";
 * undone with two fingers. "Speeds up" tapped with no look records nothing yet stays lit, and the
 * first field takes it. The tab is kept while the sheet closes and reopens.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.array.arraylooksheetsandbox"])
class ArrayLookSheetUiTest {

    @Test
    fun bothTabsAt360Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("the Placement and Look tabs at 360 dp") { tabs(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    /** A segment, chip or button [label]: scrolled wholly into view, at least [minDp], inside the screen. */
    private fun reachable(u: Qa17ArrayUi, label: String, minDp: Float = 32f) {
        u.ui.reach(label, minDp)
        assertTrue("\"$label\" wholly on the 360 dp screen", u.ui.wholly(label, minDp))
    }

    /** A number field [label] scrolled wholly into view: inside the screen sideways, at least 64 dp wide. Its bounds. */
    private fun field(u: Qa17ArrayUi, label: String): android.graphics.RectF {
        val n = u.intoView(label) { SmokeUi.field(label).node }
        val b = n.boundsInWindow
        val r = u.s.root
        assertTrue("\"$label\" inside the screen sideways: $b of $r", b.left >= r.left - 0.5f && b.right <= r.right + 0.5f)
        assertTrue("\"$label\" at least 64 dp wide: ${b.width / u.s.density}", b.width >= 64f * u.s.density)
        return android.graphics.RectF(b.left, b.top, b.right, b.bottom)
    }

    /** Start and End side by side: the same row, Start on the left. */
    private fun pair(u: Qa17ArrayUi, start: String, end: String) {
        val e = field(u, end)
        val s = field(u, start)
        assertEquals("\"$start\" and \"$end\" on one row", s.top, e.top, 2f * u.s.density)
        assertTrue("\"$start\" left of \"$end\"", s.right <= e.left + 0.5f)
    }

    /** True when the chip [label] is the chosen one (its text, or the chip around it, says Selected). */
    private fun selected(label: String): Boolean {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.Selected)?.let { return it }
            n = n.parent
        }
        return false
    }

    /** Runs [block] and asserts it recorded ONE step "Array look". */
    private fun lookStep(u: Qa17ArrayUi, what: String, block: () -> Unit) {
        val n = u.steps()
        block()
        u.settleRenders(what)
        assertEquals("$what: one step", n + 1, u.steps())
        assertEquals(what, ArrayLabels18.HISTORY_LOOK, u.c.undoManager.undoLabel)
    }

    private fun tabs(u: Qa17ArrayUi) {
        u.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), widthDp = 360f)
        val c = u.c
        u.seed(c.activeLayer, 40f, 40f, 90f, 90f, RED)
        c.setSelection(u.rectSelection(36f, 36f, 94f, 94f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val layer = c.activeLayer
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
        u.showArraySheet()

        // Placement: the switch, then the v1.7 sheet as it was.
        reachable(u, ArrayLabels18.PLACEMENT, 44f)
        reachable(u, ArrayLabels18.LOOK, 44f)
        assertEquals(ArrayTool.SheetTab.PLACEMENT, tool.sheetTab)
        for (m in listOf(ArrayLabels.LINE, ArrayLabels.CIRCLE, ArrayLabels.CURVE, ArrayLabels.TRANSFORM)) reachable(u, m)
        field(u, "Count")
        assertNull("no Look controls on Placement", u.s.tagged(V18Tags.ARRAY_LOOK))

        // Look: the caption, the three pairs, Pace, Tint, Reset, and the footer.
        u.press(ArrayLabels18.LOOK, 44f)
        assertEquals(ArrayTool.SheetTab.LOOK, tool.sheetTab)
        assertNotNull("the Look tab", u.s.tagged(V18Tags.ARRAY_LOOK))
        assertFalse("no mode segments on Look", SmokeUi.has(ArrayLabels.LINE, exact = true))
        assertTrue(SmokeUi.has(ArrayLabels18.LOOK_CAPTION, exact = true))
        pair(u, ArrayLabels18.START_OPACITY, ArrayLabels18.END_OPACITY)
        pair(u, ArrayLabels18.START_SIZE, ArrayLabels18.END_SIZE)
        pair(u, ArrayLabels18.START_TURN, ArrayLabels18.END_TURN)
        for (p in listOf(ArrayLabels18.EVEN, ArrayLabels18.SPEEDS_UP, ArrayLabels18.SLOWS_DOWN, ArrayLabels18.S_CURVE)) reachable(u, p)
        u.intoView(GRADIENT) { requireNotNull(SmokeUi.find(GRADIENT, exact = true)) { "no \"$GRADIENT\"" }.node }
        field(u, ArrayLabels18.TINT_STRENGTH)
        reachable(u, ArrayLabels18.RESET_LOOK)
        reachable(u, ArrayLabels.REMOVE)
        reachable(u, ArrayLabels.APPLY)

        // A Pace with no look (review fix): no step, as it changes no copy, but the chip stays lit
        // and the next field starts from it.
        assertTrue("Even at first", selected(ArrayLabels18.EVEN))
        val alone = u.steps()
        u.fingerPress(ArrayLabels18.SPEEDS_UP)
        u.settleRenders("a Pace alone")
        assertEquals("a Pace alone records nothing", alone, u.steps())
        assertNull("a Pace alone is no look", layer.array!!.spec.look)
        assertTrue("\"Speeds up\" stays lit", selected(ArrayLabels18.SPEEDS_UP))
        assertFalse(selected(ArrayLabels18.EVEN))

        // Each control: one step "Array look".
        lookStep(u, ArrayLabels18.END_OPACITY) { u.typeField(ArrayLabels18.END_OPACITY, "40") }
        assertEquals(0.4f, layer.array!!.spec.look!!.opacityEnd, 1e-4f)
        assertEquals("the Pace tapped first", ArrayEasing.EASE_IN, layer.array!!.spec.look!!.easing)
        lookStep(u, ArrayLabels18.END_SIZE) { u.typeField(ArrayLabels18.END_SIZE, "150") }
        assertEquals(1.5f, layer.array!!.spec.look!!.sizeEnd, 1e-4f)
        lookStep(u, ArrayLabels18.END_TURN) { u.typeField(ArrayLabels18.END_TURN, "30") }
        assertEquals(30f, layer.array!!.spec.look!!.turnEnd, 1e-4f)
        lookStep(u, ArrayLabels18.SLOWS_DOWN) { u.fingerPress(ArrayLabels18.SLOWS_DOWN) }
        assertEquals(ArrayEasing.EASE_OUT, layer.array!!.spec.look!!.easing)
        assertTrue(selected(ArrayLabels18.SLOWS_DOWN))
        val sunset = GradientEditing.presets.first { it.first == SUNSET }.second
        lookStep(u, SUNSET) { u.press(SUNSET) }
        assertEquals("the preset's stops", sunset.map { it.position to it.color }, layer.array!!.spec.look!!.tint.map { it.offset to it.color })
        assertEquals("colors chosen at strength 0 show at 100 %", 1f, layer.array!!.spec.look!!.tintStrength, 1e-4f)
        lookStep(u, ArrayLabels18.TINT_STRENGTH) { u.typeField(ArrayLabels18.TINT_STRENGTH, "60") }
        assertEquals(0.6f, layer.array!!.spec.look!!.tintStrength, 1e-4f)
        assertEquals("the placement untouched", ArrayMode.LINE, layer.array!!.spec.mode)
        val before = layer.array!!.spec.look
        lookStep(u, ArrayLabels18.RESET_LOOK) { u.press(ArrayLabels18.RESET_LOOK) }
        assertNull(layer.array!!.spec.look)
        assertTrue("reset: Even again", selected(ArrayLabels18.EVEN))
        u.ui.twoFingerUndo()
        u.settleRenders("undo")
        assertEquals("one undo brings the look back", before, layer.array!!.spec.look)
        u.shot("look-tab-360")

        // The tab stays while the sheet closes and reopens; Placement is one tap away.
        SmokeUi.click("Close", exact = true)
        assertNull(u.s.tagged(V17Tags.ARRAY_SHEET))
        u.showArraySheet()
        assertNotNull("the Look tab again", u.s.tagged(V18Tags.ARRAY_LOOK))
        u.fingerPress(ArrayLabels18.PLACEMENT, 44f)
        assertEquals(ArrayTool.SheetTab.PLACEMENT, tool.sheetTab)
        reachable(u, ArrayLabels.LINE)
        Smoke.assertQuiet(c, "the Look tab at 360 dp")
    }

    private companion object {
        /** The filters' gradient editor's spoken name. */
        const val GRADIENT = "Gradient editor"

        /** A Tint colors preset (four stops). */
        const val SUNSET = "Sunset"
    }
}
