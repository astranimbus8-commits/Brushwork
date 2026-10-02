package com.brushwork.paint.engine.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.mask.AdjustmentEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * v1.6 §3.1 C2 / §3.1d: live adjustment sessions (policy LIVE, injected clock). While a live edit
 * runs, the area it changes is drawn from proxy tiles over a per-frame-keyed below-cache; after
 * `end` and draining `refineStep()`, every display tile equals a no-session render bit for bit
 * (I7). Undo, a stroke on a layer below, hiding a layer above and an opacity change during
 * refinement all converge; over the memory cap the v1.5 path runs; EXACT (Robolectric's
 * default) and "Fast adjustment preview" off start no session.
 */
@RunWith(RobolectricTestRunner::class)
class LiveAdjustSessionRobolectricTest {
    private val w = 1000
    private val h = 800
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController
    private lateinit var photo: Layer
    private lateinit var adj: Layer
    private lateinit var above: Layer
    private var now = 0L
    private val tone = FilterRegistry.byId("adjust.tone")!!

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    /** Background, photo (semi-transparent parts), Tone with a radial mask, a Multiply layer above. */
    private fun setup(live: Boolean = true) {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("live", "live", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF204070.toInt(), 0xFFE0B070.toInt(), Shader.TileMode.CLAMP) })
        }
        photo = Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            val cv = Canvas(it.bitmap)
            cv.drawCircle(420f, 380f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0E05030.toInt() })
            cv.drawRect(600f, 100f, 950f, 700f, Paint().apply { color = 0xFF30A060.toInt() })
        }
        doc.layers += photo
        doc.activeLayerIndex = 1
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val mask = MaskSpec(components = listOf(RadialMask(1, cx = 480f, cy = 400f, rx = 380f, ry = 300f, feather = 0.5f)), nextId = 2)
        adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), mask)!!
        above = Layer(doc.newLayerId(), "Above", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(100f, 500f, 900f, 650f, Paint().apply { color = 0xFF8090F0.toInt() })
            it.blendMode = LayerBlendMode.MULTIPLY
            it.opacity = 0.8f
        }
        doc.layers += above
        c.notifyLayersChanged()
        c.invalidateDoc(null)
        now = 0L
        c.liveAdjust.clock = { now }
        if (live) c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        // What the canvas showed before the edit: every tile rendered.
        c.tiles.update(c.compositor, null)
    }

    private fun spec(exposure: Float): AdjustmentSpec = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", exposure))

    // ------------------------------------------------------------------ frames and comparisons

    private val visible = Rect(0, 0, 1000, 800)
    private val screen = Bitmap.createBitmap(500, 400, Bitmap.Config.ARGB_8888)
    private fun view(zoom: Float) = Matrix().apply { setScale(zoom, zoom) }

    /** One canvas frame at [zoom] as `CanvasView.onDraw` makes it; true when the session drew it. */
    private fun frame(zoom: Float = 0.5f): Boolean {
        val cv = Canvas(screen)
        cv.drawColor(0, PorterDuff.Mode.CLEAR)
        val m = view(zoom)
        if (c.liveAdjust.drawFrame(cv, m, visible, true)) return true
        c.tiles.update(c.compositor, visible)
        cv.save(); cv.concat(m); c.tiles.draw(cv, visible, true); cv.restore()
        return false
    }

    private fun screenPixels(): IntArray = IntArray(500 * 400).also { screen.getPixels(it, 0, 500, 0, 0, 500, 400) }

    /** The display tiles as they are, drawn 1:1. */
    private fun shown(): IntArray {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        c.tiles.draw(Canvas(out), null, smooth = false)
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /** What display tiles show without any session: fresh tiles rendered by the compositor. */
    private fun exact(): IntArray {
        val t = DisplayTiles(w, h)
        t.update(c.compositor, null)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        t.draw(Canvas(out), null, smooth = false)
        t.release()
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /** Refines until the session ends (bounded). */
    private fun drain() {
        var n = 0
        while (c.liveAdjust.refineStep()) assertTrue("refinement converges", ++n < 64)
        assertFalse("the session ended", c.liveAdjust.isActive)
    }

    private fun assertConverged(what: String) {
        drain()
        assertFalse("$what: no visible tile left dirty", c.tiles.hasDirty)
        assertArrayEquals("$what: I7, display tiles equal a no-session render bit for bit", exact(), shown())
    }

    private fun meanDiff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) for (sh in 0..24 step 8) s += abs((a[i] shr sh and 0xFF) - (b[i] shr sh and 0xFF))
        return s / (a.size * 4.0)
    }

    private fun sessionTiles(): List<Int> = (0 until c.tiles.tileCount).filter { c.tiles.isDirty(it) }

    // ------------------------------------------------------------------ tests

    @Test
    fun aSliderDragIsDrawnFromProxiesAndRefinesToExactBitForBit() {
        setup()
        val before = run { frame(); screenPixels() }
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(0.6f))
        assertTrue("a session started", c.liveAdjust.isActive)
        edit.preview(spec(1.2f))
        assertTrue("the session draws the frame", frame())
        assertEquals("fitted at 50 %: proxies at ½", 0.5f, c.liveAdjust.proxyScale, 0f)
        assertTrue(c.liveAdjust.proxyBytes > 0)
        // While the finger moves the session's display tiles are not rendered (the proxy covers them).
        assertTrue("session tiles wait for refinement", sessionTiles().isNotEmpty())
        assertFalse(c.liveAdjust.wantsFrame)
        val proxyFrame = screenPixels()

        edit.flush()
        assertTrue("refining after end", c.liveAdjust.wantsFrame)
        assertConverged("slider drag")
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)
        frame()
        val exactFrame = screenPixels()
        // The proxy frame showed the new effect (close to the exact frame, far from the old one).
        val toExact = meanDiff(proxyFrame, exactFrame)
        val toOld = meanDiff(before, exactFrame)
        assertTrue("proxy frame ≈ exact ($toExact) vs the old frame ($toOld)", toExact < toOld / 4 && toExact < 2.0)
    }

    @Test
    fun aPartialChangeDrawsTheTilesCleanPartAndTheProxysChangedPart() {
        setup()
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1f))
        frame()
        edit.flush()
        assertConverged("first drag")
        frame()
        val old = screenPixels()
        // A small mask change inside one tile, live (as MaskPreview does: touch, no end yet): its
        // tile is only partly dirty.
        val moved = MaskSpec(components = listOf(RadialMask(1, cx = 480f, cy = 400f, rx = 380f, ry = 300f, feather = 0.5f), RadialMask(2, cx = 150f, cy = 150f, rx = 60f, ry = 60f)), nextId = 3)
        val reg = MaskSpecs.changedRegion(adj.maskSpec!!, moved, w, h)!!
        MaskSpecs.renderInto(adj.mask!!, moved, reg)
        adj.maskSpec = moved
        c.liveAdjust.touch(adj, reg)
        assertTrue(c.liveAdjust.isActive)
        assertEquals("one tile", listOf(0), sessionTiles())
        val d = c.tiles.dirtyRect(0)!!
        assertNotEquals("partly dirty", c.tiles.tileRect(0, 0), d)
        assertTrue(frame())
        assertTrue("still waiting for refinement", c.tiles.isDirty(0))
        // Outside the changed part (a margin for the filtered edge) the frame is exactly the old
        // one: the clean part of tile 0 comes from the tile itself, the other tiles are untouched.
        val cur = screenPixels()
        val margin = 3
        val sx0 = d.left / 2 - margin; val sy0 = d.top / 2 - margin; val sx1 = (d.right + 1) / 2 + margin; val sy1 = (d.bottom + 1) / 2 + margin
        var outside = 0
        for (y in 0 until 400) for (x in 0 until 500) {
            if (x in sx0 until sx1 && y in sy0 until sy1) continue
            assertEquals("screen ($x, $y) outside the change", old[y * 500 + x], cur[y * 500 + x])
            outside++
        }
        assertTrue(outside > 150_000)
        // Inside, the proxy shows the new radial (the area got brighter).
        var inside = 0.0; var before = 0.0
        for (y in 70 until 80) for (x in 70 until 80) { inside += cur[y * 500 + x] and 0xFF; before += old[y * 500 + x] and 0xFF }
        assertTrue("the changed part shows the new mask ($inside vs $before)", inside > before)
        c.liveAdjust.end(adj)
        assertConverged("partial change")
    }

    @Test
    fun theBelowCacheIsKeptWhileOnlyTheAdjustmentChanges() {
        setup()
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(0.5f))
        frame()
        val builds = c.liveAdjust.belowBuilds
        assertTrue("built once for the first frame", builds > 0)
        for (i in 1..5) {
            edit.preview(spec(0.5f + i / 10f))
            frame()
        }
        assertEquals("no rebuild while only the adjustment moves", builds, c.liveAdjust.belowBuilds)
        // A stroke on a layer below (a foreign change; it records the pending adjustment step
        // first): the next live frame draws the cache again.
        assertTrue(c.editWholeLayer(photo, "Paint") { b -> Canvas(b).drawRect(300f, 300f, 500f, 450f, Paint().apply { color = 0xFF101010.toInt() }) })
        edit.preview(spec(1.3f))
        assertTrue(frame())
        assertTrue("rebuilt after a change below", c.liveAdjust.belowBuilds > builds)
        edit.flush()
        assertConverged("stroke below")
    }

    @Test
    fun undoDuringASessionConverges() {
        setup()
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(0.8f))
        frame()
        edit.flush()
        val a = adj.adjustment
        edit.preview(spec(-1f))
        frame()
        // Undo records the pending change first, then takes it back.
        c.undo()
        assertEquals(a, adj.adjustment)
        assertTrue(c.liveAdjust.isActive)
        frame()
        assertConverged("undo")
        c.redo()
        frame()
        assertConverged("redo")
    }

    @Test
    fun hidingALayerAboveDuringASessionConverges() {
        setup()
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1f))
        frame()
        c.setLayerProps(above, above.props().copy(visible = false), "Hide layer")
        frame()
        edit.preview(spec(1.4f))
        frame()
        edit.flush()
        assertConverged("hidden layer above")
    }

    @Test
    fun anOpacityChangeDuringRefinementConverges() {
        setup()
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1f))
        frame()
        edit.settled()
        // One budgeted slice (each tile "takes" 10 ms on this clock), then the Amount moves again.
        c.liveAdjust.clock = { now.also { now += 10_000_000L } }
        assertTrue("work left after one slice", c.liveAdjust.refineStep(1L))
        assertTrue(c.liveAdjust.wantsFrame)
        edit.preview(adj.adjustment, 0.4f, adj.name)
        assertFalse("moving again stops refinement", c.liveAdjust.wantsFrame)
        frame()
        edit.flush()
        assertConverged("opacity during refinement")
        assertEquals(0.4f, adj.opacity, 0f)
    }

    @Test
    fun refinementStartsWhenTheFingerRestsAndWorksCentreOutWithinTheBudget() {
        setup()
        c.liveAdjust.refineBudgetNanos = 8_000_000L
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1f))
        frame()
        val waiting = sessionTiles().size
        assertEquals("the radial covers all four tiles", 4, waiting)
        frame()
        assertEquals("no refinement while the finger may still move", waiting, sessionTiles().size)
        // The finger rests 150 ms: the next frame refines. Each tile "takes" 10 ms on this clock,
        // more than the 8 ms budget: one tile per frame, nearest the view centre first.
        now += LiveAdjust.IDLE_NANOS
        c.liveAdjust.clock = { now.also { now += 10_000_000L } }
        frame()
        assertTrue(c.liveAdjust.wantsFrame)
        assertEquals("one tile refined", waiting - 1, sessionTiles().size)
        assertFalse("the tile nearest the centre first", c.tiles.isDirty(0))
        frame()
        assertFalse("then the next nearest", c.tiles.isDirty(1))
        assertTrue(c.tiles.isDirty(2) && c.tiles.isDirty(3))
        var frames = 1
        while (c.liveAdjust.isActive) {
            frame()
            assertTrue(++frames < 32)
        }
        assertTrue("several frames, not one long stall", frames >= 2)
        assertArrayEquals(exact(), shown())
        edit.flush()
    }

    @Test
    fun aSlowFrameHalvesTheProxyScaleAndAPinchRebuildsIt() {
        setup()
        c.liveAdjust.slowFrameNanos = 0L
        c.liveAdjust.clock = { now.also { now += 1_000_000L } }
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1f))
        frame()
        assertEquals(0.5f, c.liveAdjust.proxyScale, 0f)
        assertTrue("another frame at the lower scale", c.liveAdjust.wantsFrame)
        frame()
        assertEquals(0.25f, c.liveAdjust.proxyScale, 0f)
        frame(); frame()
        assertEquals("never below ⅛", 0.125f, c.liveAdjust.proxyScale, 0f)
        edit.flush()
        drain()

        // A new drag; zoom changes pick the scale (1 when zoomed in, ⅛ far out).
        c.liveAdjust.slowFrameNanos = Long.MAX_VALUE
        edit.preview(spec(0.3f))
        frame(1f)
        assertEquals(1f, c.liveAdjust.proxyScale, 0f)
        edit.preview(spec(0.4f))
        frame(0.1f)
        assertEquals(0.125f, c.liveAdjust.proxyScale, 0f)
        edit.flush()
        assertConverged("pinch")
    }

    @Test
    fun overTheMemoryCapTheV15PathRuns() {
        setup()
        c.liveAdjust.memoryCapBytes = 4096L
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1f))
        assertTrue(c.liveAdjust.isActive)
        assertFalse("refused: the caller renders exactly", frame())
        assertFalse(c.liveAdjust.isActive)
        assertArrayEquals("the exact path drew it", exact(), shown())
        // The rest of the drag stays on the v1.5 path.
        edit.preview(spec(1.1f))
        assertFalse(c.liveAdjust.isActive)
        assertFalse(frame())
        edit.flush()
        // A new drag under a sane cap gets a session again.
        c.liveAdjust.memoryCapBytes = LiveAdjust.MEMORY_CAP_BYTES
        edit.preview(spec(0.2f))
        assertTrue(c.liveAdjust.isActive)
        edit.flush()
        assertConverged("after the cap")
    }

    @Test
    fun exactPolicyAndTheSettingOffStartNoSession() {
        setup(live = false)
        assertEquals("I8: EXACT under Robolectric", LiveAdjust.Policy.EXACT, c.liveAdjust.policy)
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1f))
        assertFalse(c.liveAdjust.isActive)
        assertFalse(frame())
        edit.flush()
        assertArrayEquals(exact(), shown())

        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        c.liveAdjust.fastPreview = false
        assertFalse("saved", c.settings.fastAdjustPreview)
        edit.preview(spec(0.5f))
        assertFalse(c.liveAdjust.isActive)
        edit.flush()
        // On again; turning it off mid-session ends the session (the next frame is exact).
        c.liveAdjust.fastPreview = true
        assertTrue(c.settings.fastAdjustPreview)
        edit.preview(spec(0.7f))
        assertTrue(c.liveAdjust.isActive)
        c.liveAdjust.fastPreview = false
        assertFalse(c.liveAdjust.isActive)
        assertFalse(frame())
        assertArrayEquals(exact(), shown())
        edit.flush()
    }

    @Test
    fun proxiesNeverReachExportsOrThumbnails() {
        setup()
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1.1f))
        frame()
        assertTrue(c.liveAdjust.isActive)
        val flatDuring = c.compositor.renderFlattened()
        val thumbDuring = c.compositor.renderThumbnail(256)
        edit.flush()
        drain()
        val flatAfter = c.compositor.renderFlattened()
        val thumbAfter = c.compositor.renderThumbnail(256)
        assertTrue("flattened image unchanged by the session", flatDuring.sameAs(flatAfter))
        assertTrue("thumbnail unchanged by the session", thumbDuring.sameAs(thumbAfter))
    }
}
