package com.brushwork.paint.ui.textframes

import android.graphics.Matrix
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.placement.PlacementStripKit
import com.brushwork.paint.ui.placement.PlacementStripKit.assertSplits
import com.brushwork.paint.ui.placement.PlacementStripKit.clippedTexts
import com.brushwork.paint.ui.placement.PlacementStripKit.inspect
import com.brushwork.paint.ui.placement.PlacementStripKit.key
import com.brushwork.paint.ui.placement.PlacementStripKit.moreHandles
import com.brushwork.paint.ui.placement.PlacementStripKit.moreTexts
import com.brushwork.paint.ui.placement.PlacementStripKit.overlayWindowShown
import com.brushwork.paint.ui.placement.PlacementStripKit.popupKeepsRow
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Handles
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Texts
import com.brushwork.paint.ui.placement.SNAP_LABEL
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
 * v1.8 G (design §3.9 "Text frames"): the Text frames strip at 360 and 392 dp, each state's split
 * at the four configurations (with and without the VECTOR cell), every v1.7 handle reachable (the
 * inventory, with this test's record for the selected and linking states), no label cut by its
 * item's width, `legacyContent == null`:
 * - nothing selected: v1.7's draw hint alone on row 1; the More row "Threads", "Snap to objects";
 * - a frame selected whose story does not fit: row 1 "Edit story", "Link…", "Unlink here" (AUTO 1:
 *   the More row's front with the VECTOR cell), "Delete frame" and the red "+ N characters"; the
 *   More row "Wrap around picture" (its dropdown keeps the row open), "Threads", "Snap to objects"
 *   under v1.7's hint (about as wide as the row: the host may end it with "…");
 * - linking: "Cancel link" and the link hint on row 1.
 * "Edit story" opens the story editor (an overlay dialog) and "Link…" waits for the next canvas
 * touch: both close an open More row first (D25).
 * Widths against the planning table (rule 4, measured): "Edit story" 64 (two lines after its
 * icon) and "Link…" 60 instead of 92 and 72, which leaves room for the overset note (64, reserved
 * while the story fits) on row 1: the non-AUTO sum is 240.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.textframes.textframestripsandbox"])
class TextFrameStripTest {

    private val F = TextFrameStripItems

    @Test
    fun theTextFramesStripKeepsItsControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        PlacementStripKit.atEachWidth { rig, dp -> flow(rig, dp) }
        dog.interrupt()
    }

    private fun flow(rig: StripRig, dp: Int) {
        // Nothing selected.
        for (vector in listOf(false, true)) {
            val k = key(ToolId.TEXT_FRAMES, vector)
            rig.select(ToolId.TEXT_FRAMES, vector)
            inspect(rig, k)
            assertSplits(k, rig.spec) { _, _ -> listOf(F.STATUS) }
            assertNull("$k: no hint (the draw hint is on row 1)", rig.spec.hint)
            assertEquals("$k at $dp dp: the draw hint", listOf(F.DRAW_HINT), row1Texts())
            assertTrue(StripTestKit.openMoreRow())
            assertEquals("$k at $dp dp: the More row", listOf(F.THREADS_LABEL, SNAP_LABEL), moreHandles())
            assertEquals("$k at $dp dp: texts fit", emptyList<String>(), clippedTexts())
            StripTestKit.closeMoreRow()
        }

        // A frame whose story does not fit.
        rig.select(ToolId.TEXT_FRAMES)
        val c = rig.c
        val tool = c.currentTool as TextFrameTool
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
        FrameFixtures.drag(c, 40f, 40f, 200f, 120f)
        rig.settle()
        assertTrue("the story editor opened for the new frame", tool.story.isOpen && tool.story.editingNew)
        assertTrue("the editor is an overlay window", overlayWindowShown(rig))
        tool.story.setText(FrameFixtures.STORY)
        tool.story.setSizePx(16f)
        tool.story.confirmEditor()
        rig.settle()
        val frame = tool.selected
        assertNotNull("the new frame is selected", frame)
        val overset = tool.oversetCount(frame)
        assertTrue("the story goes on beyond the frame", overset > 0)
        val sel = "TEXT_FRAMES, a frame selected"
        val row1 = listOf(F.EDIT_LABEL, F.LINK_LABEL, F.UNLINK_LABEL, F.DELETE_LABEL)
        val more = listOf(F.wrapLabel(false), F.THREADS_LABEL, SNAP_LABEL)
        inspect(rig, sel, (row1 + more).toSet())
        assertSplits(sel, rig.spec) { _, chip ->
            if (chip) listOf(F.EDIT, F.LINK, F.DELETE, F.OVERSET) else listOf(F.EDIT, F.LINK, F.UNLINK, F.DELETE, F.OVERSET)
        }
        assertEquals("$sel at $dp dp: row 1", row1, row1Handles())
        assertEquals("$sel at $dp dp: the overset note", listOf(F.overset(overset)), row1Texts())
        assertTrue("$sel: described as in v1.7", SmokeUi.has(F.oversetDescription(overset), exact = true))
        assertFalse("$sel: nothing to unlink", SmokeUi.isEnabled(F.UNLINK_LABEL))
        assertEquals(F.FRAME_HINT, rig.spec.hint)
        assertTrue(StripTestKit.openMoreRow())
        assertEquals("$sel: the hint heads the More row", listOf(F.FRAME_HINT), moreTexts())
        assertEquals("$sel at $dp dp: the More row", more, moreHandles())
        // v1.7's hint (70 characters, the host's one line at 13 sp) is a few dp wider than the
        // More row's line in the test font (332 dp at 360 dp, 364 dp at 392 dp). The host ends a
        // hint alone on its line with "…" (a host rule, not an item's width).
        val clipped = clippedTexts().filterNot { it.startsWith(F.FRAME_HINT) }
        assertEquals("$sel at $dp dp: texts fit", emptyList<String>(), clipped)
        StripTestKit.closeMoreRow()
        popupKeepsRow(rig, F.wrapLabel(false), "$sel: the wrap dropdown") { SmokeUi.has("No wrap", exact = true) }

        // Threads on the More row.
        val threads = tool.showThreads
        SmokeUi.click(F.THREADS_LABEL, exact = true)
        rig.settle()
        assertEquals(!threads, tool.showThreads)
        SmokeUi.click(F.THREADS_LABEL, exact = true)
        rig.settle()
        assertEquals(threads, tool.showThreads)
        StripTestKit.closeMoreRow()

        // "Edit story": the story editor over the strip; the More row closes first (D25).
        assertTrue(StripTestKit.openMoreRow())
        SmokeUi.click(F.EDIT_LABEL, exact = true)
        rig.settle()
        assertTrue("$sel: the story editor", tool.story.isOpen && overlayWindowShown(rig))
        assertFalse("$sel: \"Edit story\" closed the More row", StripTestKit.moreRowShown() || rig.state.anyOpen)
        tool.story.cancelEditor()
        rig.settle()
        assertFalse(tool.story.isOpen)

        // Linking: "Cancel link" and the link hint. The next canvas touch picks the next frame, so
        // "Link…" closes the More row (an open row would take that touch, D25).
        assertTrue(StripTestKit.openMoreRow())
        SmokeUi.click(F.LINK_LABEL, exact = true)
        rig.settle()
        assertTrue(tool.linkFrom === frame)
        assertFalse("\"Link…\" closed the More row", StripTestKit.moreRowShown() || rig.state.anyOpen)
        val link = "TEXT_FRAMES, linking"
        inspect(rig, link, setOf(F.CANCEL_LINK_LABEL, F.THREADS_LABEL, SNAP_LABEL))
        assertSplits(link, rig.spec) { _, _ -> listOf(F.CANCEL_LINK, F.STATUS) }
        assertEquals("$link at $dp dp: row 1", listOf(F.CANCEL_LINK_LABEL), row1Handles())
        assertEquals(listOf(LINK_HINT), row1Texts())
        assertEquals("$link at $dp dp: texts fit", emptyList<String>(), clippedTexts())
        SmokeUi.click(F.CANCEL_LINK_LABEL, exact = true)
        rig.settle()
        assertNull(tool.linkFrom)

        // Delete frame: one step, nothing selected again.
        val steps = c.undoManager.undoCount
        SmokeUi.click(F.DELETE_LABEL, exact = true)
        rig.settle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(tool.selected)
        assertEquals(listOf(F.DRAW_HINT), row1Texts())
        rig.select(ToolId.BRUSH)
    }
}
