package com.brushwork.paint.qa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.tools.mask.MaskTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 final QA, performance guards at the user's own canvas (1080 x 2408, the one large-canvas
 * test of this QA pass): a Tone slider drag renders once per frame for the newest value, a full
 * frame of a full-canvas adjustment and a full-canvas mask edit stay far from a stall. Times are
 * printed for the report; the bounds leave a loaded desktop JVM plenty of slack over the T606
 * budgets (§4.3c: 50–70 ms per slider frame, about 8 ms per 2.6 MP mask render).
 */
@RunWith(RobolectricTestRunner::class)
class MaskAdjustmentPhonePerfQaRobolectricTest {
    private val w = 1080
    private val h = 2408
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        scope.cancel()
    }

    @Test
    fun aToneSliderDragAndMaskEditsOnThePhonesCanvas() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("perf", "perf", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF203060.toInt(), 0xFFF0C080.toInt(), Shader.TileMode.CLAMP) })
        }
        doc.activeLayerIndex = 0
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        val tone = FilterRegistry.byId("adjust.tone")!!

        // A full-canvas linear mask: one synchronous render on release.
        val spec = MaskSpec(components = listOf(LinearMask(1, x0 = 0f, y0 = 0f, x1 = 0f, y1 = h.toFloat())), nextId = 2)
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), spec)!!
        val t0 = System.nanoTime()
        val moved = spec.copy(components = listOf(LinearMask(1, x0 = 0f, y0 = 200f, x1 = 0f, y1 = h - 200f)))
        assertTrue(MaskEdits.apply(c, adj, moved, "Edit mask"))
        val maskMs = (System.nanoTime() - t0) / 1e6
        // Add a radial: changes only its own area.
        val t1 = System.nanoTime()
        val withRadial = moved.copy(components = moved.components + RadialMask(2, cx = 540f, cy = 1200f, rx = 300f, ry = 300f), nextId = 3)
        assertTrue(MaskEdits.apply(c, adj, withRadial, "Mask: radial"))
        val radialMs = (System.nanoTime() - t1) / 1e6

        // Baseline: the same full-canvas frame without the adjustment (what compositing alone
        // costs here), then the first frame with it.
        adj.visible = false
        c.invalidateDoc(null)
        val tb = System.nanoTime()
        c.tiles.update(c.compositor, null)
        val baseMs = (System.nanoTime() - tb) / 1e6
        adj.visible = true
        c.invalidateDoc(null)
        c.tiles.update(c.compositor, null)
        // The mapper alone over the whole canvas.
        val px = IntArray(w * h) { 0xFF000000.toInt() or (it * 2654435761L.toInt() ushr 8) }
        val mapper = tone.pixelMapper(tone.defaultValues().set("exposure", 1f))!!
        val tm = System.nanoTime()
        com.brushwork.paint.core.Parallel.forRange(h, 8) { r0, r1 -> mapper.map(px, r0 * w, r1 * w) }
        val mapMs = (System.nanoTime() - tm) / 1e6
        // A slider drag: 30 values between two frames render once.
        val edit = tool.adjustmentEdit(adj)
        for (i in 1..30) edit.preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", i / 20f)))
        val visible = Rect(0, 0, w, h)
        val t2 = System.nanoTime()
        assertTrue(c.tiles.update(c.compositor, visible))
        val frameMs = (System.nanoTime() - t2) / 1e6
        assertFalse("one render for 30 slider moves", c.tiles.update(c.compositor, visible))
        edit.flush()
        assertEquals("one step for the drag", "Edit adjustment", c.undoManager.undoLabel)

        // Undo of the mask edit re-renders from the data (no tiles).
        val t3 = System.nanoTime()
        c.undo(); c.undo()
        val undoMs = (System.nanoTime() - t3) / 1e6

        println("[qa] 1080x2408: full mask edit ${"%.1f".format(maskMs)} ms, radial add ${"%.1f".format(radialMs)} ms, full-canvas Tone frame ${"%.1f".format(frameMs)} ms (same frame without the adjustment ${"%.1f".format(baseMs)} ms, Tone mapper alone ${"%.1f".format(mapMs)} ms), undo x2 ${"%.1f".format(undoMs)} ms")
        assertTrue("full mask edit $maskMs ms", maskMs < 2_000)
        assertTrue("radial add $radialMs ms", radialMs < 2_000)
        assertTrue("full-canvas Tone frame $frameMs ms", frameMs < 5_000)
        assertTrue("undo $undoMs ms", undoMs < 4_000)
    }

    // ------------------------------------------------------------------ v1.6 §3.1: live sessions

    /** What one canvas's slider drag costs per frame, before (v1.5 Skia stage) and after (v1.6). */
    private class LiveNumbers(
        val v15Ms: Double, val exactMs: Double, val firstLiveMs: Double, val liveMs: Double, val scale: Float,
        val refineFrames: Int, val refineMs: Double,
    )

    private fun median(v: List<Double>): Double = v.sorted()[v.size / 2]

    /**
     * §3.1 C3's scenario on a [w] x [h] canvas fitted at [zoom] on the phone's 1080 x 2408 screen:
     * Tone with a full-canvas linear mask, [below] layers under it and [above] over it (Multiply
     * and Normal). Measures a full exact frame of the v1.5 stage (Skia path), a full exact frame of
     * v1.6 without a session (the fused path), and live frames (policy LIVE: proxies), then the
     * refinement after the finger lifts.
     */
    private fun liveDrag(w: Int, h: Int, zoom: Float, below: Int, above: Int, frames: Int): LiveNumbers {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("live", "live", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF203060.toInt(), 0xFFF0C080.toInt(), Shader.TileMode.CLAMP) })
        }
        for (i in 1 until below) {
            doc.layers += Layer(doc.newLayerId(), "Below $i", BitmapUtils.createLayerBitmap(w, h)).also {
                val cv = Canvas(it.bitmap)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (i % 2 == 0) 0x9930A050.toInt() else 0xCCE04020.toInt() }
                cv.drawCircle(w * (0.3f + 0.2f * i), h * 0.4f, w * 0.25f, p)
                cv.drawRect(w * 0.1f, h * (0.6f + 0.05f * i), w * 0.9f, h * (0.7f + 0.05f * i), p)
            }
        }
        doc.activeLayerIndex = doc.layers.size - 1
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val tone = FilterRegistry.byId("adjust.tone")!!
        val spec = MaskSpec(components = listOf(LinearMask(1, x0 = 0f, y0 = 0f, x1 = 0f, y1 = h.toFloat())), nextId = 2)
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), spec)!!
        for (i in 0 until above) {
            doc.layers += Layer(doc.newLayerId(), "Above $i", BitmapUtils.createLayerBitmap(w, h)).also {
                Canvas(it.bitmap).drawRect(w * 0.2f, h * (0.1f + 0.3f * i), w * 0.8f, h * (0.25f + 0.3f * i), Paint().apply { color = 0xFF7088E0.toInt() })
                if (i == 0) it.blendMode = LayerBlendMode.MULTIPLY
            }
        }
        c.notifyLayersChanged()
        // The phone's screen, the canvas fitted in it (as CanvasView: SCREEN space, docToScreen).
        val screen = Bitmap.createBitmap(1080, 2408, Bitmap.Config.ARGB_8888)
        val sc = Canvas(screen)
        val m = Matrix().apply {
            setScale(zoom, zoom)
            postTranslate((1080 - w * zoom) / 2f, (2408 - h * zoom) / 2f)
        }
        val inv = Matrix().also { m.invert(it) }
        val visF = android.graphics.RectF(0f, 0f, 1080f, 2408f).also { inv.mapRect(it) }
        val visible = Rect().also { visF.roundOut(it) }
        c.invalidateDoc(null)
        c.tiles.update(c.compositor, visible)
        fun exposure(i: Int) = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 0.3f + (i % 9) / 10f))
        val edit = AdjustmentEdit(c, adj)

        // v1.6 without a session (EXACT): every move re-renders the visible tiles (fused path).
        val exact = ArrayList<Double>()
        for (i in 1..3) {
            edit.preview(exposure(i))
            val t0 = System.nanoTime()
            c.tiles.update(c.compositor, visible)
            sc.save(); sc.concat(m); c.tiles.draw(sc, visible, true); sc.restore()
            exact += (System.nanoTime() - t0) / 1e6
        }
        // The v1.5 stage on the same tiles (directWrite = false: the Skia saveLayer path).
        val tile = BitmapUtils.createLayerBitmap(c.tiles.tileSize, c.tiles.tileSize)
        val tv = Canvas(tile)
        val tr = Rect()
        val tv0 = System.nanoTime()
        for (idx in 0 until c.tiles.tileCount) {
            c.tiles.tileRect(idx % c.tiles.cols, idx / c.tiles.cols, tr)
            if (!Rect.intersects(tr, visible)) continue
            tv.save()
            tv.translate(-tr.left.toFloat(), -tr.top.toFloat())
            tv.clipRect(tr)
            tv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            val target = CompositeTarget(tile, Matrix().apply { setTranslate(-tr.left.toFloat(), -tr.top.toFloat()) }, display = true, directWrite = false)
            c.compositor.drawDocument(tv, tr, target = target)
            tv.restore()
        }
        val v15 = (System.nanoTime() - tv0) / 1e6
        tile.recycle()
        edit.flush()

        // v1.6 live session: proxies while the finger moves.
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        val live = ArrayList<Double>()
        var first = 0.0
        for (i in 1..frames) {
            edit.preview(exposure(i + 3))
            val t0 = System.nanoTime()
            assertTrue("frame $i from the session", c.liveAdjust.drawFrame(sc, m, visible, true))
            val ms = (System.nanoTime() - t0) / 1e6
            if (i == 1) first = ms else live += ms
        }
        val scale = c.liveAdjust.proxyScale
        // The finger lifts: refinement in budgeted frames until the visible tiles are exact.
        edit.flush()
        var refineFrames = 0
        val r0 = System.nanoTime()
        while (c.liveAdjust.isActive) {
            c.liveAdjust.drawFrame(sc, m, visible, true)
            assertTrue(++refineFrames < 400)
        }
        val refineMs = (System.nanoTime() - r0) / 1e6
        assertFalse("every visible tile is exact again", (0 until c.tiles.tileCount).any {
            c.tiles.tileRect(it % c.tiles.cols, it / c.tiles.cols, tr)
            Rect.intersects(tr, visible) && c.tiles.isDirty(it)
        })
        screen.recycle()
        return LiveNumbers(v15, median(exact), first, median(live), scale, refineFrames, refineMs)
    }

    private fun report(label: String, n: LiveNumbers) {
        println(
            "[qa] $label Tone drag, full-canvas linear mask: v1.5 stage frame ${"%.1f".format(n.v15Ms)} ms, " +
                "v1.6 exact frame ${"%.1f".format(n.exactMs)} ms, live frame ${"%.1f".format(n.liveMs)} ms at s = ${n.scale} " +
                "(first ${"%.1f".format(n.firstLiveMs)} ms with the below-cache), refinement ${n.refineFrames} frames / ${"%.1f".format(n.refineMs)} ms",
        )
    }

    @Test
    fun aLiveToneDragOnThePhonesCanvasIsDrawnFromProxies() {
        // 1080 x 2408 fitted (zoom 0.685): s = 1/2, 3 layers below and 2 above (§3.1 C3).
        val n = liveDrag(1080, 2408, 0.685f, below = 3, above = 2, frames = 12)
        report("1080x2408", n)
        assertTrue("proxies at 1/2 or below (adaptive)", n.scale <= 0.5f)
        // §3.1 C3 JVM guard: a proxy frame takes at most 35 % of the exact frame of the same test.
        assertTrue("live ${n.liveMs} ms vs exact ${n.exactMs} ms", n.liveMs <= 0.35 * n.exactMs)
        assertTrue("refined in several frames, not one stall (${n.refineFrames})", n.refineFrames >= 2)
    }

    @Test
    fun aLiveToneDragOnA20MegapixelCanvas() {
        // 4000 x 5000 fitted (zoom 0.27): s = 1/4.
        val n = liveDrag(4000, 5000, 0.27f, below = 3, above = 2, frames = 8)
        report("4000x5000", n)
        assertTrue("proxies at 1/4 or below (adaptive)", n.scale <= 0.25f)
        assertTrue("live ${n.liveMs} ms vs exact ${n.exactMs} ms", n.liveMs <= 0.35 * n.exactMs)
        assertTrue(n.refineFrames >= 2)
    }
}
