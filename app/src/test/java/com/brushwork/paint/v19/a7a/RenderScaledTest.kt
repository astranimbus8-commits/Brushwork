package com.brushwork.paint.v19.a7a

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.RenderScaled
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.tools.select.colorrange.ColorMasks
import com.brushwork.paint.tools.select.magnetic.EdgeMap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * v1.9 A7a step 6 (design 3.2 A7a, R9): [RenderScaled], what the Magnetic lasso's edge map and
 * Color select's proxy are rendered from, off the main thread.
 * - Its size keeps the long side at most 2048 px, rounded as `EdgeMap.proxySize` (and as
 *   `ColorMasks.proxySize` for the proxy's 1280).
 * - 1:1 it is the flatten on the paper; scaled, above and below one half, it is the thumbnail
 *   the canvas gave before (`Compositor.renderThumbnail`), the paper behind a visible adjustment
 *   layer as a matte; without the paper, transparent stays transparent.
 * - A layer source is a copy (never the layer's bitmap); a canvas source is the canvas when it
 *   was taken (a layer added after it is not drawn), rendered on any thread.
 * - A layer recycled meanwhile throws a `RuntimeException` (the tools take a new source and try
 *   once more), never draws garbage.
 */
@RunWith(RobolectricTestRunner::class)
class RenderScaledTest {

    private val paper = 0xFFF2E6CC.toInt()
    private val blue = 0xFF1E50C8.toInt()
    private val red = 0xFFD02020.toInt()

    /** A [w] x [h] picture of flat 40 px blocks (every third one transparent) on [paper]. */
    private fun picture(w: Int = 480, h: Int = 320, withPaper: Boolean = true): Document {
        val doc = Document("render-scaled-test", "", w, h)
        if (withPaper) doc.paper = PaperSpec(paper)
        val bottom = BitmapUtils.createLayerBitmap(w, h)
        val top = BitmapUtils.createLayerBitmap(w, h)
        val p = Paint()
        val cb = Canvas(bottom)
        val ct = Canvas(top)
        for (by in 0 until h / 40) for (bx in 0 until w / 40) {
            val k = by * (w / 40) + bx
            if (k % 3 == 2) continue
            p.color = if (k % 2 == 0) blue else 0x80FFFF00.toInt()
            cb.drawRect(bx * 40f, by * 40f, bx * 40f + 40f, by * 40f + 40f, p)
            if (k % 5 == 0) {
                p.color = red
                ct.drawRect(bx * 40f + 8f, by * 40f + 8f, bx * 40f + 32f, by * 40f + 32f, p)
            }
        }
        doc.layers += Layer(doc.newLayerId(), "Bottom", bottom)
        doc.layers += Layer(doc.newLayerId(), "Top", top)
        return doc
    }

    private fun channelDiff(a: Int, b: Int): Int = maxOf(
        abs(Color.alpha(a) - Color.alpha(b)),
        abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)),
        abs(Color.blue(a) - Color.blue(b)),
    )

    /** [a] against [b] (the same size): at most [worst] in any channel inside a block, [mean] on average. */
    private fun assertClose(what: String, a: Bitmap, b: Bitmap, blockPx: Float, worst: Int = 2, mean: Double = 2.0) {
        assertEquals("$what: width", b.width, a.width)
        assertEquals("$what: height", b.height, a.height)
        var sum = 0L
        var worstSeen = 0
        var at = ""
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val d = channelDiff(a.getPixel(x, y), b.getPixel(x, y))
            sum += d
            // Inside a block (away from its edges, where the two filters may round apart).
            val fx = (x + 0.5f) % blockPx
            val fy = (y + 0.5f) % blockPx
            val inside = fx > 1.5f && fx < blockPx - 1.5f && fy > 1.5f && fy < blockPx - 1.5f
            if (inside && d > worstSeen) { worstSeen = d; at = "($x, $y)" }
        }
        val avg = sum.toDouble() / (a.width * a.height)
        assertTrue("$what: worst $worstSeen at $at", worstSeen <= worst)
        assertTrue("$what: mean $avg", avg <= mean)
    }

    @Test
    fun theSizeKeepsTheLongSideAtMost2048AsTheEdgeMapDoes() {
        assertEquals(2048, RenderScaled.MAX_SIDE)
        assertEquals(EdgeMap.MAX_SIDE, RenderScaled.MAX_SIDE)
        assertArrayEquals("PRINT", intArrayOf(1536, 2048), RenderScaled.size(3000, 4000))
        assertArrayEquals("small: as it is", intArrayOf(400, 300), RenderScaled.size(400, 300))
        for ((w, h) in listOf(3000 to 4000, 4000 to 3000, 2049 to 7, 4096 to 4096, 5000 to 1, 2048 to 2048, 6000 to 4501, 1 to 9000, 3508 to 2480)) {
            val (pw, ph) = EdgeMap.proxySize(w, h)
            assertArrayEquals("$w x $h", intArrayOf(pw, ph), RenderScaled.size(w, h))
        }
        assertArrayEquals("Color select's proxy", ColorMasks.proxySize(2560, 1600), RenderScaled.size(2560, 1600, ColorMasks.PROXY_MAX))
        assertThrows(IllegalArgumentException::class.java) {
            RenderScaled.renderAt(RenderScaled.Source.layer(BitmapUtils.createLayerBitmap(10, 10)), 11, 10, withPaper = true)
        }
    }

    @Test
    fun oneToOneItIsTheFlattenOnThePaper() {
        val doc = picture(240, 160)
        val src = RenderScaled.Source.canvas(doc)
        val a = RenderScaled.render(src, withPaper = true)
        val b = Compositor(doc) { null }.renderFlattened(withPaper = true)
        assertTrue("the flatten on the paper", a.sameAs(b))
        // Block 2 is transparent: the paper shows, or nothing without it.
        assertEquals(paper, a.getPixel(100, 20))
        val clear = RenderScaled.render(src, withPaper = false)
        assertEquals(0, Color.alpha(clear.getPixel(100, 20)))
        assertEquals(blue, clear.getPixel(2, 2))
    }

    @Test
    fun scaledItIsTheThumbnailTheCanvasGaveAboveAndBelowOneHalf() {
        val doc = picture()
        val src = RenderScaled.Source.canvas(doc)
        val compositor = Compositor(doc) { null }
        // 0.625 and 0.5: straight through a scaled canvas; 0.25: at twice the size, then filtered down.
        for ((max, block) in listOf(300 to 25f, 240 to 20f, 120 to 10f)) {
            val a = RenderScaled.render(src, max, withPaper = true)
            val b = compositor.renderThumbnail(max)
            assertClose("long side $max", a, b, block)
            assertEquals("$max: the paper where nothing is", paper, a.getPixel((2.5f * block).toInt(), (0.5f * block).toInt()))
        }
        // A visible adjustment layer: the paper behind, as a matte (never inverted).
        val fx = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        doc.layers += Layer(doc.newLayerId(), "Invert", fx).also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert") }
        val withFx = RenderScaled.Source.canvas(doc)
        for ((max, block) in listOf(300 to 25f, 120 to 10f)) {
            val a = RenderScaled.render(withFx, max, withPaper = true)
            val b = compositor.renderThumbnail(max)
            assertClose("long side $max under an adjustment", a, b, block)
            assertEquals("$max: the paper behind, as a matte", paper, a.getPixel((2.5f * block).toInt(), (0.5f * block).toInt()))
        }
    }

    @Test
    fun aLayerSourceIsACopyNeverTheLayersBitmap() {
        val bmp = picture(200, 120).layers[0].bitmap
        val same = RenderScaled.render(RenderScaled.Source.layer(bmp), withPaper = true)
        assertNotSame(bmp, same)
        assertTrue(same.sameAs(bmp))
        same.recycle()
        assertFalse("the layer's own bitmap is never recycled", bmp.isRecycled)
        val small = RenderScaled.render(RenderScaled.Source.layer(bmp), 50, withPaper = true)
        assertEquals(50, small.width)
        assertEquals(30, small.height)
        assertEquals(blue, small.getPixel(5, 5))
        assertFalse(bmp.isRecycled)
    }

    @Test
    fun aCanvasSourceIsTheCanvasWhenTakenRenderedOnAnyThread() {
        val doc = picture(240, 160)
        val src = RenderScaled.Source.canvas(doc)
        // A layer added after the source was taken is not drawn (the source holds the list as it was).
        val black = BitmapUtils.createLayerBitmap(240, 160).also { it.eraseColor(Color.BLACK) }
        doc.layers += Layer(doc.newLayerId(), "Later", black)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val out = pool.submit(Callable { Thread.currentThread() to RenderScaled.render(src, 120, withPaper = true) }).get()
            assertNotSame("off the main thread", Thread.currentThread(), out.first)
            assertEquals(120, out.second.width)
            assertEquals(80, out.second.height)
            assertEquals("not the layer added later", paper, out.second.getPixel(50, 10))
        } finally {
            pool.shutdown()
        }
        assertEquals("the document keeps its layers", 3, doc.layers.size)
    }

    @Test
    fun aLayerRecycledMeanwhileThrowsARuntimeExceptionNeverDrawsGarbage() {
        val doc = picture(240, 160)
        val src = RenderScaled.Source.canvas(doc)
        doc.layers[1].bitmap.recycle()
        assertThrows("1:1", RuntimeException::class.java) { RenderScaled.render(src, withPaper = true) }
        assertThrows("scaled", RuntimeException::class.java) { RenderScaled.render(src, 120, withPaper = true) }
        val bmp = BitmapUtils.createLayerBitmap(64, 64)
        val layer = RenderScaled.Source.layer(bmp)
        bmp.recycle()
        assertThrows("a layer", RuntimeException::class.java) { RenderScaled.render(layer, 32, withPaper = true) }
    }
}
