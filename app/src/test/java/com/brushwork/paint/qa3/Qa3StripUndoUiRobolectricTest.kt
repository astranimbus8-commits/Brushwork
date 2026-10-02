package com.brushwork.paint.qa3

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * Final QA (v1.5 §4.5, §4.6): ONE undo step per drag of the X / Y strip and of the point
 * thickness slider, whatever the finger's pace. Real finger drags at 392 dp:
 * - a finger that rests on the slider for two seconds in the middle of a drag (to look at the
 *   canvas) still makes one step (the tools coalesced edits only within 1.5 s of each other);
 * - two drags right after each other are two steps (the Shape tool's strip never ended its edit,
 *   so a second drag within 1.5 s was folded into the first).
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.stripundosandbox"])
class Qa3StripUndoUiRobolectricTest {

    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            runCatching { settle() }
        }
    }

    private fun editor(c: (ComponentActivity) -> EditorController): Pair<ComponentActivity, EditorController> {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val controller = c(activity)
        // No detents (0, centre, edge) pulling the dragged value: the drags below are free.
        controller.snapping.enabled = false
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools
        settle()
        return activity to controller
    }

    private fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(name) } == true &&
            (e.node.config.contains(SemanticsActions.SetProgress) || name.contains("thickness"))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /**
     * A real finger drag along the slider [name] from [at] of its width by [dx] window px in
     * [moves] moves (8 ms apart). With [restMs] the finger rests (no events) that long after
     * half of the moves, as a user looking at the canvas does.
     */
    private fun drag(name: String, dx: Float, moves: Int = 8, restMs: Long = 0, at: Float = 0.4f) {
        val e = slider(name)
        val x0 = e.bounds.left + e.bounds.width * at
        val y0 = e.bounds.center.y
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y0, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            e.window.dispatchTouchEvent(ev)
            ev.recycle()
            idle(8)
        }
        send(MotionEvent.ACTION_DOWN, x0)
        for (s in 1..moves) {
            send(MotionEvent.ACTION_MOVE, x0 + dx * s / moves)
            if (s == moves / 2 && restMs > 0) idle(restMs)
        }
        send(MotionEvent.ACTION_UP, x0 + dx)
        settle(4)
    }

    @Test
    fun oneStepPerDrag() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        section("shape with its own points: two quick strip drags are two steps") { shapeTwoDrags() }
        section("shape with its own points: a drag with a rest is one step") { shapeRestingDrag() }
        section("curve point: a strip drag with a rest is one step") { curveRestingDrag() }
        section("curve point: a thickness drag with a rest is one step") { thicknessRestingDrag() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    /** A pending rectangle turned to its own points (the Shape tool keeps in-tool steps then). */
    private fun pendingShapeWithPoints(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        c.pointerDown(ToolPoint(150f, 100f))
        c.pointerMove(ToolPoint(200f, 150f))
        c.pointerMove(ToolPoint(250f, 200f))
        c.pointerUp(ToolPoint(250f, 200f))
        settle()
        shape.setPointEditing(true)
        settle()
        assertTrue("strip shown", RobolectricUi.elements().any { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("X slider") == true })
        return shape
    }

    private fun shapeTwoDrags() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val shape = pendingShapeWithPoints(c)
        val b0 = shape.box!!
        drag("X slider", 60f, at = 0.3f)
        val b1 = shape.box!!
        assertNotEquals("the first drag moved the shape", b0.cx, b1.cx)
        idle(100)
        drag("X slider", 60f, at = 0.6f)
        val b2 = shape.box!!
        assertNotEquals("the second drag moved the shape", b1.cx, b2.cx)
        assertTrue(shape.undoStep())
        assertEquals("one undo takes back the second drag only", b1.cx, shape.box!!.cx, 0.01f)
        assertTrue(shape.undoStep())
        assertEquals("a second undo takes back the first drag", b0.cx, shape.box!!.cx, 0.01f)
        shape.discard()
    }

    private fun shapeRestingDrag() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val shape = pendingShapeWithPoints(c)
        val b0 = shape.box!!
        drag("X slider", 80f, restMs = 2_000, at = 0.3f)
        assertNotEquals(b0.cx, shape.box!!.cx)
        assertTrue(shape.undoStep())
        assertEquals("one undo takes back the whole drag", b0.cx, shape.box!!.cx, 0.01f)
        shape.discard()
    }

    private fun pendingCurve(c: EditorController): CurveTool {
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
        tool.select(1)
        repeat(10) { settle(2); idle(16) }
        return tool
    }

    private fun curveRestingDrag() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val tool = pendingCurve(c)
        val before = tool.anchors[1].pos
        drag("X slider", 80f, restMs = 2_000)
        assertNotEquals(before.x, tool.anchors[1].x)
        assertTrue(tool.undoStep())
        assertEquals("one undo takes back the whole drag", before, tool.anchors[1].pos)
        // Two quick drags: two steps.
        drag("X slider", 40f, at = 0.3f)
        val mid = tool.anchors[1].pos
        idle(100)
        drag("X slider", 40f, at = 0.6f)
        assertNotEquals(mid, tool.anchors[1].pos)
        assertTrue(tool.undoStep())
        assertEquals(mid, tool.anchors[1].pos)
        tool.discard()
    }

    private fun thicknessRestingDrag() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val tool = pendingCurve(c)
        assertEquals(1f, tool.anchors[1].width, 0f)
        drag("Point thickness slider", 120f, moves = 10, restMs = 2_000)
        val w = tool.anchors[1].width
        assertTrue("the drag thickened the point: $w", w > 1.05f)
        assertTrue(tool.undoStep())
        assertEquals("one undo takes back the whole drag", 1f, tool.anchors[1].width, 0f)
        tool.discard()
    }
}
