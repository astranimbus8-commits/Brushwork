package com.brushwork.paint.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LambdaAction
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives [CanvasView] with synthetic MotionEvents (Robolectric, real Skia) to check the input
 * state machine against a real [EditorController]: strokes, stroke cancel by a second finger,
 * pinch zoom, two/three-finger taps, stylus-only panning and palm rejection.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasViewRobolectricTest {
    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var controller: EditorController
    private lateinit var view: CanvasView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val doc = Document("test", "Test", 800, 600)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(800, 600))
        controller = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), settings)
        view = CanvasView(context, controller)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1000, 800)
    }

    private data class P(val id: Int, val x: Float, val y: Float, val tool: Int = MotionEvent.TOOL_TYPE_FINGER)

    private fun event(action: Int, time: Long, vararg pts: P, index: Int = 0): MotionEvent {
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = pts[i].id; toolType = pts[i].tool } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].x; y = pts[i].y; pressure = 0.5f; size = 0.1f } }
        val masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(0L, time, masked, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun send(action: Int, time: Long, vararg pts: P, index: Int = 0) {
        val e = event(action, time, *pts, index = index)
        view.onTouchEvent(e)
        e.recycle()
    }

    private fun pushTestAction(label: String, onUndo: () -> Unit = {}, onRedo: () -> Unit = {}) =
        controller.pushUndo(LambdaAction(label, onUndo = { onUndo() }, onRedo = { onRedo() }))

    @Test
    fun firstLayoutFitsTheDocumentAndSetsTheViewTransform() {
        val t = controller.viewTransform
        // min(1000 * 0.9 / 800, 800 * 0.9 / 600) = 1.125
        assertEquals(1.125f, t.zoom, 1e-4f)
        val center = t.docToScreen(400f, 300f)
        assertEquals(500f, center.x, 0.01f)
        assertEquals(400f, center.y, 0.01f)
        assertEquals(context.resources.displayMetrics.density, t.density, 0f)
    }

    @Test
    fun singleFingerStrokeGoesToTheTool() {
        send(MotionEvent.ACTION_DOWN, 0, P(0, 300f, 300f))
        assertTrue(controller.isInteracting)
        send(MotionEvent.ACTION_MOVE, 16, P(0, 320f, 310f))
        send(MotionEvent.ACTION_UP, 32, P(0, 330f, 320f))
        assertFalse(controller.isInteracting)
    }

    @Test
    fun secondFingerCancelsTheStrokeAndPinchZooms() {
        val z0 = controller.viewTransform.zoom
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        assertTrue(controller.isInteracting)
        send(MotionEvent.ACTION_POINTER_DOWN, 20, P(0, 400f, 400f), P(1, 600f, 400f), index = 1)
        assertFalse("stroke cancelled", controller.isInteracting)
        send(MotionEvent.ACTION_MOVE, 40, P(0, 300f, 400f), P(1, 700f, 400f))
        assertEquals(z0 * 2f, controller.viewTransform.zoom, 1e-3f)
        send(MotionEvent.ACTION_POINTER_UP, 400, P(0, 300f, 400f), P(1, 700f, 400f), index = 0)
        send(MotionEvent.ACTION_UP, 420, P(1, 700f, 400f))
        assertFalse(controller.isInteracting)
        assertEquals(z0 * 2f, controller.viewTransform.zoom, 1e-3f)
    }

    @Test
    fun twoFingerTapUndoesAndRestoresTheView() {
        var undone = false
        pushTestAction("Test stroke", onUndo = { undone = true })
        val before = Matrix(controller.viewTransform.matrix)
        var feedback: String? = null
        view.onTapAction = { feedback = it }
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        send(MotionEvent.ACTION_POINTER_DOWN, 30, P(0, 400f, 400f), P(1, 600f, 400f), index = 1)
        send(MotionEvent.ACTION_MOVE, 60, P(0, 403f, 402f), P(1, 604f, 401f))
        send(MotionEvent.ACTION_POINTER_UP, 120, P(0, 403f, 402f), P(1, 604f, 401f), index = 0)
        send(MotionEvent.ACTION_UP, 150, P(1, 604f, 401f))
        assertTrue(undone)
        assertEquals("Undo: Test stroke", feedback)
        assertEquals("tap jitter reverted", before, controller.viewTransform.matrix)
    }

    @Test
    fun threeFingerTapRedoes() {
        var redone = false
        pushTestAction("Fill", onRedo = { redone = true })
        controller.undo()
        var feedback: String? = null
        view.onTapAction = { feedback = it }
        send(MotionEvent.ACTION_DOWN, 0, P(0, 300f, 400f))
        send(MotionEvent.ACTION_POINTER_DOWN, 20, P(0, 300f, 400f), P(1, 500f, 400f), index = 1)
        send(MotionEvent.ACTION_POINTER_DOWN, 40, P(0, 300f, 400f), P(1, 500f, 400f), P(2, 700f, 400f), index = 2)
        send(MotionEvent.ACTION_POINTER_UP, 150, P(0, 300f, 400f), P(1, 500f, 400f), P(2, 700f, 400f), index = 0)
        send(MotionEvent.ACTION_POINTER_UP, 160, P(1, 500f, 400f), P(2, 700f, 400f), index = 0)
        send(MotionEvent.ACTION_UP, 170, P(2, 700f, 400f))
        assertTrue(redone)
        assertEquals("Redo: Fill", feedback)
    }

    @Test
    fun disabledTwoFingerUndoDoesNothing() {
        settings.twoFingerUndo = false
        var undone = false
        pushTestAction("Test", onUndo = { undone = true })
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        send(MotionEvent.ACTION_POINTER_DOWN, 30, P(0, 400f, 400f), P(1, 600f, 400f), index = 1)
        send(MotionEvent.ACTION_POINTER_UP, 120, P(0, 400f, 400f), P(1, 600f, 400f), index = 0)
        send(MotionEvent.ACTION_UP, 150, P(1, 600f, 400f))
        assertFalse(undone)
    }

    @Test
    fun slowTwoFingerTouchIsNotAnUndo() {
        var undone = false
        pushTestAction("Test", onUndo = { undone = true })
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        send(MotionEvent.ACTION_POINTER_DOWN, 30, P(0, 400f, 400f), P(1, 600f, 400f), index = 1)
        send(MotionEvent.ACTION_POINTER_UP, 500, P(0, 400f, 400f), P(1, 600f, 400f), index = 0)
        send(MotionEvent.ACTION_UP, 520, P(1, 600f, 400f))
        assertFalse(undone)
    }

    @Test
    fun stylusOnlyModeMakesOneFingerPan() {
        settings.stylusOnlyDrawing = true
        val before = controller.viewTransform.docToScreen(100f, 100f)
        send(MotionEvent.ACTION_DOWN, 0, P(0, 500f, 400f))
        assertFalse("finger does not draw", controller.isInteracting)
        send(MotionEvent.ACTION_MOVE, 16, P(0, 550f, 430f))
        val after = controller.viewTransform.docToScreen(100f, 100f)
        assertEquals(before.x + 50f, after.x, 0.01f)
        assertEquals(before.y + 30f, after.y, 0.01f)
        send(MotionEvent.ACTION_UP, 32, P(0, 550f, 430f))

        // The stylus still draws.
        send(MotionEvent.ACTION_DOWN, 100, P(0, 500f, 400f, MotionEvent.TOOL_TYPE_STYLUS))
        assertTrue(controller.isInteracting)
        send(MotionEvent.ACTION_UP, 120, P(0, 500f, 400f, MotionEvent.TOOL_TYPE_STYLUS))
        assertFalse(controller.isInteracting)
    }

    @Test
    fun palmDuringAPenStrokeIsIgnored() {
        var undone = false
        pushTestAction("Test", onUndo = { undone = true })
        val m0 = Matrix(controller.viewTransform.matrix)
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f, MotionEvent.TOOL_TYPE_STYLUS))
        send(MotionEvent.ACTION_POINTER_DOWN, 20, P(0, 400f, 400f, MotionEvent.TOOL_TYPE_STYLUS), P(1, 700f, 600f), index = 1)
        assertTrue("pen stroke continues", controller.isInteracting)
        send(MotionEvent.ACTION_MOVE, 40, P(0, 420f, 410f, MotionEvent.TOOL_TYPE_STYLUS), P(1, 760f, 640f))
        assertEquals("palm does not move the view", m0, controller.viewTransform.matrix)
        send(MotionEvent.ACTION_POINTER_UP, 60, P(0, 420f, 410f, MotionEvent.TOOL_TYPE_STYLUS), P(1, 760f, 640f), index = 1)
        send(MotionEvent.ACTION_UP, 80, P(0, 420f, 410f, MotionEvent.TOOL_TYPE_STYLUS))
        assertFalse(controller.isInteracting)
        assertFalse("never a tap", undone)
    }

    @Test
    fun stylusTakesOverFromAFingerStroke() {
        send(MotionEvent.ACTION_DOWN, 0, P(0, 700f, 600f))
        assertTrue(controller.isInteracting)
        send(MotionEvent.ACTION_POINTER_DOWN, 20, P(0, 700f, 600f), P(1, 400f, 300f, MotionEvent.TOOL_TYPE_STYLUS), index = 1)
        assertTrue("pen stroke started", controller.isInteracting)
        val m0 = Matrix(controller.viewTransform.matrix)
        send(MotionEvent.ACTION_MOVE, 40, P(0, 760f, 650f), P(1, 420f, 310f, MotionEvent.TOOL_TYPE_STYLUS))
        assertEquals(m0, controller.viewTransform.matrix)
        send(MotionEvent.ACTION_POINTER_UP, 60, P(0, 760f, 650f), P(1, 420f, 310f, MotionEvent.TOOL_TYPE_STYLUS), index = 1)
        assertFalse(controller.isInteracting)
        send(MotionEvent.ACTION_UP, 80, P(0, 760f, 650f))
        assertFalse(controller.isInteracting)
    }

    @Test
    fun twoFingerTapStillWorksAfterAPenTakeover() {
        // Palm lands first, the pen takes over, both lift (the palm's UP is ignored).
        send(MotionEvent.ACTION_DOWN, 0, P(0, 700f, 600f))
        send(MotionEvent.ACTION_POINTER_DOWN, 20, P(0, 700f, 600f), P(1, 400f, 300f, MotionEvent.TOOL_TYPE_STYLUS), index = 1)
        send(MotionEvent.ACTION_POINTER_UP, 60, P(0, 700f, 600f), P(1, 400f, 300f, MotionEvent.TOOL_TYPE_STYLUS), index = 1)
        send(MotionEvent.ACTION_UP, 80, P(0, 700f, 600f))
        // A later two-finger tap must be recognized again.
        var undone = false
        pushTestAction("Test", onUndo = { undone = true })
        send(MotionEvent.ACTION_DOWN, 1000, P(0, 400f, 400f))
        send(MotionEvent.ACTION_POINTER_DOWN, 1030, P(0, 400f, 400f), P(1, 600f, 400f), index = 1)
        send(MotionEvent.ACTION_POINTER_UP, 1120, P(0, 400f, 400f), P(1, 600f, 400f), index = 0)
        send(MotionEvent.ACTION_UP, 1150, P(1, 600f, 400f))
        assertTrue(undone)
    }

    @Test
    fun fitCentersBetweenReportedChromeAndIgnoresSmallChanges() {
        view.setFitInsets(0f, 300f, 0f, 200f)
        val t = controller.viewTransform
        // Free area 1000 x 300: min(1000 * 0.9 / 800, 300 * 0.9 / 600) = 0.45, centered at y = 450.
        assertEquals(0.45f, t.zoom, 1e-4f)
        assertEquals(450f, t.docToScreen(400f, 300f).y, 0.01f)
        // A few pixels of chrome change (another tool's option strip) must not move the canvas.
        view.setFitInsets(0f, 304f, 0f, 200f)
        assertEquals(450f, t.docToScreen(400f, 300f).y, 0.01f)
        // Once the user adjusted the view, even large chrome changes keep it.
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        send(MotionEvent.ACTION_POINTER_DOWN, 20, P(0, 400f, 400f), P(1, 600f, 400f), index = 1)
        send(MotionEvent.ACTION_MOVE, 40, P(0, 350f, 400f), P(1, 650f, 400f))
        send(MotionEvent.ACTION_POINTER_UP, 600, P(0, 350f, 400f), P(1, 650f, 400f), index = 0)
        send(MotionEvent.ACTION_UP, 620, P(1, 650f, 400f))
        val zoomed = t.zoom
        view.setFitInsets(0f, 100f, 0f, 100f)
        assertEquals(zoomed, t.zoom, 1e-5f)
        // Fit to screen uses the latest chrome.
        view.fitToScreen()
        assertEquals(400f, t.docToScreen(400f, 300f).y, 0.01f)
    }

    @Test
    fun strokeNeverDanglesWhenTheFinalUpIsFromAnotherPointer() {
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        assertTrue(controller.isInteracting)
        // Inconsistent stream (the drawing pointer's id changed): the gesture still ends.
        send(MotionEvent.ACTION_UP, 30, P(5, 410f, 400f))
        assertFalse("tool released", controller.isInteracting)
        send(MotionEvent.ACTION_DOWN, 100, P(0, 400f, 400f))
        assertTrue("next stroke starts normally", controller.isInteracting)
        send(MotionEvent.ACTION_UP, 120, P(0, 400f, 400f))
    }

    @Test
    fun viewCommandsDropAStrokeInProgress() {
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        assertTrue(controller.isInteracting)
        view.actualPixels()
        assertFalse("stroke dropped before the view moves", controller.isInteracting)
        // The rest of that finger's gesture neither draws nor pans.
        val m = Matrix(controller.viewTransform.matrix)
        send(MotionEvent.ACTION_MOVE, 20, P(0, 450f, 420f))
        send(MotionEvent.ACTION_UP, 40, P(0, 450f, 420f))
        assertFalse(controller.isInteracting)
        assertEquals(m, controller.viewTransform.matrix)

        send(MotionEvent.ACTION_DOWN, 100, P(0, 400f, 400f))
        view.setMirrored(true)
        assertFalse(controller.isInteracting)
        send(MotionEvent.ACTION_UP, 120, P(0, 400f, 400f))
    }

    @Test
    fun chromeActionEndsTheCanvasStrokeCleanly() {
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        send(MotionEvent.ACTION_MOVE, 16, P(0, 420f, 400f))
        assertTrue(controller.isInteracting)
        // A hotbar button tapped with another finger.
        controller.endCanvasGesture()
        assertFalse(controller.isInteracting)
        send(MotionEvent.ACTION_MOVE, 32, P(0, 440f, 400f))
        assertFalse("later samples of that finger are ignored", controller.isInteracting)
        send(MotionEvent.ACTION_UP, 48, P(0, 450f, 400f))
        send(MotionEvent.ACTION_DOWN, 100, P(0, 400f, 400f))
        assertTrue(controller.isInteracting)
        send(MotionEvent.ACTION_UP, 120, P(0, 400f, 400f))
    }

    @Test
    fun inputIsIgnoredWhileBusy() {
        var release: (() -> Unit)? = null
        controller.runBusy("Working") { kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c -> release = { c.resumeWith(Result.success(Unit)) } } }
        assertEquals("Working", controller.busyMessage)
        send(MotionEvent.ACTION_DOWN, 0, P(0, 400f, 400f))
        assertFalse(controller.isInteracting)
        send(MotionEvent.ACTION_UP, 10, P(0, 400f, 400f))
        release!!.invoke()
        assertNull(controller.busyMessage)
        send(MotionEvent.ACTION_DOWN, 20, P(0, 400f, 400f))
        assertTrue(controller.isInteracting)
        send(MotionEvent.ACTION_UP, 30, P(0, 400f, 400f))
    }

    @Test
    fun mirroringKeepsInputUnderTheFinger() {
        view.setMirrored(true)
        val t = controller.viewTransform
        assertTrue(t.isMirrored)
        val screen = t.docToScreen(100f, 200f)
        val doc = t.screenToDoc(screen.x, screen.y)
        assertEquals(100f, doc.x, 0.01f)
        assertEquals(200f, doc.y, 0.01f)
        // The left edge of the document is now on the right half of the view.
        assertTrue(t.docToScreen(0f, 300f).x > 500f)
    }

    @Test
    fun viewportMatrixMatchesViewTransformHelpers() {
        val v = Viewport()
        v.resize(1000, 800)
        v.fit(640, 480)
        v.rotateAround(500f, 400f, 30f)
        val values = FloatArray(9)
        v.matrixValues(values)
        val m = Matrix().apply { setValues(values) }
        val t = ViewTransform().apply { set(m) }
        assertEquals(v.scale, t.zoom, 1e-4f)
        assertEquals(30f, t.rotationDeg, 1e-3f)
        assertFalse(t.isMirrored)
        v.mirrored = true
        v.matrixValues(values)
        t.set(Matrix().apply { setValues(values) })
        assertTrue(t.isMirrored)
        val out = FloatArray(2)
        v.docToScreen(123f, 45f, out)
        val p = t.docToScreen(123f, 45f)
        assertEquals(out[0], p.x, 0.01f)
        assertEquals(out[1], p.y, 0.01f)
    }

    @Test
    fun drawsDocumentWithoutCrashingAndFillsBackdrop() {
        controller.doc.layers[0].bitmap.eraseColor(0xFFFF0000.toInt())
        controller.invalidateDoc(null)
        val bmp = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        // Document center is red, the corner shows the backdrop.
        assertEquals(0xFFFF0000.toInt(), bmp.getPixel(500, 400))
        assertNotEquals(0xFFFF0000.toInt(), bmp.getPixel(2, 2))
    }
}
