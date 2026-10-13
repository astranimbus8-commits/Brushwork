package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.placement.TransformStripItems
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
 * v1.8 final QA verification (items 8 and 9, D25) on the user's phone (392 × 873 dp): what the
 * controls ON a real More row do to it, by fingers. v1.9 (R3, design 2.6): only the toggle closes
 * the row; a sheet or the tool menu covers it and it shows again when they close. Transform with
 * a lifted picture: "Numbers" opens its sheet over the row (Back closes the sheet, the row shows
 * again); "Choose interpolation" drops its menu over the open row (rendered
 * `menus-verify-interpolation.png`), a pick changes the interpolation and leaves the row open; the
 * tool menu covers it. Lasso polygon over a selection: while the selection bar shows, the More row
 * repeats none of its buttons; while a polygon is pending the bar hides and the row offers
 * "Deselect" and "Selection menu" whole on screen (`menus-verify-lasso-shortcuts.png`); "Selection
 * menu" opens the sheet over the row, "Deselect" drops the selection in one step and keeps the row
 * and the polygon; the bar comes back when the polygon goes and the selection is undone back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.menusverifyflowssandbox"])
class Qa18MenusVerifyFlowsTest {

    @Test
    fun theControlsOnARealMoreRowOpenCloseOrKeepItAsDesigned() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("Transform: Numbers, Choose interpolation, the tool menu") { transform(h) }
        h.section("Lasso polygon: the selection shortcuts on the More row") { lasso(h) }
        dog.interrupt()
        h.finish()
    }

    private fun expanded(s: ChromeScreen): Boolean = Fingers18.state(s, StripLabels18.MORE_TOGGLE) == StripLabels18.EXPANDED

    private fun open(s: ChromeScreen, what: String) {
        assertTrue("$what: closed before", !expanded(s) && s.tagged(V18Tags.STRIP_MORE_ROW) == null)
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("$what: the toggle opens the More row", expanded(s) && s.tagged(V18Tags.STRIP_MORE_ROW) != null)
    }

    private fun assertOpen(s: ChromeScreen, why: String) {
        assertTrue("$why: the toggle says Expanded", expanded(s))
        assertNotNull("$why: the More row stays", s.tagged(V18Tags.STRIP_MORE_ROW))
    }

    private fun assertClosed(s: ChromeScreen, why: String) {
        assertFalse("$why: the toggle says Collapsed", expanded(s))
        assertNull("$why: no More row", s.tagged(V18Tags.STRIP_MORE_ROW))
    }

    private fun shot(s: ChromeScreen, name: String) {
        Qa18MenusShots.save(Qa18MenusShots.capture(), s.density, "verify-$name")
    }

    private fun transform(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c ->
            check(c.editWholeLayer(c.doc.layers[1], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        }
        Qa18MenusShots.pick(s, ToolId.TRANSFORM)
        val t = s.c.currentTool as TransformTool
        assertTrue("the picture is lifted", Smoke.pumpUntil(10_000) { SmokeUi.settle(); t.transformState != null })

        // "Numbers" opens a sheet over the row (v1.9: it covers the row, remembered), the sheet
        // stays until Back, and the row shows again.
        open(s, "Numbers")
        Finger.tap(s, TransformStripItems.NUMBERS_LABEL)
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("\"Numbers\" opens the Numbers sheet: ${SmokeUi.sheetTitles()}", t.numbersOpen && "Numbers" in SmokeUi.sheetTitles())
        assertClosed(s, "under the Numbers sheet")
        Smoke.pump(500); SmokeUi.settle()
        assertTrue("the Numbers sheet stays", "Numbers" in SmokeUi.sheetTitles())
        Finger.back()
        Smoke.pump(300); SmokeUi.settle()
        assertFalse("Back closes the Numbers sheet", t.numbersOpen || "Numbers" in SmokeUi.sheetTitles())
        assertOpen(s, "Back from the Numbers sheet")
        assertTrue("the picture is still lifted", t.transformState != null)

        // "Choose interpolation" drops an anchored menu: the row stays open under it and after a pick.
        assertEquals(TransformTool.Interpolation.SMOOTH, t.interpolation)
        Finger.tap(s, TransformStripItems.INTERPOLATION_LABEL)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the interpolation menu drops", 2, SmokeUi.windows().size)
        assertOpen(s, "the interpolation menu")
        shot(s, "interpolation")
        SmokeUi.tap(TransformTool.Interpolation.NEAREST.label, exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("a pick sets the interpolation", TransformTool.Interpolation.NEAREST, t.interpolation)
        assertEquals("…and closes the menu", 1, SmokeUi.windows().size)
        assertOpen(s, "a pick in the interpolation menu")

        // The tool menu opening (a chrome surface) covers the row; it shows again when it closes.
        val menuButton = Finger.control(s, "Tools (current: ", exact = false) ?: throw AssertionError("no tool menu button")
        Finger.tapAt(s, menuButton.center.x, menuButton.center.y)
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("the tool menu opens", s.tagged(ChromeTags.TOOL_MENU))
        assertClosed(s, "under the tool menu")
        Finger.tapAt(s, menuButton.center.x, menuButton.center.y)
        Smoke.pump(300); SmokeUi.settle()
        assertNull("the tool menu closes", s.tagged(ChromeTags.TOOL_MENU))
        assertOpen(s, "the tool menu closed again")
        assertEquals(ToolId.TRANSFORM, s.c.activeToolId)
        Qa18MenusShots.closeMore(s, "Transform, at the end")
        Smoke.assertQuiet(s.c, "Transform")
    }

    private fun shortcuts(s: ChromeScreen, what: String): List<String> {
        val (items, lines) = Qa18MenusShots.openMore(s, what)
        System.err.println("[menus] verify $what | More ($lines lines): ${Qa18MenusShots.names(items)}")
        return items.flatMap { it.own }.filter { it == "Deselect" || it == "Selection menu" }
    }

    private fun lasso(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c -> c.selectAll() }
        val c = s.c
        Qa18MenusShots.pick(s, ToolId.LASSO)
        val t = c.currentTool as LassoTool
        Finger.tap(s, "Freehand lasso")
        Smoke.pump(300); SmokeUi.settle()
        SmokeUi.tap("Polygon lasso", exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals(LassoKind.POLYGON, t.kind)
        assertNotNull("a selection: the selection bar shows", s.tagged(ChromeTags.SELECTION_BAR))

        // The bar offers Deselect and the menu: the More row does not repeat them.
        assertEquals("with the bar shown, the More row repeats none of its buttons", emptyList<String>(), shortcuts(s, "Polygon lasso, the bar shown"))
        Qa18MenusShots.closeMore(s, "Polygon lasso, the bar shown")

        // Two corners: the bar hides, the More row offers both, whole on screen.
        Fingers18.tap(s, 120f, 90f)
        Fingers18.tap(s, 260f, 110f)
        assertEquals("two corners by two taps", 2, t.vertexCount)
        assertNull("a pending polygon hides the selection bar", s.tagged(ChromeTags.SELECTION_BAR))
        assertEquals("the bar hidden, the More row offers both", listOf("Deselect", "Selection menu"), shortcuts(s, "Polygon lasso, two corners").sorted())
        shot(s, "lasso-shortcuts")

        // "Selection menu" is a sheet: it covers the row; Back closes it and the row shows again.
        Finger.tap(s, "Selection menu")
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("\"Selection menu\" opens the Selection sheet: ${SmokeUi.sheetTitles()}", "Selection" in SmokeUi.sheetTitles())
        assertClosed(s, "under the Selection sheet")
        Finger.back()
        Smoke.pump(300); SmokeUi.settle()
        assertFalse("Back closes the Selection sheet", "Selection" in SmokeUi.sheetTitles())
        assertOpen(s, "Back from the Selection sheet")
        assertEquals("the polygon is kept", 2, t.vertexCount)
        assertNotNull("the selection is kept", c.selection)

        // "Deselect" drops the selection in one step: the row and the polygon stay.
        val undo = c.undoManager.undoCount
        Finger.tap(s, "Deselect")
        Smoke.pump(300); SmokeUi.settle()
        assertNull("\"Deselect\" drops the selection", c.selection)
        assertEquals("…one step", undo + 1, c.undoManager.undoCount)
        assertOpen(s, "\"Deselect\"")
        assertEquals("…the polygon is kept", 2, t.vertexCount)
        Qa18MenusShots.closeMore(s, "after Deselect")

        // The polygon discarded and the deselect undone: the bar is back, the row repeats nothing.
        Finger.tap(s, "Discard polygon")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the polygon discarded", 0, t.vertexCount)
        Finger.tap(s, "Undo")
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("Undo brings the selection back", c.selection)
        assertNotNull("…and the selection bar", s.tagged(ChromeTags.SELECTION_BAR))
        assertEquals("the bar back, the More row repeats none of its buttons", emptyList<String>(), shortcuts(s, "Polygon lasso, the bar back"))
        Qa18MenusShots.closeMore(s, "Polygon lasso, the bar back")
        Smoke.assertQuiet(c, "Lasso")
    }
}
