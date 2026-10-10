package com.brushwork.paint.qa3

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
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Final QA (v1.5 §7 checklist 5 and 6) in the real editor at the user's phone size (392 dp):
 * the X / Y strip for lifted vector objects, a pending text, a mask component's pin and the
 * clone source (typed values in mm / in at the document DPI, one undo step per real drag, the
 * canvas never jumps when the strip appears), and two fingers through the real canvas view
 * (the first finger's touch reaches the tool before the second lands): both fingers beside an
 * object zoom the view, one finger on it (landing first or second) scales it, tiny objects can
 * still be grabbed.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.stripsandbox"])
class Qa3StripAndPinchUiRobolectricTest {

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
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools
        settle()
        return activity to controller
    }

    private fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }

    private fun hasSlider(name: String): Boolean = RobolectricUi.elements().any { e ->
        e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }

    private fun setSlider(name: String, v: Float) {
        requireNotNull(slider(name).node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(v)
        settle(4)
    }

    private fun matrixOf(c: EditorController): FloatArray = FloatArray(9).also { c.viewTransform.matrix.getValues(it) }

    /** A real finger drag along the slider [name] from its centre by [dx] px (window px). */
    private fun dragSlider(name: String, dx: Float) {
        val e = slider(name)
        val x0 = e.bounds.center.x
        val y0 = e.bounds.center.y
        val t0 = SystemClock.uptimeMillis()
        var t = t0
        fun send(action: Int, x: Float, y: Float) {
            val ev = MotionEvent.obtain(t0, t, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            e.window.dispatchTouchEvent(ev)
            ev.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8))
            t += 8
        }
        send(MotionEvent.ACTION_DOWN, x0, y0)
        for (s in 1..6) send(MotionEvent.ACTION_MOVE, x0 + dx * s / 6f, y0)
        send(MotionEvent.ACTION_UP, x0 + dx, y0)
        settle(4)
    }

    private fun canvasOf(activity: ComponentActivity): CanvasView =
        Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas view")

    /** Window pixel of document point ([x], [y]). */
    private fun screen(activity: ComponentActivity, c: EditorController, x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        canvasOf(activity).getLocationInWindow(loc)
        val p = c.viewTransform.docToScreen(x, y)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    private fun dp(activity: ComponentActivity, v: Float) = v * activity.resources.displayMetrics.density

    /** Two fingers down at [a] (first) and [b], each moving [by] px away from the other (a pinch out). */
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

    /**
     * A window point on the canvas (clear of the top and bottom chrome as measured now: the pill's
     * Scale row moves the selection bar down) far from document point ([x], [y]): the corner of
     * that free area farthest from it.
     */
    private fun farFrom(activity: ComponentActivity, c: EditorController, x: Float, y: Float): Pair<Float, Float> =
        Qa3FreeCanvas.farFrom(activity, c, x, y)

    @Test
    fun stripAndTwoFingers() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("strip: lifted vector objects, typed inches, one step") { vectorObjects() }
        section("strip: pending text, typed mm, no refit") { text() }
        section("strip: mask pin, one step per real drag") { maskPin() }
        section("strip: clone source") { cloneSource() }
        section("two fingers: transform") { pinchTransform() }
        section("two fingers: text, shape box and line") { pinchTextAndShape() }
        section("two fingers: mask component, tiny object") { pinchMaskAndTiny() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(x0, y0, sharp = true), VAnchor(x1, y0, sharp = true), VAnchor(x1, y1, sharp = true), VAnchor(x0, y1, sharp = true)), closed = true)),
        polyline = true,
        fill = VPaint.Solid(0xFF2266CC.toInt()),
    )

    private fun vectorObjects() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val vector = c.addVectorLayer()!!
        c.vectors.addObjects(vector, listOf(square(100f, 80f, 200f, 160f)), "Add square")
        c.settleVectorWork()
        settle()
        val before = matrixOf(c)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("objects lifted", Smoke.pumpUntil { tool.transformState != null })
        settle()
        assertTrue("strip shown for lifted objects", hasSlider("X slider") && hasSlider("Y slider"))
        assertArrayEquals("the canvas doesn't move when the strip appears", before, matrixOf(c), 0f)
        assertEquals(150f, tool.anchorPosition!!.x, 0.01f)
        val steps = c.undoManager.undoCount
        setSlider("X slider", 250f)
        assertEquals(250f, tool.anchorPosition!!.x, 0.01f)
        // Typed in inches at the document DPI.
        tool.unit = LengthUnit.IN
        settle()
        assertTrue("the strip shows inches", SmokeUi.has(" in", exact = false))
        SmokeUi.click("Type Y")
        SmokeUi.typeAndDone("Y", "0.5")
        val y = (0.5 * c.doc.dpi).toFloat()
        assertEquals(y, tool.anchorPosition!!.y, 0.05f)
        assertEquals("still pending", steps, c.undoManager.undoCount)
        tool.commit()
        assertTrue(Smoke.pumpUntil { c.settleVectorWork(); !tool.hasPendingWork })
        c.settleVectorWork()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        val moved = vector.vector!!.objects.single() as VPath
        assertEquals("objects moved by the strip", 200f, moved.subpaths[0].anchors[0].x, 0.05f)
        assertEquals(y - 40f, moved.subpaths[0].anchors[0].y, 0.1f)
        assertTrue(vector.isVectorLayer)
    }

    private fun text() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val before = matrixOf(c)
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(200f, 150f)
        tool.setText("Hello")
        tool.confirmEditor()
        settle()
        assertTrue("strip shown for a pending text", hasSlider("X slider"))
        assertArrayEquals("the canvas doesn't move when the strip appears", before, matrixOf(c), 0f)
        tool.positionUnit = LengthUnit.MM
        settle()
        val steps = c.undoManager.undoCount
        SmokeUi.click("Type X")
        SmokeUi.typeAndDone("X", "5")
        val x = (5.0 / 25.4 * c.doc.dpi).toFloat()
        assertEquals(x, tool.anchorOf(tool.item!!).x, 0.05f)
        setSlider("Y slider", 100f)
        assertEquals(100f, tool.anchorOf(tool.item!!).y, 0.05f)
        assertEquals("still pending", steps, c.undoManager.undoCount)
        tool.commit()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertFalse(hasSlider("X slider"))
    }

    private fun maskPin() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2, whiteBottom = true)) }
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawCircle(200f, 150f, 80f, Paint().apply { color = 0xFF2266CC.toInt() }) }
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        tool.arm(MaskTool.Kind.RADIAL)
        val before = matrixOf(c)
        c.pointerDown(ToolPoint(200f, 150f))
        for (i in 1..10) c.pointerMove(ToolPoint(200f + 6f * i, 150f + 5f * i))
        c.pointerUp(ToolPoint(260f, 200f))
        Smoke.pump(300)
        settle()
        assertNotNull("a radial component is selected", tool.selected)
        assertTrue("strip shown for the pin", hasSlider("X slider"))
        assertArrayEquals("the canvas doesn't move when the strip appears", before, matrixOf(c), 0f)
        val pin0 = tool.objectPosition!!.position!!
        val steps = c.undoManager.undoCount
        dragSlider("X slider", 80f)
        val pin1 = tool.objectPosition!!.position!!
        assertNotEquals(pin0.x, pin1.x)
        assertEquals("one step per drag", steps + 1, c.undoManager.undoCount)
        dragSlider("X slider", -90f)
        assertEquals("one step per drag", steps + 2, c.undoManager.undoCount)
        c.undo()
        Smoke.pump(100)
        settle()
        assertEquals(pin1.x, tool.objectPosition!!.position!!.x, 0.5f)
        c.undo()
        Smoke.pump(100)
        settle()
        assertEquals(pin0.x, tool.objectPosition!!.position!!.x, 0.5f)
    }

    private fun cloneSource() {
        val (_, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2, whiteBottom = true)) }
        c.selectTool(ToolId.CLONE)
        val tool = c.tools.getValue(ToolId.CLONE) as CloneTool
        settle()
        assertFalse("no source: no strip", hasSlider("X slider"))
        val before = matrixOf(c)
        tool.setSource(Vec2(120f, 90f))
        settle()
        assertTrue(hasSlider("X slider"))
        assertTrue(SmokeUi.has("Source", exact = true))
        assertArrayEquals("the canvas doesn't move when the strip appears", before, matrixOf(c), 0f)
        setSlider("X slider", 300f)
        assertEquals(Vec2(300f, 90f), tool.objectPosition!!.position)
        dragSlider("Y slider", 80f)
        assertTrue(tool.objectPosition!!.position!!.y > 90f)
    }

    // ------------------------------------------------------------------ two fingers

    private fun pinchTransform() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(160f, 110f, 240f, 190f, Paint().apply { color = 0xFF2266CC.toInt() }) }
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil { tool.transformState != null })
        settle()
        val touch = Smoke.Touch(activity.window.decorView)
        val g = dp(activity, 20f)
        fun pinchOut(a: Pair<Float, Float>, b: Pair<Float, Float>) = spread(touch, a, b, g * 2)
        // Both fingers just beside the box (20 dp out on either side): the view zooms.
        val st0 = tool.transformState
        val z0 = c.viewTransform.zoom
        val left = screen(activity, c, 160f, 150f).let { it.first - g to it.second }
        val right = screen(activity, c, 240f, 150f).let { it.first + g to it.second }
        pinchOut(left, right)
        assertEquals("the object stays", st0, tool.transformState)
        assertTrue("the view zoomed", c.viewTransform.zoom > z0 * 1.05f)
        // The outside finger lands first, the second one on the object: the object scales.
        val inside = screen(activity, c, 200f, 150f)
        val far = farFrom(activity, c, 200f, 150f)
        val z1 = c.viewTransform.zoom
        val w0 = tool.transformState!!.bounds().width
        pinchOut(far, inside)
        assertEquals("the view stays", z1, c.viewTransform.zoom, 1e-4f)
        assertTrue("the object scaled (outside finger first)", tool.transformState!!.bounds().width > w0 * 1.05f)
        // On the object first, then far away.
        val w1 = tool.transformState!!.bounds().width
        val inside2 = screen(activity, c, tool.transformState!!.center().x, tool.transformState!!.center().y)
        pinchOut(inside2, farFrom(activity, c, tool.transformState!!.center().x, tool.transformState!!.center().y))
        assertTrue("the object scaled (inside finger first)", tool.transformState!!.bounds().width > w1 * 1.05f)
        val steps = c.undoManager.undoCount
        tool.commit()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
    }

    private fun pinchTextAndShape() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        val touch = Smoke.Touch(activity.window.decorView)
        val g = dp(activity, 20f)
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(200f, 150f)
        text.setText("Hi")
        text.updateSpec { it.copy(sizePx = 30f) }
        text.confirmEditor()
        settle()
        val size0 = text.item!!.spec.sizePx
        val block = text.blockFor(text.item!!)
        val corners = text.item!!.corners(block.width, block.height)
        val l = corners.minOf { it.x }
        val r = corners.maxOf { it.x }
        val z0 = c.viewTransform.zoom
        val a = screen(activity, c, l, 150f).let { it.first - g - dp(activity, 12f) to it.second }
        val b = screen(activity, c, r, 150f).let { it.first + g + dp(activity, 12f) to it.second }
        touch.idle(300)
        touch.pinch(a, b, a.first - g to a.second, b.first + g to b.second)
        settle(4)
        assertEquals("text: both beside -> the text stays", size0, text.item!!.spec.sizePx, 1e-3f)
        assertTrue("text: the view zoomed", c.viewTransform.zoom > z0 * 1.02f)
        val far = farFrom(activity, c, 200f, 150f)
        val on = screen(activity, c, 200f, 150f)
        spread(touch, far, on, g * 2)
        assertTrue("text: one finger on it (landing second) scales it", text.item!!.spec.sizePx > size0 * 1.05f)
        text.discard()
        settle()

        // Shape: a pending box.
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        c.pointerDown(ToolPoint(160f, 110f))
        c.pointerMove(ToolPoint(200f, 150f))
        c.pointerMove(ToolPoint(240f, 190f))
        c.pointerUp(ToolPoint(240f, 190f))
        settle()
        val box0 = shape.box!!
        val zs = c.viewTransform.zoom
        val sa = screen(activity, c, box0.cx - box0.w / 2f, box0.cy).let { it.first - g to it.second }
        val sb = screen(activity, c, box0.cx + box0.w / 2f, box0.cy).let { it.first + g to it.second }
        touch.idle(300)
        touch.pinch(sa, sb, sa.first - g to sa.second, sb.first + g to sb.second)
        settle(4)
        assertEquals("shape: both beside -> the shape stays", box0, shape.box)
        assertTrue("shape: the view zoomed", c.viewTransform.zoom > zs * 1.02f)
        val sfar = farFrom(activity, c, box0.cx, box0.cy)
        val son = screen(activity, c, box0.cx, box0.cy)
        spread(touch, sfar, son, g * 2)
        assertTrue("shape: one finger on it (landing second) scales it", shape.box!!.w > box0.w * 1.05f)
        shape.discard()
        settle()
    }

    private fun pinchMaskAndTiny() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2, whiteBottom = true)) }
        val touch = Smoke.Touch(activity.window.decorView)
        val g = dp(activity, 20f)
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        tool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(200f, 150f))
        for (i in 1..10) c.pointerMove(ToolPoint(200f + 4f * i, 150f + 4f * i))
        c.pointerUp(ToolPoint(240f, 190f))
        Smoke.pump(300)
        settle()
        val comp0 = tool.selected!!
        val z0 = c.viewTransform.zoom
        val pin = tool.objectPosition!!.position!!
        // Fingers far from the ellipse on both sides: the view zooms.
        val a = screen(activity, c, 60f, pin.y)
        val b = screen(activity, c, 340f, pin.y)
        touch.idle(300)
        touch.pinch(a, b, a.first - g to a.second, b.first + g to b.second)
        settle(4)
        assertEquals("mask: the component stays", comp0, tool.selected)
        assertTrue("mask: the view zoomed", c.viewTransform.zoom > z0 * 1.02f)
        val z1 = c.viewTransform.zoom
        val far = farFrom(activity, c, pin.x, pin.y)
        val on = screen(activity, c, pin.x, pin.y)
        spread(touch, far, on, g * 2)
        Smoke.pump(300)
        settle(4)
        assertEquals("mask: the view stays", z1, c.viewTransform.zoom, 1e-4f)
        assertNotEquals("mask: one finger on it (landing second) changes the component", comp0, tool.selected)

        // A tiny picture (4 x 4 px, a few dp on screen): one finger right next to it grabs it.
        val (activity2, c2) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c2.editWholeLayer(c2.activeLayer, "Dot") { bmp -> Canvas(bmp).drawRect(198f, 148f, 202f, 152f, Paint().apply { color = 0xFF000000.toInt() }) }
        c2.selectTool(ToolId.TRANSFORM)
        val tt = c2.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil { tt.transformState != null })
        settle()
        val touch2 = Smoke.Touch(activity2.window.decorView)
        val w0 = tt.transformState!!.bounds().width
        val next = screen(activity2, c2, 200f, 150f).let { it.first + dp(activity2, 14f) to it.second }
        val far2 = farFrom(activity2, c2, 200f, 150f)
        spread(touch2, next, far2, g * 2)
        assertTrue("tiny object: a finger 14 dp from it scales it (44 dp min box)", tt.transformState!!.bounds().width > w0 * 1.05f)
    }
}
