package com.brushwork.paint.ui.editor

import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.tools.ToolId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (F3, D25): [CanvasView.onConsumeTouch], the canvas side of "a canvas touch closes the More
 * row and is consumed", through a real view driven with MotionEvents. A gesture whose first
 * finger the hook consumes draws nothing, pans and zooms nothing and taps nothing until every
 * pointer lifts, whatever joins it (a second finger, a pen); the hook is asked once per gesture,
 * at its first finger, and the next gesture it declines works as before. H's `MoreRowCloseTest`
 * covers the editor wiring (the strip's `StripState` closing on that touch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi")
class CanvasConsumedTouchRobolectricTest {
    private lateinit var c: EditorController

    @After
    fun tearDown() {
        Smoke.pump(50)
        Smoke.assertQuiet(c, "end of test")
        c.dispose()
    }

    @Test
    fun aConsumedGestureDoesNothingUntilEveryPointerLifts() {
        ShadowLog.clear()
        Smoke.scopeErrors.clear()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        c = Smoke.controller(activity, Smoke.document(400, 300, 2))
        val view = CanvasView(activity, c)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        assertTrue(view.width > 0 && view.height > 0)
        val touch = Smoke.Touch(view)
        c.tools
        c.selectTool(ToolId.BRUSH)
        c.brush = c.brush.copy(size = 8f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 0.1f, taperStart = 0f, taperEnd = 0f, scatter = 0f, grain = 0f)
        c.color = RED
        Smoke.pump(20)

        // The hook: consumes while `open` (and then closes, as EditorScreen's does).
        var open = true
        var asked = 0
        view.onConsumeTouch = { asked++; if (open) { open = false; true } else false }
        fun screen(x: Float, y: Float): Pair<Float, Float> = c.viewTransform.docToScreen(x, y).let { it.x to it.y }
        val layer = c.activeLayer
        val undo0 = c.undoManager.undoCount
        val view0 = Matrix(c.viewTransform.matrix)

        // 1. One finger drawing: nothing painted, nothing recorded.
        touch.idle(200)
        touch.stroke(screen(60f, 150f), screen(200f, 150f), screen(340f, 150f))
        assertEquals("asked once, at the first finger", 1, asked)
        assertEquals("no stroke", 0, layer.bitmap.getPixel(200, 150))
        assertEquals("no step", undo0, c.undoManager.undoCount)

        // 2. Two fingers pinching: the view neither zooms nor pans.
        open = true
        touch.idle(200)
        touch.pinch(screen(150f, 150f), screen(250f, 150f), screen(100f, 150f), screen(300f, 150f))
        assertEquals(2, asked)
        assertEquals("no zoom, no pan", view0, c.viewTransform.matrix)

        // 3. A two-finger tap (undo) after a real stroke: it undoes nothing.
        touch.idle(200)
        touch.stroke(screen(60f, 100f), screen(340f, 100f))
        assertEquals(3, asked)
        assertEquals("a declined gesture draws as before", RED, layer.bitmap.getPixel(200, 100))
        val undo1 = c.undoManager.undoCount
        assertNotEquals(undo0, undo1)
        open = true
        touch.idle(200)
        touch.twoFingerTap(screen(150f, 200f), screen(250f, 200f))
        assertEquals(4, asked)
        assertEquals("the tap undid nothing", undo1, c.undoManager.undoCount)
        assertEquals(RED, layer.bitmap.getPixel(200, 100))

        // 4. A pen joining a consumed finger draws nothing either.
        open = true
        touch.idle(200)
        val (fx, fy) = screen(100f, 250f)
        val (px, py) = screen(150f, 220f)
        val (qx, qy) = screen(330f, 220f)
        touch.send(MotionEvent.ACTION_DOWN, P(0, fx, fy))
        touch.idle(16)
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, fx, fy), P(1, px, py, MotionEvent.TOOL_TYPE_STYLUS), index = 1)
        for (s in 1..8) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, P(0, fx, fy), P(1, px + (qx - px) * s / 8f, py + (qy - py) * s / 8f, MotionEvent.TOOL_TYPE_STYLUS))
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, fx, fy), P(1, qx, qy, MotionEvent.TOOL_TYPE_STYLUS), index = 1)
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, P(0, fx, fy))
        touch.idle(50)
        assertEquals("asked once for the whole gesture", 5, asked)
        assertEquals("the pen drew nothing", 0, layer.bitmap.getPixel(240, 220))
        assertEquals(undo1, c.undoManager.undoCount)

        // 5. Declined again: the next stroke draws.
        touch.idle(200)
        touch.stroke(screen(60f, 50f), screen(340f, 50f))
        assertEquals(6, asked)
        assertEquals(RED, layer.bitmap.getPixel(200, 50))
        assertEquals(undo1 + 1, c.undoManager.undoCount)
        assertEquals("the view never moved", view0, c.viewTransform.matrix)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
