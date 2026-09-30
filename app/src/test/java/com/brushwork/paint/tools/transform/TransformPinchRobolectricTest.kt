package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Two-finger pinch on the transform tool through the controller's two-finger API (real Skia):
 * scale/rotate/move of lifted content, cancel, idle lifting, distort, and the background lift of
 * large layers.
 */
@RunWith(RobolectricTestRunner::class)
class TransformPinchRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun setup(w: Int, h: Int, content: Rect?, zoom: Float): Pair<EditorController, Layer> {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        if (content != null) Canvas(layer.bitmap).drawRect(content, Paint().apply { color = RED })
        doc.layers += layer
        val c = EditorController(app, doc, scope, settings)
        c.viewTransform.set(Matrix().apply { setScale(zoom, zoom) })
        return c to layer
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun tool(c: EditorController) = c.tools.getValue(ToolId.TRANSFORM) as TransformTool

    private fun activate(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        return tool(c)
    }

    private fun pumpUntil(timeoutMs: Long = 20_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    private fun startAt(c: EditorController, x: Float, y: Float, half: Float = 10f) =
        c.twoFingerStart(Vec2(x, y), Vec2(x - half, y), Vec2(x + half, y))

    @Test
    fun pinchScalesTheLiftedContentAndCancelPutsItBack() {
        val (c, layer) = setup(128, 128, Rect(20, 20, 60, 60), zoom = 2f)
        val tool = activate(c)
        val st0 = tool.transformState!!
        assertFalse("fingers away from the box: the view zooms", startAt(c, 100f, 100f))

        assertTrue(startAt(c, 40f, 40f))
        c.twoFingerGesture(Vec2.ZERO, 1.5f, 0f)
        assertEquals(60f, tool.transformState!!.width, 1e-3f)
        assertEquals(Vec2(40f, 40f), tool.transformState!!.center())
        assertTrue("a pinch is the user's work", tool.hasUserChanges)
        assertEquals("nothing recorded while pending", 0, c.undoManager.undoCount)
        c.twoFingerEnd(cancelled = true)
        assertTrue(tool.transformState!!.sameGeometry(st0))

        assertTrue(startAt(c, 40f, 40f))
        c.twoFingerGesture(Vec2(4f, 0f), 2f, 0f)
        c.twoFingerEnd(cancelled = false)
        val st = tool.transformState!!
        assertEquals(80f, st.width, 1e-3f)
        assertEquals(DocBox(4f, 0f, 84f, 80f), st.bounds())
        tool.commit()
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        val b = layer.bitmap
        assertEquals(RED, b.getPixel(6, 2))
        assertEquals(RED, b.getPixel(82, 78))
        assertEquals(0, b.getPixel(86, 40))
        c.undo()
        assertEquals(RED, b.getPixel(21, 21))
        assertEquals(0, b.getPixel(6, 2))
    }

    @Test
    fun rotationFromTheFingersTurnsTheContent() {
        val (c, _) = setup(128, 128, Rect(20, 20, 60, 60), zoom = 2f)
        val tool = activate(c)
        assertTrue(startAt(c, 40f, 40f))
        c.twoFingerGesture(Vec2.ZERO, 1f, 30f)
        assertEquals(30f, tool.transformState!!.rotationDeg, 1e-3f)
        c.twoFingerGesture(Vec2.ZERO, 1f, 2f) // tiny turn while scaling: keeps the original angle
        assertEquals(0f, tool.transformState!!.rotationDeg, 0f)
        c.twoFingerEnd(cancelled = false)
    }

    @Test
    fun numberEditsWaitWhileFingersPinch() {
        val (c, _) = setup(128, 128, Rect(20, 20, 60, 60), zoom = 2f)
        val tool = activate(c)
        assertTrue(startAt(c, 40f, 40f))
        c.twoFingerGesture(Vec2.ZERO, 2f, 0f)
        tool.moveBy(10f, 0f)
        assertEquals(Vec2(40f, 40f), tool.transformState!!.center())
        c.twoFingerEnd(cancelled = false)
        tool.moveBy(10f, 0f)
        assertEquals(Vec2(50f, 40f), tool.transformState!!.center())
    }

    @Test
    fun idleToolLiftsOnlyWhenTheFingersAreOnTheContent() {
        val (c, _) = setup(128, 128, Rect(20, 20, 60, 60), zoom = 2f)
        val tool = activate(c)
        tool.commit() // untouched: ends the lift, the tool is idle
        assertNull(tool.transformState)
        assertFalse(startAt(c, 100f, 100f))
        assertNull("nothing lifted for a pinch beside the content", tool.transformState)
        assertTrue(startAt(c, 40f, 40f))
        assertNotNull(tool.transformState)
        c.twoFingerGesture(Vec2.ZERO, 2f, 0f)
        c.twoFingerEnd(cancelled = false)
        assertEquals(80f, tool.transformState!!.width, 1e-3f)
    }

    @Test
    fun lockedLayerDeclinesQuietly() {
        val (c, layer) = setup(128, 128, Rect(20, 20, 60, 60), zoom = 2f)
        layer.locked = true
        val tool = activate(c)
        c.message = null
        assertFalse(startAt(c, 40f, 40f))
        assertNull(tool.transformState)
        assertNull("no message for a pinch: the view just zooms", c.message)
    }

    @Test
    fun distortedQuadScalesAsAWhole() {
        val (c, _) = setup(128, 128, Rect(20, 20, 60, 60), zoom = 2f)
        val tool = activate(c)
        tool.mode = TransformTool.Mode.DISTORT
        c.pointerDown(ToolPoint(20f, 20f))
        c.pointerMove(ToolPoint(12f, 16f))
        c.pointerUp(ToolPoint(12f, 16f))
        val distorted = tool.transformState!!
        assertTrue(distorted.isDistorted)
        val focus = Vec2(40f, 40f)
        assertTrue(startAt(c, focus.x, focus.y))
        c.twoFingerGesture(Vec2.ZERO, 1.5f, 0f)
        val st = tool.transformState!!
        assertTrue(st.isDistorted)
        for (i in 0 until 4) {
            val expected = focus + (distorted.corner(i) - focus) * 1.5f
            assertEquals(expected.x, st.corner(i).x, 1e-2f)
            assertEquals(expected.y, st.corner(i).y, 1e-2f)
        }
        c.twoFingerEnd(cancelled = false)
    }

    @Test
    fun placementCanBePinched() {
        val (c, _) = setup(200, 200, Rect(40, 40, 80, 80), zoom = 1f)
        assertTrue(c.copySelection())
        val pasted = c.paste()!!
        idle()
        val tool = tool(c)
        assertTrue(tool.isPlacement)
        assertTrue(startAt(c, 60f, 60f))
        c.twoFingerGesture(Vec2(10f, 10f), 0.5f, 90f)
        c.twoFingerEnd(cancelled = false)
        val st = tool.transformState!!
        assertEquals(20f, st.width, 1e-3f)
        assertEquals(90f, st.rotationDeg, 1e-3f)
        assertEquals(DocBox(60f, 60f, 80f, 80f), st.bounds())
        tool.commit()
        assertEquals(RED, pasted.bitmap.getPixel(70, 70))
        assertEquals(0, pasted.bitmap.getPixel(50, 50))
    }

    @Test
    fun largeLayerPinchWaitsForTheBackgroundLift() {
        // 1500 x 1500 is above the size scanned on the main thread (like a phone-sized canvas).
        val (c, _) = setup(1500, 1500, Rect(600, 600, 900, 900), zoom = 0.5f)
        c.selectTool(ToolId.TRANSFORM) // its own lift is deferred and hasn't run yet
        val tool = tool(c)
        assertFalse("empty area: no lift started for a pinch there", startAt(c, 100f, 100f, half = 40f))
        assertFalse(tool.isPreparing)

        assertTrue(startAt(c, 750f, 750f, half = 40f))
        assertTrue("content under the fingers is lifted in the background", tool.isPreparing)
        assertNull(tool.transformState)
        c.twoFingerGesture(Vec2.ZERO, 2f, 0f)
        c.twoFingerEnd(cancelled = false)
        assertTrue(pumpUntil { tool.transformState != null && !tool.isPreparing })
        val st = tool.transformState!!
        assertEquals("the finished pinch was applied when the content landed", 600f, st.width, 1e-3f)
        assertEquals(Vec2(750f, 750f), st.center())
        assertTrue(tool.hasUserChanges)
        idle()
        assertEquals("the tool's own deferred lift did not replace it", 600f, tool.transformState!!.width, 1e-3f)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun largeLayerPinchContinuesOnceTheLiftLands() {
        val (c, _) = setup(1500, 1500, Rect(600, 600, 900, 900), zoom = 0.5f)
        c.selectTool(ToolId.TRANSFORM)
        val tool = tool(c)
        assertTrue(startAt(c, 750f, 750f, half = 40f))
        c.twoFingerGesture(Vec2.ZERO, 2f, 0f)
        assertTrue(pumpUntil { tool.transformState != null })
        assertEquals(600f, tool.transformState!!.width, 1e-3f)
        c.twoFingerGesture(Vec2.ZERO, 0.5f, 0f)
        assertEquals(150f, tool.transformState!!.width, 1e-3f)
        c.twoFingerEnd(cancelled = true)
        assertEquals("cancelled: back to the lifted content", 300f, tool.transformState!!.width, 1e-3f)
        assertFalse(tool.hasUserChanges)
    }

    @Test
    fun largeLayerPinchAtTheCanvasEdgeWithAFingerOutside() {
        // Content along the left edge of a large layer; one finger lands off the canvas.
        val (c, _) = setup(1500, 1500, Rect(0, 600, 300, 900), zoom = 0.5f)
        c.selectTool(ToolId.TRANSFORM)
        val tool = tool(c)
        assertFalse("off the canvas and beside the content: the view zooms", startAt(c, 10f, 200f, half = 60f))
        assertFalse(tool.isPreparing)
        assertTrue("one finger on the content, the other outside the canvas", startAt(c, 10f, 750f, half = 60f))
        c.twoFingerGesture(Vec2.ZERO, 0.5f, 0f)
        c.twoFingerEnd(cancelled = false)
        assertTrue(pumpUntil { tool.transformState != null && !tool.isPreparing })
        assertEquals(150f, tool.transformState!!.width, 1e-3f)
        tool.discard()
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
