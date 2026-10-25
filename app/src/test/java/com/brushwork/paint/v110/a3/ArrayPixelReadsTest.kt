package com.brushwork.paint.v110.a3

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VectorLayers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.10 (A3 step 6, C-TILE; I28): the array operations read a layer that may be parked from its
 * store, never through `Layer.bitmap`. `ArraySources.selectedPixelsOf` and `layerPixelsOf` give
 * a parked ([TiledPixels]) layer's pixels bit for bit as v1.9's full-bitmap reads give a flat
 * one, under [PixelFacade.strict] and without a materialization; a blank layer, and a selection
 * over no painted tile, give none. "Finish source edit" and "Apply array" on a parked row (the
 * layer menu) leave what they leave on the active, flat row. A background render keys on
 * `pixelGeneration` (F0): parking its layer meanwhile (no bump) still lands the patch as ONE
 * step, and a replaced store (a bump) lands it synchronously, with the same pixels either way.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayPixelReadsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        PixelFacade.strict = false
        ArrayDraw.clearCaches()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun pixels(b: Bitmap) = ArrayLookKit.pixels(b)

    /** A 300 × 280 layer painted over [150, 140, 290, 270) (across the 256 px tile edges), semi-transparent and varied. */
    private fun painted(): Bitmap {
        val b = keep(BitmapUtils.createLayerBitmap(W, H))
        val w = 140
        val h = 130
        b.setPixels(IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            Color.argb(90 + (x * 7 + y * 3) % 166, (x * 5) and 0xFF, (y * 9) and 0xFF, (x * y) and 0xFF)
        }, 0, w, 150, 140, w, h)
        return b
    }

    /** An anti-aliased ellipse selection over [l, t, r, b]. */
    private fun ellipse(l: Float, t: Float, r: Float, b: Float): Selection =
        Selection.fromPath(Path().apply { addOval(RectF(l, t, r, b), Path.Direction.CW) }, W, H, antiAlias = true)

    /** v1.9's `ArraySources.selectedPixels` of a whole layer bitmap, verbatim: the oracle. */
    private fun v19Selected(src: Bitmap, sel: Selection): ArrayPixels? {
        val b = Rect(sel.bounds)
        if (!b.intersect(0, 0, src.width, src.height)) return null
        val crop = BitmapUtils.createLayerBitmap(b.width(), b.height())
        Canvas(crop).apply {
            translate(-b.left.toFloat(), -b.top.toFloat())
            drawBitmap(sel.mask, 0f, 0f, Paint().apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) })
        }
        val o = CanvasOps.opaqueBounds(crop)
        if (o == null) { crop.recycle(); return null }
        val out = ArraySources.cropped(crop, o)
        crop.recycle()
        return ArrayPixels(out, b.left + o.left, b.top + o.top)
    }

    private fun assertSamePixels(what: String, expected: ArrayPixels?, actual: ArrayPixels?) {
        assertNotNull("$what: expected pixels", expected)
        assertNotNull("$what: pixels", actual)
        assertEquals("$what: left", expected!!.left, actual!!.left)
        assertEquals("$what: top", expected.top, actual.top)
        assertEquals("$what: width", expected.bitmap.width, actual.bitmap.width)
        assertEquals("$what: height", expected.bitmap.height, actual.bitmap.height)
        assertArrayEquals("$what: every pixel", pixels(expected.bitmap), pixels(actual.bitmap))
    }

    @Test
    fun aParkedLayerReadsAsTheFlatOneWithoutMaterializing() {
        val bmp = painted()
        val flat = Layer(1L, "Flat", bmp)
        val parked = Layer(2L, "Parked", TiledPixels.fromBitmap(bmp))
        val blank = Layer(3L, "Blank", BlankPixels(W, H))
        val empty = Layer(4L, "Empty", TiledPixels.fromBitmap(keep(BitmapUtils.createLayerBitmap(W, H))))
        // Across the tile edges at x = 256 and y = 256; one wholly inside, one over no painted tile.
        val across = ellipse(170f, 150f, 296f, 279f)
        val inside = ellipse(160f, 150f, 230f, 220f)
        val unpainted = ellipse(10f, 10f, 120f, 110f)
        val expectedAcross = v19Selected(bmp, across)
        val expectedInside = v19Selected(bmp, inside)
        val expectedLayer = ArraySources.layerPixels(bmp)
        val generation = parked.pixelGeneration
        val before = PixelFacade.materializations
        val read = mutableListOf<ArrayPixels?>()
        PixelFacade.strict = true
        try {
            read += ArraySources.selectedPixelsOf(flat, across)
            read += ArraySources.selectedPixelsOf(parked, across)
            read += ArraySources.selectedPixelsOf(parked, inside)
            read += ArraySources.layerPixelsOf(flat)
            read += ArraySources.layerPixelsOf(parked)
            assertNull("a selection over no painted tile", ArraySources.selectedPixelsOf(parked, unpainted))
            assertNull("a blank layer: no selected pixels", ArraySources.selectedPixelsOf(blank, across))
            assertNull("a blank layer: no source", ArraySources.layerPixelsOf(blank))
            assertNull("a parked layer without a painted tile: no source", ArraySources.layerPixelsOf(empty))
        } finally {
            PixelFacade.strict = false
        }
        read.forEach { it?.let { p -> keep(p.bitmap) } }
        assertEquals("no read materialized a layer", before, PixelFacade.materializations)
        assertTrue("the parked layer is still parked", parked.pixels is TiledPixels)
        assertEquals("reads never replace a store", generation, parked.pixelGeneration)
        assertSamePixels("flat, across the tiles", expectedAcross, read[0])
        assertSamePixels("parked, across the tiles", expectedAcross, read[1])
        assertSamePixels("parked, inside one tile", expectedInside, read[2])
        assertSamePixels("flat, the whole layer", expectedLayer, read[3])
        assertSamePixels("parked, the whole layer", expectedLayer, read[4])
    }

    /** A raster array whose source is being edited and was painted over (not baked yet); the array layer is active. */
    private fun editedSource(c: EditorController): Layer {
        val layer = ArrayLookKit.pixelArray(c)
        assertTrue(ArrayOps.editSource(c, layer))
        ArrayLookKit.settle(c)
        val rec = c.beginEdit(layer)
        rec.touch(Rect(30, 40, 70, 80))
        Canvas(layer.paintTarget).drawRect(30f, 40f, 70f, 80f, Paint().apply { color = 0xFF11AA33.toInt() })
        assertTrue(c.commitEdit(rec, "Paint"))
        assertTrue("not baked", layer.array!!.spec.editingSource)
        return layer
    }

    /** Selects another row and parks [layer] (already parked when the engine parks inactive rows itself). */
    private fun parkAway(c: EditorController, layer: Layer) {
        c.selectLayer(c.doc.layers[1])
        ArrayLookKit.settle(c)
        ChaosResidency().park(layer)
        assertTrue("${layer.name} is parked", layer.pixels is TiledPixels)
    }

    @Test
    fun finishingAndApplyingAParkedRowLeaveWhatTheActiveRowLeaves() {
        for (op in listOf(ArrayLabels.FINISH_SOURCE, ArrayLabels.APPLY)) {
            val run: (EditorController, Layer) -> Boolean = { c, l -> if (op == ArrayLabels.APPLY) ArrayOps.apply(c, l) else ArrayOps.finishSource(c, l) }
            val twin = ArrayLookKit.controller(app)
            val active = editedSource(twin)
            assertTrue("$op on the active row", run(twin, active))
            ArrayLookKit.settle(twin)
            val c = ArrayLookKit.controller(app)
            val layer = editedSource(c)
            parkAway(c, layer)
            val steps = c.undoManager.undoCount
            assertTrue("$op on a parked row", run(c, layer))
            ArrayLookKit.settle(c)
            assertEquals("$op: one step", steps + 1, c.undoManager.undoCount)
            assertEquals(op, c.undoManager.undoLabel)
            assertEquals("$op: the data", active.dataSnapshot().array?.spec, layer.dataSnapshot().array?.spec)
            val source = layer.array?.pixels
            val expected = active.array?.pixels
            if (expected != null) assertSamePixels("$op: the new source", expected, source) else assertNull("$op: no live array", source)
            assertArrayEquals("$op: the layer's pixels", pixels(active.bitmap), pixels(layer.bitmap))
        }
    }

    private fun tool(c: EditorController) = c.tools.getValue(ToolId.ARRAY) as ArrayTool

    /**
     * An "Edit array" rendering in the background while [meanwhile] changes the layer's store,
     * then landed: ONE step, with the pixels of the same edit rendered synchronously on a twin.
     */
    private fun landsAfter(what: String, meanwhile: (Layer) -> Unit) {
        val twin = ArrayLookKit.controller(app)
        val twinLayer = ArrayLookKit.pixelArray(twin)
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.pixelArray(c)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        // Whole-pixel copies: the worker's patch equals the synchronous render exactly.
        val spec = layer.array!!.spec.copy(count = 6, constantX = 9f, constantY = 4f)
        val before = pixels(layer.bitmap)
        val gate = CountDownLatch(1)
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        c.arrayRenders.workerHook = { gate.await(20, TimeUnit.SECONDS) }
        val steps = c.undoManager.undoCount
        try {
            tool(c).commit(spec)
            assertTrue("$what: rendering in the background", c.arrayRenders.isPending)
            meanwhile(layer)
        } finally {
            gate.countDown()
        }
        ArrayLookKit.settle(c)
        c.arrayRenders.workerHook = null
        assertTrue("$what: landed", !c.arrayRenders.isPending)
        assertEquals("$what: ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        assertEquals("$what: the data", spec.sanitized(), layer.array!!.spec)
        tool(twin).commit(spec)
        ArrayLookKit.settle(twin)
        assertArrayEquals("$what: the synchronous render's pixels", pixels(twinLayer.bitmap), pixels(layer.bitmap))
        c.undo()
        ArrayLookKit.settle(c)
        assertArrayEquals("$what: undo restores every pixel", before, pixels(layer.bitmap))
    }

    @Test
    fun aPendingRenderLandsOnItsLayerParkedOrReplacedMeanwhile() {
        landsAfter("parked meanwhile") { layer ->
            val generation = layer.pixelGeneration
            ChaosResidency().park(layer)
            assertTrue("parked", layer.pixels is TiledPixels)
            assertEquals("parking is no replacement: the patch is still fresh", generation, layer.pixelGeneration)
        }
        landsAfter("replaced meanwhile") { layer ->
            val generation = layer.pixelGeneration
            layer.bitmap = keep(layer.paintTarget.copy(Bitmap.Config.ARGB_8888, true))
            assertNotEquals("a replaced store: the patch is stale and the edit is drawn here", generation, layer.pixelGeneration)
        }
    }

    private companion object {
        const val W = 300
        const val H = 280
    }
}
