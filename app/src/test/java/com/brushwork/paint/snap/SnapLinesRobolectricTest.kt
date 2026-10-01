package com.brushwork.paint.snap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.distort.TableCountFilter
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.LayerBoundsCache
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapSource
import com.brushwork.paint.tools.transform.TransformTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Snapping to the straight lines drawn in layers (Table filters, boxes) against real Skia
 * bitmaps, the bounds / lines cache, the snapping service and the transform tool.
 */
@RunWith(RobolectricTestRunner::class)
class SnapLinesRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Idles the main thread until [done] (background scans finish on it). */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        idle()
        assertTrue("timed out", done())
    }

    private fun List<DetectedLine>.on(axis: SnapAxis) = filter { it.axis == axis }

    private fun assertPositions(expected: List<Float>, lines: List<DetectedLine>, tol: Float = 0.01f) {
        assertEquals("$lines", expected.size, lines.size)
        for ((e, l) in expected.zip(lines)) assertEquals("$lines", e, l.pos, tol)
    }

    /** Table (Count) drawn into [bmp] (black lines over what is there). */
    private fun applyTable(bmp: Bitmap, cols: Int, rows: Int, margin: Float, t: Float) {
        val f = TableCountFilter()
        val v = f.defaultValues().set("cols", cols.toFloat()).set("rows", rows.toFloat()).set("margin", margin)
            .set("space", 0f).set("thickness", t).set("color", BLACK)
        val out: PixelBuffer = f.apply(BitmapUtils.toPixelBuffer(bmp), v, FilterContext())
        BitmapUtils.writePixelBuffer(bmp, out)
    }

    private fun bitmap(w: Int, h: Int, color: Int = 0): Bitmap = BitmapUtils.createLayerBitmap(w, h).apply { if (color != 0) eraseColor(color) }

    // ------------------------------------------------------------------ detector on bitmaps

    @Test
    fun aRegionIsReadWithItsBorderAndResultsAreInDocumentPixels() {
        // A table with a 40 px margin on a transparent 500 x 400 layer; its content bounds start
        // at the outer edge of the border line (38): the border is still found at its middle.
        val bmp = bitmap(500, 400)
        applyTable(bmp, 4, 3, 40f, 4f)
        val bounds = Rect(38, 38, 462, 362)
        val lines = LineDetector.detect(bmp, bounds)
        assertPositions(listOf(40f, 145f, 250f, 355f, 460f), lines.on(SnapAxis.X))
        assertPositions(listOf(40f, 146.667f, 253.333f, 360f), lines.on(SnapAxis.Y))
        val top = lines.on(SnapAxis.Y)[0]
        assertEquals(38f, top.start, 0.5f)
        assertEquals(462f, top.end, 0.5f)
        // Only inside the region: the right half alone gives the lines there.
        val right = LineDetector.detect(bmp, Rect(300, 0, 500, 400))
        assertEquals(listOf(355f, 460f), right.on(SnapAxis.X).map { it.pos })
        assertTrue(right.on(SnapAxis.Y).all { it.start >= 299f })
        assertTrue(LineDetector.detect(bmp, Rect(600, 0, 700, 10)).isEmpty())
    }

    @Test
    fun aPageOfTextGivesNoMoreThanAFewLines() {
        val words = "The quick brown fox jumps over the lazy dog, then 12 more lines of text follow here".split(' ')
        // Every row the same (letters stacked in columns) or different rows, as in real text.
        for (same in listOf(true, false)) for (bg in listOf(WHITE, 0)) {
            val bmp = bitmap(1080, 1400, bg)
            val c = Canvas(bmp)
            for ((i, size) in listOf(28f, 36f, 44f, 56f).withIndex()) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = BLACK }
                for (row in 0 until 7) {
                    val shift = if (same) 0 else (row * 5 + i * 3) % words.size
                    val text = (words.drop(shift) + words.take(shift)).joinToString(" ")
                    c.drawText(text, 30f, 80f + i * 330f + row * size * 1.25f, paint)
                }
            }
            val lines = LineDetector.detect(bmp)
            println("text page (same rows $same, bg ${Integer.toHexString(bg)}): ${lines.size} lines ${lines.map { "${it.axis}@${it.pos} ${it.start}..${it.end}" }}")
            assertTrue("lines: $lines", lines.size <= 4)
        }
    }

    @Test
    fun drawnStrokesAndOutlinesAreLines() {
        val bmp = bitmap(600, 500, WHITE)
        val c = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLACK; style = Paint.Style.STROKE; strokeWidth = 6f }
        // A rectangle outline (e.g. the shape tool) and a ruler-straight stroke.
        c.drawRect(100f, 80f, 400f, 300f, stroke)
        c.drawLine(50f, 420.5f, 550f, 420.5f, stroke)
        val lines = LineDetector.detect(bmp)
        assertEquals(listOf(100f, 400f), lines.on(SnapAxis.X).map { it.pos })
        assertEquals(listOf(80f, 300f, 420.5f), lines.on(SnapAxis.Y).map { it.pos })
    }

    // ------------------------------------------------------------------ cache

    @Test
    fun theCacheFindsLinesInTheBackgroundAndDropsStaleOnes() {
        var updates = 0
        val cache = LayerBoundsCache(scope, detectLines = true) { updates++ }
        // 400 x 400: bounds right away, lines (a big table) in the background.
        val layer = Layer(1, "Table", bitmap(400, 400))
        applyTable(layer.bitmap, 2, 2, 20f, 4f)
        layer.markChanged()
        cache.request(listOf(layer))
        assertEquals(Rect(18, 18, 382, 382), cache.bounds(layer))
        assertTrue(cache.lines(layer).isEmpty())
        assertFalse(cache.isKnown(layer))
        assertTrue(cache.isBusy)
        // Changed while it is being looked at (the result comes back on the main thread, after
        // this): dropped...
        idle()
        Canvas(layer.bitmap).drawRect(0f, 0f, 400f, 400f, Paint().apply { color = WHITE })
        applyTable(layer.bitmap, 3, 3, 40f, 4f)
        layer.markChanged()
        waitFor { !cache.isBusy }
        assertTrue(cache.lines(layer).isEmpty())
        assertFalse(cache.isKnown(layer))
        // ...and the new content is looked at when asked again.
        cache.request(listOf(layer))
        waitFor { !cache.isBusy }
        assertTrue(cache.isKnown(layer))
        assertEquals(Rect(0, 0, 400, 400), cache.bounds(layer))
        assertPositions(listOf(40f, 146.667f, 253.333f, 360f), cache.lines(layer).on(SnapAxis.X))
        assertTrue(updates >= 2)

        // Tiny content: bounds and lines right away.
        val small = Layer(2, "Box", bitmap(400, 400))
        Canvas(small.bitmap).drawRect(10f, 10f, 70f, 50f, Paint().apply { color = BLACK })
        small.markChanged()
        cache.request(listOf(small), all = listOf(layer, small))
        assertTrue(cache.isKnown(small))
        assertEquals(listOf(10f, 70f), cache.lines(small).on(SnapAxis.X).map { it.pos })
        assertEquals(listOf(10f, 30f, 50f), cache.lines(small).on(SnapAxis.Y).map { it.pos })
        // Deleted layers are forgotten.
        cache.request(emptyList(), all = listOf(small))
        assertFalse(cache.isKnown(layer))
    }

    // ------------------------------------------------------------------ snapping

    /** A [w] x [h] document: a white "Background", a transparent "Table" layer, the top one active. */
    private fun setup(w: Int, h: Int, layers: List<String>): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        for (name in layers) doc.layers += Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h))
        doc.activeLayerIndex = layers.size - 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    @Test
    fun aSnappedPointLandsExactlyOnATableLine() {
        // 500 x 400; Table (Count) 4 x 4, margin 50, 4 px lines: x = 50, 150, 250, 350, 450 and
        // y = 50, 125, 200, 275, 350. Once on its own transparent layer above a white
        // background, once on the white layer itself.
        for (sameLayer in listOf(false, true)) {
            val c = setup(500, 400, listOf("Background", "Table", "Drawing"))
            val bg = c.doc.layers[0]
            bg.bitmap.eraseColor(WHITE)
            bg.markChanged()
            val table = if (sameLayer) bg else c.doc.layers[1]
            applyTable(table.bitmap, 4, 4, 50f, 4f)
            table.markChanged()
            c.snapping.prepare()
            waitFor { !c.snapping.isBusy }
            assertEquals(5, c.snapping.lines(table).on(SnapAxis.X).size)
            val s = c.newSnapSession()
            s.begin(exclude = listOf(c.activeLayer))
            assertEquals(Vec2(150f, 125f), s.snapPoint(Vec2(153.5f, 128f)))
            val name = table.name
            assertTrue("guides ${s.guides}", s.guides.any { it.axis == SnapAxis.X && it.pos == 150f && it.label == "$name line" && it.source == SnapSource.LINE })
            assertTrue("guides ${s.guides}", s.guides.any { it.axis == SnapAxis.Y && it.pos == 125f && it.label == "$name line" })
            // Far from any line: free.
            assertEquals(Vec2(100f, 90f), s.snapPoint(Vec2(100f, 90f)))
            s.end()
            // Snapping off: nothing.
            c.snapping.enabled = false
            s.begin()
            assertEquals(Vec2(153.5f, 128f), s.snapPoint(Vec2(153.5f, 128f)))
            s.end()
        }
    }

    @Test
    fun linesOnALayersBoundsOrTheCanvasAreNotRepeated() {
        val c = setup(500, 400, listOf("Box", "Table", "Top"))
        // A filled box: its sides and middle lines are its bounds lines already.
        Canvas(c.doc.layers[0].bitmap).drawRect(100f, 100f, 180f, 160f, Paint().apply { color = BLACK })
        c.doc.layers[0].markChanged()
        // A table on the canvas edges (no margin): its border is on the canvas lines.
        applyTable(c.doc.layers[1].bitmap, 2, 2, 0f, 4f)
        c.doc.layers[1].markChanged()
        c.snapping.prepare()
        waitFor { !c.snapping.isBusy }
        assertTrue(c.snapping.lines(c.doc.layers[0]).isNotEmpty())
        val t = c.snapping.targets(exclude = listOf(c.doc.layers[2]))
        val drawn = (t.xs + t.ys).filter { it.source == SnapSource.LINE }
        assertTrue("$drawn", drawn.none { it.name == "Box" })
        // The table's lines: x = 0, 250, 500 / y = 0, 200, 400 are all canvas lines.
        assertTrue("$drawn", drawn.none { it.name == "Table" })
        assertTrue(SnapService.duplicatesBoxLine(DetectedLine(SnapAxis.X, 100.4f, 0f, 1f, 1f), DocBox(100f, 0f, 200f, 50f), 0f, 0f))
        assertFalse(SnapService.duplicatesBoxLine(DetectedLine(SnapAxis.X, 101f, 0f, 1f, 1f), DocBox(100f, 0f, 200f, 50f), 0f, 0f))
        assertTrue(SnapService.duplicatesBoxLine(DetectedLine(SnapAxis.Y, 25f, 0f, 1f, 1f), DocBox(100f, 0f, 200f, 50f), 0f, 0f))
        assertTrue(SnapService.duplicatesBoxLine(DetectedLine(SnapAxis.Y, 200f, 0f, 1f, 1f), null, 500f, 400f))
    }

    @Test
    fun theTransformToolPutsABoxEdgeOnATableLine() {
        val c = setup(500, 400, listOf("Table", "Block"))
        val table = c.doc.layers[0]
        table.bitmap.eraseColor(WHITE)
        applyTable(table.bitmap, 4, 4, 50f, 4f)
        table.markChanged()
        val block = c.doc.layers[1]
        Canvas(block.bitmap).drawRect(20f, 300f, 60f, 320f, Paint().apply { color = RED })
        block.markChanged()
        c.selectTool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        waitFor { t.hasPendingWork && !t.isFindingSnapTargets }
        assertEquals(DocBox(20f, 300f, 60f, 320f), t.transformState!!.bounds())
        // Raw box (153, 228)-(193, 248): its left side 3 px right of the line at x = 150; nothing
        // within reach vertically (lines at 200 and 275).
        c.pointerDown(ToolPoint(40f, 310f))
        c.pointerMove(ToolPoint(100f, 280f))
        c.pointerMove(ToolPoint(173f, 238f))
        assertEquals(DocBox(150f, 228f, 190f, 248f), t.transformState!!.bounds())
        assertTrue("guides ${t.activeGuides}", t.activeGuides.any { it.axis == SnapAxis.X && it.pos == 150f && it.label == "Table line" })
        c.pointerUp(ToolPoint(173f, 238f))
        assertEquals(DocBox(150f, 228f, 190f, 248f), t.transformState!!.bounds())
        // Its bottom near the line at y = 275 (raw 279): the bottom lands on it.
        c.pointerDown(ToolPoint(170f, 238f))
        c.pointerMove(ToolPoint(170f, 255f))
        c.pointerMove(ToolPoint(170f, 269f))
        assertEquals(275f, t.transformState!!.bounds().bottom, 0f)
        c.pointerUp(ToolPoint(170f, 269f))
        t.commit()
        assertEquals(RED, block.bitmap.getPixel(150, 260))
        assertEquals(0, block.bitmap.getPixel(149, 260))
        assertNotNull(c.snapping.bounds(table))
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
