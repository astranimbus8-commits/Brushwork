package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 foundation (§4.3, §4.9): `drawDocument(layerRange)`. Drawing `[0, k)` then `[k, n)` onto
 * the same target equals the full draw when `k` is an adjustment layer's index (the live
 * session's below-cache), and a range that splits a clipping group throws (Robolectric, Skia).
 */
@RunWith(RobolectricTestRunner::class)
class DrawDocumentRangeTest {
    private val w = 64
    private val h = 48

    private fun layer(doc: Document, name: String, color: Int?, block: (Layer) -> Unit = {}): Layer {
        val l = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h))
        if (color != null) Canvas(l.bitmap).drawCircle(w / 2f, h / 2f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        block(l)
        return l
    }

    /** Bottom, a layer clipped to it, an Invert adjustment, a multiply layer with a clipped layer above it. */
    private fun document(): Document {
        val doc = Document("r", "r", w, h)
        doc.layers += layer(doc, "bottom", null) { it.bitmap.eraseColor(0xFF4080C0.toInt()) }
        doc.layers += layer(doc, "clipped", 0xCCFF2200.toInt()) { it.clipping = true }
        doc.layers += layer(doc, "invert", null) { it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.opacity = 0.7f }
        doc.layers += layer(doc, "multiply", 0x9900FF66.toInt()) { it.blendMode = LayerBlendMode.MULTIPLY }
        doc.layers += layer(doc, "on top", 0x80FFFF00.toInt()) { it.clipping = true }
        return doc
    }

    private fun render(c: Compositor, vararg ranges: IntRange?): IntArray {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        val target = CompositeTarget.identity(bmp)
        val canvas = Canvas(bmp)
        for (r in ranges) c.drawDocument(canvas, null, useOverrides = false, target = target, layerRange = r)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return px
    }

    @Test
    fun belowThenFromTheAdjustmentEqualsTheFullDraw() {
        val doc = document()
        val c = Compositor(doc) { null }
        val full = render(c, null)
        assertArrayEquals(full, render(c, 0 until 2, 2 until 5))
        assertArrayEquals(full, render(c, 0 until 5))
        // An empty range at a boundary draws nothing.
        assertArrayEquals(full, render(c, 0 until 2, 2 until 2, 2 until 5, 5 until 5))
        // The effect really is there (the split is not trivially "nothing").
        val noAdjust = render(Compositor(Document("r", "r", w, h).also { d -> d.layers += doc.layers.filterIndexed { i, _ -> i != 2 } }) { null }, null)
        assertTrue(!full.contentEquals(noAdjust))
    }

    @Test
    fun aRangeThatSplitsAClippingGroupThrows() {
        val doc = document()
        val c = Compositor(doc) { null }
        val canvas = Canvas(BitmapUtils.createLayerBitmap(w, h))
        for (bad in listOf(0 until 1, 1 until 5, 0 until 4, 3 until 5, -1 until 2, 0 until 6, 4..1)) {
            var threw = false
            try {
                c.drawDocument(canvas, null, target = null, layerRange = bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("$bad must be refused", threw)
        }
    }

    @Test
    fun aRangeWithoutAdjustmentLayersCoversEverything() {
        val doc = Document("r", "r", w, h)
        doc.layers += layer(doc, "a", 0xFF112233.toInt())
        doc.layers += layer(doc, "b", 0x80445566.toInt()) { it.clipping = true }
        val c = Compositor(doc) { null }
        assertArrayEquals(render(c, null), render(c, 0 until 2))
        val bmp: Bitmap = BitmapUtils.createLayerBitmap(w, h)
        var threw = false
        try { c.drawDocument(Canvas(bmp), null, target = null, layerRange = 1 until 2) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }
}
