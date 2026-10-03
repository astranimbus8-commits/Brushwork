package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.boxOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.drag
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A frame drawn by dragging from the previous frame's out-port (the red "+") has its top-left
 * corner handle exactly on that port (v1.6 integration, item 20). With the new frame selected:
 * a TAP that starts on the port is the port's (it loads or unloads link mode, as anywhere else),
 * while a DRAG from there is still the handle's (it resizes the new frame). The selected frame's
 * own bottom-right handle, 13 dp from its own out-port on each axis, keeps working as before.
 */
@RunWith(RobolectricTestRunner::class)
class FramePortUnderHandleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Frame 1 overflowing, and frame 2 drawn from its out-port (selected); returns them and the port. */
    private fun chain(s: FrameFixtures.Setup): Triple<Layer, Layer, Vec2> {
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        assertTrue(itemOf(f1).thread.overset)
        val port = FramePorts.outPort(s.c.viewTransform, boxOf(f1))
        drag(s.c, port.x, port.y, 330f, 260f)
        idle()
        val chain = chainOf(s.c, f1)
        assertEquals(2, chain.size)
        val f2 = chain[1]
        assertSame("the new frame is selected", f2, s.tool.selected)
        assertEquals("its top-left handle sits on the port", port.x, boxOf(f2).left, 0.51f)
        assertEquals(port.y, boxOf(f2).top, 0.01f)
        return Triple(f1, f2, port)
    }

    @Test
    fun aTapOnThePortUnderTheNewFramesCornerIsThePorts() {
        val s = setup(context)
        val (f1, f2, port) = chain(s)
        val steps = s.c.undoManager.undoCount
        val data1 = f1.textData
        val data2 = f2.textData
        tap(s.c, port.x, port.y)
        idle()
        assertFalse("the tap is not the new frame's (no story editor)", s.tool.story.isOpen)
        assertSame("the tap loads the out-port it started on", f1, s.tool.linkFrom)
        assertEquals("a tap records nothing", steps, s.c.undoManager.undoCount)
        assertSame(data1, f1.textData)
        assertSame(data2, f2.textData)
        // Tapped again: unloaded, as any loaded port.
        tap(s.c, port.x, port.y)
        idle()
        assertNull(s.tool.linkFrom)
        assertFalse(s.tool.story.isOpen)
        // Loading the port selected its frame (as a tap on any out-port does). With the new frame
        // selected again, a finger just off the port (still in the handle's reach) is the handle's tap.
        assertSame(f1, s.tool.selected)
        s.tool.select(f2)
        tap(s.c, port.x + 14f, port.y + 14f)
        idle()
        assertTrue("a tap on the selected frame opens its story", s.tool.story.isOpen)
        assertNull(s.tool.linkFrom)
        s.tool.story.cancelEditor()
        assertEquals(steps, s.c.undoManager.undoCount)
    }

    @Test
    fun aDragFromThatCornerStillResizesTheNewFrame() {
        val s = setup(context)
        val (f1, f2, port) = chain(s)
        val before = boxOf(f2)
        val steps = s.c.undoManager.undoCount
        drag(s.c, port.x, port.y, port.x - 30f, port.y + 20f)
        idle()
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals(TextFrameTool.RESIZE_LABEL, s.c.undoManager.undoLabel)
        val after = boxOf(f2)
        assertEquals("the corner followed the finger", before.left - 30f, after.left, 0.51f)
        assertEquals(before.top + 20f, after.top, 0.51f)
        assertEquals("the opposite edges stay", before.right, after.right, 0.51f)
        assertEquals(before.bottom, after.bottom, 0.51f)
        assertEquals("no frame was drawn", 2, chainOf(s.c, f1).size)
        assertNull("no link mode", s.tool.linkFrom)
        assertWhole(s.c, itemOf(f1).thread.storyId)
    }

    @Test
    fun theSelectedFramesOwnBottomRightHandleAndOutPortStayApart() {
        val s = setup(context)
        val (_, f2, _) = chain(s)
        val b = boxOf(f2)
        val own = FramePorts.outPort(s.c.viewTransform, b)
        // The handle: a tap opens the story (the selected frame), a drag resizes.
        tap(s.c, b.right, b.bottom)
        idle()
        assertTrue(s.tool.story.isOpen)
        assertNull(s.tool.linkFrom)
        s.tool.story.cancelEditor()
        drag(s.c, b.right, b.bottom, b.right + 20f, b.bottom + 10f)
        idle()
        assertEquals(TextFrameTool.RESIZE_LABEL, s.c.undoManager.undoLabel)
        assertEquals(b.right + 20f, boxOf(f2).right, 0.51f)
        // Its own out-port: a tap loads it.
        val port = FramePorts.outPort(s.c.viewTransform, boxOf(f2))
        assertEquals(own.x + 20f, port.x, 0.51f)
        tap(s.c, port.x, port.y)
        idle()
        assertSame(f2, s.tool.linkFrom)
        assertFalse(s.tool.story.isOpen)
        s.tool.cancelLink()
    }
}
