package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * v1.7 area A (§3.8): what [FolderComposite] does around a folder besides drawing it. A render
 * override on a folder (a tool's "new layer" preview with a folder active) draws where
 * `LayerStructure.insertionPoint` puts the new layer; [FolderComposite.effectStart] is how low an
 * adjustment layer reads; [FolderComposite.renderBlockThumbnail] is [FolderComposite.renderBlock]
 * fitted into the row's picture.
 */
@RunWith(RobolectricTestRunner::class)
class FolderPreviewScopeTest {
    private val w = 64
    private val h = 48
    private val previewRect = RectF(8f, 6f, 40f, 30f)
    private val previewColor = 0xFFE03020.toInt()

    private fun pixel(doc: Document, name: String, color: Int, rect: RectF = RectF(0f, 0f, w.toFloat(), h.toFloat())): Layer =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(doc.width, doc.height)).also { l ->
            Canvas(l.bitmap).drawRect(rect, Paint().apply { this.color = color })
        }

    private fun folder(doc: Document, name: String, passThrough: Boolean = true): Layer =
        Layer.newFolder(doc.newLayerId(), name, FolderSpec(passThrough = passThrough))

    private fun premul(b: Bitmap): ByteArray = ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()

    private fun maxDiff(a: Bitmap, b: Bitmap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val x = premul(a)
        val y = premul(b)
        var m = 0
        for (i in x.indices) m = maxOf(m, abs((x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)))
        return m
    }

    /** The document drawn as on screen: with [override] installed. */
    private fun drawn(doc: Document, override: LayerRenderOverride?): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        Compositor(doc) { override }.drawDocument(Canvas(out), null, useOverrides = true, target = CompositeTarget.identity(out))
        return out
    }

    private inner class RectPreview(override val layer: Layer) : LayerRenderOverride {
        override fun drawContent(canvas: Canvas): Boolean {
            canvas.drawRect(previewRect, Paint().apply { color = previewColor })
            return true
        }
    }

    /**
     * A backdrop, then folder "F" (pass-through or isolated, at 50 %) holding a disc; with [real]
     * the previewed layer exists: the top child of F when [inside], else directly above F.
     */
    private fun scene(passThrough: Boolean, open: Boolean, real: Boolean, inside: Boolean): Pair<Document, Layer> {
        val doc = Document("p", "p", w, h)
        val bg = pixel(doc, "BG", 0xFF2050C0.toInt())
        val f = folder(doc, "F", passThrough).also { it.opacity = 0.5f; it.folderOpen = open }
        val a = pixel(doc, "A", 0xFF30C060.toInt(), RectF(20f, 10f, 60f, 44f)).also { it.parentId = f.id }
        val new = if (real) pixel(doc, "New", previewColor, previewRect) else null
        doc.layers += bg
        doc.layers += a
        if (new != null && inside) { new.parentId = f.id; doc.layers += new }
        doc.layers += f
        if (new != null && !inside) doc.layers += new
        assertNull(LayerTree.check(doc.layers))
        return doc to f
    }

    @Test
    fun aPreviewOnAFolderDrawsWhereTheNewLayerGoes() {
        for (passThrough in listOf(true, false)) for (open in listOf(true, false)) {
            val (doc, f) = scene(passThrough, open, real = false, inside = false)
            val (expected, _) = scene(passThrough, open, real = true, inside = open)
            val shown = drawn(doc, RectPreview(f))
            assertTrue("pass-through $passThrough, open $open", maxDiff(drawn(expected, null), shown) <= 1)
            // Not where the other case puts it (the folder's 50 % tells them apart).
            val (other, _) = scene(passThrough, open, real = true, inside = !open)
            assertTrue("pass-through $passThrough, open $open: not the other place", maxDiff(drawn(other, null), shown) > 20)
        }
    }

    @Test
    fun aPreviewInsideAHiddenFolderIsHiddenAndOneAboveAClosedOneIsNot() {
        val (doc, f) = scene(passThrough = false, open = true, real = false, inside = false)
        f.visible = false
        assertEquals(0, maxDiff(drawn(doc, null), drawn(doc, RectPreview(f))))
        f.folderOpen = false
        assertTrue(maxDiff(drawn(doc, null), drawn(doc, RectPreview(f))) > 100)
    }

    @Test
    fun anAdjustmentReadsDownToItsNearestIsolatedFolder() {
        val doc = Document("s", "s", w, h)
        val bg = pixel(doc, "BG", 0xFF808080.toInt())
        val outer = folder(doc, "Outer", passThrough = false)
        val x = pixel(doc, "X", 0xFF102030.toInt()).also { it.parentId = outer.id }
        val inner = folder(doc, "Inner").also { it.parentId = outer.id; it.opacity = 0.4f }
        val y = pixel(doc, "Y", 0xFF405060.toInt()).also { it.parentId = inner.id }
        val adj = pixel(doc, "Invert", 0).also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.parentId = inner.id }
        val top = pixel(doc, "Top adjustment", 0).also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert") }
        doc.layers += listOf(bg, x, y, adj, inner, outer, top)
        assertNull(LayerTree.check(doc.layers))
        val l = doc.layers
        assertEquals("top level", 0, FolderComposite.effectStart(l, doc.indexOf(top)))
        assertEquals("pass-through below 100 % inside an isolated folder", doc.indexOf(x), FolderComposite.effectStart(l, doc.indexOf(adj)))
        outer.folder = FolderSpec(passThrough = true)
        assertEquals("pass-through all the way", 0, FolderComposite.effectStart(l, doc.indexOf(adj)))
        inner.folder = FolderSpec(passThrough = false)
        assertEquals("its own folder isolated", doc.indexOf(y), FolderComposite.effectStart(l, doc.indexOf(adj)))
        inner.folder = FolderSpec(passThrough = true)
        inner.clipping = true
        assertEquals("a clipped pass-through folder composites isolated", doc.indexOf(y), FolderComposite.effectStart(l, doc.indexOf(adj)))
    }

    @Test
    fun theFolderThumbnailIsTheBlockFittedIntoTheRow() {
        val doc = Document("t", "t", 300, 200)
        val bg = Layer(doc.newLayerId(), "BG", BitmapUtils.createLayerBitmap(300, 200).also { it.eraseColor(0xFF000000.toInt()) })
        val f = folder(doc, "F", passThrough = true).also { it.opacity = 0.3f }
        val a = Layer(doc.newLayerId(), "A", BitmapUtils.createLayerBitmap(300, 200)).also { l ->
            l.parentId = f.id
            Canvas(l.bitmap).drawRect(0f, 0f, 150f, 200f, Paint().apply { color = 0xFF20A0E0.toInt() })
        }
        doc.layers += listOf(bg, a, f)
        val thumb = FolderComposite.renderBlockThumbnail(Compositor(doc) { null }, doc, f, 96)
        assertEquals(96, thumb.width)
        assertEquals(64, thumb.height)
        // The children alone (no backdrop, no folder opacity): the left half blue, the right empty.
        assertEquals(0xFF20A0E0.toInt(), thumb.getPixel(20, 32))
        assertEquals(0, thumb.getPixel(80, 32))
        // A document smaller than the picture is never upscaled: exactly renderBlock.
        val small = Document("s", "s", 40, 30)
        val b = Layer(small.newLayerId(), "B", BitmapUtils.createLayerBitmap(40, 30).also { it.eraseColor(0x8040C020.toInt()) })
        val g = folder(small, "G", passThrough = false)
        b.parentId = g.id
        small.layers += listOf(b, g)
        assertEquals(0, maxDiff(FolderComposite.renderBlock(small, g), FolderComposite.renderBlockThumbnail(Compositor(small) { null }, small, g, 96)))
    }
}
