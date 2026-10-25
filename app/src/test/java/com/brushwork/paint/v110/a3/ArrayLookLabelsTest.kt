package com.brushwork.paint.v110.a3

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.RED
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
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
 * v1.10 (R5 (a), UQ6, A3 step 5) on the user's 392 dp phone, by label and by finger: the Array
 * sheet's Look tab shows, right under `ArrayLabels18.TINT_COLORS`, the two segments
 * [ArrayLabels110.TINTED] | [ArrayLabels110.RECOLORED] (tag [V110Tags.ARRAY_COLOR_MODE]) across
 * the sheet, each at least 48 dp; Tinted at first. Recolored tapped with no look records nothing
 * yet stays lit and renames the strength field [ArrayLabels110.RECOLOR_STRENGTH]; a Tint colors
 * preset then records ONE step "Array look" with the copies Recolored; Tinted is one step back
 * (the v1.9 field name again) and undo brings Recolored back; Reset look forgets the mode. Neither
 * word is a part of another label on the screen (the label trap of C18).
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a3.looklabelssandbox"])
class ArrayLookLabelsTest {

    @Test
    fun theColorModeSegmentsAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("the Look tab's color mode at 392 dp") { colorMode(Qa17ArrayUi(h)) }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    /** True when the segment or chip [label] is the chosen one (it, or a node around it, says Selected). */
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

    /** The strength field: named [shown], the other name nowhere. */
    private fun strengthNamed(shown: String, gone: String) {
        assertTrue("\"$shown\" shown", SmokeUi.has(shown, exact = true))
        assertFalse("\"$gone\" gone", SmokeUi.has(gone, exact = true))
    }

    private fun colorMode(u: Qa17ArrayUi) {
        u.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), widthDp = 392f)
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
        u.press(ArrayLabels18.LOOK, 44f)

        // The two segments: under the Tint colors header, one row across the sheet, 48 dp each.
        for (w in listOf(ArrayLabels110.TINTED, ArrayLabels110.RECOLORED)) {
            u.ui.reach(w, 48f)
            assertTrue("\"$w\" wholly on the 392 dp screen, at least 48 dp", u.ui.wholly(w, 48f))
        }
        val row = requireNotNull(u.s.tagged(V110Tags.ARRAY_COLOR_MODE)) { "no color mode row" }
        val sheet = requireNotNull(u.s.tagged(V17Tags.ARRAY_SHEET))
        assertTrue("the row spans the sheet: $row of $sheet", row.width >= sheet.width - 48f)
        val header = requireNotNull(SmokeUi.find(ArrayLabels18.TINT_COLORS.uppercase(), exact = true)) { "no header" }.node.boundsInWindow
        val tinted = requireNotNull(SmokeUi.find(ArrayLabels110.TINTED, exact = true)).node.boundsInWindow
        val recolored = requireNotNull(SmokeUi.find(ArrayLabels110.RECOLORED, exact = true)).node.boundsInWindow
        assertEquals("one row", tinted.top, recolored.top, 2f * u.s.density)
        assertTrue("Tinted first", tinted.right <= recolored.left + 0.5f)
        assertTrue("right under the header", tinted.top >= header.bottom - 0.5f && tinted.top - header.bottom <= 24f * u.s.density)
        assertTrue("Tinted at first", selected(ArrayLabels110.TINTED))
        assertFalse(selected(ArrayLabels110.RECOLORED))
        strengthNamed(ArrayLabels18.TINT_STRENGTH, ArrayLabels110.RECOLOR_STRENGTH)

        // The label trap: neither word is a part of another label on the screen, nor holds one.
        val labels = SmokeUi.shown()
        for (w in listOf(ArrayLabels110.TINTED, ArrayLabels110.RECOLORED)) {
            val clash = labels.filter { it != w && it.length >= 3 && (it.contains(w, ignoreCase = true) || w.contains(it, ignoreCase = true)) }
            assertTrue("\"$w\" clashes with $clash", clash.isEmpty())
        }

        // Recolored with no look: nothing recorded, yet lit; the strength field renamed.
        val alone = u.steps()
        u.fingerPress(ArrayLabels110.RECOLORED, 48f)
        u.settleRenders("a mode alone")
        assertEquals("a mode alone records nothing", alone, u.steps())
        assertNull(layer.array!!.spec.look)
        assertTrue("Recolored stays lit", selected(ArrayLabels110.RECOLORED))
        assertFalse(selected(ArrayLabels110.TINTED))
        strengthNamed(ArrayLabels110.RECOLOR_STRENGTH, ArrayLabels18.TINT_STRENGTH)

        // A preset: ONE step, the copies Recolored at 100 %.
        lookStep(u, SUNSET) { u.press(SUNSET) }
        assertEquals(ArrayColorMode.RECOLOR, layer.array!!.spec.look!!.colorMode)
        assertEquals(1f, layer.array!!.spec.look!!.tintStrength, 1e-4f)
        lookStep(u, ArrayLabels110.RECOLOR_STRENGTH) { u.typeField(ArrayLabels110.RECOLOR_STRENGTH, "60") }
        assertEquals(0.6f, layer.array!!.spec.look!!.tintStrength, 1e-4f)

        // Tinted: one step, v1.9's field name again; undo: Recolored.
        lookStep(u, ArrayLabels110.TINTED) { u.fingerPress(ArrayLabels110.TINTED, 48f) }
        assertEquals(ArrayColorMode.TINT, layer.array!!.spec.look!!.colorMode)
        assertEquals("the rest of the look kept", 0.6f, layer.array!!.spec.look!!.tintStrength, 1e-4f)
        assertTrue(selected(ArrayLabels110.TINTED))
        strengthNamed(ArrayLabels18.TINT_STRENGTH, ArrayLabels110.RECOLOR_STRENGTH)
        u.ui.twoFingerUndo()
        u.settleRenders("undo")
        assertEquals(ArrayColorMode.RECOLOR, layer.array!!.spec.look!!.colorMode)
        assertTrue("undo: Recolored again", selected(ArrayLabels110.RECOLORED))
        strengthNamed(ArrayLabels110.RECOLOR_STRENGTH, ArrayLabels18.TINT_STRENGTH)
        u.shot("look-color-mode-392")

        // Reset look: no look, and Tinted again.
        lookStep(u, ArrayLabels18.RESET_LOOK) { u.press(ArrayLabels18.RESET_LOOK) }
        assertNull(layer.array!!.spec.look)
        assertEquals(ArrayColorMode.TINT, tool.plainColorMode)
        assertTrue("reset: Tinted", selected(ArrayLabels110.TINTED))
        strengthNamed(ArrayLabels18.TINT_STRENGTH, ArrayLabels110.RECOLOR_STRENGTH)
        assertNotNull(u.s.tagged(V110Tags.ARRAY_COLOR_MODE))
        Smoke.assertQuiet(c, "the Look tab's color mode at 392 dp")
    }

    private companion object {
        /** A Tint colors preset (four stops). */
        const val SUNSET = "Sunset"
    }
}
