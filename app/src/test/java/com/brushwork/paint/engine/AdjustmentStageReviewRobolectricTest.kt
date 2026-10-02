package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.MaskBrushRaster
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.MaskStroke
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * v1.5 A5 review: effects follow the document's color mode, a flattened image's background is a
 * matte behind adjustments (as the canvas shows transparency) while documents without adjustments
 * flatten exactly as before, the coverage hint of a mask being edited limits the effect, and the
 * cheap brush-stroke bounds hold every dab.
 */
@RunWith(RobolectricTestRunner::class)
class AdjustmentStageReviewRobolectricTest {
    private val w = 80
    private val h = 60

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private val invert = AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))

    private fun layer(doc: Document, name: String, fill: Int? = null): Layer =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { l -> fill?.let { l.bitmap.eraseColor(it) } }

    private fun adjustment(doc: Document): Layer = layer(doc, "Invert 1").also { it.adjustment = invert }

    @Test
    fun effectsAreConstrainedToTheDocumentsColorMode() {
        val doc = Document("m", "m", w, h)
        doc.layers += layer(doc, "Grey", 0xFF646464.toInt())
        doc.layers += adjustment(doc)
        // RGB: grey 100 inverted is grey 155.
        assertEquals(0xFF9B9B9B.toInt(), Compositor(doc) { null }.renderFlattened().getPixel(10, 10))
        // 1-bit: what the effect gives is black or white, like every committed pixel.
        doc.colorMode = ColorMode.MONOCHROME
        assertEquals(-1, Compositor(doc) { null }.renderFlattened().getPixel(10, 10))
        // Display tiles too.
        val tiles = DisplayTiles(w, h, tileSize = 32)
        tiles.update(Compositor(doc) { null }, null)
        val screen = BitmapUtils.createLayerBitmap(w, h)
        tiles.draw(Canvas(screen), null, smooth = false)
        assertEquals(-1, screen.getPixel(10, 10))
        tiles.release()
        // Transparent areas stay transparent (the coverage never changes).
        doc.layers[0].bitmap.eraseColor(0)
        assertEquals(0, Compositor(doc) { null }.renderFlattened().getPixel(10, 10))
    }

    @Test
    fun aBackgroundIsAMatteBehindAdjustments() {
        val doc = Document("j", "j", w, h)
        val photo = layer(doc, "Photo")
        Canvas(photo.bitmap).drawRect(0f, 0f, 40f, h.toFloat(), Paint().apply { color = 0xFF204080.toInt() })
        doc.layers += photo
        // Without an adjustment: exactly the v1.4 flattening (the background first).
        val v14 = BitmapUtils.createLayerBitmap(w, h)
        Canvas(v14).also { it.drawColor(-1) }.let { Compositor(doc) { null }.drawDocument(it, null, useOverrides = false, target = CompositeTarget.identity(v14)) }
        assertArrayEquals(pixels(v14), pixels(Compositor(doc) { null }.renderFlattened(-1)))
        // With an Invert adjustment: a JPG export keeps the white background where the canvas
        // is transparent (it isn't inverted to black), and the picture is inverted.
        doc.layers += adjustment(doc)
        val jpg = Compositor(doc) { null }.renderFlattened(-1)
        assertEquals(-1, jpg.getPixel(60, 30))
        assertEquals(0xFFDFBF7F.toInt(), jpg.getPixel(20, 30))
        // The scaled thumbnail path agrees.
        val big = Document("t", "t", 400, 300)
        big.layers += Layer(big.newLayerId(), "P", BitmapUtils.createLayerBitmap(400, 300)).also {
            Canvas(it.bitmap).drawRect(0f, 0f, 200f, 300f, Paint().apply { color = 0xFF204080.toInt() })
        }
        big.layers += Layer(big.newLayerId(), "A", BitmapUtils.createLayerBitmap(400, 300)).also { it.adjustment = invert }
        val thumb = Compositor(big) { null }.renderThumbnail(40, background = -1)
        assertEquals(-1, thumb.getPixel(thumb.width - 3, thumb.height / 2))
        val inv = thumb.getPixel(3, thumb.height / 2)
        assertTrue("inverted: ${Integer.toHexString(inv)}", (inv shr 16 and 0xFF) > 0xC0 && (inv and 0xFF) < 0x90)
    }

    @Test
    fun aMaskBeingEditedLimitsTheEffectToWhereItsOverrideSaysItCanShow() {
        val doc = Document("h", "h", w, h)
        doc.layers += layer(doc, "Photo", 0xFF204080.toInt())
        val adj = adjustment(doc)
        adj.mask = BitmapUtils.createMaskBitmap(w, h, 0xFF000000.toInt())
        doc.layers += adj
        // An override that draws a white mask everywhere.
        var hint: Rect? = Rect(0, 0, 20, 20)
        val white = BitmapUtils.createMaskBitmap(w, h, -1)
        val hinted = object : LayerRenderOverride, MaskCoverageHint {
            override val layer: Layer = adj
            override fun drawContent(canvas: Canvas): Boolean = false
            override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean { canvas.drawBitmap(white, 0f, 0f, maskPaint); return true }
            override fun maskCoverage(): Rect? = hint?.let { Rect(it) }
        }
        fun render(ov: LayerRenderOverride): Bitmap {
            val out = BitmapUtils.createLayerBitmap(w, h)
            Compositor(doc) { ov }.drawDocument(Canvas(out), null, useOverrides = true, target = CompositeTarget.identity(out))
            return out
        }
        val got = render(hinted)
        assertEquals("inside the hint", 0xFFDFBF7F.toInt(), got.getPixel(10, 10))
        assertEquals("outside the hint", 0xFF204080.toInt(), got.getPixel(50, 40))
        hint = null
        assertEquals("nowhere", 0xFF204080.toInt(), render(hinted).getPixel(10, 10))
        // An override that says nothing: everywhere its mask lets the effect through.
        val plain = object : LayerRenderOverride {
            override val layer: Layer = adj
            override fun drawContent(canvas: Canvas): Boolean = false
            override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean { canvas.drawBitmap(white, 0f, 0f, maskPaint); return true }
        }
        assertEquals(0xFFDFBF7F.toInt(), render(plain).getPixel(50, 40))
    }

    @Test
    fun cheapStrokeBoundsHoldEveryDab() {
        val rnd = Random(5)
        repeat(200) {
            val n = 1 + rnd.nextInt(12)
            val xs = FloatArray(n) { rnd.nextFloat() * 300f - 50f }
            val ys = FloatArray(n) { rnd.nextFloat() * 300f - 50f }
            val s = MaskStroke(rnd.nextBoolean(), 1f + rnd.nextFloat() * 60f, rnd.nextFloat(), 0.05f + rnd.nextFloat() * 0.95f, PackedPoints(xs, ys, FloatArray(n) { 1f }))
            val d = MaskBrushRaster.dabsOf(s)
            val b = MaskBrushRaster.bounds(s)!!
            assertTrue(b[0] <= d.left && b[1] <= d.top && b[2] >= d.right && b[3] >= d.bottom)
        }
        assertNull(MaskBrushRaster.bounds(MaskStroke(false, 10f, 0.5f, 0f, PackedPoints(floatArrayOf(1f), floatArrayOf(1f), floatArrayOf(1f)))))
        // A spec's coverage holds what its brush strokes paint.
        val strokes = listOf(MaskStroke(false, 18f, 0.4f, 0.8f, PackedPoints(floatArrayOf(10f, 40f, 60f), floatArrayOf(10f, 30f, 12f), floatArrayOf(1f, 1f, 1f))))
        val spec = MaskSpec(components = listOf(BrushMask(1, strokes = strokes), RadialMask(2, cx = 60f, cy = 45f, rx = 8f, ry = 6f)), nextId = 3)
        val cov = MaskSpecs.coverageBounds(spec, w, h)!!
        val px = IntArray(w * h).also { MaskSpecs.render(spec, w, h, Rect(0, 0, w, h), it, w) }
        for (y in 0 until h) for (x in 0 until w) if (px[y * w + x] and 0xFF != 0) assertTrue("($x, $y) outside $cov", cov.contains(x, y))
    }
}
