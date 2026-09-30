package com.brushwork.paint.ui.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The canvas while a floating window (the layers window) is open, through a real [CanvasView]
 * driven with MotionEvents: a quick tap only reports the tap outside (no dot, no text box, no
 * undo step), while strokes still draw from their first point, holding still still picks a color,
 * and pinches, pen input and two-finger undo work as always. Also the touch-down report used to
 * minimize menus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi")
class CanvasOutsideTapRobolectricTest {
    private lateinit var activity: ComponentActivity
    private lateinit var c: EditorController
    private lateinit var view: CanvasView
    private lateinit var touch: Smoke.Touch
    private var outsideTaps = 0
    private var touchDowns = 0

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
        view.onOutsideTap = { outsideTaps++ }
        view.onTouchDown = { touchDowns++ }
    }

    @After
    fun tearDown() {
        Smoke.pump(50)
        Smoke.assertQuiet(c, "end of test")
        c.dispose()
    }

    private fun screen(x: Float, y: Float): Pair<Float, Float> = c.viewTransform.docToScreen(x, y).let { it.x to it.y }

    private fun pixels(): IntArray = IntArray(400 * 300).also { c.activeLayer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }

    @Test
    fun aQuickTapOnlyReportsTheTapOutside() {
        val before = pixels()
        val undo0 = c.undoManager.undoCount
        val p = screen(200f, 150f)
        touch.idle(300)
        touch.tap(p.first, p.second)
        Smoke.pump(50)
        assertEquals("the tap was reported", 1, outsideTaps)
        assertEquals("a touch started", 1, touchDowns)
        assertEquals("no undo step", undo0, c.undoManager.undoCount)
        assertTrue("no dot painted", before.contentEquals(pixels()))
        assertFalse(c.isInteracting)
    }

    @Test
    fun aTapWithTheTextToolStartsNoText() {
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        val p = screen(200f, 150f)
        touch.idle(300)
        touch.tap(p.first, p.second)
        Smoke.pump(50)
        assertEquals(1, outsideTaps)
        assertFalse("no text box started", text.hasPendingWork)
        assertFalse(text.editorOpen)
    }

    @Test
    fun aStrokeStillDrawsFromItsFirstPoint() {
        val undo0 = c.undoManager.undoCount
        touch.idle(300)
        touch.stroke(screen(60f, 150f), screen(340f, 150f))
        Smoke.pump(50)
        assertEquals("a stroke is no tap", 0, outsideTaps)
        assertEquals("one stroke recorded", undo0 + 1, c.undoManager.undoCount)
        assertEquals("painted where the finger went down", RED, c.activeLayer.bitmap.getPixel(61, 150))
        assertEquals("painted along the way", RED, c.activeLayer.bitmap.getPixel(200, 150))
        assertEquals("painted where it lifted", RED, c.activeLayer.bitmap.getPixel(339, 150))
        assertEquals("nothing elsewhere", 0, c.activeLayer.bitmap.getPixel(200, 60))
    }

    @Test
    fun aSlowStartIsStillAStroke() {
        // Tiny moves (inside the tap slop) first, then a real drag: all of it is drawn.
        val a = screen(100f, 100f)
        val undo0 = c.undoManager.undoCount
        touch.idle(300)
        touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        for (k in 1..4) { touch.idle(16); touch.send(MotionEvent.ACTION_MOVE, P(0, a.first + k, a.second)) }
        val b = screen(300f, 100f)
        for (k in 1..10) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, P(0, a.first + 4f + (b.first - a.first - 4f) * k / 10f, a.second))
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, P(0, b.first, b.second))
        Smoke.pump(50)
        assertEquals(0, outsideTaps)
        assertEquals(undo0 + 1, c.undoManager.undoCount)
        assertEquals(RED, c.activeLayer.bitmap.getPixel(101, 100))
        assertEquals(RED, c.activeLayer.bitmap.getPixel(299, 100))
    }

    @Test
    fun holdingStillStillPicksAColor() {
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(20f, 20f, 380f, 280f, Paint().apply { color = BLUE }) }
        val undo0 = c.undoManager.undoCount
        val p = screen(200f, 150f)
        touch.idle(300)
        touch.send(MotionEvent.ACTION_DOWN, P(0, p.first, p.second))
        touch.idle(700)
        assertTrue("holding still started color picking", c.holdPicking)
        touch.send(MotionEvent.ACTION_UP, P(0, p.first, p.second))
        Smoke.pump(50)
        assertEquals("a held finger is no tap", 0, outsideTaps)
        assertEquals("picked the color under the finger", BLUE, c.color)
        assertEquals("picking records nothing", undo0, c.undoManager.undoCount)
    }

    @Test
    fun aPinchZoomsTheViewAndIsNoTap() {
        val z0 = c.viewTransform.zoom
        val undo0 = c.undoManager.undoCount
        val m = screen(200f, 150f)
        touch.idle(300)
        touch.pinch(m.first - 40f to m.second, m.first + 40f to m.second, m.first - 80f to m.second, m.first + 80f to m.second)
        Smoke.pump(50)
        assertEquals(z0 * 2f, c.viewTransform.zoom, 0.02f)
        assertEquals(0, outsideTaps)
        assertEquals(undo0, c.undoManager.undoCount)
    }

    @Test
    fun aTwoFingerTapStillUndoes() {
        touch.idle(300)
        touch.stroke(screen(60f, 150f), screen(340f, 150f))
        val undo0 = c.undoManager.undoCount
        touch.idle(300)
        touch.twoFingerTap(screen(150f, 100f), screen(250f, 100f))
        Smoke.pump(50)
        assertEquals("undone", undo0 - 1, c.undoManager.undoCount)
        assertEquals(0, c.activeLayer.bitmap.getPixel(200, 150))
        assertEquals("an undo tap is no tap outside", 0, outsideTaps)
    }

    @Test
    fun aPenTapWithAPalmDownIsATapAndThePalmNeverDraws() {
        val before = pixels()
        val pen = screen(200f, 150f)
        val palm = screen(320f, 250f)
        touch.idle(300)
        touch.send(MotionEvent.ACTION_DOWN, P(0, pen.first, pen.second, MotionEvent.TOOL_TYPE_STYLUS))
        touch.idle(20)
        touch.send(
            MotionEvent.ACTION_POINTER_DOWN,
            P(0, pen.first, pen.second, MotionEvent.TOOL_TYPE_STYLUS), P(1, palm.first, palm.second), index = 1,
        )
        touch.idle(30)
        touch.send(
            MotionEvent.ACTION_POINTER_UP,
            P(0, pen.first, pen.second, MotionEvent.TOOL_TYPE_STYLUS), P(1, palm.first, palm.second), index = 0,
        )
        touch.idle(200)
        touch.send(MotionEvent.ACTION_MOVE, P(1, palm.first + 40f, palm.second))
        touch.idle(20)
        touch.send(MotionEvent.ACTION_UP, P(1, palm.first + 40f, palm.second))
        Smoke.pump(50)
        assertEquals("the pen tapped", 1, outsideTaps)
        assertTrue("nothing painted", before.contentEquals(pixels()))
    }

    @Test
    fun aPenArrivingDuringAHeldFingerDrawsAndTheFingerIsAPalm() {
        val finger = screen(320f, 250f)
        val a = screen(60f, 100f)
        val b = screen(300f, 100f)
        touch.idle(300)
        touch.send(MotionEvent.ACTION_DOWN, P(0, finger.first, finger.second))
        touch.idle(20)
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, finger.first, finger.second), P(1, a.first, a.second, MotionEvent.TOOL_TYPE_STYLUS), index = 1)
        for (k in 1..10) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, P(0, finger.first, finger.second), P(1, a.first + (b.first - a.first) * k / 10f, a.second, MotionEvent.TOOL_TYPE_STYLUS))
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, finger.first, finger.second), P(1, b.first, b.second, MotionEvent.TOOL_TYPE_STYLUS), index = 1)
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, P(0, finger.first, finger.second))
        Smoke.pump(50)
        assertEquals(0, outsideTaps)
        assertEquals("the pen drew", RED, c.activeLayer.bitmap.getPixel(180, 100))
        assertEquals("the finger never drew", 0, c.activeLayer.bitmap.getPixel(320, 250))
    }

    @Test
    fun withStylusOnlyDrawingAFingerTapIsATapAndTheViewStays() {
        c.settings.stylusOnlyDrawing = true
        val m0 = android.graphics.Matrix(c.viewTransform.matrix)
        val p = screen(200f, 150f)
        touch.idle(300)
        touch.send(MotionEvent.ACTION_DOWN, P(0, p.first, p.second))
        touch.idle(20)
        touch.send(MotionEvent.ACTION_MOVE, P(0, p.first + 3f, p.second + 2f))
        touch.idle(20)
        touch.send(MotionEvent.ACTION_UP, P(0, p.first + 3f, p.second + 2f))
        Smoke.pump(50)
        assertEquals(1, outsideTaps)
        assertEquals("the few pixels of pan are taken back", m0, c.viewTransform.matrix)
        // A real pan is no tap.
        touch.idle(300)
        touch.stroke(p, p.first + 120f to p.second)
        assertEquals(1, outsideTaps)
        c.settings.stylusOnlyDrawing = false
    }

    @Test
    fun withoutAWindowATapPaintsAsAlways() {
        view.onOutsideTap = null
        val undo0 = c.undoManager.undoCount
        val p = screen(200f, 150f)
        touch.idle(300)
        touch.tap(p.first, p.second)
        Smoke.pump(50)
        assertEquals(0, outsideTaps)
        assertEquals("a dot", undo0 + 1, c.undoManager.undoCount)
        assertEquals(RED, c.activeLayer.bitmap.getPixel(200, 150))
    }

    @Test
    fun theTouchDownIsReportedBeforeTheToolSeesIt() {
        view.onOutsideTap = null
        var interactingAtDown: Boolean? = null
        view.onTouchDown = { touchDowns++; interactingAtDown = c.isInteracting }
        touch.idle(300)
        touch.stroke(screen(60f, 150f), screen(340f, 150f))
        assertEquals(1, touchDowns)
        assertEquals("reported before the stroke began", false, interactingAtDown)
        // Pinches report their first finger only.
        val m = screen(200f, 150f)
        touch.idle(300)
        touch.pinch(m.first - 40f to m.second, m.first + 40f to m.second, m.first - 60f to m.second, m.first + 60f to m.second)
        assertEquals(2, touchDowns)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
