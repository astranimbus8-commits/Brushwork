package com.brushwork.paint.ui.placement

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.placement.PlacementStripKit.moreHandles
import com.brushwork.paint.ui.placement.PlacementStripKit.moreTexts
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Handles
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
 * v1.8 G (design §3.9 "Transform, Free deform" and F4) on the user's 392 dp phone, the whole
 * editor: Free deform's strip with "Select several" in the X / Y pill.
 * - The box modes offer no "Select several" cell (`selectSeveralAvailable` is `isMeshShown`);
 *   Free deform, picked in the mode dropdown, does ("Off").
 * - Row 1: the mode ("Free deform"), the mesh button showing "3 × 3", "Reset mesh" (enabled once the
 *   mesh is deformed); the More row: "Smooth mesh", "Select all points", "Delete", "Choose
 *   interpolation".
 * - The cell on: the "Select several" hint is the More row's first line, taps add vertices; off:
 *   the hint is gone. "Select all points" turns into "Deselect all points".
 * - The [TransformLabels18.MESH_SIZE] popup keeps the More row open, also after Back closes it.
 * - "Delete" on the More row is one step; the strip, now without a More row, leaves none open
 *   (D25): the next two-finger tap undoes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.placement.freedeformpillsandbox"])
class FreeDeformPillTest {

    @Test
    fun freeDeformSelectsSeveralThroughThePill() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("Free deform: the strip, the pill's Select several, the mesh popup, Delete") { flow(h) }
        dog.interrupt()
        h.finish()
    }

    private fun flow(h: ChromeHarness) {
        val s: ChromeScreen = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true)) { it.snapping.enabled = false }
        val ui = Qa16Ui(s)
        settle()
        val c = s.c
        assertTrue(c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        settle()
        ui.tool("Transform")
        val t = c.currentTool as TransformTool
        assertTrue("lifted", Smoke.pumpUntil { settle(1); t.transformState != null })
        settle()

        // The box modes: no cell.
        assertFalse(t.selectSeveralAvailable)
        assertNull("no Select several cell in the box modes", PillTestKit.severalCell())
        assertEquals(
            listOf(TransformTool.Mode.FREE.label, TransformStripItems.keepAspectLabel(t.keepAspect), TransformStripItems.FROM_CENTER, TransformStripItems.FLIP_HORIZONTALLY, TransformStripItems.FLIP_VERTICALLY),
            row1Handles(),
        )

        // Free deform from the mode dropdown: the cell shows, off.
        SmokeUi.click(TransformTool.Mode.FREE.label, exact = true)
        SmokeUi.click(TransformLabels17.FREE_DEFORM, exact = true)
        assertEquals(TransformTool.Mode.MESH, t.mode)
        assertTrue(t.isMeshShown)
        assertTrue(t.selectSeveralAvailable)
        assertEquals("Off", PillTestKit.severalState())
        assertEquals(listOf(TransformLabels17.FREE_DEFORM, TransformLabels18.MESH_SIZE, TransformLabels17.RESET), row1Handles())
        assertTrue("the mesh button shows the mesh", has(TransformStripItems.meshText(3, 3), exact = true))
        assertFalse("Reset mesh waits for a deformation", SmokeUi.isEnabled(TransformLabels17.RESET))
        assertTrue(StripTestKit.openMoreRow())
        assertEquals(
            listOf(TransformLabels17.SMOOTH, PointLabels.SELECT_ALL, TransformStripItems.DELETE_LABEL, TransformStripItems.INTERPOLATION_LABEL),
            moreHandles(),
        )
        assertFalse("no hint while Select several is off", PointLabels.SEVERAL_HINT in moreTexts())

        // The cell on: the hint heads the More row; taps add vertices.
        PillTestKit.toggleSeveral()
        assertTrue(t.selectSeveral)
        assertEquals("On", PillTestKit.severalState())
        StripTestKit.openMoreRow()
        assertTrue("the Select several hint", PointLabels.SEVERAL_HINT in moreTexts())
        val hint = SmokeUi.find(PointLabels.SEVERAL_HINT, exact = true) ?: throw AssertionError("no hint")
        val smooth = SmokeUi.find(TransformLabels17.SMOOTH, exact = true) ?: throw AssertionError("no Smooth mesh")
        assertTrue("the hint is the first line", hint.bounds.bottom <= smooth.bounds.top + 1f)
        // (Closed again: an open More row takes the next canvas touch, D25.)
        StripTestKit.closeMoreRow()
        settle()
        for (i in listOf(5, 6)) t.pointAt(i).let { p -> ui.tap(p.x, p.y) }
        assertEquals("two taps, two vertices", listOf(5, 6), t.pointSelection.indices)

        // Select all points, then Deselect all points (the same control).
        SmokeUi.click(PointLabels.SELECT_ALL, exact = true)
        assertTrue(t.allPointsSelected)
        assertTrue(PointLabels.DESELECT_ALL in moreHandles())
        SmokeUi.click(PointLabels.DESELECT_ALL, exact = true)
        assertFalse(t.allPointsSelected)
        assertTrue(PointLabels.SELECT_ALL in moreHandles())

        // The cell off: the hint is gone.
        PillTestKit.toggleSeveral()
        assertFalse(t.selectSeveral)
        assertEquals("Off", PillTestKit.severalState())
        StripTestKit.openMoreRow()
        assertFalse(PointLabels.SEVERAL_HINT in moreTexts())

        // The mesh popup (on row 1) keeps the More row open, also after Back.
        SmokeUi.click(TransformLabels18.MESH_SIZE, exact = true)
        assertTrue("the popup", has(MeshStepperLabels.MORE_COLUMNS, exact = true))
        assertTrue("the More row stays open", StripTestKit.moreRowShown())
        SmokeUi.click(MeshStepperLabels.MORE_COLUMNS, exact = true)
        assertEquals(4, t.meshColumns)
        ui.backKey()
        assertFalse("the popup closed", has(MeshStepperLabels.MORE_COLUMNS, exact = true))
        assertTrue("the More row is still open", StripTestKit.moreRowShown())
        assertTrue(has(TransformStripItems.meshText(4, 3), exact = true))
        StripTestKit.closeMoreRow()
        settle()

        // A finger deforms the mesh: Reset mesh comes alive and takes it back.
        QaCurves.drag(s, t.pointAt(6), Vec2(15f, 10f))
        assertTrue(t.isMeshChanged)
        assertTrue(SmokeUi.isEnabled(TransformLabels17.RESET))
        SmokeUi.click(TransformLabels17.RESET, exact = true)
        assertFalse(t.isMeshChanged)
        assertFalse(SmokeUi.isEnabled(TransformLabels17.RESET))

        // Delete (More row): one step; nothing is left open to swallow the two-finger undo.
        val steps = c.undoManager.undoCount
        SmokeUi.click(TransformStripItems.DELETE_LABEL, exact = true)
        assertTrue("deleted", Smoke.pumpUntil { settle(1); t.transformState == null })
        settle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertFalse("no More row", StripTestKit.moreRowShown())
        assertNull("the strip has no toggle now", StripTestKit.toggle())
        ui.twoFingerUndo()
        assertEquals("the first two-finger tap undoes Delete", steps, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "free deform pill")
    }
}
