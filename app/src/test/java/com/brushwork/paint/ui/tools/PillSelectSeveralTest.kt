package com.brushwork.paint.ui.tools

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
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
 * v1.8 (F4, design §3.2 and D5) on the real editor at the user's phone width: the X / Y pill's
 * "Select several" cell (tag [V18Tags.PILL_SEVERAL]) shows exactly while the tool's
 * [PointEditor.selectSeveralAvailable]:
 * - Curve, Polyline and Path: once they have points (none before), at the end of row 1;
 * - Shape: a pending shape in Points mode only (not before "Points", not after leaving it);
 * - Transform: while Free deform shows its mesh;
 * and each time it switches the tool's `selectSeveral` (its state "On" / "Off"), by a finger's tap
 * too. Folded the pill keeps it (`[✥][🗑][sel]`); with points but no position the pill is reduced
 * to the cell ([pillRow1]: no tool of the app gets there today, so its rows are checked pure).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pillselectseveralsandbox"])
class PillSelectSeveralTest {

    private val three = listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 160f))

    /** The cell shows as [editor] says it is available; when it does, it is the last of row 1. */
    private fun assertCell(s: ChromeScreen, what: String, editor: PointEditor, shown: Boolean) {
        assertEquals("$what: available", shown, editor.selectSeveralAvailable)
        val cell = s.tagged(V18Tags.PILL_SEVERAL)
        if (!shown) {
            assertNull("$what: no cell", cell)
            return
        }
        assertNotNull("$what: the cell; shown: ${SmokeUi.shown().take(80)}", cell)
        val trash = s.tagged(V17Tags.PILL_TRASH)
        val fold = Finger.element(s, "Fold the X / Y strip") ?: Finger.element(s, "Unfold the X / Y strip")
        for ((name, r) in listOf("trash" to trash, "fold" to fold)) {
            if (r != null) assertTrue("$what: the cell ($cell) after the $name cell ($r)", r.right <= cell!!.left + 0.75f)
        }
    }

    /** Switches it on (through [toggle]) and off again, its state and the tool's flag following. */
    private fun assertToggles(what: String, editor: PointEditor, toggle: () -> Unit = PillTestKit::toggleSeveral) {
        assertFalse("$what: off at first", editor.selectSeveral)
        assertEquals("$what: Off", "Off", PillTestKit.severalState())
        toggle()
        assertTrue("$what: on", editor.selectSeveral)
        assertEquals("$what: On", "On", PillTestKit.severalState())
        toggle()
        assertFalse("$what: off again", editor.selectSeveral)
        assertEquals("$what: Off again", "Off", PillTestKit.severalState())
    }

    @Test
    fun theSelectSeveralCellFollowsEachToolAndSwitchesIt() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()

        h.section("the reduced pill and row 1's cells (pure)") {
            assertEquals(listOf(PillCell.SEVERAL), pillRow1(hasPosition = false, folded = false, trash = false, several = true))
            assertEquals(listOf(PillCell.SEVERAL), pillRow1(hasPosition = false, folded = true, trash = true, several = true))
            assertEquals(emptyList<PillCell>(), pillRow1(hasPosition = false, folded = false, trash = true, several = false))
            assertEquals(
                listOf(PillCell.FOLD, PillCell.X, PillCell.Y, PillCell.STEP, PillCell.TRASH, PillCell.SEVERAL),
                pillRow1(hasPosition = true, folded = false, trash = true, several = true),
            )
            assertEquals(listOf(PillCell.FOLD, PillCell.TRASH, PillCell.SEVERAL), pillRow1(hasPosition = true, folded = true, trash = true, several = true))
            assertEquals(listOf(PillCell.FOLD, PillCell.SEVERAL), pillRow1(hasPosition = true, folded = true, trash = false, several = true))
            // Without "Select several", v1.7's rows.
            assertEquals(listOf(PillCell.FOLD, PillCell.X, PillCell.Y, PillCell.STEP), pillRow1(hasPosition = true, folded = false, trash = false, several = false))
            assertEquals(listOf(PillCell.FOLD, PillCell.TRASH), pillRow1(hasPosition = true, folded = true, trash = true, several = false))
        }

        for (label in listOf("Curve", "Polyline", "Path")) {
            h.section("$label: the cell once there are points; folded too") {
                val s = h.editor()
                QaCurves.tool(s, label)
                val tool = s.c.currentTool as CurveTool
                assertCell(s, "$label, no points", tool, shown = false)
                for (p in three) assertTrue(tool.addAnchor(p))
                settle()
                assertCell(s, "$label, three points", tool, shown = true)
                assertToggles(label, tool)
                // A finger's tap reaches it (nothing covers the cell).
                assertToggles("$label by a finger", tool) { Finger.tap(s, PointLabels.SELECT_SEVERAL); settle() }
                // Folded: [✥][🗑][sel]; the cell stays and still switches.
                click("Fold the X / Y strip", exact = true)
                settle()
                assertNull("$label folded: no X", Finger.element(s, "X slider"))
                assertCell(s, "$label folded", tool, shown = true)
                assertToggles("$label folded", tool)
                click("Unfold the X / Y strip", exact = true)
                settle()
                assertNotNull("$label unfolded: X", Finger.element(s, "X slider"))
                tool.discard()
                settle()
                assertCell(s, "$label discarded", tool, shown = false)
                Smoke.assertQuiet(s.c, label)
            }
        }

        h.section("Shape: the cell in Points mode only") {
            val s = h.editor()
            val ui = Qa16Ui(s)
            QaCurves.tool(s, "Shape")
            val tool = s.c.currentTool as ShapeTool
            QaCurves.snapOff(s.c)
            assertCell(s, "Shape, nothing drawn", tool, shown = false)
            QaCurves.drag(s, Vec2(60f, 70f), Vec2(170f, 150f))
            assertNotNull("a pending shape", tool.box)
            assertCell(s, "Shape, not in Points mode", tool, shown = false)
            ui.reach("Points", 32f)
            click("Points", exact = true)
            assertTrue(tool.pointsMode)
            assertCell(s, "Shape, Points mode", tool, shown = true)
            assertToggles("Shape", tool)
            PillTestKit.toggleSeveral()
            assertTrue("its hint as the editor's message", SmokeUi.has(PointLabels.SEVERAL_HINT, exact = true))
            tool.setPointEditing(false)
            settle()
            assertFalse("leaving Points mode turns it off", tool.selectSeveral)
            assertCell(s, "Shape, Points mode left", tool, shown = false)
            tool.discard()
            settle()
            Smoke.assertQuiet(s.c, "Shape")
        }

        h.section("Transform: the cell while Free deform shows its mesh") {
            val s = h.editor()
            s.c.selectLayer(0)
            settle()
            QaCurves.tool(s, "Transform")
            val tool = s.c.currentTool as TransformTool
            assertTrue(Smoke.pumpUntil { settle(1); tool.transformState != null })
            settle()
            assertCell(s, "Transform, Free", tool, shown = false)
            // v1.8 (§3.9): Free deform is an entry of the mode dropdown (its button: the current mode).
            click(TransformTool.Mode.FREE.label, exact = true)
            Qa16Ui(s).reach(TransformLabels17.FREE_DEFORM, 0f)
            click(TransformLabels17.FREE_DEFORM, exact = true)
            assertTrue(Smoke.pumpUntil { settle(1); tool.isMeshShown })
            settle()
            assertCell(s, "Transform, Free deform", tool, shown = true)
            assertToggles("Free deform", tool)
            tool.discard()
            s.c.selectTool(ToolId.BRUSH)
            settle()
            assertNull("another tool: no cell", s.tagged(V18Tags.PILL_SEVERAL))
            Smoke.assertQuiet(s.c, "Transform")
        }

        dog.interrupt()
        h.finish()
    }
}
