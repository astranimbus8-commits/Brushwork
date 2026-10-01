package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.brush.CoveragePainter
import com.brushwork.paint.brush.CoverageStyle
import com.brushwork.paint.brush.PaperGrain
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v1.5 foundation, invariant I5 (§5.10 item 1): without adjustment layers the compositor draws
 * exactly what v1.4 drew — tiles, flattened images and thumbnails, all 17 blend modes, masks,
 * clipping groups and render overrides — and a coverage style without a shader paints exactly as
 * v1.4's painter. The v1.4 code (3f3e849) is copied below as the reference.
 */
@RunWith(RobolectricTestRunner::class)
class CompositorGoldenRobolectricTest {
    private val w = 96
    private val h = 72

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Layers in every blend mode over a gradient, with masks (on / off), clipping groups (one on a hidden base). */
    private fun richDoc(): Document {
        val doc = Document("g", "g", w, h)
        val base = Layer(doc.newLayerId(), "Base", BitmapUtils.createLayerBitmap(w, h))
        Canvas(base.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2040C0.toInt(), 0x80F0E020.toInt(), Shader.TileMode.CLAMP) })
        doc.layers += base
        LayerBlendMode.entries.forEachIndexed { i, mode ->
            val l = Layer(doc.newLayerId(), mode.name, BitmapUtils.createLayerBitmap(w, h))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (0x60 + i * 9 shl 24) or (0x00FFFFFF and (0x1F7A33 * (i + 3))) }
            Canvas(l.bitmap).drawCircle(10f + (i % 6) * 15f, 12f + (i / 6) * 22f, 14f, p)
            l.blendMode = mode
            l.opacity = 0.35f + (i % 4) * 0.2f
            if (i % 3 == 0) {
                l.mask = BitmapUtils.createMaskBitmap(w, h, 0xFF000000.toInt()).also { m ->
                    Canvas(m).drawRect(0f, 0f, w / 2f + i, h.toFloat(), Paint().apply { color = 0xFFB0B0B0.toInt() })
                }
                l.maskEnabled = i % 2 == 0
            }
            if (i % 5 == 4) l.clipping = true
            doc.layers += l
        }
        // A clipping group on a hidden base.
        val hidden = Layer(doc.newLayerId(), "Hidden base", BitmapUtils.createLayerBitmap(w, h)).also {
            it.bitmap.eraseColor(0xFF00FF00.toInt()); it.visible = false
        }
        val clipped = Layer(doc.newLayerId(), "Clipped", BitmapUtils.createLayerBitmap(w, h)).also {
            it.bitmap.eraseColor(0xFFFF00FF.toInt()); it.clipping = true
        }
        doc.layers += hidden
        doc.layers += clipped
        return doc
    }

    /** An override of [layer]'s content (a live stroke) and of its mask. */
    private fun override(layer: Layer) = object : LayerRenderOverride {
        override val layer: Layer = layer
        override fun drawContent(canvas: Canvas): Boolean {
            canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
            canvas.drawRect(30f, 30f, 60f, 50f, Paint().apply { color = 0xC0FF8800.toInt() })
            return true
        }
        override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
            val m = layer.mask ?: return false
            val save = canvas.saveLayer(null, maskPaint)
            canvas.drawBitmap(m, 0f, 0f, null)
            canvas.drawRect(0f, 0f, 20f, 20f, Paint().apply { color = -1 })
            canvas.restoreToCount(save)
            return true
        }
    }

    @Test
    fun drawDocumentIsBitIdenticalToV14() {
        val doc = richDoc()
        for (ovIndex in listOf(-1, 1, 4)) {
            val ov = if (ovIndex < 0) null else override(doc.layers[ovIndex])
            val now = Compositor(doc) { ov }
            val old = V14Compositor(doc) { ov }
            for (useOverrides in listOf(true, false)) {
                // The whole document, and a display tile (translated, clipped) as DisplayTiles draws it.
                val a = BitmapUtils.createLayerBitmap(w, h)
                val b = BitmapUtils.createLayerBitmap(w, h)
                now.drawDocument(Canvas(a), null, useOverrides, target = CompositeTarget.identity(a))
                old.drawDocument(Canvas(b), null, useOverrides)
                assertArrayEquals("whole, override $ovIndex / $useOverrides", pixels(b), pixels(a))
                val tile = Rect(40, 24, 88, 64)
                val ta = Bitmap.createBitmap(tile.width(), tile.height(), Bitmap.Config.ARGB_8888)
                val tb = Bitmap.createBitmap(tile.width(), tile.height(), Bitmap.Config.ARGB_8888)
                for ((bmp, draw) in listOf(ta to { c: Canvas -> now.drawDocument(c, tile, useOverrides, target = CompositeTarget.translate(ta, tile.left, tile.top)) }, tb to { c: Canvas -> old.drawDocument(c, tile, useOverrides) })) {
                    val c = Canvas(bmp)
                    c.translate(-tile.left.toFloat(), -tile.top.toFloat())
                    c.clipRect(tile)
                    draw(c)
                }
                assertArrayEquals("tile, override $ovIndex / $useOverrides", pixels(tb), pixels(ta))
                // Without a target (pass-through) the drawing is the same too.
                val n = BitmapUtils.createLayerBitmap(w, h)
                now.drawDocument(Canvas(n), null, useOverrides, target = null)
                assertArrayEquals(pixels(b), pixels(n))
            }
        }
    }

    @Test
    fun flattenedImagesAndThumbnailsAreBitIdenticalToV14() {
        val doc = richDoc()
        val now = Compositor(doc) { null }
        val old = V14Compositor(doc) { null }
        assertArrayEquals(pixels(old.renderFlattened()), pixels(now.renderFlattened()))
        assertArrayEquals(pixels(old.renderFlattened(-1)), pixels(now.renderFlattened(-1)))
        // Both thumbnail paths: >= 0.5 (flattened then scaled) and the scaled-canvas path.
        for (size in listOf(80, 40, 16)) {
            assertArrayEquals("thumbnail $size", pixels(old.renderThumbnail(size)), pixels(now.renderThumbnail(size)))
            assertArrayEquals("thumbnail $size on white", pixels(old.renderThumbnail(size, -1)), pixels(now.renderThumbnail(size, -1)))
        }
    }

    @Test
    fun displayTilesDrawWhatV14Drew() {
        val doc = richDoc()
        val ov = override(doc.layers[1])
        val tiles = DisplayTiles(w, h, tileSize = 32)
        tiles.update(Compositor(doc) { ov }, Rect(0, 0, w, h))
        val a = BitmapUtils.createLayerBitmap(w, h)
        tiles.draw(Canvas(a), null, smooth = false)
        // v1.4's DisplayTiles.update, tile by tile.
        val old = V14Compositor(doc) { ov }
        val b = BitmapUtils.createLayerBitmap(w, h)
        for (row in 0 until (h + 31) / 32) for (col in 0 until (w + 31) / 32) {
            val tr = Rect(col * 32, row * 32, minOf(w, col * 32 + 32), minOf(h, row * 32 + 32))
            val tile = Bitmap.createBitmap(tr.width(), tr.height(), Bitmap.Config.ARGB_8888)
            val c = Canvas(tile)
            c.translate(-tr.left.toFloat(), -tr.top.toFloat())
            c.clipRect(tr)
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            old.drawDocument(c, tr)
            Canvas(b).drawBitmap(tile, tr.left.toFloat(), tr.top.toFloat(), Paint())
        }
        assertArrayEquals(pixels(b), pixels(a))
        tiles.release()
    }

    @Test
    fun adjustmentLayersAreTheirOwnGroupAndPassThroughForNow() {
        val doc = richDoc()
        val before = Compositor(doc) { null }.renderFlattened()
        // An adjustment layer inserted mid-stack with a clipped layer right above it: the
        // adjustment is never a clipping base, so the layer above draws unclipped (as its own
        // group); the foundation stage is pass-through.
        val adj = Layer(doc.newLayerId(), "Tone 1", BitmapUtils.createLayerBitmap(w, h)).also { it.adjustment = AdjustmentSpec() }
        val idx = 5
        doc.layers.add(idx, adj)
        val above = doc.layers[idx + 1]
        val wasClipping = above.clipping
        above.clipping = false
        val unclipped = Compositor(doc) { null }.renderFlattened()
        above.clipping = true
        val flagged = Compositor(doc) { null }.renderFlattened()
        assertArrayEquals("a clipping flag right above an adjustment layer is ignored", pixels(unclipped), pixels(flagged))
        above.clipping = wasClipping
        doc.layers.remove(adj)
        assertArrayEquals(pixels(before), pixels(Compositor(doc) { null }.renderFlattened()))
    }

    @Test
    fun coverageWithoutAShaderPaintsAsV14() {
        val cov = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(cov).drawCircle(40f, 30f, 25f, Paint(Paint.ANTI_ALIAS_FLAG))
        val sel = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(sel).drawRect(0f, 0f, 50f, h.toFloat(), Paint().apply { alpha = 200 })
        for (mode in listOf(PorterDuff.Mode.SRC_OVER, PorterDuff.Mode.DST_OUT, PorterDuff.Mode.SRC_ATOP)) {
            for (grain in listOf(0f, 0.6f)) {
                for (selection in listOf(null, sel)) {
                    for (bounds in listOf(Rect(0, 0, w, h), Rect(20, 10, 60, 50))) {
                        val a = BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0x8030A0FF.toInt()) }
                        val b = BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0x8030A0FF.toInt()) }
                        CoveragePainter().draw(Canvas(a), cov, bounds, CoverageStyle(mode, 0xFFAA2211.toInt(), 0.8f, grain), selection)
                        V14CoveragePainter().draw(Canvas(b), cov, bounds, V14Style(mode, 0xFFAA2211.toInt(), 0.8f, grain), selection)
                        assertArrayEquals("$mode grain $grain selection ${selection != null} $bounds", pixels(b), pixels(a))
                    }
                }
            }
        }
        // A painter that painted with a shader once paints plain colors exactly again afterwards.
        val p = CoveragePainter()
        val shaded = BitmapUtils.createLayerBitmap(w, h)
        val src = BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0xFF00FF00.toInt()) }
        p.draw(Canvas(shaded), cov, Rect(0, 0, w, h), CoverageStyle(PorterDuff.Mode.SRC_OVER, -16777216, 1f, 0f, BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)), null)
        assertTrue(shaded.getPixel(40, 30) == 0xFF00FF00.toInt())
        val a = BitmapUtils.createLayerBitmap(w, h)
        val b = BitmapUtils.createLayerBitmap(w, h)
        p.draw(Canvas(a), cov, Rect(0, 0, w, h), CoverageStyle(PorterDuff.Mode.SRC_OVER, 0xFF123456.toInt(), 1f, 0f), null)
        V14CoveragePainter().draw(Canvas(b), cov, Rect(0, 0, w, h), V14Style(PorterDuff.Mode.SRC_OVER, 0xFF123456.toInt(), 1f, 0f), null)
        assertArrayEquals(pixels(b), pixels(a))
    }

    // ====================================================================== v1.4 reference copies (3f3e849)

    private class V14Compositor(private val doc: Document, private val overrideProvider: () -> LayerRenderOverride?) {
        private val maskPaint = BitmapUtils.newMaskApplyPaint()
        private val dstInPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun drawDocument(canvas: Canvas, clip: Rect?, useOverrides: Boolean = true) {
            val bounds = RectF(clip ?: doc.bounds)
            val override = if (useOverrides) overrideProvider() else null
            val layers = doc.layers
            var i = 0
            while (i < layers.size) {
                val base = layers[i]
                var j = i + 1
                while (j < layers.size && layers[j].clipping) j++
                if (base.visible && base.opacity > 0f) {
                    val clips = if (j > i + 1) layers.subList(i + 1, j).filter { it.visible && it.opacity > 0f } else emptyList()
                    drawGroup(canvas, base, clips, bounds, override)
                }
                i = j
            }
        }

        private fun drawGroup(canvas: Canvas, base: Layer, clips: List<Layer>, bounds: RectF, override: LayerRenderOverride?) {
            val groupPaint = BlendModes.paint(base.blendMode, base.opacity)
            if (clips.isEmpty()) {
                drawLayer(canvas, base, groupPaint, bounds, override)
                return
            }
            val save = canvas.saveLayer(bounds, groupPaint)
            drawLayer(canvas, base, null, bounds, override)
            for (c in clips) {
                val cs = canvas.saveLayer(bounds, BlendModes.paint(c.blendMode, c.opacity))
                drawLayer(canvas, c, null, bounds, override)
                val bs = canvas.saveLayer(bounds, dstInPaint)
                drawLayer(canvas, base, null, bounds, override)
                canvas.restoreToCount(bs)
                canvas.restoreToCount(cs)
            }
            canvas.restoreToCount(save)
        }

        private fun drawLayer(canvas: Canvas, layer: Layer, paint: Paint?, bounds: RectF, override: LayerRenderOverride?) {
            val ov = if (override != null && override.layer === layer) override else null
            val mask = if (layer.maskEnabled) layer.mask else null
            if (ov == null && mask == null) {
                canvas.drawBitmap(layer.bitmap, 0f, 0f, paint ?: plainPaint)
                return
            }
            val save = canvas.saveLayer(bounds, paint)
            if (ov == null || !ov.drawContent(canvas)) canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
            if (mask != null && (ov == null || !ov.drawMask(canvas, maskPaint))) canvas.drawBitmap(mask, 0f, 0f, maskPaint)
            canvas.restoreToCount(save)
        }

        fun renderFlattened(background: Int? = null): Bitmap {
            val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
            val c = Canvas(out)
            if (background != null) c.drawColor(background)
            drawDocument(c, null, useOverrides = false)
            return out
        }

        fun renderThumbnail(maxSize: Int, background: Int? = null): Bitmap {
            val s = minOf(1f, maxSize.toFloat() / max(doc.width, doc.height))
            val w = max(1, (doc.width * s).roundToInt()); val h = max(1, (doc.height * s).roundToInt())
            if (s >= 0.5f) {
                val full = renderFlattened(background)
                if (s >= 1f) return full
                val scaled = Bitmap.createScaledBitmap(full, w, h, true)
                full.recycle()
                return scaled
            }
            val s2 = minOf(1f, s * 2f)
            val w2 = max(1, (doc.width * s2).roundToInt()); val h2 = max(1, (doc.height * s2).roundToInt())
            val mid = BitmapUtils.createLayerBitmap(w2, h2)
            val c = Canvas(mid)
            if (background != null) c.drawColor(background)
            c.scale(w2.toFloat() / doc.width, h2.toFloat() / doc.height)
            drawDocument(c, null, useOverrides = false)
            val out = Bitmap.createScaledBitmap(mid, w, h, true)
            if (out !== mid) mid.recycle()
            return out
        }
    }

    private data class V14Style(val mode: PorterDuff.Mode, val color: Int, val opacity: Float, val grain: Float)

    private class V14CoveragePainter {
        private val tint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val layerPaint = Paint()
        private val grainPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        private var grainAmount = -1f
        private val boundsF = RectF()
        private val modes = HashMap<PorterDuff.Mode, PorterDuffXfermode>()

        private fun xfer(mode: PorterDuff.Mode): PorterDuffXfermode? =
            if (mode == PorterDuff.Mode.SRC_OVER) null else modes.getOrPut(mode) { PorterDuffXfermode(mode) }

        private fun alphaOf(style: V14Style): Int = (style.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        fun draw(canvas: Canvas, coverage: Bitmap, bounds: Rect, style: V14Style, selectionMask: Bitmap?) {
            if (bounds.isEmpty) return
            val alpha = alphaOf(style)
            if (alpha <= 0) return
            canvas.save()
            canvas.clipRect(bounds)
            if (selectionMask == null && style.grain <= 0f) {
                tint.shader = null
                tint.xfermode = xfer(style.mode)
                tint.color = style.color
                tint.alpha = alpha
                canvas.drawBitmap(coverage, 0f, 0f, tint)
            } else {
                boundsF.set(bounds)
                layerPaint.xfermode = xfer(style.mode)
                layerPaint.alpha = alpha
                canvas.saveLayer(boundsF, layerPaint)
                tint.shader = null
                tint.xfermode = null
                tint.color = style.color
                tint.alpha = 255
                canvas.drawBitmap(coverage, 0f, 0f, tint)
                if (style.grain > 0f) {
                    if (grainAmount != style.grain) {
                        grainPaint.shader = BitmapShader(PaperGrain.tile(style.grain), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                        grainAmount = style.grain
                    }
                    canvas.drawRect(boundsF, grainPaint)
                }
                if (selectionMask != null) BitmapUtils.maskWith(canvas, selectionMask)
                canvas.restore()
            }
            canvas.restore()
        }
    }

    @Suppress("unused")
    private val keep = Matrix()
}
