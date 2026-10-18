package com.brushwork.paint.v19.a6

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 A6 step 7 (R13): the mask tool's work per frame while a Linear, Radial or Brush mask is
 * dragged on an adjustment layer, two touch events per 60 Hz frame (a 120 Hz digitizer): the
 * moves plus the frame's `beforeFrame`, timed together, divided by the frames. Under the live
 * policy (opted into, with the test's clock: Robolectric runs EXACT, I8) the frame draws the
 * latest move once at the interaction scale; the reference is a twin editor on v1.8's path,
 * which previews every move at once at the v1.8 scale. Each drag is cancelled, so every run
 * starts from the same mask. On the 512 px document (every run) at zoom 0.3 the live frame is at
 * most 0.6x the exact one; on A4 at the app's fit (0.47, local runs only) too.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class MaskFramePerfTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = ArrayList<EditorController>()
    private val traced = PerfTrace.enabled

    @Before
    fun setUp() {
        AppSettings(RuntimeEnvironment.getApplication()).prefs.edit().clear().commit()
        PerfTrace.enabled = true
        PerfTrace.clear()
    }

    @After
    fun tearDown() {
        made.forEach { it.dispose() }
        made.forEach { c -> c.doc.layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() } }
        PerfTrace.enabled = traced
        PerfTrace.clear()
        scope.cancel()
    }

    private val EditorController.maskTool get() = tools.getValue(ToolId.MASK) as MaskTool

    /**
     * An editor on a [w] x [h] document (white, a blue rectangle) at view [zoom] whose active
     * layer is an adjustment layer with one committed Linear mask; [live] opts into the live
     * policy afterwards (its clock stands still: no rest, no refinement during the drags).
     */
    private fun controller(w: Int, h: Int, zoom: Float, live: Boolean): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val doc = Document("m", "m", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(w * 0.08f, h * 0.1f, w * 0.92f, h * 0.9f, Paint().apply { color = 0xFF3080C0.toInt() })
        }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, AppSettings(app))
        made += c
        c.viewTransform.set(Matrix().apply { setScale(zoom, zoom) })
        c.selectTool(ToolId.MASK)
        c.maskTool.arm(MaskTool.Kind.LINEAR)
        c.pointerDown(ToolPoint(w * 0.1f, h * 0.85f))
        for (k in 1..10) c.pointerMove(ToolPoint(w * (0.1f + 0.015f * k), h * (0.85f + 0.01f * k)))
        c.pointerUp(ToolPoint(w * 0.25f, h * 0.95f))
        assertTrue("the drag made an adjustment layer", c.activeLayer.isAdjustmentLayer)
        if (live) {
            c.liveAdjust.clock = { 0L }
            c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        }
        return c
    }

    /** The bench's drag paths (`qa19/P0ProxyBench`) for [kind] on a [w] x [h] document. */
    private fun path(kind: MaskTool.Kind, w: Float, h: Float): List<ToolPoint> = when (kind) {
        MaskTool.Kind.LINEAR -> List(MOVES + 1) { k -> ToolPoint(w * (0.5f + 0.1f * k / MOVES), h * (0.2f + 0.4f * k / MOVES)) }
        MaskTool.Kind.RADIAL -> List(MOVES + 1) { k -> ToolPoint(w * (0.5f + if (k == 0) 0f else 0.05f + 0.3f * k / MOVES), h * 0.4f) }
        MaskTool.Kind.BRUSH -> A6Bench.serpentine(RectF(w * 0.15f, h * 0.15f, w * 0.85f, h * 0.6f), w * 1.7f, step = w / 60f, gap = w / 12f)
            .take(MOVES + 1).map { ToolPoint(it.x, it.y) }
    }

    /**
     * One cancelled drag along [pts] with [kind]: the first move (which begins the preview) is
     * left out, then two moves and `beforeFrame` per frame. Returns ms per frame.
     */
    private fun dragFrameMs(c: EditorController, kind: MaskTool.Kind, pts: List<ToolPoint>): Double {
        val tool = c.maskTool
        if (tool.armed != kind) tool.arm(kind)
        c.pointerDown(pts[0])
        c.pointerMove(pts[1])
        tool.beforeFrame()
        PerfTrace.clear()
        var frames = 0
        var k = 2
        val t0 = System.nanoTime()
        while (k < pts.size) {
            c.pointerMove(pts[k++])
            if (k < pts.size) c.pointerMove(pts[k++])
            tool.beforeFrame()
            frames++
        }
        val ms = (System.nanoTime() - t0) / 1e6 / frames
        val updates = PerfTrace.stats(PerfTrace.MASK_FRAME).count
        c.pointerCancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("$kind: the drag updated the preview ($updates updates in $frames frames)", updates >= frames)
        return ms
    }

    /** The live frame against the exact one for each kind, on [w] x [h] at [zoom]. */
    private fun liveAgainstExact(w: Int, h: Int, zoom: Float, slackMs: Double) {
        val live = controller(w, h, zoom, live = true)
        val exact = controller(w, h, zoom, live = false)
        assertEquals("I8", LiveAdjust.Policy.EXACT, exact.liveAdjust.policy)
        for (kind in MaskTool.Kind.entries) {
            val pts = path(kind, w.toFloat(), h.toFloat())
            repeat(WARM_UPS) {
                dragFrameMs(live, kind, pts)
                dragFrameMs(exact, kind, pts)
            }
            val (a, b) = PerfBudget.measuredRatioWithin(LIVE_LIMIT, slackMs = slackMs, what = "mask frame, ${kind.name}, ${w}x$h at $zoom: live against exact") {
                val a = A6Bench.median(List(RUNS) { dragFrameMs(live, kind, pts).toFloat() }).toDouble()
                val b = A6Bench.median(List(RUNS) { dragFrameMs(exact, kind, pts).toFloat() }).toDouble()
                a to b
            }
            println("[a6] mask frame ${kind.name} ${w}x$h at $zoom: live $a ms, exact $b ms")
            assertEquals("$kind: the cancelled drags left the one component", 1, live.activeLayer.maskSpec!!.components.size)
        }
    }

    @Test
    fun aLiveMaskFrameCostsAtMostSixTenthsOfV18sOnA512Document() = liveAgainstExact(512, 384, 0.3f, slackMs = 0.5)

    /** A4 (2480 x 3508) at the app's fit 0.47: the interaction scale 0.25 against v1.8's 0.371. Local only. */
    @Test
    fun onA4AtFitTooLocally() {
        assumeTrue("a T606-size probe: local runs only", System.getenv("CI") == null)
        liveAgainstExact(2480, 3508, 0.47f, slackMs = 0.0)
    }

    private companion object {
        const val MOVES = 30
        const val WARM_UPS = 2
        const val RUNS = 5
        const val LIVE_LIMIT = 0.6
    }
}
