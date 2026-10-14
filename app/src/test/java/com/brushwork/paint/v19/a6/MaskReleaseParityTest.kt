package com.brushwork.paint.v19.a6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.masks.MaskSpecRenderer
import com.brushwork.paint.masks.SampleGrid
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 A6 step 5 (R13): under the live policy, a released mask edit that would take a while at
 * full resolution is rendered in bands over the next frames (`MaskRefine`, a slice per main-thread
 * message within a 6 ms budget) while the preview shows it, and lands as ONE step: the released
 * mask, spec, step and its undo / redo equal v1.8's full render (a twin editor on Robolectric's
 * EXACT policy, I8, where the release renders at once). Anything that records a step, undo or
 * a new gesture lands it first. The refine is forced on these small documents by lowering the
 * tool's threshold (the bands are counted on a clock that moves 4 ms per read).
 */
@RunWith(RobolectricTestRunner::class)
class MaskReleaseParityTest {
    private val w = 512
    private val h = 384
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = ArrayList<EditorController>()
    private var now = 0L
    private var tick = 0L

    @Before
    fun setUp() {
        AppSettings(RuntimeEnvironment.getApplication()).prefs.edit().clear().commit()
        MaskTool.refineMinMillis = 0.0
    }

    @After
    fun tearDown() {
        MaskTool.refineMinMillis = MaskTool.REFINE_MIN_MS
        made.forEach { it.dispose() }
        scope.cancel()
    }

    private fun controller(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val doc = Document("m", "m", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(40f, 40f, 470f, 340f, Paint().apply { color = 0xFF3080C0.toInt() })
        }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, AppSettings(app)).also {
            it.viewTransform.set(Matrix().apply { setScale(0.5f, 0.5f) })
            it.selectTool(ToolId.MASK)
            made += it
        }
    }

    private fun EditorController.goLive() = apply {
        liveAdjust.clock = { now += tick; now }
        liveAdjust.policy = LiveAdjust.Policy.LIVE
    }

    private val EditorController.maskTool get() = tools.getValue(ToolId.MASK) as MaskTool

    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** I1: the layer's mask is the full render of its spec. */
    private fun assertMaskRendersItsSpec(c: EditorController, what: String) {
        val l = c.activeLayer
        val want = IntArray(w * h)
        MaskSpecRenderer.render(l.maskSpec!!, w, h, 0, 0, w, h, want, w)
        assertArrayEquals("$what (I1: the mask renders its spec)", want, px(l.mask!!))
    }

    private fun assertSameAs(twin: EditorController, c: EditorController, what: String) {
        assertEquals("$what: step", twin.undoManager.undoLabel, c.undoManager.undoLabel)
        assertEquals("$what: redo", twin.undoManager.redoLabel, c.undoManager.redoLabel)
        assertEquals("$what: the spec", twin.activeLayer.maskSpec, c.activeLayer.maskSpec)
        assertArrayEquals("$what: the full-resolution mask", px(twin.activeLayer.mask!!), px(c.activeLayer.mask!!))
    }

    /** The main thread's messages until none are due (the refine's slices). */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A radial adjustment centred at (256, 192), radius 150 (the new layer is active). */
    private fun createRadial(c: EditorController) {
        c.maskTool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(256f, 192f))
        for (i in 1..5) c.pointerMove(ToolPoint(256f + 30f * i, 192f))
        c.pointerUp(ToolPoint(406f, 192f))
        assertTrue(c.activeLayer.isAdjustmentLayer)
    }

    /** + Linear from (60, 60) to (300, 250) on the active adjustment layer. */
    private fun addLinear(c: EditorController) {
        tick = 0L
        c.maskTool.arm(MaskTool.Kind.LINEAR)
        c.pointerDown(ToolPoint(60f, 60f))
        for (i in 1..6) {
            c.pointerMove(ToolPoint(60f + 40f * i, 60f + 31.6f * i))
            c.maskTool.beforeFrame()
        }
        tick = 4_000_000L
        c.pointerUp(ToolPoint(300f, 249.6f))
    }

    @Test
    fun aReleasedEditRendersInBandsAndLandsAsV18sStep() {
        val twin = controller()
        createRadial(twin)
        addLinear(twin)
        assertNull("EXACT (I8): the twin's release rendered at once", twin.maskTool.refine)
        assertEquals("Mask: linear", twin.undoManager.undoLabel)

        val c = controller()
        createRadial(c)
        c.goLive()
        val layer = c.activeLayer
        val before = layer.maskSpec
        val beforePixels = px(layer.mask!!)
        addLinear(c)
        val r = c.maskTool.refine
        assertTrue("the release left a refine pending", r != null && r.isPending)
        assertSame("the step waits: the layer still has its spec", before, layer.maskSpec)
        assertArrayEquals("and its pixels", beforePixels, px(layer.mask!!))
        assertEquals("Mask: radial", c.undoManager.undoLabel)
        assertEquals("the tool shows the new spec", twin.activeLayer.maskSpec, c.maskTool.displaySpec)
        assertTrue("the preview shows it while it refines", c.maskTool.preview.isActive)
        assertSame(c.maskTool.displaySpec, c.maskTool.preview.shown)
        val pv = c.maskTool.preview
        val grid = SampleGrid(0f, 0f, 1f / pv.scale, pv.bitmap!!.width, pv.bitmap!!.height)
        val want = IntArray(grid.size).also { MaskSpecRenderer.renderGrid(pv.shown!!, grid, it, grid.cols) }
        assertArrayEquals("the preview is the released spec's", want, px(pv.bitmap!!))
        idle()
        assertEquals("landed", true, r!!.result)
        assertTrue("in more than one slice (${r.slices})", r.slices > 1)
        assertNull(c.maskTool.refine)
        assertTrue("the preview ended", !c.maskTool.preview.isActive)
        assertSameAs(twin, c, "landed")
        assertMaskRendersItsSpec(c, "landed")
        twin.undo(); c.undo()
        assertSameAs(twin, c, "undone")
        twin.redo(); c.redo()
        assertSameAs(twin, c, "redone")
    }

    @Test
    fun undoAGestureOrAStepLandsAPendingRefineFirst() {
        val twin = controller()
        val c = controller()
        for (x in listOf(twin, c)) createRadial(x)
        c.goLive()
        for (x in listOf(twin, c)) addLinear(x)
        // Undo while it refines: the edit lands, then it is undone.
        assertTrue(c.maskTool.refine?.isPending == true)
        twin.undo(); c.undo()
        assertSameAs(twin, c, "undo while refining")
        twin.redo(); c.redo()
        assertSameAs(twin, c, "redo")

        // The radial's pin dragged and released, then a new finger: the move lands before the
        // finger does anything.
        for (x in listOf(twin, c)) {
            tick = 0L
            x.maskTool.select(x.activeLayer.maskSpec!!.components.first().id)
            x.pointerDown(ToolPoint(256f, 192f))
            for (i in 1..4) { x.pointerMove(ToolPoint(256f - 15f * i, 192f + 10f * i)); x.maskTool.beforeFrame() }
            tick = 4_000_000L
            x.pointerUp(ToolPoint(196f, 232f))
        }
        assertTrue("the move refines", c.maskTool.refine?.isPending == true)
        tick = 0L
        c.pointerDown(ToolPoint(30f, 30f))
        assertNull("the finger landed it", c.maskTool.refine)
        c.pointerUp(ToolPoint(30f, 30f))
        assertSameAs(twin, c, "landed by the next finger")

        // Another step while it refines (the Masks sheet deletes the mask): its edit scope lands
        // the move first, as its own step before the delete.
        for (x in listOf(twin, c)) {
            tick = 0L
            x.pointerDown(ToolPoint(196f, 232f))
            for (i in 1..3) { x.pointerMove(ToolPoint(196f + 20f * i, 232f)); x.maskTool.beforeFrame() }
            tick = 4_000_000L
            x.pointerUp(ToolPoint(256f, 232f))
        }
        assertTrue(c.maskTool.refine?.isPending == true)
        val moved = twin.activeLayer.maskSpec
        assertNotEquals("the move waits", moved, c.activeLayer.maskSpec)
        for (x in listOf(twin, c)) MaskEdits.deleteMask(x, x.activeLayer)
        assertNull(c.maskTool.refine)
        assertNull(c.activeLayer.mask)
        assertEquals("the delete is the step", twin.undoManager.undoLabel, c.undoManager.undoLabel)
        for (x in listOf(twin, c)) x.undo()
        assertEquals("the move landed before the delete", moved, c.activeLayer.maskSpec)
        assertSameAs(twin, c, "move landed, then the delete undone")
        assertMaskRendersItsSpec(c, "the deleted mask back")
        for (x in listOf(twin, c)) x.undo()
        assertSameAs(twin, c, "the move undone")
        assertMaskRendersItsSpec(c, "the move undone")
    }

    @Test
    fun leavingTheToolLandsIt() {
        val twin = controller()
        val c = controller()
        for (x in listOf(twin, c)) createRadial(x)
        c.goLive()
        for (x in listOf(twin, c)) addLinear(x)
        assertTrue(c.maskTool.refine?.isPending == true)
        c.selectTool(ToolId.BRUSH)
        twin.selectTool(ToolId.BRUSH)
        assertNull(c.maskTool.refine)
        assertSameAs(twin, c, "tool changed")
        assertMaskRendersItsSpec(c, "tool changed")
    }
}
