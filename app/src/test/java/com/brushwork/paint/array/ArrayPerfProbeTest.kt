package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
 * - a text array of 32 copies of a text about 1500 × 400 px in a spiral (≤ 250 ms).
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
}
