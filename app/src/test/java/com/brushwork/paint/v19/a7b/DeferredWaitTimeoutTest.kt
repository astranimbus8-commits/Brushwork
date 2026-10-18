package com.brushwork.paint.v19.a7b

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.9 A7b step 5 (design 3.2 A7b, R9): a wait for work still landing in the background (a
 * vector render, an array render, a saved selection compressing) holds the main thread
 * [EditorController.deferredWaitLimitMs] at most (v1.8: `runBlocking`, for good). Past it the
 * work is cancelled and never lands later, even once its worker ends: its layer keeps the data
 * and pixels it had (I1), no step is recorded, and "<step> took too long and was cancelled"
 * shows. An Undo (or Redo) that waited is that cancellation: the step before is not also undone.
 * Under another operation's overlay ("Exporting…", "Saving…") the work lands on the spot instead,
 * through the synchronous path, as one step with a synchronous render's pixels.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredWaitTimeoutTest {
    private val kit = ObjectTestKit()
    private val gate = GatedWorker()
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        gate.close()
        kit.close()
        ArrayDraw.clearCaches()
    }

    /** The main looper and the workers for [ms] of real time (what lands, lands). */
    private fun pumpReal(ms: Long = 600) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
    }

    private fun tooSlow(label: String) = StabilityLabels19.TOO_SLOW.format(label)

    /** Three objects (ids 1, 2, 3) added synchronously; then renders go to the gated worker. */
    private fun vectorDrawing(): EditorController {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(kit.box(40f, 40f, 140f, 120f), kit.ellipse(260f, 200f), kit.box(400f, 250f, 480f, 330f)), "Add")
        c.vectors.workerDispatcher = gate
        c.vectors.policy = VectorLayers.Policy.ASYNC
        c.deferredWaitLimitMs = LIMIT_MS
        return c
    }

    @Test
    fun aTimedOutVectorRenderIsCancelledAndNeverLandsLater() {
        val c = vectorDrawing()
        val layer = c.vec
        val before = layer.vector!!
        val pixels = kit.pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        var done: Boolean? = null
        c.vectors.update(layer, before.without(setOf(3L)), "Erase") { done = it }
        assertTrue("the erase renders in the background", c.vectors.isRendering)
        assertNotNull("Undo and Redo reach the wait guard meanwhile", c.inToolHistory)
        assertFalse("which has no steps of its own", c.inToolHistory!!.canUndo || c.inToolHistory!!.canRedo)
        // Anything else that records a step lands it first (a stroke starting, a layer switch...).
        c.flushDeferredSteps()
        assertEquals("the wait gave up while the worker was still held (bounded)", 0, gate.letThrough)
        assertFalse(c.vectors.isRendering)
        assertEquals("its caller hears it was not applied", false, done)
        assertSame("the layer keeps its objects (I1)", before, layer.vector)
        assertArrayEquals("and its pixels (I1)", pixels, kit.pixels(layer.bitmap))
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertEquals(tooSlow("Erase"), c.message)
        assertEquals(1, c.deferredWaitTimeouts)
        // The worker ends now: nothing lands.
        gate.open()
        pumpReal()
        assertSame("never lands later", before, layer.vector)
        assertArrayEquals(pixels, kit.pixels(layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        assertNull("the guard lets go", c.inToolHistory)
        // The same edit again works as usual.
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.update(layer, before.without(setOf(3L)), "Erase")
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
    }

    @Test
    fun anUndoOrRedoThatWaitedIsThatCancellationAndNothingMore() {
        val c = vectorDrawing()
        val layer = c.vec
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.update(layer, layer.vector!!.without(setOf(1L)), "Delete objects")
        val deleted = layer.vector!!
        val pixels = kit.pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        c.vectors.policy = VectorLayers.Policy.ASYNC
        c.vectors.update(layer, deleted.without(setOf(3L)), "Erase")
        assertTrue(c.vectors.isRendering)
        // Undo waits for the erase, past the limit: the erase is cancelled, and that is the Undo.
        c.undo()
        assertFalse(c.vectors.isRendering)
        assertSame("the step before is not undone", deleted, layer.vector)
        assertArrayEquals(pixels, kit.pixels(layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals("Delete objects", c.undoManager.undoLabel)
        assertEquals(tooSlow("Erase"), c.message)
        assertNull("nothing pending: the guard is gone", c.inToolHistory)
        // The next Undo takes the step back as usual.
        c.message = null
        c.undo()
        assertEquals(listOf(1L, 2L, 3L), layer.vector!!.objects.map { it.id })
        assertTrue(c.canRedo)
        val restored = layer.vector!!
        val restoredPixels = kit.pixels(layer.bitmap)
        // Redo likewise: a Redo that waited redoes nothing (and the redo stack stays).
        c.vectors.update(layer, restored.without(setOf(2L)), "Erase")
        assertTrue(c.vectors.isRendering)
        c.redo()
        assertSame(restored, layer.vector)
        assertArrayEquals(restoredPixels, kit.pixels(layer.bitmap))
        assertTrue("still something to redo", c.canRedo)
        assertEquals("Delete objects", c.undoManager.redoLabel)
        assertEquals(tooSlow("Erase"), c.message)
        assertEquals(2, c.deferredWaitTimeouts)
        gate.open()
        pumpReal()
        assertSame("nothing landed later", restored, layer.vector)
        assertArrayEquals(restoredPixels, kit.pixels(layer.bitmap))
        c.redo()
        assertEquals(listOf(2L, 3L), layer.vector!!.objects.map { it.id })
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
    }

    @Test
    fun underAnotherOperationsOverlayTheWorkLandsOnTheSpot() {
        val c = vectorDrawing()
        val layer = c.vec
        val steps = c.undoManager.undoCount
        // A re-rendered edit; an export (its overlay up) lands the work it writes.
        val export = CompletableDeferred<Unit>()
        try {
            val erased = layer.vector!!.without(setOf(3L))
            var done: Boolean? = null
            c.vectors.update(layer, erased, "Erase") { done = it }
            assertTrue(c.vectors.isRendering)
            c.runBusy("Exporting PNG") { export.await() }
            assertEquals("Exporting PNG", c.busyMessage)
            c.settleVectorWork()
            assertFalse(c.vectors.isRendering)
            assertEquals(true, done)
            assertSame(erased, layer.vector)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            assertEquals("Erase", c.undoManager.undoLabel)
            assertArrayEquals("drawn on the spot: the rendering of the objects", kit.render(erased), kit.pixels(layer.bitmap))
        } finally {
            export.complete(Unit)
        }
        Smoke.pump(40)
        assertNull(c.busyMessage)
        // New objects drawn over the cache (a stroke, a shape); then closing ("Saving…") lands it.
        val saving = CompletableDeferred<Unit>()
        try {
            c.vectors.addObjects(layer, listOf(kit.box(300f, 60f, 380f, 140f)), "Add box")
            assertTrue(c.vectors.isRendering)
            c.runBusy("Saving…") { saving.await() }
            c.vectors.flushPending()
            assertFalse(c.vectors.isRendering)
            assertEquals(steps + 2, c.undoManager.undoCount)
            assertEquals("Add box", c.undoManager.undoLabel)
            assertEquals(3, layer.vector!!.objects.size)
            assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
            assertEquals("nothing cancelled", 0, c.deferredWaitTimeouts)
            assertFalse(c.message.orEmpty().contains("took too long"))
        } finally {
            saving.complete(Unit)
        }
        val landed = layer.vector
        val landedPixels = kit.pixels(layer.bitmap)
        gate.open()
        pumpReal()
        assertSame("nothing lands twice", landed, layer.vector)
        assertArrayEquals(landedPixels, kit.pixels(layer.bitmap))
        assertEquals(steps + 2, c.undoManager.undoCount)
        c.undo()
        c.undo()
        assertEquals(listOf(1L, 2L, 3L), layer.vector!!.objects.map { it.id })
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
    }

    // ------------------------------------------------------------------ arrays

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** A raster array (a 48 x 40 colour ramp at (20, 30), three copies) on [c]; the Array tool is current. */
    private fun rasterArray(c: EditorController): Layer {
        c.selectLayer(c.doc.layers[1])
        val w = 48
        val h = 40
        val px = IntArray(w * h) { i -> Color.argb(120 + (i % w * 3 + i / w * 2) % 136, 40 + i % w * 4, 30 + i / w * 5, 200 - i % w - i / w) }
        c.activeLayer.bitmap.setPixels(px, 0, w, 20, 30, w, h)
        c.setSelection(Selection.fromPath(Path().apply { addRect(10f, 20f, 80f, 80f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        Smoke.pump(20)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        return c.activeLayer.also { assertNotNull(it.array?.pixels) }
    }

    @Test
    fun aTimedOutArrayRenderIsCancelledAndTheUndoThatWaitedIsThatUndo() {
        val c = Smoke.controller(app)
        val layer = rasterArray(c)
        c.deferredWaitLimitMs = LIMIT_MS
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        val arrayGate = CountDownLatch(1)
        c.arrayRenders.workerHook = { arrayGate.await(30, TimeUnit.SECONDS) }
        val old = layer.array!!.spec
        val before = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        val label = c.undoManager.undoLabel
        var done: Boolean? = null
        try {
            assertTrue(ArrayOps.edit(c, layer, old.copy(mode = ArrayMode.CIRCLE, count = 12, centerX = 200f, centerY = 150f), ArrayLabels.EDIT) { done = it })
            assertTrue(c.arrayRenders.isPending)
            assertNotNull(c.inToolHistory)
            c.undo()
            assertFalse(c.arrayRenders.isPending)
            assertEquals(false, done)
            assertEquals("the old array (I1)", old, layer.array!!.spec)
            assertArrayEquals("its pixels (I1)", before, pixels(layer.bitmap))
            assertEquals("no step, and the step before stays", steps, c.undoManager.undoCount)
            assertEquals(label, c.undoManager.undoLabel)
            assertEquals(tooSlow(ArrayLabels.EDIT), c.message)
        } finally {
            arrayGate.countDown()
        }
        pumpReal()
        assertEquals("never lands later", old, layer.array!!.spec)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        assertNull(c.inToolHistory)
        Smoke.assertQuiet(c, "after the cancelled array render")
    }

    @Test
    fun anArrayRenderUnderAnotherOperationsOverlayLandsOnTheSpot() {
        val c = Smoke.controller(app)
        val layer = rasterArray(c)
        val twin = Smoke.controller(app)
        val twinLayer = rasterArray(twin)
        c.deferredWaitLimitMs = LIMIT_MS
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        val arrayGate = CountDownLatch(1)
        c.arrayRenders.workerHook = { arrayGate.await(30, TimeUnit.SECONDS) }
        val spec = layer.array!!.spec.copy(mode = ArrayMode.TRANSFORM, count = 4, moveX = 70f, moveY = 30f)
        val steps = c.undoManager.undoCount
        val busy = CompletableDeferred<Unit>()
        var done: Boolean? = null
        try {
            assertTrue(ArrayOps.edit(c, layer, spec, ArrayLabels.EDIT) { done = it })
            assertTrue(c.arrayRenders.isPending)
            c.runBusy("Saving…") { busy.await() }
            c.settleVectorWork()
            assertFalse(c.arrayRenders.isPending)
            assertEquals(true, done)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            assertEquals(spec.sanitized(), layer.array!!.spec)
            assertTrue(ArrayOps.edit(twin, twinLayer, spec, ArrayLabels.EDIT))
            assertArrayEquals("a synchronous render's pixels", pixels(twinLayer.bitmap), pixels(layer.bitmap))
            assertEquals(0, c.deferredWaitTimeouts)
        } finally {
            busy.complete(Unit)
            arrayGate.countDown()
        }
        pumpReal()
        assertEquals("nothing lands twice", steps + 1, c.undoManager.undoCount)
        assertArrayEquals(pixels(twinLayer.bitmap), pixels(layer.bitmap))
        Smoke.assertQuiet(c, "after the array landed on the spot")
    }

    // ------------------------------------------------------------------ saved selections

    @Test
    fun aSavedSelectionStillCompressingIsCancelledAndTheUndoThatWaitedUndoesNothingMore() {
        val c = Smoke.controller(app)
        c.deferredWaitLimitMs = LIMIT_MS
        val sel = Selection.fromPath(Path().apply { addRect(40f, 30f, 200f, 150f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false)
        c.setSelection(sel)
        val steps = c.undoManager.undoCount
        assertTrue(steps > 0)
        c.beforeSavedSelectionPack = { delay(30_000) }
        assertTrue(c.saveSelection())
        assertEquals(1, c.pendingSavedSelections.size)
        // The hotbar's Undo: the save lands first (waiting), then undo runs.
        HistoryLabels.performUndo(c)
        assertTrue("nothing pending", c.pendingSavedSelections.isEmpty())
        assertTrue("never saved", c.doc.savedSelections.isEmpty())
        assertNotNull("the selection step is not undone", c.selection)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(tooSlow(SavedSelectionLabels.SAVE), c.message)
        pumpReal()
        assertTrue("never lands later", c.doc.savedSelections.isEmpty())
        assertNull("the one-shot guard is gone", c.inToolHistory)
        // The next Undo takes back the selection.
        HistoryLabels.performUndo(c)
        assertNull(c.selection)
        assertEquals(steps - 1, c.undoManager.undoCount)
    }

    @Test
    fun aSavedSelectionUnderAnotherOperationsOverlayIsWaitedFor() {
        val c = Smoke.controller(app)
        c.deferredWaitLimitMs = LIMIT_MS
        c.setSelection(Selection.fromPath(Path().apply { addRect(40f, 30f, 200f, 150f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false))
        c.beforeSavedSelectionPack = { delay(LIMIT_MS * 3) }
        assertTrue(c.saveSelection())
        val pending = c.pendingSavedSelections.single()
        val busy = CompletableDeferred<Unit>()
        try {
            c.runBusy("Saving…") { busy.await() }
            c.landSavedSelections()
            assertEquals("landed (it has no synchronous path: waited for)", listOf(pending.id), c.doc.savedSelections.map { it.id })
            assertEquals(SavedSelectionLabels.SAVE, c.undoManager.undoLabel)
            assertEquals(0, c.deferredWaitTimeouts)
        } finally {
            busy.complete(Unit)
        }
        pumpReal(300)
        assertEquals(1, c.doc.savedSelections.size)
    }

    private companion object {
        /** The wait limit in these tests (1.5 s in the app). */
        const val LIMIT_MS = 150L
    }
}
