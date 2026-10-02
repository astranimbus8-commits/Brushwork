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
