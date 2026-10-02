package com.brushwork.paint.tools.text.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.frames.FrameFixtures.boxOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.drag
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Frame increments (v1.6, §3.4c "D: frame move and resize (Length)", I8): with increments off
 * every frame gesture is exact (v1.5 behaviour); with a Length step on, a move travels in
 * multiples of the step from where the finger went down, a resized edge and a drawn frame land on
 * whole steps from the fixed edge / start corner, and the readout shows the stepped value while
 * the finger is down. Typed pill values are never stepped.
 */
@RunWith(RobolectricTestRunner::class)
class FrameIncrementsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun stepsOn(s: FrameFixtures.Setup, length: Float = 10f) =
        s.c.increments.update { it.copy(enabled = true, lengthPx = length) }

    @Test
    fun withIncrementsOffGesturesAreExact() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 183f, 127f, text = "Exact")
        val b = boxOf(f)
        assertEquals(163f, b.width(), 0.51f)
        assertEquals(107f, b.height(), 0.01f)
        val before = itemOf(f)
        drag(s.c, 100f, 70f, 113f, 77f)
        assertEquals(before.cx + 13f, itemOf(f).cx, 0f)
        assertEquals(before.cy + 7f, itemOf(f).cy, 0f)
        assertNull(s.c.increments.readout)
    }

    @Test
    fun aMoveTravelsInWholeSteps() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 180f, 120f, text = "Stepped")
        stepsOn(s)
        val before = itemOf(f)
        s.c.pointerDown(ToolPoint(100f, 70f))
        s.c.pointerMove(ToolPoint(106f, 70f))
        s.c.pointerMove(ToolPoint(113f, 77f))
        assertNotNull("the readout shows the stepped travel", s.c.increments.readout)
        assertEquals("+10, +10 px", s.c.increments.readout)
        s.c.pointerUp(ToolPoint(113f, 77f))
        assertNull("and goes away with the finger", s.c.increments.readout)
        assertEquals(before.cx + 10f, itemOf(f).cx, 0f)
        assertEquals(before.cy + 10f, itemOf(f).cy, 0f)
        // A short travel (under half a step) stays put.
        val at = itemOf(f)
        drag(s.c, 100f, 70f, 118f, 73f)
        assertEquals(at.cx + 20f, itemOf(f).cx, 0f)
        assertEquals(at.cy, itemOf(f).cy, 0f)
    }

    @Test
    fun aResizedEdgeLandsOnWholeStepsFromTheFixedEdge() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 180f, 120f, text = "Stepped")
        stepsOn(s, 25f)
        s.tool.select(f)
        val b = boxOf(f)
        drag(s.c, b.right, b.bottom, b.right + 37f, b.bottom + 12f)
        val now = boxOf(f)
        assertEquals("the left and top edges stay", b.left, now.left, 0.01f)
        assertEquals(b.top, now.top, 0.01f)
        // 160 + 37 = 197 → 200 (8 steps); 100 + 12 = 112 → 100 (4 steps).
        assertEquals(200f, now.width(), 0.51f)
        assertEquals(100f, now.height(), 0.01f)
    }

    @Test
    fun aDrawnFrameIsWholeStepsBig() {
        val s = setup(context)
        stepsOn(s)
        val f = newFrame(s, 20f, 20f, 183f, 127f, text = "Stepped")
        val b = boxOf(f)
        assertEquals(20f, b.left, 0.51f)
        assertEquals(20f, b.top, 0.01f)
        assertEquals(160f, b.width(), 0.51f)
        assertEquals(110f, b.height(), 0.01f)
    }

    @Test
    fun typedPillValuesAreNeverStepped() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 180f, 120f, text = "Typed")
        stepsOn(s)
        s.tool.select(f)
        val pos = s.tool.objectPosition!!
        pos.setPosition(137.25f, null)
        assertEquals(137.25f, itemOf(f).cx, 0f)
        assertEquals(TextFrameTool.MOVE_LABEL, s.c.undoManager.undoLabel)
        assertTrue(s.c.textThreads.isFrame(f))
    }
}
