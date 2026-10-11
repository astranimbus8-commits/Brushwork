package com.brushwork.paint.ui.placement

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.placement.PlacementStripKit.assertSplits
import com.brushwork.paint.ui.placement.PlacementStripKit.clippedTexts
import com.brushwork.paint.ui.placement.PlacementStripKit.inspect
import com.brushwork.paint.ui.placement.PlacementStripKit.key
import com.brushwork.paint.ui.placement.PlacementStripKit.moreHandles
import com.brushwork.paint.ui.placement.PlacementStripKit.moreTexts
import com.brushwork.paint.ui.placement.PlacementStripKit.popupKeepsRow
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Handles
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Texts
import com.brushwork.paint.ui.placement.PlacementStripKit.sheetShown
import com.brushwork.paint.ui.placement.PlacementStripKit.staysOpen
import com.brushwork.paint.ui.tools.strip.StripRig
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
 * v1.8 G (design §3.9 "Transform", "Transform, Free deform", "Text", "Frame divider"): the three
 * placement strips at 360 and 392 dp, each state's row 1 / More row split at the four
 * configurations (with and without the VECTOR cell), every v1.7 handle reachable (the inventory,
 * with this test's own record for the states [StripHandlesV17] did not record), no label cut by
 * its item's width.
 * - Transform: nothing to transform (its status alone on row 1, no toggle); the box modes (row 1
 *   the mode, "Keep aspect ratio", "From center", the flips; the More row under the status text);
 *   Free deform (row 1 the mode, [TransformLabels18.MESH_SIZE], "Reset mesh"; the steppers in its
 *   popup, `movedInto`). The other modes are inside the mode dropdown (`movedInto`, rule 5). The
 *   Numbers sheet outlives the More row; "Choose interpolation" keeps it open.
 * - Text: row 1 "Edit text", the direction, "Letter scaling", "Wrap" (AUTO 1: the More row's
 *   front at 360 dp with the VECTOR cell); "Edit text" opens the editor dialog and closes the More
 *   row first (D25); "Numbers" on the More row while a text is open, its sheet outliving the row.
 * - Frame divider: without a frame layer "New frame layer" and the status (no toggle; its sheet
 *   shows); with one "Rows × columns", "Remove panels mode", "Frame settings / new frame layer"
 *   (sheets from row 1 close the More row and show); edited by hand "Redraw", "New frame" and the
 *   status (no toggle).
 * Widths against the planning table (rule 4, measured): Transform's status 284 (no More row),
 * Delete 104, interpolation 80, Numbers 64; Free deform's Smooth mesh and the points button 80;
 * the Frame divider status 184.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.placement.placementstripsandbox"])
class PlacementStripTest {

    private val T = TransformStripItems

    @Test
    fun thePlacementStripsKeepTheirControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        PlacementStripKit.atEachWidth { rig, dp ->
            transform(rig, dp)
            text(rig, dp)
            frameDivider(rig, dp)
        }
        dog.interrupt()
    }

    private fun transform(rig: StripRig, dp: Int) {
        // Nothing to transform (empty layers): the status alone, no toggle.
        for (vector in listOf(false, true)) {
            val k = key(ToolId.TRANSFORM, vector)
            rig.select(ToolId.TRANSFORM, vector)
            val t = rig.c.currentTool as TransformTool
            assertNull("$k: nothing lifted", t.transformState)
            inspect(rig, k)
            assertSplits(k, rig.spec) { _, _ -> listOf(T.STATUS) }
            assertNull("$k: no toggle", StripTestKit.toggle())
            assertEquals("$k at $dp dp: the status on row 1", listOf(t.statusText), row1Texts())
            assertEquals("$k at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        }

        // A picture to transform: the box modes.
        rig.select(ToolId.BRUSH)
        assertTrue(rig.c.editWholeLayer(rig.doc.layers[0], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        rig.select(ToolId.TRANSFORM)
        val t = rig.c.currentTool as TransformTool
        assertTrue("lifted", Smoke.pumpUntil { rig.settle(); t.transformState != null })
        rig.settle()
        val box = "TRANSFORM, box modes"
        val boxRow1 = listOf(TransformTool.Mode.FREE.label, T.keepAspectLabel(t.keepAspect), T.FROM_CENTER, T.FLIP_HORIZONTALLY, T.FLIP_VERTICALLY)
        val boxMore = listOf(T.DELETE_LABEL, T.ROTATE_CCW_LABEL, T.ROTATE_CW_LABEL, T.FIT_LABEL, T.RESET_LABEL, SNAP_LABEL, T.INTERPOLATION_LABEL, T.NUMBERS_LABEL)
        // v1.7's other mode chips are inside the mode dropdown (rule 5: the inventory opens it).
        val boxModes = listOf(TransformTool.Mode.DISTORT.label, TransformTool.Mode.MESH.label)
        assertEquals(TransformLabels17.FREE_DEFORM, TransformTool.Mode.MESH.label)
        assertEquals("$box: the other modes are inside the mode dropdown", boxModes.toSet(), rig.spec.movedInto.keys)
        inspect(rig, box, (boxRow1 + boxMore + boxModes).toSet())
        assertSplits(box, rig.spec) { _, _ -> listOf(T.MODE, T.ASPECT, T.CENTER, T.FLIP_H, T.FLIP_V) }
        assertEquals("$box at $dp dp: row 1", boxRow1, row1Handles())
        assertEquals("$box: the status text is the hint", t.statusText, rig.spec.hint)
        assertTrue(StripTestKit.openMoreRow())
        assertEquals("$box: the hint heads the More row", t.statusText, moreTexts().firstOrNull())
        assertEquals("$box at $dp dp: the More row", boxMore, moreHandles())
        assertEquals("$box at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        StripTestKit.closeMoreRow()
        popupKeepsRow(rig, T.INTERPOLATION_LABEL, "$box: the interpolation dropdown") {
            SmokeUi.has(TransformTool.Interpolation.NEAREST.description, exact = true)
        }
        staysOpen(rig, T.NUMBERS_LABEL, "$box: the Numbers sheet") { t.numbersOpen && sheetShown(rig, "Numbers") }
        t.numbersOpen = false
        rig.settle()

        // Free deform, from the mode dropdown.
        SmokeUi.click(TransformTool.Mode.FREE.label, exact = true)
        rig.settle()
        SmokeUi.click(TransformLabels17.FREE_DEFORM, exact = true)
        rig.settle()
        assertTrue(t.isMeshShown)
        val mesh = "TRANSFORM, Free deform"
        val meshRow1 = listOf(TransformLabels17.FREE_DEFORM, TransformLabels18.MESH_SIZE, TransformLabels17.RESET)
        val meshMore = listOf(TransformLabels17.SMOOTH, PointLabels.SELECT_ALL, T.DELETE_LABEL, T.INTERPOLATION_LABEL)
        val meshModes = listOf(TransformTool.Mode.FREE.label, TransformTool.Mode.DISTORT.label)
        assertEquals(
            "$mesh: the steppers are inside ${TransformLabels18.MESH_SIZE}, the other modes inside the mode dropdown",
            T.MESH_POPUP.associateWith { T.MESH_SIZE } + meshModes.associateWith { T.MODE },
            rig.spec.movedInto,
        )
        inspect(rig, mesh, (meshRow1 + meshMore + T.MESH_POPUP + meshModes).toSet())
        assertSplits(mesh, rig.spec) { _, _ -> listOf(T.MODE, T.MESH_SIZE, T.MESH_RESET) }
        assertEquals("$mesh at $dp dp: row 1", meshRow1, row1Handles())
        assertTrue("$mesh: \"3 × 3\"", SmokeUi.has(T.meshText(t.meshColumns, t.meshRows), exact = true))
        assertNull("$mesh: no hint while Select several is off", rig.spec.hint)
        assertTrue(StripTestKit.openMoreRow())
        assertEquals("$mesh at $dp dp: the More row", meshMore, moreHandles())
        assertEquals("$mesh at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        StripTestKit.closeMoreRow()
        popupKeepsRow(rig, T.INTERPOLATION_LABEL, "$mesh: the interpolation dropdown") {
            SmokeUi.has(TransformTool.Interpolation.NEAREST.description, exact = true)
        }
        // Back to the box modes, and out of Transform (commits the move-free lift).
        SmokeUi.click(TransformLabels17.FREE_DEFORM, exact = true)
        rig.settle()
        SmokeUi.click(TransformTool.Mode.FREE.label, exact = true)
        rig.settle()
        assertEquals(TransformTool.Mode.FREE, t.mode)
        rig.select(ToolId.BRUSH)
    }

    private fun text(rig: StripRig, dp: Int) {
        val X = TextStripItems
        for (vector in listOf(false, true)) {
            val k = key(ToolId.TEXT, vector)
            rig.select(ToolId.TEXT, vector)
            inspect(rig, k)
            // 256 dp in all: "Wrap" leaves row 1 only at 360 dp with the VECTOR cell.
            assertSplits(k, rig.spec) { w, chip ->
                listOf(X.EDIT, X.VERTICAL, X.LETTERS) + if (w == 360 && chip) emptyList() else listOf(X.WRAP)
            }
            assertEquals("$k at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        }

        // An open text: "Numbers" on the More row; its sheet outlives the row.
        rig.select(ToolId.TEXT)
        val tool = rig.c.currentTool as TextTool
        tool.startTextAt(200f, 150f)
        tool.setText("Strip")
        tool.confirmEditor()
        rig.settle()
        assertNotNull(tool.item)
        val open = "TEXT, an open text"
        inspect(rig, open, setOf(X.EDIT_LABEL, X.verticalLabel(false), X.LETTERS_LABEL, X.wrapLabel(tool), X.NUMBERS_LABEL, SNAP_LABEL))
        assertEquals("$open at $dp dp: row 1", listOf(X.EDIT_LABEL, X.verticalLabel(false), X.LETTERS_LABEL, X.wrapLabel(tool)), row1Handles())
        // "Edit text" (row 1) opens the editor dialog: the More row closes first (D25).
        assertTrue(StripTestKit.openMoreRow())
        SmokeUi.click(X.EDIT_LABEL, exact = true)
        rig.settle()
        assertTrue("$open: the editor", tool.editorOpen)
        assertFalse("$open: \"Edit text\" closed the More row", StripTestKit.moreRowShown() || rig.state.anyOpen)
        tool.cancelEditor()
        rig.settle()
        assertNotNull("$open: still open after the editor", tool.item)
        staysOpen(rig, X.NUMBERS_LABEL, "$open: the Numbers sheet") { tool.numbersOpen && sheetShown(rig, "Position & size") }
        tool.numbersOpen = false
        rig.settle()
        rig.select(ToolId.BRUSH)
    }

    private fun frameDivider(rig: StripRig, dp: Int) {
        val F = FrameStripItems
        // No frame layer: "New frame layer" and the status, no toggle.
        for (vector in listOf(false, true)) {
            val k = key(ToolId.FRAME_DIVIDER, vector)
            rig.select(ToolId.FRAME_DIVIDER, vector)
            inspect(rig, k)
            assertSplits(k, rig.spec) { _, _ -> listOf(F.NEW_LAYER, F.STATUS) }
            assertNull("$k: no toggle", StripTestKit.toggle())
            assertEquals("$k at $dp dp: the status", listOf(F.THEN_DIVIDE), row1Texts())
            assertEquals("$k at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        }
        rig.select(ToolId.FRAME_DIVIDER)
        val tool = rig.c.currentTool as FrameDividerTool
        SmokeUi.click(F.NEW_LAYER_LABEL, exact = true)
        rig.settle()
        assertTrue("the frame settings sheet", tool.settingsOpen && sheetShown(rig, "Frame layer"))
        tool.settingsOpen = false
        rig.settle()

        // A frame layer: three buttons on row 1, Snap to objects on the More row under the hint.
        assertTrue(tool.createFrameLayer())
        rig.settle()
        val ready = "FRAME_DIVIDER, a frame layer"
        inspect(rig, ready, setOf(F.GRID_LABEL, F.REMOVE_LABEL, F.SETTINGS_LABEL, SNAP_LABEL))
        assertSplits(ready, rig.spec) { _, _ -> listOf(F.GRID, F.REMOVE, F.SETTINGS) }
        assertEquals("$ready at $dp dp: row 1", listOf(F.GRID_LABEL, F.REMOVE_LABEL, F.SETTINGS_LABEL), row1Handles())
        assertTrue(StripTestKit.openMoreRow())
        assertEquals(listOf(F.DIVIDE_HINT), moreTexts())
        assertEquals(listOf(SNAP_LABEL), moreHandles())
        SmokeUi.click(F.REMOVE_LABEL, exact = true)
        rig.settle()
        assertTrue(tool.removeMode)
        assertEquals("$ready: the remove hint", F.REMOVE_HINT, rig.spec.hint)
        SmokeUi.click(F.REMOVE_LABEL, exact = true)
        rig.settle()
        // The grid dialog from row 1 closes the More row and shows.
        StripTestKit.openMoreRow()
        SmokeUi.click(F.GRID_LABEL, exact = true)
        rig.settle()
        assertFalse("$ready: the grid closed the More row", StripTestKit.moreRowShown())
        assertTrue("$ready: the grid dialog", tool.gridOpen && SmokeUi.has("Split into rows × columns", exact = true))
        tool.gridOpen = false
        rig.settle()
        // Frame settings likewise.
        StripTestKit.openMoreRow()
        SmokeUi.click(F.SETTINGS_LABEL, exact = true)
        rig.settle()
        assertFalse(StripTestKit.moreRowShown())
        assertTrue("$ready: the settings sheet", tool.settingsOpen && sheetShown(rig, "Frame layer"))
        tool.settingsOpen = false
        rig.settle()

        // Edited by hand: "Redraw", "New frame" and the status, no toggle (the More row it had closes).
        StripTestKit.openMoreRow()
        assertTrue(rig.c.editWholeLayer(rig.c.activeLayer, "Hand edit") { b -> Canvas(b).drawRect(10f, 10f, 30f, 30f, Paint().apply { color = 0xFFCC2222.toInt() }) })
        rig.settle()
        assertEquals(FrameDividerTool.Status.OUT_OF_SYNC, tool.status())
        val edited = "FRAME_DIVIDER, edited by hand"
        assertFalse("$edited: the More row closed", StripTestKit.moreRowShown() || rig.state.anyOpen)
        inspect(rig, edited, setOf(F.REDRAW_LABEL, F.NEW_FRAME_LABEL))
        assertSplits(edited, rig.spec) { _, _ -> listOf(F.REDRAW, F.NEW_FRAME, F.STATUS) }
        assertNull("$edited: no toggle", StripTestKit.toggle())
        assertEquals("$edited at $dp dp: row 1", listOf(F.REDRAW_LABEL, F.NEW_FRAME_LABEL), row1Handles())
        assertEquals(listOf(F.EDITED), row1Texts())
        assertEquals("$edited at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        SmokeUi.click(F.REDRAW_LABEL, exact = true)
        rig.settle()
        assertEquals(FrameDividerTool.Status.READY, tool.status())
        rig.select(ToolId.BRUSH)
    }
}
