package com.brushwork.paint.v19.a6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.masks.MaskSpecRenderer
import com.brushwork.paint.masks.SampleGrid
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskPreview
import com.brushwork.paint.tools.mask.MaskTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.ceil
import kotlin.math.sin

/**
 * v1.9 A6 step 4 (R13): under the live policy a mask drag (a creating drag, a handle or pin
 * drag, a brush stroke) draws its latest move once per frame (`BeforeFrame`, which `CanvasView`
 * runs first in every frame), at the interaction scale (the live proxies' own scale, at least
 * 1/8, never above the v1.8 preview) when "Fast adjustment preview" is on; the preview sharpens
 * to the v1.8 scale once the finger rests 150 ms without delaying the live session's refinement,
 * and the release records exactly what v1.8 recorded. Under Robolectric's EXACT policy (I8)
 * every move previews at once at the v1.8 scale. The previews are checked against full renders
 * of the shown spec at their scale; the releases against a twin editor that runs v1.8's path.
 */
@RunWith(RobolectricTestRunner::class)
class MaskInteractionScaleTest {
    private val w = 512
    private val h = 384
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = ArrayList<EditorController>()
    private var now = 0L
    private val traced = PerfTrace.enabled

    @Before
    fun clearSettings() {
        AppSettings(RuntimeEnvironment.getApplication()).prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        made.forEach { it.dispose() }
        PerfTrace.enabled = traced
        PerfTrace.clear()
        scope.cancel()
    }

    /** An editor on a fresh 512 x 384 document (white, a blue rectangle on the active layer) at view [zoom], on the Masks tool. */
    private fun controller(zoom: Float): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val doc = Document("m", "m", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(40f, 40f, 470f, 340f, Paint().apply { color = 0xFF3080C0.toInt() })
        }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, AppSettings(app)).also {
            it.viewTransform.set(Matrix().apply { setScale(zoom, zoom) })
            it.selectTool(ToolId.MASK)
            made += it
        }
    }

    /** The live policy, on the test's clock. */
    private fun EditorController.goLive() = apply {
        liveAdjust.clock = { now }
        liveAdjust.policy = LiveAdjust.Policy.LIVE
    }

    private val EditorController.maskTool get() = tools.getValue(ToolId.MASK) as MaskTool

    private fun maskFrames() = PerfTrace.stats(PerfTrace.MASK_FRAME).count

    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The preview shows the tool's latest spec at [scale], and its bitmap is a full render of that spec at that scale. */
    private fun assertPreviewShowsTheSpec(c: EditorController, scale: Float, what: String) {
        val pv = c.maskTool.preview
        assertSame("$what: the preview shows the latest spec", c.maskTool.displaySpec, pv.shown)
        assertEquals("$what: preview scale", scale, pv.scale, 0f)
        val bmp = pv.bitmap!!
        assertEquals("$what: preview width", ceil(w * scale).toInt(), bmp.width)
        val grid = SampleGrid(0f, 0f, 1f / pv.scale, bmp.width, bmp.height)
        val expected = IntArray(grid.size)
        MaskSpecRenderer.renderGrid(pv.shown!!, grid, expected, grid.cols)
        assertArrayEquals("$what: the preview is a full render at its scale", expected, px(bmp))
    }

    /** A radial adjustment (+ Radial, one finger) centred at (256, 192), radius 180; the new layer is active. */
    private fun createRadial(c: EditorController) {
        c.maskTool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(256f, 192f))
        for (i in 1..6) c.pointerMove(ToolPoint(256f + 30f * i, 192f))
        c.pointerUp(ToolPoint(436f, 192f))
        assertTrue("the drag made an adjustment layer", c.activeLayer.isAdjustmentLayer)
    }

    private fun pin(i: Int) = ToolPoint(256f - 20f * i, 192f + 15f * i)

    private fun strokePoint(i: Int) = ToolPoint(60f + 14f * i, 300f + 24f * sin(i * 0.5f))

    /** Asserts that [c] recorded what [twin] (v1.8's path) recorded on its active layer. */
    private fun assertSameRelease(twin: EditorController, c: EditorController, what: String) {
        assertEquals("$what: step", twin.undoManager.undoLabel, c.undoManager.undoLabel)
        assertEquals("$what: layers", twin.doc.layers.size, c.doc.layers.size)
        assertEquals("$what: the spec", twin.activeLayer.maskSpec, c.activeLayer.maskSpec)
        assertArrayEquals("$what: the full-resolution mask", px(twin.activeLayer.mask!!), px(c.activeLayer.mask!!))
    }

    @Test
    fun theInteractionScaleIsTheProxyScaleBetweenAnEighthAndTheV18Preview() {
        assertEquals(0.5f, MaskTool.interactionScaleFor(2f, w, h), 0f)
        assertEquals(0.5f, MaskTool.interactionScaleFor(1f, w, h), 0f)
        assertEquals(0.5f, MaskTool.interactionScaleFor(0.5f, w, h), 0f)
        assertEquals(0.25f, MaskTool.interactionScaleFor(0.3f, w, h), 0f)
        assertEquals(0.125f, MaskTool.interactionScaleFor(0.2f, w, h), 0f)
        assertEquals(0.125f, MaskTool.interactionScaleFor(0.05f, w, h), 0f)
        // The bench's boards at fit zoom (v1.8 previews 0.5, 0.371 and 0.316).
        assertEquals(0.5f, MaskTool.interactionScaleFor(0.69f, 1080, 2408), 0f)
        assertEquals(0.25f, MaskTool.interactionScaleFor(0.47f, 2480, 3508), 0f)
        assertEquals(0.25f, MaskTool.interactionScaleFor(0.41f, 3000, 4000), 0f)
        for (zoom in listOf(0.05f, 0.2f, 0.3f, 0.47f, 0.69f, 1f, 3f)) {
            val s = MaskTool.interactionScaleFor(zoom, 2480, 3508)
            assertEquals("zoom $zoom: the live proxies take the preview one to one", LiveAdjust.proxyScaleFor(zoom).coerceAtMost(MaskPreview.previewScale(2480, 3508)), s, 0f)
        }
        // Never above the v1.8 preview of a very large canvas.
        assertEquals(MaskPreview.previewScale(6000, 4000), MaskTool.interactionScaleFor(1f, 6000, 4000), 0f)
    }

    @Test
    fun underTheExactPolicyEveryMovePreviewsAtOnceAtTheV18Scale() {
        val c = controller(0.5f)
        assertEquals("I8: Robolectric runs EXACT", LiveAdjust.Policy.EXACT, c.liveAdjust.policy)
        PerfTrace.enabled = true
        PerfTrace.clear()
        c.maskTool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(256f, 192f))
        for (i in 1..6) {
            c.pointerMove(ToolPoint(256f + 30f * i, 192f))
            assertPreviewShowsTheSpec(c, 0.5f, "move $i")
            assertEquals("one preview update per move", i, maskFrames())
        }
        val shown = c.maskTool.preview.shown
        c.maskTool.beforeFrame()
        assertSame("nothing waits for the frame", shown, c.maskTool.preview.shown)
        assertEquals(6, maskFrames())
        c.pointerUp(ToolPoint(436f, 192f))
        assertEquals("Mask: radial", c.undoManager.undoLabel)
    }

    @Test
    fun aLiveDragDrawsItsLatestMoveOncePerFrameAtTheInteractionScale() {
        val twin = controller(0.3f)
        createRadial(twin)
        val c = controller(0.3f).goLive()
        PerfTrace.enabled = true
        PerfTrace.clear()
        c.maskTool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(256f, 192f))
        c.pointerMove(ToolPoint(286f, 192f))
        assertPreviewShowsTheSpec(c, 0.25f, "the first move: drawn at once")
        assertEquals(1, maskFrames())
        val first = c.maskTool.preview.shown
        for (i in 2..6) {
            c.pointerMove(ToolPoint(256f + 30f * i, 192f))
            assertSame("move $i waits for the frame", first, c.maskTool.preview.shown)
            assertNotSame(first, c.maskTool.displaySpec)
        }
        assertEquals("five moves, no preview work yet", 1, maskFrames())
        c.maskTool.beforeFrame()
        assertPreviewShowsTheSpec(c, 0.25f, "the frame: the latest move")
        assertEquals("one preview update for five moves", 2, maskFrames())
        c.maskTool.beforeFrame()
        assertEquals("nothing new: no work", 2, maskFrames())
        c.pointerUp(ToolPoint(436f, 192f))
        assertSameRelease(twin, c, "+ Radial released")

        // "Fast adjustment preview" off: the frames still coalesce, at the v1.8 scale.
        c.settings.fastAdjustPreview = false
        c.maskTool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(100f, 100f))
        c.pointerMove(ToolPoint(130f, 100f))
        assertPreviewShowsTheSpec(c, 0.5f, "setting off, first move")
        val shown = c.maskTool.preview.shown
        c.pointerMove(ToolPoint(160f, 100f))
        assertSame("setting off: the move waits for the frame", shown, c.maskTool.preview.shown)
        c.maskTool.beforeFrame()
        assertPreviewShowsTheSpec(c, 0.5f, "setting off, the frame")
        c.pointerUp(ToolPoint(160f, 100f))
        assertEquals(2, c.activeLayer.maskSpec!!.components.size)
    }

    @Test
    fun aRestingFingerSharpensThePreviewAndRefinementStartsWhenItWould() {
        val twin = controller(0.3f)
        createRadial(twin)
        twin.pointerDown(ToolPoint(256f, 192f))
        for (i in 1..5) twin.pointerMove(pin(i))
        twin.pointerUp(pin(5))

        val c = controller(0.3f)
        createRadial(c)
        c.tiles.update(c.compositor, null)
        c.goLive()
        val adj = c.activeLayer
        c.pointerDown(ToolPoint(256f, 192f))
        c.pointerMove(pin(1))
        assertPreviewShowsTheSpec(c, 0.25f, "the pin's first move")
        assertTrue("the preview draws the layer's mask", c.renderOverride != null)
        assertTrue("a live session follows the drag", c.liveAdjust.isActive)
        for (i in 2..4) c.pointerMove(pin(i))
        c.maskTool.beforeFrame()
        assertPreviewShowsTheSpec(c, 0.25f, "the frame")
        now += MaskTool.REST_NANOS - 1
        c.maskTool.beforeFrame()
        assertEquals("not rested yet: the interaction scale", 0.25f, c.maskTool.preview.scale, 0f)
        now += 1
        c.maskTool.beforeFrame()
        assertPreviewShowsTheSpec(c, 0.5f, "rested 150 ms: the v1.8 scale")
        // The sharpening kept the session's idle time: this frame refines it to the end.
        val screen = Bitmap.createBitmap(154, 116, Bitmap.Config.ARGB_8888)
        assertTrue(c.liveAdjust.drawFrame(Canvas(screen), Matrix().apply { setScale(0.3f, 0.3f) }, Rect(0, 0, w, h), true))
        assertFalse("refined while the finger rests", c.liveAdjust.isActive)
        now += 16_000_000L
        c.pointerMove(pin(5))
        c.maskTool.beforeFrame()
        assertPreviewShowsTheSpec(c, 0.25f, "moving again: the interaction scale")
        assertTrue("moving again: a live session", c.liveAdjust.isActive)
        c.pointerUp(pin(5))
        assertSame(adj, c.activeLayer)
        assertSameRelease(twin, c, "pin drag released")
    }

    @Test
    fun aLiveBrushStrokeOnAMaskKeepsEverySampleAndPaintsThemAtTheFrame() {
        val twin = controller(1f)
        val c = controller(1f)
        for (x in listOf(twin, c)) {
            createRadial(x)
            x.updatePreset(ToolId.MASK, x.maskBrush.copy(size = 24f, hardness = 0.4f, opacity = 0.7f))
            x.maskTool.arm(MaskTool.Kind.BRUSH)
        }
        c.goLive()
        twin.pointerDown(strokePoint(0))
        c.pointerDown(strokePoint(0))
        assertFalse("a part added to a mask paints into the mask (no preview)", c.maskTool.preview.isActive)
        val mask = c.activeLayer.mask!!
        var shown = px(mask)
        assertArrayEquals("the first dab is drawn at once", px(twin.activeLayer.mask!!), shown)
        for (i in 1..26) {
            twin.pointerMove(strokePoint(i))
            c.pointerMove(strokePoint(i))
            assertArrayEquals("move $i waits for the frame", shown, px(mask))
            if (i % 6 == 0) {
                c.maskTool.beforeFrame()
                shown = px(mask)
                assertArrayEquals("the frame after move $i paints every sample so far", px(twin.activeLayer.mask!!), shown)
            }
        }
        // Moves 25 and 26 still wait: the release paints them before the step is recorded.
        twin.pointerUp(strokePoint(26))
        c.pointerUp(strokePoint(26))
        assertSameRelease(twin, c, "brush stroke released")
    }

    @Test
    fun aLiveBrushStrokeForANewAdjustmentPreviewsAtTheInteractionScale() {
        val twin = controller(0.3f)
        val c = controller(0.3f).goLive()
        for (x in listOf(twin, c)) {
            x.updatePreset(ToolId.MASK, x.maskBrush.copy(size = 30f, hardness = 0.5f, opacity = 0.8f))
            x.maskTool.arm(MaskTool.Kind.BRUSH)
            x.pointerDown(strokePoint(0))
        }
        assertPreviewShowsTheSpec(c, 0.25f, "the first dab")
        for (i in 1..20) {
            twin.pointerMove(strokePoint(i))
            val before = c.maskTool.preview.shown
            c.pointerMove(strokePoint(i))
            if (i % 5 == 0) {
                c.maskTool.beforeFrame()
                assertPreviewShowsTheSpec(c, 0.25f, "the frame after move $i")
            } else {
                assertSame("move $i waits for the frame", before, c.maskTool.preview.shown)
            }
        }
        now += MaskTool.REST_NANOS
        c.maskTool.beforeFrame()
        assertPreviewShowsTheSpec(c, 0.5f, "rested: the v1.8 scale")
        for (i in 21..22) {
            twin.pointerMove(strokePoint(i))
            c.pointerMove(strokePoint(i))
        }
        twin.pointerUp(strokePoint(22))
        c.pointerUp(strokePoint(22))
        assertEquals("Mask: brush", c.undoManager.undoLabel)
        assertSameRelease(twin, c, "new brush adjustment released")
    }
}
