package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 foundation (§4.3, §4.9): [MultiLayerRenderOverride]. A null override and single-layer
 * overrides draw exactly as before (the v1.5 rule); a multi-layer override draws every layer of
 * its set through `drawContentFor` / `drawMaskFor`, exactly as if those layers held the content it
 * draws, and leaves the others alone (Robolectric, Skia).
 */
@RunWith(RobolectricTestRunner::class)
class MultiLayerOverrideTest {
    private val w = 60
    private val h = 40

    private fun bmp(color: Int, x: Float): Bitmap = BitmapUtils.createLayerBitmap(w, h).also {
        Canvas(it).drawRect(x, 5f, x + 25f, 35f, Paint().apply { this.color = color })
    }

    private fun doc(bitmaps: List<Bitmap>): Document {
        val d = Document("m", "m", w, h)
        bitmaps.forEachIndexed { i, b ->
            d.layers += Layer(d.newLayerId(), "L$i", b).also { l ->
                if (i == 1) l.blendMode = LayerBlendMode.MULTIPLY
                if (i == 2) l.opacity = 0.6f
            }
        }
        return d
    }

    private fun base() = listOf(bmp(0xFF2050A0.toInt(), 2f), bmp(0xFFCC8833.toInt(), 15f), bmp(0xFF22AA44.toInt(), 30f))
    private fun replacement(i: Int) = bmp(0xFF000000.toInt() or (0x30 * (i + 1) shl 16), 8f + 10f * i)

    private fun render(d: Document, ov: LayerRenderOverride?): IntArray {
        val out = BitmapUtils.createLayerBitmap(w, h)
        Compositor(d) { ov }.drawDocument(Canvas(out), null, target = CompositeTarget.identity(out))
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        return px
    }

    /** Overridden layers go through an offscreen layer: allow one level of rounding per channel. */
    private fun assertNear(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            val a = expected[i]; val b = actual[i]
            for (sh in intArrayOf(0, 8, 16, 24)) {
                val d = kotlin.math.abs((a ushr sh and 0xFF) - (b ushr sh and 0xFF))
                assertTrue("pixel $i: ${Integer.toHexString(a)} vs ${Integer.toHexString(b)}", d <= 1)
            }
        }
    }

    private class Single(override val layer: Layer, private val content: Bitmap?) : LayerRenderOverride {
        override fun drawContent(canvas: Canvas): Boolean {
            content ?: return false
            canvas.drawBitmap(content, 0f, 0f, null)
            return true
        }
    }

    private class Multi(override val layer: Layer, private val contents: Map<Layer, Bitmap>, private val handled: Boolean = true) : MultiLayerRenderOverride {
        val asked = ArrayList<Layer>()
        override val layers: Set<Layer> get() = contents.keys
        override fun drawContent(canvas: Canvas): Boolean = throw AssertionError("a multi-layer override is asked per layer")
        override fun drawContentFor(target: Layer, canvas: Canvas): Boolean {
            asked += target
            if (!handled) return false
            canvas.drawBitmap(contents.getValue(target), 0f, 0f, null)
            return true
        }
    }

    @Test
    fun nullAndSingleOverridesDrawAsBefore() {
        val d = doc(base())
        val plain = render(d, null)
        // An override of another document's layer, or one that falls back, changes nothing.
        assertArrayEquals(plain, render(d, Single(Layer(-5, "elsewhere", BitmapUtils.createLayerBitmap(w, h)), replacement(0))))
        assertNear(plain, render(d, Single(d.layers[1], null)))
        // A single override draws its layer's content in place, with the layer's blend and opacity.
        val swapped = render(d, Single(d.layers[1], replacement(1)))
        val expected = render(doc(listOf(base()[0], replacement(1), base()[2])), null)
        assertNear(expected, swapped)
        assertTrue(!plain.contentEquals(swapped))
    }

    /** Answers only for masks: [masks] per layer (null = default drawing); never asked through its own drawContent / drawMask. */
    private class MaskMulti(override val layer: Layer, override val layers: Set<Layer>, private val masks: Map<Layer, Bitmap>) : MultiLayerRenderOverride {
        val askedMask = ArrayList<Layer>()
        override fun drawContent(canvas: Canvas): Boolean = throw AssertionError("a multi-layer override is asked per layer")
        override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean = throw AssertionError("a multi-layer override is asked per layer")
        override fun drawContentFor(target: Layer, canvas: Canvas): Boolean = false
        override fun drawMaskFor(target: Layer, canvas: Canvas, maskPaint: Paint): Boolean {
            askedMask += target
            val m = masks[target] ?: return false
            canvas.drawBitmap(m, 0f, 0f, maskPaint)
            return true
        }
    }

    /** A luminance mask: white (effect / layer shows) inside [l, t, r, b], black elsewhere. */
    private fun mask(l: Float, t: Float, r: Float, b: Float): Bitmap = BitmapUtils.createMaskBitmap(w, h, 0xFF000000.toInt()).also {
        Canvas(it).drawRect(l, t, r, b, Paint().apply { color = 0xFFFFFFFF.toInt() })
    }

    /** A picture, then an Invert adjustment layer with [adjMask] above it. */
    private fun adjustmentDoc(adjMask: Bitmap): Document {
        val d = Document("a", "a", w, h)
        d.layers += Layer(d.newLayerId(), "picture", bmp(0xFF2050A0.toInt(), 10f))
        d.layers += Layer(d.newLayerId(), "invert", BitmapUtils.createLayerBitmap(w, h)).also { l ->
            l.adjustment = com.brushwork.paint.masks.AdjustmentSpec(filterId = "adjust.invert")
            l.mask = adjMask
        }
        return d
    }

    @Test
    fun anAdjustmentLayerInTheSetGetsItsMaskFromDrawMaskFor() {
        // AdjustmentStage resolves the override per layer too (MultiLayerView -> drawMaskFor).
        val left = mask(0f, 0f, w / 2f, h.toFloat())
        val top = mask(0f, 0f, w.toFloat(), h / 2f)
        val d = adjustmentDoc(left)
        val adj = d.layers[1]
        val plain = render(d, null)
        // Falling back (no mask of its own): exactly the layer's own mask applies.
        val fallback = MaskMulti(adj, setOf(adj), emptyMap())
        assertNear(plain, render(d, fallback))
        assertTrue("asked for the adjustment layer's mask", adj in fallback.askedMask)
        // Supplying a mask: as if the layer had that mask.
        val swapped = MaskMulti(adj, setOf(adj), mapOf(adj to top))
        val drawn = render(d, swapped)
        assertNear(render(adjustmentDoc(top), null), drawn)
        assertTrue(!plain.contentEquals(drawn))
        assertEquals(setOf(adj), swapped.askedMask.toSet())
    }

    @Test
    fun aMultiLayerOverrideDrawsEveryLayerOfItsSet() {
        val d = doc(base())
        val contents = mapOf(d.layers[0] to replacement(0), d.layers[2] to replacement(2))
        val multi = Multi(d.layers[0], contents)
        val drawn = render(d, multi)
        val expected = render(doc(listOf(replacement(0), base()[1], replacement(2))), null)
        assertNear(expected, drawn)
        assertEquals("asked for its own layers only", setOf(d.layers[0], d.layers[2]), multi.asked.toSet())
        // One that falls back for every layer draws the document as it is.
        assertNear(render(d, null), render(d, Multi(d.layers[0], contents, handled = false)))
    }
}
