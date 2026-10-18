package com.brushwork.paint.v19.a6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.ViewportStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import kotlin.math.hypot

/**
 * v1.9 A6 (steps 7 and 8): the P0 bench's way of timing the canvas (design 2.7, `qa19/P0ProxyBench`),
 * for A6's own guards and its "Drawing preview" comparison: a real controller and an unattached
 * [CanvasView] of [screenW] x [screenH] px whose chrome leaves [freeH] px of height, frames drawn
 * through `View.draw` (the FRAME span), strokes sent as two touch events per frame (a 120 Hz
 * digitizer at 60 Hz). Every layer is covered with translucent shapes, as the bench's. Call
 * [close] when done.
 */
internal class A6Bench(
    w: Int,
    h: Int,
    layers: Int,
    active: Int,
    val screenW: Int = 1080,
    val screenH: Int = 2408,
    freeH: Int = 1846,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val c: EditorController
    val view: CanvasView
    private val screen = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
    private var clock = 0L
    private val traced = PerfTrace.enabled

    init {
        PerfTrace.enabled = true
        PerfTrace.clear()
        val doc = Document("a6", "A6 bench", w, h)
        repeat(layers) { i ->
            val bmp = BitmapUtils.createLayerBitmap(w, h)
            paintLayer(bmp, i)
            doc.layers += Layer(doc.newLayerId(), if (i == 0) "Background" else "Layer $i", bmp)
        }
        doc.activeLayerIndex = active
        val app = RuntimeEnvironment.getApplication()
        c = Smoke.controller(app, doc, scope)
        view = CanvasView(app, c)
        val chrome = (screenH - freeH) / 2f
        view.setFitInsets(0f, chrome, 0f, chrome)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, screenW, screenH)
        c.tools // the painting tools load their stored presets now: set presets after this
        c.snapping.enabled = false
        frame()
    }

    /** Layer [i]'s pixels: a white background, then translucent shapes (the bench's). */
    private fun paintLayer(bmp: Bitmap, i: Int) {
        if (i == 0) {
            bmp.eraseColor(-1)
            return
        }
        val cv = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val color = android.graphics.Color.HSVToColor(0x90, floatArrayOf(((i * 47) % 360).toFloat(), 0.6f, 0.8f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val fx = ((i * 37) % 10) / 10f
        val fy = ((i * 53) % 10) / 10f
        cv.drawCircle(w * (0.15f + 0.7f * fx), h * (0.1f + 0.8f * fy), w * 0.2f, p)
        cv.drawRect(w * 0.05f, h * (0.05f + 0.9f * (1f - fy)), w * 0.95f, h * (0.05f + 0.9f * (1f - fy)) + h * 0.06f, p)
        if (i % 3 == 0) {
            val g = Paint().apply { shader = LinearGradient(0f, 0f, w, h, 0x40203060, 0x40F0C080, Shader.TileMode.CLAMP) }
            cv.drawRect(0f, 0f, w, h, g)
        }
    }

    /** One frame (View.draw -> onDraw, the FRAME span); its time in ms. */
    fun frame(): Float {
        PerfTrace.clear()
        view.draw(Canvas(screen))
        return PerfTrace.lastMs(PerfTrace.FRAME) ?: throw AssertionError("no FRAME span")
    }

    /** The last frame's pixels (the whole view). */
    fun pixels(): IntArray = IntArray(screenW * screenH).also { screen.getPixels(it, 0, screenW, 0, 0, screenW, screenH) }

    /** The app's own fit; returns the zoom. */
    fun fit(): Float {
        view.fitToScreen()
        frame()
        return c.viewTransform.zoom
    }

    /** Zoom [z] around the free area's centre. */
    fun zoomTo(z: Float): Float {
        ViewportStore.of(c).zoomAround(screenW / 2f, screenH / 2f, z)
        view.resetRotation()
        assertEquals("zoom", z, c.viewTransform.zoom, 1e-3f)
        frame()
        return c.viewTransform.zoom
    }

    /** The part of the document on screen, [inset] (a fraction of its size) inside on each side. */
    fun visibleArea(inset: Float = 0.08f): RectF {
        val r = c.viewTransform.visibleDocRect(screenW, screenH)
        assertTrue(r.intersect(0f, 0f, c.doc.width.toFloat(), c.doc.height.toFloat()))
        r.inset(r.width() * inset, r.height() * inset)
        return r
    }

    fun point(p: PointF): ToolPoint {
        clock += EVENT_MS
        return ToolPoint(p.x, p.y, 1f, clock)
    }

    /**
     * One stroke through [pts] with the active painting tool, a frame after every second event.
     * Returns the median frame while drawing and the lift (LIFT); then draws the frame after the
     * lift, undoes the stroke and draws again (untimed), so every run paints the same layer.
     * [onFrame] runs after each frame while drawing.
     */
    fun strokeRun(pts: List<PointF>, onFrame: () -> Unit = {}): Pair<Float, Float> {
        val frames = ArrayList<Float>(pts.size / 2 + 1)
        c.pointerDown(point(pts[0]))
        var i = 1
        while (i < pts.size - 1) {
            c.pointerMove(point(pts[i++]))
            if (i < pts.size - 1) c.pointerMove(point(pts[i++]))
            frames += frame()
            onFrame()
        }
        PerfTrace.clear()
        c.pointerUp(point(pts.last()))
        val lift = PerfTrace.lastMs(PerfTrace.LIFT) ?: throw AssertionError("no LIFT span")
        settle()
        val steps = c.undoManager.undoCount
        c.undo()
        Smoke.pump(40)
        assertEquals("the stroke was undone", steps - 1, c.undoManager.undoCount)
        settle()
        return median(frames) to lift
    }

    /** Frames until the canvas has nothing left to refine (a live session's refinement). */
    fun settle() {
        var n = 0
        do frame() while ((c.liveAdjust.wantsFrame || c.liveAdjust.isActive) && ++n < 200)
    }

    override fun close() {
        PerfTrace.enabled = traced
        PerfTrace.clear()
        runCatching { c.dispose() }
        for (l in c.doc.layers) runCatching { if (!l.bitmap.isRecycled) l.bitmap.recycle() }
        screen.recycle()
        scope.cancel()
    }

    companion object {
        const val EVENT_MS = 8L

        fun median(xs: List<Float>): Float {
            require(xs.isNotEmpty())
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
        }

        /** A serpentine of [length] document px inside [area], rows [gap] apart, sampled every [step] px. */
        fun serpentine(area: RectF, length: Float, step: Float = 12f, gap: Float = 60f): List<PointF> {
            val corners = ArrayList<PointF>()
            var y = area.top
            var right = true
            var down = 1f
            corners += PointF(area.left, y)
            var total = 0f
            while (total < length) {
                val x = if (right) area.right else area.left
                corners += PointF(x, y)
                total += area.width()
                if (y + down * gap > area.bottom || y + down * gap < area.top) down = -down
                y += down * gap
                corners += PointF(x, y)
                total += gap
                right = !right
            }
            val out = ArrayList<PointF>()
            out += corners[0]
            var walked = 0f
            var next = step
            for (k in 1 until corners.size) {
                val a = corners[k - 1]
                val b = corners[k]
                val len = hypot(b.x - a.x, b.y - a.y)
                while (next <= walked + len && next <= length) {
                    val t = (next - walked) / len
                    out += PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                    next += step
                }
                walked += len
                if (next > length) break
            }
            return out
        }
    }
}
