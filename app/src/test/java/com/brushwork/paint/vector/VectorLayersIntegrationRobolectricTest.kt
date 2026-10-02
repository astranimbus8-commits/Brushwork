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
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
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
 * v1.5 integration of the vector service with its callers (A1 x A2 / A3): `update` reports back
 * exactly once on every path (applied, refused, re-run after a stale patch, abandoned by the
 * editor closing) with `isRendering` true for the whole background render; nothing runs after
 * dispose; the object selection reads in O(1) per id from the content's cached index; lifting
 * every object of a layer that reaches past the canvas previews the off-canvas parts too.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLayersIntegrationRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 600
    private val h = 400

    private fun setup(policy: VectorLayers.Policy = VectorLayers.Policy.ASYNC): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.vectors.policy = policy
        }
    }

    private val EditorController.l1: Layer get() = doc.layers[0]

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, seed: Long = 5L): VStroke {
        val n = 24
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) + if (it % 3 == 0) 6f else 0f }
        return VStroke(0, preset = BrushLibrary.byId("pen")!!.copy(size = 14f), color = 0xFF2050C0.toInt(), seed = seed, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFFE04020.toInt()), stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 3f),
    )

    private fun objects() = listOf(stroke(40f, 60f, 560f, 120f), box(300f, 200f, 500f, 360f), stroke(60f, 340f, 540f, 220f, 9L))

    private fun seeded(c: EditorController): VectorContent {
        val p = c.vectors.policy
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.addObjects(c.l1, objects(), "Add")
        c.vectors.policy = p
        return c.l1.vector!!
    }

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    /** Runs the main looper; [each] is checked after every step until [done] or 60 s. */
    private fun runUntil(done: () -> Boolean, each: () -> Unit = {}) {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            each()
            if (done()) break
            check(System.currentTimeMillis() < deadline) { "never done" }
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ------------------------------------------------------------------ B-d: onDone exactly once

    @Test
    fun refusedUpdatesReportOnceAtOnce() {
        val c = setup()
        val base = seeded(c)
        val calls = ArrayList<Boolean>()
        c.l1.locked = true
        c.vectors.update(c.l1, base.without(setOf(1L)), "Delete") { calls += it }
        assertEquals(listOf(false), calls)
        c.l1.locked = false
        c.l1.restoreData(c.l1.dataSnapshot().copy(vector = null))
        c.vectors.update(c.l1, base, "Again") { calls += it }
        assertEquals(listOf(false, false), calls)
        runUntil({ !c.vectors.isRendering })
        assertEquals("nothing reports later", listOf(false, false), calls)
    }

    @Test
    fun aBackgroundRenderIsRenderingUntilItReportsOnceAlsoWhenReRunAfterAStalePatch() {
        val c = setup()
        val base = seeded(c)
        val calls = ArrayList<Boolean>()
        var renderingAtDone: Boolean? = null
        c.vectors.update(c.l1, base.without(setOf(2L)), "Delete") { ok ->
            calls += ok
            renderingAtDone = c.vectors.isRendering
        }
        assertTrue("in the background", c.vectors.isRendering)
        // The layer changes behind the service's back: the patch lands stale and is re-run.
        val sneaky = base.plus(listOf(box(20f, 200f, 120f, 280f))).first
        c.l1.restoreData(c.l1.dataSnapshot().copy(vector = sneaky))
        Canvas(c.l1.bitmap).also { cv -> VectorLayerRenderer.render(cv, VectorContent(objects = listOf(sneaky.objects.last())), Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h)) }
        c.l1.markChanged()
        runUntil({ calls.isNotEmpty() }) {
            if (calls.isEmpty()) assertTrue("isRendering until the edit reported back", c.vectors.isRendering)
        }
        assertEquals(listOf(true), calls)
        assertEquals("not rendering any more once it reported", false, renderingAtDone)
        runUntil({ !c.vectors.isRendering })
        assertEquals("exactly once", listOf(true), calls)
        val now = c.l1.vector!!
        assertNull(now.byId(2L))
        assertNotNull(now.byId(sneaky.objects.last().id))
        assertTrue(fresh(now).contentEquals(px(c.l1.bitmap)))
    }

    @Test
    fun disposeTellsAPendingUpdateAndAPreparingEditOnceAndRefusesEverythingAfter() {
        val c = setup()
        val base = seeded(c)
        val calls = ArrayList<Boolean>()
        c.vectors.update(c.l1, base.without(setOf(1L)), "Delete") { calls += it }
        assertTrue(c.vectors.isRendering)
        c.vectors.dispose()
        assertEquals("abandoned: told once", listOf(false), calls)
        assertFalse(c.vectors.isRendering)
        runUntil({ true })
        assertEquals(listOf(false), calls)
        assertSame("nothing applied", base, c.l1.vector)
        // Every later request is refused at once.
        c.vectors.update(c.l1, base.without(setOf(1L)), "Late") { calls += it }
        assertEquals(listOf(false, false), calls)
        assertTrue(c.vectors.addObjects(c.l1, objects(), "Late").isEmpty())
        val answers = ArrayList<VectorEditSession?>()
        c.vectors.beginEdit(c.l1, setOf(1L)) { answers += it }
        assertEquals(listOf<VectorEditSession?>(null), answers)
        assertSame(base, c.l1.vector)
        assertEquals(1, c.undoManager.undoCount)

        // An edit session still preparing in the background hears null once.
        val c2 = setup()
        seeded(c2)
        val ready = ArrayList<VectorEditSession?>()
        c2.vectors.beginEdit(c2.l1, setOf(1L)) { ready += it }
        assertTrue("prepared in the background", c2.vectors.isRendering)
        c2.vectors.dispose()
        assertEquals(listOf<VectorEditSession?>(null), ready)
        runUntil({ true })
        assertEquals(1, ready.size)
    }

    // ------------------------------------------------------------------ B-c: the selection's id lookups

    @Test
    fun selectedIdsReadFromTheContentsCachedIndex() {
        val c = setup(VectorLayers.Policy.SYNC)
        val n = 5000
        // Data only (the selection never looks at pixels).
        val content = VectorContent(objects = List(n) { i -> box((i % 50) * 10f, (i / 50) * 4f, (i % 50) * 10f + 6f, (i / 50) * 4f + 3f).withId(i + 1L) }, nextId = n + 1L)
        c.l1.restoreData(c.l1.dataSnapshot().copy(vector = content))
        val all = (1L..n).toSet()
        c.vectors.setSelection(c.l1, all)
        val first = c.vectors.selectedIds
        assertEquals(all, first)
        assertSame("the same index serves every read", ObjectIndex.of(content), ObjectIndex.of(content))
        assertSame("all present: the selection itself, no copy", first, c.vectors.selectedIds)
        // 100 reads of 5000 selected ids: hash lookups (O(k)), not a scan per id (O(k·n), ~10^9 steps).
        val t0 = System.nanoTime()
        repeat(100) { assertEquals(n, c.vectors.selectedIds.size) }
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("100 reads took $ms ms", ms < 1500)
        // Vanished ids drop out.
        c.l1.restoreData(c.l1.dataSnapshot().copy(vector = content.without(setOf(7L, 8L))))
        assertEquals(n - 2, c.vectors.selectedIds.size)
    }

    // ------------------------------------------------------------------ B-e: lifting everything past the canvas

    @Test
    fun liftingEveryObjectReachingPastTheCanvasPreviewsTheOffCanvasParts() {
        for (policy in listOf(VectorLayers.Policy.SYNC, VectorLayers.Policy.ASYNC)) {
            val c = setup(VectorLayers.Policy.SYNC)
            // A stroke and a box that run off the right edge, and a stroke inside the canvas.
            c.vectors.addObjects(c.l1, listOf(stroke(380f, 100f, 760f, 140f), box(450f, 220f, 720f, 330f), stroke(60f, 300f, 300f, 360f, 3L)), "Add")
            c.vectors.policy = policy
            val content = c.l1.vector!!
            var session: VectorEditSession? = null
            c.vectors.beginEdit(c.l1, content.objects.map { it.id }.toSet()) { session = it }
            if (policy == VectorLayers.Policy.ASYNC) {
                assertTrue("$policy: the part past the canvas renders in the background", c.vectors.isRendering)
                runUntil({ session != null })
            }
            val s = session!!
            val floating = s.floating!!
            val fr = s.floatingRect
            assertTrue("the lifted box reaches past the canvas: $fr", fr.right > w)
            fun alphaAt(x: Int, y: Int): Int {
                val fx = ((x - fr.left) * s.floatingScale).toInt()
                val fy = ((y - fr.top) * s.floatingScale).toInt()
                return floating.getPixel(fx, fy) ushr 24
            }
            assertTrue("$policy: the stroke past the edge shows", alphaAt(700, 134) > 0 || alphaAt(700, 128) > 0 || alphaAt(700, 140) > 0)
            assertTrue("$policy: the box past the edge shows", alphaAt(680, 280) == 0xFF)
            // On the canvas it is the cache itself.
            val cache = px(c.l1.bitmap)
            for (y in maxOf(fr.top, 0) until minOf(fr.bottom, h)) for (x in maxOf(fr.left, 0) until w) {
                assertEquals("$policy at $x,$y", cache[y * w + x], floating.getPixel(((x - fr.left) * s.floatingScale).toInt(), ((y - fr.top) * s.floatingScale).toInt()))
            }
            s.cancel()
        }
        // All on the canvas: still the cache's copy (nothing rendered).
        val c2 = setup(VectorLayers.Policy.SYNC)
        seeded(c2)
        var s2: VectorEditSession? = null
        c2.vectors.beginEdit(c2.l1, c2.l1.vector!!.objects.map { it.id }.toSet()) { s2 = it }
        val f2 = s2!!.floating!!
        val r2 = s2!!.floatingRect
        val cache2 = px(c2.l1.bitmap)
        for (y in r2.top until r2.bottom step 5) for (x in r2.left until r2.right step 5) assertEquals(cache2[y * w + x], f2.getPixel(x - r2.left, y - r2.top))
        s2!!.cancel()
    }
}
