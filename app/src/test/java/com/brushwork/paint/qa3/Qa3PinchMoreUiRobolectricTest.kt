package com.brushwork.paint.qa3

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Final QA (v1.5 §4.7, §7 checklist 6) of the two-finger rule through the real canvas view at
 * 392 dp, for the cases the first pass didn't drive: Transform with nothing lifted (after ✓),
 * Transform with a selection smaller than the picture (the selection's box is the object), a
 * pending Shape line (a band around the segment) and lifted vector objects far apart (the box
 * around them, gap included, is the object). Both fingers just beside: the view zooms and
 * nothing changes; one finger on it: the object scales.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.pinchmoresandbox"])
class Qa3PinchMoreUiRobolectricTest {

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

    private fun canvasOf(activity: ComponentActivity): CanvasView =
        Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas view")

    private fun screen(activity: ComponentActivity, c: EditorController, x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        canvasOf(activity).getLocationInWindow(loc)
        val p = c.viewTransform.docToScreen(x, y)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    private fun dp(activity: ComponentActivity, v: Float) = v * activity.resources.displayMetrics.density

    private fun matrixOf(c: EditorController): FloatArray = FloatArray(9).also { c.viewTransform.matrix.getValues(it) }

    /** Two fingers down at [a] (first) and [b], each moving [by] px away from the other. */
    private fun spread(touch: Smoke.Touch, a: Pair<Float, Float>, b: Pair<Float, Float>, by: Float) {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / len * by
        val uy = dy / len * by
        touch.idle(300)
        touch.pinch(a, b, a.first - ux to a.second - uy, b.first + ux to b.second + uy)
        settle(4)
    }

    /** A point on the free canvas (clear of the chrome) far from document point ([x], [y]). */
    private fun farFrom(activity: ComponentActivity, c: EditorController, x: Float, y: Float): Pair<Float, Float> {
        val v = canvasOf(activity)
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val l = loc[0] + dp(activity, 40f)
        val r = loc[0] + v.width - dp(activity, 40f)
        val t = loc[1] + dp(activity, 230f)
        val b = loc[1] + v.height - dp(activity, 190f)
        val p = screen(activity, c, x, y)
        return listOf(l to t, r to t, l to b, r to b).maxBy { (cx, cy) -> (cx - p.first) * (cx - p.first) + (cy - p.second) * (cy - p.second) }
    }

    private fun shifted(p: Pair<Float, Float>, dx: Float, dy: Float) = (p.first + dx) to (p.second + dy)

    @Test
    fun twoFingerRuleMoreCases() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        section("transform after ✓ (nothing lifted)") { transformNotLifted() }
        section("transform with a selection smaller than the picture") { transformSelection() }
        section("shape line band") { shapeLine() }
        section("vector objects far apart") { vectorObjects() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun transformNotLifted() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(160f, 110f, 240f, 190f, Paint().apply { color = 0xFF2266CC.toInt() }) }
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil { tool.transformState != null })
        tool.moveBy(10f, 0f)
        tool.commit()
        settle()
        assertEquals("nothing lifted after ✓", null, tool.transformState)
        val steps = c.undoManager.undoCount
        val pixels0 = IntArray(400 * 300).also { c.activeLayer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        val touch = Smoke.Touch(activity.window.decorView)
        val g = dp(activity, 20f)
        // Both fingers 20 dp beside the picture (170..250): the view zooms.
        val z0 = c.viewTransform.zoom
        spread(touch, shifted(screen(activity, c, 170f, 150f), -g, 0f), shifted(screen(activity, c, 250f, 150f), g, 0f), g * 2)
        assertTrue("the view zoomed", c.viewTransform.zoom > z0 * 1.05f)
        val st = tool.transformState
        if (st != null) assertEquals("the picture didn't scale", 80f, st.bounds().width, 0.5f)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertArrayEquals("the picture is unchanged", pixels0, IntArray(400 * 300).also { c.activeLayer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) })
        // One finger on it (landing second): it is lifted and scales.
        val z1 = c.viewTransform.zoom
        spread(touch, farFrom(activity, c, 210f, 150f), screen(activity, c, 210f, 150f), g * 2)
        assertEquals("the view stays", z1, c.viewTransform.zoom, 1e-4f)
        val lifted = tool.transformState
        assertNotNull("lifted by the pinch", lifted)
        assertTrue("scaled: ${lifted!!.bounds()}", lifted.bounds().width > 80f * 1.05f)
        tool.commit()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
    }

    private fun transformSelection() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        // A wide picture; the selection is its right part only.
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(60f, 110f, 340f, 190f, Paint().apply { color = 0xFF2266CC.toInt() }) }
        c.setSelection(Selection.fromPath(Path().apply { addRect(260f, 100f, 340f, 200f, Path.Direction.CW) }, 400, 300, antiAlias = false))
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil { tool.transformState != null })
        settle()
        assertEquals("the selection is lifted", 80f, tool.transformState!!.bounds().width, 0.5f)
        val touch = Smoke.Touch(activity.window.decorView)
        val g = dp(activity, 20f)
        // On the picture but well left of the selection, both fingers: the view.
        val z0 = c.viewTransform.zoom
        spread(touch, screen(activity, c, 80f, 150f), screen(activity, c, 180f, 150f), g * 2)
        assertTrue("the view zoomed", c.viewTransform.zoom > z0 * 1.05f)
        assertEquals("the selection didn't scale", 80f, tool.transformState!!.bounds().width, 0.5f)
        // One finger on the selection: it scales.
        val z1 = c.viewTransform.zoom
        spread(touch, farFrom(activity, c, 300f, 150f), screen(activity, c, 300f, 150f), g * 2)
        assertEquals("the view stays", z1, c.viewTransform.zoom, 1e-4f)
        assertTrue("the selection scaled", tool.transformState!!.bounds().width > 80f * 1.05f)
        tool.discard()
    }

    private fun shapeLine() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.LINE, useBrushSize = false, strokeWidth = 2f) }
        c.pointerDown(ToolPoint(120f, 150f))
        for (i in 1..10) c.pointerMove(ToolPoint(120f + 16f * i, 150f))
        c.pointerUp(ToolPoint(280f, 150f))
        settle()
        val box0 = shape.box!!
        assertEquals(160f, box0.w, 0.5f)
        val touch = Smoke.Touch(activity.window.decorView)
        val g = dp(activity, 20f)
        val mid = screen(activity, c, 200f, 150f)
        // 20 dp above and below the line's middle: the view zooms.
        val z0 = c.viewTransform.zoom
        spread(touch, shifted(mid, 0f, -g), shifted(mid, 0f, g), g * 2)
        assertTrue("the view zoomed", c.viewTransform.zoom > z0 * 1.05f)
        assertEquals("the line stays", box0, shape.box)
        // One finger 8 dp from the line (inside the 12 dp band): the line scales.
        val z1 = c.viewTransform.zoom
        val near = shifted(screen(activity, c, 200f, 150f), 0f, -dp(activity, 8f))
        spread(touch, farFrom(activity, c, 200f, 150f), near, g * 2)
        assertEquals("the view stays", z1, c.viewTransform.zoom, 1e-4f)
        assertTrue("the line scaled: ${shape.box}", shape.box!!.w > box0.w * 1.05f)
        shape.discard()
    }

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(x0, y0, sharp = true), VAnchor(x1, y0, sharp = true), VAnchor(x1, y1, sharp = true), VAnchor(x0, y1, sharp = true)), closed = true)),
        polyline = true,
        fill = VPaint.Solid(0xFF2266CC.toInt()),
    )

    private fun vectorObjects() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val vector = c.addVectorLayer()!!
        c.vectors.addObjects(vector, listOf(square(60f, 60f, 120f, 120f), square(280f, 180f, 340f, 240f)), "Add squares")
        c.settleVectorWork()
        settle()
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("objects lifted", Smoke.pumpUntil { tool.transformState != null })
        settle()
        val w0 = tool.transformState!!.bounds().width
        assertEquals("the box around both (a little anti-aliasing margin)", 280f, w0, 6f)
        val touch = Smoke.Touch(activity.window.decorView)
        val g = dp(activity, 20f)
        // Both fingers 20 dp beside the box (left of 60, right of 340): the view zooms.
        val z0 = c.viewTransform.zoom
        spread(touch, shifted(screen(activity, c, 60f, 150f), -g, 0f), shifted(screen(activity, c, 340f, 150f), g, 0f), g * 2)
        assertTrue("the view zoomed", c.viewTransform.zoom > z0 * 1.05f)
        assertEquals("the objects stay", w0, tool.transformState!!.bounds().width, 0.01f)
        // One finger in the empty gap between the squares (inside the box): the objects scale.
        val z1 = c.viewTransform.zoom
        spread(touch, farFrom(activity, c, 200f, 150f), screen(activity, c, 200f, 150f), g * 2)
        assertEquals("the view stays", z1, c.viewTransform.zoom, 1e-4f)
        assertTrue("the objects scaled", tool.transformState!!.bounds().width > w0 * 1.05f)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertTrue(Smoke.pumpUntil { c.settleVectorWork(); !tool.hasPendingWork })
        c.settleVectorWork()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertTrue("still a vector layer", vector.isVectorLayer)
        assertEquals(2, vector.vector!!.objects.size)
    }
}
