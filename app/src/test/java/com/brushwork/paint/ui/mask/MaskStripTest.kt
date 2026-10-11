package com.brushwork.paint.ui.mask

import android.graphics.Matrix
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.placement.PlacementStripKit
import com.brushwork.paint.ui.placement.PlacementStripKit.assertSplits
import com.brushwork.paint.ui.placement.PlacementStripKit.clippedTexts
import com.brushwork.paint.ui.placement.PlacementStripKit.inspect
import com.brushwork.paint.ui.placement.PlacementStripKit.key
import com.brushwork.paint.ui.placement.PlacementStripKit.moreHandles
import com.brushwork.paint.ui.placement.PlacementStripKit.popupKeepsRow
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Handles
import com.brushwork.paint.ui.placement.PlacementStripKit.sheetShown
import com.brushwork.paint.ui.placement.PlacementStripKit.staysOpen
import com.brushwork.paint.ui.tools.strip.StripRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 G (design §3.9 "Masks"): the Masks strip at 360 and 392 dp, each state's split at the four
 * configurations (with and without the VECTOR cell), every v1.7 handle reachable (the inventory,
 * with this test's record for the states [com.brushwork.paint.ui.tools.strip.StripHandlesV17] did
 * not record), no label cut by its item's width, `legacyContent == null`:
 * - fresh (both layers) and on the new adjustment layer: row 1 "+ Linear", "+ Radial", "+ Brush",
 *   "Invert", "Adjust…" (AUTO 1: the More row's front at 360 dp with the VECTOR cell); the More
 *   row "Choose what the mask is for" (an opaque dropdown that keeps the row open), "Add",
 *   "Subtract", "Intersect", the overlay toggle, "Components (N)" (its sheet outlives the row);
 * - a brush part selected: "Done" joins row 1 and "Adjust…" leaves it but at 392 dp without the
 *   VECTOR cell (its sheet, opened from the More row, outlives the row); "Brush" / "Erase" on the
 *   More row.
 * Arming a part kind closes the More row (the next drag on the canvas makes the part).
 * Widths against the planning table (rule 4, measured): the three add buttons 40 dp each (an icon
 * over "+ Name"; 128 with their gaps instead of 120), "Adjust…" 72, the target 152, "Components
 * (N)" 100.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.mask.maskstripsandbox"])
class MaskStripTest {

    private val M = MaskStripItems

    @Test
    fun theMasksStripKeepsItsControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        PlacementStripKit.atEachWidth { rig, dp -> flow(rig, dp) }
        dog.interrupt()
    }

    private val noBrush: (Int, Boolean) -> List<String> = { w, chip ->
        listOf(M.ADD_LINEAR, M.ADD_RADIAL, M.ADD_BRUSH, M.INVERT) + if (w == 360 && chip) emptyList() else listOf(M.ADJUST)
    }

    private fun modes() = MaskMode.entries.map(::modeLabel)

    private fun flow(rig: StripRig, dp: Int) {
        // Fresh, on both layers.
        for (vector in listOf(false, true)) {
            val k = key(ToolId.MASK, vector)
            rig.select(ToolId.MASK, vector)
            inspect(rig, k)
            assertSplits(k, rig.spec, noBrush)
            assertEquals("$k at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        }
        rig.select(ToolId.MASK)
        val c = rig.c
        val tool = c.currentTool as MaskTool
        c.viewTransform.set(Matrix())
        val adds = listOf(M.add(M.LINEAR), M.add(M.RADIAL), M.add(M.BRUSH))
        assertEquals("MASK at $dp dp: row 1", adds + listOf(M.INVERT_LABEL, M.ADJUST_LABEL), row1Handles())
        assertFalse("Adjust needs an adjustment layer", SmokeUi.isEnabled(M.ADJUST_LABEL))
        assertFalse("Invert needs a selected part", SmokeUi.isEnabled(M.INVERT_LABEL))
        assertTrue(StripTestKit.openMoreRow())
        assertEquals(
            "MASK at $dp dp: the More row",
            listOf(M.TARGET_LABEL) + modes() + listOf(M.overlayLabel(tool.overlayAlways), M.components(0)),
            moreHandles(),
        )
        StripTestKit.closeMoreRow()
        popupKeepsRow(rig, M.TARGET_LABEL, "MASK: the target dropdown") { SmokeUi.has("This layer's mask", exact = true) }

        // Arming closes the More row; arming again disarms.
        assertTrue(StripTestKit.openMoreRow())
        SmokeUi.click(M.add(M.RADIAL), exact = true)
        rig.settle()
        assertEquals(MaskTool.Kind.RADIAL, tool.armed)
        assertFalse("arming closed the More row", StripTestKit.moreRowShown() || rig.state.anyOpen)
        SmokeUi.click(M.add(M.RADIAL), exact = true)
        rig.settle()
        assertNull(tool.armed)

        // A linear part: a new adjustment layer, the part selected.
        SmokeUi.click(M.add(M.LINEAR), exact = true)
        rig.settle()
        drag(c, 120f, 150f, 280f, 150f)
        rig.settle()
        val adj = c.activeLayer
        assertTrue("the drag made an adjustment layer", adj.isAdjustmentLayer)
        val linear = "MASK, a linear part on an adjustment layer"
        val linearMore = listOf(M.TARGET_LABEL) + modes() + listOf(M.overlayLabel(tool.overlayAlways), M.components(1))
        inspect(rig, linear, (adds + listOf(M.INVERT_LABEL, M.ADJUST_LABEL) + linearMore).toSet())
        assertSplits(linear, rig.spec, noBrush)
        assertEquals("$linear at $dp dp: row 1", adds + listOf(M.INVERT_LABEL, M.ADJUST_LABEL), row1Handles())
        assertTrue(SmokeUi.isEnabled(M.ADJUST_LABEL))
        assertTrue(SmokeUi.isEnabled(M.INVERT_LABEL))
        assertTrue(StripTestKit.openMoreRow())
        assertEquals("$linear at $dp dp: the More row", linearMore, moreHandles())
        assertEquals("$linear at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        StripTestKit.closeMoreRow()
        staysOpen(rig, M.components(1), "$linear: the components sheet") { tool.componentsOpen && sheetShown(rig, "Mask of ${adj.name}") }
        tool.componentsOpen = false
        rig.settle()

        // A brush part, selected: "Done" on row 1, "Brush" / "Erase" on the More row.
        SmokeUi.click(M.add(M.BRUSH), exact = true)
        rig.settle()
        assertEquals(MaskTool.Kind.BRUSH, tool.armed)
        drag(c, 150f, 100f, 250f, 200f)
        assertTrue("the stroke selected its brush part", Smoke.pumpUntil { rig.settle(); tool.selected is BrushMask })
        rig.settle()
        val brush = "MASK, a brush part selected"
        val brushRow1 = adds + listOf(M.INVERT_LABEL) + (if (dp == 392) listOf(M.ADJUST_LABEL) else emptyList()) + M.DONE_LABEL
        val brushMore = (if (dp == 392) emptyList() else listOf(M.ADJUST_LABEL)) +
            listOf(M.TARGET_LABEL, M.BRUSH, M.ERASE_LABEL) + modes() + listOf(M.overlayLabel(tool.overlayAlways), M.components(2))
        inspect(rig, brush, (brushRow1 + brushMore).toSet())
        assertSplits(brush, rig.spec) { w, chip ->
            listOf(M.ADD_LINEAR, M.ADD_RADIAL, M.ADD_BRUSH, M.INVERT) + (if (w == 392 && !chip) listOf(M.ADJUST) else emptyList()) + M.DONE
        }
        assertEquals("$brush at $dp dp: row 1", brushRow1, row1Handles())
        assertTrue(StripTestKit.openMoreRow())
        assertEquals("$brush at $dp dp: the More row", brushMore, moreHandles())
        assertEquals("$brush at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        SmokeUi.click(M.ERASE_LABEL, exact = true)
        rig.settle()
        assertTrue("Erase", tool.brushErase)
        SmokeUi.click(M.BRUSH, exact = true)
        rig.settle()
        assertFalse("Brush", tool.brushErase)
        StripTestKit.closeMoreRow()

        // The Adjust sheet: from the More row at 360 dp (it outlives the row), from row 1 at 392 dp.
        val adjustShown = { tool.adjustOpen && sheetShown(rig, "Adjust: ${adj.name}") }
        if (dp == 392) {
            SmokeUi.click(M.ADJUST_LABEL, exact = true)
            rig.settle()
            assertTrue("$brush: the Adjust sheet", adjustShown())
        } else {
            staysOpen(rig, M.ADJUST_LABEL, "$brush: the Adjust sheet", adjustShown)
        }
        SmokeUi.click("Close", exact = true)
        rig.settle()
        assertFalse(tool.adjustOpen)

        // "Done": the part is no longer selected, "Done" leaves.
        SmokeUi.click(M.DONE_LABEL, exact = true)
        rig.settle()
        assertNull(tool.selected)
        assertFalse(SmokeUi.has(M.DONE_LABEL, exact = true))
        rig.select(ToolId.BRUSH)
    }

    /** A finger drag in document px (the view is the identity). */
    private fun drag(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..4) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / 4f, y0 + (y1 - y0) * i / 4f))
        c.pointerUp(ToolPoint(x1, y1))
    }
}
