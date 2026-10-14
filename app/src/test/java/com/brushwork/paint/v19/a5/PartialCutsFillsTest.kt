package com.brushwork.paint.v19.a5

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.VectorDrawState
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.draw.VectorEraserRecorder
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * v1.9 (R11 b and c, A5 step 5): the partial eraser cuts shapes and filled paths by the region it
 * swept (Minus Front), strokes and open lines as in v1.8, all in ONE "Erase" step: the pieces are
 * editable paths in the object's place and style. The cut runs at the lift in the background
 * (the preview stays meanwhile, the busy overlay shows after 400 ms); anything that records a step
 * lands it first; when the layer changed while it ran, the erase runs again on what it holds then.
 * The preview takes away what the cut will (except along the cut edges, which the result strokes).
 * Step 9: one toast says what else happened (copies baked; objects too complex to cut, kept).
 */
@RunWith(RobolectricTestRunner::class)
class PartialCutsFillsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 300
    private val h = 220

    private fun setup(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 0
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

    private val pen = BrushLibrary.defaultBrush.copy(size = 8f, taperStart = 20f, taperEnd = 20f)

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 31): VStroke {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = pen, color = 0xFF203040.toInt(), seed = 5L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private val blue = 0xFF3070C0.toInt()

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(blue), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
    )

    private fun eraser(c: EditorController) {
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
    }

    private fun EditorController.swipe(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The region cut runs where the test says: each job waits in [queued] until the test runs it. */
    private fun held(c: EditorController): ArrayList<Runnable> {
        val queued = ArrayList<Runnable>()
        VectorDrawState.of(c).cutDispatcher = Executor { queued += it }.asCoroutineDispatcher()
        return queued
    }

    /** The pieces of a box cut by a vertical swipe at x = 100 (radius 6): their anchors keep clear of it. */
    private fun assertCutAt100(pieces: List<VPath>) {
        assertEquals(2, pieces.size)
        for (p in pieces) {
            assertEquals(VPaint.Solid(blue), p.fill)
            assertEquals(3f, p.stroke!!.width, 0f)
            assertFalse(ObjectCopies.hasCopies(p))
        }
        val (left, right) = pieces.sortedBy { p -> p.subpaths.minOf { s -> s.anchors.minOf { it.x } } }
        assertTrue(left.subpaths.flatMap { it.anchors }.all { it.x <= 94.05f })
        assertTrue(right.subpaths.flatMap { it.anchors }.all { it.x >= 105.95f })
    }

    @Test
    fun aFilledPathIsCutIntoEditablePiecesAtTheLiftInOneStep() {
        val c = setup()
        VectorDrawState.of(c).cutDispatcher = Dispatchers.Unconfined
        c.vectors.addObjects(c.vec, listOf(box(40f, 60f, 160f, 140f), stroke(20f, 180f, 280f, 180f)), "Add")
        eraser(c)
        val before = c.vec.vector!!
        val drawn = pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        // Down across the box and the stroke under it.
        c.swipe(100f to 40f, 100f to 120f, 100f to 200f)
        // The cut lands from the main looper: until then the layer is as it was, under the preview.
        assertSame(before, c.vec.vector)
        assertTrue(c.renderOverride is DoomPreview)
        idle()
        val after = c.vec.vector!!
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(VectorEraserRecorder.ERASE_LABEL, c.undoManager.undoLabel)
        assertNull(c.renderOverride)
        // The box: two filled paths in its place (the left one keeps its id); the stroke: v1.8's cut.
        assertEquals(4, after.objects.size)
        val boxes = after.objects.filterIsInstance<VPath>()
        assertEquals(1L, boxes[0].id)
        assertEquals(listOf(boxes[0].id, boxes[1].id), after.objects.take(2).map { it.id })
        assertCutAt100(boxes)
        val strokes = after.objects.filterIsInstance<VStroke>()
        assertEquals(2, strokes.size)
        assertTrue(strokes[0].points.x.all { it < 100f } && strokes[1].points.x.all { it > 100f })
        // The pixels are the data's; the cut is a gap.
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
        assertEquals(0, c.vec.bitmap.getPixel(100, 100) ushr 24)
        assertEquals(blue, c.vec.bitmap.getPixel(70, 100))
        assertEquals(blue, c.vec.bitmap.getPixel(130, 100))
        // ONE undo brings the box and the stroke back.
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(drawn, pixels(c.vec.bitmap))
        c.redo()
        assertSame(after, c.vec.vector)
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
    }

    @Test
    fun thePreviewTakesAwayWhatTheCutWillExceptAlongItsEdges() {
        val c = setup()
        VectorDrawState.of(c).cutDispatcher = Dispatchers.Unconfined
        c.vectors.addObjects(c.vec, listOf(box(40f, 60f, 160f, 140f)), "Add")
        eraser(c)
        val drawn = pixels(c.vec.bitmap)
        c.pointerDown(ToolPoint(100f, 40f))
        c.pointerMove(ToolPoint(100f, 120f))
        c.pointerMove(ToolPoint(100f, 160f))
        val ov = c.renderOverride as DoomPreview
        val b = BitmapUtils.createLayerBitmap(w, h)
        assertTrue(ov.drawContent(Canvas(b)))
        val preview = pixels(b)
        // Over the box the eraser's path is gone; the rest is as drawn.
        assertEquals(0, preview[100 * w + 100] ushr 24)
        assertEquals(drawn[100 * w + 70], preview[100 * w + 70])
        c.pointerUp(ToolPoint(100f, 160f))
        idle()
        val result = pixels(c.vec.bitmap)
        // They differ only along the cut edges (x = 94 and 106), which the pieces stroke.
        var differ = 0
        for (i in preview.indices) if (preview[i] != result[i]) {
            differ++
            assertTrue("differs at ${i % w},${i / w}", abs(i % w - 100) <= 9)
        }
        assertTrue("$differ", differ > 0)
    }

    @Test
    fun theCutRunsAgainOnWhatTheLayerHoldsWhenItChangedMeanwhile() {
        val c = setup()
        val queued = held(c)
        c.vectors.addObjects(c.vec, listOf(box(40f, 60f, 160f, 140f)), "Add")
        eraser(c)
        val steps = c.undoManager.undoCount
        c.swipe(100f to 40f, 100f to 160f)
        assertEquals(1, queued.size)
        // The layer changes while the cut runs.
        val changed = c.vec.vector!!.plus(listOf(box(200f, 60f, 260f, 140f))).first
        c.vec.vector = changed
        queued.removeAt(0).run()
        idle()
        // Not applied over the new content: the erase runs again on it.
        assertSame(changed, c.vec.vector)
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        idle()
        val after = c.vec.vector!!
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(3, after.objects.size)
        assertSame(changed.byId(2), after.byId(2))
        assertCutAt100(after.objects.filter { it.id != 2L }.map { it as VPath })
        assertNull(c.renderOverride)

        // A layer that keeps changing: after two runs again, the cut runs on this thread.
        c.swipe(230f to 40f, 230f to 160f)
        assertEquals(1, queued.size)
        c.vec.vector = c.vec.vector!!.plus(listOf(stroke(20f, 200f, 60f, 200f))).first
        queued.removeAt(0).run()
        idle()
        assertEquals(1, queued.size)
        c.vec.vector = c.vec.vector!!.plus(listOf(stroke(20f, 210f, 60f, 210f))).first
        queued.removeAt(0).run()
        idle()
        assertTrue(queued.isEmpty())
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals("the second box in two pieces, beside the first one's and the two strokes", 6, c.vec.vector!!.objects.size)
        assertNull(c.renderOverride)
    }

    /**
     * A cut that fails to land from the background (here: the layer's update throws) leaves the
     * layer as it was and never holds the erases after it on that layer (review fix: they waited
     * for VectorDrawState's stuck guard, 30 s).
     */
    @Test
    fun aCutThatFailsToLandHoldsNoLaterErase() {
        val c = setup()
        val queued = held(c)
        c.vectors.addObjects(c.vec, listOf(box(40f, 60f, 160f, 140f), box(200f, 60f, 260f, 140f)), "Add")
        eraser(c)
        val state = VectorDrawState.of(c)
        val update = state.update
        var fail = true
        state.update = { ctl, layer, after, label, onDone ->
            if (fail) {
                fail = false
                throw IllegalStateException("the update failed")
            }
            update(ctl, layer, after, label, onDone)
        }
        val before = c.vec.vector
        val steps = c.undoManager.undoCount
        c.swipe(100f to 40f, 100f to 160f)
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        // (The failure goes to the editor scope's uncaught handler.)
        runCatching { idle() }
        assertFalse("the update was tried", fail)
        assertSame(before, c.vec.vector)
        assertEquals(steps, c.undoManager.undoCount)
        assertNull(c.renderOverride)
        // The next erase on the layer starts its cut at once.
        c.swipe(230f to 40f, 230f to 160f)
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        idle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        val after = c.vec.vector!!
        assertEquals("the first box, and the second one in two pieces", 3, after.objects.size)
        assertSame(before!!.objects[0], after.objects[0])
        assertNull(c.renderOverride)
    }

    @Test
    fun anythingThatRecordsAStepLandsTheCutFirst() {
        val c = setup()
        // (The real background: Dispatchers.Default.)
        c.vectors.addObjects(c.vec, listOf(box(40f, 60f, 160f, 140f)), "Add")
        eraser(c)
        val before = c.vec.vector!!
        val drawn = pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        c.swipe(100f to 40f, 100f to 160f)
        // Undo right away: the cut lands as its step first, and that step is undone.
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(drawn, pixels(c.vec.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        assertNull(c.busyMessage)
        c.redo()
        val after = c.vec.vector!!
        assertCutAt100(after.objects.map { it as VPath })
        // The background's own landing comes later and changes nothing.
        idle()
        assertSame(after, c.vec.vector)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(c.renderOverride)
    }

    @Test
    fun aSlowCutShowsTheBusyOverlayAfter400Ms() {
        val c = setup()
        val queued = held(c)
        c.vectors.addObjects(c.vec, listOf(box(40f, 60f, 160f, 140f)), "Add")
        eraser(c)
        c.swipe(100f to 40f, 100f to 160f)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(VectorEraserRecorder.CUT_BUSY_AFTER_MS - 50))
        assertNull(c.busyMessage)
        looper.idleFor(Duration.ofMillis(100))
        assertEquals(VectorEraserRecorder.ERASE_LABEL, c.busyMessage)
        queued.removeAt(0).run()
        idle()
        assertNull(c.busyMessage)
        assertCutAt100(c.vec.vector!!.objects.map { it as VPath })
        // A quick one never shows it.
        c.swipe(70f to 40f, 70f to 160f)
        queued.removeAt(0).run()
        looper.idleFor(Duration.ofMillis(VectorEraserRecorder.CUT_BUSY_AFTER_MS + 100))
        assertNull(c.busyMessage)
        assertEquals(3, c.vec.vector!!.objects.size)
    }

    @Test
    fun aCancelledGestureLeavesNoTraceAndStartsNoCut() {
        val c = setup()
        val queued = held(c)
        c.vectors.addObjects(c.vec, listOf(box(40f, 60f, 160f, 140f)), "Add")
        eraser(c)
        val before = c.vec.vector!!
        val drawn = pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(100f, 40f))
        c.pointerMove(ToolPoint(100f, 160f))
        assertTrue(c.renderOverride is DoomPreview)
        c.pointerCancel()
        idle()
        assertNull(c.renderOverride)
        assertTrue(queued.isEmpty())
        assertSame(before, c.vec.vector)
        assertArrayEquals(drawn, pixels(c.vec.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun aLegacyShapeLayerIsConvertedAndCutInOneStep() {
        val c = setup()
        VectorDrawState.of(c).cutDispatcher = Dispatchers.Unconfined
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 120f, h = 80f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, fillColor = blue)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, w, h))!!
        c.selectLayer(shape)
        val data = shape.dataSnapshot()
        val cache = pixels(shape.bitmap)
        val steps = c.undoManager.undoCount
        eraser(c)
        c.swipe(100f to 40f, 100f to 160f)
        idle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(VectorEraserRecorder.ERASE_LABEL, c.undoManager.undoLabel)
        assertNull(shape.shapeData)
        assertTrue(shape.isVectorLayer)
        val pieces = shape.vector!!.objects
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it is VPath && it.fill == VPaint.Solid(blue) })
        assertArrayEquals(render(shape.vector!!), pixels(shape.bitmap))
        assertNull(c.renderOverride)
        c.undo()
        assertEquals(data, shape.dataSnapshot())
        assertTrue(shape.isShapeLayer)
        assertArrayEquals(cache, pixels(shape.bitmap))
    }

    /** A filled comb: a spine 6 px high at y 100 and [teeth] teeth 0.6 px wide, 1 px apart, down to y 130. */
    private fun comb(teeth: Int): VPath {
        val anchors = ArrayList<VAnchor>()
        val right = 5f + teeth
        anchors += VAnchor(5f, 100f, true)
        anchors += VAnchor(right, 100f, true)
        for (k in teeth - 1 downTo 0) {
            val x = 5f + k
            anchors += VAnchor(x + 0.6f, 106f, true)
            anchors += VAnchor(x + 0.6f, 130f, true)
            anchors += VAnchor(x, 130f, true)
            anchors += VAnchor(x, 106f, true)
        }
        return VPath(0, subpaths = listOf(VSubpath(anchors, closed = true)), fill = VPaint.Solid(blue))
    }

    @Test
    fun theCutSaysWhatElseHappenedInOneToast() {
        val c = setup()
        VectorDrawState.of(c).cutDispatcher = Dispatchers.Unconfined
        // A box with a live copy 150 px to the right, cut through the source: every copy becomes a piece.
        val shift = floatArrayOf(1f, 0f, 150f, 0f, 1f, 0f, 0f, 0f, 1f)
        c.vectors.addObjects(c.vec, listOf(box(20f, 30f, 100f, 80f).copy(copies = CopyMaps.of(listOf(shift)))), "Add")
        eraser(c)
        var steps = c.undoManager.undoCount
        c.swipe(60f to 10f, 60f to 100f)
        idle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(3, c.vec.vector!!.objects.size)
        assertTrue(c.vec.vector!!.objects.none { ObjectCopies.hasCopies(it) })
        assertEquals(EraseLabels19.COPIES_BAKED, c.message)

        // A comb of 270 teeth whose spine is erased would fall into more than 256 pieces: it stays,
        // no step, and the toast says so.
        c.message = null
        c.vectors.addObjects(c.vec, listOf(comb(270)), "Add")
        val before = c.vec.vector
        steps = c.undoManager.undoCount
        c.swipe(-10f to 103f, 150f to 103f, 310f to 103f)
        idle()
        assertEquals(steps, c.undoManager.undoCount)
        assertSame(before, c.vec.vector)
        assertEquals(EraseLabels19.TOO_COMPLEX, c.message)

        // With a stroke cut in the same gesture (down at x 290): one step, and the same toast.
        c.message = null
        c.vectors.addObjects(c.vec, listOf(stroke(290f, 60f, 290f, 200f)), "Add")
        steps = c.undoManager.undoCount
        c.swipe(-10f to 103f, 150f to 103f, 310f to 103f)
        idle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue("the comb stays", c.vec.vector!!.objects.any { it is VPath && it.subpaths[0].anchors.size == 2 + 4 * 270 })
        assertEquals(EraseLabels19.TOO_COMPLEX, c.message)
    }
}
