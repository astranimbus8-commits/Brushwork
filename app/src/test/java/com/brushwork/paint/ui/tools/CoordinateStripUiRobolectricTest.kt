package com.brushwork.paint.ui.tools

import android.graphics.Canvas
import android.graphics.Paint
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
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * v1.5 §4.6 / v1.6 §3.7.8 (G): the X / Y pill in the real editor at the user's phone size. Hidden
 * without anything to place; shown while transforming (its cell moves the transformed content,
 * which stays one pending transform: ✓ is one step), for a pending shape and for the selected
 * curve point; a typed value in mm lands at the document DPI; a drag of the number follows the
 * finger in document px, is fine (a tenth of the movement) far above or below the cell, and is
 * one in-tool undo step; showing the pill doesn't refit the canvas; the fold is remembered.
 *
 * Own sandbox (the test recomposer policy and paused Choreographer are global); all UI work in
 * ONE test split into sections (Compose's frame clock only runs in the first test of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.stripsandbox"])
class CoordinateStripUiRobolectricTest {

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

    /** The editor on [c] in a fresh activity. */
    private fun editor(c: (ComponentActivity) -> EditorController): Pair<ComponentActivity, EditorController> {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val controller = c(activity)
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools
        settle()
        return activity to controller
    }

    private fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }

    private fun hasSlider(name: String): Boolean = RobolectricUi.elements().any { e ->
        e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }

    /** Sets the slider [name] like an accessibility service (or a release after a drag). */
    private fun setSlider(name: String, v: Float) {
        requireNotNull(slider(name).node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(v)
        settle(4)
    }

    private fun matrixOf(c: EditorController): FloatArray = FloatArray(9).also { c.viewTransform.matrix.getValues(it) }

    @Test
    fun theCoordinateStrip() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("transform: shown, moves, one step, typed mm") { transform() }
        section("shape: one step") { shape() }
        section("curve point: no refit, fine drag, one step per drag") { curve() }
        section("fold is remembered") { fold() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun transform() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(100f, 80f, 220f, 180f, Paint().apply { color = 0xFF2266CC.toInt() }) }
        settle()
        assertFalse("hidden with nothing to place", hasSlider("X slider"))
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("lifted", Smoke.pumpUntil { tool.transformState != null })
        settle()
        assertTrue("shown during a transform", hasSlider("X slider") && hasSlider("Y slider"))
        assertTrue(SmokeUi.has("Center", exact = true))
        val steps = c.undoManager.undoCount
        setSlider("X slider", 300f)
        assertEquals(300f, tool.anchorPosition!!.x, 0.01f)
        setSlider("Y slider", 50f)
        assertEquals(50f, tool.anchorPosition!!.y, 0.01f)
        assertEquals("still one pending transform", steps, c.undoManager.undoCount)
        assertTrue(tool.hasPendingWork)
        // A typed value in the tool's unit, at the document's DPI.
        tool.unit = LengthUnit.MM
        settle()
        SmokeUi.click("Type X")
        SmokeUi.typeAndDone("X", "10")
        assertEquals((10.0 / 25.4 * c.doc.dpi).toFloat(), tool.anchorPosition!!.x, 0.05f)
        assertFalse("the dialog closed", SmokeUi.has("X position", exact = true))
        tool.commit()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertFalse(hasSlider("X slider"))
    }

    private fun shape() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        c.pointerDown(ToolPoint(100f, 100f))
        c.pointerMove(ToolPoint(150f, 150f))
        c.pointerMove(ToolPoint(200f, 200f))
        c.pointerUp(ToolPoint(200f, 200f))
        settle()
        assertNotNull(tool.box)
        assertTrue(hasSlider("X slider"))
        val steps = c.undoManager.undoCount
        setSlider("X slider", 250f)
        setSlider("Y slider", 120f)
        assertEquals(Vec2(250f, 120f), tool.box!!.center)
        assertEquals(steps, c.undoManager.undoCount)
        tool.commit()
        settle()
        assertEquals("a shape moved by the strip is one step", steps + 1, c.undoManager.undoCount)
    }

    private fun curve() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
        tool.deselect()
        settle()
        // v1.7 (item 12, design §3.12): with no point selected the pill shows the object's centre.
        assertTrue("no point selected: the pill shows the object's Center", hasSlider("X slider"))
        assertTrue(SmokeUi.has(CurveTool.CENTER_LABEL, exact = true))
        val before = matrixOf(c)
        tool.select(1)
        settle()
        assertTrue(hasSlider("X slider"))
        assertTrue(SmokeUi.has("Point 2", exact = true))
        assertArrayEquals("showing the strip doesn't refit the canvas", before, matrixOf(c), 0f)
        // The slider moves the point; one in-tool step.
        setSlider("X slider", 230f)
        assertEquals(230f, tool.anchors[1].x, 0.01f)
        assertTrue(tool.undoStep())
        assertEquals(200f, tool.anchors[1].x, 0.01f)

        // A real drag of the number: the value follows the finger's travel in document px (dx /
        // zoom), a tenth of it once the finger is far above or below the cell; one step.
        val e = slider("X slider")
        val b = e.bounds
        val density = activity.resources.displayMetrics.density
        val zoom = c.viewTransform.zoom
        val x0 = b.center.x
        val y0 = b.center.y
        val t0 = SystemClock.uptimeMillis()
        var t = t0
        fun send(action: Int, x: Float, y: Float) {
            val ev = MotionEvent.obtain(t0, t, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            e.window.dispatchTouchEvent(ev)
            ev.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8))
            t += 8
        }
        val historyBefore = tool.anchors[1].x
        send(MotionEvent.ACTION_DOWN, x0, y0)
        assertEquals("a touch alone changes nothing", 200f, tool.anchors[1].x, 0f)
        for (s in 1..8) send(MotionEvent.ACTION_MOVE, x0 + 60f * s / 8f, y0)
        val v1 = tool.anchors[1].x
        assertEquals("the finger's travel in document px", 60f / zoom, v1 - 200f, 0.05f)
        val far = 80f * density
        for (s in 1..8) send(MotionEvent.ACTION_MOVE, x0 + 60f, y0 + far * s / 8f)
        assertEquals("no jump when fine starts", v1, tool.anchors[1].x, 0.01f)
        assertTrue("says Fine", SmokeUi.has("Fine", exact = true))
        for (s in 1..8) send(MotionEvent.ACTION_MOVE, x0 + 60f + 60f * s / 8f, y0 + far)
        val v2 = tool.anchors[1].x
        assertEquals("a tenth of the movement", 6f / zoom, v2 - v1, 0.05f)
        send(MotionEvent.ACTION_UP, x0 + 120f, y0 + far)
        settle(4)
        assertFalse(SmokeUi.has("Fine", exact = true))
        assertTrue(tool.undoStep())
        assertEquals("the drag was one step", historyBefore, tool.anchors[1].x, 0.01f)
        tool.discard()
        settle()
        assertFalse(hasSlider("X slider"))
    }

    private fun fold() {
        val doc = Smoke.document(400, 300, layers = 2)
        val (_, c) = editor { Smoke.controller(it, doc) }
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.addAnchor(Vec2(60f, 70f))
        settle()
        assertTrue(hasSlider("X slider"))
        SmokeUi.click("Fold the X / Y strip")
        assertTrue(c.settings.coordinateStripFolded)
        assertFalse(hasSlider("X slider"))
        // Folded: a lone ✥.
        assertTrue(SmokeUi.has("Unfold the X / Y strip"))
        assertFalse(SmokeUi.has("Increments", exact = true))
        // Remembered by a new editor screen.
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        activities.clear()
        settle()
        editor { c }
        settle()
        assertTrue(SmokeUi.has("Unfold the X / Y strip"))
        assertFalse(hasSlider("X slider"))
        SmokeUi.click("Unfold the X / Y strip")
        assertFalse(c.settings.coordinateStripFolded)
        assertTrue(hasSlider("X slider"))
        tool.discard()
        settle()
    }

}
