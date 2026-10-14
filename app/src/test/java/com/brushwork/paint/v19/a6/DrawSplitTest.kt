package com.brushwork.paint.v19.a6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A6 step 9 (P3): `Compositor.drawSplit` draws `[0, k)` then `[k, n)` onto one display
 * target exactly as the full draw at every clipping group's first layer, not only at an
 * adjustment layer (`DrawDocumentRangeTest` holds `drawDocument`'s own contract, unchanged);
 * a split inside a clipping group is refused, and with folders so is a cut through a folder that
 * is not drawn as is.
 */
@RunWith(RobolectricTestRunner::class)
class DrawSplitTest {
    private val w = 64
    private val h = 48

    private fun layer(doc: Document, name: String, color: Int?, block: (Layer) -> Unit = {}): Layer {
        val l = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h))
        if (color != null) Canvas(l.bitmap).drawCircle(w / 2f + name.length, h / 2f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        block(l)
        return l
    }

    /** Bottom, a clipped layer, an Invert, a multiply base with a clipped layer, a masked screen layer, a layer clipped after an adjustment. */
    private fun flat(): Document {
        val doc = Document("s", "s", w, h)
        doc.layers += layer(doc, "bottom", null) { it.bitmap.eraseColor(0xFF4080C0.toInt()) }
        doc.layers += layer(doc, "clipped", 0xCCFF2200.toInt()) { it.clipping = true }
        doc.layers += layer(doc, "invert", null) { it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.opacity = 0.7f }
        doc.layers += layer(doc, "after adjustment", 0x9930A0F0.toInt()) { it.clipping = true }
        doc.layers += layer(doc, "multiply", 0x9900FF66.toInt()) { it.blendMode = LayerBlendMode.MULTIPLY }
        doc.layers += layer(doc, "on multiply", 0x80FFFF00.toInt()) { it.clipping = true }
        doc.layers += layer(doc, "screen", 0xA0F040A0.toInt()) { l ->
            l.blendMode = LayerBlendMode.SCREEN
            l.opacity = 0.6f
            l.mask = BitmapUtils.createLayerBitmap(w, h).also { Canvas(it).drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = -1 }) }
        }
        return doc
    }

    private fun render(c: Compositor, vararg ranges: IntRange): IntArray {
        val bmp: Bitmap = BitmapUtils.createLayerBitmap(w, h)
        val target = CompositeTarget.displayTile(bmp, 0, 0)
        val canvas = Canvas(bmp)
        for (r in ranges) c.drawSplit(canvas, Rect(0, 0, w, h), target, r.first, r.last + 1)
        return IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
    }

    private fun refused(c: Compositor, from: Int, until: Int): Boolean = try {
        c.drawSplit(Canvas(BitmapUtils.createLayerBitmap(w, h)), Rect(0, 0, w, h), null, from, until)
        false
    } catch (e: IllegalArgumentException) {
        true
    }

    @Test
    fun belowThenAboveEqualsTheFullDrawAtEveryGroupStart() {
        val doc = flat()
        val c = Compositor(doc) { null }
        val n = doc.layers.size
        val full = render(c, 0 until n)
        val boundaries = (0..n).filter { c.isSplitBoundary(it) }
        // Groups: [bottom, clipped], [invert], [after adjustment], [multiply, on multiply], [screen].
        assertEquals(listOf(0, 2, 3, 4, 6, 7), boundaries)
        for (k in boundaries) assertArrayEquals("split at $k", full, render(c, 0 until k, k until n))
        for (k in (0..n) - boundaries.toSet()) {
            assertTrue("$k splits a clipping group", refused(c, 0, k))
            assertTrue("$k splits a clipping group", refused(c, k, n))
        }
        assertTrue(refused(c, -1, 2))
        assertTrue(refused(c, 0, n + 1))
        assertTrue(refused(c, 4, 2))
    }

    @Test
    fun withFoldersOnlyACutThroughAFolderDrawnAsIs() {
        val doc = flat()
        // "multiply" and "on multiply" (no longer clipped) in a pass-through folder at 100 %.
        val f = Layer.newFolder(doc.newLayerId(), "Group", FolderSpec(passThrough = true))
        doc.layers[4].parentId = f.id
        doc.layers[5].parentId = f.id
        doc.layers[5].clipping = false
        doc.layers.add(6, f)
        val c = Compositor(doc) { null }
        val n = doc.layers.size
        val full = render(c, 0 until n)
        // Below the folder, between its children (the folder draws them as is) and above it.
        for (k in listOf(4, 5, 7)) {
            assertTrue(c.isSplitBoundary(k))
            assertArrayEquals("split at $k", full, render(c, 0 until k, k until n))
        }
        // Isolated: a cut between its children is refused; below or above it is fine.
        f.folder = FolderSpec(passThrough = false)
        assertTrue(refused(c, 0, 5))
        assertTrue(refused(c, 5, n))
        val isolated = render(c, 0 until n)
        assertArrayEquals(isolated, render(c, 0 until 4, 4 until n))
        assertArrayEquals(isolated, render(c, 0 until 7, 7 until n))
    }
}
