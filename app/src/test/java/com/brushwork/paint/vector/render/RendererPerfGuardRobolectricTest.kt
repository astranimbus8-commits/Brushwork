package com.brushwork.paint.vector.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.Dab
import com.brushwork.paint.brush.DabStamper
import com.brushwork.paint.brush.StrokeDynamics
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sin

/**
 * v1.5 A1 performance guards (JVM, relative, so they hold on any machine): the replay of a
 * stroke costs a bounded multiple of stamping its dabs alone (sampling, dynamics, tapers and the
 * composite must not dominate), the cost estimate grows with the work, and a render cache serves
 * a second render of the same objects without preparing them again — with the same pixels.
 */
@RunWith(RobolectricTestRunner::class)
class RendererPerfGuardRobolectricTest {
    private val w = 900
    private val h = 600

    private fun points(n: Int): PackedPoints = PackedPoints(
        FloatArray(n) { 40f + 820f * it / (n - 1) },
        FloatArray(n) { 300f + 220f * sin(it * 0.05f) },
        FloatArray(n) { 0.5f + 0.5f * sin(it * 0.07f) },
    )

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun aReplayCostsABoundedMultipleOfStampingItsDabs() {
        val preset = BrushLibrary.byId("softround")!!.copy(size = 24f)
        val pts = points(400)
        val tips = TipCache()
        val raster = StrokeRaster(tips)
        val out = BitmapUtils.createLayerBitmap(w, h)
        val doc = Rect(0, 0, w, h)
        // The baseline: the same dabs stamped into a coverage buffer, nothing else.
        val dabs = ArrayList<Dab>()
        val dynamics = StrokeDynamics(preset, true, 3L)
        val total = StrokeRaster.collectDabs(dynamics, preset, true, pts, dabs)
        for (d in dabs) dynamics.resolve(d, total)
        val stamper = DabStamper(tips)
        val cov = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val cc = Canvas(cov)
        fun stampAll() { cov.eraseColor(0); for (d in dabs) { stamper.measure(preset, d); stamper.stamp(cc, preset, d) } }
        fun replay() = raster.render(Canvas(out), doc, preset, -16777216, 3L, true, pts, cut = doc)
        repeat(3) { stampAll(); replay() } // warm up (tips, JIT)
        var base = Long.MAX_VALUE
        var full = Long.MAX_VALUE
        repeat(5) {
            val t0 = System.nanoTime(); stampAll(); val t1 = System.nanoTime(); replay(); val t2 = System.nanoTime()
            base = minOf(base, t1 - t0); full = minOf(full, t2 - t1)
        }
        val perDab = full.toDouble() / dabs.size
        println("replay: ${dabs.size} dabs, ${"%.0f".format(perDab)} ns/dab, ${"%.2f".format(full.toDouble() / base)} x stamping alone")
        assertTrue("replay ${full / 1000} us vs stamping ${base / 1000} us", full <= 6 * base + 2_000_000)
    }

    @Test
    fun theCostEstimateFollowsTheWork() {
        val short = VStroke(1, preset = BrushLibrary.defaultBrush.copy(size = 30f), color = -1, seed = 1, stylus = false, points = PackedPoints(floatArrayOf(40f, 200f), floatArrayOf(300f, 300f), floatArrayOf(1f, 1f)))
        val big = short.copy(id = 2, points = points(100))
        val all = Rect(0, 0, w, h)
        val a = VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(short)), all)
        val b = VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(big)), all)
        assertTrue("a longer stroke costs more ($a, $b)", b > a && a > 0.0)
        // Two objects cost their sum.
        assertEquals(a + b, VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(short, big), nextId = 3), all), 1e-6 * (a + b))
        // A region holding part of the stroke costs part of it.
        val part = VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(big)), Rect(0, 0, 300, h))
        assertTrue(part < b && part > 0.0)
        assertEquals(0.0, VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(big)), Rect(0, 0, 10, 10)), 0.0)
    }

    @Test
    fun aRenderCacheServesTheSecondRenderWithTheSamePixels() {
        val path = VPath(
            1, subpaths = listOf(VSubpath(listOf(VAnchor(50f, 500f), VAnchor(300f, 80f, width = 2.5f), VAnchor(600f, 520f), VAnchor(850f, 90f)))), tension = 0.2f,
            fill = VPaint.Solid(0xFF80C0F0.toInt()), stroke = VStrokeStyle(color = 0xFF102040.toInt(), width = 9f),
        )
        val brushPath = path.copy(id = 2, fill = null, stroke = VStrokeStyle(VStrokeKind.BRUSH, 0xFF802010.toInt(), 14f, brush = BrushLibrary.byId("chalk")!!, seed = 4))
        val content = VectorContent(objects = listOf(path, brushPath), nextId = 3)
        val cache = RenderCache()
        val tips = TipCache()
        val doc = Rect(0, 0, w, h)
        val a = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.renderWith(Canvas(a), content, doc, emptySet(), tips, doc, cache, null)
        assertEquals(2L, cache.misses)
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.renderWith(Canvas(b), content, doc, emptySet(), tips, doc, cache, null)
        assertEquals("prepared once", 2L, cache.misses)
        assertTrue(cache.hits >= 2)
        assertArrayEquals(px(a), px(b))
        // Without a cache: the same pixels too.
        val c = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(c), content, doc, tips = tips, document = doc)
        assertArrayEquals(px(a), px(c))
        // Progress reaches every object; stopping returns false.
        var calls = 0
        assertTrue(VectorLayerRenderer.renderWith(Canvas(c), content, doc, emptySet(), tips, doc, cache) { _, _ -> calls++; true })
        assertEquals(2, calls)
        assertTrue(!VectorLayerRenderer.renderWith(Canvas(c), content, doc, emptySet(), tips, doc, cache) { _, _ -> false })
    }
}
