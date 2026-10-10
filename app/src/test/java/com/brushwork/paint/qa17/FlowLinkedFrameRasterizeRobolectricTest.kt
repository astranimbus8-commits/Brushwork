package com.brushwork.paint.qa17

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.pixels
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.TransformLabels17
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 integration flow (design §6.2, areas F and D): the MIDDLE frame of a 3-frame linked text
 * chain put through the Transform tool's "Rasterize and deform" for Distort
 * (`TransformTool.rasterizeAndDeform`). The pending move is its own step ("Transform"), the
 * rasterize its own ("Rasterize text") with the story re-flowed into the frames left INSIDE it;
 * the frame becomes plain pixels, the chain is [f1, f3] and the story is whole (I9, I1); the
 * corner dragged in Distort is a third step ("Transform"). Each undo restores the document
 * exactly as it was (pixels and data of every layer) and nothing re-flows on undo or redo.
 */
@RunWith(RobolectricTestRunner::class)
class FlowLinkedFrameRasterizeRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun theMiddleFrameRasterizedForDistortLeavesTheStoryWholeInItsOwnSteps() {
        val s = setup(context)
        val c = s.c
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 170f, 180f, 270f)
        assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
        val story = itemOf(f1).thread.storyId
        val text = c.textThreads.story(story)!!.text
        assertWhole(c, story)
        val f2Item = itemOf(f2)

        c.selectLayer(f2)
        c.selectTool(ToolId.TRANSFORM)
        idle()
        val tt = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertEquals("a frame is lifted as text", TransformTool.Lifted.TEXT, tt.lifted)
        assertEquals(TransformLabels17.RASTERIZE_TO_DEFORM, tt.modeRefusal(TransformTool.Mode.DISTORT))
        assertEquals(TransformLabels17.RASTERIZE_TO_FREE_DEFORM, tt.modeRefusal(TransformTool.Mode.MESH))
        assertTrue(tt.canRasterizeFor(TransformTool.Mode.DISTORT))

        val s0 = snapshot(c)
        val steps = c.undoManager.undoCount
        // A move pending, then "Rasterize and deform": the move, then the rasterize, each a step.
        tt.moveBy(12f, 0f)
        assertTrue(tt.rasterizeAndDeform(TransformTool.Mode.DISTORT))
        idle()
        assertEquals("the move, then the rasterize", steps + 2, c.undoManager.undoCount)
        assertEquals(TransformTool.RASTERIZE_TEXT_LABEL, c.undoManager.undoLabel)
        assertNull("the middle frame is plain pixels", f2.textData)
        assertEquals("the chain is the frames left", listOf(f1, f3), chainOf(c, f1))
        assertEquals("not a letter lost", text, c.textThreads.story(story)!!.text)
        assertWhole(c, story)
        assertEquals(TransformTool.Lifted.PIXELS, tt.lifted)
        assertEquals(TransformTool.Mode.DISTORT, tt.mode)
        val raster = snapshot(c)

        // A corner dragged in Distort, applied: a third step ("Transform"), the frames untouched.
        val q = tt.transformState!!.corner(2)
        c.pointerDown(ToolPoint(q.x, q.y))
        for (i in 1..4) c.pointerMove(ToolPoint(q.x + 4f * i, q.y + 2f * i))
        c.pointerUp(ToolPoint(q.x + 16f, q.y + 8f))
        assertTrue(tt.transformState!!.isDistorted)
        tt.commit()
        idle()
        assertEquals(steps + 3, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        assertFalse("the pixels are distorted", pixels(f2.bitmap).contentEquals(raster.entries.first { it.first === f2 }.second))
        assertEquals(listOf(f1, f3), chainOf(c, f1))
        assertWhole(c, story)
        val distorted = snapshot(c)

        // Undo, one step at a time; nothing re-flows on undo or redo.
        val heals = c.textThreads.healCount
        c.undo()
        idle()
        assertSnapshot(c, raster, "undo Distort")
        c.undo()
        idle()
        assertNotNull("undo Rasterize text: the frame is back", f2.textData)
        assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
        assertEquals("the moved frame", f2Item.cx + 12f, itemOf(f2).cx, 1e-3f)
        assertWhole(c, story)
        val moved = snapshot(c)
        c.undo()
        idle()
        assertSnapshot(c, s0, "undo the move")
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        idle()
        assertSnapshot(c, moved, "redo the move")
        c.redo()
        idle()
        assertSnapshot(c, raster, "redo Rasterize text")
        c.redo()
        idle()
        assertSnapshot(c, distorted, "redo Distort")
        assertEquals("undo / redo never re-flow", heals, c.textThreads.healCount)
    }
}
