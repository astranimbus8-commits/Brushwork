package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.7 (§6.3, the array rows; area E): the array's costs on a 4000 × 5000 document, measured on
 * the JVM (Robolectric NATIVE, software Skia). Each probe prints its median (the phone's own
 * times are device checks); the guards sit at five times the phone budget, so they catch an
 * order-of-magnitude regression without failing while other builds load the desktop. Locally
 * only, never on CI (the documents are T606-size).
 *
 * - the handle or slider preview of 64 copies of a 1000 × 1000 source, through the controller:
 *   once the preview runs, a frame re-renders NO display tile (strict), and the Array tool's
 *   overlay draws the copies (on the phone the view's hardware canvas; here a software canvas of
 *   the phone's 1080 × 2400, so that guard is ten times the 33 ms budget);
 * - the raster cache (what "Edit array" draws on the main thread): 200 copies of 500 × 500
 *   (≤ 600 ms) and 64 copies of 1000 × 1000 (≤ 1.5 s), turned round a circle;
 * - a vector array of 64 copies of a 200-anchor stroked path: the expanded content and its first
 *   render tile (≤ 120 ms);
 * - a text array of 32 copies of a text about 1500 × 400 px in a spiral (≤ 250 ms);
 * - integration pass "arrayrender": the same raster and text rows as "Edit array" through the
 *   controller with the automatic policy (the cache renders on the worker: each prints the main
 *   thread's time, the worker's and the whole edit's, and when "Rendering array…" showed), and
 *   the cost model the policy starts from ([ArrayRenders.INITIAL_NS_PIXELS] and
 *   [ArrayRenders.INITIAL_NS_SOURCE], what a small array costs in the background).
 */
@RunWith(RobolectricTestRunner::class)
class ArrayPerfProbeTest {
    @Before
    fun notOnCi() = assumeTrue("T606-size probes run locally only", System.getenv("CI") == null)

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val w = 4000
    private val h = 5000

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    private inline fun timeMs(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    /** [runs] timed runs of [block] after one warm-up run (JIT); prints them and returns the median. */
    private inline fun measure(what: String, runs: Int, block: () -> Unit): Double {
        block()
        val times = List(runs) { timeMs(block) }
        val med = median(times)
        println("v17 array probe: $what: median ${"%.1f".format(med)} ms (${times.joinToString { "%.1f".format(it) }})")
        return med
    }

    /** A painted, partly transparent source (an opaque square with a soft disc). */
    private fun source(side: Int): Bitmap = BitmapUtils.createLayerBitmap(side, side).also { b ->
        Canvas(b).apply {
            drawColor(0x803366AA.toInt())
            drawCircle(side / 2f, side / 2f, side / 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEE8800.toInt() })
        }
    }

    @Test
    fun aPreviewFrame() {
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), Smoke.document(w, h, layers = 2, whiteBottom = true))
        try {
            val src = c.doc.layers[1]
            Canvas(src.bitmap).drawBitmap(source(1000), 1500f, 1000f, null)
            c.selectLayer(src)
            val rect = Path().apply { addRect(1500f, 1000f, 2500f, 2000f, Path.Direction.CW) }
            c.setSelection(Selection.fromPath(rect, w, h, antiAlias = false), recordUndo = false)
            assertTrue(c.arrayFromSelection())
            Smoke.pump(20)
            val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
            val base = c.activeLayer.array!!.spec.copy(mode = ArrayMode.CIRCLE, count = 64)
            // The phone's canvas with the document fitted, then at 100 %.
            val fit = min(1080f / w, 2400f / h)
            c.viewTransform.set(Matrix().apply { setScale(fit, fit) })
            val all = Rect(0, 0, w, h)
            c.tiles.update(c.compositor, all)

            // The preview's first frame hides the layer in the tiles, once.
            tool.preview(base)
            assertNotNull("the preview hides the layer", c.renderOverride)
            val hide = timeMs { c.tiles.update(c.compositor, all) }
            println("v17 array probe: preview start (the layer hidden in the tiles once), 4000 x 5000: ${"%.1f".format(hide)} ms")
            var sweep = 360f
            for (i in 0 until 3) {
                sweep -= 5f
                tool.preview(base.copy(sweepDeg = sweep))
                assertFalse("a preview frame re-renders no display tile", c.tiles.hasDirty)
            }

            val screen = BitmapUtils.createLayerBitmap(1080, 2400)
            val cv = Canvas(screen)
            fun frame() {
                sweep -= 1f
                tool.preview(base.copy(sweepDeg = sweep))
                cv.drawColor(0, PorterDuff.Mode.CLEAR)
                tool.drawOverlay(cv, c.viewTransform)
            }
            val whole = measure("preview frame (overlay, software canvas), 64 copies of 1000 x 1000, whole document", 9) { frame() }
            c.viewTransform.set(Matrix().apply { setTranslate(-1460f, -600f) })
            val full = measure("preview frame (overlay, software canvas), 64 copies of 1000 x 1000, at 100 %", 9) { frame() }
            assertFalse("no display tile re-rendered", c.tiles.hasDirty)
            assertTrue("whole $whole ms", whole <= PerfBudget.ms(10 * 33.0))
            assertTrue("100 % $full ms", full <= PerfBudget.ms(10 * 33.0))
            tool.commitPreview()
            screen.recycle()
        } finally {
            c.tiles.release()
            for (l in c.doc.layers) if (!l.isFolder) l.bitmap.recycle()
        }
    }

    @Test
    fun theRasterCache() {
        val cache = BitmapUtils.createLayerBitmap(w, h)
        val cv = Canvas(cache)
        fun probe(count: Int, side: Int, budgetMs: Double) {
            val array = LayerArray(ArraySpec(mode = ArrayMode.CIRCLE, count = count), ArrayPixels(source(side), w / 2 - side / 2, 1200))
            val med = measure("raster cache, $count copies of $side x $side on $w x $h", 3) {
                cv.drawColor(0, PorterDuff.Mode.CLEAR)
                ArrayDraw.drawPixels(cv, array)
            }
            assertTrue("$count copies of $side: $med ms", med <= PerfBudget.ms(5 * budgetMs))
        }
        probe(200, 500, 600.0)
        probe(64, 1000, 1500.0)
        cache.recycle()
    }

    @Test
    fun aVectorArraysFirstTile() {
        val anchors = List(200) { i -> VAnchor(520f + i * 5f, 900f + 60f * sin(i / 6.0).toFloat()) }
        val path = VPath(0, subpaths = listOf(VSubpath(anchors)), stroke = VStrokeStyle(color = 0xFF202020.toInt(), width = 8f))
        val content = VectorContent.EMPTY.plus(listOf(path)).first
        val data = LayerData(vector = content, array = LayerArray(ArraySpec(mode = ArrayMode.LINE, count = 64, relativeX = 0f, relativeY = 0.25f)))
        // The tile holding the source's start, and the copies below it.
        val tile = Rect(512, 768, 768, 1024)
        val bmp = BitmapUtils.createLayerBitmap(tile.width(), tile.height())
        val document = Rect(0, 0, w, h)
        val med = measure("vector array, 64 copies of a 200-anchor path, expanded and first tile", 5) {
            ArrayDraw.clearCaches()
            val eff = ArrayDraw.effectiveVector(data)!!
            val cv = Canvas(bmp)
            cv.drawColor(0, PorterDuff.Mode.CLEAR)
            cv.translate(-tile.left.toFloat(), -tile.top.toFloat())
            ArrayOps.renderTiles(cv, eff, tile, document)
        }
        assertTrue("first tile $med ms", med <= PerfBudget.ms(5 * 120.0))
    }

    @Test
    fun aTextArray() {
        val item = TextItem("Brushwork", spec = TextSpec(sizePx = 300f, color = 0xFF000000.toInt()), cx = 2000f, cy = 1200f)
        val data = LayerData(
            text = TextCodec.encode(item),
            array = LayerArray(ArraySpec(mode = ArrayMode.TRANSFORM, count = 32, moveY = 90f, turnDeg = 11f, scale = 0.97f)),
        )
        val bounds = ArrayDraw.sourceBounds(data)!!
        println("v17 array probe: the text is ${bounds.width().toInt()} x ${bounds.height().toInt()} px")
        val draw = ArraySources.sourceDraw(data, ColorMode.RGB, w, h)!!
        val cache = BitmapUtils.createLayerBitmap(w, h)
        val cv = Canvas(cache)
        val med = measure("text array, 32 copies", 3) {
            cv.drawColor(0, PorterDuff.Mode.CLEAR)
            ArrayDraw.drawWithArray(cv, data.array, bounds, draw)
        }
        assertTrue("text $med ms", med <= PerfBudget.ms(5 * 250.0))
        cache.recycle()
    }

    // ------------------------------------------------------------------ integration pass "arrayrender"

    /** A controller with a raster array of a [side]² [source] at ([left], [top]); the Array tool is current. */
    private fun rasterArray(side: Int, docW: Int = w, docH: Int = h, left: Int = docW / 2 - side / 2, top: Int = 1200): Pair<EditorController, Layer> {
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), Smoke.document(docW, docH, layers = 2))
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawBitmap(source(side), left.toFloat(), top.toFloat(), null)
        c.selectLayer(src)
        val rect = Path().apply { addRect(left.toFloat(), top.toFloat(), (left + side).toFloat(), (top + side).toFloat(), Path.Direction.CW) }
        c.setSelection(Selection.fromPath(rect, docW, docH, antiAlias = false), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        Smoke.pump(20)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        return c to c.activeLayer
    }

    /** A 4000 × 5000 controller with [aTextArray]'s text ("Brushwork", 300 px) arrayed as a whole; the Array tool is current. */
    private fun textArray(): Pair<EditorController, Layer> {
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), Smoke.document(w, h, layers = 1))
        val item = TextItem("Brushwork", spec = TextSpec(sizePx = 300f, color = 0xFF000000.toInt()), cx = 2000f, cy = 1200f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        assertTrue(c.arrayWholeLayer(text))
        Smoke.pump(20)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        return c to text
    }

    private fun release(c: EditorController) {
        c.dispose()
        for (l in c.doc.layers) if (!l.isFolder && !l.bitmap.isRecycled) l.bitmap.recycle()
        ArrayDraw.clearCaches()
    }

    /**
     * Runs the main looper in step with the clock (virtual time follows real time, so "Rendering
     * array…" shows on time) until the background render landed. Returns the ms from [t0] (the
     * commit) to the swap, and when "Rendering array…" showed (-1: never).
     */
    private fun landInRealTime(c: EditorController, t0: Long, timeoutMs: Long = 60_000): Pair<Double, Double> {
        val looper = shadowOf(Looper.getMainLooper())
        var advanced = 0L
        var slowAt = -1.0
        while (c.arrayRenders.isPending) {
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            if (elapsed > advanced) {
                looper.idleFor(Duration.ofMillis(elapsed - advanced))
                advanced = elapsed
            } else {
                looper.idle()
            }
            if (slowAt < 0 && c.arrayRenders.isSlow) slowAt = (System.nanoTime() - t0) / 1e6
            assertTrue("landed within $timeoutMs ms", elapsed < timeoutMs)
            if (c.arrayRenders.isPending) Thread.sleep(1)
        }
        return (System.nanoTime() - t0) / 1e6 to slowAt
    }

    /**
     * One §6.3 row through the controller, the way the Array tool commits it ("Edit array": a
     * handle or slider released), with the automatic policy: [specs] committed one after the
     * other (the first warms up). Prints the main thread's time (the commit, then the swap), the
     * worker's and the whole edit's; the whole edit stays within five times [budgetMs], the render
     * goes to the worker exactly when its estimate (at the speed learnt so far) is over the sync
     * budget, and "Rendering array…" shows once it has run 300 ms, not before. (A render kept
     * synchronous has landed when the commit returns: the edit is the commit.)
     */
    private fun editRow(what: String, c: EditorController, layer: Layer, specs: List<ArraySpec>, budgetMs: Double) {
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
        c.arrayRenders.policy = VectorLayers.Policy.AUTO
        val totals = ArrayList<Double>()
        for ((i, spec) in specs.withIndex()) {
            val t0 = System.nanoTime()
            tool.commit(spec)
            val commitMs = (System.nanoTime() - t0) / 1e6
            val s = c.arrayRenders.lastStats!!
            val est = s.estimateMs
            assertEquals(
                "$what: the automatic policy renders it in the background exactly when the estimate (${"%.0f".format(est)} ms) is over the budget",
                est > c.arrayRenders.syncBudgetMs,
                s.background,
            )
            assertEquals(s.background, c.arrayRenders.isPending)
            val (total, slowAt) = if (s.background) landInRealTime(c, t0) else commitMs to -1.0
            assertEquals(spec.sanitized(), layer.array!!.spec)
            val workerMs = s.workerNs / 1e6
            val mainMs = s.mainNs / 1e6
            println(
                "v17 arrayrender probe: $what${if (i == 0) " (warm-up)" else ""}: edit ${"%.1f".format(total)} ms, " +
                    "main thread ${"%.1f".format(mainMs)} ms (commit ${"%.1f".format(commitMs)}, swap about ${"%.1f".format(mainMs - commitMs)}), " +
                    "${if (s.background) "worker ${"%.1f".format(workerMs)} ms" else "synchronous"}, ${"%.1f".format(s.units / 1e6)} M units (estimate ${"%.0f".format(est)} ms), " +
                    "\"Rendering array…\" ${if (slowAt < 0) "not shown" else "at ${"%.0f".format(slowAt)} ms"}",
            )
            if (i == 0) continue
            totals += total
            if (total < 280.0) assertTrue("$what: no chip for an edit of ${"%.0f".format(total)} ms", slowAt < 0)
            if (workerMs > 360.0) assertTrue("$what: the chip shows past 300 ms (${"%.0f".format(slowAt)})", slowAt >= 295.0)
            if (slowAt >= 0) assertTrue("$what: not before 300 ms (${"%.0f".format(slowAt)})", slowAt >= 295.0)
        }
        val med = median(totals)
        println("v17 arrayrender probe: $what: median edit ${"%.1f".format(med)} ms (the phone's budget ${budgetMs.toInt()} ms is a device check; the guard here is five times it)")
        assertTrue("$what: $med ms", med <= PerfBudget.ms(5 * budgetMs))
        assertFalse(c.arrayRenders.isSlow)
    }

    /** §6.3 raster row: "Edit array" of 200 copies of 500 × 500 round a circle (≤ 600 ms, "Rendering array…" past 300 ms). */
    @Test
    fun editArrayOf200RasterCopies() {
        val (c, layer) = rasterArray(500)
        try {
            val base = layer.array!!.spec.copy(mode = ArrayMode.CIRCLE, count = 200)
            editRow("raster edit, 200 copies of 500 x 500 on $w x $h", c, layer, List(4) { base.copy(sweepDeg = 360f - 5f * it) }, 600.0)
        } finally {
            release(c)
        }
    }

    /** §6.3 raster row: "Edit array" of 64 copies of 1000 × 1000 (≤ 1.5 s). */
    @Test
    fun editArrayOf64LargeRasterCopies() {
        val (c, layer) = rasterArray(1000)
        try {
            val base = layer.array!!.spec.copy(mode = ArrayMode.CIRCLE, count = 64)
            editRow("raster edit, 64 copies of 1000 x 1000 on $w x $h", c, layer, List(3) { base.copy(sweepDeg = 360f - 5f * it) }, 1500.0)
        } finally {
            release(c)
        }
    }

    /** §6.3 text row: "Edit array" of 32 copies of a text about 1500 × 400 px in a spiral (≤ 250 ms). */
    @Test
    fun editArrayOf32TextCopies() {
        val (c, layer) = textArray()
        try {
            val base = ArraySpec(mode = ArrayMode.TRANSFORM, count = 32, moveY = 90f, turnDeg = 11f, scale = 0.97f)
            editRow("text edit, 32 copies", c, layer, List(4) { base.copy(turnDeg = 11f - 0.5f * it) }, 250.0)
        } finally {
            release(c)
        }
    }

    /**
     * The cost model behind "small arrays stay synchronous" ([ArrayRenders.SYNC_BUDGET_MS]):
     * synchronous renders of several sizes print their main-thread time per cost unit (the JVM's
     * speed, which the starting speeds are set from), and a small array rendered in the background
     * prints what the worker costs the main thread anyway (the commit and the swap) and how long
     * it takes to land. The JVM is not slower than the phone speed the estimate starts from.
     */
    @Test
    fun theBackgroundRenderCostModel() {
        val perUnit = Array(2) { ArrayList<Double>() }
        fun sync(what: String, c: EditorController, layer: Layer, specs: List<ArraySpec>) {
            val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
            c.arrayRenders.policy = VectorLayers.Policy.SYNC
            for ((i, spec) in specs.withIndex()) {
                tool.commit(spec)
                assertEquals(spec.sanitized(), layer.array!!.spec)
                val s = c.arrayRenders.lastStats!!
                val ns = s.mainNs / s.units
                val est = c.arrayRenders.estimateMs(s.kind, s.units)
                println(
                    "v17 arrayrender cost: $what${if (i == 0) " (warm-up)" else ""}: ${"%.3f".format(s.units / 1e6)} M units, " +
                        "${"%.2f".format(s.mainNs / 1e6)} ms on the main thread, ${"%.2f".format(ns)} ns per unit (phone estimate ${"%.1f".format(est)} ms)",
                )
                if (i > 0 && s.units >= 1e6) perUnit[s.kind] += ns
            }
        }
        rasterArray(48, docW = 400, docH = 300, left = 20, top = 30).let { (c, l) ->
            val b = l.array!!.spec
            sync("raster, 3 to 4 copies of 48 x 48 on 400 x 300", c, l, List(4) { b.copy(count = 3 + (it + 1) % 2) })
            sync("raster, 12 copies of 48 x 48 round a circle on 400 x 300", c, l, List(4) { b.copy(mode = ArrayMode.CIRCLE, count = 12, centerX = 200f, centerY = 150f, sweepDeg = 360f - it) })
            // A small array in the background: what the main thread still pays, and when it lands.
            c.arrayRenders.policy = VectorLayers.Policy.ASYNC
            val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
            repeat(4) { i ->
                val t0 = System.nanoTime()
                tool.commit(b.copy(count = 5 + i % 2))
                val commitMs = (System.nanoTime() - t0) / 1e6
                val (total, _) = landInRealTime(c, t0)
                val s = c.arrayRenders.lastStats!!
                println(
                    "v17 arrayrender cost: raster, 5 to 6 copies of 48 x 48 in the background${if (i == 0) " (warm-up)" else ""}: " +
                        "main thread ${"%.2f".format(s.mainNs / 1e6)} ms (commit ${"%.2f".format(commitMs)}), worker ${"%.2f".format(s.workerNs / 1e6)} ms, landed after ${"%.1f".format(total)} ms",
                )
            }
            release(c)
        }
        rasterArray(500).let { (c, l) ->
            val b = l.array!!.spec.copy(mode = ArrayMode.CIRCLE)
            sync("raster, 9 copies of 500 x 500", c, l, List(3) { b.copy(count = 9, sweepDeg = 360f - it) })
            sync("raster, 50 copies of 500 x 500", c, l, List(3) { b.copy(count = 50, sweepDeg = 360f - it) })
            sync("raster, 200 copies of 500 x 500", c, l, List(3) { b.copy(count = 200, sweepDeg = 360f - it) })
            release(c)
        }
        textArray().let { (c, l) ->
            val b = ArraySpec(mode = ArrayMode.TRANSFORM, moveY = 90f, turnDeg = 11f, scale = 0.97f)
            sync("text, 8 copies", c, l, List(3) { b.copy(count = 8, turnDeg = 11f - it) })
            sync("text, 32 copies", c, l, List(3) { b.copy(count = 32, turnDeg = 11f - it) })
            release(c)
        }
        for (kind in 0..1) {
            val xs = perUnit[kind]
            if (xs.isEmpty()) continue
            val med = median(xs)
            val start = if (kind == ArrayRenders.KIND_PIXELS) ArrayRenders.INITIAL_NS_PIXELS else ArrayRenders.INITIAL_NS_SOURCE
            println("v17 arrayrender cost: ${if (kind == 0) "bitmap pixels" else "text and shape area"}: median ${"%.2f".format(med)} ns per unit on the JVM; the phone estimate starts at ${"%.1f".format(start)}")
            assertTrue("the JVM is not slower than the phone estimate ($med > $start)", med <= PerfBudget.ms(start))
        }
    }
}
