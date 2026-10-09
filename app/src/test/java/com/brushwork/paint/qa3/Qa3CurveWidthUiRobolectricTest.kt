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
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
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
 * Final QA (v1.5 §4.4, §4.5) of the curve's line width in the real editor at 392 dp, on a vector
 * layer: the side "Brush size" slider dragged with a finger resizes the pending plain line live
 * (the width chip shows it), "Use brush size" off keeps the line's own width while the brush
 * changes, a width typed while linked resizes the brush; ✓ stores the width; switching the stroke
 * to the current brush keeps every point's thickness and ✓ makes a brush path with them.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.curvewidthsandbox"])
class Qa3CurveWidthUiRobolectricTest {

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

    /** A real finger drag along the slider described [name] by [dx] window px. */
    private fun drag(name: String, dx: Float) {
        val e = RobolectricUi.elements().last { el ->
            el.node.layoutInfo.isPlaced && el.node.config.contains(SemanticsActions.SetProgress) &&
                el.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
        }
        val x0 = e.bounds.center.x
        val y0 = e.bounds.center.y
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y0, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            e.window.dispatchTouchEvent(ev)
            ev.recycle()
            idle(16)
        }
        send(MotionEvent.ACTION_DOWN, x0)
        for (s in 1..8) send(MotionEvent.ACTION_MOVE, x0 + dx * s / 8f)
        send(MotionEvent.ACTION_UP, x0 + dx)
        settle(4)
    }

    private fun pendingCurve(c: EditorController): CurveTool {
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        settle()
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
        tool.select(1)
        tool.setWidth(1, 2f)
        tool.endNumericEdit()
        settle()
        return tool
    }

    @Test
    fun curveWidthAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        section("the side slider resizes the pending plain line; ✓ stores it") { sideSlider() }
        section("Use brush size off; typed width while linked") { settingsSheet() }
        section("plain -> current brush keeps the thickness") { brushStroke() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun sideSlider() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val layer = c.addVectorLayer()!!
        c.brush = c.brush.copy(size = 8f)
        val tool = pendingCurve(c)
        assertTrue(tool.widthLinked)
        assertEquals(8f, tool.lineWidth, 0f)
        assertTrue("the width chip shows the brush size", SmokeUi.has("8 px", exact = true))
        drag("Brush size", 120f)
        val w = c.brush.size
        assertTrue("the slider made the brush bigger: $w", w > 8f)
        assertEquals("the pending line follows it", w, tool.lineWidth, 0f)
        assertEquals("the ring follows it too", 2f * w, tool.diameterAt(1), 0.01f)
        val steps = c.undoManager.undoCount
        tool.commit()
        c.settleVectorWork()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        val path = layer.vector!!.objects.single() as VPath
        assertEquals(w, path.stroke!!.width, 0f)
        assertEquals(listOf(1f, 2f, 1f), path.subpaths[0].anchors.map { it.width })
    }

    private fun settingsSheet() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.addVectorLayer()!!
        c.brush = c.brush.copy(size = 8f)
        val tool = pendingCurve(c)
        SmokeUi.click("Curve settings", exact = true)
        SmokeUi.assertPanelShown("Curve")
        assertTrue(SmokeUi.has("Use brush size", exact = true))
        // A width typed while linked resizes the brush.
        SmokeUi.typeAndDone("Line width", "12")
        assertEquals(12f, c.brush.size, 0.01f)
        assertEquals(12f, tool.lineWidth, 0.01f)
        // Unlinked: the line keeps its own width while the brush changes.
        SmokeUi.click("Use brush size", exact = true)
        assertFalse(tool.widthLinked)
        SmokeUi.typeAndDone("Line width", "5")
        assertEquals(5f, tool.lineWidth, 0.01f)
        assertEquals("the brush stays", 12f, c.brush.size, 0.01f)
        c.brush = c.brush.copy(size = 30f)
        settle()
        assertEquals(5f, tool.lineWidth, 0.01f)
        SmokeUi.click("Close", exact = true)
        settle()
        assertFalse("the chip shows the line's own width (no brush icon)", tool.widthLinked)
        assertTrue(SmokeUi.has("5 px", exact = true))
        tool.discard()
    }

    private fun brushStroke() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val layer = c.addVectorLayer()!!
        c.brush = c.brush.copy(size = 8f)
        val tool = pendingCurve(c)
        val before = tool.anchors.map { it.width }
        SmokeUi.click(CurveLabels17.STROKE_KIND, exact = true)
        SmokeUi.click("Current brush", exact = true)
        assertEquals(CurveStroke.BRUSH, tool.settings.stroke)
        assertEquals("the points keep their thickness", before, tool.anchors.map { it.width })
        assertTrue("the thickness control stays", SmokeUi.has("Point thickness slider"))
        assertEquals("the ring is the brush size times the thickness", 16f, tool.diameterAt(1), 0.01f)
        val steps = c.undoManager.undoCount
        tool.commit()
        c.settleVectorWork()
        Smoke.pumpUntil { c.settleVectorWork(); !tool.hasPendingWork }
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertTrue(layer.isVectorLayer)
        val path = layer.vector!!.objects.single() as VPath
        assertEquals(VStrokeKind.BRUSH, path.stroke!!.kind)
        assertEquals(listOf(1f, 2f, 1f), path.subpaths[0].anchors.map { it.width })
        assertNotEquals("pixels were drawn", 0, layer.bitmap.getPixel(200, 82) ushr 24 or (layer.bitmap.getPixel(200, 80) ushr 24))
    }
}
