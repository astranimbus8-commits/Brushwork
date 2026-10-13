package com.brushwork.paint.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.PixelSnapshot
import com.brushwork.paint.tools.select.SampleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v1.9 F1b (C16, design 2.4.9): every [PaperBackdrop] site is v1.8 while `doc.paper == null`:
 * the flatten with `withPaper` (exports, shares, canvas sampling), the thumbnails (both scales,
 * with and without a background, with a live adjustment's matte), the SVG / PDF scene's
 * background and payload, the Eyedropper and the selection snapshot. The canvas site
 * (`CanvasView`) and `ExchangeUi.restore` only act on a non-null paper. With a paper set, the
 * hooks are live: the paper shows under transparent pixels, and never in the default flatten (I21).
 */
@RunWith(RobolectricTestRunner::class)
class PaperHookNoOpTest {
    private val w = 96
    private val h = 64
    private val white = 0xFFFFFFFF.toInt()
    private val cream = 0xFFF4ECD8.toInt()

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() = scope.cancel()

    /** Two painted layers over transparency (corners stay empty), optionally an Invert adjustment on top. */
    private fun doc(adjustment: Boolean = false): Document {
        val d = Document("p", "Paper", w, h)
        val a = Layer(d.newLayerId(), "A", BitmapUtils.createLayerBitmap(w, h))
        Canvas(a.bitmap).apply {
            drawCircle(30f, 30f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80C03020.toInt() })
            drawRect(40f, 10f, 70f, 40f, Paint().apply { color = 0xFF2050C0.toInt() })
        }
        val b = Layer(d.newLayerId(), "B", BitmapUtils.createLayerBitmap(w, h))
        Canvas(b.bitmap).drawRect(20f, 25f, 80f, 55f, Paint().apply { color = 0xC060D090.toInt() })
        b.blendMode = LayerBlendMode.MULTIPLY
        b.opacity = 0.7f
        d.layers += a
        d.layers += b
        if (adjustment) {
            d.layers += Layer(d.newLayerId(), "Invert", BitmapUtils.createLayerBitmap(w, h)).also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert") }
        }
        d.activeLayerIndex = 0
        return d
    }

    private fun controller(d: Document): EditorController = EditorController(context, d, scope, AppSettings(context)).also {
        it.viewTransform.set(Matrix())
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun liveAdjustment(d: Document) = d.layers.any { it.isAdjustmentLayer && d.effectiveVisible(it) && it.opacity > 0f }

    /** v1.8's `Compositor.renderFlattened`, verbatim. */
    private fun v18Flattened(d: Document, background: Int?): Bitmap {
        val out = BitmapUtils.createLayerBitmap(d.width, d.height)
        val c = Canvas(out)
        val matte = background != null && liveAdjustment(d)
        if (background != null && !matte) c.drawColor(background)
        Compositor(d) { null }.drawDocument(c, null, useOverrides = false, target = CompositeTarget.identity(out))
        if (matte) c.drawColor(background, PorterDuff.Mode.DST_OVER)
        return out
    }

    /** v1.8's `Compositor.renderThumbnail`, verbatim. */
    private fun v18Thumbnail(d: Document, maxSize: Int, background: Int?): Bitmap {
        val s = minOf(1f, maxSize.toFloat() / max(d.width, d.height))
        val tw = max(1, (d.width * s).roundToInt()); val th = max(1, (d.height * s).roundToInt())
        if (s >= 0.5f) {
            val full = v18Flattened(d, background)
            if (s >= 1f) return full
            return Bitmap.createScaledBitmap(full, tw, th, true)
        }
        val s2 = minOf(1f, s * 2f)
        val w2 = max(1, (d.width * s2).roundToInt()); val h2 = max(1, (d.height * s2).roundToInt())
        val mid = BitmapUtils.createLayerBitmap(w2, h2)
        val c = Canvas(mid)
        val matte = background != null && liveAdjustment(d)
        if (background != null && !matte) c.drawColor(background)
        val sx = w2.toFloat() / d.width
        val sy = h2.toFloat() / d.height
        c.save()
        c.scale(sx, sy)
        Compositor(d) { null }.drawDocument(c, null, useOverrides = false, target = CompositeTarget(mid, Matrix().apply { setScale(sx, sy) }))
        c.restore()
        if (matte) c.drawColor(background, PorterDuff.Mode.DST_OVER)
        return Bitmap.createScaledBitmap(mid, tw, th, true)
    }

    @Test
    fun theFlattenWithPaperIsV18WithoutAPaper() {
        for (adjustment in listOf(false, true)) {
            val d = doc(adjustment)
            assertNull(d.paper)
            val comp = Compositor(d) { null }
            for (bg in listOf(null, white, 0xFF336699.toInt())) {
                val v18 = pixels(v18Flattened(d, bg))
                assertArrayEquals("adjustment $adjustment, background $bg", v18, pixels(comp.renderFlattened(bg)))
                assertArrayEquals("withPaper, adjustment $adjustment, background $bg", v18, pixels(comp.renderFlattened(bg, withPaper = true)))
            }
        }
    }

    @Test
    fun theThumbnailsAreV18WithoutAPaper() {
        for (adjustment in listOf(false, true)) {
            val d = doc(adjustment)
            val comp = Compositor(d) { null }
            // 128 and 96: s = 1; 64: s >= 0.5 (scaled flatten); 20: s < 0.5 (the 2x path).
            for (size in listOf(128, 96, 64, 20)) for (bg in listOf(null, white)) {
                val v18 = v18Thumbnail(d, size, bg)
                val now = comp.renderThumbnail(size, bg)
                assertEquals(v18.width, now.width)
                assertEquals(v18.height, now.height)
                assertArrayEquals("adjustment $adjustment, size $size, background $bg", pixels(v18), pixels(now))
            }
        }
    }

    @Test
    fun thePaperShowsWhereItShouldAndNowhereElse() {
        for (adjustment in listOf(false, true)) {
            val d = doc(adjustment)
            d.paper = PaperSpec(cream)
            val comp = Compositor(d) { null }
            assertEquals("the default flatten (merges, I21) has no paper", 0, comp.renderFlattened().getPixel(0, 0))
            assertEquals(cream, comp.renderFlattened(withPaper = true).getPixel(0, 0))
            assertEquals("the paper is drawn first: the white does not show", cream, comp.renderFlattened(white, withPaper = true).getPixel(0, 0))
            assertEquals(cream, comp.renderThumbnail(96).getPixel(0, 0))
            assertEquals(cream, comp.renderThumbnail(20).getPixel(0, 0))
            assertEquals("the paper as the background", pixels(v18Flattened(d, cream)).toList(), pixels(comp.renderFlattened(withPaper = true)).toList())
            assertEquals(cream, PaperBackdrop.sampleColor(d))
        }
    }

    @Test
    fun theBackdropDrawsNothingWithoutAPaper() {
        val d = doc()
        assertNull(PaperBackdrop.sampleColor(d))
        val b = BitmapUtils.createLayerBitmap(w, h)
        PaperBackdrop.draw(Canvas(b), d)
        PaperBackdrop.draw(Canvas(b), d, RectF(0f, 0f, 10f, 10f))
        assertTrue("nothing drawn", pixels(b).all { it == 0 })
        d.paper = PaperSpec()
        PaperBackdrop.draw(Canvas(b), d, RectF(0f, 0f, 10f, 10f))
        assertEquals(white, b.getPixel(5, 5))
        assertEquals("only into dst", 0, b.getPixel(20, 20))
        PaperBackdrop.draw(Canvas(b), d)
        assertTrue("the document rect", pixels(b).all { it == white })
    }

    @Test
    fun theSceneAndThePayloadAreV18WithoutAPaper() {
        val c = controller(doc())
        fun scene(options: ExportOptions): ExportScene = runBlocking {
            ExportSceneBuilder(c, options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        }
        assertTrue("Include paper is on by default", ExportOptions(VectorFormat.SVG).includePaper)
        val plain = scene(ExportOptions(VectorFormat.SVG))
        assertNull(plain.background)
        assertNull(plain.payload!!.paper)
        assertEquals(white, scene(ExportOptions(VectorFormat.PDF, whiteBackground = true)).background)
        assertNull(scene(ExportOptions(VectorFormat.SVG, includePaper = false)).background)
        // With a paper: the background rectangle (with Include paper), and the payload carries it.
        c.doc.paper = PaperSpec(cream)
        val papered = scene(ExportOptions(VectorFormat.SVG))
        assertEquals(cream, papered.background)
        assertEquals(PaperSpec(cream), papered.payload!!.paper)
        assertEquals(cream, scene(ExportOptions(VectorFormat.SVG, whiteBackground = true)).background)
        assertNull(scene(ExportOptions(VectorFormat.SVG, includePaper = false)).background)
        assertEquals(white, scene(ExportOptions(VectorFormat.SVG, includePaper = false, whiteBackground = true)).background)
    }

    @Test
    fun canvasSamplingIsV18WithoutAPaper() {
        val d = doc()
        val c = controller(d)
        val dropper = c.tools.getValue(ToolId.EYEDROPPER) as EyedropperTool
        assertNull("a transparent spot of the canvas picks nothing", dropper.sample(2f, 2f))
        val onRect = dropper.sample(50f, 15f)
        assertEquals(0xFF2050C0.toInt(), onRect)
        val snapshot = PixelSnapshot.take(c, SampleSource.CANVAS, d.layers[0].bitmap)
        assertArrayEquals(pixels(v18Flattened(d, null)), pixels(snapshot))
        // With a paper the canvas shows it under transparent pixels; the layer's own pixels do not.
        d.paper = PaperSpec(cream)
        assertEquals(cream, dropper.sample(2f, 2f))
        assertEquals(onRect, dropper.sample(50f, 15f))
        assertEquals(cream, PixelSnapshot.take(c, SampleSource.CANVAS, d.layers[0].bitmap).getPixel(0, 0))
        dropper.settings = dropper.settings.copy(source = SampleSource.LAYER)
        assertNull("This layer: no paper", dropper.sample(2f, 2f))
        assertFalse(PixelSnapshot.take(c, SampleSource.LAYER, d.layers[0].bitmap).getPixel(0, 0) == cream)
    }
}
