package com.brushwork.paint.tools.remove

import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * Content-aware fill in the editor (Robolectric, real Skia): the selection action (new layer /
 * current layer, one undo step, Refill, all layers, Stop) and the Remove tool (stroke, cancel,
 * selection clip, undo).
 */
@RunWith(RobolectricTestRunner::class)
class ContentAwareFillJobRobolectricTest {

    private val w = 160
    private val h = 120
    private val magenta = 0xFFFF00FF.toInt()
    /** The "object" to remove, painted over vertical stripes. */
    private val objectRect = Rect(65, 45, 95, 75)

    private fun stripe(x: Int) = if (x % 8 < 4) 0xFF000000.toInt() else -1

    private fun photo(): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        if (objectRect.contains(x, y)) magenta else stripe(x)
    }

    private fun document(extraTopLayer: Boolean = false): Document {
        val doc = Document("caf", "caf", w, h)
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        bmp.setPixels(photo(), 0, w, 0, 0, w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", bmp)
        if (extraTopLayer) doc.layers += Layer(doc.newLayerId(), "Top", BitmapUtils.createLayerBitmap(w, h))
        doc.activeLayerIndex = doc.layers.lastIndex
        return doc
    }

    private lateinit var c: EditorController

    @Before
    fun setUp() {
        Smoke.scopeErrors.clear()
    }

    private fun controller(doc: Document = document()): EditorController =
        Smoke.controller(ApplicationProvider.getApplicationContext<Context>(), doc).also { c = it }

    private fun select(r: Rect) {
        c.setSelection(Selection.fromPath(Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }, w, h, antiAlias = false))
    }

    private fun waitIdle() {
        assertTrue("fill finished", Smoke.pumpUntil(30_000) { c.busyMessage == null && !ContentAwareFillJob.isRunning(c) })
        Smoke.pump(100)
    }

    private fun pixels(layer: Layer): IntArray = IntArray(w * h).also { layer.bitmap.getPixels(it, 0, w, 0, 0, w, h) }

    private fun near(a: Int, b: Int, tol: Int = 40): Boolean {
        for (sh in intArrayOf(0, 8, 16, 24)) if (abs(((a ushr sh) and 0xFF) - ((b ushr sh) and 0xFF)) > tol) return false
        return true
    }

    /** Share of the object's pixels in [px] that continue the stripes. */
    private fun stripeShare(px: IntArray): Double {
        var ok = 0; var n = 0
        for (y in objectRect.top until objectRect.bottom) for (x in objectRect.left until objectRect.right) {
            n++
            if (near(px[y * w + x], stripe(x))) ok++
        }
        return ok.toDouble() / n
    }

    // ------------------------------------------------------------------ selection fill

    @Test
    fun fillToANewLayerIsOneUndoStepAndKeepsTheSelection() {
        controller(document())
        val photo = c.activeLayer
        select(Rect(63, 43, 97, 77))
        val sel = c.selection
        val before = pixels(photo)
        val steps = c.undoManager.undoCount
        assertFalse(ContentAwareFillJob.canRefill(c))

        assertTrue(ContentAwareFillJob.fillSelection(c, CafOptions(output = CafOutput.NEW_LAYER)))
        waitIdle()
        Smoke.assertQuiet(c, "after the fill")

        assertEquals(2, c.doc.layers.size)
        val filled = c.doc.layers[1]
        assertEquals(ContentAwareFillJob.FILL_LABEL, filled.name)
        assertSame("the new layer is active", filled, c.activeLayer)
        assertEquals("one undo step", steps + 1, c.undoManager.undoCount)
        assertEquals(ContentAwareFillJob.FILL_LABEL, c.undoManager.undoLabel)
        assertSame("the selection is kept", sel, c.selection)
        assertTrue("the source layer is untouched", before.contentEquals(pixels(photo)))
        val out = pixels(filled)
        assertTrue("stripes continue on the new layer: ${stripeShare(out)}", stripeShare(out) >= 0.9)
        assertEquals("nothing outside the fill", 0, out[5 * w + 5])
        assertTrue("no magenta", out.none { near(it, magenta, 60) })
        assertTrue(ContentAwareFillJob.canRefill(c))

        c.undo()
        assertEquals(1, c.doc.layers.size)
        c.redo()
        assertEquals(2, c.doc.layers.size)
        assertTrue(Smoke.scopeErrors.isEmpty())
    }

    @Test
    fun fillOfTheCurrentLayerChangesOnlyTheSelectedArea() {
        controller(document())
        val photo = c.activeLayer
        select(Rect(63, 43, 97, 77))
        val before = pixels(photo)
        val steps = c.undoManager.undoCount
        assertTrue(ContentAwareFillJob.fillSelection(c, CafOptions(output = CafOutput.CURRENT_LAYER, expand = 2)))
        waitIdle()
        Smoke.assertQuiet(c, "after the fill")
        assertEquals(1, c.doc.layers.size)
        assertEquals(steps + 1, c.undoManager.undoCount)
        val after = pixels(photo)
        val allowed = Rect(63 - 4, 43 - 4, 97 + 4, 77 + 4)
        for (y in 0 until h) for (x in 0 until w) {
            if (!allowed.contains(x, y)) assertEquals("pixel $x,$y outside the selection changed", before[y * w + x], after[y * w + x])
        }
        assertTrue("stripes continue: ${stripeShare(after)}", stripeShare(after) >= 0.9)
        c.undo()
        assertTrue("undo restores every pixel", before.contentEquals(pixels(photo)))
    }

    @Test
    fun refillReplacesTheLastFill() {
        controller(document())
        select(Rect(63, 43, 97, 77))
        val steps = c.undoManager.undoCount
        assertTrue(ContentAwareFillJob.fillSelection(c, CafOptions(output = CafOutput.NEW_LAYER)))
        waitIdle()
        assertEquals(2, c.doc.layers.size)
        assertTrue(ContentAwareFillJob.canRefill(c))

        assertTrue(ContentAwareFillJob.fillSelection(c, CafOptions(output = CafOutput.NEW_LAYER), refill = true))
        waitIdle()
        Smoke.assertQuiet(c, "after the refill")
        assertEquals("the refill replaced the first fill", 2, c.doc.layers.size)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("the fill sits above the photo", "Photo", c.doc.layers[0].name)
        assertTrue(ContentAwareFillJob.canRefill(c))
        // Any other edit makes the fill history: Refill then adds a new fill instead.
        c.deselect()
        assertFalse(ContentAwareFillJob.canRefill(c))
    }

    @Test
    fun samplingAllLayersFillsTheActiveLayer() {
        controller(document(extraTopLayer = true))
        val top = c.activeLayer
        val photo = c.doc.layers[0]
        val photoBefore = pixels(photo)
        select(Rect(63, 43, 97, 77))
        assertTrue(ContentAwareFillJob.fillSelection(c, CafOptions(output = CafOutput.CURRENT_LAYER, source = CafSource.ALL_LAYERS)))
        waitIdle()
        val out = pixels(top)
        assertTrue("the empty top layer got the stripes: ${stripeShare(out)}", stripeShare(out) >= 0.9)
        assertTrue(photoBefore.contentEquals(pixels(photo)))
        // Sampling only the (empty) top layer gives an empty fill: nothing changes, no step.
        c.undo()
        val steps = c.undoManager.undoCount
        c.message = null
        assertTrue(ContentAwareFillJob.fillSelection(c, CafOptions(output = CafOutput.CURRENT_LAYER, source = CafSource.LAYER)))
        waitIdle()
        assertTrue(pixels(top).all { it == 0 })
        assertEquals(steps, c.undoManager.undoCount)
        assertNotNull("says nothing changed", c.message)
    }

    @Test
    fun stopLeavesNoTrace() {
        controller(document())
        val photo = c.activeLayer
        select(Rect(63, 43, 97, 77))
        val before = pixels(photo)
        val steps = c.undoManager.undoCount
        assertTrue(ContentAwareFillJob.fillSelection(c, CafOptions(output = CafOutput.NEW_LAYER)))
        val stop = c.busyCancel
        assertNotNull("the busy overlay offers Stop", stop)
        stop!!.invoke()
        waitIdle()
        Smoke.assertQuiet(c, "after Stop")
        assertEquals(1, c.doc.layers.size)
        assertEquals(steps, c.undoManager.undoCount)
        assertTrue(before.contentEquals(pixels(photo)))
    }

    @Test
    fun lockedLayerOrNoSelectionIsRefused() {
        controller(document())
        assertFalse("no selection", ContentAwareFillJob.fillSelection(c, CafOptions()))
        assertNotNull(c.message)
        c.message = null
        select(Rect(63, 43, 97, 77))
        c.activeLayer.locked = true
        assertFalse(ContentAwareFillJob.fillSelection(c, CafOptions(output = CafOutput.CURRENT_LAYER)))
        assertNotNull(c.message)
        assertNull(c.busyMessage)
    }

    // ------------------------------------------------------------------ Remove tool

    private fun removeTool(size: Float): RemoveTool {
        c.selectTool(ToolId.REMOVE)
        val tool = c.currentTool as RemoveTool
        tool.settings = tool.settings.copy(size = size)
        return tool
    }

    private fun stroke(vararg pts: Pair<Float, Float>) {
        c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (p in pts.drop(1)) c.pointerMove(ToolPoint(p.first, p.second))
        c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    @Test
    fun removeStrokeFillsOnlyThePaintedArea() {
        controller(document())
        val photo = c.activeLayer
        val before = pixels(photo)
        val tool = removeTool(40f)
        val steps = c.undoManager.undoCount
        stroke(70f to 60f, 76f to 60f, 82f to 61f, 90f to 60f)
        assertTrue("filling starts at once", tool.busy || c.busyMessage != null)
        waitIdle()
        assertFalse(tool.busy)
        Smoke.assertQuiet(c, "after Remove")
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ContentAwareFillJob.REMOVE_LABEL, c.undoManager.undoLabel)
        val after = pixels(photo)
        // Brush radius 20 + 3 px expansion + anti-aliasing.
        val allowed = Rect(70 - 25, 60 - 25, 90 + 25, 61 + 25)
        for (y in 0 until h) for (x in 0 until w) {
            if (!allowed.contains(x, y)) assertEquals("pixel $x,$y far from the stroke changed", before[y * w + x], after[y * w + x])
        }
        assertTrue("the object is gone", after.none { near(it, magenta, 60) })
        assertTrue("stripes continue: ${stripeShare(after)}", stripeShare(after) >= 0.9)
        c.undo()
        assertTrue("undo restores the layer", before.contentEquals(pixels(photo)))
    }

    @Test
    fun secondFingerCancelsTheStrokeWithoutATrace() {
        controller(document())
        val photo = c.activeLayer
        val before = pixels(photo)
        val tool = removeTool(30f)
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(70f, 60f))
        c.pointerMove(ToolPoint(80f, 60f))
        c.pointerCancel()
        Smoke.pump(200)
        assertFalse(tool.busy)
        assertNull(c.busyMessage)
        assertEquals(steps, c.undoManager.undoCount)
        assertTrue(before.contentEquals(pixels(photo)))
        Smoke.assertQuiet(c, "after the cancelled stroke")
    }

    @Test
    fun removeIsLimitedToTheSelection() {
        controller(document())
        val photo = c.activeLayer
        val before = pixels(photo)
        select(Rect(0, 0, 80, h))
        removeTool(44f)
        stroke(66f to 60f, 94f to 60f)
        waitIdle()
        val after = pixels(photo)
        for (y in 0 until h) for (x in 80 until w) assertEquals("pixel $x,$y outside the selection", before[y * w + x], after[y * w + x])
        for (y in objectRect.top until objectRect.bottom) for (x in objectRect.left until 78) {
            assertFalse("magenta left at $x,$y", near(after[y * w + x], magenta, 60))
        }
        // A stroke entirely outside the selection does nothing (and says so).
        val steps = c.undoManager.undoCount
        c.message = null
        stroke(120f to 20f, 140f to 20f)
        Smoke.pump(100)
        assertNull(c.busyMessage)
        assertEquals(steps, c.undoManager.undoCount)
        assertNotNull(c.message)
    }

    @Test
    fun strokeMaskIsARoundBrush() {
        val path = Path().apply { moveTo(50f, 50f); lineTo(50.01f, 50f) }
        val mask = RemoveTool.strokeMask(path, 20f, 200, 100, null)!!
        // A disc of radius 10 around (50, 50).
        val r = mask.rect
        fun at(x: Int, y: Int) = mask.alpha[(y - r.top) * r.width + (x - r.left)].toInt() and 0xFF
        assertEquals(255, at(50, 50))
        assertEquals(255, at(56, 50))
        assertEquals(0, at(50 + 13, 50))
        assertEquals(0, at(57, 57))
        var n = 0
        for (b in mask.alpha) if ((b.toInt() and 0xFF) >= 128) n++
        assertTrue("disc area $n", abs(n - 314) < 30)
        // Off the canvas: nothing.
        assertNull(RemoveTool.strokeMask(Path().apply { moveTo(-100f, -100f); lineTo(-90f, -100f) }, 10f, 200, 100, null))
    }
}
