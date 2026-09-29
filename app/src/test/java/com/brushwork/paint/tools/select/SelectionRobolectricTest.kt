package com.brushwork.paint.tools.select

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * Selection-module behavior on real Skia (Robolectric NATIVE graphics). Never touches
 * `controller.tools` (other modules' tools), only this module's classes.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionRobolectricTest {
    private val red = 0xFFFF0000.toInt()
    private val black = 0xFF000000.toInt()

    private fun alphaAt(sel: Selection, x: Int, y: Int) = sel.alphaAt(x, y)

    private fun controllerFor(w: Int, h: Int): Pair<EditorController, Layer> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("t", "t", w, h)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += layer
        return EditorController(ctx, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(ctx)) to layer
    }

    // ------------------------------------------------------------------ lasso / marquee

    @Test
    fun lassoPolygonRasterization() {
        val path = LassoTool.polygonPath(floatArrayOf(10f, 10f, 50f, 10f, 10f, 50f))
        val hard = LassoTool.rasterize(path, 64, 64, antiAlias = false)
        assertEquals(255, alphaAt(hard, 15, 15))
        assertEquals(255, alphaAt(hard, 11, 45))
        assertEquals(0, alphaAt(hard, 40, 40))
        assertEquals(0, alphaAt(hard, 5, 5))
        assertEquals(10, hard.bounds.left); assertEquals(10, hard.bounds.top)
        assertTrue(hard.bounds.right in 48..50 && hard.bounds.bottom in 48..50)
        val bytes = hard.toBytes()
        val area = bytes.count { it.toInt() != 0 }
        assertTrue("area $area", abs(area - 800) < 45)
        assertTrue(bytes.all { it.toInt() == 0 || it.toInt() == -1 })

        val soft = LassoTool.rasterize(path, 64, 64, antiAlias = true)
        val partial = soft.toBytes().count { (it.toInt() and 0xFF) in 1..254 }
        assertTrue("anti-aliased diagonal edge ($partial partial pixels)", partial >= 30)
    }

    @Test
    fun marqueeRectangleIsCrispAndConstrained() {
        val s = MarqueeSettings()
        val r = MarqueeTool.shapeRect(10.4f, 20.6f, 30.2f, 25.0f, s)
        assertEquals(RectF(10f, 21f, 30f, 25f), r)
        val sq = MarqueeTool.shapeRect(10f, 10f, 30f, 15f, s.copy(square = true))
        assertEquals(RectF(10f, 10f, 30f, 30f), sq)
        val centered = MarqueeTool.shapeRect(50f, 50f, 60f, 45f, s.copy(fromCenter = true))
        assertEquals(RectF(40f, 45f, 60f, 55f), centered)
        val leftUp = MarqueeTool.shapeRect(30f, 30f, 20f, 10f, s)
        assertEquals(RectF(20f, 10f, 30f, 30f), leftUp)

        val sel = Selection.fromPath(MarqueeTool.shapePath(r, MarqueeShape.RECTANGLE), 64, 64, antiAlias = false)
        assertEquals(Rect(10, 21, 30, 25), sel.bounds)
        assertEquals(20 * 4, sel.toBytes().count { it.toInt() == -1 })
    }

    // ------------------------------------------------------------------ marching ants

    @Test
    fun outlineOfSquareSelection() {
        val w = 40; val h = 30
        val sel = Selection.fromBytes(ByteArray(w * h) { i -> if (i % w in 10 until 30 && i / w in 5 until 25) -1 else 0 }, w, h)
        val path = SelectionOutline.buildOutline(sel)
        assertNotNull(path)
        val b = RectF()
        path!!.computeBounds(b, true)
        assertEquals(10f, b.left, 0.01f); assertEquals(30f, b.right, 0.01f)
        assertEquals(5f, b.top, 0.01f); assertEquals(25f, b.bottom, 0.01f)
    }

    @Test
    fun outlineOfLargeSelectionIsDownsampledButCoversBounds() {
        val sel = Selection.all(3000, 2000)
        val path = SelectionOutline.buildOutline(sel)!!
        val b = RectF()
        path.computeBounds(b, true)
        assertEquals(0f, b.left, 2f); assertEquals(3000f, b.right, 2f)
        assertEquals(0f, b.top, 2f); assertEquals(2000f, b.bottom, 2f)
    }

    @Test
    fun outlineOfEmptySelectionIsNull() {
        assertNull(SelectionOutline.buildOutline(Selection.empty(10, 10)))
    }

    // ------------------------------------------------------------------ grow / shrink / feather

    @Test
    fun growShrinkFeatherOnRealSelections() {
        val w = 60; val h = 60
        val sel = Selection.fromBytes(ByteArray(w * h) { i -> if (i % w in 20 until 40 && i / w in 20 until 40) -1 else 0 }, w, h)
        assertEquals(Rect(17, 17, 43, 43), SelectionEdits.grown(sel, 3)!!.bounds)
        assertEquals(Rect(22, 22, 38, 38), SelectionEdits.shrunk(sel, 2)!!.bounds)
        val f = SelectionEdits.feathered(sel, 6f)!!
        assertEquals(255, f.alphaAt(30, 30))
        assertTrue(abs(f.alphaAt(19, 30) + f.alphaAt(20, 30) - 255) <= 6)
        assertEquals(0, f.alphaAt(5, 30))
        // Selections touching the canvas edge don't shrink away from it.
        val all = SelectionEdits.shrunk(Selection.all(w, h), 5)!!
        assertEquals(Rect(0, 0, w, h), all.bounds)
    }

    // ------------------------------------------------------------------ wand / fill / eyedropper

    /** Transparent layer with a closed black square outline (2 px) from 8 to 24. */
    private fun drawBox(bmp: Bitmap) {
        val p = Paint().apply { color = black; style = Paint.Style.STROKE; strokeWidth = 2f }
        Canvas(bmp).drawRect(9f, 9f, 23f, 23f, p)
    }

    @Test
    fun magicWandSelectsInsideOfOutline() {
        val bmp = BitmapUtils.createLayerBitmap(32, 32)
        drawBox(bmp)
        val sel = MagicWandTool.computeSelection(bmp, 16, 16, RegionParams(tolerance = 20, antiAlias = false))!!
        assertEquals(Rect(10, 10, 22, 22), sel.bounds)
        assertEquals(0, sel.alphaAt(2, 2))
        val global = MagicWandTool.computeSelection(bmp, 16, 16, RegionParams(tolerance = 20, contiguous = false, antiAlias = false))!!
        assertEquals(255, global.alphaAt(2, 2))
        assertEquals(0, global.alphaAt(9, 16))
    }

    private fun regionAt(bmp: Bitmap, x: Int, y: Int, params: RegionParams): Region {
        val map = PixelSnapshot.similarityMap(bmp, x, y, params.tolerance, null) { false }!!
        return RegionFill.compute(map, bmp.width, bmp.height, x, y, params)!!
    }

    @Test
    fun fillPaintsRegionAndUndoes() {
        val (c, layer) = controllerFor(32, 32)
        drawBox(layer.bitmap)
        val tool = FillTool(c)
        val region = regionAt(layer.bitmap, 16, 16, RegionParams(tolerance = 20, antiAlias = false))
        assertTrue(tool.applyRegion(layer, EditTarget.CONTENT, region, red))
        assertEquals(red, layer.bitmap.getPixel(16, 16))
        assertEquals(black, layer.bitmap.getPixel(9, 16))
        assertEquals(0, layer.bitmap.getPixel(2, 2))
        assertTrue(c.canUndo)
        c.undoManager.undo(c)
        assertEquals(0, layer.bitmap.getPixel(16, 16))
    }

    @Test
    fun fillRespectsAlphaLockMaskAndColorMode() {
        val (c, layer) = controllerFor(32, 32)
        val tool = FillTool(c)
        // Alpha lock: filling a transparent area changes nothing visible, opaque pixels recolor.
        drawBox(layer.bitmap)
        layer.alphaLocked = true
        val inside = regionAt(layer.bitmap, 16, 16, RegionParams(tolerance = 20, antiAlias = false))
        tool.applyRegion(layer, EditTarget.CONTENT, inside, red)
        assertEquals(0, layer.bitmap.getPixel(16, 16))
        val line = regionAt(layer.bitmap, 9, 16, RegionParams(tolerance = 20, antiAlias = false))
        tool.applyRegion(layer, EditTarget.CONTENT, line, red)
        assertEquals(red, layer.bitmap.getPixel(9, 16))
        layer.alphaLocked = false

        // Grayscale documents get gray fills.
        c.doc.colorMode = ColorMode.GRAYSCALE
        tool.applyRegion(layer, EditTarget.CONTENT, inside, red)
        val g = layer.bitmap.getPixel(16, 16)
        assertEquals(0xFF4C4C4C.toInt(), g)
        c.doc.colorMode = ColorMode.RGB

        // Mask editing paints the color's luminance into the mask.
        layer.mask = BitmapUtils.createMaskBitmap(32, 32)
        layer.editingMask = true
        tool.applyRegion(layer, EditTarget.MASK, inside, red)
        assertEquals(0xFF4C4C4C.toInt(), layer.mask!!.getPixel(16, 16))
        assertEquals(-1, layer.mask!!.getPixel(2, 2))
    }

    @Test
    fun fillIsCroppedAndSkipsTransparentAreasUnderAlphaLock() {
        val bmp = BitmapUtils.createLayerBitmap(32, 32)
        drawBox(bmp)
        val hard = RegionParams(tolerance = 20, antiAlias = false)
        // Without alpha lock the region is cropped to the pixels that change.
        val inside = FillTool.computeFill(bmp, 16, 16, hard, null, null)!!
        assertEquals(Rect(10, 10, 22, 22), Rect(inside.x0, inside.y0, inside.x1, inside.y1))
        // Clipped by a selection: only the selected part remains.
        val sel = Selection.fromBytes(ByteArray(32 * 32) { i -> if (i % 32 < 14) -1 else 0 }, 32, 32)
        val clipped = FillTool.computeFill(bmp, 12, 16, hard, sel, null)!!
        assertEquals(Rect(10, 10, 14, 22), Rect(clipped.x0, clipped.y0, clipped.x1, clipped.y1))
        // Alpha lock: nothing can change inside the transparent box, the line itself can.
        val lock = SelectionEdits.alphaMask(bmp)
        assertNull(FillTool.computeFill(bmp, 16, 16, hard, null, lock))
        val line = FillTool.computeFill(bmp, 9, 16, RegionParams(tolerance = 20, antiAlias = true), null, lock)!!
        assertEquals(Rect(8, 8, 24, 24), Rect(line.x0, line.y0, line.x1, line.y1))
        assertEquals(0, line.at(16, 16))
    }

    @Test
    fun eyedropperSamplesCompositeAndAverages() {
        val (c, layer) = controllerFor(16, 16)
        layer.bitmap.eraseColor(0xFF000000.toInt())
        Canvas(layer.bitmap).drawRect(8f, 0f, 16f, 16f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        val tool = EyedropperTool(c)
        tool.settings = EyedropperSettings(source = SampleSource.CANVAS, sampleSize = 1)
        assertEquals(0xFF000000.toInt(), tool.sample(3.5f, 3.5f))
        assertEquals(0xFFFFFFFF.toInt(), tool.sample(12.2f, 3.5f))
        tool.settings = EyedropperSettings(source = SampleSource.LAYER, sampleSize = 5)
        // 5x5 around x=8 spans columns 6..10: 2 black + 3 white columns.
        val avg = tool.sample(8.5f, 8.5f)!!
        assertEquals(153, avg and 0xFF)
        assertNull(tool.sample(-1f, 3f))
        layer.bitmap.eraseColor(0)
        assertNull(tool.sample(3f, 3f))
    }

    @Test
    fun averageIgnoresTransparentPixels() {
        val px = intArrayOf(0, 0x80FF0000.toInt(), 0xFF0000FF.toInt(), 0x00FFFFFF)
        // Alpha-weighted: red weight 128, blue weight 255.
        val c = EyedropperTool.averageOpaque(px, 4)!!
        assertEquals(0xFF, c ushr 24)
        assertEquals(85, (c shr 16) and 0xFF)
        assertEquals(170, c and 0xFF)
        assertNull(EyedropperTool.averageOpaque(intArrayOf(0, 0), 2))
    }

    // ------------------------------------------------------------------ panel pixel edits

    @Test
    fun fillClearAndCutSelection() {
        val (c, layer) = controllerFor(20, 20)
        layer.bitmap.eraseColor(0xFF00FF00.toInt())
        val sel = Selection.fromBytes(ByteArray(400) { i -> if (i % 20 < 10) -1 else 0 }, 20, 20)
        c.setSelection(sel, recordUndo = false)

        assertTrue(SelectionEdits.fillSelection(c, red))
        assertEquals(red, layer.bitmap.getPixel(2, 2))
        assertEquals(0xFF00FF00.toInt(), layer.bitmap.getPixel(15, 2))

        assertTrue(SelectionEdits.clearSelection(c))
        assertEquals(0, layer.bitmap.getPixel(2, 2))
        c.undoManager.undo(c)
        assertEquals(red, layer.bitmap.getPixel(2, 2))

        val cut = SelectionEdits.cut(c, layer, sel)!!
        assertEquals(2, c.doc.layers.size)
        assertEquals(cut, c.doc.activeLayer)
        assertEquals(red, cut.bitmap.getPixel(2, 2))
        assertEquals(0, cut.bitmap.getPixel(15, 2))
        assertEquals(0, layer.bitmap.getPixel(2, 2))
        assertEquals(0xFF00FF00.toInt(), layer.bitmap.getPixel(15, 2))
        // One undo step restores both the pixels and the layer list.
        c.undoManager.undo(c)
        assertEquals(1, c.doc.layers.size)
        assertEquals(red, layer.bitmap.getPixel(2, 2))
    }

    @Test
    fun clearAndCutRespectAlphaLock() {
        val (c, layer) = controllerFor(20, 20)
        layer.bitmap.eraseColor(0xFF00FF00.toInt())
        layer.alphaLocked = true
        val sel = Selection.fromBytes(ByteArray(400) { i -> if (i % 20 < 10) -1 else 0 }, 20, 20)
        c.setSelection(sel, recordUndo = false)
        assertTrue(!SelectionEdits.clearSelection(c))
        assertNull(SelectionEdits.cut(c, layer, sel))
        assertEquals(0xFF00FF00.toInt(), layer.bitmap.getPixel(2, 2))
        assertEquals(1, c.doc.layers.size)
        assertTrue(!c.canUndo)
        // Filling still works (and keeps the transparency).
        assertTrue(SelectionEdits.fillSelection(c, red))
        assertEquals(red, layer.bitmap.getPixel(2, 2))
    }

    @Test
    fun cutInMonochromeSplitsSoftEdgesCleanly() {
        val (c, layer) = controllerFor(20, 20)
        c.doc.colorMode = ColorMode.MONOCHROME
        layer.bitmap.eraseColor(black)
        // Soft selection: full on columns 0..4, 60 on column 5, 200 on column 6.
        val sel = Selection.fromBytes(ByteArray(400) { i ->
            when (i % 20) { in 0..4 -> -1; 5 -> 60; 6 -> 200.toByte().toInt(); else -> 0 }.toByte()
        }, 20, 20)
        val cut = SelectionEdits.cut(c, layer, sel)!!
        for (x in 0..8) {
            val a = cut.bitmap.getPixel(x, 3) ushr 24
            val b = layer.bitmap.getPixel(x, 3) ushr 24
            assertTrue("x=$x alphas $a / $b must be 0 or 255", (a == 0 || a == 255) && (b == 0 || b == 255))
            assertEquals("x=$x belongs to exactly one layer", 255, a + b)
        }
        assertEquals(255, cut.bitmap.getPixel(6, 3) ushr 24)
        assertEquals(255, layer.bitmap.getPixel(5, 3) ushr 24)
    }

    @Test
    fun copyAndCutFollowTheSelectionShapeNotItsBounds() {
        val (c, layer) = controllerFor(40, 40)
        layer.bitmap.eraseColor(0xFF00FF00.toInt())
        val sel = Selection.fromPath(MarqueeTool.shapePath(RectF(0f, 0f, 40f, 40f), MarqueeShape.ELLIPSE), 40, 40, antiAlias = true)
        assertEquals(0, sel.alphaAt(1, 1))
        val cut = SelectionEdits.cut(c, layer, sel)!!
        // Corners of the bounding box are outside the ellipse: they stay on the source layer.
        assertEquals(0, cut.bitmap.getPixel(1, 1) ushr 24)
        assertEquals(255, layer.bitmap.getPixel(1, 1) ushr 24)
        assertEquals(255, cut.bitmap.getPixel(20, 20) ushr 24)
        assertEquals(0, layer.bitmap.getPixel(20, 20) ushr 24)
        // Soft edge pixels are split between the two layers.
        for (y in 0 until 40) for (x in 0 until 40) {
            val sum = (cut.bitmap.getPixel(x, y) ushr 24) + (layer.bitmap.getPixel(x, y) ushr 24)
            assertTrue("($x,$y) sum $sum", abs(sum - 255) <= 2)
        }
    }

    @Test
    fun intersectModeKeepsOnlyTheOverlap() {
        val w = 20; val h = 20
        val left = Selection.fromBytes(ByteArray(w * h) { i -> if (i % w < 10) -1 else 0 }, w, h)
        val top = Selection.fromBytes(ByteArray(w * h) { i -> if (i / w < 6) -1 else if (i / w == 6) 100 else 0 }, w, h)
        val both = SelectionMasks.combine(left, top, SelectionMode.INTERSECT)!!
        assertEquals(Rect(0, 0, 10, 7), both.bounds)
        assertEquals(255, both.alphaAt(3, 3))
        assertEquals(100, both.alphaAt(3, 6))
        assertEquals(0, both.alphaAt(15, 3))
        assertEquals(0, both.alphaAt(3, 10))
        val none = SelectionMasks.combine(left, Selection.fromBytes(ByteArray(w * h) { i -> if (i % w >= 12) -1 else 0 }, w, h), SelectionMode.INTERSECT)!!
        assertTrue(none.isEmpty)
        // Add / subtract behave as expected too.
        val add = SelectionMasks.combine(left, top, SelectionMode.ADD)!!
        assertEquals(Rect(0, 0, 20, 20), add.bounds)
        assertEquals(255, add.alphaAt(15, 3)); assertEquals(0, add.alphaAt(15, 10)); assertEquals(255, add.alphaAt(3, 10))
        val sub = SelectionMasks.combine(left, top, SelectionMode.SUBTRACT)!!
        assertEquals(Rect(0, 6, 10, 20), sub.bounds)
        assertTrue(abs(sub.alphaAt(3, 6) - 155) <= 1)
    }

    @Test
    fun layerOpacityMask() {
        val bmp = BitmapUtils.createLayerBitmap(10, 10)
        Canvas(bmp).drawRect(0f, 0f, 5f, 10f, Paint().apply { color = 0x80FF0000.toInt() })
        val sel = Selection.wrap(SelectionEdits.alphaMask(bmp))
        assertEquals(Rect(0, 0, 5, 10), sel.bounds)
        assertTrue(abs(sel.alphaAt(2, 2) - 128) <= 1)
    }
}
