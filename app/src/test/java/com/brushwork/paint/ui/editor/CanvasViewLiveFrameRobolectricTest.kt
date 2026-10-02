package com.brushwork.paint.ui.editor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.TransparencyDisplay
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.6 (area A): the canvas view's frame. The transparency squares follow
 * `AppSettings.transparencyDisplay` through a cached value refreshed by a preferences listener
 * (the review: no SharedPreferences read per frame), and a live adjustment session draws the
 * frame itself (`LiveAdjust.drawFrame`), asks for frames while it refines, and leaves the canvas
 * exactly as a no-session frame once it ends.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasViewLiveFrameRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController
    private lateinit var view: CanvasView
    private lateinit var settings: AppSettings

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    /** An attached canvas view (listeners registered) of a [w] x [h] document. */
    private fun setup(w: Int = 400, h: Int = 300, content: (Document) -> Unit = {}) {
        val app = RuntimeEnvironment.getApplication()
        settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("v", "v", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        content(doc)
        c = EditorController(app, doc, scope, settings)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = CanvasView(activity, c)
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(800, 600)) })
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 800, 600)
        assertTrue(view.isAttachedToWindow)
    }

    private fun frame(): IntArray {
        val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(b))
        return IntArray(800 * 600).also { b.getPixels(it, 0, 800, 0, 0, 800, 600) }
    }

    /** The colors drawn in the middle of the (transparent) document. */
    private fun docColors(px: IntArray): Set<Int> {
        val out = HashSet<Int>()
        for (y in 250 until 350) for (x in 350 until 450) out += px[y * 800 + x]
        return out
    }

    @Test
    fun theTransparencySquaresFollowTheSettingWithoutAPerFrameRead() {
        setup()
        // Default: the light checker (white and light grey squares).
        val light = docColors(frame())
        assertTrue("two checker colors: $light", light.size >= 2)
        settings.transparencyDisplay = TransparencyDisplay.WHITE
        val white = docColors(frame())
        assertEquals(setOf(-1), white)
        settings.transparencyDisplay = TransparencyDisplay.DARK_CHECKER
        val dark = docColors(frame())
        assertTrue(dark.size >= 2)
        assertTrue("darker squares", dark.all { (it and 0xFF) < 0x80 })
        settings.transparencyDisplay = TransparencyDisplay.NONE
        val none = docColors(frame())
        assertEquals("the surround shows through", 1, none.size)
        assertNotEquals(setOf(-1), none)
        // Detached, the view stops listening; attached again, it catches up.
        val parent = view.parent as FrameLayout
        parent.removeView(view)
        settings.transparencyDisplay = TransparencyDisplay.WHITE
        parent.addView(view, FrameLayout.LayoutParams(800, 600))
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 800, 600)
        assertEquals(setOf(-1), docColors(frame()))
    }

    @Test
    fun aLiveSessionDrawsTheFrameAndLeavesTheCanvasExact() {
        lateinit var photo: Layer
        setup(1000, 800) { doc ->
            photo = doc.layers[0]
            Canvas(photo.bitmap).apply {
                drawColor(0xFF305080.toInt())
                drawCircle(500f, 400f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0A040.toInt() })
            }
        }
        val tone = FilterRegistry.byId("adjust.tone")!!
        val mask = MaskSpec(components = listOf(RadialMask(1, cx = 500f, cy = 400f, rx = 350f, ry = 280f, feather = 0.5f)), nextId = 2)
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), mask)!!
        val before = frame()
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        val edit = AdjustmentEdit(c, adj)
        edit.preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 1f)))
        assertTrue(c.liveAdjust.isActive)
        val live = frame()
        assertTrue("drawn from proxies", c.liveAdjust.proxyBytes > 0)
        assertTrue("the session's tiles wait", c.tiles.hasDirty)
        edit.flush()
        assertTrue("refining asks for frames", c.liveAdjust.wantsFrame)
        var n = 0
        while (c.liveAdjust.isActive) {
            frame()
            assertTrue(++n < 32)
        }
        assertFalse(c.tiles.hasDirty)
        val refined = frame()
        // The same frame without any session: invalidate everything and draw again.
        c.liveAdjust.policy = LiveAdjust.Policy.EXACT
        c.invalidateDoc(null)
        val exact = frame()
        assertArrayEquals("I7: the canvas after refinement is the exact canvas", exact, refined)
        // The live frame showed the new effect: much nearer the exact frame than the old one.
        val toExact = meanDiff(live, exact)
        val toOld = meanDiff(before, exact)
        assertTrue("live frame $toExact vs old frame $toOld", toExact < toOld / 3)
    }

    private fun meanDiff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) for (sh in 0..16 step 8) s += kotlin.math.abs((a[i] shr sh and 0xFF) - (b[i] shr sh and 0xFF))
        return s / (a.size * 3.0)
    }
}
