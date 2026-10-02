package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.LayerDataTransforms
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 A1 review: after a canvas operation a vector layer's cache is still exactly the rendering
 * of its objects (in the document's color mode), so a later partial re-render (deleting one
 * object) leaves no seam. Brushes that don't turn or move into themselves (paper grain, textured
 * or angled tips, scatter) are drawn again instead of having their pixels remapped; color-mode
 * switches draw vector layers again in the new mode.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasOpsVectorConsistencyRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setup(w: Int, h: Int, vararg objects: VObject, mode: ColorMode = ColorMode.RGB): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.colorMode = mode
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.vectors.addObjects(doc.layers[1], objects.toList(), "Add")
        return c
    }

    private val EditorController.vec: Layer get() = doc.layers[1]

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(content: VectorContent, w: Int, h: Int, mode: ColorMode = ColorMode.RGB): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        ColorModeOps.constrain(b, Rect(0, 0, w, h), mode)
        return px(b)
    }

    private fun commit(c: EditorController, label: String, op: (CanvasSnapshot) -> CanvasResult) {
        val snap = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, label, snap, op(snap))
    }

    private fun stroke(preset: BrushPreset, x0: Float, y0: Float, x1: Float, y1: Float, seed: Long = 7L): VStroke {
        val n = 30
        return VStroke(
            0, preset = preset, color = 0xFF803010.toInt(), seed = seed, stylus = false,
            points = PackedPoints(FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }, FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) + if (it % 4 == 0) 5f else 0f }, FloatArray(n) { 1f }),
        )
    }

    private fun ellipse(cx: Float, cy: Float) = VShape(
        0, shape = ShapeObject(ShapeType.ELLIPSE, cx = cx, cy = cy, w = 90f, h = 60f, rotation = 15f, style = ShapeStyle.STROKE_FILL, strokeWidth = 4f, strokeColor = 0xFF103080.toInt(), fillColor = 0xFFE0A040.toInt()),
    )

    private val chalk get() = BrushLibrary.byId("chalk")!!
    private val calligraphy get() = BrushLibrary.byId("calligraphy")!!
    private val pen get() = BrushLibrary.defaultBrush

    /** Deletes the last object (a partial re-render of its tiles) and checks the whole cache. */
    private fun assertConsistentAfterAPartialEdit(c: EditorController) {
        val now = c.vec.vector!!
        assertArrayEquals("the cache is the rendering of the objects", fresh(now, c.doc.width, c.doc.height, c.doc.colorMode), px(c.vec.bitmap))
        val fewer = now.without(setOf(now.objects.last().id))
        c.vectors.update(c.vec, fewer, "Delete")
        assertSame(fewer, c.vec.vector)
        assertArrayEquals("no seam after a partial re-render", fresh(fewer, c.doc.width, c.doc.height, c.doc.colorMode), px(c.vec.bitmap))
    }

    @Test
    fun theInvarianceRulesFollowTheBrush() {
        fun of(p: BrushPreset) = VectorContent(objects = listOf(stroke(p, 10f, 10f, 50f, 50f)))
        // A round pen turns and mirrors into itself.
        assertTrue(LayerDataTransforms.turnsExactly(of(pen), 1, mirror = false))
        assertTrue(LayerDataTransforms.turnsExactly(of(pen), 0, mirror = true))
        assertTrue(LayerDataTransforms.shiftsExactly(of(pen), 13, 7))
        // Paper grain is anchored to the document grid.
        assertFalse(LayerDataTransforms.turnsExactly(of(chalk), 2, mirror = false))
        assertFalse(LayerDataTransforms.shiftsExactly(of(chalk), 100, 0))
        assertTrue(LayerDataTransforms.shiftsExactly(of(chalk), 256, -512))
        // A 45° calligraphy nib: a quarter turn changes it, a half turn doesn't, a mirror does.
        assertFalse(LayerDataTransforms.turnsExactly(of(calligraphy), 1, mirror = false))
        assertTrue(LayerDataTransforms.turnsExactly(of(calligraphy), 2, mirror = false))
        assertFalse(LayerDataTransforms.turnsExactly(of(calligraphy), 0, mirror = true))
        assertTrue(LayerDataTransforms.turnsExactly(of(calligraphy.copy(angle = 90f)), 0, mirror = true))
        // A square pixel tip turns into itself; scatter does not.
        assertTrue(LayerDataTransforms.turnsExactly(of(BrushLibrary.byId("pixelpen")!!), 1, mirror = true))
        assertFalse(LayerDataTransforms.turnsExactly(of(pen.copy(scatter = 0.3f)), 0, mirror = true))
        // Plain shapes always do.
        assertTrue(LayerDataTransforms.turnsExactly(VectorContent(objects = listOf(ellipse(30f, 30f))), 1, mirror = true))
    }

    @Test
    fun aQuarterTurnRedrawsGrainAndAngledTipsSoLaterEditsHaveNoSeams() {
        val c = setup(300, 260, stroke(chalk, 20f, 30f, 280f, 200f), stroke(calligraphy, 30f, 220f, 270f, 40f, 3L), ellipse(150f, 130f))
        val pixels = px(c.vec.bitmap)
        commit(c, "Rotate 90° clockwise") { s -> CanvasOps.rotate(s, CanvasRotation.CW_90) }
        assertEquals(260, c.doc.width)
        // Not a pixel remap (the grain and the nib would be turned)...
        val turned = px(c.vec.bitmap)
        var remapped = true
        for (y in 0 until 260) for (x in 0 until 300) if (pixels[y * 300 + x] != turned[x * 260 + (260 - 1 - y)]) { remapped = false }
        assertFalse(remapped)
        // ... but the rendering of the turned objects, which stays seam-free.
        assertConsistentAfterAPartialEdit(c)
        c.undo(); c.undo()
        assertArrayEquals(pixels, px(c.vec.bitmap))
    }

    @Test
    fun aFlipRedrawsAnAngledNib() {
        val c = setup(300, 200, stroke(calligraphy, 20f, 30f, 280f, 170f), ellipse(200f, 60f))
        commit(c, "Flip canvas horizontally") { s -> CanvasOps.flip(s, horizontal = true) }
        assertConsistentAfterAPartialEdit(c)
    }

    @Test
    fun aCanvasSizeOffsetOffTheGrainGridRedrawsGrainButAGridOffsetMovesThePixels() {
        val w = 300; val h = 200
        val c = setup(w, h, stroke(chalk, 20f, 30f, 280f, 170f), ellipse(150f, 100f))
        commit(c, "Canvas size") { s -> CanvasOps.resizeCanvas(s, 400, 200, 100, 0) }
        assertConsistentAfterAPartialEdit(c)
        // A multiple of 256 px: grain stays on its grid, the cache moves exactly (nothing reaches
        // past the old edges).
        val c2 = setup(w, h, stroke(chalk, 60f, 50f, 240f, 140f), ellipse(150f, 100f))
        val p2 = px(c2.vec.bitmap)
        commit(c2, "Canvas size") { s -> CanvasOps.resizeCanvas(s, 556, 200, 256, 0) }
        val g2 = px(c2.vec.bitmap)
        for (y in 0 until h) for (x in 0 until w) assertEquals(p2[y * w + x], g2[y * 556 + x + 256])
    }

    @Test
    fun switchingBackToColorRedrawsVectorLayersInColor() {
        val c = setup(240, 180, stroke(pen.copy(size = 12f), 20f, 30f, 220f, 150f), ellipse(120f, 90f))
        val before = c.vec.vector!!
        val background = c.doc.layers[0]
        background.bitmap.eraseColor(0xFF20A040.toInt())
        commit(c, "Color mode: Grayscale") { s -> CanvasOps.convertColorMode(s, ColorMode.GRAYSCALE) }
        assertSame(before, c.vec.vector)
        assertArrayEquals(fresh(before, 240, 180, ColorMode.GRAYSCALE), px(c.vec.bitmap))
        val grayBackground = background.bitmap
        // Back to color: the pixels "already qualify", but the vector layer is drawn again in color.
        commit(c, "Color mode: RGB") { s -> CanvasOps.convertColorMode(s, ColorMode.RGB) }
        assertEquals(ColorMode.RGB, c.doc.colorMode)
        assertSame(before, c.vec.vector)
        assertSame("raster layers keep their pixels", grayBackground, background.bitmap)
        assertConsistentAfterAPartialEdit(c)
        c.undo(); c.undo()
        assertEquals(ColorMode.GRAYSCALE, c.doc.colorMode)
        assertArrayEquals(fresh(before, 240, 180, ColorMode.GRAYSCALE), px(c.vec.bitmap))
    }

    @Test
    fun aDitheredOneBitConversionHoldsVectorLayersToTheStandardThreshold() {
        val c = setup(240, 180, stroke(BrushLibrary.byId("softround")!!, 20f, 30f, 220f, 150f), ellipse(120f, 90f))
        val raster = c.doc.layers[0]
        Canvas(raster.bitmap).drawColor(0xFF808080.toInt())
        commit(c, "Color mode: 1-bit") { s -> CanvasOps.convertColorMode(s, ColorMode.MONOCHROME, threshold = 100, dither = true) }
        // The raster layer is dithered as asked (both black and white pixels in flat grey)...
        val r = px(raster.bitmap).toSet()
        assertTrue(r.contains(0xFFFFFFFF.toInt()) && r.contains(0xFF000000.toInt()))
        // ... the vector layer is what its later re-renders give: no dithered patches next to them.
        assertConsistentAfterAPartialEdit(c)
        // Mono -> grey (no pixel conversion) draws the vector layer again, anti-aliased.
        commit(c, "Color mode: Grayscale") { s -> CanvasOps.convertColorMode(s, ColorMode.GRAYSCALE) }
        assertNotEquals(0, px(c.vec.bitmap).count { val a = it ushr 24; a in 1..254 })
        assertConsistentAfterAPartialEdit(c)
    }
}
