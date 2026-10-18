package com.brushwork.paint.v19.a7b

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
import com.brushwork.paint.engine.GovernorPolicy
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.engine.live.StrokeBelowCache
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.ui.common.StabilityLabels19
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.9 A7b step 6 (design 3.2 A7b, after A6's merge): A6's caches as entries of the
 * [GovernorPolicyV19] the app runs with. Their priorities are the policy's levels (display tiles
 * [GovernorPolicyV19.PRIORITY_DISPLAY], live proxies [GovernorPolicyV19.PRIORITY_PROXIES], a
 * stroke's below-cache [GovernorPolicyV19.PRIORITY_BELOW], the undo history
 * [GovernorPolicyV19.PRIORITY_UNDO]); the system's LOW trim frees the display tiles and the
 * proxies and keeps the below-cache and the history; CRITICAL frees the below-cache too, then the
 * oldest undo steps while the memory in use is over 70 %.
 */
@Suppress("DEPRECATION") // the trim levels are what Android still delivers
@RunWith(RobolectricTestRunner::class)
class GovernorRegistrationsV19Test {
    private val w = 512
    private val h = 384
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = ArrayList<EditorController>()
    private val tone = FilterRegistry.byId("adjust.tone")!!
    private lateinit var before: List<MemoryGovernor.Entry>
    private lateinit var savedPolicy: GovernorPolicy
    private lateinit var savedHeap: GovernorPolicyV19.Heap

    @Before
    fun setUp() {
        savedPolicy = MemoryGovernor.policy
        savedHeap = GovernorPolicyV19.heap
        MemoryGovernor.policy = GovernorPolicyV19
        before = MemoryGovernor.entries()
    }

    @After
    fun tearDown() {
        made.forEach { it.dispose() }
        MemoryGovernor.policy = savedPolicy
        GovernorPolicyV19.heap = savedHeap
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

    /** A live frame at 50 %: the session draws it from its proxies. */
    private fun liveFrame(c: EditorController): Boolean =
        c.liveAdjust.drawFrame(Canvas(Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)), Matrix().apply { setScale(0.5f, 0.5f) }, Rect(0, 0, w, h), true)

    @Test
    fun theEditorsCachesSitAtThePolicysLevels() {
        assertEquals(GovernorPolicyV19.PRIORITY_PROXIES, LiveAdjust.PROXY_PRIORITY)
        assertEquals(GovernorPolicyV19.PRIORITY_BELOW, StrokeBelowCache.BELOW_PRIORITY)
        val (c, adj) = controller()
        c.liveAdjust.clock = { 0L }
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        AdjustmentEdit(c, adj).preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 1.1f)))
        assertTrue(liveFrame(c))
        val entries = mine()
        assertEquals(GovernorPolicyV19.PRIORITY_DISPLAY, entries.single { it.name == "display tiles" }.priority)
        assertEquals(GovernorPolicyV19.PRIORITY_PROXIES, entries.single { it.name == "live proxies" }.priority)
        assertEquals(GovernorPolicyV19.PRIORITY_UNDO, entries.single { it.name == GovernorPolicyV19.UNDO_ENTRY }.priority)
        assertEquals(
            "trimmed in this order",
            listOf("display tiles", "live proxies", GovernorPolicyV19.UNDO_ENTRY),
            entries.map { it.name },
        )
    }

    @Test
    fun lowFreesTheDisplayTilesAndTheProxiesThenCriticalTheOldestUndoSteps() {
        val (c, adj) = controller()
        c.liveAdjust.clock = { 0L }
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        AdjustmentEdit(c, adj).preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 1.1f)))
        assertTrue(liveFrame(c))
        assertTrue(c.tiles.heldBytes > 0L)
        assertTrue(c.liveAdjust.proxyBytes > 0L)
        // The history's steps hold 360 of the 900 bytes in use: over 70 % of 1000.
        val heap = FakeHeap(1000L, 540L, native = 360L)
        GovernorPolicyV19.heap = heap
        // Only these steps in the history (the adjustment layer's own step would count its real bytes).
        c.undoManager.clear()
        val steps0 = c.undoManager.undoCount
        (1..12).forEach { c.undoManager.push(HeapStep("Step $it", heap, 30L)) }
        c.message = null

        MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals("LOW frees the display tiles", 0L, c.tiles.heldBytes)
        assertEquals("and the proxies", 0L, c.liveAdjust.proxyBytes)
        assertFalse(c.liveAdjust.isActive)
        assertEquals("LOW keeps the history", steps0 + 12, c.undoManager.undoCount)
        assertNull(c.message)

        MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertTrue("CRITICAL drops the oldest steps", c.undoManager.undoCount < steps0 + 12)
        assertTrue("under 70 % again", heap.used + heap.native < 700L)
        assertEquals(StabilityLabels19.UNDO_TRIMMED, c.message)
    }

    @Test
    fun aStrokesBelowCacheStaysAtLowAndGoesAtCritical() {
        val wasOn = StrokeBelowCache.strokesOn
        StrokeBelowCache.strokesOn = true
        try {
            belowCacheUnderPressure()
        } finally {
            StrokeBelowCache.strokesOn = wasOn
        }
    }

    private fun belowCacheUnderPressure() {
        val (c, _) = controller()
        // Memory in use well under 70 %: CRITICAL keeps the history.
        GovernorPolicyV19.heap = FakeHeap(1000L, 100L, native = 100L)
        val cache = c.liveAdjust.strokeBelow
        c.tools
        c.selectLayer(c.doc.layers.indexOfFirst { it.name == "Photo" })
        c.selectTool(ToolId.BRUSH)
        fun pt(k: Int) = ToolPoint(60f + 12f * k, 120f + 4f * k, 1f, k * 8L)
        c.pointerDown(pt(0))
        for (k in 1..8) c.pointerMove(pt(k))
        c.tiles.update(c.compositor, null)
        val held = cache.bytes
        assertTrue("the stroke holds a below-cache", held > 0L)
        val entry = mine().single { it.name == "below-caches" }
        assertEquals(GovernorPolicyV19.PRIORITY_BELOW, entry.priority)
        val steps = c.undoManager.undoCount

        MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals("LOW keeps the below-cache", held, cache.bytes)
        assertTrue(mine().any { it === entry })

        MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals("CRITICAL frees it", 0L, cache.bytes)
        assertTrue("its entry went with it", mine().none { it.name == "below-caches" })
        assertEquals("under 70 %: the history stays", steps, c.undoManager.undoCount)

        // The stroke goes on and lands.
        for (k in 9..16) c.pointerMove(pt(k))
        c.pointerUp(pt(17))
        c.tiles.update(c.compositor, null)
        assertFalse(cache.isActive)
        assertTrue(mine().none { it.name == "below-caches" })
    }
}
