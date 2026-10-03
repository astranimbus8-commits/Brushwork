package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.1 C2: [MaskFactorCache] (a live proxy's mask factors kept between frames). A draw that
 * reads the cache gives exactly the pixels of a draw that resamples the mask; a mask change with
 * a new content version, a new mask bitmap, another matrix or [MaskFactorCache.clear] samples
 * again; a render override of the layer bypasses the cache.
 */
@RunWith(RobolectricTestRunner::class)
class MaskFactorCacheRobolectricTest {
    private val w = 900
    private val h = 700

    @After
    fun tearDown() {
        AdjustmentStage.safeCompositing = false
    }

    private fun document(): Pair<Document, Layer> {
        val doc = Document("c", "c", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF204070.toInt(), 0xFFE0B070.toInt(), Shader.TileMode.CLAMP) })
        }
        val tone = FilterRegistry.byId("adjust.tone")!!
        val spec = MaskSpec(components = listOf(RadialMask(1, cx = 400f, cy = 300f, rx = 300f, ry = 220f, feather = 0.6f)), nextId = 2)
        val adj = Layer(doc.newLayerId(), "Tone 1", BitmapUtils.createLayerBitmap(w, h)).also {
            it.adjustment = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 1.1f))
            it.mask = MaskSpecs.newMask(spec, w, h, null)
            it.maskSpec = spec
        }
        doc.layers += adj
        return doc to adj
    }

    /** The composite at [scale] into a fresh target, with [cache] (or none), through [comp]. */
    private fun render(comp: Compositor, scale: Float, cache: MaskFactorCache?): IntArray {
        val tw = (w * scale).toInt(); val th = (h * scale).toInt()
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val m = Matrix().apply { setScale(scale, scale) }
        cv.concat(m)
        val clip = Rect(0, 0, w, h)
        cv.clipRect(clip)
        comp.drawDocument(cv, clip, target = CompositeTarget(bmp, m, display = true, maskCache = cache))
        return IntArray(tw * th).also { bmp.getPixels(it, 0, tw, 0, 0, tw, th) }
    }

    @Test
    fun cachedFactorsGiveExactlyTheResampledPixels() {
        val (doc, adj) = document()
        val comp = Compositor(doc) { null }
        val cache = MaskFactorCache()
        val plain = render(comp, 0.5f, null)
        val first = render(comp, 0.5f, cache)
        assertTrue("sampled once", cache.byteCount >= (w / 2L) * (h / 2L))
        val second = render(comp, 0.5f, cache)
        assertArrayEquals("first draw (sampling) = no cache", plain, first)
        assertArrayEquals("second draw (cached) = no cache", plain, second)
        // A quarter: another matrix samples again.
        assertArrayEquals(render(comp, 0.25f, null), render(comp, 0.25f, cache))
    }

    @Test
    fun aChangedMaskIsSampledAgain() {
        val (doc, adj) = document()
        val comp = Compositor(doc) { null }
        val cache = MaskFactorCache()
        render(comp, 0.5f, cache)
        // New mask pixels with a new content version (what every committed mask edit does).
        val moved = MaskSpec(components = listOf(RadialMask(1, cx = 600f, cy = 400f, rx = 200f, ry = 200f)), nextId = 2)
        MaskSpecs.renderInto(adj.mask!!, moved, Rect(0, 0, w, h))
        adj.maskSpec = moved
        adj.markChanged()
        assertArrayEquals(render(comp, 0.5f, null), render(comp, 0.5f, cache))
        // Pixels changed without a content version: clear() is the owner's job (a foreign change).
        Canvas(adj.mask!!).drawColor(0xFF000000.toInt(), PorterDuff.Mode.SRC)
        cache.clear()
        val black = render(comp, 0.5f, cache)
        assertArrayEquals(render(comp, 0.5f, null), black)
        // Another mask bitmap: sampled again.
        adj.mask = MaskSpecs.newMask(moved, w, h, null)
        assertArrayEquals(render(comp, 0.5f, null), render(comp, 0.5f, cache))
    }

    @Test
    fun anOverrideOfTheLayerBypassesTheCache() {
        val (doc, adj) = document()
        var override: LayerRenderOverride? = null
        val comp = Compositor(doc) { override }
        val cache = MaskFactorCache()
        val cached = render(comp, 0.5f, cache)
        // A mask painted live (no content version yet) behind an override that leaves the mask
        // to the default: the cache must not be read.
        override = object : LayerRenderOverride {
            override val layer: Layer = adj
            override fun drawContent(canvas: Canvas): Boolean = false
        }
        Canvas(adj.mask!!).drawColor(-1, PorterDuff.Mode.SRC)
        val live = render(comp, 0.5f, cache)
        assertArrayEquals(render(comp, 0.5f, null), live)
        assertTrue("the live mask shows", !live.contentEquals(cached))
        assertEquals(w / 2 * (h / 2), live.size)
    }
}
