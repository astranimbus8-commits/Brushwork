package com.brushwork.paint.qa

import android.view.MotionEvent
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa.QaTouch.RawPointer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.lift.VectorLift
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA (§4.9 with fingers and a pen on the 392 dp phone): gestures on a vector layer through the
 * real canvas view — the long-press color pick, a resting palm while the pen draws, a second
 * finger that turns a stroke into a pinch, Lasso's deselect tap, Transform's object taps,
 * Delete and two-finger undo while objects are lifted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorGestureQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private lateinit var vec: Layer
    private val ink = 0xFF1A2A6C.toInt()

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    private fun setup() {
        r = VectorQaRig(480, 320)
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        vec = c.activeLayer
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 20f)
        r.stroke(40f to 80f, 240f to 90f, 440f to 80f); r.checkpoint("s1")
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        r.stroke(40f to 200f, 240f to 210f, 440f to 200f); r.checkpoint("s2")
    }

    @Test
    fun aLongPressPicksTheColorAndLeavesNoStrokeBehind() {
        setup()
        c.color = 0xFFE02020.toInt()
        val (x, y) = r.screen(240f, 89f)
        r.touch.idle(250)
        r.touch.raw(MotionEvent.ACTION_DOWN, listOf(RawPointer(0, x, y)))
        r.touch.idle(700) // still: the long press fires
        assertTrue("picking", c.holdPicking)
        r.touch.raw(MotionEvent.ACTION_UP, listOf(RawPointer(0, x, y)))
        r.touch.idle(60)
        assertEquals("the stroke's color was picked", ink, c.color)
        r.checkpoint("long-press pick", steps = 0)
        assertEquals(2, vec.vector!!.objects.size)
    }

    @Test
    fun aRestingPalmNeverDrawsWhileThePenDraws() {
        setup()
        val pen = MotionEvent.TOOL_TYPE_STYLUS
        val p0 = r.screen(60f, 140f)
        val palm = r.screen(300f, 300f)
        r.touch.idle(250)
        r.touch.raw(MotionEvent.ACTION_DOWN, listOf(RawPointer(0, p0.first, p0.second, pen, 0.4f)))
        var last = p0
        for (k in 1..10) {
            r.touch.idle(16)
            last = r.screen(60f + 30f * k, 140f + 2f * k)
            r.touch.raw(MotionEvent.ACTION_MOVE, listOf(RawPointer(0, last.first, last.second, pen, 0.4f + 0.04f * k)))
        }
        // The palm lands and stays while the pen goes on.
        r.touch.raw(MotionEvent.ACTION_POINTER_DOWN, listOf(RawPointer(0, last.first, last.second, pen, 0.8f), RawPointer(1, palm.first, palm.second)), index = 1)
        for (k in 11..16) {
            r.touch.idle(16)
            last = r.screen(60f + 30f * k, 140f + 2f * k)
            r.touch.raw(MotionEvent.ACTION_MOVE, listOf(RawPointer(0, last.first, last.second, pen, 0.8f), RawPointer(1, palm.first + k, palm.second)))
        }
        r.touch.raw(MotionEvent.ACTION_POINTER_UP, listOf(RawPointer(0, last.first, last.second, pen, 0.8f), RawPointer(1, palm.first, palm.second)), index = 1)
        r.touch.idle(16)
        r.touch.raw(MotionEvent.ACTION_UP, listOf(RawPointer(0, last.first, last.second, pen, 0.8f)))
        r.touch.idle(60)
        r.checkpoint("pen stroke with a palm")
        val s = vec.vector!!.objects.last() as VStroke
        assertTrue(s.stylus)
        assertTrue("the whole pen stroke: ${s.points.bounds()}", s.points.bounds().right > 500f - 50f)
        assertEquals(3, vec.vector!!.objects.size)
    }

    @Test
    fun aSecondFingerTurnsTheStrokeIntoAPinchWithNoTrace() {
        setup()
        val zoom = c.viewTransform.zoom
        val a = r.screen(100f, 150f)
        val b = r.screen(300f, 150f)
        r.touch.idle(250)
        r.touch.raw(MotionEvent.ACTION_DOWN, listOf(RawPointer(0, a.first, a.second)))
        for (k in 1..4) { r.touch.idle(16); r.touch.raw(MotionEvent.ACTION_MOVE, listOf(RawPointer(0, a.first + 6f * k, a.second))) }
        val a1 = a.first + 24f to a.second
        r.touch.raw(MotionEvent.ACTION_POINTER_DOWN, listOf(RawPointer(0, a1.first, a1.second), RawPointer(1, b.first, b.second)), index = 1)
        for (k in 1..10) {
            r.touch.idle(30)
            r.touch.raw(MotionEvent.ACTION_MOVE, listOf(RawPointer(0, a1.first - 8f * k, a1.second), RawPointer(1, b.first + 8f * k, b.second)))
        }
        r.touch.raw(MotionEvent.ACTION_POINTER_UP, listOf(RawPointer(0, a1.first - 80f, a1.second), RawPointer(1, b.first + 80f, b.second)), index = 0)
        r.touch.idle(16)
        r.touch.raw(MotionEvent.ACTION_UP, listOf(RawPointer(1, b.first + 80f, b.second)))
        r.touch.idle(60)
        assertTrue("the view zoomed", c.viewTransform.zoom > zoom * 1.1f)
        r.checkpoint("stroke turned into a pinch", steps = 0)
        assertEquals(2, vec.vector!!.objects.size)
        r.view.fitToScreen()
        Smoke.pump(40)
    }

    @Test
    fun lassoTapDeselectsTheObjects() {
        setup()
        r.tool(ToolId.LASSO)
        r.stroke(20f to 50f, 460f to 50f, 460f to 120f, 20f to 120f, 20f to 52f)
        assertTrue(Smoke.pumpUntil(10_000) { c.vectors.selectedIds.isNotEmpty() })
        r.checkpoint("lasso", steps = 0)
        assertEquals(1, c.vectors.selectedIds.size)
        r.tap(240f, 280f)
        Smoke.pump(40)
        assertTrue("a plain tap deselects the objects", c.vectors.selectedIds.isEmpty())
        r.checkpoint("deselect tap", steps = 0)
    }

    @Test
    fun transformTapsPickObjectsAndDeleteIsOneStep() {
        setup()
        r.tool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        assertEquals("everything lifted", 2, VectorLift.activeLift(c)!!.ids.size)
        val s2 = vec.vector!!.objects[1]
        // A tap on s2 (inside the box of everything) picks it alone.
        r.tap(240f, 209f)
        assertTrue(Smoke.pumpUntil(10_000) { VectorLift.activeLift(c)?.ids == setOf(s2.id) })
        assertEquals(setOf(s2.id), c.vectors.selectedIds)
        // Delete (options strip): one step, s2 gone.
        assertTrue(t.deleteContent())
        r.checkpoint("delete lifted object")
        assertNull(vec.vector!!.byId(s2.id))
        r.undoAndCheck("undo delete")
        assertNotNull(vec.vector!!.byId(s2.id))
        assertFalse(t.hasUserChanges)
    }

    @Test
    fun twoFingerUndoWhileObjectsAreLiftedUndoesTheLastStroke() {
        setup()
        r.tool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        // Both fingers on the lifted objects: a quick tap is still undo.
        r.touch.idle(250)
        r.touch.twoFingerTap(r.screen(150f, 85f), r.screen(330f, 205f))
        Smoke.pump(60)
        assertEquals("s2 undone", 1, vec.vector!!.objects.size)
        assertEquals(3, c.undoManager.undoCount + 1)
        r.resync()
        r.assertCacheFresh("after the undo", vec)
        // The tool lifts what is there now on the next touch.
        r.tool(ToolId.BRUSH)
        r.tool(ToolId.TRANSFORM)
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        assertEquals(1, VectorLift.activeLift(c)!!.ids.size)
        t.discard()
        r.checkpoint("discard", steps = 0)
    }
}
