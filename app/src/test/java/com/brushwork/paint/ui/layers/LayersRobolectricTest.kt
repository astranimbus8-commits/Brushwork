package com.brushwork.paint.ui.layers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real Skia (Robolectric NATIVE graphics): thumbnails, row snapshots and the panel's layer
 * operations against a real [EditorController]. Tests avoid controller calls that activate tools.
 */
@RunWith(RobolectricTestRunner::class)
class LayersRobolectricTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = -1
    private val black = 0xFF000000.toInt()

    private fun newController(w: Int = 8, h: Int = 8, layers: Int = 1): EditorController {
        val doc = Document("t", "t", w, h)
        repeat(layers) { doc.layers += Layer(doc.newLayerId(), "Layer ${it + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = doc.layers.lastIndex
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // A cancelled scope: nothing in these tests needs background work.
        val scope = CoroutineScope(Job().apply { cancel() })
        return EditorController(context, doc, scope, AppSettings(context))
    }

    /** Selection of the left half (x < w/2). */
    private fun leftHalf(w: Int, h: Int): Selection =
        Selection.fromBytes(ByteArray(w * h) { if (it % w < w / 2) -1 else 0 }, w, h)

    private fun Bitmap.px(x: Int, y: Int) = getPixel(x, y)

    // ------------------------------------------------------------------ thumbnails

    @Test
    fun downscaleFitsAspectAndNeverReturnsSource() {
        val big = BitmapUtils.createLayerBitmap(400, 200)
        big.eraseColor(red)
        val t = LayerThumbnails.downscale(big, 96)
        assertEquals(96, t.width)
        assertEquals(48, t.height)
        assertEquals(red, t.getPixel(10, 10))

        // Tiny canvases are not upscaled; the result must still be a separate copy.
        val tiny = BitmapUtils.createLayerBitmap(20, 10)
        tiny.eraseColor(blue)
        val tt = LayerThumbnails.downscale(tiny, 96)
        assertNotSame(tiny, tt)
        assertEquals(20, tt.width)
        assertEquals(10, tt.height)
        tiny.eraseColor(red)
        assertEquals(blue, tt.getPixel(0, 0))
    }

    @Test
    fun downscaleKeepsThinStrokesOnHugeReductions() {
        val src = BitmapUtils.createLayerBitmap(2000, 2000)
        // A 12 px wide vertical stroke placed between the sample points of a single 1/20 pass.
        Canvas(src).drawRect(1030f, 0f, 1042f, 2000f, Paint().apply { color = black })
        val t = LayerThumbnails.downscale(src, 100)
        assertEquals(100, t.width)
        var maxAlpha = 0
        for (x in 0 until t.width) maxAlpha = maxOf(maxAlpha, t.getPixel(x, 50) ushr 24)
        assertTrue("stroke should remain visible, alpha=$maxAlpha", maxAlpha > 40)
    }

    @Test
    fun thumbnailCacheFollowsContentVersionAndBitmapIdentity() {
        val layer = Layer(7, "l", BitmapUtils.createLayerBitmap(300, 300))
        val cache = LayerThumbnails(64)
        val a = cache.content(layer)
        assertSame(a, cache.content(layer))
        layer.markChanged()
        val b = cache.content(layer)
        assertNotSame(a, b)
        // Same version but a replaced bitmap (e.g. flip) also refreshes.
        layer.bitmap = BitmapUtils.createLayerBitmap(300, 300)
        assertNotSame(b, cache.content(layer))

        assertNull(cache.mask(layer))
        layer.mask = BitmapUtils.createMaskBitmap(300, 300)
        assertNotNull(cache.mask(layer))
        assertEquals(2, cache.size)
        cache.retain(emptySet())
        assertEquals(0, cache.size)
    }

    // ------------------------------------------------------------------ row snapshots

    @Test
    fun rowModelsAreTopFirstWithClippingAndHiddenBase() {
        val c = newController(layers = 3)
        val (base, clipped, top) = c.doc.layers
        clipped.clipping = true
        base.visible = false
        c.doc.activeLayerIndex = 1
        val rows = LayerRowModel.build(c.doc, c.doc.layers.asReversed().toList())
        assertEquals(listOf(top, clipped, base), rows.map { it.layer })
        assertFalse(rows[0].clip.clipped)
        assertTrue(rows[1].clip.clipped)
        assertTrue(rows[1].clip.lowestInGroup)
        assertTrue(rows[1].baseHidden)
        assertTrue(rows[1].active)
        assertFalse(rows[0].active)
        assertFalse(rows[2].baseHidden)
    }

    @Test
    fun droppingARowMovesTheLayerToTheMappedDocumentIndex() {
        val c = newController(layers = 4)
        val (l0, l1, l2, l3) = c.doc.layers
        val display = c.doc.layers.asReversed().toList()
        val after = LayerListMath.moved(display, 0, 2) // drag the top layer two rows down
        val index = after.indexOf(l3)
        c.moveLayer(l3, LayerListMath.displayToDoc(index, after.size))
        assertEquals(listOf(l0, l3, l1, l2), c.doc.layers)
        assertEquals(after, c.doc.layers.asReversed())
        assertSame(l3, c.doc.activeLayer)
    }

    // ------------------------------------------------------------------ operations

    @Test
    fun fillRespectsSelectionAndAlphaLockAndUndoes() {
        val c = newController()
        val layer = c.doc.activeLayer
        // Top half opaque red.
        Canvas(layer.bitmap).drawRect(0f, 0f, 8f, 4f, Paint().apply { color = red })
        layer.alphaLocked = true
        c.color = blue
        c.setSelection(leftHalf(8, 8), recordUndo = false)
        assertTrue(LayerOps.fill(c, layer))
        assertEquals(blue, layer.bitmap.px(1, 1))  // selected + opaque
        assertEquals(red, layer.bitmap.px(6, 1))   // outside the selection
        assertEquals(0, layer.bitmap.px(1, 6))     // alpha lock keeps transparent pixels
        assertEquals("Fill", c.undoManager.undoLabel)
        c.undoManager.undo(c)
        assertEquals(red, layer.bitmap.px(1, 1))
    }

    @Test
    fun fillWithoutSelectionCoversTheLayer() {
        val c = newController()
        val layer = c.doc.activeLayer
        c.color = blue
        assertTrue(LayerOps.fill(c, layer))
        assertEquals(blue, layer.bitmap.px(0, 0))
        assertEquals(blue, layer.bitmap.px(7, 7))
    }

    @Test
    fun clearWhileEditingMaskHidesInsteadOfErasing() {
        val c = newController()
        val layer = c.doc.activeLayer
        layer.bitmap.eraseColor(red)
        layer.mask = BitmapUtils.createMaskBitmap(8, 8)
        layer.editingMask = true
        c.setSelection(leftHalf(8, 8), recordUndo = false)
        assertEquals("Clear mask (hide)", LayerOps.clearLabel(c, layer))
        assertTrue(LayerOps.clear(c, layer))
        assertEquals(black, layer.mask!!.px(1, 1))
        assertEquals(white, layer.mask!!.px(6, 1))
        assertEquals(red, layer.bitmap.px(1, 1)) // content untouched
        assertEquals("Clear mask", c.undoManager.undoLabel)
    }

    @Test
    fun clearContentErasesOnlyTheSelection() {
        val c = newController()
        val layer = c.doc.activeLayer
        layer.bitmap.eraseColor(red)
        c.setSelection(leftHalf(8, 8), recordUndo = false)
        assertTrue(LayerOps.clear(c, layer))
        assertEquals(0, layer.bitmap.px(1, 1))
        assertEquals(red, layer.bitmap.px(6, 1))
    }

    @Test
    fun fillMaskUsesTheColorLuminance() {
        val c = newController()
        val layer = c.doc.activeLayer
        layer.mask = BitmapUtils.createMaskBitmap(8, 8)
        layer.editingMask = true
        c.color = red
        assertTrue(LayerOps.fill(c, layer))
        assertEquals(0xFF4C4C4C.toInt(), layer.mask!!.px(3, 3)) // luminance(255, 0, 0) = 76
        assertEquals(0, layer.bitmap.px(3, 3))
    }

    @Test
    fun lockedLayersRefusePixelAndMaskChanges() {
        val c = newController(layers = 2)
        val (lower, upper) = c.doc.layers
        upper.locked = true
        assertFalse(LayerOps.fill(c, upper))
        assertEquals(0, upper.bitmap.px(0, 0))
        assertTrue(c.message!!.contains("locked"))
        LayerOps.addMask(c, upper, fromSelection = false)
        assertNull(upper.mask)
        assertFalse(c.undoManager.canUndo)

        // Merging into a locked lower layer is refused before anything changes.
        upper.locked = false
        lower.locked = true
        c.message = null
        LayerOps.mergeDown(c, upper)
        assertEquals(2, c.doc.layers.size)
        assertTrue(c.message!!.contains("locked"))
    }

    @Test
    fun maskAddInvertAndApply() {
        val c = newController()
        val layer = c.doc.activeLayer
        layer.bitmap.eraseColor(red)
        c.setSelection(leftHalf(8, 8), recordUndo = false)
        LayerOps.addMask(c, layer, fromSelection = true)
        assertNotNull(layer.mask)
        val mask = layer.mask!!
        assertTrue(layer.editingMask)
        assertEquals(white, mask.px(1, 1))
        assertEquals(black, mask.px(6, 1))

        LayerOps.invertMask(c, layer)
        assertEquals(black, layer.mask!!.px(1, 1))
        assertEquals(white, layer.mask!!.px(6, 1))

        LayerOps.applyMask(c, layer)
        assertNull(layer.mask)
        assertFalse(layer.editingMask)
        assertEquals(0, layer.bitmap.px(1, 1) ushr 24)
        assertEquals(red, layer.bitmap.px(6, 1))
        // One undo step restores both the pixels and the mask.
        c.undoManager.undo(c)
        assertNotNull(layer.mask)
        assertEquals(red, layer.bitmap.px(1, 1))
    }

    @Test
    fun maskEnableToggleAndDeleteAreUndoable() {
        val c = newController()
        val layer = c.doc.activeLayer
        LayerOps.addMask(c, layer, fromSelection = false)
        LayerOps.setMaskEnabled(c, layer, false)
        assertFalse(layer.maskEnabled)
        LayerOps.deleteMask(c, layer)
        assertNull(layer.mask)
        c.undoManager.undo(c)
        assertNotNull(layer.mask)
        c.undoManager.undo(c)
        assertTrue(layer.maskEnabled)
    }
}
