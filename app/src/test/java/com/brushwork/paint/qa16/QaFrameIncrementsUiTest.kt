package com.brushwork.paint.qa16

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameGeometry
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.roundToInt

/**
 * v1.6 final QA (increments everywhere: text frames) on the real editor at the user's phone size,
 * with real fingers: Text frames in the tool menu, a frame dragged out on the canvas from (40, 40)
 * to (183, 127), its text typed, OK; a finger drag on the frame by (+37, +12); its bottom-right
 * handle dragged by (+43, +20). With "#" on (More › Increments…, Length 25 px) the drawn frame is
 * whole steps big from where the finger went down (150 × 75), the move goes by whole steps per
 * axis (+25, 0) with the travel in the info chip, and the resized edges land on whole steps from
 * the fixed corner (200 × 100). With "#" off every gesture is exact (v1.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.frameincrementssandbox"])
class QaFrameIncrementsUiTest {

    private fun box(l: Layer) = FrameFixtures.boxOf(l)

    /** Text frames from the tool menu; a frame dragged out from (40, 40) to (183, 127) with "Frame" typed in it. */
    private fun drawFrame(s: ChromeScreen): Pair<TextFrameTool, Layer> {
        val c = s.c
        QaCurves.tool(s, ToolId.TEXT_FRAMES.label)
        assertEquals(ToolId.TEXT_FRAMES, c.activeToolId)
        val tool = c.currentTool as TextFrameTool
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
        if (c.snapping.enabled) {
            if (has("Snap to objects", exact = true) || has("Snap", exact = true)) QaCurves.snapOff(c) else c.snapping.enabled = false
        }
        assertFalse(c.snapping.enabled)
        val layers = c.doc.layers.size
        QaCurves.drag(s, Vec2(40f, 40f), Vec2(143f, 87f))
        assertTrue("the story editor opened for the new frame", tool.story.isOpen)
        SmokeUi.field("Text").type("Frame")
        settle()
        click("OK", exact = true)
        assertEquals("one new frame layer", layers + 1, c.doc.layers.size)
        return tool to c.activeLayer
    }

    /** The bottom-right handle of [l]'s frame dragged by [by] (document px). */
    private fun resize(s: ChromeScreen, l: Layer, by: Vec2, held: () -> Unit = {}) {
        val at = FrameGeometry.Handle.BOTTOM_RIGHT.at(box(l))
        QaCurves.drag(s, at, by, held = held)
    }

    @Test
    fun framesStepByTheLengthIncrementOnAndAreExactOff() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("\"#\" on (Length 25): draw, move, resize") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(length = "25")
            val (_, frame) = drawFrame(s)
            var b = box(frame)
            assertEquals("drawn from where the finger went down", 40f, b.left, 0.51f)
            assertEquals(40f, b.top, 0.51f)
            assertEquals("143 px → 6 steps", 150f, b.width(), 0.51f)
            assertEquals("87 px → 3 steps", 75f, b.height(), 0.51f)

            // Move (+37, +12): +25, 0.
            val i0 = FrameFixtures.itemOf(frame)
            var held: String? = null
            QaCurves.drag(s, Vec2(b.centerX(), b.centerY()), Vec2(37f, 12f)) { held = c.increments.readout }
            val i1 = FrameFixtures.itemOf(frame)
            assertEquals("x: one step", i0.cx + 25f, i1.cx, 1e-3f)
            assertEquals("y: under half a step", i0.cy, i1.cy, 1e-3f)
            // (The frames' chip names the unit once, "+25, 0 px", where Transform and Text say "+25 px, 0 px".)
            assertTrue("the info chip: $held", held == "+25, 0 px" || held == "+25 px, 0 px")
            assertNull(c.increments.readout)

            // The bottom-right handle (+43, +20): the edges land on whole steps from the fixed corner.
            b = box(frame)
            val w0 = b.width()
            val h0 = b.height()
            held = null
            resize(s, frame, Vec2(43f, 20f)) { held = c.increments.readout }
            val r = box(frame)
            assertEquals("the left edge stays", b.left, r.left, 0.51f)
            assertEquals("the top edge stays", b.top, r.top, 0.51f)
            assertEquals("width on whole steps", ((w0 + 43f) / 25f).roundToInt() * 25f, r.width(), 0.51f)
            assertEquals("height on whole steps", ((h0 + 20f) / 25f).roundToInt() * 25f, r.height(), 0.51f)
            assertTrue("a readout while resizing: $held", held != null)
            QaCurves.shot(s, "frames-increments")
            Smoke.assertQuiet(c, "frames on")
        }
        h.section("\"#\" off: draw, move and resize follow the finger (v1.5)") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOff()
            val (_, frame) = drawFrame(s)
            var b = box(frame)
            assertEquals(143f, b.width(), 0.51f)
            assertEquals(87f, b.height(), 0.51f)
            val i0 = FrameFixtures.itemOf(frame)
            QaCurves.drag(s, Vec2(b.centerX(), b.centerY()), Vec2(37f, 12f))
            val i1 = FrameFixtures.itemOf(frame)
            assertEquals(i0.cx + 37f, i1.cx, 0.05f)
            assertEquals(i0.cy + 12f, i1.cy, 0.05f)
            b = box(frame)
            resize(s, frame, Vec2(43f, 20f))
            val r = box(frame)
            assertEquals(b.width() + 43f, r.width(), 0.51f)
            assertEquals(b.height() + 20f, r.height(), 0.51f)
            assertNull(c.increments.readout)
            Smoke.assertQuiet(c, "frames off")
        }
        dog.interrupt()
        h.finish()
    }
}
