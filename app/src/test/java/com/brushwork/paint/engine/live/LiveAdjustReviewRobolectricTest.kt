package com.brushwork.paint.engine.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskEdits
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.6 §3.1 C2, review fixes (policy LIVE, injected clock):
 * - a change the session didn't make (a stroke right after a slider was let go) on a display tile
 *   that was already refined renders exactly at once, instead of being shown from a proxy until
 *   refinement comes back to it;
 * - proxies at 1:1 follow the canvas's crisp-pixel display when zoomed far in (no blur that pops
 *   sharp on refinement);
 * - a heavy discrete mask change of an adjustment layer (its tiles recorded instead of a spec
 *   re-render) catches up through the session rather than one long synchronous frame, as one step.
 */
@RunWith(RobolectricTestRunner::class)
class LiveAdjustReviewRobolectricTest {
    private val w = 1000
    private val h = 800
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController
    private lateinit var photo: Layer
    private lateinit var adj: Layer
    private var now = 0L
    private val tone = FilterRegistry.byId("adjust.tone")!!

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    /** Background, photo, Tone with a radial mask over all four tiles, a Multiply band above. */
    private fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("review", "review", w, h)
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
        doc.layers += Layer(doc.newLayerId(), "Above", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(100f, 500f, 900f, 650f, Paint().apply { color = 0xFF8090F0.toInt() })
            it.blendMode = LayerBlendMode.MULTIPLY
            it.opacity = 0.8f
        }
        c.notifyLayersChanged()
        c.invalidateDoc(null)
        now = 0L
        c.liveAdjust.clock = { now }
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        c.tiles.update(c.compositor, null)
    }

    private fun spec(exposure: Float): AdjustmentSpec = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", exposure))

    private val full = Rect(0, 0, w, h)
    private val screen = Bitmap.createBitmap(500, 400, Bitmap.Config.ARGB_8888)

    /** One canvas frame as `CanvasView.onDraw` makes it; true when the session drew it. */
    private fun frame(m: Matrix = Matrix().apply { setScale(0.5f, 0.5f) }, visible: Rect = full, smooth: Boolean = true): Boolean {
        val cv = Canvas(screen)
        cv.drawColor(0, PorterDuff.Mode.CLEAR)
        if (c.liveAdjust.drawFrame(cv, m, visible, smooth)) return true
        c.tiles.update(c.compositor, visible)
        cv.save(); cv.concat(m); c.tiles.draw(cv, visible, smooth); cv.restore()
        return false
    }

    private fun screenPixels(): IntArray = IntArray(500 * 400).also { screen.getPixels(it, 0, 500, 0, 0, 500, 400) }

    private fun shown(): IntArray {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        c.tiles.draw(Canvas(out), null, smooth = false)
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    private fun exact(): IntArray {
        val t = DisplayTiles(w, h)
        t.update(c.compositor, null)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        t.draw(Canvas(out), null, smooth = false)
        t.release()
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

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

    @Test
    fun aStrokeOnATileAlreadyRefinedRendersExactlyInsteadOfFromAProxy() {
        setup()
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1f))
        // Looking at the lower right (centre 700, 550): tile 3 is the first to refine.
        val lowerRight = Rect(400, 300, 1000, 800)
        assertTrue(frame(visible = lowerRight))
        edit.settled()
        // Every tile "takes" 10 ms on this clock, more than the 8 ms budget: one tile per frame.
        c.liveAdjust.clock = { now.also { now += 10_000_000L } }
        assertTrue(frame(visible = lowerRight))
        assertFalse("tile 3 refined first", c.tiles.isDirty(3))
        assertTrue("the others wait", c.tiles.isDirty(0) && c.tiles.isDirty(1) && c.tiles.isDirty(2))
        assertTrue(c.liveAdjust.isActive)

        // A stroke on the photo (below the adjustment) inside tile 3, while refinement goes on.
        val r = Rect(700, 560, 800, 660)
        val rec = c.beginEdit(photo, EditTarget.CONTENT)
        rec.touch(r)
        Canvas(photo.bitmap).drawRect(RectF(r), Paint().apply { color = 0xFF101010.toInt() })
        assertTrue(c.commitEdit(rec, "Paint"))
        assertTrue(c.tiles.isDirty(3))

        // The whole canvas in view (centre 500, 400): tile 0 is the nearest left to refine. The
        // stroke's tile is no session tile any more: it renders exactly in this very frame.
        assertTrue("still a session frame", frame())
        assertFalse("the stroke's tile rendered exactly at once", c.tiles.isDirty(3))
        assertTrue("refinement goes on elsewhere", c.liveAdjust.isActive)
        val ex = exact(); val sh = shown()
        for (y in 512 until h) for (x in 512 until w) assertEquals("tile 3 at ($x, $y)", ex[y * w + x], sh[y * w + x])
        assertConverged("stroke on a refined tile")
    }

    @Test
    fun oneToOneProxiesAreCrispWhenTheCanvasShowsCrispPixels() {
        setup()
        // Zoomed in 4x on the green rectangle's left edge and the Multiply band's top edge, inside
        // the radial (the canvas draws crisp pixels there: smooth = false).
        val m = Matrix().apply { setScale(4f, 4f); postTranslate(-4f * 560f, -4f * 450f) }
        val vis = Rect(560, 450, 685, 550)
        val edit = AdjustmentEdit(c, adj)
        edit.preview(spec(1.2f))
        assertTrue(frame(m, vis, smooth = false))
        assertEquals("zoomed in: proxies at 1:1", 1f, c.liveAdjust.proxyScale, 0f)
        val proxyFrame = screenPixels()
        edit.flush()
        drain()
        assertFalse(frame(m, vis, smooth = false))
        val crisp = screenPixels()
        assertArrayEquals("the 1:1 proxy frame is the exact crisp frame", crisp, proxyFrame)
        // The view has edges where filtering would show: the comparison above means something.
        assertFalse(frame(m, vis, smooth = true))
        assertFalse("filtered and crisp frames differ here", crisp.contentEquals(screenPixels()))
    }

    @Test
    fun aHeavyMaskChangeOfAnAdjustmentLayerRefinesThroughTheSessionAsOneStep() {
        setup()
        // A painted mask (the spec goes with the paint, I1): replacing it with an editable one
        // records the mask tiles (MaskEdits' heavy path).
        assertTrue(c.editWholeLayer(adj, "Paint mask", EditTarget.MASK) { b -> Canvas(b).drawRect(0f, 0f, 300f, 300f, Paint().apply { color = -1 }) })
        assertNull(adj.maskSpec)
        frame()
        assertFalse(c.liveAdjust.isActive)
        val steps = c.undoManager.undoCount
        val next = MaskSpec(components = listOf(RadialMask(1, cx = 300f, cy = 500f, rx = 250f, ry = 200f, feather = 0.3f)), nextId = 2)
        assertTrue(MaskEdits.apply(c, adj, next, "Replace mask"))
        assertNotNull(adj.maskSpec)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("Replace mask", c.undoManager.undoLabel)
        assertTrue("the canvas catches up through a session", c.liveAdjust.isActive)
        assertTrue("refining at once (a discrete change)", c.liveAdjust.wantsFrame)
        // Every tile "takes" 10 ms on this clock: one refined per frame, the rest from proxies.
        c.liveAdjust.clock = { now.also { now += 10_000_000L } }
        assertTrue(frame())
        assertTrue("drawn from proxies first", c.liveAdjust.proxyBytes > 0)
        assertTrue("refinement left for later frames", c.liveAdjust.wantsFrame)
        c.liveAdjust.clock = { now }
        assertConverged("heavy mask change")
        c.undo()
        assertNull(adj.maskSpec)
        frame()
        assertConverged("undo")
    }
}
