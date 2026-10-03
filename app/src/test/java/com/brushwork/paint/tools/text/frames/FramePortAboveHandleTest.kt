package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.frames.FrameFixtures.boxOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.drag
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Review of v1.6 integration item 20 (a frame drawn from the previous frame's out-port has its
 * top-left handle on that port; a tap there is the port's):
 * - what the overlay shows agrees with what a tap does: the port is drawn ABOVE the selected
 *   frame's handle that lies on it (before, the handle hid it, so the tap went to something the
 *   user could not see), while the selected frame's other handles still show;
 * - only OTHER frames' ports take a tap from a handle: a tap just outside the selected frame's
 *   bottom-right corner, nearer that handle than its own out-port (and outside the port's
 *   square), stays the handle's tap (the story opens), as before integration.
 */
@RunWith(RobolectricTestRunner::class)
class FramePortAboveHandleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Frame 1 overflowing, and frame 2 drawn from its out-port (selected); returns them and the port (screen = document). */
    private fun chain(s: FrameFixtures.Setup): Triple<Layer, Layer, Vec2> {
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        assertTrue(itemOf(f1).thread.overset)
        val port = FramePorts.outPort(s.c.viewTransform, boxOf(f1))
        drag(s.c, port.x, port.y, 330f, 260f)
        idle()
        val chain = chainOf(s.c, f1)
        assertEquals(2, chain.size)
        val f2 = chain[1]
        assertSame(f2, s.tool.selected)
        assertEquals("its top-left handle sits on the port", port.x, boxOf(f2).left, 0.51f)
        return Triple(f1, f2, port)
    }

    private fun overlay(s: FrameFixtures.Setup): Bitmap =
        Bitmap.createBitmap(s.doc.width, s.doc.height, Bitmap.Config.ARGB_8888).also { s.tool.drawOverlay(Canvas(it), s.c.viewTransform) }

    @Test
    fun thePortUnderTheNewFramesCornerIsDrawnAboveTheHandle() {
        val s = setup(context)
        val (_, f2, port) = chain(s)
        val t = s.c.viewTransform
        val bmp = overlay(s)
        // Inside the port's white square, left of its arrow: the handle's accent disc would cover it.
        val beside = bmp.getPixel((port.x - t.dp(5f)).toInt(), port.y.toInt())
        assertEquals("the port's square shows over the handle: ${Integer.toHexString(beside)}", 0xFFFFFFFF.toInt(), beside)
        val centre = bmp.getPixel(port.x.toInt(), port.y.toInt())
        assertNotEquals("the port's arrow, not the handle: ${Integer.toHexString(centre)}", FrameOverlay.ACCENT, centre)
        // The selected frame's other handles still show (above the frames).
        val b = boxOf(f2)
        assertEquals("the top-right handle", FrameOverlay.ACCENT, bmp.getPixel(b.right.toInt(), b.top.toInt()))
        assertEquals("the bottom-right handle", FrameOverlay.ACCENT, bmp.getPixel(b.right.toInt(), b.bottom.toInt()))
    }

    @Test
    fun aTapJustOutsideTheSelectedFramesCornerIsStillItsHandles() {
        val s = setup(context)
        val (_, f2, _) = chain(s)
        val t = s.c.viewTransform
        val b = boxOf(f2)
        val steps = s.c.undoManager.undoCount
        // 5 dp out from the bottom-right corner on each axis: 7 dp from the handle, 11 dp from the
        // frame's own out-port (13 dp out), outside the port's 14 dp square.
        val d = t.screenToDocLength(t.dp(5f))
        tap(s.c, b.right + d, b.bottom + d)
        idle()
        assertNull("the frame's own port is not loaded", s.tool.linkFrom)
        assertTrue("a tap on the selected frame's handle opens its story", s.tool.story.isOpen)
        assertSame(f2, s.tool.storyTarget)
        s.tool.story.cancelEditor()
        assertEquals(steps, s.c.undoManager.undoCount)
        // On the port's square itself, the port: it loads.
        val own = FramePorts.outPort(t, b)
        tap(s.c, own.x, own.y)
        idle()
        assertSame(f2, s.tool.linkFrom)
        s.tool.cancelLink()
    }
}
