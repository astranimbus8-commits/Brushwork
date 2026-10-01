package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.render.VectorLayerRenderer
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
import android.os.Looper

/**
 * v1.5 A1 (I3): background re-renders. A render goes to the background worker; its step lands
 * with the data and pixels together (equal to a synchronous render), only while the layer is
 * unchanged (a stale patch is rejected and the edit re-based and rendered again); any other step,
 * undo or redo completes it first; renders inside another step stay synchronous; long renders
 * show the busy overlay; edit sessions prepare in the background and keep their preview up while
 * a commit renders.
 *
 * The controller runs on the main looper (Robolectric's paused looper): results land only when
 * the test lets the looper run ([awaitIdle]) or something completes them ([VectorLayers.flushPending],
 * undo, another edit), so every order below is deterministic.
 */
@RunWith(RobolectricTestRunner::class)
class VectorAsyncRobolectricTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)

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
        doc.layers += Layer(doc.newLayerId(), "Vector 2", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.vectors.policy = policy
        }
    }

    /** Lets the main looper run until every vector render of [cs] landed. */
    private fun awaitIdle(vararg cs: EditorController) {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cs.all { !it.vectors.isRendering && it.busyMessage == null }) break
            check(System.currentTimeMillis() < deadline) { "renders did not land" }
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val EditorController.l1: Layer get() = doc.layers[0]
    private val EditorController.l2: Layer get() = doc.layers[1]

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, id: String = "chalk", seed: Long = 5L): VStroke {
        val n = 24
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) + if (it % 3 == 0) 6f else 0f }
        return VStroke(0, preset = BrushLibrary.byId(id)!!, color = 0xFF2050C0.toInt(), seed = seed, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, color: Int = 0xFFE04020.toInt()) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(color), stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 3f),
    )

    private fun ellipse(cx: Float, cy: Float, opacity: Float = 1f) = VShape(
        0, opacity = opacity,
        shape = ShapeObject(ShapeType.ELLIPSE, cx = cx, cy = cy, w = 220f, h = 150f, rotation = 20f, style = ShapeStyle.STROKE_FILL, strokeWidth = 6f, strokeColor = 0xFF006030.toInt(), fillColor = 0xFF60D090.toInt()),
    )

    private fun objects() = listOf(stroke(40f, 60f, 660f, 120f), ellipse(300f, 300f), box(450f, 380f, 620f, 560f), stroke(60f, 540f, 640f, 420f, "softround", 9L), ellipse(200f, 450f, 0.6f))

    @Test
    fun aBackgroundRenderLandsAsOneStepEqualToASynchronousOne() {
        val sync = setup(VectorLayers.Policy.SYNC)
        val async = setup()
        sync.vectors.addObjects(sync.l1, objects(), "Add")
        val ids = async.vectors.addObjects(async.l1, objects(), "Add")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids)
        assertTrue("rendering in the background", async.vectors.isRendering)
        assertEquals("nothing recorded yet", 0, async.undoManager.undoCount)
        assertEquals(VectorContent.EMPTY, async.l1.vector)
        awaitIdle(async)
        assertFalse(async.vectors.isRendering)
        assertEquals(1, async.undoManager.undoCount)
        assertEquals(sync.l1.vector, async.l1.vector)
        assertArrayEquals(px(sync.l1.bitmap), px(async.l1.bitmap))
        assertArrayEquals(fresh(async.l1.vector!!), px(async.l1.bitmap))
        // An update: move the ellipse, delete a stroke.
        for (c in listOf(sync, async)) {
            val s = c.l1.vector!!
            val moved = s.replaced(mapOf(2L to listOf(VectorOps.transformed(s.byId(2)!!, floatArrayOf(1f, 0f, 90f, 0f, 1f, -40f, 0f, 0f, 1f))))).without(setOf(4L))
            var done: Boolean? = null
            c.vectors.update(c.l1, moved, "Edit") { done = it }
            awaitIdle(c)
            assertEquals(true, done)
            assertSame(moved, c.l1.vector)
        }
        assertArrayEquals(px(sync.l1.bitmap), px(async.l1.bitmap))
        assertEquals(2, async.undoManager.undoCount)
        async.undo()
        assertArrayEquals(fresh(async.l1.vector!!), px(async.l1.bitmap))
    }

    @Test
    fun aPendingRenderRecordsNothingUntilItLandsAndUndoCompletesItFirst() {
        // A paused worker: scheduled, not rendered yet (the policy's flush completes it on demand).
        val c = setup()
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.addObjects(c.l1, objects().take(2), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val s0 = c.l1.vector!!
        val s1 = s0.without(setOf(1L))
        var done: Boolean? = null
        c.vectors.update(c.l1, s1, "Delete") { done = it }
        assertNull(done)
        assertTrue(c.vectors.isRendering)
        assertEquals(1, c.undoManager.undoCount)
        assertSame(s0, c.l1.vector)
        // Undo right away: the pending delete is recorded first, then undone.
        c.undo()
        assertEquals(true, done)
        assertSame(s0, c.l1.vector)
        assertArrayEquals(fresh(s0), px(c.l1.bitmap))
        assertEquals(1, c.undoManager.undoCount)
        c.redo()
        assertSame(s1, c.l1.vector)
        assertArrayEquals(fresh(s1), px(c.l1.bitmap))
        awaitIdle(c)
        assertEquals(2, c.undoManager.undoCount)
        assertFalse(c.vectors.isRendering)
    }

    @Test
    fun anotherEditOfTheLayerWhileRenderingKeepsBothEdits() {
        val c = setup()
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val base = c.l1.vector!!
        c.vectors.update(c.l1, base.without(setOf(1L)), "Delete")
        // Computed from the same (old) content while the first edit renders: re-based onto it.
        val recolored = (base.byId(3) as VPath).copy(fill = VPaint.Solid(0xFF3060F0.toInt()))
        c.vectors.update(c.l1, base.replaced(mapOf(3L to listOf(recolored))), "Recolor")
        awaitIdle(c)
        val now = c.l1.vector!!
        assertNull("the first edit is kept", now.byId(1L))
        assertEquals("the second edit is kept", recolored, now.byId(3L))
        assertEquals("add, delete, recolor", 3, c.undoManager.undoCount)
        assertArrayEquals(fresh(now), px(c.l1.bitmap))
        c.undo()
        assertNull(c.l1.vector!!.byId(1L))
        assertEquals(base.byId(3L), c.l1.vector!!.byId(3L))
        assertArrayEquals(fresh(c.l1.vector!!), px(c.l1.bitmap))
    }

    @Test
    fun aStalePatchIsRejectedAndTheEditRenderedAgain() {
        val c = setup()
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val base = c.l1.vector!!
        c.vectors.update(c.l1, base.without(setOf(2L)), "Delete")
        // The layer changes behind the service's back (not through a step): the patch is stale.
        val sneaky = base.plus(listOf(box(20f, 200f, 120f, 280f, 0xFF00AA00.toInt()))).first
        c.l1.restoreData(c.l1.dataSnapshot().copy(vector = sneaky))
        Canvas(c.l1.bitmap).also { cv -> VectorLayerRenderer.render(cv, VectorContent(objects = listOf(sneaky.objects.last())), Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h)) }
        c.l1.markChanged()
        c.vectors.flushPending()
        awaitIdle(c)
        val now = c.l1.vector!!
        assertNull("the delete is applied", now.byId(2L))
        assertNotNull("the other change is kept", now.byId(sneaky.objects.last().id))
        assertArrayEquals(fresh(now), px(c.l1.bitmap))
        assertEquals("add, delete", 2, c.undoManager.undoCount)
    }

    @Test
    fun aLayerThatStoppedBeingAVectorLayerRefusesThePatch() {
        val c = setup()
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        var done: Boolean? = null
        c.vectors.update(c.l1, c.l1.vector!!.without(setOf(1L)), "Delete") { done = it }
        c.l1.restoreData(c.l1.dataSnapshot().copy(vector = null))
        c.l1.markChanged()
        c.vectors.flushPending()
        assertEquals(false, done)
        assertNull(c.l1.vector)
        assertEquals("only the add", 1, c.undoManager.undoCount)
    }

    @Test
    fun editsInsideAnotherStepStaySynchronous() {
        val c = setup()
        var done: Boolean? = null
        c.groupUndo("Group") {
            c.vectors.addObjects(c.l1, objects().take(2), "Add")
            c.vectors.update(c.l1, c.l1.vector!!.without(setOf(1L)), "Delete") { done = it }
            // Applied before update returned: the group holds both.
            assertEquals(true, done)
            assertFalse(c.vectors.isRendering)
        }
        assertEquals(1, c.undoManager.undoCount)
        assertArrayEquals(fresh(c.l1.vector!!), px(c.l1.bitmap))
    }

    @Test
    fun aLongRenderShowsTheBusyOverlayUntilItLands() {
        val c = setup(VectorLayers.Policy.AUTO)
        c.vectors.nsPerUnit = 1e6 // every render "takes" far more than 400 ms
        c.vectors.addObjects(c.l1, objects(), "Add")
        assertTrue(c.vectors.isRendering)
        assertEquals(VectorLayers.BUSY_LABEL, c.busyMessage)
        awaitIdle(c)
        assertNull(c.busyMessage)
        assertEquals(1, c.undoManager.undoCount)
        assertArrayEquals(fresh(c.l1.vector!!), px(c.l1.bitmap))
        // A cheap one stays on the main thread.
        c.vectors.nsPerUnit = 1e-6
        var done: Boolean? = null
        c.vectors.update(c.l1, c.l1.vector!!.without(setOf(3L)), "Delete") { done = it }
        assertEquals(true, done)
        assertFalse(c.vectors.isRendering)
        assertEquals(2, c.undoManager.undoCount)
    }

    @Test
    fun twoLayersRenderOneAfterTheOther() {
        val c = setup()
        c.vectors.addObjects(c.l1, objects().take(3), "Add 1")
        c.vectors.addObjects(c.l2, objects().drop(2), "Add 2")
        assertEquals("the second waits for the first", 1, c.undoManager.undoCount)
        awaitIdle(c)
        assertEquals(2, c.undoManager.undoCount)
        assertArrayEquals(fresh(c.l1.vector!!), px(c.l1.bitmap))
        assertArrayEquals(fresh(c.l2.vector!!), px(c.l2.bitmap))
        c.undo()
        assertEquals(VectorContent.EMPTY, c.l2.vector)
        assertEquals(3, c.l1.vector!!.objects.size)
    }

    // ------------------------------------------------------------------ edit sessions

    @Test
    fun anEditSessionPreparesInTheBackgroundLikeOnTheMainThread() {
        val sync = setup(VectorLayers.Policy.SYNC)
        val async = setup()
        sync.vectors.addObjects(sync.l1, objects(), "Add")
        async.vectors.policy = VectorLayers.Policy.SYNC
        async.vectors.addObjects(async.l1, objects(), "Add")
        async.vectors.policy = VectorLayers.Policy.ASYNC
        var a: VectorEditSession? = null
        var b: VectorEditSession? = null
        sync.vectors.beginEdit(sync.l1, setOf(2L, 4L)) { a = it }
        async.vectors.beginEdit(async.l1, setOf(2L, 4L)) { b = it }
        assertNotNull(a)
        assertNull("prepared in the background", b)
        awaitIdle(async)
        assertNotNull(a); assertNotNull(b)
        fun drawn(s: VectorEditSession): IntArray = BitmapUtils.createLayerBitmap(w, h).also { s.drawContent(Canvas(it)) }.let { px(it) }
        assertArrayEquals(drawn(a!!), drawn(b!!))
        assertArrayEquals(px(a!!.floating!!), px(b!!.floating!!))
        assertSame(b, async.renderOverride)
        // A newer request answers the older one with null.
        b!!.cancel()
        var first: VectorEditSession? = VectorEditSession(async, async.l1, emptySet(), null, Rect(), 1f)
        var second: VectorEditSession? = null
        async.vectors.beginEdit(async.l1, setOf(1L)) { first = it }
        async.vectors.beginEdit(async.l1, setOf(3L)) { second = it }
        awaitIdle(async)
        assertNull(first)
        assertEquals(setOf(3L), second!!.ids)
        second!!.cancel()
    }

    @Test
    fun aCommitRenderingInTheBackgroundKeepsThePreviewUp() {
        val c = setup()
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.addObjects(c.l1, objects(), "Add")
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.l1, setOf(2L)) { session = it }
        val s = session!!
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val moved = VectorOps.transformed(c.l1.vector!!.byId(2)!!, floatArrayOf(1f, 0f, 60f, 0f, 1f, 30f, 0f, 0f, 1f))
        var done: Boolean? = null
        // A paused render: flush only when asked.
        s.commit(listOf(moved), "Edit") { done = it }
        assertFalse(s.isOpen)
        // The session stays installed (its preview up) until the result lands.
        assertNull(done)
        assertSame(s, c.renderOverride)
        s.cancel() // a no-op once committed
        assertSame(s, c.renderOverride)
        c.vectors.flushPending()
        awaitIdle(c)
        assertEquals(true, done)
        assertNull(c.renderOverride)
        assertEquals(moved, c.l1.vector!!.byId(2))
        assertArrayEquals(fresh(c.l1.vector!!), px(c.l1.bitmap))
        assertEquals(2, c.undoManager.undoCount)
    }

    @Test
    fun disposeAbandonsAPendingRender() {
        val c = setup()
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.dispose()
        awaitIdle(c)
        assertFalse(c.vectors.isRendering)
        assertEquals(0, c.undoManager.undoCount)
        assertEquals(VectorContent.EMPTY, c.l1.vector)
    }
}
