package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 A1: canvas operations carry vector layers along (§4.9's table). Scaling re-renders them
 * from their mapped objects (crisp, not resampled); translate, quarter turns and flips move the
 * pixels exactly and map the objects; an enlarged canvas re-renders objects that reach into the
 * new area; color-mode changes keep the objects; undo restores data and pixels.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasOpsVectorRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setup(w: Int, h: Int, vararg objects: com.brushwork.paint.vector.VObject): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        if (objects.isNotEmpty()) c.vectors.addObjects(doc.layers[1], objects.toList(), "Add")
        return c
    }

    private val EditorController.vec: Layer get() = doc.layers[1]

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(content: VectorContent, w: Int, h: Int): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    private fun pen(x0: Float, y0: Float, x1: Float, y1: Float, size: Float = 2f): VStroke {
        val n = 20
        return VStroke(
            0, preset = BrushLibrary.defaultBrush.copy(size = size, taperStart = 0f, taperEnd = 0f), color = 0xFF000000.toInt(), seed = 4, stylus = false,
            points = PackedPoints(FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }, FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }, FloatArray(n) { 1f }),
        )
    }

    private fun shape(cx: Float, cy: Float, w: Float = 60f) = VShape(
        0, shape = ShapeObject(ShapeType.ELLIPSE, cx = cx, cy = cy, w = w, h = 40f, rotation = 10f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, strokeColor = 0xFF003060.toInt(), fillColor = 0xFF90C0F0.toInt()),
    )

    private fun commit(c: EditorController, label: String, op: (CanvasSnapshot) -> CanvasResult) {
        c.vectors.flushPending()
        val snap = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, label, snap, op(snap))
    }

    /** Pixels whose alpha is neither 0 nor 255 (anti-aliasing / blur width) along the row [y]. */
    private fun softPixels(p: IntArray, w: Int, y: Int, x0: Int, x1: Int): Int = (x0 until x1).count { x -> (p[y * w + x] ushr 24) in 1..254 }

    @Test
    fun resizingTwiceKeepsAThinPenStrokeCrisp() {
        val w = 200; val h = 150
        // A 2 px vertical line.
        val c = setup(w, h, pen(100f, 10f, 100f, 140f))
        val before = c.vec.vector!!
        // What a resample of the same pixels gives.
        val resampled = CanvasOps.resampleBitmap(c.vec.bitmap, 2 * w, 2 * h, Resample.BILINEAR)
        commit(c, "Resize image") { s -> CanvasOps.resizeImage(s, 2 * w, 2 * h, Resample.BILINEAR) }
        assertEquals(400, c.doc.width)
        val s = c.vec.vector!!.objects.single() as VStroke
        assertEquals("the stroke's size scales with the canvas", 2f, s.sizeScale, 1e-4f)
        assertEquals(200f, s.points.x[0], 1e-3f)
        val got = px(c.vec.bitmap)
        // Re-rendered from the mapped object: exactly a fresh render at the new size.
        assertArrayEquals(fresh(c.vec.vector!!, 2 * w, 2 * h), got)
        // ... and sharper than resampling: fewer half-covered pixels across the line.
        val sharp = softPixels(got, 2 * w, 150, 180, 220)
        val blurry = softPixels(px(resampled), 2 * w, 150, 180, 220)
        assertTrue("re-rendered $sharp soft pixels vs resampled $blurry", sharp < blurry)
        c.undo()
        assertSame(before, c.vec.vector)
        assertEquals(200, c.doc.width)
    }

    @Test
    fun quarterTurnsAndFlipsMoveThePixelsExactlyAndTheObjectsAlong() {
        val w = 240; val h = 160
        val c = setup(w, h, pen(30f, 30f, 200f, 120f, 6f), shape(150f, 60f))
        val before = c.vec.vector!!
        val pixels = px(c.vec.bitmap)
        commit(c, "Rotate 90° clockwise") { s -> CanvasOps.rotate(s, CanvasRotation.CW_90) }
        assertEquals(160, c.doc.width); assertEquals(240, c.doc.height)
        val turned = px(c.vec.bitmap)
        // (x, y) -> (H - 1 - y, x) for pixels.
        for (y in 0 until h) for (x in 0 until w) assertEquals(pixels[y * w + x], turned[x * h + (h - 1 - y)])
        val content = c.vec.vector!!
        assertTrue("a shape turned by 90° stays a shape", content.objects[1] is VShape)
        val st = content.objects[0] as VStroke
        assertEquals(h - 30f, st.points.x[0], 1e-3f); assertEquals(30f, st.points.y[0], 1e-3f)
        assertEquals(1f, st.sizeScale, 1e-5f)
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(pixels, px(c.vec.bitmap))
        commit(c, "Flip canvas horizontally") { s -> CanvasOps.flip(s, horizontal = true) }
        val flipped = px(c.vec.bitmap)
        for (y in 0 until h) for (x in 0 until w) assertEquals(pixels[y * w + x], flipped[y * w + (w - 1 - x)])
        assertEquals(w - 150f, (c.vec.vector!!.objects[1] as VShape).shape.cx, 1e-3f)
        c.undo()
        assertSame(before, c.vec.vector)
    }

    @Test
    fun canvasSizeShiftsThePixelsOrRedrawsObjectsComingIntoView() {
        val w = 200; val h = 150
        // An object reaching past the right edge.
        val c = setup(w, h, shape(180f, 70f, 90f), pen(20f, 20f, 60f, 60f))
        val before = c.vec.vector!!
        // Crop (smaller): the cache moves exactly.
        val pixels = px(c.vec.bitmap)
        commit(c, "Canvas size") { s -> CanvasOps.resizeCanvas(s, 150, 120, -10, -5) }
        val cropped = px(c.vec.bitmap)
        for (y in 0 until 120) for (x in 0 until 150) assertEquals(pixels[(y + 5) * w + (x + 10)], cropped[y * 150 + x])
        assertEquals(170f, (c.vec.vector!!.objects[0] as VShape).shape.cx, 1e-3f)
        c.undo()
        assertSame(before, c.vec.vector)
        // Larger to the right: the ellipse's part beyond the old edge is drawn now.
        commit(c, "Canvas size") { s -> CanvasOps.resizeCanvas(s, 300, 150, 0, 0) }
        val bigger = px(c.vec.bitmap)
        assertArrayEquals(fresh(c.vec.vector!!, 300, 150), bigger)
        assertTrue("the ellipse continues past x = 200", (bigger[70 * 300 + 215] ushr 24) > 0)
        c.undo()
        assertSame(before, c.vec.vector)
        // Larger with nothing beyond the edges (left/top padding): exact shift.
        val c2 = setup(w, h, pen(20f, 20f, 60f, 60f))
        val p2 = px(c2.vec.bitmap)
        commit(c2, "Canvas size") { s -> CanvasOps.resizeCanvas(s, 260, 190, 30, 20) }
        val g2 = px(c2.vec.bitmap)
        for (y in 0 until h) for (x in 0 until w) assertEquals(p2[y * w + x], g2[(y + 20) * 260 + (x + 30)])
        assertNotNull(c2.vec.vector)
    }

    @Test
    fun aFilledBottomVectorLayerBecomesARasterLayer() {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 100, 80)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(100, 80)).also { it.vector = VectorContent.EMPTY }
        val c = EditorController(app, doc, scope, settings)
        c.vectors.addObjects(doc.layers[0], listOf(shape(50f, 40f)), "Add")
        commit(c) { s -> CanvasOps.resizeCanvas(s, 140, 100, 20, 10, fillBottom = 0xFFFFFFFF.toInt()) }
        assertNull("the fill isn't one of its objects", doc.layers[0].vector)
        assertEquals(0xFFFFFFFF.toInt(), doc.layers[0].bitmap.getPixel(2, 2))
        c.undo()
        assertNotNull(doc.layers[0].vector)
    }

    private fun commit(c: EditorController, op: (CanvasSnapshot) -> CanvasResult) = commit(c, "Canvas size", op)

    @Test
    fun colorModeChangesKeepTheObjects() {
        val c = setup(120, 90, shape(60f, 45f))
        val before = c.vec.vector!!
        commit(c, "Color mode: Grayscale") { s -> CanvasOps.convertColorMode(s, ColorMode.GRAYSCALE) }
        assertSame(before, c.vec.vector)
        val p = c.vec.bitmap.getPixel(60, 45)
        assertEquals((p shr 16) and 0xFF, p and 0xFF)
        // Later edits keep the cache the constrained rendering of the objects.
        c.vectors.update(c.vec, before.without(setOf(1L)), "Delete")
        assertTrue(px(c.vec.bitmap).all { it == 0 })
    }

    @Test
    fun theMappedDataIsMadeWithThePixelsAndUndoRestoresIt() {
        val c = setup(160, 120, pen(20f, 20f, 140f, 100f, 4f), VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(10f, 10f), VAnchor(60f, 30f, width = 2f), VAnchor(100f, 10f)))), fill = VPaint.Solid(-16777216)))
        val before = c.vec.vector!!
        val snap = CanvasSnapshot.of(c.doc)
        val result = CanvasOps.resizeImage(snap, 80, 60, Resample.HIGH_QUALITY)
        val mapped = result.layers[1].data!!.vector!!
        assertEquals(0.5f, (mapped.objects[0] as VStroke).sizeScale, 1e-5f)
        val p = mapped.objects[1] as VPath
        assertEquals(30f, p.subpaths[0].anchors[1].x, 1e-4f)
        assertEquals("anchor widths are factors: unchanged", 2f, p.subpaths[0].anchors[1].width)
        CanvasOps.commit(c, "Resize image", snap, result)
        assertSame(mapped, c.vec.vector)
        c.undo()
        assertSame(before, c.vec.vector)
        // Exposure test helper: objects inside the old canvas never count.
        assertTrue(!CanvasOps.exposesObjects(before, 160, 120, 300, 300, 10, 10))
        val wide = before.plus(listOf(VectorOps.transformed(before.objects[0], floatArrayOf(1f, 0f, 100f, 0f, 1f, 0f, 0f, 0f, 1f)))).first
        assertTrue(CanvasOps.exposesObjects(wide, 160, 120, 300, 300, 0, 0))
        assertTrue(!CanvasOps.exposesObjects(wide, 160, 120, 100, 100, 0, 0))
    }
}
