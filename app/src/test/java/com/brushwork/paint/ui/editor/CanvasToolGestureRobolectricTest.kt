package com.brushwork.paint.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformState
import com.brushwork.paint.tools.transform.TransformTool
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.cos
import kotlin.math.sin

/**
 * Two-finger gestures handed to the tools and the long-press color pick, through a real
 * [CanvasView] in an activity window driven with MotionEvents: pinching a pasted picture or the
 * pending text scales it instead of the view, pinching elsewhere zooms, two-finger tap undo still
 * works, rotation follows the fingers on a mirrored view, and a held brush picks colors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi")
class CanvasToolGestureRobolectricTest {
    private lateinit var activity: ComponentActivity
    private lateinit var c: EditorController
    private lateinit var view: CanvasView
    private lateinit var touch: Smoke.Touch

    @Before
    fun setUp() {
        ShadowLog.clear()
        Smoke.scopeErrors.clear()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        c = Smoke.controller(activity, Smoke.document(400, 300, 2))
        view = CanvasView(activity, c)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        assertTrue(view.width > 0 && view.height > 0)
        touch = Smoke.Touch(view)
        c.tools
        c.brush = c.brush.copy(size = 8f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 0.1f, taperStart = 0f, taperEnd = 0f, scatter = 0f, grain = 0f)
        c.color = RED
    }

    @After
    fun tearDown() {
        Smoke.pump(50)
        Smoke.assertQuiet(c, "end of test")
        c.dispose()
    }

    // ------------------------------------------------------------------ helpers

    private fun screen(x: Float, y: Float): Pair<Float, Float> = c.viewTransform.docToScreen(x, y).let { it.x to it.y }

    private fun doc(s: Pair<Float, Float>): Vec2 = c.viewTransform.screenToDoc(Vec2(s.first, s.second))

    private fun transformTool() = c.tools.getValue(ToolId.TRANSFORM) as TransformTool

    private fun fill(layer: Layer, l: Float, t: Float, r: Float, b: Float, color: Int) {
        c.editWholeLayer(layer, "Seed") { bmp -> Canvas(bmp).drawRect(l, t, r, b, Paint().apply { this.color = color }) }
    }

    /** Pastes a copy of the 100 x 80 block at (100, 100) and returns the placement's start state. */
    private fun pasteBlock(): TransformState {
        fill(c.activeLayer, 100f, 100f, 200f, 180f, BLUE)
        assertTrue(c.copySelection())
        assertNotNull(c.paste())
        Smoke.pump(50)
        val tool = transformTool()
        assertTrue(tool.isPlacement)
        return tool.transformState!!
    }

    private fun pinch(a0: Pair<Float, Float>, b0: Pair<Float, Float>, a1: Pair<Float, Float>, b1: Pair<Float, Float>) {
        touch.idle(200)
        touch.pinch(a0, b0, a1, b1)
    }

    private fun around(center: Pair<Float, Float>, dx: Float, dy: Float) = (center.first + dx) to (center.second + dy)

    // ------------------------------------------------------------------ transform tool

    @Test
    fun pinchInsideThePastedPictureScalesItAndNotTheView() {
        val st0 = pasteBlock()
        val tool = transformTool()
        val m0 = Matrix(c.viewTransform.matrix)
        val undo0 = c.undoManager.undoCount
        val center = screen(150f, 140f)
        pinch(around(center, -30f, 0f), around(center, 30f, 0f), around(center, -60f, 0f), around(center, 60f, 0f))
        assertEquals("the view did not zoom", m0, c.viewTransform.matrix)
        val st = tool.transformState!!
        assertEquals(st0.width * 2f, st.width, 0.6f)
        assertEquals(st0.height * 2f, st.height, 0.6f)
        assertEquals(150f, st.center().x, 0.6f)
        assertEquals(140f, st.center().y, 0.6f)
        assertEquals("nothing recorded until confirmed", undo0, c.undoManager.undoCount)
        assertTrue(tool.isPlacement)
        tool.commit()
        Smoke.pump(50)
        assertEquals(BLUE, c.activeLayer.bitmap.getPixel(55, 140))
        assertEquals(0, c.activeLayer.bitmap.getPixel(45, 140))
    }

    @Test
    fun pinchBesideThePictureZoomsTheView() {
        val st0 = pasteBlock()
        val tool = transformTool()
        val z0 = c.viewTransform.zoom
        val spot = screen(320f, 250f)
        pinch(around(spot, -20f, 0f), around(spot, 20f, 0f), around(spot, -40f, 0f), around(spot, 40f, 0f))
        assertEquals(z0 * 2f, c.viewTransform.zoom, 0.02f)
        assertEquals(st0, tool.transformState)
        tool.discard()
        Smoke.pump(50)
    }

    @Test
    fun jitteryTwoFingerTapOnTheContentStillUndoes() {
        fill(c.activeLayer, 100f, 100f, 200f, 180f, BLUE) // one undo step
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(50)
        val tool = transformTool()
        assertNotNull("the layer was lifted", tool.transformState)
        assertFalse(tool.hasUserChanges)
        val m0 = Matrix(c.viewTransform.matrix)
        var feedback: String? = null
        view.onTapAction = { feedback = it }
        val a = screen(140f, 140f)
        val b = screen(160f, 140f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        touch.idle(20)
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, a.first, a.second), P(1, b.first, b.second), index = 1)
        touch.idle(30)
        // A few pixels of jitter: the tool previews it, then takes it back when it turns out a tap.
        touch.send(MotionEvent.ACTION_MOVE, P(0, a.first - 4f, a.second + 2f), P(1, b.first + 5f, b.second - 1f))
        touch.idle(30)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, a.first - 4f, a.second + 2f), P(1, b.first + 5f, b.second - 1f), index = 0)
        touch.idle(20)
        touch.send(MotionEvent.ACTION_UP, P(1, b.first + 5f, b.second - 1f))
        Smoke.pump(50)
        assertEquals("Undo: Seed", feedback)
        assertEquals("the seed was undone", 0, c.activeLayer.bitmap.getPixel(150, 140) ushr 24)
        assertEquals(0, c.undoManager.undoCount)
        assertEquals(m0, c.viewTransform.matrix)
    }

    @Test
    fun twoFingerTapDuringAPlacementTakesThePlacementBack() {
        pasteBlock()
        val layers = c.doc.layers.size
        val center = screen(150f, 140f)
        touch.idle(200)
        touch.twoFingerTap(around(center, -20f, 0f), around(center, 20f, 0f))
        Smoke.pump(100)
        assertFalse(transformTool().hasPendingWork)
        assertEquals("the pasted layer went away", layers - 1, c.doc.layers.size)
    }

    @Test
    fun rotationFollowsTheFingersAlsoOnAMirroredView() {
        for (mirrored in listOf(false, true)) {
            view.setMirrored(mirrored)
            val st0 = pasteBlock()
            val tool = transformTool()
            val s = screen(150f, 140f)
            val a0 = around(s, -40f, 0f); val b0 = around(s, 40f, 0f)
            // A quarter turn clockwise on screen.
            val a1 = around(s, 0f, -40f); val b1 = around(s, 0f, 40f)
            val grabbed = doc(a0)
            pinch(a0, b0, a1, b1)
            val st = tool.transformState!!
            assertEquals("mirrored=$mirrored", if (mirrored) -90f else 90f, st.rotationDeg, 0.01f)
            assertEquals(st0.width, st.width, 0.01f)
            // The content point first under finger A ends up under finger A again.
            val moved = st.map(st0.unmap(grabbed).x, st0.unmap(grabbed).y)
            val under = doc(a1)
            assertEquals("mirrored=$mirrored x", under.x, moved.x, 1f)
            assertEquals("mirrored=$mirrored y", under.y, moved.y, 1f)
            tool.discard()
            Smoke.pump(100)
            c.selectTool(ToolId.BRUSH)
            Smoke.pump(20)
        }
        view.setMirrored(false)
    }

    @Test
    fun aThirdFingerTakesThePinchBack() {
        val st0 = pasteBlock()
        val tool = transformTool()
        val s = screen(150f, 140f)
        val a = around(s, -30f, 0f); val b = around(s, 30f, 0f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        touch.idle(20)
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, a.first, a.second), P(1, b.first, b.second), index = 1)
        for (k in 1..5) {
            touch.idle(40)
            touch.send(MotionEvent.ACTION_MOVE, P(0, a.first - 8f * k, a.second), P(1, b.first + 8f * k, b.second))
        }
        assertNotEquals("pinching previews live", st0, tool.transformState)
        val a1 = around(a, -40f, 0f); val b1 = around(b, 40f, 0f)
        touch.idle(40)
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, a1.first, a1.second), P(1, b1.first, b1.second), P(2, s.first, s.second + 100f), index = 2)
        assertEquals("third finger: back to the start", st0, tool.transformState)
        touch.idle(40)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, a1.first, a1.second), P(1, b1.first, b1.second), P(2, s.first, s.second + 100f), index = 2)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, a1.first, a1.second), P(1, b1.first, b1.second), index = 1)
        touch.send(MotionEvent.ACTION_UP, P(0, a1.first, a1.second))
        Smoke.pump(50)
        assertEquals(st0, tool.transformState)
        tool.discard()
        Smoke.pump(50)
    }

    @Test
    fun systemCancelTakesThePinchBack() {
        val st0 = pasteBlock()
        val tool = transformTool()
        val s = screen(150f, 140f)
        val a = around(s, -30f, 0f); val b = around(s, 30f, 0f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, a.first, a.second), P(1, b.first, b.second), index = 1)
        touch.idle(100)
        touch.send(MotionEvent.ACTION_MOVE, P(0, a.first - 50f, a.second), P(1, b.first + 50f, b.second))
        assertNotEquals(st0, tool.transformState)
        touch.send(MotionEvent.ACTION_CANCEL, P(0, a.first - 50f, a.second), P(1, b.first + 50f, b.second))
        assertEquals(st0, tool.transformState)
        tool.discard()
        Smoke.pump(50)
    }

    @Test
    fun liftingOneFingerAndPuttingItBackContinuesThePinch() {
        val st0 = pasteBlock()
        val tool = transformTool()
        val s = screen(150f, 140f)
        pinch(around(s, -30f, 0f), around(s, 30f, 0f), around(s, -45f, 0f), around(s, 45f, 0f))
        assertEquals(st0.width * 1.5f, tool.transformState!!.width, 0.6f)

        // Re-grip: spread, lift one finger, put another one down, spread again.
        val a = around(s, -30f, 0f); val b = around(s, 30f, 0f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, a.first, a.second), P(1, b.first, b.second), index = 1)
        touch.idle(100)
        val a1 = around(s, -45f, 0f); val b1 = around(s, 45f, 0f)
        touch.send(MotionEvent.ACTION_MOVE, P(0, a1.first, a1.second), P(1, b1.first, b1.second))
        assertEquals(st0.width * 2.25f, tool.transformState!!.width, 0.6f)
        touch.idle(40)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, a1.first, a1.second), P(1, b1.first, b1.second), index = 1)
        touch.idle(40)
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, a1.first, a1.second), P(2, b1.first, b1.second), index = 1)
        touch.idle(40)
        val a2 = around(s, -90f, 0f); val b2 = around(s, 90f, 0f)
        touch.send(MotionEvent.ACTION_MOVE, P(0, a2.first, a2.second), P(2, b2.first, b2.second))
        touch.idle(40)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, a2.first, a2.second), P(2, b2.first, b2.second), index = 0)
        touch.send(MotionEvent.ACTION_UP, P(2, b2.first, b2.second))
        Smoke.pump(50)
        assertEquals(st0.width * 4.5f, tool.transformState!!.width, 1.5f)
        assertEquals(150f, tool.transformState!!.center().x, 1f)
        tool.discard()
        Smoke.pump(50)
    }

    // ------------------------------------------------------------------ text tool

    @Test
    fun pinchOnThePendingTextScalesItsFont() {
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(200f, 150f)
        text.setText("Hello")
        text.confirmEditor()
        val start = text.item!!
        val z0 = c.viewTransform.zoom
        val s = screen(200f, 150f)
        pinch(around(s, -10f, 0f), around(s, 10f, 0f), around(s, -20f, 0f), around(s, 20f, 0f))
        val item = text.item!!
        assertEquals(start.spec.sizePx * 2f, item.spec.sizePx, 0.5f)
        assertEquals(200f, item.cx, 0.6f)
        assertEquals(150f, item.cy, 0.6f)
        assertEquals(z0, c.viewTransform.zoom, 0f)

        // Turning the fingers turns the text; far from it the view zooms instead.
        pinch(around(s, -40f, 0f), around(s, 40f, 0f), around(s, 0f, -40f), around(s, 0f, 40f))
        assertEquals(90f, text.item!!.rotationDeg, 0.01f)
        val far = screen(30f, 30f)
        pinch(around(far, -10f, 0f), around(far, 10f, 0f), around(far, -20f, 0f), around(far, 20f, 0f))
        assertEquals(z0 * 2f, c.viewTransform.zoom, 0.02f)
        text.discard()
    }

    // ------------------------------------------------------------------ long-press color pick

    @Test
    fun holdingTheBrushStillPicksAColorWithAPreview() {
        val layer = c.activeLayer
        fill(layer, 20f, 20f, 180f, 280f, BLUE)
        fill(layer, 220f, 20f, 380f, 280f, GREEN)
        val before = IntArray(400 * 300).also { layer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        val undo0 = c.undoManager.undoCount
        assertEquals(ToolId.BRUSH, c.activeToolId)
        val p = screen(100f, 200f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, p.first, p.second))
        touch.idle(700)
        assertTrue("holding still started color picking", c.holdPicking)

        // The preview square above the finger shows blue over the current red.
        val out = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(out))
        val density = activity.resources.displayMetrics.density
        val box = RectF()
        EyedropperTool.placePreview(p.first, p.second, view.width.toFloat(), view.height.toFloat(), density, box)
        assertTrue("above the finger", box.bottom < p.second)
        assertEquals(BLUE, out.getPixel(box.centerX().toInt(), (box.top + box.height() / 4f).toInt()))
        assertEquals(RED, out.getPixel(box.centerX().toInt(), (box.bottom - box.height() / 4f).toInt()))

        // Slide onto the green half, a little at a time.
        val q = screen(300f, 200f)
        for (k in 1..10) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, P(0, p.first + (q.first - p.first) * k / 10f, p.second))
        }
        view.draw(Canvas(out))
        EyedropperTool.placePreview(q.first, q.second, view.width.toFloat(), view.height.toFloat(), density, box)
        assertEquals(GREEN, out.getPixel(box.centerX().toInt(), (box.top + box.height() / 4f).toInt()))
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, P(0, q.first, q.second))
        Smoke.pump(50)

        assertFalse(c.holdPicking)
        assertEquals(GREEN, c.color)
        assertEquals(ToolId.BRUSH, c.activeToolId)
        assertEquals("no undo step", undo0, c.undoManager.undoCount)
        val after = IntArray(400 * 300).also { layer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        assertArrayEquals("no paint", before, after)

        // A quick tiny hold-then-slide also works: long press, move 3 px, lift.
        val r = screen(300f, 100f)
        c.color = RED
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, r.first, r.second))
        touch.idle(600)
        touch.send(MotionEvent.ACTION_MOVE, P(0, r.first + 3f, r.second))
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, P(0, r.first + 3f, r.second))
        Smoke.pump(50)
        assertEquals(GREEN, c.color)
        assertEquals(undo0, c.undoManager.undoCount)
    }

    @Test
    fun rotatedViewPinchTurnsTheContentByTheFingerAngle() {
        val st0 = pasteBlock()
        val tool = transformTool()
        // Turn the view first (brush active: the pinch moves the view).
        c.selectTool(ToolId.BRUSH)
        Smoke.pump(20)
        transformTool().commit()
        Smoke.pump(20)
        val vc = view.width / 2f to view.height / 2f
        val r = 100f
        val ang = Math.toRadians(30.0)
        pinch(around(vc, -r, 0f), around(vc, r, 0f), around(vc, (-r * cos(ang)).toFloat(), (-r * sin(ang)).toFloat()), around(vc, (r * cos(ang)).toFloat(), (r * sin(ang)).toFloat()))
        assertEquals(30f, c.viewTransform.rotationDeg, 0.5f)
        // Paste again and turn it by 60 degrees clockwise on screen: 60 degrees in the document too.
        assertTrue(c.copySelection())
        c.paste()
        Smoke.pump(50)
        val start = tool.transformState!!
        val s = screen(start.center().x, start.center().y)
        val ang2 = Math.toRadians(60.0)
        pinch(
            around(s, -40f, 0f), around(s, 40f, 0f),
            around(s, (-40 * cos(ang2)).toFloat(), (-40 * sin(ang2)).toFloat()), around(s, (40 * cos(ang2)).toFloat(), (40 * sin(ang2)).toFloat()),
        )
        assertEquals(start.rotationDeg + 60f, tool.transformState!!.rotationDeg, 0.5f)
        assertEquals(st0.width, tool.transformState!!.width, 0.5f)
        tool.discard()
        Smoke.pump(50)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GREEN = 0xFF00FF00.toInt()
    }
}
