package com.brushwork.paint.v19.cross

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.EraseCut
import com.brushwork.paint.vector.draw.VectorDrawState
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.draw.VectorEraserRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.Executor

/**
 * v1.9 Gate 2 item M3 (A7b x A5, design 3.2 A7b, R9): the partial eraser's region cut still
 * running when another step lands it (`EraseLanding`'s `Cutting.flush`) is waited for with the
 * editor's bounded wait (`EditorController.awaitDeferredJob`; it waited for good before, the last
 * `runBlocking` on the main thread). A cut that is not done by the limit (here a fake slow cut:
 * its job waits on a held dispatcher) is cancelled, as the lead ruled: the layer keeps its data
 * and pixels, no document step is recorded, its work is cancelled (it never lands later) and
 * the editor says [StabilityLabels19.TOO_SLOW]. That holds when a step on another layer lands
 * it, when an Undo lands it (that Undo is the cancellation: the step before stays), and under the
 * cut's own busy overlay. Under another operation's overlay (an export) it must land: it is cut
 * again on the spot, as one step. An erase that is not slow still lands.
 */
@RunWith(RobolectricTestRunner::class)
class CrossMemoryEraseCutTimeoutTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 300
    private val h = 220
    private val blue = 0xFF3070C0.toInt()
    private val tooSlow = StabilityLabels19.TOO_SLOW.format(VectorEraserRecorder.ERASE_LABEL)

    /** "Vector 1" (a box) under a raster "Paint" layer; the vector layer is active. */
    private fun setup(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.layers += Layer(doc.newLayerId(), "Paint", BitmapUtils.createLayerBitmap(w, h))
        doc.activeLayerIndex = 0
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[0]
    private val EditorController.paint: Layer get() = doc.layers[1]

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun box(l: Float, t: Float, r: Float, b: Float, id: Long = 0) = VPath(
        id, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(blue), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
    )

    private fun EditorController.swipe(x: Float) {
        pointerDown(ToolPoint(x, 40f))
        pointerMove(ToolPoint(x, 100f))
        pointerUp(ToolPoint(x, 160f))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A raster step on "Paint" (it lands the steps still on their way first). */
    private fun EditorController.paintStep(k: Int) {
        val rec = beginEdit(paint)
        val r = Rect(10 + 20 * k, 180, 25 + 20 * k, 210)
        rec.touch(r)
        Canvas(paint.bitmap).drawRect(r, Paint().apply { color = 0xFF208040.toInt() })
        assertTrue(commitEdit(rec, "Paint ${k + 1}"))
    }

    @Test
    fun aCutThatTimesOutIsCancelledAndLeavesTheLayerAsItWas() {
        val c = setup()
        // The fake slow cut: every cut job waits in [queued] until the test runs it.
        val queued = ArrayList<Runnable>()
        VectorDrawState.of(c).cutDispatcher = Executor { queued += it }.asCoroutineDispatcher()
        c.vectors.addObjects(c.vec, listOf(box(40f, 60f, 160f, 140f)), "Add")
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        assertEquals("1.5 s in the app", 1_500L, c.deferredWaitLimitMs)
        c.deferredWaitLimitMs = 60
        val before = c.vec.vector!!
        val drawn = pixels(c.vec.bitmap)

        /** The layer as before the erase, no step, the cut's work cancelled, the editor said so. */
        fun assertCancelled(where: String, steps: Int, label: String, timeouts: Int) {
            assertSame("$where: the layer's data", before, c.vec.vector)
            assertArrayEquals("$where: the layer's pixels", drawn, pixels(c.vec.bitmap))
            assertEquals("$where: no step", steps, c.undoManager.undoCount)
            assertEquals("$where: the step on top", label, c.undoManager.undoLabel)
            assertEquals("$where: the wait hit its limit", timeouts, c.deferredWaitTimeouts)
            assertEquals(where, tooSlow, c.message)
            assertNull("$where: the preview went", c.renderOverride)
            assertFalse("$where: no cut pending", VectorDrawState.hasPendingCut(c))
            // Its work was cancelled: run at last, it never lands.
            assertEquals(1, queued.size)
            queued.removeAt(0).run()
            idle()
            assertSame("$where: never lands later", before, c.vec.vector)
            assertEquals(steps, c.undoManager.undoCount)
            assertNull("$where: no overlay left", c.busyMessage)
            assertNull("$where: Undo and Redo are v1.8's again", c.inToolHistory)
            c.message = null
        }

        // ---- 1. A step on another layer lands the cut first: the wait hits the limit, the cut is cancelled.
        var steps = c.undoManager.undoCount
        c.swipe(100f)
        assertEquals("the cut runs in the background", 1, queued.size)
        assertTrue("the preview shows meanwhile", c.renderOverride is DoomPreview)
        assertTrue(VectorDrawState.hasPendingCut(c))
        assertNotNull("Undo and Redo reach the wait guard", c.inToolHistory)
        c.paintStep(0)
        assertCancelled("a step lands it", steps + 1, "Paint 1", timeouts = 1)

        // ---- 2. An Undo lands it: the cancellation is that Undo, the step before stays.
        steps = c.undoManager.undoCount
        c.swipe(100f)
        assertEquals(1, queued.size)
        c.undo()
        assertCancelled("an Undo lands it", steps, "Paint 1", timeouts = 2)
        c.undo()
        assertEquals("the next Undo undoes the step before", steps - 1, c.undoManager.undoCount)
        c.redo()
        assertEquals(steps, c.undoManager.undoCount)

        // ---- 3. Under its own busy overlay ("Erase", after 400 ms) it is cancelled too.
        c.swipe(100f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VectorEraserRecorder.CUT_BUSY_AFTER_MS + 100))
        assertEquals("its own overlay", VectorEraserRecorder.ERASE_LABEL, c.busyMessage)
        c.redo()
        assertCancelled("a Redo under its own overlay", steps, "Paint 1", timeouts = 3)

        // ---- 4. Under another operation's overlay it must land: cut again on the spot, one step.
        c.swipe(100f)
        assertEquals(1, queued.size)
        // (An export starts while the cut runs: under an overlay, a cut would run at the lift.)
        val export = CompletableDeferred<Unit>()
        c.runBusy("Exporting") { export.await() }
        idle()
        assertEquals("Exporting", c.busyMessage)
        c.paintStep(1)
        assertEquals("the erase landed, then the paint step", steps + 2, c.undoManager.undoCount)
        assertEquals(3, c.deferredWaitTimeouts)
        assertNull("nothing was cancelled", c.message)
        assertEquals(2, c.vec.vector!!.objects.size)
        assertTrue("the box is cut", c.vec.bitmap.getPixel(100, 100) ushr 24 == 0 && c.vec.bitmap.getPixel(70, 100) == blue)
        c.undo()
        assertEquals("ONE erase step", VectorEraserRecorder.ERASE_LABEL, c.undoManager.undoLabel)
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(drawn, pixels(c.vec.bitmap))
        // (Its background job was cancelled; it never lands again.)
        queued.removeAt(0).run()
        idle()
        assertSame(before, c.vec.vector)
        export.complete(Unit)
        idle()
        assertNull(c.busyMessage)

        // ---- 5. A cut that is not slow lands as before.
        steps = c.undoManager.undoCount
        c.swipe(100f)
        queued.removeAt(0).run()
        idle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(VectorEraserRecorder.ERASE_LABEL, c.undoManager.undoLabel)
        assertEquals(2, c.vec.vector!!.objects.size)
        assertEquals(3, c.deferredWaitTimeouts)
        assertNull(c.renderOverride)
        assertNull(c.inToolHistory)
    }

    /**
     * Review (G2): a cut cancelled while it runs on its worker (its wait hit the limit) stops at its
     * next object instead of running on to the end, as the vector and array renders stop: the cut
     * asks the background job's state ([EraseCut.cutAll]'s `active`) before each object.
     */
    @Test
    fun aCancelledCutStopsAtItsNextObject() {
        val boxes = (0 until 6).map { k -> box(10f + 45f * k, 60f, 45f + 45f * k, 140f, id = k + 1L) }
        val band = Path().apply { addRect(0f, 90f, w.toFloat(), 110f, Path.Direction.CW) }
        val all = EraseCut.cutAll(boxes, band)
        assertEquals("not cancelled: every box is cut", (1L..6L).toSet(), all.pieces.keys)
        assertTrue("in two", all.pieces.values.all { it.size == 2 })
        var asked = 0
        try {
            EraseCut.cutAll(boxes, band) { ++asked <= 2 }
            fail("a cancelled cut stops")
        } catch (e: CancellationException) {
            assertEquals("asked before each object, stopped before the third", 3, asked)
        }
    }
}
