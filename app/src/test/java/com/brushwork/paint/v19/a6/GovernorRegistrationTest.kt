package com.brushwork.paint.v19.a6

import android.content.ComponentCallbacks2
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
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.engine.live.WeakRegistration
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_DESCRIPTION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.9 A6 step 6 (design 2.4.3): the editor's display tiles (priority 0) and a live adjustment's
 * proxies (priority 10) are entries of the [MemoryGovernor] registry: their bytes are what they
 * hold, a pressure above NORMAL frees them (the canvas then draws the same pixels again), and the
 * proxies' entry lives only while they do. The default policy trims nothing (v1.8, I8). The
 * entries hold their owners weakly. The "Fast adjustment preview" switch reads R13's note.
 * Step 9 (P3): a stroke's below-cache (priority 20) while it holds tiles.
 */
@RunWith(RobolectricTestRunner::class)
class GovernorRegistrationTest {
    private val w = 512
    private val h = 384
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = ArrayList<EditorController>()
    private val tone = FilterRegistry.byId("adjust.tone")!!
    private lateinit var before: List<MemoryGovernor.Entry>

    @Before
    fun setUp() {
        MemoryGovernor.policy = MemoryGovernor.DefaultPolicy
        before = MemoryGovernor.entries()
    }

    @After
    fun tearDown() {
        made.forEach { it.dispose() }
        MemoryGovernor.policy = MemoryGovernor.DefaultPolicy
        scope.cancel()
    }

    /** A gradient background, a photo and a Tone adjustment with a radial mask over them. */
    private fun controller(): Pair<EditorController, Layer> {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("g", "g", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF204070.toInt(), 0xFFE0B070.toInt(), Shader.TileMode.CLAMP) })
        }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawCircle(220f, 190f, 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0E05030.toInt() })
        }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        made += c
        val mask = MaskSpec(components = listOf(RadialMask(1, cx = 250f, cy = 190f, rx = 190f, ry = 150f, feather = 0.5f)), nextId = 2)
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), mask)!!
        c.notifyLayersChanged()
        c.invalidateDoc(null)
        c.tiles.update(c.compositor, null)
        return c to adj
    }

    /** The entries registered since [before] (other tests of the JVM may have left theirs). */
    private fun mine(): List<MemoryGovernor.Entry> = MemoryGovernor.entries().filter { e -> before.none { it === e } }

    private fun shown(c: EditorController): IntArray {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        c.tiles.draw(Canvas(out), null, smooth = false)
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /** A live frame at 50 %: the session draws it from its proxies. */
    private fun liveFrame(c: EditorController): Boolean =
        c.liveAdjust.drawFrame(Canvas(Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)), Matrix().apply { setScale(0.5f, 0.5f) }, Rect(0, 0, w, h), true)

    @Test
    fun theDisplayTilesAreAPriority0EntryThatFreesAndDrawsAgain() {
        val (c, _) = controller()
        val entry = mine().single { it.name == "display tiles" }
        assertEquals(0, entry.priority)
        assertEquals("one 512 x 384 tile", w * h * 4L, entry.bytes())
        assertEquals(c.tiles.heldBytes, entry.bytes())
        val pixels = shown(c)
        var redraws = 0
        c.onInvalidate = { redraws++ }
        assertEquals("NORMAL frees nothing", 0L, entry.trimmable.trim(MemoryPressure.NORMAL))
        assertEquals(w * h * 4L, entry.bytes())
        assertEquals(0, redraws)

        assertEquals("MODERATE frees the tiles", w * h * 4L, entry.trimmable.trim(MemoryPressure.MODERATE))
        assertEquals(0L, entry.bytes())
        assertTrue("everything is drawn again", c.tiles.hasDirty)
        assertEquals("the canvas asked for a frame", 1, redraws)
        assertEquals("nothing left to free", 0L, entry.trimmable.trim(MemoryPressure.CRITICAL))
        c.tiles.update(c.compositor, null)
        assertEquals(w * h * 4L, entry.bytes())
        assertArrayEquals("the same pixels again", pixels, shown(c))

        made.remove(c)
        c.dispose()
        assertEquals("a disposed editor holds nothing", 0L, entry.bytes())
    }

    @Test
    fun theLiveProxiesAreAPriority10EntryWhileTheyExist() {
        val (c, adj) = controller()
        c.liveAdjust.clock = { 0L }
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        assertTrue("no proxies, no entry", mine().none { it.name == "live proxies" })
        val edit = AdjustmentEdit(c, adj)
        edit.preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 1.1f)))
        assertTrue(liveFrame(c))
        val held = c.liveAdjust.proxyBytes
        assertTrue(held > 0L)
        val entry = mine().single { it.name == "live proxies" }
        assertEquals(LiveAdjust.PROXY_PRIORITY, entry.priority)
        assertEquals(held, entry.bytes())
        assertEquals("display caches go first", listOf("display tiles", "live proxies"), mine().map { it.name })

        assertEquals("NORMAL frees nothing", 0L, entry.trimmable.trim(MemoryPressure.NORMAL))
        assertEquals("MODERATE keeps a running session", 0L, entry.trimmable.trim(MemoryPressure.MODERATE))
        assertTrue(c.liveAdjust.isActive)
        assertEquals("LOW ends it", held, entry.trimmable.trim(MemoryPressure.LOW))
        assertFalse(c.liveAdjust.isActive)
        assertEquals(0L, c.liveAdjust.proxyBytes)
        assertTrue("the entry went with the proxies", mine().none { it.name == "live proxies" })
        edit.flush()

        // Proxies kept after a session (for the next drag) go at MODERATE.
        val again = AdjustmentEdit(c, adj)
        again.preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 0.7f)))
        assertTrue(liveFrame(c))
        again.flush()
        while (c.liveAdjust.refineStep()) Unit
        assertFalse(c.liveAdjust.isActive)
        val kept = c.liveAdjust.proxyBytes
        assertTrue("kept for the next drag", kept > 0L)
        val second = mine().single { it.name == "live proxies" }
        assertEquals(kept, second.trimmable.trim(MemoryPressure.MODERATE))
        assertEquals(0L, c.liveAdjust.proxyBytes)
        assertTrue(mine().none { it.name == "live proxies" })

        // release() closes it too.
        AdjustmentEdit(c, adj).preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 0.4f)))
        assertTrue(liveFrame(c))
        assertTrue(mine().any { it.name == "live proxies" })
        c.liveAdjust.release()
        assertTrue(mine().none { it.name == "live proxies" })
    }

    @Test
    fun theDefaultPolicyTrimsNothing() {
        val (c, adj) = controller()
        c.liveAdjust.clock = { 0L }
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        AdjustmentEdit(c, adj).preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 1.1f)))
        assertTrue(liveFrame(c))
        val tiles = c.tiles.heldBytes
        val proxies = c.liveAdjust.proxyBytes
        @Suppress("DEPRECATION")
        for (level in intArrayOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)) {
            MemoryGovernor.onTrimMemory(level)
        }
        assertEquals("v1.8: the tiles stay", tiles, c.tiles.heldBytes)
        assertEquals("v1.8: the proxies stay", proxies, c.liveAdjust.proxyBytes)
        assertTrue(c.liveAdjust.isActive)
    }

    @Test
    fun anOwnerCollectedWithoutClosingReadsAsNothingAndItsEntryGoes() {
        var owner: Any? = Any()
        WeakRegistration.register(owner!!, "a6 probe", 99, { 7L }, { _, _ -> 7L })
        val entry = mine().single { it.name == "a6 probe" }
        assertEquals(7L, entry.bytes())
        @Suppress("UNUSED_VALUE")
        owner = null
        var cleared = false
        for (i in 0 until 50) {
            System.gc()
            if (entry.bytes() == 0L) { cleared = true; break }
            Thread.sleep(10)
        }
        assumeTrue("the JVM collected the owner", cleared)
        assertEquals("a collected owner frees nothing", 0L, entry.trimmable.trim(MemoryPressure.CRITICAL))
        // The next registration removes the stale entry (once the JVM has queued its reference).
        var gone = false
        for (i in 0 until 50) {
            WeakRegistration.register(this, "a6 probe 2", 99, { 0L }, { _, _ -> 0L }).close()
            if (MemoryGovernor.entries().none { it === entry }) { gone = true; break }
            Thread.sleep(10)
        }
        assertTrue("the stale entry went", gone)
    }

    /**
     * v1.9 A6 step 9 (P3, turned on here: it ships off): a stroke's below-cache is a priority 20
     * entry while it holds tiles; a pressure above NORMAL frees it, the rest of the stroke
     * composites as before, with the same pixels, and nothing is left after the lift.
     */
    @Test
    fun theStrokeBelowCacheIsAPriority20EntryWhileAStrokeHoldsIt() {
        val wasOn = com.brushwork.paint.engine.live.StrokeBelowCache.strokesOn
        com.brushwork.paint.engine.live.StrokeBelowCache.strokesOn = true
        try {
            strokeBelowEntry()
        } finally {
            com.brushwork.paint.engine.live.StrokeBelowCache.strokesOn = wasOn
        }
    }

    private fun strokeBelowEntry() {
        val (c, _) = controller()
        val cache = c.liveAdjust.strokeBelow
        c.tools
        c.selectLayer(c.doc.layers.indexOfFirst { it.name == "Photo" })
        c.selectTool(com.brushwork.paint.tools.ToolId.BRUSH)
        assertTrue("no stroke, no entry", mine().none { it.name == "below-caches" })
        fun pt(k: Int) = com.brushwork.paint.tools.ToolPoint(60f + 12f * k, 120f + 4f * k, 1f, k * 8L)
        c.pointerDown(pt(0))
        for (k in 1..8) c.pointerMove(pt(k))
        c.tiles.update(c.compositor, null)
        assertTrue("the frame came from the cache", cache.hits > 0)
        val held = cache.bytes
        assertTrue(held > 0L)
        val entry = mine().single { it.name == "below-caches" }
        assertEquals(com.brushwork.paint.engine.live.StrokeBelowCache.BELOW_PRIORITY, entry.priority)
        assertEquals(20, entry.priority)
        assertEquals(held, entry.bytes())
        assertEquals("NORMAL frees nothing", 0L, entry.trimmable.trim(MemoryPressure.NORMAL))
        assertEquals("MODERATE frees it", held, entry.trimmable.trim(MemoryPressure.MODERATE))
        assertEquals(0L, cache.bytes)
        assertTrue("the entry went with it", mine().none { it.name == "below-caches" })
        val hits = cache.hits
        for (k in 9..16) c.pointerMove(pt(k))
        c.tiles.update(c.compositor, null)
        assertEquals("the rest of the stroke composites as before", hits, cache.hits)
        assertEquals(0L, cache.bytes)
        c.pointerUp(pt(17))
        c.tiles.update(c.compositor, null)
        val fresh = com.brushwork.paint.engine.DisplayTiles(w, h).also { it.update(c.compositor, null) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fresh.draw(Canvas(out), null, smooth = false)
        assertArrayEquals("the same pixels", IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }, shown(c))
        assertFalse(cache.isActive)
        assertTrue(mine().none { it.name == "below-caches" })
    }

    @Test
    fun theFastAdjustmentPreviewSwitchReadsR13sNote() {
        assertEquals(StabilityLabels19.FAST_ADJUST_NOTE, FAST_ADJUST_PREVIEW_DESCRIPTION)
    }
}
