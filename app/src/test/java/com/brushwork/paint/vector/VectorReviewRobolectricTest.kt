package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * v1.5 A1 review fixes: background renders never lose an edit or freeze the main thread, a
 * partial duplicate copies the cache where it can, and damaged ids are numbered again.
 */
@RunWith(RobolectricTestRunner::class)
class VectorReviewRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 700
    private val h = 600

    private fun setup(policy: VectorLayers.Policy = VectorLayers.Policy.ASYNC): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.vectors.policy = policy
        }
    }

    private fun awaitIdle(c: EditorController) {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!c.vectors.isRendering && (c.busyMessage == null || c.busyMessage != VectorLayers.BUSY_LABEL)) break
            check(System.currentTimeMillis() < deadline) { "renders did not land" }
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val EditorController.l1: Layer get() = doc.layers[0]

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, id: String = "pen", seed: Long = 5L): VStroke {
        val n = 24
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) + if (it % 3 == 0) 6f else 0f }
        return VStroke(0, preset = BrushLibrary.byId(id)!!, color = 0xFF2050C0.toInt(), seed = seed, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun ellipse(cx: Float, cy: Float) = VShape(
        0, shape = ShapeObject(ShapeType.ELLIPSE, cx = cx, cy = cy, w = 220f, h = 150f, rotation = 20f, style = ShapeStyle.STROKE_FILL, strokeWidth = 6f, strokeColor = 0xFF006030.toInt(), fillColor = 0xFF60D090.toInt()),
    )

    private fun objects() = listOf(stroke(40f, 60f, 660f, 120f, "chalk"), ellipse(300f, 300f), stroke(60f, 540f, 640f, 420f, "softround", 9L), ellipse(520f, 470f))

    // ------------------------------------------------------------------ never lost, never frozen

    @Test
    fun anEditUnderAnotherBusyOverlayRendersAtOnce() {
        // Closing the editor shows "Saving…" and then commits the tool's pending work: that edit
        // must land before the save, not in a background render the closing editor abandons.
        val c = setup()
        val saved = CompletableDeferred<Unit>()
        c.runBusy("Saving…") { saved.await() }
        assertEquals("Saving…", c.busyMessage)
        var done: Boolean? = null
        val ids = c.vectors.addObjects(c.l1, objects(), "Add")
        assertEquals(4, ids.size)
        assertFalse(c.vectors.isRendering)
        assertEquals("recorded at once", 1, c.undoManager.undoCount)
        c.vectors.update(c.l1, c.l1.vector!!.without(setOf(2L)), "Delete") { done = it }
        assertEquals(true, done)
        assertEquals(2, c.undoManager.undoCount)
        assertArrayEquals(fresh(c.l1.vector!!), px(c.l1.bitmap))
        saved.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(c.busyMessage)
    }

    @Test
    fun anEditSessionWaitsForAPendingRenderWithoutCompletingItOnTheMainThread() {
        // The Transform tool lifts again right after a commit: beginEdit must not block on the
        // worker (the busy overlay could never show), it prepares once the render landed.
        val c = setup(VectorLayers.Policy.SYNC)
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val base = c.l1.vector!!
        c.vectors.update(c.l1, base.without(setOf(1L)), "Delete")
        assertTrue(c.vectors.isRendering)
        var session: VectorEditSession? = null
        var answered = false
        c.vectors.beginEdit(c.l1, setOf(2L)) { session = it; answered = true }
        assertFalse("not answered yet", answered)
        assertEquals("the pending delete was not forced through", 1, c.undoManager.undoCount)
        assertSame(base, c.l1.vector)
        awaitIdle(c)
        assertTrue(answered)
        assertEquals(2, c.undoManager.undoCount)
        val s = session!!
        assertEquals(setOf(2L), s.ids)
        assertSame(s, c.renderOverride)
        // The session belongs to the content after the delete.
        assertNull(c.l1.vector!!.byId(1L))
        s.cancel()
        assertNull(c.renderOverride)
    }

    @Test
    fun aLongEditPreparationShowsTheBusyOverlayAndStopGivesItUp() {
        val c = setup(VectorLayers.Policy.SYNC)
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.AUTO
        c.vectors.nsPerUnit = 1e6 // every render "takes" far more than 400 ms
        var answered = false
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.l1, setOf(2L)) { session = it; answered = true }
        assertFalse(answered)
        assertEquals(VectorLayers.BUSY_LABEL, c.busyMessage)
        val stop = c.busyCancel
        assertNotNull("Stop is offered", stop)
        stop!!.invoke()
        assertTrue(answered)
        assertNull(session)
        awaitIdle(c)
        assertNull(c.busyMessage)
        assertNull(c.renderOverride)
        // Without Stop the session is installed when ready and the overlay goes.
        c.vectors.beginEdit(c.l1, setOf(2L)) { session = it }
        assertEquals(VectorLayers.BUSY_LABEL, c.busyMessage)
        awaitIdle(c)
        assertNull(c.busyMessage)
        assertSame(session, c.renderOverride)
        session!!.cancel()
    }

    @Test
    fun aNewerEditRequestWhileWaitingAnswersTheOlderOneWithNull() {
        val c = setup(VectorLayers.Policy.SYNC)
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        c.vectors.update(c.l1, c.l1.vector!!.without(setOf(1L)), "Delete")
        var first: VectorEditSession? = null
        var firstAnswered = false
        var second: VectorEditSession? = null
        c.vectors.beginEdit(c.l1, setOf(2L)) { first = it; firstAnswered = true }
        c.vectors.beginEdit(c.l1, setOf(4L)) { second = it }
        assertTrue(firstAnswered)
        assertNull(first)
        awaitIdle(c)
        assertEquals(setOf(4L), second!!.ids)
        second!!.cancel()
    }

    @Test
    fun undoWhileAnEditSessionWaitsStillWorks() {
        val c = setup(VectorLayers.Policy.SYNC)
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        c.vectors.update(c.l1, c.l1.vector!!.without(setOf(1L)), "Delete")
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.l1, setOf(2L)) { session = it }
        // Undo completes the pending delete first, then undoes it.
        c.undo()
        assertNotNull(c.l1.vector!!.byId(1L))
        awaitIdle(c)
        assertArrayEquals(fresh(c.l1.vector!!), px(c.l1.bitmap))
        session?.cancel()
    }

    @Test
    fun objectsAnEditAddsAreSelectableWhileItRenders() {
        // The Object bar's Duplicate selects the copies right away: the bar must not vanish
        // while the copies render in the background.
        val c = setup(VectorLayers.Policy.SYNC)
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val base = c.l1.vector!!
        val (after, newIds) = base.plus(listOf(VectorOps.transformed(base.objects[1], floatArrayOf(1f, 0f, 16f, 0f, 1f, 16f, 0f, 0f, 1f))))
        c.vectors.update(c.l1, after, "Duplicate")
        assertTrue(c.vectors.isRendering)
        c.vectors.setSelection(c.l1, newIds.toSet())
        assertEquals(newIds.toSet(), c.vectors.selectedIds)
        assertSame(c.l1, c.vectors.selectedLayer)
        awaitIdle(c)
        assertSame(after, c.l1.vector)
        assertEquals(newIds.toSet(), c.vectors.selectedIds)
        // Undo removes the copy: it is no longer selected.
        c.undo()
        assertTrue(c.vectors.selectedIds.isEmpty())
    }

    @Test
    fun anEditSessionCommittingAWholePixelMoveShiftsTheCache() {
        val c = setup(VectorLayers.Policy.SYNC)
        // Two separate objects: the ellipse moves, the stroke far away stays.
        c.vectors.addObjects(c.l1, listOf(ellipse(200f, 200f), stroke(40f, 540f, 640f, 520f, "pen", 9L)), "Add")
        val before = c.l1.vector!!
        val pixels = px(c.l1.bitmap)
        val shifts = c.vectors.shiftCount
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.l1, setOf(1L)) { session = it }
        val m = floatArrayOf(1f, 0f, 37f, 0f, 1f, 21f, 0f, 0f, 1f)
        var ok: Boolean? = null
        session!!.commit(listOf(VectorOps.transformed(before.byId(1L)!!, m)), "Transform objects") { ok = it }
        assertEquals(true, ok)
        assertEquals("moved by shifting the cache", shifts + 1, c.vectors.shiftCount)
        assertNull(c.renderOverride)
        val now = px(c.l1.bitmap)
        // The ellipse's pixels moved by (37, 21); the stroke's stayed.
        for (y in 100 until 320) for (x in 60 until 340) assertEquals(pixels[y * w + x], now[(y + 21) * w + (x + 37)])
        for (y in 480 until h) for (x in 0 until w) assertEquals(pixels[y * w + x], now[y * w + x])
        assertEquals(1, c.undoManager.undoCount - 1)
        c.undo()
        assertSame(before, c.l1.vector)
        assertArrayEquals(pixels, px(c.l1.bitmap))
        // A fractional move is drawn again.
        c.vectors.beginEdit(c.l1, setOf(1L)) { session = it }
        session!!.commit(listOf(VectorOps.transformed(before.byId(1L)!!, floatArrayOf(1f, 0f, 10.5f, 0f, 1f, 3f, 0f, 0f, 1f))), "Transform objects")
        assertEquals(shifts + 1, c.vectors.shiftCount)
        assertArrayEquals(fresh(c.l1.vector!!), px(c.l1.bitmap))
    }

    @Test
    fun mergingAfterAnAsyncEditKeepsBothEdits() {
        val c = setup(VectorLayers.Policy.SYNC)
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val base = c.l1.vector!!
        c.vectors.update(c.l1, base.without(setOf(1L)), "Delete")
        // A second edit computed from the same (old) content while the first still renders.
        c.vectors.update(c.l1, base.without(setOf(3L)), "Delete")
        awaitIdle(c)
        val now = c.l1.vector!!
        assertEquals(listOf(2L, 4L), now.objects.map { it.id })
        assertArrayEquals(fresh(now), px(c.l1.bitmap))
        assertEquals(3, c.undoManager.undoCount)
    }

    // ------------------------------------------------------------------ duplicate with a selection

    @Test
    fun aPartialDuplicateCopiesTheCacheWhereOnlyTouchedObjectsPaint() {
        val c = setup(VectorLayers.Policy.SYNC)
        // Two groups far apart; a stroke of the left group crosses into the right group's tiles.
        val objs = listOf(
            ellipse(150f, 150f), stroke(40f, 260f, 420f, 300f, "pen", 3L),
            ellipse(560f, 460f), stroke(380f, 520f, 680f, 560f, "chalk", 8L),
        )
        c.vectors.addObjects(c.l1, objs, "Add")
        val content = c.l1.vector!!
        // Select the left ellipse and the long pen stroke.
        val sel = Selection.fromPath(android.graphics.Path().apply { addRect(100f, 120f, 200f, 280f, android.graphics.Path.Direction.CW) }, w, h)
        val copy = VectorLayerOps.duplicateTouched(c, c.l1, sel)!!
        val ids = copy.vector!!.objects.map { it.id }.toSet()
        assertEquals(setOf(1L, 2L), ids)
        assertArrayEquals("exactly the rendering of the touched objects", fresh(copy.vector!!), px(copy.bitmap))
        assertEquals(content, c.doc.layers[0].vector)
        c.undo()
        assertEquals(-1, c.doc.indexOf(copy))
    }

    // ------------------------------------------------------------------ codec

    @Test
    fun overflowingIdsAreNumberedAgain() {
        val base = VectorContent(objects = listOf(stroke(10f, 10f, 50f, 50f), ellipse(100f, 100f), stroke(20f, 80f, 90f, 30f)))
        val bad = VectorContent(objects = listOf(base.objects[0].withId(Long.MAX_VALUE), base.objects[1].withId(5L), base.objects[2].withId(Long.MAX_VALUE - 1)), nextId = Long.MAX_VALUE)
        val fixed = VectorCodec.decode(VectorCodec.encode(bad))
        assertEquals(listOf(1L, 2L, 3L), fixed.objects.map { it.id })
        assertEquals(4L, fixed.nextId)
        val (more, ids) = fixed.plus(listOf(ellipse(10f, 10f)))
        assertEquals(listOf(4L), ids)
        assertEquals(4, more.objects.map { it.id }.toSet().size)
        // Ordinary contents are untouched.
        val ok = VectorContent.EMPTY.plus(base.objects).first
        assertEquals(ok, VectorCodec.decode(VectorCodec.encode(ok)))
    }
}
