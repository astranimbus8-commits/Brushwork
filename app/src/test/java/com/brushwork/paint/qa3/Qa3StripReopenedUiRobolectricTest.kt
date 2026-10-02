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
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import org.junit.Assert.assertArrayEquals
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
 * Final QA (v1.5 §4.6 × §4.9, §7 checklist 5) at 392 dp: the X / Y strip on objects of a vector
 * layer that are opened again: a curve path tapped in the Curve tool (the strip shows only once
 * a point is selected; a drag of it is one in-tool step; ✓ is one "Edit path" step) and a shape
 * tapped in the Shape tool (the strip moves its box; ✓ is one "Edit shape" step; ✕ puts it back).
 * The canvas never moves when the strip comes and goes, and the layer stays a vector layer.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.stripreopensandbox"])
class Qa3StripReopenedUiRobolectricTest {

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
        controller.snapping.enabled = false
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools
        settle()
        return activity to controller
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun matrixOf(c: EditorController): FloatArray = FloatArray(9).also { c.viewTransform.matrix.getValues(it) }

    private fun hasStrip(): Boolean = RobolectricUi.elements().any { e ->
        e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("X slider") == true
    }

    /** A real finger drag along the X slider from [at] of its width by [dx] window px. */
    private fun dragX(dx: Float, at: Float) {
        val e = RobolectricUi.elements().last { el ->
            el.node.layoutInfo.isPlaced && el.node.config.contains(SemanticsActions.SetProgress) &&
                el.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("X slider") == true
        }
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
        for (s in 1..8) send(MotionEvent.ACTION_MOVE, x0 + dx * s / 8f)
        send(MotionEvent.ACTION_UP, x0 + dx)
        settle(4)
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    @Test
    fun stripOnReopenedObjects() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        section("a curve path tapped again") { curve() }
        section("a shape tapped again") { shape() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun curve() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val layer = c.addVectorLayer()!!
        c.brush = c.brush.copy(size = 6f)
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
        tool.commit()
        c.settleVectorWork()
        settle()
        val path0 = layer.vector!!.objects.single() as VPath
        val view0 = matrixOf(c)
        val steps = c.undoManager.undoCount
        c.tap(60f, 200f)
        Smoke.pumpUntil { tool.isReopened }
        settle()
        assertTrue("reopened", tool.isReopened)
        assertFalse("no point selected: no strip", hasStrip())
        tool.select(1)
        settle()
        assertTrue("a point selected: the strip", hasStrip())
        assertTrue("it names the point", SmokeUi.has("Point 2", exact = true))
        assertArrayEquals("the canvas doesn't move", view0, matrixOf(c), 0f)
        dragX(90f, at = 0.3f)
        val moved = tool.anchors[1].pos
        assertNotEquals("the point moved", path0.subpaths[0].anchors[1].x, moved.x)
        assertEquals(80f, moved.y, 0.01f)
        assertTrue(tool.undoStep())
        assertEquals("one in-tool step", path0.subpaths[0].anchors[1].x, tool.anchors[1].x, 0.01f)
        assertTrue(tool.redoStep())
        assertEquals(moved, tool.anchors[1].pos)
        assertEquals("all of it is pending", steps, c.undoManager.undoCount)
        tool.commit()
        c.settleVectorWork()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertTrue(layer.isVectorLayer)
        val path1 = layer.vector!!.objects.single() as VPath
        assertEquals(moved.x, path1.subpaths[0].anchors[1].x, 0.01f)
        assertFalse("nothing selected after ✓: no strip", hasStrip())
        assertArrayEquals("the canvas never moved", view0, matrixOf(c), 0f)
        c.undo()
        c.settleVectorWork()
        assertEquals(path0.subpaths[0].anchors[1].x, (layer.vector!!.objects.single() as VPath).subpaths[0].anchors[1].x, 0.01f)
    }

    private fun shape() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val layer = c.addVectorLayer()!!
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        c.pointerDown(ToolPoint(150f, 100f))
        c.pointerMove(ToolPoint(200f, 150f))
        c.pointerMove(ToolPoint(250f, 200f))
        c.pointerUp(ToolPoint(250f, 200f))
        tool.commit()
        c.settleVectorWork()
        settle()
        val shape0 = layer.vector!!.objects.single() as VShape
        assertFalse("nothing pending: no strip", hasStrip())
        val view0 = matrixOf(c)
        val steps = c.undoManager.undoCount
        // Tap on its outline.
        c.tap(150f, 150f)
        Smoke.pumpUntil { tool.editingObject }
        settle()
        assertTrue("reopened", tool.editingObject)
        assertTrue("the strip shows its centre", hasStrip())
        assertArrayEquals("the canvas doesn't move", view0, matrixOf(c), 0f)
        val cx0 = tool.box!!.cx
        dragX(90f, at = 0.6f)
        val cx1 = tool.box!!.cx
        assertNotEquals("the box moved", cx0, cx1)
        assertEquals("all of it is pending", steps, c.undoManager.undoCount)
        // ✕ puts it back.
        tool.discard()
        c.settleVectorWork()
        settle()
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(shape0, layer.vector!!.objects.single())
        // Again, then ✓.
        c.tap(150f, 150f)
        Smoke.pumpUntil { tool.editingObject }
        settle()
        dragX(90f, at = 0.6f)
        val cx2 = tool.box!!.cx
        tool.commit()
        c.settleVectorWork()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertTrue(layer.isVectorLayer)
        val shape1 = layer.vector!!.objects.single() as VShape
        assertEquals(cx2, shape1.shape.box.cx, 0.01f)
        assertArrayEquals("the canvas never moved", view0, matrixOf(c), 0f)
        c.undo()
        c.settleVectorWork()
        assertEquals(shape0.shape.box, (layer.vector!!.objects.single() as VShape).shape.box)
    }
}
