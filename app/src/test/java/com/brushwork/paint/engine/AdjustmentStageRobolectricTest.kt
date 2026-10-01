package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.layers.LayerOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * v1.5 A5 (§4.3c, §4.3e): adjustment layers composited live — the effect over the layers below
 * equals a filter applied through the same mask, alpha is kept, blend modes, tiles, flattened
 * images and thumbnails agree, pass-through cases, the kill switch, merge down, and the patches
 * other tools read (eyedropper, content-aware fill) include adjustments.
 */
@RunWith(RobolectricTestRunner::class)
class AdjustmentStageRobolectricTest {
    private val w = 96
    private val h = 72
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private val invert = AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))

    private fun invertPx(c: Int): Int = (c and 0xFF000000.toInt()) or (c.inv() and 0x00FFFFFF)

    private fun layer(doc: Document, name: String, fill: Int? = null): Layer =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { l -> fill?.let { l.bitmap.eraseColor(it) } }

    private fun gradientLayer(doc: Document, alphaEnd: Int = 0xFF): Layer = layer(doc, "Photo").also {
        Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2040C0.toInt(), (alphaEnd shl 24) or 0xF0E020, Shader.TileMode.CLAMP) })
    }

    private fun adjustment(doc: Document, spec: AdjustmentSpec = invert, mask: MaskSpec? = null): Layer = layer(doc, "Adjust").also {
        it.adjustment = spec
        if (mask != null) {
            it.mask = MaskSpecs.newMask(mask, w, h)
            it.maskSpec = mask
        }
    }

    private val ramp = MaskSpec(components = listOf(LinearMask(1, x0 = 10f, y0 = 0f, x1 = 80f, y1 = 0f)), nextId = 2)

    @Test
    fun anAdjustmentEqualsTheFilterAppliedThroughTheSameMask() {
        val doc = Document("a", "a", w, h)
        val photo = gradientLayer(doc)
        doc.layers += photo
        doc.layers += adjustment(doc, mask = ramp)
        val out = Compositor(doc) { null }.renderFlattened()
        // Reference: FilterSession's compose (lerp by the selection) with the mask as the selection.
        val src = pixels(photo.bitmap)
        val maskPx = IntArray(w * h).also { MaskSpecs.render(ramp, w, h, Rect(0, 0, w, h), it, w) }
        val got = pixels(out)
        var worst = 0
        for (i in src.indices) {
            val expect = FilterSessionMath.lerp255(src[i], invertPx(src[i]), maskPx[i] and 0xFF)
            for (sh in 0..24 step 8) worst = maxOf(worst, abs((expect shr sh and 0xFF) - (got[i] shr sh and 0xFF)))
        }
        assertTrue("differs by $worst", worst <= 1)
        // Without a mask the whole composite is inverted; opacity is the amount.
        doc.layers[1].mask = null
        doc.layers[1].maskSpec = null
        doc.layers[1].opacity = 0.5f
        val half = pixels(Compositor(doc) { null }.renderFlattened())
        for (i in src.indices step 37) {
            val expect = FilterSessionMath.lerp255(src[i], invertPx(src[i]), 128)
            for (sh in 0..16 step 8) assertTrue(abs((expect shr sh and 0xFF) - (half[i] shr sh and 0xFF)) <= 2)
        }
    }

    @Test
    fun transparentPixelsKeepTheirAlpha() {
        val doc = Document("t", "t", w, h)
        val photo = gradientLayer(doc, alphaEnd = 0x20)
        // Half the canvas is empty.
        Canvas(photo.bitmap).drawRect(0f, 0f, 30f, h.toFloat(), Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        doc.layers += photo
        doc.layers += adjustment(doc, mask = ramp)
        val got = pixels(Compositor(doc) { null }.renderFlattened())
        val src = pixels(photo.bitmap)
        val maskPx = IntArray(w * h).also { MaskSpecs.render(ramp, w, h, Rect(0, 0, w, h), it, w) }
        for (i in src.indices) {
            val a = src[i] ushr 24
            assertTrue("alpha kept at $i: $a -> ${got[i] ushr 24}", abs(a - (got[i] ushr 24)) <= 1)
            if (a < 64) continue // premultiplied colours of nearly transparent pixels are coarse
            val expect = FilterSessionMath.lerp255(src[i], invertPx(src[i]), maskPx[i] and 0xFF)
            for (sh in 0..16 step 8) {
                val d = abs((expect shr sh and 0xFF) - (got[i] shr sh and 0xFF))
                assertTrue("colour at $i (alpha $a) differs by $d", d <= 3 + 255 / a)
            }
        }
    }

    @Test
    fun blendModesBlendTheMappedCompositeLikeALayer() {
        for (mode in listOf(LayerBlendMode.MULTIPLY, LayerBlendMode.SCREEN, LayerBlendMode.DIFFERENCE)) {
            val doc = Document("b", "b", w, h)
            val photo = gradientLayer(doc)
            doc.layers += photo
            doc.layers += adjustment(doc, mask = ramp).also { it.blendMode = mode; it.opacity = 0.8f }
            val got = pixels(Compositor(doc) { null }.renderFlattened())
            // The same as a plain layer holding the inverted photo, with the mask, mode and opacity.
            val ref = Document("r", "r", w, h)
            ref.layers += Layer(1, "Photo", photo.bitmap)
            ref.layers += Layer(2, "Inverted", BitmapUtils.createLayerBitmap(w, h)).also { l ->
                l.bitmap.setPixels(pixels(photo.bitmap).map { invertPx(it) }.toIntArray(), 0, w, 0, 0, w, h)
                l.mask = MaskSpecs.newMask(ramp, w, h)
                l.blendMode = mode
                l.opacity = 0.8f
            }
            val want = pixels(Compositor(ref) { null }.renderFlattened())
            for (i in got.indices) for (sh in 0..24 step 8) assertTrue("$mode at $i", abs((want[i] shr sh and 0xFF) - (got[i] shr sh and 0xFF)) <= 1)
        }
    }

    @Test
    fun tilesFlattenedImagesThumbnailsAndPatchesAgree() {
        val doc = Document("g", "g", w, h)
        doc.layers += gradientLayer(doc)
        doc.layers += layer(doc, "Dot").also { Canvas(it.bitmap).drawCircle(50f, 30f, 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10C040.toInt() }) }
        doc.layers += adjustment(doc, mask = MaskSpec(components = listOf(RadialMask(1, cx = 48f, cy = 36f, rx = 40f, ry = 28f)), nextId = 2))
        doc.layers += layer(doc, "Above").also { Canvas(it.bitmap).drawRect(0f, 60f, w.toFloat(), h.toFloat(), Paint().apply { color = 0x80FF0000.toInt() }) }
        val comp = Compositor(doc) { null }
        val flat = comp.renderFlattened()
        // Display tiles (32 px, translated targets).
        val tiles = DisplayTiles(w, h, tileSize = 32)
        tiles.update(comp, null)
        val a = BitmapUtils.createLayerBitmap(w, h)
        tiles.draw(Canvas(a), null, smooth = false)
        assertArrayEquals(pixels(flat), pixels(a))
        // A patch like the eyedropper's and the fill's (translated, clipped).
        val r = Rect(17, 9, 77, 55)
        val patch = BitmapUtils.createLayerBitmap(r.width(), r.height())
        val c = Canvas(patch)
        c.translate(-r.left.toFloat(), -r.top.toFloat())
        c.clipRect(r)
        comp.drawDocument(c, r, useOverrides = false, target = CompositeTarget.translate(patch, r.left, r.top))
        for (y in 0 until r.height()) for (x in 0 until r.width()) assertEquals(flat.getPixel(r.left + x, r.top + y), patch.getPixel(x, y))
        // Thumbnails (both paths: scaled after flattening, and the scaled target).
        for (size in listOf(60, 30)) {
            val thumb = comp.renderThumbnail(size)
            val ref = Bitmap.createScaledBitmap(flat, thumb.width, thumb.height, true)
            val tp = pixels(thumb); val rp = pixels(ref)
            var bad = 0
            for (i in tp.indices) for (sh in 0..16 step 8) if (abs((tp[i] shr sh and 0xFF) - (rp[i] shr sh and 0xFF)) > 24) bad++
            assertTrue("thumbnail $size: $bad channels far off", bad <= tp.size / 20)
        }
        tiles.release()
    }

    @Test
    fun passThroughCasesDrawExactlyWhatIsBelow() {
        val doc = Document("p", "p", w, h)
        doc.layers += gradientLayer(doc)
        val before = pixels(Compositor(doc) { null }.renderFlattened())
        fun with(adj: Layer): IntArray {
            doc.layers += adj
            try { return pixels(Compositor(doc) { null }.renderFlattened()) } finally { doc.layers.remove(adj) }
        }
        assertArrayEquals("Tone at its defaults", before, with(adjustment(doc, AdjustmentEffects.defaultSpec())))
        assertArrayEquals("unknown effect", before, with(adjustment(doc, AdjustmentSpec(filterId = "adjust.from_the_future"))))
        assertArrayEquals("hidden", before, with(adjustment(doc).also { it.visible = false }))
        assertArrayEquals("no opacity", before, with(adjustment(doc).also { it.opacity = 0f }))
        assertArrayEquals("an empty mask", before, with(adjustment(doc, mask = MaskSpec())))
        AdjustmentStage.safeCompositing = true
        assertArrayEquals("safe compositing", before, with(adjustment(doc)))
        AdjustmentStage.safeCompositing = false
        // Without a target (old callers) adjustment layers are pass-through too.
        doc.layers += adjustment(doc)
        val n = BitmapUtils.createLayerBitmap(w, h)
        Compositor(doc) { null }.drawDocument(Canvas(n), null, false, target = null)
        assertArrayEquals(before, pixels(n))
    }

    @Test
    fun aPaintedMaskLimitsTheEffectToItsPixels() {
        val doc = Document("m", "m", w, h)
        val photo = gradientLayer(doc)
        doc.layers += photo
        val adj = adjustment(doc)
        adj.mask = BitmapUtils.createMaskBitmap(w, h, 0xFF000000.toInt()).also { Canvas(it).drawRect(40f, 20f, 60f, 50f, Paint().apply { color = -1 }) }
        doc.layers += adj
        val got = Compositor(doc) { null }.renderFlattened()
        assertEquals(photo.bitmap.getPixel(10, 10), got.getPixel(10, 10))
        assertEquals(invertPx(photo.bitmap.getPixel(50, 30)), got.getPixel(50, 30))
        // The bounds are re-read after the mask changes (content version).
        Canvas(adj.mask!!).drawRect(0f, 0f, 20f, 20f, Paint().apply { color = -1 })
        adj.markChanged()
        val again = Compositor(doc) { null }.renderFlattened()
        assertEquals(invertPx(photo.bitmap.getPixel(10, 10)), again.getPixel(10, 10))
    }

    // ------------------------------------------------------------------ in the editor

    private fun controller(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("e", "e", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(0xFFFF0000.toInt()) }
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    @Test
    fun aStrokeUnderAnAdjustmentIsAdjustedInTheSameFrameAndMergeDownAppliesIt() {
        val c = controller()
        val paint = c.activeLayer
        val adj = c.addAdjustmentLayer(invert, null)!!
        c.selectLayer(paint)
        c.selectTool(ToolId.BRUSH)
        c.color = 0xFF0000FF.toInt()
        c.tiles.update(c.compositor, null)
        // A live stroke (not lifted yet) is already inverted on screen.
        c.pointerDown(ToolPoint(20f, 36f))
        for (x in 21..70) c.pointerMove(ToolPoint(x.toFloat(), 36f))
        c.tiles.update(c.compositor, null)
        val screen = BitmapUtils.createLayerBitmap(w, h)
        c.tiles.draw(Canvas(screen), null, smooth = false)
        assertEquals("the live stroke is inverted (blue -> yellow)", 0xFFFFFF00.toInt(), screen.getPixel(45, 36))
        assertEquals("the background is inverted (red -> cyan)", 0xFF00FFFF.toInt(), screen.getPixel(45, 5))
        c.pointerUp(ToolPoint(70f, 36f))
        c.tiles.update(c.compositor, null)
        c.tiles.draw(Canvas(screen), null, smooth = false)
        assertEquals(0xFFFFFF00.toInt(), screen.getPixel(45, 36))
        // Eyedropper picks what is shown (the adjusted colour).
        c.selectTool(ToolId.EYEDROPPER)
        c.pointerDown(ToolPoint(45f, 5f)); c.pointerUp(ToolPoint(45f, 5f))
        assertEquals(0xFF00FFFF.toInt(), c.color)
        // Merge down applies the effect to the layer below.
        c.selectLayer(adj)
        LayerOps.mergeDown(c, adj)
        assertEquals(-1, c.doc.indexOf(adj))
        assertEquals(0xFFFFFF00.toInt(), paint.bitmap.getPixel(45, 36))
        // Only "Layer 1" got the effect: the background stays red underneath it.
        assertEquals(0xFFFF0000.toInt(), c.doc.layers[0].bitmap.getPixel(45, 5))
        c.undo()
        assertEquals(0xFF0000FF.toInt(), paint.bitmap.getPixel(45, 36))
        c.dispose()
    }

    @Test
    fun visibleFirstTilesLeaveOffScreenTilesDirtyUntilShown() {
        val doc = Document("v", "v", 200, 200)
        doc.layers += Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(200, 200)).also { it.bitmap.eraseColor(0xFF336699.toInt()) }
        val tiles = DisplayTiles(200, 200, tileSize = 64)
        val compositor = Compositor(doc) { null }
        assertTrue(tiles.update(compositor, Rect(0, 0, 60, 60)))
        assertTrue("off-screen tiles wait", tiles.hasDirty)
        val out = BitmapUtils.createLayerBitmap(200, 200)
        tiles.draw(Canvas(out), Rect(0, 0, 60, 60), smooth = false)
        assertEquals(0xFF336699.toInt(), out.getPixel(10, 10))
        assertEquals("not rendered yet", 0, out.getPixel(150, 150))
        // Scrolling there renders them.
        assertTrue(tiles.update(compositor, Rect(100, 100, 200, 200)))
        tiles.draw(Canvas(out), Rect(100, 100, 200, 200), smooth = false)
        assertEquals(0xFF336699.toInt(), out.getPixel(150, 150))
        assertTrue(tiles.update(compositor, null))
        assertTrue(!tiles.hasDirty)
        tiles.release()
    }
}
