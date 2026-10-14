package com.brushwork.paint.qa19

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.ViewportStore
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
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
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1.9 gate 1 (LA1, design 2.7): the P0 proxy baselines. The lead has no T606, so the P0 numbers
 * are JVM proxies: Robolectric NATIVE graphics on the lead's PC, through the app's own code paths
 * (EditorController, the canvas view's frame, BrushTool, the vector eraser, MaskTool) and read
 * from the PerfTrace spans those paths record (FRAME, LIFT, ERASE_REBUILD, MASK_FRAME), so a
 * proxy number is the span the device's frame-times overlay shows. Each value is the median of
 * [RUNS] runs after [WARM_UPS] warm-up runs; a run is one stroke or drag, and a run's value is
 * the median of its own samples (a lift: its one sample). The T606 is taken as 6x slower: the
 * thresholds of 2.7 are compared with `value x 6`.
 *
 * Each result is printed as `[p0] name value unit canvas zoom jvm` (the lines of
 * `.wt/v19-p0.txt`); `[p0-runs]` lines give every run's value. Nothing is asserted beyond the
 * setup working: this is a bench, not a guard.
 *
 * It runs only with `-Pbench` (`bw.testSet` is then "bench"): every CI run leaves the class out
 * (app/build.gradle.kts), and a local run without `-Pbench` skips it. A6 runs it again at its
 * checkpoint (design 2.9) and appends its lines as `a6-after-p2`.
 *
 * The canvases are the conventions' (PHONE 1080 x 2408 with 20 layers, A4 2480 x 3508 with 5,
 * PRINT 3000 x 4000 with 3), shown in a 1080 x 2408 canvas view whose chrome leaves 1846 px of
 * height free, so the app's own fit is 0.69 on PHONE (the design's value); on A4 and PRINT the
 * same fit is width-limited (0.39, 0.32), so their frames are also measured at the design's
 * stated fit zooms (0.47, 0.41).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h803dp-xxhdpi")
@Category(Timing::class)
class P0ProxyBench {
    private val app get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: EditorController? = null
    private var canvasView: CanvasView? = null
    private var screen: Bitmap? = null
    private var clock = 0L

    private val c: EditorController get() = controller!!
    private val view: CanvasView get() = canvasView!!

    @Before
    fun onlyWithBench() {
        assumeTrue("the P0 proxy bench runs only with -Pbench", System.getProperty("bw.testSet") == "bench")
        PerfTrace.clear()
        PerfTrace.enabled = true
    }

    @After
    fun release() {
        PerfTrace.enabled = false
        PerfTrace.clear()
        controller?.let { ctl ->
            runCatching { ctl.dispose() }
            for (l in ctl.doc.layers) runCatching { if (!l.bitmap.isRecycled) l.bitmap.recycle() }
        }
        screen?.recycle()
        controller = null
        canvasView = null
        screen = null
        scope.cancel()
    }

    // ------------------------------------------------------------------ canvases

    /** A device canvas of the conventions; [active] is the layer drawn on (one in the middle). */
    private enum class Board(val w: Int, val h: Int, val layers: Int, val active: Int, val designFit: Float) {
        PHONE(1080, 2408, 20, 10, 0.69f),
        A4(2480, 3508, 5, 2, 0.47f),
        PRINT(3000, 4000, 3, 1, 0.41f),
    }

    /** Layer [i]'s pixels: a white background, then translucent shapes spread over the canvas. */
    private fun paintLayer(bmp: Bitmap, i: Int) {
        if (i == 0) {
            bmp.eraseColor(-1)
            return
        }
        val cv = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val hue = (i * 47) % 360
        val color = android.graphics.Color.HSVToColor(0x90, floatArrayOf(hue.toFloat(), 0.6f, 0.8f))
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

    /**
     * Opens [b] with [layers] layers ([active] drawn on) in a real controller and an unattached
     * 1080 x 2408 [CanvasView] (onDraw is the FRAME span), fitted by the app's own fit.
     */
    private fun open(b: Board, layers: Int = b.layers, active: Int = b.active) {
        val doc = Document("p0", "P0 ${b.name}", b.w, b.h)
        repeat(layers) { i ->
            val bmp = BitmapUtils.createLayerBitmap(b.w, b.h)
            paintLayer(bmp, i)
            doc.layers += Layer(doc.newLayerId(), if (i == 0) "Background" else "Layer $i", bmp)
        }
        doc.activeLayerIndex = active
        val ctl = Smoke.controller(app, doc, scope)
        controller = ctl
        val v = CanvasView(app, ctl)
        // The chrome (top bar, strips, bottom bar) leaves FREE_H of height: the fit is 0.69 on PHONE.
        val chrome = (SCREEN_H - FREE_H) / 2f
        v.setFitInsets(0f, chrome, 0f, chrome)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(SCREEN_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(SCREEN_H, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, SCREEN_W, SCREEN_H)
        canvasView = v
        screen = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888)
        ctl.tools // the painting tools load their stored presets now: set the bench's presets after this
        ctl.snapping.enabled = false
        frame() // the first frame composites every tile
    }

    /** One frame (View.draw -> onDraw, the FRAME span); returns the span's time in ms. */
    private fun frame(): Float {
        PerfTrace.clear()
        view.draw(Canvas(screen!!))
        return PerfTrace.lastMs(PerfTrace.FRAME) ?: throw AssertionError("no FRAME span: is PerfTrace on?")
    }

    /** The app's own fit (CanvasView.fitToScreen); returns the zoom. */
    private fun fit(): Float {
        view.fitToScreen()
        frame()
        return c.viewTransform.zoom
    }

    /** Zoom [z] around the free area's centre; resetRotation pushes the viewport into the controller. */
    private fun zoomTo(z: Float): Float {
        ViewportStore.of(c).zoomAround(SCREEN_W / 2f, SCREEN_H / 2f, z)
        view.resetRotation()
        assertEquals("zoom", z, c.viewTransform.zoom, 1e-3f)
        frame()
        return c.viewTransform.zoom
    }

    /** The part of the document on screen, [inset] (a fraction of its size) inside on each side. */
    private fun visibleArea(inset: Float): RectF {
        val r = c.viewTransform.visibleDocRect(SCREEN_W, SCREEN_H)
        assertTrue(r.intersect(0f, 0f, c.doc.width.toFloat(), c.doc.height.toFloat()))
        r.inset(r.width() * inset, r.height() * inset)
        return r
    }

    // ------------------------------------------------------------------ paths

    /**
     * A serpentine of [length] document px inside [area] (rows [gap] apart, turning back at the
     * area's bottom), sampled every [step] px.
     */
    private fun serpentine(area: RectF, length: Float, step: Float = STEP, gap: Float = ROW_GAP): List<PointF> {
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

    private fun point(p: PointF): ToolPoint {
        clock += EVENT_MS
        return ToolPoint(p.x, p.y, 1f, clock)
    }

    // ------------------------------------------------------------------ measuring

    private fun median(xs: List<Float>): Float {
        require(xs.isNotEmpty())
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    /** [WARM_UPS] untimed runs of [run], then [RUNS] runs; the median of the runs' values. */
    private fun bench(what: String, run: () -> Float): Float {
        repeat(WARM_UPS) { run() }
        val values = List(RUNS) { run() }
        println("[p0-runs] $what: ${values.joinToString(" ") { f(it) }}")
        return median(values)
    }

    /** Several values per run, named by [labels]: each value's median over the runs. */
    private fun benchValues(what: String, labels: List<String>, run: () -> List<Float>): List<Float> {
        repeat(WARM_UPS) { run() }
        val values = List(RUNS) { run() }
        return labels.mapIndexed { i, label ->
            println("[p0-runs] $what $label: ${values.joinToString(" ") { f(it[i]) }}")
            median(values.map { it[i] })
        }
    }

    /** The frames and the lift of one stroke per run: each value's median over the runs. */
    private fun benchPairs(what: String, run: () -> Pair<Float, Float>): Pair<Float, Float> {
        val (frame, lift) = benchValues(what, listOf("frame", "lift")) { run().toList() }
        return frame to lift
    }

    private fun f(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    private fun report(name: String, value: Float, canvas: String, zoom: Float) =
        println("[p0] $name ${f(value)} ms $canvas ${f(zoom)} jvm")

    /**
     * One stroke through [pts] with the active painting tool: two touch events per 60 Hz frame
     * (a 120 Hz digitizer) and a frame after each pair. Returns the median frame while drawing
     * (FRAME) and the lift (LIFT). Afterwards the frame after the lift is drawn, the stroke is
     * undone and the canvas drawn again (untimed), so every run paints the same layer.
     */
    private fun strokeRun(pts: List<PointF>): Pair<Float, Float> {
        val frames = ArrayList<Float>(pts.size / 2 + 1)
        c.pointerDown(point(pts[0]))
        var i = 1
        while (i < pts.size - 1) {
            c.pointerMove(point(pts[i++]))
            if (i < pts.size - 1) c.pointerMove(point(pts[i++]))
            frames += frame()
        }
        PerfTrace.clear()
        c.pointerUp(point(pts.last()))
        val lift = PerfTrace.lastMs(PerfTrace.LIFT) ?: throw AssertionError("no LIFT span")
        frame()
        val steps = c.undoManager.undoCount
        c.undo()
        Smoke.pump(40)
        assertEquals("the stroke was undone", steps - 1, c.undoManager.undoCount)
        frame()
        return median(frames) to lift
    }

    /** The brush (round, 40 px) on [b]: frames at each zoom of [zooms] (null = the app's fit), lift at the fit. */
    private fun drawing(b: Board, zooms: List<Float?>) {
        open(b)
        c.selectTool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 40f)
        for (z in zooms) {
            val zoom = if (z == null) fit() else { fit(); zoomTo(z) }
            val pts = serpentine(visibleArea(0.08f), STROKE_LENGTH)
            val (frameMs, liftMs) = benchPairs("${b.name} brush at ${f(zoom)} (${pts.size} points)") { strokeRun(pts) }
            report("frame", frameMs, b.name, zoom)
            if (z == null) report("brush.lift", liftMs, b.name, zoom)
        }
    }

    /** The raster Eraser (soft, 90 px) on [b] at the app's fit: its frames and its lift. */
    private fun erasing(b: Board) {
        c.toggleEraser()
        assertEquals(ToolId.ERASER, c.activeToolId)
        c.eraser = BrushLibrary.byId("softeraser")!!.copy(size = 90f)
        val zoom = fit()
        val pts = serpentine(visibleArea(0.08f), STROKE_LENGTH)
        val (frameMs, liftMs) = benchPairs("${b.name} raster eraser at ${f(zoom)} (${pts.size} points)") { strokeRun(pts) }
        report("frame.erase", frameMs, b.name, zoom)
        report("erase.lift", liftMs, b.name, zoom)
    }

    // ------------------------------------------------------------------ the measurements

    /** PHONE: brush frames at fit (0.69) and 2.0, the brush lift, the raster Eraser's frames. */
    @Test
    fun phoneBrushAndRasterEraser() {
        drawing(Board.PHONE, listOf(null, 2f))
        erasing(Board.PHONE)
    }

    /** A4: brush frames at the app's fit (0.39), the design's fit (0.47) and 2.0; the brush lift. */
    @Test
    fun a4Brush() {
        drawing(Board.A4, listOf(null, Board.A4.designFit, 2f))
    }

    /** PRINT: brush frames at the app's fit (0.32), the design's fit (0.41) and 2.0; the lift; the raster Eraser. */
    @Test
    fun printBrushAndRasterEraser() {
        drawing(Board.PRINT, listOf(null, Board.PRINT.designFit, 2f))
        erasing(Board.PRINT)
    }

    /** ~70 points along a wavy line of about 260 px (VectorBudgetQaTest's strokes). */
    private fun vectorStroke(rnd: Random, w: Int, h: Int): VStroke {
        val x0 = 100f + rnd.nextFloat() * (w - 200f)
        val y0 = 100f + rnd.nextFloat() * (h - 200f)
        val a = rnd.nextFloat() * 6.28f
        val n = 70
        val xs = FloatArray(n) { k -> x0 + cos(a) * 3.7f * k + 8f * sin(k * 0.3f) }
        val ys = FloatArray(n) { k -> y0 + sin(a) * 3.7f * k + 8f * cos(k * 0.3f) }
        val preset = BrushLibrary.byId(listOf("pen", "softround")[rnd.nextInt(2)])!!.copy(size = 6f + rnd.nextFloat() * 14f)
        return VStroke(0, preset = preset, color = 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF), seed = rnd.nextLong(), stylus = rnd.nextBoolean(), points = PackedPoints(xs, ys, FloatArray(n) { 0.4f + 0.6f * rnd.nextFloat() }))
    }

    /**
     * The number of objects the vector Eraser's preview shows dimmed now: the size of
     * [DoomPreview]'s private `items` (read by reflection; this bench only). 0 before the first hit.
     */
    private fun doomed(): Int {
        val preview = c.renderOverride as? DoomPreview ?: return 0
        val field = DoomPreview::class.java.getDeclaredField("items").apply { isAccessible = true }
        return (field.get(preview) as Map<*, *>).size
    }

    /**
     * erase.rebuild: the vector Eraser (Object mode, 40 px) dragged across a vector layer of 500,
     * then 2000 strokes on PHONE; each move's preview rebuild (ERASE_REBUILD). Two values: the
     * median of every move (most moves only hit-test, as the overlay's p50 shows them) and, as
     * `.hit`, the median of the moves that dim new objects (the rebuild A5's throttle is about).
     * A frame is drawn after every second move, as while drawing: `frame.erase.vector.<n>` (not a
     * row of 2.7; the dimmed preview's composite, for A5). Every drag is cancelled, so the layer
     * keeps its objects.
     */
    @Test
    fun phoneVectorEraseRebuild() {
        open(Board.PHONE, layers = 2, active = 1)
        val zoom = fit()
        c.toggleVectorMode()
        val vec = c.activeLayer
        assertTrue("a vector layer", vec.vector != null)
        val rnd = Random(1909)
        c.toggleEraser()
        assertEquals(ToolId.ERASER, c.activeToolId)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.eraser = c.eraser.copy(size = 40f)
        val pts = serpentine(RectF(60f, 60f, c.doc.width - 60f, c.doc.height - 60f), STROKE_LENGTH, gap = 160f)
        var have = 0
        for (count in listOf(500, 2000)) {
            while (have < count) {
                val n = minOf(250, count - have)
                c.vectors.addObjects(vec, List(n) { vectorStroke(rnd, c.doc.width, c.doc.height) }, "Add")
                c.settleVectorWork()
                have += n
            }
            Smoke.pump(100)
            c.settleVectorWork()
            assertEquals(count, vec.vector!!.objects.size)
            assertEquals(ToolId.ERASER, c.activeToolId)
            var hitMoves = 0
            val (value, hitValue, frameMs) = benchValues(
                "PHONE erase.rebuild over $count objects (${pts.size} points)",
                listOf("every move", "moves that dim new objects", "frame"),
            ) {
                val samples = ArrayList<Float>(pts.size)
                val hits = ArrayList<Float>()
                val frames = ArrayList<Float>(pts.size / 2 + 1)
                c.pointerDown(point(pts[0]))
                for (k in 1 until pts.size) {
                    val before = doomed()
                    PerfTrace.clear()
                    c.pointerMove(point(pts[k]))
                    PerfTrace.lastMs(PerfTrace.ERASE_REBUILD)?.let { ms ->
                        samples += ms
                        if (doomed() != before) hits += ms
                    }
                    // A frame after every second move, as while drawing (the dimmed preview composited).
                    if (k % 2 == 0 || k == pts.size - 1) frames += frame()
                }
                hitMoves = hits.size
                c.pointerCancel()
                Smoke.pump(20)
                frame()
                assertEquals("a cancelled drag erases nothing", count, vec.vector!!.objects.size)
                assertTrue("the drag dimmed objects", hits.isNotEmpty())
                listOf(median(samples), median(hits), median(frames))
            }
            println("[p0-runs] PHONE erase.rebuild over $count objects: $hitMoves of ${pts.size - 1} moves dim new objects")
            report("erase.rebuild.$count", value, Board.PHONE.name, zoom)
            report("erase.rebuild.$count.hit", hitValue, Board.PHONE.name, zoom)
            report("frame.erase.vector.$count", frameMs, Board.PHONE.name, zoom)
        }
    }

    /**
     * mask.frame: Linear, Radial and Brush mask drags on an adjustment layer of [b] at the app's
     * fit; each move's MASK_FRAME (the tool's preview update). The first sample of each drag
     * (which begins the preview) is left out. Every drag is cancelled, so the mask keeps its one
     * component. No frames are drawn: the span does not include the composite.
     */
    private fun masks(b: Board) {
        open(b)
        val zoom = fit()
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        val w = c.doc.width.toFloat()
        val h = c.doc.height.toFloat()
        // The adjustment layer: one Linear drag, committed.
        tool.arm(MaskTool.Kind.LINEAR)
        c.pointerDown(point(PointF(w * 0.1f, h * 0.85f)))
        for (k in 1..10) c.pointerMove(point(PointF(w * (0.1f + 0.015f * k), h * (0.85f + 0.01f * k))))
        c.pointerUp(point(PointF(w * 0.25f, h * 0.95f)))
        Smoke.pumpUntil { c.activeLayer.isAdjustmentLayer }
        val adj = c.activeLayer
        assertTrue("the drag made an adjustment layer", adj.isAdjustmentLayer)
        Smoke.pump(200)
        frame()
        val brushPath = serpentine(RectF(w * 0.15f, h * 0.15f, w * 0.85f, h * 0.6f), STROKE_LENGTH, step = 40f, gap = 200f).take(MASK_MOVES + 1)
        for (kind in MaskTool.Kind.entries) {
            val path: List<PointF> = when (kind) {
                MaskTool.Kind.LINEAR -> List(MASK_MOVES + 1) { k -> PointF(w * (0.5f + 0.1f * k / MASK_MOVES), h * (0.2f + 0.4f * k / MASK_MOVES)) }
                // (The first move clears the touch slop: 4 dp on screen.)
                MaskTool.Kind.RADIAL -> List(MASK_MOVES + 1) { k -> PointF(w * (0.5f + if (k == 0) 0f else 0.05f + 0.3f * k / MASK_MOVES), h * 0.4f) }
                MaskTool.Kind.BRUSH -> brushPath
            }
            val value = bench("${b.name} mask.${kind.name.lowercase()} (${path.size - 1} moves)") {
                if (tool.armed != kind) tool.arm(kind)
                assertEquals(kind, tool.armed)
                val samples = ArrayList<Float>(path.size)
                c.pointerDown(point(path[0]))
                for (k in 1 until path.size) {
                    PerfTrace.clear()
                    c.pointerMove(point(path[k]))
                    PerfTrace.lastMs(PerfTrace.MASK_FRAME)?.let { samples += it }
                }
                c.pointerCancel()
                Smoke.pump(20)
                assertTrue("${kind.name}: the drag was measured (${samples.size} samples)", samples.size >= MASK_MOVES - 2)
                median(samples.drop(1))
            }
            assertEquals("the cancelled drags left the one component", 1, adj.maskSpec!!.components.size)
            report("mask.frame.${kind.name.lowercase()}", value, b.name, zoom)
        }
    }

    @Test
    fun a4Masks() = masks(Board.A4)

    @Test
    fun printMasks() = masks(Board.PRINT)

    private companion object {
        const val WARM_UPS = 3
        const val RUNS = 15
        const val SCREEN_W = 1080
        const val SCREEN_H = 2408
        /** The height the chrome leaves free: the app's fit (0.9 of it) is 0.69 on PHONE. */
        const val FREE_H = 1846
        /** A 30 cm stroke at the document's 350 dpi (design 2.7's "30 cm"), document px. */
        const val STROKE_LENGTH = 4134f
        /** Document px between touch events (a 30 cm stroke in about 3 s at 120 Hz). */
        const val STEP = 12f
        const val ROW_GAP = 60f
        const val EVENT_MS = 8L
        const val MASK_MOVES = 30
    }
}
