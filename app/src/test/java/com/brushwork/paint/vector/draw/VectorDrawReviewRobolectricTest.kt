package com.brushwork.paint.vector.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 A3 review: eraser and bucket updates that are still on their way (background renders)
 * are applied one after the other, each to the content as it is then (a later one never brings
 * back what an earlier one removed), with no stale dimmed preview left behind; the eraser
 * explains itself once; bucket colors follow the document's color mode.
 */
@RunWith(RobolectricTestRunner::class)
class VectorDrawReviewRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 300
    private val h = 200

    private fun setup(mode: ColorMode = ColorMode.RGB): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.colorMode = mode
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[0]

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 31): VStroke {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = BrushLibrary.defaultBrush.copy(size = 8f), color = 0xFF000000.toInt(), seed = 5L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    /** A horizontal line y = 100 (id 1) crossed by verticals at x = 100 (id 2) and x = 200 (id 3). */
    private fun lines(c: EditorController) {
        c.vectors.addObjects(c.vec, listOf(stroke(0f, 100f, 300f, 100f), stroke(100f, 20f, 100f, 180f), stroke(200f, 20f, 200f, 180f)), "Add")
    }

    private fun EditorController.swipe(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    /** Holds every update of the eraser and the bucket until [release]d (a background render). */
    private class HeldUpdates(private val c: EditorController) {
        val held = ArrayList<() -> Unit>()

        init {
            VectorDrawState.of(c).update = { cc, layer, after, label, done -> held += { cc.vectors.update(layer, after, label, onDone = done) } }
        }

        fun release() = held.removeAt(0).invoke()
    }

    private fun ids(c: EditorController) = c.vec.vector!!.objects.map { it.id }

    @Test
    fun eraserUpdatesOnTheirWayAreAppliedInOrderWithoutStalePreviews() {
        val c = setup()
        lines(c)
        val steps = c.undoManager.undoCount
        val updates = HeldUpdates(c)
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        // First gesture: the vertical at x = 100. Its update is on its way: the layer is as it
        // was, and what will go stays dimmed.
        c.swipe(100f to 30f, 100f to 60f)
        assertEquals(listOf(1L, 2L, 3L), ids(c))
        assertEquals(1, updates.held.size)
        val first = c.renderOverride
        assertNotNull(first)
        // Second gesture meanwhile: the vertical at x = 200. It waits for the first.
        c.swipe(200f to 30f, 200f to 60f)
        assertEquals(1, updates.held.size)
        assertTrue(c.renderOverride !== first)
        // The first lands; the second is computed from what the layer holds then.
        updates.release()
        assertEquals(listOf(1L, 3L), ids(c))
        assertEquals(1, updates.held.size)
        updates.release()
        assertEquals(listOf(1L), ids(c))
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        // No dimmed preview is left (the first one never comes back).
        assertNull(c.renderOverride)
        c.undo()
        assertEquals(listOf(1L, 3L), ids(c))
        c.undo()
        assertEquals(listOf(1L, 2L, 3L), ids(c))
    }

    @Test
    fun aGestureEndingAfterTheEarlierUpdateLandedLeavesNoPreviewEither() {
        val c = setup()
        lines(c)
        val updates = HeldUpdates(c)
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        c.swipe(100f to 30f, 100f to 60f)
        // A second gesture starts while the first update is on its way...
        c.pointerDown(ToolPoint(200f, 30f))
        c.pointerMove(ToolPoint(200f, 60f))
        // ...which lands now; then the second gesture ends.
        updates.release()
        assertEquals(listOf(1L, 3L), ids(c))
        c.pointerUp(ToolPoint(200f, 60f))
        updates.release()
        assertEquals(listOf(1L), ids(c))
        assertNull(c.renderOverride)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
    }

    @Test
    fun aBucketTapWaitsForTheEraserAndRecolorsWhatIsUnderTheFingerThen() {
        val c = setup()
        lines(c)
        val updates = HeldUpdates(c)
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        // Cuts the horizontal line at x = 150 (its first piece keeps id 1, the second is id 4).
        c.swipe(150f to 70f, 150f to 130f)
        assertEquals(1, updates.held.size)
        // The bucket on the horizontal line's right part meanwhile.
        c.selectTool(ToolId.FILL)
        c.color = 0xFFE02020.toInt()
        c.tap(250f, 101f)
        updates.release()
        assertEquals(listOf(1L, 4L, 2L, 3L), ids(c))
        // The recolor runs on the cut line: the piece under the finger (the right one, a new
        // object) gets the color, the other piece keeps its own, the erased part stays erased.
        assertEquals(1, updates.held.size)
        updates.release()
        val after = c.vec.vector!!
        assertEquals(listOf(1L, 4L, 2L, 3L), after.objects.map { it.id })
        assertEquals(0xFFE02020.toInt(), (after.byId(4) as VStroke).color)
        assertTrue((after.byId(4) as VStroke).points.x.all { it > 150f })
        assertEquals(0xFF000000.toInt(), (after.byId(1) as VStroke).color)
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
        // A tap on what an erase on its way removes recolors nothing.
        val steps = c.undoManager.undoCount
        c.selectTool(ToolId.ERASER)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.swipe(200f to 30f, 200f to 60f)
        c.selectTool(ToolId.FILL)
        c.color = 0xFF2040E0.toInt()
        c.tap(200f, 150f)
        updates.release()
        assertEquals(listOf(1L, 4L, 2L), ids(c))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue(updates.held.isEmpty())
    }

    @Test
    fun theEraserExplainsItselfOnceOnVectorLayers() {
        val c = setup()
        lines(c)
        c.selectTool(ToolId.ERASER)
        c.pointerDown(ToolPoint(20f, 20f))
        assertEquals(VectorStrokeCapture.eraserHint(VectorEraseMode.OBJECT), c.message)
        assertTrue(c.message!!.contains("Object"))
        c.pointerUp(ToolPoint(20f, 20f))
        c.message = null
        c.swipe(20f to 20f, 40f to 30f)
        assertNull(c.message)
    }

    @Test
    fun bucketColorsFollowTheDocumentsColorMode() {
        val c = setup(ColorMode.GRAYSCALE)
        val box = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(40f, 40f, true), VAnchor(140f, 40f, true), VAnchor(140f, 140f, true), VAnchor(40f, 140f, true)), closed = true)),
            stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
        )
        c.vectors.addObjects(c.vec, listOf(box), "Add")
        c.selectTool(ToolId.FILL)
        c.color = 0xFFE02020.toInt()
        c.tap(90f, 90f)
        val fill = (c.vec.vector!!.objects.single() as VPath).fill as VPaint.Solid
        assertEquals(Color.red(fill.color), Color.green(fill.color))
        assertEquals(Color.green(fill.color), Color.blue(fill.color))
        assertEquals(0xFF, Color.alpha(fill.color))
        // The cache shows exactly that color there.
        assertEquals(fill.color, c.vec.bitmap.getPixel(90, 90))
    }
}
